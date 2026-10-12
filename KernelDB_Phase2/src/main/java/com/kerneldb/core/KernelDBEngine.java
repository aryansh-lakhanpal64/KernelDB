package com.kerneldb.core;

import com.kerneldb.buffer.BufferManager;
import com.kerneldb.model.Student;
import com.kerneldb.storage.FileManager;
import com.kerneldb.storage.Page;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * KernelDB's core engine: a custom page file, ID-to-slot index, LRU buffer,
 * CRUD operations, concurrent page reads, and a simple repeatable-read benchmark.
 */
public final class KernelDBEngine implements AutoCloseable {
    public static final int BUFFER_CAPACITY = 32;

    private record Location(int pageNumber, int slotNumber) { }

    private final KernelDBMetrics metrics = new KernelDBMetrics();
    private final FileManager fileManager;
    private final BufferManager bufferManager;
    private final Map<Integer, Location> recordIndex = new HashMap<>();
    private final ReentrantReadWriteLock databaseLock = new ReentrantReadWriteLock(true);
    private int appendPageNumber;
    private int appendSlotNumber;

    public KernelDBEngine(Path databasePath) throws IOException {
        fileManager = new FileManager(databasePath, metrics);
        bufferManager = new BufferManager(fileManager, metrics, BUFFER_CAPACITY);
        rebuildIndex();
        metrics.reset();
    }

    public KernelDBMetrics metrics() { return metrics; }
    public int bufferCapacity() { return bufferManager.capacity(); }
    public Path databasePath() { return fileManager.databasePath(); }

    public int pageCount() throws IOException {
        databaseLock.readLock().lock();
        try { return fileManager.pageCount(); }
        finally { databaseLock.readLock().unlock(); }
    }

    public int recordCount() {
        databaseLock.readLock().lock();
        try { return recordIndex.size(); }
        finally { databaseLock.readLock().unlock(); }
    }

    public int maxStudentId() {
        databaseLock.readLock().lock();
        try { return recordIndex.keySet().stream().mapToInt(Integer::intValue).max().orElse(0); }
        finally { databaseLock.readLock().unlock(); }
    }

    public void insert(Student student) throws IOException {
        databaseLock.writeLock().lock();
        try {
            if (recordIndex.containsKey(student.id())) {
                throw new IllegalArgumentException("Student ID " + student.id() + " already exists.");
            }
            insertUnlocked(student, true);
        } finally {
            databaseLock.writeLock().unlock();
        }
    }

    public Student findById(int id) throws IOException {
        databaseLock.readLock().lock();
        try {
            Location location = recordIndex.get(id);
            if (location == null) return null;
            return bufferManager.getPage(location.pageNumber()).getRecord(location.slotNumber());
        } finally {
            databaseLock.readLock().unlock();
        }
    }

    public void update(Student replacement) throws IOException {
        databaseLock.writeLock().lock();
        try {
            Location location = recordIndex.get(replacement.id());
            if (location == null) throw new IllegalArgumentException("Student ID " + replacement.id() + " was not found.");
            Page page = bufferManager.getPage(location.pageNumber());
            page.setRecord(location.slotNumber(), replacement);
            bufferManager.markDirty(location.pageNumber());
            bufferManager.flushPage(location.pageNumber());
        } finally {
            databaseLock.writeLock().unlock();
        }
    }

    public void delete(int id) throws IOException {
        databaseLock.writeLock().lock();
        try {
            Location location = recordIndex.get(id);
            if (location == null) throw new IllegalArgumentException("Student ID " + id + " was not found.");
            Page page = bufferManager.getPage(location.pageNumber());
            page.setRecord(location.slotNumber(), null);
            recordIndex.remove(id);
            bufferManager.markDirty(location.pageNumber());
            bufferManager.flushPage(location.pageNumber());
        } finally {
            databaseLock.writeLock().unlock();
        }
    }

    public List<Student> listStudents() throws IOException {
        databaseLock.readLock().lock();
        try {
            List<Student> result = new ArrayList<>(recordIndex.size());
            int pages = fileManager.pageCount();
            for (int pageNumber = 0; pageNumber < pages; pageNumber++) {
                result.addAll(bufferManager.getPage(pageNumber).records());
            }
            result.sort(Comparator.comparingInt(Student::id));
            return result;
        } finally {
            databaseLock.readLock().unlock();
        }
    }

    /** Imports unique IDs as one batch and flushes dirty pages at the end. */
    public ImportResult importStudents(List<Student> students) throws IOException {
        databaseLock.writeLock().lock();
        try {
            int inserted = 0;
            int skipped = 0;
            for (Student student : students) {
                if (recordIndex.containsKey(student.id())) {
                    skipped++;
                    continue;
                }
                insertUnlocked(student, false);
                inserted++;
            }
            bufferManager.flushAll();
            return new ImportResult(inserted, skipped);
        } finally {
            databaseLock.writeLock().unlock();
        }
    }

