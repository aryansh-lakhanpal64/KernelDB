package com.kerneldb.core;

import com.kerneldb.model.Course;
import com.kerneldb.model.DatabaseState;
import com.kerneldb.model.Enrollment;
import com.kerneldb.model.JoinedEnrollment;
import com.kerneldb.model.Student;
import com.kerneldb.storage.StorageManager;
import com.kerneldb.storage.PageBufferManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Small file-backed relational engine. Transactions work on private snapshots,
 * validate constraints, then commit under a fair state lock. Resource locks
 * are acquired in sorted order and retained through commit (strict 2PL style).
 */
public final class KernelDatabase {
    private final StorageManager storage;
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);
    private final Map<String, ReentrantLock> resourceLocks = new ConcurrentHashMap<>();
    private final AtomicLong commits = new AtomicLong();
    private final AtomicLong rollbacks = new AtomicLong();
    private final AtomicLong lockAcquisitions = new AtomicLong();
    private final AtomicLong totalWaitNanos = new AtomicLong();
    private final AtomicLong maxWaitNanos = new AtomicLong();
    private final AtomicLong conflictRetries = new AtomicLong();

    private DatabaseState state;
    private long version;
    private long sequence;
    private volatile String lastRecoveryMessage = "Database initialized.";

    public KernelDatabase(Path dataDirectory) throws IOException {
        storage = new StorageManager(dataDirectory);
        StorageManager.LoadResult loaded = storage.load();
        state = loaded.state();
        sequence = loaded.sequence();
        lastRecoveryMessage = loaded.message();
    }

    /** A transaction either commits the complete candidate state or leaves the database unchanged. */
    public TransactionResult transact(String name, Collection<String> resourceKeys,
                                      Consumer<DatabaseState> mutation) throws Exception {
        long started = System.nanoTime();
        List<ReentrantLock> held = new ArrayList<>();
        List<String> keys = resourceKeys == null ? List.of("database") : resourceKeys.stream()
                .filter(k -> k != null && !k.isBlank()).distinct().sorted().toList();
        if (keys.isEmpty()) keys = List.of("database");
        long lockWait = 0;
        long committedSequence = -1;
        try {
            // Sorting keys prevents circular-wait patterns; fair locks reduce barging.
            for (String key : keys) {
                ReentrantLock lock = resourceLocks.computeIfAbsent(key, ignored -> new ReentrantLock(true));
                long before = System.nanoTime();
                lock.lockInterruptibly();
                long waited = System.nanoTime() - before;
                lockWait += waited;
                totalWaitNanos.addAndGet(waited);
                maxWaitNanos.accumulateAndGet(waited, Math::max);
                lockAcquisitions.incrementAndGet();
                held.add(lock);
            }

            DatabaseState candidate;
            long baseVersion;
            stateLock.readLock().lock();
            try {
                candidate = state.copy();
                baseVersion = version;
            } finally {
                stateLock.readLock().unlock();
            }
            mutation.accept(candidate);
            candidate.validate();

            stateLock.writeLock().lock();
            try {
                // Another non-conflicting transaction may have committed while this one prepared.
                // Reapply the deterministic mutation to the newest snapshot so unrelated writes aren't lost.
                if (version != baseVersion) {
                    conflictRetries.incrementAndGet();
                    candidate = state.copy();
                    mutation.accept(candidate);
                    candidate.validate();
                }
                long nextSequence = sequence + 1;
                storage.commit(nextSequence, candidate); // WAL is forced before durable table snapshots.
                state = candidate;
                sequence = nextSequence;
                committedSequence = nextSequence;
                version++;
                commits.incrementAndGet();
            } finally {
                stateLock.writeLock().unlock();
            }
            return new TransactionResult(name, committedSequence, (System.nanoTime() - started) / 1_000_000,
                    lockWait / 1_000_000);
        } catch (Exception | Error ex) {
            rollbacks.incrementAndGet();
            throw ex;
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
        }
    }

    public DatabaseState readSnapshot() {
        stateLock.readLock().lock();
        try { return state.copy(); }
        finally { stateLock.readLock().unlock(); }
    }

    public List<Student> students() {
        return readSnapshot().students().values().stream().sorted(Comparator.comparingInt(Student::id)).toList();
    }
    public List<Course> courses() {
        return readSnapshot().courses().values().stream().sorted(Comparator.comparingInt(Course::id)).toList();
    }
    public List<Enrollment> enrollments() {
        return readSnapshot().enrollments().values().stream().sorted(Comparator.comparingInt(Enrollment::id)).toList();
    }

    public List<JoinedEnrollment> joinedEnrollments(String requiredGrade) {
        DatabaseState snap = readSnapshot();
        String filter = requiredGrade == null ? "" : requiredGrade.trim().toUpperCase(Locale.ROOT);
        List<JoinedEnrollment> rows = new ArrayList<>();
        for (Enrollment e : snap.enrollments().values()) {
            if (!filter.isEmpty() && !e.grade().equalsIgnoreCase(filter)) continue;
            Student s = snap.students().get(e.studentId());
            Course c = snap.courses().get(e.courseId());
            if (s != null && c != null) rows.add(new JoinedEnrollment(e.id(), s.name(), c.title(), e.semester(), e.grade()));
        }
        rows.sort(Comparator.comparing(JoinedEnrollment::studentName).thenComparing(JoinedEnrollment::courseTitle));
        return rows;
    }

    public void seedDemoData() throws Exception {
        if (!readSnapshot().students().isEmpty()) return;
        transact("Seed normalized demo dataset", List.of("seed:dataset"), s -> {
            String[][] studentData = {
                    {"Aarav Mehta", "aarav@demo.kdb"}, {"Diya Sharma", "diya@demo.kdb"},
                    {"Kabir Singh", "kabir@demo.kdb"}, {"Anaya Verma", "anaya@demo.kdb"},
                    {"Ishaan Gupta", "ishaan@demo.kdb"}, {"Sara Khan", "sara@demo.kdb"},
                    {"Vivaan Joshi", "vivaan@demo.kdb"}, {"Myra Kapoor", "myra@demo.kdb"}
            };
            for (String[] row : studentData) {
                int id = s.nextStudentId();
                s.students().put(id, new Student(id, row[0], row[1]));
            }
            String[][] courseData = {
                    {"CS201", "Database Systems", "4"}, {"CS202", "Operating Systems", "4"},
                    {"CS203", "Computer Networks", "3"}, {"CS204", "System Design", "3"}
            };
            for (String[] row : courseData) {
                int id = s.nextCourseId();
                s.courses().put(id, new Course(id, row[0], row[1], Integer.parseInt(row[2])));
            }
            String[] grades = {"A", "B+", "A+", "B", "A", "C", "B+", "A"};
            for (int studentId = 1; studentId <= 8; studentId++) {
                int courseId = ((studentId - 1) % 4) + 1;
                int id = s.nextEnrollmentId();
                s.enrollments().put(id, new Enrollment(id, studentId, courseId, "2026-Semester-1", grades[studentId - 1]));
                int secondCourseId = (courseId % 4) + 1;
                id = s.nextEnrollmentId();
                s.enrollments().put(id, new Enrollment(id, studentId, secondCourseId, "2026-Semester-1", grades[(studentId + 2) % grades.length]));
            }
        });
    }

    /** Restore the last valid committed state by replaying the WAL, simulating restart recovery. */
    public String recoverFromWal() throws IOException {
        stateLock.writeLock().lock();
        try {
            StorageManager.LoadResult loaded = storage.load();
            state = loaded.state();
            sequence = Math.max(sequence, loaded.sequence());
            version++;
            lastRecoveryMessage = loaded.message();
            return lastRecoveryMessage;
        } finally { stateLock.writeLock().unlock(); }
    }

    public List<String> walTail(int count) throws IOException { return storage.recentWalLines(count); }
    public byte[] requestPage(int pageIndex) throws IOException { return storage.requestPage(pageIndex); }
    public PageBufferManager.Metrics pageMetrics() throws IOException { return storage.pageMetrics(); }
    public void resetPageMetrics() { storage.resetPageMetrics(); }
    public String dataDirectory() { return storage.root().toString(); }
    public String lastRecoveryMessage() { return lastRecoveryMessage; }
    public String storageWarning() { return storage.lastMaintenanceWarning(); }
    public long commitCount() { return commits.get(); }
    public long rollbackCount() { return rollbacks.get(); }
    public long lockAcquisitionCount() { return lockAcquisitions.get(); }
    public long conflictRetryCount() { return conflictRetries.get(); }
    public long totalLockWaitMillis() { return totalWaitNanos.get() / 1_000_000; }
    public long maxSingleLockWaitMillis() { return maxWaitNanos.get() / 1_000_000; }
    public long currentSequence() {
        stateLock.readLock().lock();
        try { return sequence; }
        finally { stateLock.readLock().unlock(); }
    }
}
