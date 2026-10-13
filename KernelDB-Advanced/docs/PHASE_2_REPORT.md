# PHASE 2 PROJECT REPORT
## KernelDB: An OS–DBMS Integrated Storage Engine for Transaction Processing and Concurrency

**Course context:** OS–DBMS Project-Based Learning  
**Team:** Harshit Sharma (Team Lead), Aryansh Lakhanpal, Shourya Choudhary  
**Institution:** Graphic Era (Deemed to be University)  
**Technology:** Java 21, JavaFX, Maven, Java NIO, custom local database files

> **Report integrity note:** This is the report draft for the redesigned prototype. Run the application and execute the listed tests before submission. Insert actual screenshots and measured timings where indicated. No performance result is assumed or invented in this draft.

## 1. Abstract

KernelDB is a teaching-scale, file-backed relational database prototype developed to demonstrate how DBMS transaction processing and Operating System synchronization interact. The redesigned version goes beyond basic record import and cache counters by implementing a normalized academic schema, primary-key and foreign-key constraints, transaction staging, rollback on validation failure, concurrent database workers, fair locking experiments, write-ahead commit logging, and recovery of the latest committed state. A JavaFX interface provides hands-on experiments for these topics, including a bounded 32-page LRU over fixed 4 KB page images. The implementation is intentionally educational and is not intended to replace a production DBMS.

## 2. Problem Statement

Basic database demonstrations often focus on inserting, displaying, or importing records without showing how a DBMS preserves relational integrity during concurrent operations or failures. KernelDB addresses this learning gap through a custom local storage layer and observable experiments for normalization, concurrency control, ACID transactions, locking, and recovery.

## 3. Objectives

1. Build a custom file-backed database engine using Java and JavaFX without relying on MySQL/JDBC for the core storage.
2. Design a normalized relational schema and enforce primary keys, unique constraints, and foreign keys.
3. Implement transaction staging and all-or-nothing commit behavior for the application operations.
4. Demonstrate concurrent database operations through real Java worker threads.
5. Compare fair and non-fair lock acquisition using measured wait times and task completion counts.
6. Use resource locks, consistent lock ordering, and lock retention through transaction completion to demonstrate strict-2PL-style control.
7. Persist commit records in a write-ahead log and reload the latest valid committed state during a recovery demonstration.
8. Provide a GUI that makes the above concepts easy to inspect and explain.

## 4. System Scope

### Included in this prototype

- JavaFX GUI with separate tabs for relations/CRUD, normalization and joins, ACID experiments, concurrency/starvation, and WAL recovery.
- Three custom persistent relation files: `students.tbl`, `courses.tbl`, and `enrollments.tbl`.
- A serialized state snapshot and append-only `kernel.wal` commit log.
- `kernel.pages`: a fixed-size 4 KB page file, addressed with `FileChannel`, plus a bounded 32-page access-order LRU cache with hits, misses, file-page reads, and evictions.
- Student, course, and enrollment CRUD workflows, including a student deletion that removes dependent enrollment rows in the same transaction.
- Validation of primary-key identity, unique student emails, unique course codes, foreign keys, supported grade values, credit ranges, and duplicate enrollment combinations.
- Multi-table commit demo and deliberate constraint-failure/rollback demos.
- Four worker threads performing temporary database inserts concurrently.
- Four worker threads making random requests against the persisted 4 KB pages through a shared 32-page LRU buffer.
- A three-pass sequential page I/O benchmark that measures cache behavior against the configured buffer capacity.
- Fair versus non-fair `ReentrantLock` experiment with per-worker operation counts and wait metrics.
- WAL reload/replay demonstration.
- Built-in relational join over the three relations with a grade filter.

### Not claimed as production features

The project does not implement a complete SQL parser, a distributed DBMS, multi-process access coordination, a production-grade page-addressed storage engine, comprehensive user-selectable isolation levels, log checkpoints, log truncation, or formal proof that starvation cannot occur. These are outside the current educational prototype and should not be claimed during evaluation.

## 5. Architecture

