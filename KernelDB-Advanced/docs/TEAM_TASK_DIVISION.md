# KernelDB Team Division (Phase 2 → Phase 3)

The assignments below split implementation ownership. All members should be able to explain the full architecture and run every demo because the teacher may ask any member about any concept.

## Member 1 — Harshit Sharma (Team Lead): OS concurrency & integration

**Primary ownership**
- `concurrency/FairnessExperiment.java`
- Concurrency tab in `MainApp.java`
- Worker thread experiment, start coordination, waiting-time measurements, and result display
- Integration review: verify that UI calls the database transaction API rather than editing files directly

**Must be able to explain**
- Process vs thread; why the demo uses threads
- Critical section and mutual exclusion
- `ReentrantLock`, fairness, barging, and why fairness is not an absolute no-starvation guarantee
- Lock acquisition order, circular wait, and why sorted keys help avoid deadlock among declared locks
- How to interpret elapsed time and worker completion counts without inventing conclusions

**Phase 3 deliverables**
- More stable concurrent stress tests
- Tests for concurrent duplicate insert, concurrent enrollment vs student deletion, and lock wait metrics
- Review thread shutdown and exception handling

## Member 2 — Aryansh Lakhanpal: Relational model & storage core

**Primary ownership**
- `model/Student.java`, `Course.java`, `Enrollment.java`
- `model/DatabaseState.java`
- `storage/StorageManager.java` and `storage/PageBufferManager.java` (with review from Member 3)
- Relations/CRUD and Normalization/Join sections of `MainApp.java`

**Must be able to explain**
- Why Student, Course, and Enrollment are separate relations
- Primary key, candidate/alternate key, foreign key, uniqueness, and referential integrity
- 1NF, 2NF, 3NF, functional dependencies, and update/insert/delete anomalies
- How transactions work on a private state copy and how validation prevents invalid state from being committed
- How table files and the WAL differ

**Phase 3 deliverables**
- Add automated schema/constraint tests
- Add safer reset/backup features and file integrity checks
- Improve page management from full page-image rebuilds to granular dirty-page updates and write-back tracking

## Member 3 — Shourya Choudhary: ACID, recovery & testing/reporting

**Primary ownership**
- ACID Transaction Lab in `MainApp.java`
- `KernelDatabase.java` transaction lifecycle (coordinate changes with Member 2)
- WAL recovery validation and `docs/PHASE_2_REPORT.md`
- `docs/DEMO_SCRIPT_AND_VIVA.md`

**Must be able to explain**
- Atomicity, consistency, isolation, durability with the project's own button demonstrations
- Why the deliberately invalid transaction rolls back all staged changes
- Write-ahead logging: why the WAL record is forced before relation snapshots are rewritten
- Recovery replay and what the current demo does/does not prove
- Test cases, actual measurements, and project limitations

**Phase 3 deliverables**
- Unit/integration tests for ACID and recovery
- Crash-injection and log-compaction/checkpoint design
- Collect screenshots, repeatable benchmarks, and finish report evidence

## Shared integration rules

1. Work on separate branches or keep a dated backup before merging edits.
2. Never edit the same method simultaneously without coordinating.
3. After every merge, run `mvn -U clean javafx:run` from the project root.
4. Run T1–T10 in the Phase 2 report and record the actual result for each.
5. Every demo must have a short explanation: concept → code location → what button does → expected/observed result → limitation.
6. Do not claim a complete SQL engine, production-grade page-addressed storage, every SQL isolation level, or guaranteed starvation freedom. The current build does contain a real fixed 4 KB page file and LRU page-request lab, but relational browsing is still backed by the in-memory state.
