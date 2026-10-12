# KernelDB — Phase 2 Working Prototype

**Project:** KernelDB: An OS–DBMS Integrated Storage Engine for Efficient Data Ingestion  
**Stack:** Java 21, JavaFX, Maven, custom local page file  
**IDE:** Visual Studio Code  
**Database file:** `data/students.db`  
**CSV sample:** `data/students_sample.csv`

This prototype follows the attached KernelDB proposal: a JavaFX GUI talks to a custom storage engine; the storage engine organizes student records in fixed-size pages and connects DBMS concepts (records, CRUD, pages and buffering) with OS-related concepts (file I/O, worker threads and synchronization). It is an educational project, not a replacement for a commercial DBMS and not a reproduction of the complete research system.

## What works in this Phase 2 code

- JavaFX dashboard with a searchable student table and record form.
- Custom `students.db` binary file; it is **not** a CSV or a MySQL database.
- Fixed-size 4 KB database pages, with 31 fixed-size record slots per page.
- Insert, search, update and delete student records.
- CSV import and export, including quote-aware CSV fields.
- Included 1,000-row CSV file for quick testing.
- Generate a larger test CSV (up to 100,000 rows) from the GUI.
- Thread-safe LRU buffer with a configurable capacity of 32 pages.
- Cache hit/miss, page-read-call, page-write-call, worker-request and JVM-heap monitoring.
- Four concurrent read workers that exercise a shared buffer.
- A repeatable baseline-vs-buffer benchmark which compares direct page reads with buffered page reuse on the same page-request pattern.
- Reopening the application reloads existing records from `students.db`.

## What is intentionally left for Phase 3

- Full transaction manager with durable `BEGIN` / `COMMIT` / `ROLLBACK` semantics.
- Write-ahead logging and crash recovery after an interrupted write.
- Stronger recovery and corruption tests.
- More comprehensive, repeatable benchmarks on several dataset sizes, charting, and analysis of results.
- Further GUI polishing and automated tests.

Do not present these Phase 3 features as finished. The current benchmark measures **application-level page-read calls through the Java file API**, not physical disk-device reads. The operating system may satisfy file reads from its own filesystem cache. Benchmark times are also machine-dependent; use the measurements from your own machine and do not copy invented numbers into the report.

---

## 1. Install/check the tools

### Java JDK 21

Open a terminal (PowerShell in Windows) and run:

```powershell
java -version
javac -version
```

Both commands should report Java 21. This project is configured for Java 21 and JavaFX 21. If the commands are missing or show a different major version, install a JDK 21 distribution and make VS Code use that JDK.

### Maven

Run:

```powershell
mvn -version
```

This should show Apache Maven and the Java version Maven is using. Your message mentioned Maven `3.6.16`; please check the exact output because the official Apache Maven release history lists `3.6.3` in the 3.6 series, not `3.6.16`. A current Maven 3.9.x is recommended for fewer build-tool compatibility issues. If Maven is not found, add Maven's `bin` folder to the system `PATH` and open a new terminal.

### VS Code extensions

Install these from the VS Code Extensions panel:

1. **Extension Pack for Java** (Microsoft)
2. **Maven for Java** (Microsoft)

You do **not** need to download a separate JavaFX SDK for this Maven project. JavaFX dependencies and the platform-specific JavaFX libraries are declared in `pom.xml`; Maven downloads them on the first build. An internet connection is required for that first dependency download.

---

## 2. Open the project in VS Code

1. Download and extract the `KernelDB_Phase2.zip` archive.
2. Open VS Code.
3. Select **File → Open Folder**.
4. Select the extracted folder named `KernelDB_Phase2` — the folder containing `pom.xml`, `src`, and `data`.
5. Wait for the Java and Maven extensions to finish importing the project.
6. Open **Terminal → New Terminal**. Confirm the terminal is inside the `KernelDB_Phase2` folder.

The project structure is:

```text
KernelDB_Phase2/
├── pom.xml
├── README.md
├── data/
│   └── students_sample.csv
└── src/main/java/com/kerneldb/
    ├── model/       Student.java
    ├── storage/     Page.java, FileManager.java
    ├── buffer/      BufferManager.java
    ├── core/        KernelDBEngine.java, metrics/results
    ├── csv/         CsvService.java
    └── gui/         KernelDBApp.java
```

---

## 3. Run the GUI

Run this command in the VS Code terminal from the project root:

```powershell
mvn clean javafx:run
```

The first run may take a while because Maven needs to download JavaFX and Maven plugins. If the build succeeds, the KernelDB dashboard opens. You do not need to run a separate SQL server or create a MySQL database.