```text
User / JavaFX GUI
       |
       v
KernelDB transaction API
       |
       +--> Resource locks acquired in sorted order
       |
       v
Private working copy of relational state
       |
       +--> PK / unique / FK / domain validation
       |
       v
Fair state read/write lock and commit serialization
       |
       v
Write-ahead COMMIT record (forced to file)
       |
       +--> kernel.snapshot
       +--> students.tbl
       +--> courses.tbl
       +--> enrollments.tbl
       +--> kernel.pages (4 KB images + 32-slot LRU cache)
```

The UI does not directly edit persistent files. It submits operations through the transaction API. The database layer applies each operation to a private candidate state, validates the candidate, and commits it as one logical unit. If validation throws an exception, the candidate is discarded and the committed state remains unchanged.

## 6. Relational Design and Normalization

### 6.1 Relations

**STUDENT**(`student_id` PK, `name`, `email` UNIQUE)  
**COURSE**(`course_id` PK, `course_code` UNIQUE, `title`, `credits`)  
**ENROLLMENT**(`enrollment_id` PK, `student_id` FK, `course_id` FK, `semester`, `grade`, UNIQUE(`student_id`, `course_id`, `semester`))

### 6.2 Functional dependencies

- `student_id → name, email`; email is also constrained unique.
- `course_id → course_code, title, credits`; course_code is also constrained unique.
- `enrollment_id → student_id, course_id, semester, grade`.
- Under the selected business rule, (`student_id`, `course_id`, `semester`) uniquely identifies an enrollment for that semester.

### 6.3 Why decomposition is appropriate

An unnormalized enrollment sheet might repeat course details in several columns or copy a student's email into every course row. Such duplication causes update, insertion, and deletion anomalies. The design separates student facts and course facts from the many-to-many student-course association.

- **1NF:** attributes contain single values; there are no repeating groups such as `course_1`, `course_2`, and `course_3`.
- **2NF:** student and course descriptive attributes are stored with their own entity keys, not dependent on only part of the student-course relationship.
- **3NF:** enrollment does not duplicate student names/emails or course titles/credits. Non-key descriptive attributes remain with the relation they describe.

The implementation enforces foreign-key validity when an enrollment is added. It also prevents deleting a student while leaving orphan enrollment rows by deleting the student's dependent enrollments within the same transaction.

## 7. Transaction Management and ACID

### Atomicity

An operation is applied to a private copy. The candidate is not made visible as committed state unless validation succeeds and the commit path completes. The rollback demo stages a student insert and then adds an invalid enrollment with missing foreign keys; the complete candidate should be rejected, leaving the original student count unchanged.

### Consistency

Before commit, the database validates identity of primary keys, unique emails and course codes, foreign keys, grade values, allowed credit values, and enrollment uniqueness. A deliberate duplicate-email attempt should fail before commit.

### Isolation / concurrency control

The engine uses fair resource locks and a fair read/write state lock. Declared resource locks are acquired in sorted order and held through commit, which demonstrates a strict-2PL-style lifecycle for the declared resource keys. A commit lock protects the atomic state replacement and write-ahead logging point. Concurrent transactions re-apply their deterministic mutation to the newest state if the state version changed, preventing unrelated updates from being overwritten by a stale state copy.

**Scope qualification:** the prototype is not a full SQL transaction engine and does not offer user-selectable READ COMMITTED / REPEATABLE READ / SERIALIZABLE isolation levels. Those are future extensions.

### Durability and recovery

Each successful commit appends a `COMMIT` record with a monotonically increasing sequence and a recoverable encoded state. Java NIO `FileChannel.force(true)` is used to force the log record before the table snapshots are rewritten. On startup, KernelDB scans the WAL and restores the latest valid committed state; malformed incomplete records are ignored. The GUI's recovery button re-loads the WAL and rewrites relation snapshots, simulating restart recovery without forcibly terminating the process.

## 8. Concurrency, Starvation, and Deadlock Discussion

### Concurrent workers

The concurrency tab starts four Java worker threads. Each worker attempts a batch of temporary inserts through the same `KernelDatabase` object. The interface reports attempted/completed/failed transactions and elapsed time, then removes the temporary benchmark rows in a cleanup transaction. This demonstrates actual threaded operations against the transactional storage API rather than manually selecting four pages.