    /** Exercises repeated reads with and without the buffer over the same page sequence. */
    public BenchmarkResult runBenchmark(int repeats) throws IOException {
        if (repeats < 1) throw new IllegalArgumentException("Repeats must be at least one.");
        databaseLock.writeLock().lock();
        try {
            int pagesOnDisk = fileManager.pageCount();
            if (pagesOnDisk == 0) throw new IllegalStateException("Import or create some student data before running the benchmark.");
            int workingSet = Math.min(Math.min(8, bufferManager.capacity()), pagesOnDisk);

            bufferManager.clearCache();
            metrics.reset();
            long baselineStart = System.nanoTime();
            for (int round = 0; round < repeats; round++) {
                for (int page = 0; page < workingSet; page++) fileManager.readPage(page);
            }
            long baselineMillis = elapsedMillis(baselineStart);
            long baselineReads = metrics.fileReadCalls();

            bufferManager.clearCache();
            metrics.reset();
            long bufferedStart = System.nanoTime();
            for (int round = 0; round < repeats; round++) {
                for (int page = 0; page < workingSet; page++) bufferManager.getPage(page);
            }
            long bufferedMillis = elapsedMillis(bufferedStart);
            return new BenchmarkResult(workingSet, repeats, baselineMillis, baselineReads,
                    bufferedMillis, metrics.fileReadCalls(), metrics.cacheHits(), metrics.cacheMisses());
        } finally {
            databaseLock.writeLock().unlock();
        }
    }

    /** Makes multiple worker threads request a shared set of pages concurrently. */
    public ConcurrentTestResult runConcurrentTest(int workerCount, int requestsPerWorker) throws Exception {
        if (workerCount < 1 || workerCount > 32) throw new IllegalArgumentException("Worker count must be between 1 and 32.");
        if (requestsPerWorker < 1) throw new IllegalArgumentException("Requests per worker must be at least one.");
        int pageCount = pageCount();
        if (pageCount == 0) throw new IllegalStateException("Import some student data before running the worker test.");

        int workingSet = Math.min(4, pageCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("KernelDB-Worker-" + thread.threadId());
            thread.setDaemon(false);
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        long startNanos = System.nanoTime();
        try {
            for (int worker = 0; worker < workerCount; worker++) {
                final int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int request = 0; request < requestsPerWorker; request++) {
                            int pageNumber = (request + workerIndex) % workingSet;
                            databaseLock.readLock().lock();
                            try {
                                bufferManager.getPage(pageNumber);
                                metrics.workerPageRequest();
                            } finally {
                                databaseLock.readLock().unlock();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Worker was interrupted.", ex);
                    } catch (IOException ex) {
                        throw new IllegalStateException("Worker could not read a page.", ex);
                    }
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not become ready in time.");
            startNanos = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) future.get();
            return new ConcurrentTestResult(workerCount, requestsPerWorker,
                    (long) workerCount * requestsPerWorker, elapsedMillis(startNanos));
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    public String[] cachedPageLabels() { return bufferManager.cachedPageLabels(); }

    public void clearBufferAndResetMetrics() throws IOException {
        databaseLock.writeLock().lock();
        try {
            bufferManager.clearCache();
            metrics.reset();
        } finally {
            databaseLock.writeLock().unlock();
        }
    }

    public void flush() throws IOException {
        databaseLock.writeLock().lock();
        try { bufferManager.flushAll(); }
        finally { databaseLock.writeLock().unlock(); }
    }

    private void insertUnlocked(Student student, boolean flushImmediately) throws IOException {
        while (true) {
            Page page = bufferManager.getPage(appendPageNumber);
            int slot = page.findEmptySlotFrom(appendSlotNumber);
            if (slot < 0) {
                appendPageNumber++;
                appendSlotNumber = 0;
                continue;
            }
            page.setRecord(slot, student);
            bufferManager.markDirty(appendPageNumber);
            recordIndex.put(student.id(), new Location(appendPageNumber, slot));
            appendSlotNumber = slot + 1;
            if (appendSlotNumber >= Page.SLOTS_PER_PAGE) {
                appendPageNumber++;
                appendSlotNumber = 0;
            }
            if (flushImmediately) bufferManager.flushPage(page.pageNumber());
            return;
        }
    }

    private void rebuildIndex() throws IOException {
        int pages = fileManager.pageCount();
        int lastPageWithRecords = -1;
        int highestSlotOnLastPage = -1;
        for (int pageNumber = 0; pageNumber < pages; pageNumber++) {
            Page page = bufferManager.getPage(pageNumber);
            for (int slot = 0; slot < Page.SLOTS_PER_PAGE; slot++) {
                Student student = page.getRecord(slot);
                if (student != null) {
                    Location previous = recordIndex.put(student.id(), new Location(pageNumber, slot));
                    if (previous != null) throw new IOException("Duplicate student ID " + student.id() + " found in the database file.");
                    lastPageWithRecords = pageNumber;
                    highestSlotOnLastPage = slot;
                }
            }
        }
        if (lastPageWithRecords < 0) {
            appendPageNumber = pages;
            appendSlotNumber = 0;
        } else {
            appendPageNumber = lastPageWithRecords;
            appendSlotNumber = highestSlotOnLastPage + 1;
            if (appendSlotNumber >= Page.SLOTS_PER_PAGE) {
                appendPageNumber++;
                appendSlotNumber = 0;
            }
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @Override
    public void close() throws IOException {
        databaseLock.writeLock().lock();
        try {
            bufferManager.flushAll();
            fileManager.close();
        } finally {
            databaseLock.writeLock().unlock();
        }
    }
}
