# KernelDB Phase 3 — Remaining Work

This file deliberately separates planned work from what is running in the Phase 2 prototype.

- Implement transaction boundaries and durable BEGIN/COMMIT/ROLLBACK semantics.
- Add a write-ahead or undo log in `data/kerneldb.log` with a clearly defined record format.
- Add crash simulation and recovery tests; verify the database returns to a documented consistent state.
- Add tests for database page serialization, CRUD, duplicate IDs, cache eviction, concurrent reads, CSV validation and restart persistence.
- Run baseline and buffered tests across multiple dataset sizes (e.g. 10k, 50k and 100k) and several trials; save raw results to CSV.
- Add charts for time, read calls, cache hit ratio and memory usage; distinguish application-level reads from hardware-level disk I/O.
- Improve UI feedback and error handling; consider background/async CRUD for very large datasets.
- Final report: architecture, test environment, workload methodology, measured results, limitations and references.