### Starvation and fairness

A finite experiment compares fair and non-fair `ReentrantLock` configurations. Each worker repeatedly requests one shared lock. KernelDB records completed operations and maximum/cumulative observed wait time for every worker. A fair lock discourages barging and improves queue fairness, but a short run cannot prove that starvation is impossible for every possible operating-system schedule.

### Deadlock avoidance and 2PL-style lock lifecycle

Resource keys are sorted before acquisition to remove circular-wait among those declared locks. Locks are held until validation/commit finishes and are released in reverse order. This illustrates a strict-2PL-style lifecycle. The prototype does not currently implement a general wait-for graph or deadlock detection service.

## 9. OS Integration

- **Thread management:** Java `ExecutorService`, worker threads, start latches, and futures.
- **Synchronization:** fair `ReentrantLock` and fair `ReentrantReadWriteLock`.
- **Critical sections:** short sections around shared committed state and file commit ordering.
- **File management and I/O:** Java NIO paths and channels write the WAL, normalized relation files, and fixed-size 4 KB page images.
- **Memory and page replacement:** a bounded access-order 32-page LRU buffer keeps requested page images in memory and counts page hits, misses, file reads, and evictions. These counters measure application-level `FileChannel` reads, not physical disk-device operations.
- **Recovery:** restart-style loading of the latest valid durable commit.
- **Resource observation:** elapsed transaction time, lock waiting time, worker completion counts, commit counts, rollback counts, and state sequence.

## 10. Implementation Modules

| Module | Responsibility |
|---|---|
| `MainApp.java` | JavaFX interface and button workflows. |
| `DatabaseState.java` | Transaction working state, identity rules, normalization-aware relations, and integrity constraints. |
| `KernelDatabase.java` | Transaction orchestration, resource locking, revalidation, state replacement, metrics, and query joins. |
| `StorageManager.java` | WAL append/force, snapshot writing, normalized table-file writing, page-image rebuilding, and WAL replay. |
| `PageBufferManager.java` | Fixed 4 KB page reads through `FileChannel`, 32-entry LRU cache, hit/miss/read/eviction counters. |
| `FairnessExperiment.java` | Fair/non-fair lock contention experiment and per-worker statistics. |
| `Student.java`, `Course.java`, `Enrollment.java` | Typed relational row models. |

## 11. Test Plan and Evidence to Capture

Run each experiment and record actual outcomes. Add screenshots from your own application run in the final submission.

| Test ID | Action | Expected result | Actual result to fill after testing |
|---|---|---|---|
| T1 | Launch app with an empty `data` folder | Demo rows are seeded once and table files are created | `[fill in]` |
| T2 | Register enrollment using a missing student or course ID | Foreign-key constraint rejects transaction; no orphan row is committed | `[fill in]` |
| T3 | Run valid multi-table ACID demo | One student, one course, and one enrollment appear together under one commit sequence | `[fill in]` |
| T4 | Run atomicity rollback demo | Student staged before deliberate invalid enrollment does not persist | `[fill in]` |
| T5 | Run unique email constraint test | Duplicate email is rejected and row count remains unchanged | `[fill in]` |
| T6 | Run four-worker database test | App reports actual completed/failed counts and removes temporary benchmark rows | `[fill in]` |
| T7 | Compare fair/non-fair lock | Per-worker counts and observed wait times are displayed for both modes | `[fill in]` |
| T8 | Restart/replay WAL | Latest valid committed state is reloaded; relation counts remain consistent | `[fill in]` |
| T9 | Add a record, close app normally, relaunch | Previously committed record remains present | `[fill in]` |
| T10 | Run `SELECT + JOIN` with grade A | Matching student-course-enrollment rows are displayed | `[fill in]` |
| T11 | Reset page metrics and run the 3-pass page benchmark | First pass reads each 4 KB page; later passes hit for a working set that fits in 32 slots | `[fill in]` |
| T12 | Run four random page-request workers | Four threads request real page indexes and share the LRU buffer; actual hits/misses/reads are displayed | `[fill in]` |

## 12. Performance Evaluation

