# Code Walkthrough (Beginner Friendly)

## 1. `MainApp.java` — the visible application

This file builds the five JavaFX tabs. The interface is only the front end; the data and transaction rules live in the core classes.

- **Relations & CRUD:** input fields collect student/course/enrollment values. When a button is clicked, it calls `db.transact(...)`; it never writes to `.tbl` files directly.
- **Normalization & JOIN:** displays the schema and runs `db.joinedEnrollments(grade)`. The join follows each enrollment's student ID and course ID to look up the related rows.
- **ACID Transaction Lab:** three buttons create a valid three-table commit, intentionally stage a bad foreign-key record to test rollback, and intentionally attempt a duplicate email.
- **Concurrency & Starvation:** starts real background Java threads for database inserts, random requests against the shared page cache, and fair/non-fair lock experiments; JavaFX `Platform.runLater(...)` updates the GUI from background tasks.
- **WAL Recovery & Metrics:** calls `db.recoverFromWal()` and displays recent log records, current counters, and the storage directory.

## 2. `Student.java`, `Course.java`, `Enrollment.java`

These are Java `record` classes: small immutable row types.

- `Student(id, name, email)` represents a row in STUDENT.
- `Course(id, code, title, credits)` represents a row in COURSE.
- `Enrollment(id, studentId, courseId, semester, grade)` connects a student with a course. `studentId` and `courseId` are foreign-key references.

Separating them demonstrates the normalized schema. It avoids repeating student email and course title in every enrollment row.

## 3. `DatabaseState.java` — relations and constraints

The class stores three linked maps in memory, one per relation.

- `copy()` makes a working snapshot before a transaction tries to change anything.
- `nextStudentId()`, `nextCourseId()`, `nextEnrollmentId()` choose the next positive identifier for the relevant table.
- `validate()` checks row IDs, required values, email/course-code uniqueness, allowed credits and grades, foreign keys, and uniqueness of the student-course-semester combination.
- `encode()` converts the state to a line format. Text fields are Base64 encoded so embedded separators/newlines do not corrupt the log format.
- `decode()` reconstructs and validates state during startup/recovery.

The validation is the main consistency gate: a candidate state with an invalid foreign key is rejected before it becomes the committed state.

## 4. `KernelDatabase.java` — transaction engine

`transact(name, resourceKeys, mutation)` is the central method.

1. Sorts the declared resource keys.
2. Acquires fair `ReentrantLock` locks for those keys in sorted order. Sorting helps avoid circular-wait deadlocks among those locks.
3. Copies the current database state under a fair read lock.
4. Applies the requested mutation to that private candidate.
5. Calls `candidate.validate()`.
6. Acquires the fair write lock. If a different transaction committed since the candidate was copied, it rebuilds from the latest state and re-applies the deterministic mutation, avoiding overwriting a newer unrelated commit.
7. Calls `StorageManager.commit(...)` to write a durable WAL commit and refresh the on-disk table snapshots.
8. Publishes the candidate as the in-memory committed state and advances the sequence.
9. Releases the state lock and then releases resource locks in reverse order.

If mutation or validation fails before commit, the candidate is discarded and the rollback counter increments. This is how the project demonstrates atomicity and consistency. The locking pattern is described as strict-2PL-style for the declared resource keys; the prototype is not a complete general-purpose SQL lock manager.

`joinedEnrollments(grade)` demonstrates relational JOIN: each Enrollment points to a Student and Course, so the method combines the related fields and can filter by grade.

## 5. `StorageManager.java` — custom local persistence

- `kernel.wal` is append-only. A COMMIT record contains a sequence number and an encoded state snapshot.
- `FileChannel.force(true)` requests that the WAL commit record be forced to storage before table snapshots are rewritten.
- `kernel.snapshot` stores the newest encoded state snapshot.
- `students.tbl`, `courses.tbl`, and `enrollments.tbl` store the separate relations in readable row-oriented files with encoded text fields.
- `kernel.pages` stores 4096-byte page images. Each page has a table header and up to eight encoded row entries in this teaching design.
- `PageBufferManager.java` keeps an access-order `LinkedHashMap` with a maximum of 32 page images. A hit returns a copy from RAM; a miss performs a `FileChannel` read and puts the page in the LRU. If all 32 slots are used, the least recently used page is removed before the new page is inserted.
- Atomic file replacement uses a temporary file plus move where supported.
- `load()` scans valid WAL records and chooses the latest valid commit; then it rewrites the snapshot and table files from the recovered state.

This is a deliberately straightforward redo-recovery design. Full-state WAL records are easier to understand but make the log grow, which is why checkpointing and log compaction are Phase 3 work.

## 6. `PageBufferManager.java` — page cache and LRU

The page file uses fixed 4096-byte blocks. `readPage(index)` validates the page index, increments the request counter, and checks the access-order LRU cache. A hit increments the hit counter. A miss increments the miss counter, opens the page file using `FileChannel`, reads the selected 4096-byte block, and caches it. The lab reports file-page reads, hit rate, misses, and evictions. These are application-level file-read counts, not physical hardware disk operations.

The three-pass benchmark resets the cache, reads every page once (cold pass), then reads the same pages twice more. When the whole working set fits inside 32 slots, the later passes should predominantly hit. If it exceeds the capacity, LRU evictions can cause additional misses.

## 7. `FairnessExperiment.java` — OS synchronization lab

The lab creates several worker threads and a shared `ReentrantLock`. Each thread repeatedly measures how long `lockInterruptibly()` takes before acquiring the critical section. It stores operation count, maximum observed wait, and cumulative wait for each worker.

It runs the same workload with a non-fair lock and then a fair lock. The displayed times come from that run. Fairness generally reduces barging and can make waits more evenly distributed, but the results depend on scheduling and machine load; they are not a mathematical proof of zero starvation.

## 8. Key demonstration paths

### ACID commit path
`Run valid multi-table COMMIT` → build candidate → add student + course + enrollment → validate all constraints → force WAL commit → rewrite table snapshots → publish state.

### Rollback path
`Run ATOMICITY rollback test` → add a valid student to candidate → add enrollment with nonexistent student/course IDs → `validate()` throws → candidate is discarded → committed student count stays unchanged.

### Concurrent worker path
`Run 4 database workers` → four worker threads synchronize at a start latch → each submits 15 unique temporary student inserts via the same transaction API → counters and timing are collected → a cleanup transaction removes temporary rows.

### Random page-worker path
Four worker threads choose random valid page indexes and call `db.requestPage(index)`. They share the same synchronized 32-page LRU buffer and the same page file; repeated access to a resident page increases cache hits without a new page-file read.

### Recovery path
`Simulate restart: replay WAL` → storage manager reads the latest valid WAL snapshot → validates it → rewrites table snapshots → database updates its live state to the loaded state.
