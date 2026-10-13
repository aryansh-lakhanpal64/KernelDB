package com.kerneldb.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed 4 KB page file plus a bounded, access-order LRU buffer pool. */
public final class PageBufferManager {
    public static final int PAGE_SIZE = 4096;
    public static final int DEFAULT_CAPACITY = 32;
    private final Path pageFile;
    private final int capacity;
    private final LinkedHashMap<Integer, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong filePageReads = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public PageBufferManager(Path pageFile) {
        this(pageFile, DEFAULT_CAPACITY);
    }

    public PageBufferManager(Path pageFile, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Buffer capacity must be at least one page.");
        this.pageFile = pageFile;
        this.capacity = capacity;
    }

    /** Replaces the page file. Each element is exactly one fixed-size page. */
    public synchronized void writePages(List<byte[]> pages) throws IOException {
        Path temp = pageFile.resolveSibling(pageFile.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (byte[] page : pages) {
                if (page.length != PAGE_SIZE) throw new IOException("Every stored page must be exactly 4096 bytes.");
                ByteBuffer buffer = ByteBuffer.wrap(page);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, pageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, pageFile, StandardCopyOption.REPLACE_EXISTING);
        }
        cache.clear();
    }

    /** Read one page through the LRU. A cache miss performs a FileChannel page read. */
    public synchronized byte[] readPage(int pageIndex) throws IOException {
        int pages = pageCount();
        if (pageIndex < 0 || pageIndex >= pages) {
            throw new IndexOutOfBoundsException("Page " + pageIndex + " is outside the page file (pages=" + pages + ").");
        }
        requests.incrementAndGet();
        byte[] found = cache.get(pageIndex);
        if (found != null) {
            hits.incrementAndGet();
            return found.clone();
        }
        misses.incrementAndGet();
        byte[] loaded = new byte[PAGE_SIZE];
        try (FileChannel channel = FileChannel.open(pageFile, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(loaded);
            long position = (long) pageIndex * PAGE_SIZE;
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer, position + buffer.position());
                if (count < 0) break;
                if (count == 0) break;
            }
        }
        filePageReads.incrementAndGet();
        if (cache.size() >= capacity) {
            Integer leastRecentlyUsed = cache.keySet().iterator().next();
            cache.remove(leastRecentlyUsed);
            evictions.incrementAndGet();
        }
        cache.put(pageIndex, loaded);
        return loaded.clone();
    }

    public synchronized int pageCount() throws IOException {
        if (!Files.exists(pageFile)) return 0;
        long size = Files.size(pageFile);
        if (size % PAGE_SIZE != 0) throw new IOException("Page file size is not aligned to 4096-byte page boundaries.");
        return (int) (size / PAGE_SIZE);
    }

    public synchronized void clearCacheAndResetMetrics() {
        cache.clear();
        requests.set(0); hits.set(0); misses.set(0); filePageReads.set(0); evictions.set(0);
    }

    public synchronized Metrics metrics() throws IOException {
        return new Metrics(pageCount(), cache.size(), capacity, requests.get(), hits.get(), misses.get(),
                filePageReads.get(), evictions.get());
    }

    public record Metrics(int pagesOnDisk, int pagesInBuffer, int capacity, long requests,
                          long hits, long misses, long filePageReads, long evictions) {
        public double hitRatePercent() {
            return requests == 0 ? 0.0 : (100.0 * hits / requests);
        }
    }

    /** Small helper to read the ASCII header of a cached page for the UI/log. */
    public String pageHeader(int pageIndex) throws IOException {
        byte[] data = readPage(pageIndex);
        String text = new String(data, StandardCharsets.UTF_8);
        int zero = text.indexOf('\0');
        if (zero >= 0) text = text.substring(0, zero);
        int end = text.indexOf('\n');
        return end >= 0 ? text.substring(0, end) : text;
    }
}