Do not report a performance improvement without repeatable measurement. For a more formal Phase 3 evaluation, run at least 5 trials per workload size and report median, minimum, maximum, and environment details.

Record: row count, worker count, elapsed time, completed transactions, failed transactions, transaction throughput (`committed transactions / seconds`), average and maximum lock wait, WAL size, heap usage, and database file sizes. Use identical data and worker settings when comparing runs. This Phase 2 build does not claim a proven speedup over a commercial DBMS or an unmeasured baseline.

**Measured environment:** `[CPU, RAM, OS, Java version, Maven version]`  
**Workload / settings:** `[worker count, transactions per worker, dataset size]`  
**Results / screenshots:** `[insert after running the application]`

## 13. Limitations

1. The database is designed for a single application process and small educational workloads.
2. The built-in query view demonstrates a relational join and grade filter, not a general SQL parser or query optimizer.
3. The WAL stores a complete serialized state per commit, so it grows over time; checkpointing and log compaction are not implemented.
4. The recovery button replays the WAL in-process to simulate restart; a formal power-loss test has not been claimed.
5. Starvation measurements are finite observations, not a theoretical guarantee.
6. The page lab uses actual fixed 4 KB page images and a bounded LRU cache, but normal relational browsing still reads the current in-memory state; it is not yet a fully page-addressed production storage engine. Commits rebuild the page-image file rather than applying granular page modifications.

## 14. Phase 3 Work

- Add a tested checkpoint and WAL truncation/compaction strategy.
- Add reproducible benchmarks for sequential and concurrent workloads, including repeated trials and charts.
- Add automated unit/integration tests for ACID properties, constraint failures, concurrent inserts, recovery, and persistence after restart.
- Add a controlled crash-injection test between WAL force and relation-snapshot writing.
- Add SQL-like parser support for SELECT, PROJECT, JOIN, WHERE, INSERT, UPDATE, and DELETE, or clearly keep the current built-in query lab as a relational algebra demonstration.
- Improve page management from full page-image rebuilds to granular page updates, including dirty-page tracking and write-back policy; keep cache counters tied to actual `FileChannel` page reads.
- Improve validation messages, audit history, table export, and documentation.
- Review lock lifecycle for every CRUD operation and test delete-vs-enrollment races.

## 15. Syllabus Alignment

| Syllabus area | Relevant topics | Evidence in KernelDB |
|---|---|---|
| DBMS Unit 2 — Relational Model / SQL | Relations, primary and foreign keys, constraints, SELECT/PROJECT concepts, JOIN | Student/Course/Enrollment relations, integrity validation, built-in join with grade filter. This is not a general SQL parser. |
| DBMS Unit 4 — Database Design | Functional dependencies, 1NF, 2NF, 3NF, decomposition | Normalization tab and schema discussion explain why descriptive student/course data is stored separately. |
| DBMS Unit 5 — Transaction Management | ACID, concurrent transaction execution, lock-based control, 2PL-style locking, logging, recovery | Candidate-state commits/rollbacks, fair resource locks, sorted lock acquisition, write-ahead COMMIT log, restart/replay demonstration. The prototype does not implement a serializability-graph analyzer or all SQL isolation levels. |
| OS Unit 2 — Process Management | Threads, thread scheduling, concurrent work | Four worker threads execute database inserts and four separate workers request pages. |
| OS Unit 3 — Process Synchronization | Critical sections, mutual exclusion, synchronization | Fair/non-fair `ReentrantLock`, fair read/write lock, serialized commit point, per-worker waiting-time measurements. |
| OS Unit 4 — Memory Management | Page replacement | Access-order 32-entry LRU page buffer with hit/miss and eviction measurements. |
| OS Unit 5 — File System / I/O | File access, file management, I/O | Java NIO `FileChannel` reads fixed 4 KB page blocks; snapshots and the WAL are stored as local files. |

## 16. Conclusion

The redesigned KernelDB prototype is intended to make DBMS and OS topics observable through one GUI. Its core learning features are normalized relational design, integrity constraints, transaction staging and rollback, concurrent worker execution, measured fair-lock contention, write-ahead logging, and recovery of committed state. Final claims and the performance section must be completed using the actual run output and screenshots collected by the project team.