**Each time you want to run KernelDB later:** open the same project folder and run `mvn javafx:run`. You can use `mvn clean javafx:run` if you want a fresh build first.

---

## 4. First run: try the included data

1. Open the GUI.
2. Click **Load Sample CSV**.
3. It imports `data/students_sample.csv` (1,000 sample students). Existing IDs are not inserted twice; duplicate IDs are skipped and reported.
4. The center table displays the records. The top cards show record count, page count, cache counters and file read/write calls.
5. Select a table row to fill the form. Test **Insert Record**, **Update Selected / ID**, **Find by ID**, and **Delete by ID**.
6. Click **Export CSV** to save a CSV file you can open in Excel or a text editor.
7. Click **Run 4 Worker Threads** to run concurrent page requests through the shared synchronized buffer.
8. Click **Run I/O Benchmark** to compare repeated direct page reads with repeated requests using the LRU buffer.
9. Click **Clear Buffer + Reset Metrics**, then repeat the test. Clearing the buffer makes later accesses start cold.

The `students.db` file is binary by design. To inspect the rows, use **Export CSV**. Do not open and edit `students.db` in Excel or Notepad.

### Import a different CSV

Use these five columns, in this order:

```csv
student_id,name,course,marks,attendance
1001,Aarav Sharma,CSE,87.5,94
1002,Meera Joshi,IT,91,98
```

- Student IDs must be positive and unique.
- Marks and attendance must be between 0 and 100.
- UTF-8 text is supported; name and course are stored in fixed-size byte fields, so keep names to 40 UTF-8 bytes and course names to 24 UTF-8 bytes.
- Use **Generate Test CSV** to create a larger CSV. Then use **Import CSV** to import it. The generated IDs start above the largest ID already in the database.

---

## 5. How KernelDB works

### On disk

`data/students.db` stores 4,096-byte pages. Each page has a small header and 31 fixed-size slots. Every occupied slot represents one student record. The page format is a simplified educational storage format rather than a production database format.

### In memory

The `BufferManager` holds up to 32 pages in an access-order `LinkedHashMap`. On a hit it returns the page already held in memory. On a miss it reads an existing page using `FileChannel`, or creates a new page for insertion. When the buffer is full, the least-recently-used page is evicted; dirty pages are written before eviction.

### Concurrency

The worker test launches four Java threads that request a small shared set of pages. The buffer's synchronized methods protect its cache structure; the engine also uses a read/write lock to coordinate database reads and writes.

### Metrics

- **Cache hit:** a requested page was already in the KernelDB buffer.
- **Cache miss:** the requested page was not in the KernelDB buffer.
- **File read call:** KernelDB asked Java's file API to read one full page.
- **File write call:** KernelDB wrote one full page through Java's file API.
- **JVM heap in use:** a rough view of memory used by the Java process, not the total machine RAM.

The benchmark uses the same sequence of repeated page requests for the direct-read baseline and buffered test. It is a proof-of-concept measurement. For academic results, run several trials, record the values from your own laptop, and explain the limitations.

---

## 6. Troubleshooting

### `mvn` is not recognized

Maven is either not installed or its `bin` folder is not on `PATH`. Check `mvn -version`; reopen the terminal after changing the PATH.

### `java -version` does not show 21

Install JDK 21 and configure `JAVA_HOME` / VS Code's Java runtime to point to it. Verify both `java -version` and `mvn -version` report the expected JDK.

### JavaFX dependency download fails

Check the internet connection and rerun:

```powershell
mvn -U clean javafx:run
```

### The GUI doesn't launch or JavaFX symbols are missing in VS Code

Make sure you opened the folder containing `pom.xml`, wait for Maven import, then run `mvn clean javafx:run` from the terminal. Do not use the plain `java KernelDBApp` command; Maven supplies the JavaFX module path and native libraries.

### Records seem to disappear

Confirm you are opening the same extracted project folder each time. KernelDB uses a relative path `data/students.db`, so starting the app from a different working directory creates/uses a different `data` folder.

---

## Academic reference basis

The project structure was prepared to align with the supplied **OSDBMS-T138 KernelDB Project Proposal**: Java, JavaFX, Maven, a custom persistent database file, OS/DBMS concept mapping, a monitoring dashboard, and performance evaluation. The research inspiration provided in the project specification is *“Rapid Data Ingestion through DB-OS Co-design”* (2025), DOI `10.1145/3709718`. KernelDB is a simplified educational implementation and does not claim to reproduce the paper's complete system.
