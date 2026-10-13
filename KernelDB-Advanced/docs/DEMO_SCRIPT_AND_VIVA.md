# KernelDB Demonstration Script & Viva Notes

## Suggested 8–10 minute demo

### 1. Start with the problem (30 seconds)
“KernelDB is a teaching-scale custom file-backed database. Our aim is to show not only data CRUD, but how normalized relations, transaction integrity, concurrency control, OS locks, and recovery work together.”

### 2. Normalization (1 minute)
Open **Normalization & JOIN**. Explain:
- Student stores student facts once.
- Course stores course facts once.
- Enrollment connects the two with foreign keys and carries semester/grade.
- This avoids repeated student/course descriptive facts and prevents update anomalies.
- Press **Run SELECT + JOIN** and filter by grade to show a joined view.

### 3. ACID demo (2 minutes)
Open **ACID Transaction Lab**.
1. Press **Run valid multi-table COMMIT**. One transaction creates Student, Course, and Enrollment.
2. Press **Run ATOMICITY rollback test**. A valid student is staged; a deliberately invalid enrollment refers to missing keys; validation rejects the candidate. Explain that the staged student must not remain.
3. Press **Trigger UNIQUE constraint failure**. Explain consistency and the unique email rule.
4. Explain the WAL commit sequence and how durability is tested by reloading the latest commit.

### 4. Concurrency and starvation (2 minutes)
Open **Concurrency & Starvation**.
1. Press **Run 4 database workers**. The app launches four actual Java background threads; they all submit transactions to the same database object. It reports the completed and failed transaction counts, timing, and lock metrics, then removes temporary rows.
2. Press **Compare fair vs non-fair lock**. Read the worker counts and wait times out loud.
3. Explain starvation accurately: “Starvation means a thread can be postponed indefinitely while other threads progress. A fair lock mitigates barging; our finite experiment measures wait behavior but does not prove starvation is impossible under every schedule.”

### 5. Page buffer + recovery (1–2 minutes)
First, open **Concurrency & Starvation** and press **Run 4 random page-request workers**. Show that they request pages without you selecting pages manually. Then open **WAL Recovery & Metrics** and press **Run 3-pass page I/O benchmark**. Explain that each persisted page is 4096 bytes, the buffer holds up to 32 page images, and the LRU cache returns resident pages from RAM.

Next, in **WAL Recovery & Metrics**:
1. Press **Read recent WAL entries**.
2. Press **Simulate restart: replay WAL**.
3. Explain that commit snapshots are encoded into `kernel.wal`, forced to disk before the table snapshots are refreshed, and reloaded by the storage manager.
4. Be honest that this is an in-process recovery simulation, not a physical power-loss test.

### 6. Close (20 seconds)
“The current prototype demonstrates normalized relations, constraint validation, staged transactions, threaded workloads, fair-lock measurements, WAL replay, separate persistent table files, and a 4 KB page-file/LRU experiment. Phase 3 focuses on automated testing, checkpoint/log compaction, stronger crash testing, and repeatable benchmarks.”

## Viva questions and concise answers

**Q1. Where is concurrency used?**  
In the Concurrency tab, four Java worker threads submit transactions concurrently through `KernelDatabase.transact`. The database uses resource locks and a shared state commit lock to coordinate changes.

**Q2. Where is starvation handled?**  
The fairness experiment compares fair and non-fair `ReentrantLock` behavior using measured per-worker completion and wait times. The database itself also uses fair locks. Fairness reduces barging but is not a universal mathematical guarantee that starvation can never happen.

**Q3. Where is normalization?**  
In `DatabaseState` and the separate Student, Course, and Enrollment relations. Student/course attributes are stored in their own relations, and Enrollment references them through foreign keys.

**Q4. Explain the ACID properties in your implementation.**  
Atomicity: a transaction changes a private copy and invalid candidates are discarded. Consistency: constraints are checked before commit. Isolation: resource locks and a fair state lock coordinate access. Durability: a committed snapshot is appended to the WAL and forced to disk before relation snapshots are refreshed.

**Q5. Is this complete SQL?**  
No. The current query lab demonstrates a built-in relational join and grade filter, not a general SQL parser. SQL parsing is an identified extension.

**Q6. Is your recovery button a real crash test?**  
It simulates a restart by reloading the latest valid committed WAL state. The current prototype does not forcibly terminate itself or emulate power loss.

**Q7. Why use a WAL if you also save table files?**  
The log is the recovery authority. If the process stops after the commit is durable but before all table snapshots are refreshed, WAL replay can restore the latest committed state on startup.

**Q8. What is 2PL here?**  
The engine acquires each transaction's declared resource locks in sorted order, holds them through validation/commit, and releases them only at the end. This is strict-2PL-style lock retention for the declared resources, not a general-purpose lock manager supporting every SQL workload.

**Q9. What happens if a foreign key is invalid?**  
Candidate-state validation throws an exception; the candidate is not published as committed state, so no orphan enrollment is persisted.

**Q10. How do you know a page request was a cache hit?**  
The page buffer checks its access-order LRU map by page index. A resident page increments the hit count; a miss reads 4096 bytes from `kernel.pages` with `FileChannel`, increments the miss and file-page-read counts, and adds the page to the cache.

**Q11. What will you do in Phase 3?**  
Automated tests, controlled crash injection, WAL checkpoints/log compaction, more robust concurrent conflict tests, repeated benchmark trials with actual measurements, and optionally a small SQL parser.
