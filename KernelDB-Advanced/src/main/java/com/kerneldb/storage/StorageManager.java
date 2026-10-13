package com.kerneldb.storage;

import com.kerneldb.model.Course;
import com.kerneldb.model.DatabaseState;
import com.kerneldb.model.Enrollment;
import com.kerneldb.model.Student;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Owns the custom KernelDB files and durable write-ahead commit log. */
public final class StorageManager {
    private final Path root;
    private final Path walFile;
    private final Path snapshotFile;
    private final PageBufferManager pageBuffer;
    private volatile String lastMaintenanceWarning = "";

    public StorageManager(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.walFile = this.root.resolve("kernel.wal");
        this.snapshotFile = this.root.resolve("kernel.snapshot");
        this.pageBuffer = new PageBufferManager(this.root.resolve("kernel.pages"));
        Files.createDirectories(this.root);
    }

    public record LoadResult(DatabaseState state, long sequence, boolean restoredFromWal, String message) { }

    public synchronized LoadResult load() throws IOException {
        long latestSequence = 0;
        DatabaseState latest = null;
        boolean foundWalCommit = false;
        if (Files.exists(walFile)) {
            List<String> lines = Files.readAllLines(walFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] p = line.split("\\t", 3);
                if (p.length != 3 || !"COMMIT".equals(p[0])) continue;
                try {
                    long seq = Long.parseLong(p[1]);
                    if (seq < latestSequence) continue;
                    String stateText = new String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8);
                    DatabaseState decoded = DatabaseState.decode(stateText);
                    latest = decoded;
                    latestSequence = seq;
                    foundWalCommit = true;
                } catch (RuntimeException ignored) {
                    // A malformed or partially written final WAL line is ignored; earlier commits remain usable.
                }
            }
        }
        if (latest == null && Files.exists(snapshotFile)) {
            try {
                latest = DatabaseState.decode(Files.readString(snapshotFile, StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                throw new IOException("KernelDB snapshot is corrupt and no valid WAL commit was found.", ex);
            }
        }
        if (latest == null) latest = new DatabaseState();
        latest.validate();
        persistTableFiles(latest);
        writeAtomic(snapshotFile, latest.encode());
        persistPages(latest);
        String message = foundWalCommit
                ? "Recovery replayed the latest valid committed state from kernel.wal (sequence " + latestSequence + ")."
                : "Loaded snapshot/new database; no committed WAL record needed replay.";
        return new LoadResult(latest, latestSequence, foundWalCommit, message);
    }

    /** The commit record is forced to disk before table snapshots are updated (write-ahead rule). */
    public synchronized void commit(long sequence, DatabaseState state) throws IOException {
        state.validate();
        String payload = Base64.getEncoder().encodeToString(state.encode().getBytes(StandardCharsets.UTF_8));
        byte[] row = ("COMMIT\t" + sequence + "\t" + payload + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(row);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // Once this forced WAL record exists, the transaction is durably committed.
        // Relation snapshots are derived copies; if refreshing them fails, startup WAL replay repairs them.
        try {
            writeAtomic(snapshotFile, state.encode());
            persistTableFiles(state);
            persistPages(state);
            lastMaintenanceWarning = "";
        } catch (IOException ex) {
            pageBuffer.clearCacheAndResetMetrics();
            lastMaintenanceWarning = "WAL commit is durable, but refreshing derived snapshots/pages failed: " + ex.getMessage();
        }
    }

    public String lastMaintenanceWarning() { return lastMaintenanceWarning; }
    public synchronized byte[] requestPage(int pageIndex) throws IOException { return pageBuffer.readPage(pageIndex); }
    public synchronized PageBufferManager.Metrics pageMetrics() throws IOException { return pageBuffer.metrics(); }
    public synchronized void resetPageMetrics() { pageBuffer.clearCacheAndResetMetrics(); }

    public synchronized List<String> recentWalLines(int limit) throws IOException {
        if (!Files.exists(walFile)) return List.of("kernel.wal has not been created yet.");
        List<String> all = Files.readAllLines(walFile, StandardCharsets.UTF_8);
        int start = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(start, all.size()).stream().map(line -> {
            String[] p = line.split("\\t", 3);
            if (p.length >= 2 && "COMMIT".equals(p[0])) return "COMMIT record | sequence=" + p[1] + " | durable snapshot payload present";
            return "Ignored/incomplete WAL line";
        }).toList();
    }

    public Path root() { return root; }

    private void persistTableFiles(DatabaseState state) throws IOException {
        StringBuilder students = new StringBuilder("student_id\tname_base64\temail_base64\n");
        for (Student s : state.students().values()) {
            students.append(s.id()).append('\t').append(b64(s.name())).append('\t').append(b64(s.email())).append('\n');
        }
        StringBuilder courses = new StringBuilder("course_id\tcode_base64\ttitle_base64\tcredits\n");
        for (Course c : state.courses().values()) {
            courses.append(c.id()).append('\t').append(b64(c.code())).append('\t')
                    .append(b64(c.title())).append('\t').append(c.credits()).append('\n');
        }
        StringBuilder enrollments = new StringBuilder("enrollment_id\tstudent_id\tcourse_id\tsemester_base64\tgrade_base64\n");
        for (Enrollment e : state.enrollments().values()) {
            enrollments.append(e.id()).append('\t').append(e.studentId()).append('\t').append(e.courseId()).append('\t')
                    .append(b64(e.semester())).append('\t').append(b64(e.grade())).append('\n');
        }
        writeAtomic(root.resolve("students.tbl"), students.toString());
        writeAtomic(root.resolve("courses.tbl"), courses.toString());
        writeAtomic(root.resolve("enrollments.tbl"), enrollments.toString());
    }

    private void persistPages(DatabaseState state) throws IOException {
        List<byte[]> pages = new ArrayList<>();
        List<String> studentRows = new ArrayList<>();
        for (Student s : state.students().values()) {
            studentRows.add("S\t" + s.id() + "\t" + b64(s.name()) + "\t" + b64(s.email()));
        }
        List<String> courseRows = new ArrayList<>();
        for (Course c : state.courses().values()) {
            courseRows.add("C\t" + c.id() + "\t" + b64(c.code()) + "\t" + b64(c.title()) + "\t" + c.credits());
        }
        List<String> enrollmentRows = new ArrayList<>();
        for (Enrollment e : state.enrollments().values()) {
            enrollmentRows.add("E\t" + e.id() + "\t" + e.studentId() + "\t" + e.courseId()
                    + "\t" + b64(e.semester()) + "\t" + b64(e.grade()));
        }
        appendPages(pages, "STUDENT", studentRows);
        appendPages(pages, "COURSE", courseRows);
        appendPages(pages, "ENROLLMENT", enrollmentRows);
        pageBuffer.writePages(pages);
    }

    /** Packs up to eight row records into each fixed 4 KB page for this teaching prototype. */
    private static void appendPages(List<byte[]> output, String table, List<String> rows) throws IOException {
        int total = Math.max(1, (rows.size() + 7) / 8);
        for (int pageNo = 0; pageNo < total; pageNo++) {
            StringBuilder text = new StringBuilder("KERNELDB_PAGE_V1\tTABLE=").append(table)
                    .append("\tPAGE=").append(output.size()).append("\n");
            int from = pageNo * 8;
            int to = Math.min(rows.size(), from + 8);
            for (int i = from; i < to; i++) text.append(rows.get(i)).append('\n');
            byte[] payload = text.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > PageBufferManager.PAGE_SIZE) {
                throw new IOException("A page group exceeded 4096 bytes; shorten unusually long row fields.");
            }
            byte[] page = new byte[PageBufferManager.PAGE_SIZE];
            System.arraycopy(payload, 0, page, 0, payload.length);
            output.add(page);
        }
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
