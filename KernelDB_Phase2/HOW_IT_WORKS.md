# How KernelDB Works (Phase 2)

## One request, end to end

1. A user clicks an action in the JavaFX GUI.
2. `KernelDBApp` validates the input and calls `KernelDBEngine`.
3. The engine finds the student's page/slot using an in-memory ID-to-location index.
4. The engine asks `BufferManager` for that page.
5. If the page is in the RAM buffer, the request is a cache hit. If it isn't, the buffer records a cache miss and asks `FileManager` to read the page from `students.db` (unless the requested page is a new page being created).
6. The page contains fixed-size record slots. The engine modifies the selected slot for insert/update/delete.
7. Modified pages are marked dirty and flushed to the database file; batch CSV import flushes pages in batches/at the end, with dirty pages also written when evicted.
8. The GUI refreshes the records and monitoring numbers.

## The main source files

- `model/Student.java`: validates one student record.
- `storage/Page.java`: defines the 4 KB page and fixed slot serialization.
- `storage/FileManager.java`: performs positional file reads and writes using Java `FileChannel`.
- `buffer/BufferManager.java`: thread-safe LRU cache, hit/miss counters, dirty page flush, eviction.
- `core/KernelDBEngine.java`: persistent CRUD, startup index rebuilding, CSV batch import integration, benchmark and worker test.
- `core/KernelDBMetrics.java`: counters used by the dashboard.
- `csv/CsvService.java`: reads/writes CSV and generates test data.
- `gui/KernelDBApp.java`: JavaFX screen and buttons.

## Data file layout

- One page = 4,096 bytes.
- Header = 16 bytes.
- Each slot = 128 bytes.
- 31 fixed record slots fit in each page; the remaining bytes are padding.
- `students.db` is custom binary data. Use the Export CSV button to inspect data in a spreadsheet.

## LRU behavior

The buffer uses an access-ordered `LinkedHashMap`. Every access moves that page toward the most-recently-used end. When the cache is full, the least recently accessed page is evicted. If an evicted page has been changed, KernelDB writes it before removing it from memory.

## Concurrent worker behavior

The button `Run 4 Worker Threads` starts four Java worker threads. They repeatedly request pages from a small shared working set. The engine uses a read/write lock to coordinate changes with reads, and synchronized buffer methods protect the shared LRU structure.

## Benchmark behavior

The benchmark runs the same repeated-page request sequence two ways:

- Baseline: read each requested page directly through the file manager every time.
- Buffered: use the buffer manager, so the first request to a page can miss/read and later requests can hit RAM.

The measured page-read calls are software-level read calls, not physical device operations. It is normal for elapsed times to vary and sometimes round to 0 ms for a small test. Use multiple trials and larger datasets for report-quality results. The Phase 3 plan includes a more comprehensive dataset-size comparison and charts.

## Current boundaries

Implemented in this package: local page file, records/CRUD, fixed pages, LRU buffer, CSV import/export, cache/I/O metrics, concurrent page requests, basic repeated-page comparison.

Planned for Phase 3: durable transaction manager, write-ahead/undo logging, crash recovery, systematic multi-size benchmarking and final GUI/report polish.
