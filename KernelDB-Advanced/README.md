# KernelDB — OS–DBMS Transaction & Concurrency Lab

**Technology:** Java 21, JavaFX, Maven, custom local database files. No MySQL/JDBC is required.

KernelDB is an educational, file-backed relational engine for demonstrating the connection between DBMS transaction processing and OS-level concurrency/synchronization. It is a teaching prototype, not a production database.

## Start on Windows / VS Code

1. Extract the ZIP to a simple path, for example `C:\GEU\KernelDB-Advanced`.
2. Open that folder in VS Code.
3. Confirm that JDK 21 and Maven are available in the VS Code terminal:
   ```powershell
   java -version
   mvn -version
   ```
4. Run `RUN_PROJECT.bat`, or run this in the project directory:
   ```powershell
   mvn -U clean javafx:run
   ```
5. Maven needs internet access on the first run to download JavaFX and Maven dependencies.

Your earlier setup used Java 21 Temurin. If VS Code uses a different JDK than Windows Terminal, set `JAVA_HOME` and PATH in the same terminal before running Maven.

## What to demonstrate

1. **Relations & CRUD:** inspect Student, Course, Enrollment; add a student and course; register an enrollment; change a grade; delete a student and related enrollments atomically.
2. **Normalization & JOIN:** explain the three relations, keys, foreign keys and the join output. Select a grade and run `Student ⋈ Enrollment ⋈ Course`.
3. **ACID Transaction Lab:** run a valid multi-table commit; run the deliberate foreign-key rollback; trigger the duplicate-email constraint.
4. **Concurrency & Starvation:** run four real background workers that perform temporary database inserts, then compare fair and non-fair lock measurements.
5. **Page Buffer / LRU:** click “Run 4 random page-request workers” and then “Run 3-pass page I/O benchmark”. Show cache hits, misses, FileChannel page reads, hit rate, and evictions.
6. **WAL Recovery & Metrics:** inspect recent commit records and press “Simulate restart: replay WAL”. Explain that this replays saved committed state; it does not terminate the Java process.

## Database files

The app creates a `data` folder relative to the directory where Maven is launched:

- `kernel.wal`: append-only durable commit records. Each record holds a recoverable encoded state snapshot.
- `kernel.snapshot`: most recent state snapshot.
- `students.tbl`, `courses.tbl`, `enrollments.tbl`: separate table files with Base64-encoded text fields.
- `kernel.pages`: fixed 4096-byte page images. The page lab uses a 32-entry LRU buffer.

Do not delete the `data` folder during a live demo. To reset the entire demo dataset, close the app, back up `data`, then remove the folder and relaunch. The app seeds demo records on first initialization only.

## Architecture

```text
JavaFX tabs
   ↓
KernelDatabase.transact(name, resourceKeys, mutation)
   ↓
Sorted fair resource locks (strict-2PL-style lock lifetime)
   ↓
Private DatabaseState copy → constraint validation
   ↓
Fair read/write state lock → conflict re-application if the state changed
   ↓
WAL COMMIT record (FileChannel.force) → table/snapshot replacement
   ↓
students.tbl / courses.tbl / enrollments.tbl
```

## Honest scope notes

- The join tab executes a built-in relational join over the engine's relations; this is not a general SQL parser.
- WAL entries contain full state snapshots to make the learning demo easy to inspect. This is redo-style recovery at teaching scale; log compaction and checkpoints are future work.
- The page lab stores 4 KB page images and uses a bounded LRU buffer for explicit page requests. Normal relation browsing still uses the current in-memory relation state, and commits rebuild the page image file; this is not yet a fully page-addressed production storage engine.
- The fairness lab measures a finite run; it cannot mathematically prove that starvation never occurs.
- Java's `ReentrantReadWriteLock` and fair `ReentrantLock` provide synchronization mechanisms, but this academic prototype is not a fully isolated, production-grade DBMS.
- Capture actual run results for the report. Do not invent speedups or claim fault-tolerance experiments that were not performed.
