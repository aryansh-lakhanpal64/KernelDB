package com.kerneldb;

import com.kerneldb.concurrency.FairnessExperiment;
import com.kerneldb.core.KernelDatabase;
import com.kerneldb.core.TransactionResult;
import com.kerneldb.model.Course;
import com.kerneldb.model.DatabaseState;
import com.kerneldb.model.Enrollment;
import com.kerneldb.model.JoinedEnrollment;
import com.kerneldb.model.Student;
import com.kerneldb.storage.PageBufferManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** JavaFX front end for the KernelDB OS/DBMS transaction and concurrency lab. */
public class MainApp extends Application {
    private KernelDatabase db;
    // Serialize lab runs so the page file is not rebuilt while another lab is timing its requests.
    private final ExecutorService labExecutor = Executors.newSingleThreadExecutor();

    private final TableView<Student> studentTable = new TableView<>();
    private final TableView<Course> courseTable = new TableView<>();
    private final TableView<Enrollment> enrollmentTable = new TableView<>();
    private final TableView<JoinedEnrollment> joinTable = new TableView<>();
    private final ObservableList<Student> studentRows = FXCollections.observableArrayList();
    private final ObservableList<Course> courseRows = FXCollections.observableArrayList();
    private final ObservableList<Enrollment> enrollmentRows = FXCollections.observableArrayList();
    private final ObservableList<JoinedEnrollment> joinRows = FXCollections.observableArrayList();

    private final TextField studentName = new TextField();
    private final TextField studentEmail = new TextField();
    private final TextField courseCode = new TextField();
    private final TextField courseTitle = new TextField();
    private final TextField courseCredits = new TextField("3");
    private final TextField enrollmentStudentId = new TextField();
    private final TextField enrollmentCourseId = new TextField();
    private final TextField enrollmentSemester = new TextField("2026-Semester-1");
    private final ComboBox<String> enrollmentGrade = new ComboBox<>();
    private final ComboBox<String> queryGrade = new ComboBox<>();
    private final TextField newGradeField = new TextField("A");

    private final TextArea transactionLog = logArea(11);
    private final TextArea concurrencyLog = logArea(15);
    private final TextArea recoveryLog = logArea(14);
    private final TextArea activityLog = logArea(8);

    private final Label studentsMetric = metricValue("0");
    private final Label coursesMetric = metricValue("0");
    private final Label enrollmentsMetric = metricValue("0");
    private final Label commitsMetric = metricValue("0");
    private final Label rollbacksMetric = metricValue("0");
    private final Label sequenceMetric = metricValue("0");
    private final Label lockCountMetric = metricValue("0");
    private final Label maxWaitMetric = metricValue("0");
    private final Label pageCountMetric = metricValue("0");
    private final Label cacheHitsMetric = metricValue("0");
    private final Label cacheMissesMetric = metricValue("0");
    private final Label fileReadsMetric = metricValue("0");
    private final Label evictionsMetric = metricValue("0");
    private final Label hitRateMetric = metricValue("0%");
    private final Label statusLabel = new Label("Starting KernelDB...");
    private Button concurrentButton;
    private Button fairnessButton;
    private Button pageRequestsButton;
    private Button pageBenchmarkButton;

    @Override
    public void start(Stage stage) throws Exception {
        db = new KernelDatabase(Path.of("data"));
        db.seedDemoData();
        configureTables();
        configureInputs();

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildTabs());
        root.setBottom(buildStatusBar());
        root.setStyle("-fx-background-color: #0b1120; -fx-font-family: 'Segoe UI';");

        Scene scene = new Scene(root, 1440, 940);
        stage.setTitle("KernelDB | OS–DBMS Transaction & Concurrency Lab");
        stage.setMinWidth(1120);
        stage.setMinHeight(780);
        stage.setScene(scene);
        stage.show();
        refreshAll();
        append(activityLog, "Application launched. " + db.lastRecoveryMessage());
        append(activityLog, "Data directory: " + db.dataDirectory());
        append(transactionLog, "Ready. Use the buttons to run commit, rollback, consistency, and recovery experiments.");
        append(recoveryLog, "Persistent files are written under: " + db.dataDirectory());
        append(recoveryLog, "Files: kernel.wal, kernel.snapshot, students.tbl, courses.tbl, enrollments.tbl");
    }

    private Node buildHeader() {
        Label eyebrow = new Label("JAVA 21  •  JAVAFX  •  MAVEN  •  CUSTOM FILE-BACKED STORAGE");
        eyebrow.setStyle("-fx-text-fill: #8ab4f8; -fx-font-size: 11px; -fx-font-weight: bold; -fx-letter-spacing: 1.1px;");
        Label title = new Label("KernelDB");
        title.setStyle("-fx-text-fill: #f1f5f9; -fx-font-size: 29px; -fx-font-weight: bold;");
        Label subtitle = new Label("OS–DBMS integrated storage engine  |  Transactions • Normalization • Concurrency • Recovery");
        subtitle.setStyle("-fx-text-fill: #aab7cf; -fx-font-size: 13px;");
        VBox left = new VBox(5, eyebrow, title, subtitle);

        HBox metrics = new HBox(9,
                metricCard("STUDENTS", studentsMetric), metricCard("COURSES", coursesMetric),
                metricCard("ENROLLMENTS", enrollmentsMetric), metricCard("COMMITS", commitsMetric),
                metricCard("ROLLBACKS", rollbacksMetric));
        metrics.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(22, left, spacer, metrics);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(18, 22, 18, 22));
        top.setStyle("-fx-background-color: #111a2e; -fx-border-color: #26344d; -fx-border-width: 0 0 1 0;");
        return top;
    }

    private Node buildStatusBar() {
        statusLabel.setStyle("-fx-text-fill: #b8c7df; -fx-font-size: 11px;");
        HBox bar = new HBox(statusLabel);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.setStyle("-fx-background-color: #111a2e; -fx-border-color: #26344d; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private TabPane buildTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(tab("Relations & CRUD", buildDataTab()));
        tabs.getTabs().add(tab("Normalization & JOIN", buildNormalizationTab()));
        tabs.getTabs().add(tab("ACID Transaction Lab", buildTransactionTab()));
        tabs.getTabs().add(tab("Concurrency & Starvation", buildConcurrencyTab()));
        tabs.getTabs().add(tab("WAL Recovery & Metrics", buildRecoveryTab()));
        tabs.setStyle("-fx-background-color: #0b1120; -fx-tab-min-height: 38px;");
        return tabs;
    }

    private Tab tab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private Node buildDataTab() {
        Node intro = heading("Normalized academic database", "Three connected relations, persistent table files, validated keys, and transactional CRUD.");
        Button seedButton = button("Load demo dataset", "#2459a9");
        seedButton.setOnAction(e -> runUiAction("Load demo dataset", () -> {
            if (!db.readSnapshot().students().isEmpty()) {
                logActivity("Dataset already contains data. Existing records were kept.");
            } else {
                db.seedDemoData();
                logActivity("Inserted demo students, courses, and enrollments in one transaction.");
            }
            refreshAll();
        }));
        Button refresh = button("Refresh views", "#334155");
        refresh.setOnAction(e -> refreshAll());
        HBox topActions = new HBox(10, seedButton, refresh);

        studentName.setPromptText("Full name");
        studentEmail.setPromptText("Unique email");
        Button addStudent = button("Insert student", "#176a57");
        addStudent.setOnAction(e -> runUiAction("Insert student", () -> {
            String name = required(studentName.getText(), "Student name");
            String email = required(studentEmail.getText(), "Student email");
            TransactionResult tr = db.transact("Insert Student", List.of("student-email:" + email.toLowerCase(Locale.ROOT)), s -> {
                int id = s.nextStudentId();
                s.students().put(id, new Student(id, name, email.trim()));
            });
            append(activityLog, "COMMIT " + tr.sequence() + ": student inserted; " + tr.elapsedMillis() + " ms.");
            studentName.clear(); studentEmail.clear(); refreshAll();
        }));
        HBox studentForm = new HBox(8, studentName, studentEmail, addStudent);
        HBox.setHgrow(studentName, Priority.ALWAYS); HBox.setHgrow(studentEmail, Priority.ALWAYS);

        courseCode.setPromptText("Course code (e.g. CS310)");
        courseTitle.setPromptText("Course title");
        courseCredits.setPrefWidth(85);
        Button addCourse = button("Insert course", "#176a57");
        addCourse.setOnAction(e -> runUiAction("Insert course", () -> {
            String code = required(courseCode.getText(), "Course code");
            String title = required(courseTitle.getText(), "Course title");
            int credits = Integer.parseInt(courseCredits.getText().trim());
            TransactionResult tr = db.transact("Insert Course", List.of("course-code:" + code.toUpperCase(Locale.ROOT)), s -> {
                int id = s.nextCourseId(); s.courses().put(id, new Course(id, code.trim().toUpperCase(Locale.ROOT), title, credits));
            });
            append(activityLog, "COMMIT " + tr.sequence() + ": course inserted.");
            courseCode.clear(); courseTitle.clear(); refreshAll();
        }));
        HBox courseForm = new HBox(8, courseCode, courseTitle, courseCredits, addCourse);
        HBox.setHgrow(courseCode, Priority.SOMETIMES); HBox.setHgrow(courseTitle, Priority.ALWAYS);

        enrollmentStudentId.setPromptText("Student ID"); enrollmentStudentId.setPrefWidth(100);
        enrollmentCourseId.setPromptText("Course ID"); enrollmentCourseId.setPrefWidth(100);
        enrollmentSemester.setPromptText("Semester"); enrollmentSemester.setPrefWidth(170);
        enrollmentGrade.setItems(FXCollections.observableArrayList("A+", "A", "B+", "B", "C", "D", "F", "P", "I"));
        enrollmentGrade.setValue("A"); enrollmentGrade.setPrefWidth(90);
        Button addEnrollment = button("Register enrollment", "#176a57");
        addEnrollment.setOnAction(e -> runUiAction("Register enrollment", () -> {
            int sId = Integer.parseInt(enrollmentStudentId.getText().trim());
            int cId = Integer.parseInt(enrollmentCourseId.getText().trim());
            String semester = required(enrollmentSemester.getText(), "Semester");
            String grade = enrollmentGrade.getValue();
            TransactionResult tr = db.transact("Register Enrollment", List.of("course:" + cId, "student:" + sId), s -> {
                int id = s.nextEnrollmentId(); s.enrollments().put(id, new Enrollment(id, sId, cId, semester, grade));
            });
            append(activityLog, "COMMIT " + tr.sequence() + ": enrollment inserted after FK/unique validation.");
            refreshAll();
        }));
        HBox enrollmentForm = new HBox(8, enrollmentStudentId, enrollmentCourseId, enrollmentSemester, enrollmentGrade, addEnrollment);

        Button deleteStudent = button("Delete selected student + enrollments", "#9d3d44");
        deleteStudent.setOnAction(e -> runUiAction("Delete student", () -> {
            Student selected = studentTable.getSelectionModel().getSelectedItem();
            if (selected == null) throw new IllegalArgumentException("Select a student row first.");
            db.transact("Delete Student with cascade", List.of("student:" + selected.id()), s -> {
                s.students().remove(selected.id());
                s.enrollments().values().removeIf(en -> en.studentId() == selected.id());
            });
            append(activityLog, "Student " + selected.id() + " and dependent enrollment rows deleted atomically.");
            refreshAll();
        }));
        Button updateGrade = button("Update selected enrollment grade", "#8b6428");
        updateGrade.setOnAction(e -> runUiAction("Update enrollment grade", () -> {
            Enrollment selected = enrollmentTable.getSelectionModel().getSelectedItem();
            if (selected == null) throw new IllegalArgumentException("Select an enrollment row first.");
            String grade = required(newGradeField.getText(), "New grade").toUpperCase(Locale.ROOT);
            db.transact("Update Grade", List.of("enrollment:" + selected.id()), s -> {
                Enrollment old = s.enrollments().get(selected.id());
                if (old == null) throw new IllegalArgumentException("Enrollment no longer exists.");
                s.enrollments().put(old.id(), new Enrollment(old.id(), old.studentId(), old.courseId(), old.semester(), grade));
            });
            append(activityLog, "Grade changed in one validated transaction."); refreshAll();
        }));
        HBox maintenance = new HBox(8, deleteStudent, newGradeField, updateGrade);
        newGradeField.setPromptText("New grade"); newGradeField.setPrefWidth(90);

        configureStudentTable(); configureCourseTable(); configureEnrollmentTable();
        VBox studentsPanel = panel("STUDENT", studentForm, studentTable);
        VBox coursesPanel = panel("COURSE", courseForm, courseTable);
        VBox enrollmentPanel = panel("ENROLLMENT", enrollmentForm, enrollmentTable, maintenance);
        VBox content = new VBox(14, intro, topActions, studentsPanel, coursesPanel, enrollmentPanel);
        content.setPadding(new Insets(18));
        return scrollable(content);
    }

    private Node buildNormalizationTab() {
        Node intro = heading("Normalization & relational algebra", "The schema is decomposed to reduce duplication and protect relationships with keys and constraints.");
        GridPane schemaCards = new GridPane(); schemaCards.setHgap(12); schemaCards.setVgap(12);
        schemaCards.getColumnConstraints().addAll(new ColumnConstraints(350), new ColumnConstraints(350), new ColumnConstraints(450));
        schemaCards.add(schemaCard("STUDENT", "student_id  PRIMARY KEY\nname\nemail  UNIQUE", "Functional dependency: student_id → name, email"), 0, 0);
        schemaCards.add(schemaCard("COURSE", "course_id  PRIMARY KEY\ncourse_code  UNIQUE\ntitle, credits", "Functional dependency: course_id → course_code, title, credits"), 1, 0);
        schemaCards.add(schemaCard("ENROLLMENT", "enrollment_id  PRIMARY KEY\nstudent_id  FOREIGN KEY\ncourse_id  FOREIGN KEY\nsemester, grade\nUNIQUE(student_id, course_id, semester)", "Relationship attributes belong to the association, not duplicated student/course rows."), 2, 0);

        VBox theory = new VBox(7,
                subheading("Why this design is normalized"),
                body("1NF: every stored field is a single value; courses are not kept in repeating columns such as course_1, course_2, course_3."),
                body("2NF: Enrollment facts depend on the complete student-course-semester relationship; student names and course titles are stored in their own relations."),
                body("3NF: non-key student attributes depend on Student's key; course details depend on Course's key, avoiding transitive duplication in Enrollment."),
                body("Integrity rules: primary keys identify rows, unique email/course-code rules prevent duplicates, and foreign keys stop orphan enrollments."));
        theory.setPadding(new Insets(15)); theory.setStyle(cardStyle());

        queryGrade.setItems(FXCollections.observableArrayList("All grades", "A+", "A", "B+", "B", "C", "D", "F", "P", "I"));
        queryGrade.setValue("All grades");
        Button runJoin = button("Run SELECT + JOIN", "#2459a9");
        runJoin.setOnAction(e -> runUiAction("Run relational JOIN", () -> {
            String g = "All grades".equals(queryGrade.getValue()) ? "" : queryGrade.getValue();
            joinRows.setAll(db.joinedEnrollments(g));
            append(activityLog, "Relational query ran: Student ⋈ Enrollment ⋈ Course" + (g.isBlank() ? "" : " WHERE grade='" + g + "'") + "; " + joinRows.size() + " rows.");
        }));
        HBox queryControls = new HBox(10, new Label("Grade filter:"), queryGrade, runJoin);
        queryControls.setAlignment(Pos.CENTER_LEFT);
        configureJoinTable();
        VBox queryPanel = panel("QUERY RESULT: INNER JOIN", queryControls, joinTable);
        VBox content = new VBox(14, intro, schemaCards, theory, queryPanel);
        content.setPadding(new Insets(18));
        runJoin.fire();
        return scrollable(content);
    }

    private Node buildTransactionTab() {
        Node intro = heading("ACID transaction lab", "Run controlled experiments that commit valid state or discard invalid state before persistence.");
        GridPane acidGrid = new GridPane(); acidGrid.setHgap(12); acidGrid.setVgap(12);
        acidGrid.add(acidCard("ATOMICITY", "Multiple changes are staged together. If any constraint fails, none of that candidate state is committed."), 0, 0);
        acidGrid.add(acidCard("CONSISTENCY", "Primary keys, unique emails/course codes, foreign keys, credits, grades, and enrollment uniqueness are validated before commit."), 1, 0);
        acidGrid.add(acidCard("ISOLATION", "Resource locks plus a fair read/write state lock protect concurrent state changes and readers."), 0, 1);
        acidGrid.add(acidCard("DURABILITY", "The WAL commit record is forced to disk before the table snapshots are replaced."), 1, 1);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setPercentWidth(50);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setPercentWidth(50);
        acidGrid.getColumnConstraints().addAll(c1, c2);

        Button commitDemo = button("Run valid multi-table COMMIT", "#176a57");
        commitDemo.setOnAction(e -> runUiAction("ACID commit demo", () -> runAcidCommitDemo()));
        Button rollbackDemo = button("Run ATOMICITY rollback test", "#9d3d44");
        rollbackDemo.setOnAction(e -> runUiAction("Rollback test", () -> runRollbackDemo()));
        Button consistencyDemo = button("Trigger UNIQUE constraint failure", "#8b6428");
        consistencyDemo.setOnAction(e -> runUiAction("Unique constraint test", () -> runUniqueFailureDemo()));
        Button clearLog = button("Clear lab log", "#334155"); clearLog.setOnAction(e -> transactionLog.clear());
        HBox actions = new HBox(10, commitDemo, rollbackDemo, consistencyDemo, clearLog);

        VBox twoPhase = new VBox(6, subheading("Locking protocol used by KernelDB"),
                body("1. A transaction declares resource keys (for example student:4 or course:2)."),
                body("2. All requested locks are acquired in sorted key order, preventing circular-wait deadlocks for these declared locks."),
                body("3. Locks are retained through validation and commit, then released in reverse order (strict 2PL style)."),
                body("4. A fair lock queue reduces barging. Fairness helps with starvation risk but does not promise that every thread always completes within a fixed time."));
        twoPhase.setPadding(new Insets(14)); twoPhase.setStyle(cardStyle());
        VBox content = new VBox(14, intro, acidGrid, actions, twoPhase, sectionHeader("TRANSACTION EVENT LOG"), transactionLog);
        content.setPadding(new Insets(18));
        return scrollable(content);
    }

    private Node buildConcurrencyTab() {
        Node intro = heading("Concurrency, locking & starvation", "Use real Java background threads. The lab reports measured outcomes rather than pre-filled sample numbers.");
        concurrentButton = button("Run 4 database workers (60 writes)", "#2459a9");
        concurrentButton.setOnAction(e -> runConcurrentDatabaseTest());
        fairnessButton = button("Compare fair vs non-fair lock", "#8b6428");
        fairnessButton.setOnAction(e -> runFairnessTest());
        pageRequestsButton = button("Run 4 random page-request workers", "#176a57");
        pageRequestsButton.setOnAction(e -> runConcurrentPageRequestTest());
        HBox actions = new HBox(10, concurrentButton, pageRequestsButton, fairnessButton);
        VBox concepts = new VBox(7,
                subheading("What the buttons prove"),
                body("Database workers: four independent Java threads concurrently submit inserts against the same live database engine. Unique row-lock keys reduce unnecessary conflict; the commit lock protects the state update and WAL."),
                body("Page-request workers: four Java threads choose page indexes from the real 4 KB kernel.pages file and request them through the shared 32-page LRU buffer. Repeated requests can be cache hits; a miss reads that page through FileChannel."),
                body("Fairness experiment: the same number of workers repeatedly compete for one critical-section lock. The app measures each worker's completed operations and observed wait times for fair and non-fair ReentrantLock configurations."),
                body("Starvation: a thread is starved when it waits indefinitely or is repeatedly denied a needed resource while others progress. A short lab measures fairness symptoms; it cannot prove that starvation is impossible in all schedules."),
                body("Deadlock control: KernelDB acquires declared resource locks in sorted order and releases them after commit, removing circular-wait among those locks."));
        concepts.setPadding(new Insets(14)); concepts.setStyle(cardStyle());
        VBox content = new VBox(14, intro, actions, concepts, sectionHeader("LIVE EXPERIMENT OUTPUT"), concurrencyLog);
        content.setPadding(new Insets(18));
        return scrollable(content);
    }

    private Node buildRecoveryTab() {
        Node intro = heading("Write-ahead logging, recovery & measurements", "Inspect durable commit records and re-load the last committed state from the WAL.");
        Button recover = button("Simulate restart: replay WAL", "#2459a9");
        recover.setOnAction(e -> runUiAction("WAL recovery", () -> {
            String message = db.recoverFromWal();
            refreshAll();
            append(recoveryLog, now() + " | " + message);
            append(recoveryLog, "Recovery is a reload/replay simulation; the app process is not forcibly crashed.");
        }));
        Button showWal = button("Read recent WAL entries", "#334155");
        showWal.setOnAction(e -> runUiAction("Read WAL", () -> {
            append(recoveryLog, "--- Last committed log records ---");
            for (String line : db.walTail(8)) append(recoveryLog, line);
        }));
        Button refreshMetrics = button("Refresh metrics", "#176a57");
        refreshMetrics.setOnAction(e -> refreshAll());
        HBox actions = new HBox(10, recover, showWal, refreshMetrics);

        GridPane metricGrid = new GridPane(); metricGrid.setHgap(10); metricGrid.setVgap(10);
        metricGrid.getColumnConstraints().addAll(new ColumnConstraints(260), new ColumnConstraints(260), new ColumnConstraints(260));
        metricGrid.add(metricCard("CURRENT WAL SEQUENCE", sequenceMetric), 0, 0);
        metricGrid.add(metricCard("LOCK ACQUISITIONS", lockCountMetric), 1, 0);
        metricGrid.add(metricCard("MAX OBSERVED LOCK WAIT (ms)", maxWaitMetric), 2, 0);
        metricGrid.add(metricCard("4 KB PAGES ON DISK", pageCountMetric), 0, 1);
        metricGrid.add(metricCard("CACHE HITS", cacheHitsMetric), 1, 1);
        metricGrid.add(metricCard("CACHE MISSES", cacheMissesMetric), 2, 1);
        metricGrid.add(metricCard("FILE PAGE READS", fileReadsMetric), 0, 2);
        metricGrid.add(metricCard("LRU EVICTIONS", evictionsMetric), 1, 2);
        metricGrid.add(metricCard("CACHE HIT RATE", hitRateMetric), 2, 2);

        Button benchmarkPages = button("Run 3-pass page I/O benchmark", "#2459a9");
        benchmarkPages.setOnAction(e -> runPageBufferBenchmark());
        pageBenchmarkButton = benchmarkPages;
        Button resetPageMetrics = button("Clear LRU buffer + reset metrics", "#334155");
        resetPageMetrics.setOnAction(e -> runUiAction("Reset page buffer", () -> {
            db.resetPageMetrics(); refreshAll();
            append(recoveryLog, "LRU cache and page-I/O counters reset; persisted page file unchanged.");
        }));
        HBox pageActions = new HBox(10, benchmarkPages, resetPageMetrics);

        VBox files = new VBox(7, subheading("Files managed by the custom storage layer"),
                body("kernel.wal — append-only COMMIT records containing a recoverable serialized state."),
                body("kernel.snapshot — latest serialized database snapshot."),
                body("students.tbl, courses.tbl, enrollments.tbl — separate persistent normalized relation files."),
                body("kernel.pages — fixed 4096-byte page images. The page lab uses a 32-page access-order LRU buffer and counts application-level FileChannel page reads, cache hits, misses, and evictions."),
                body("The recovery button reloads the latest valid committed WAL state and rewrites table snapshots. This is a teaching-scale redo-recovery design; production systems need WAL truncation/checkpoints, checksums, fault injection, and stronger crash-consistency testing."));
        files.setPadding(new Insets(14)); files.setStyle(cardStyle());
        VBox content = new VBox(14, intro, actions, metricGrid, pageActions, files, sectionHeader("RECOVERY & LOG OUTPUT"), recoveryLog);
        content.setPadding(new Insets(18));
        return scrollable(content);
    }

    private void runAcidCommitDemo() throws Exception {
        long suffix = System.currentTimeMillis() % 1_000_000;
        String name = "ACID Demo Student " + suffix;
        String email = "acid.demo." + suffix + "@kernel.local";
        String code = "AC" + suffix;
        String title = "ACID Demo Course " + suffix;
        TransactionResult tr = db.transact("ACID multi-table commit", List.of("student-email:" + email, "course-code:" + code), s -> {
            int studentId = s.nextStudentId();
            s.students().put(studentId, new Student(studentId, name, email));
            int courseId = s.nextCourseId();
            s.courses().put(courseId, new Course(courseId, code, title, 3));
            int enrollmentId = s.nextEnrollmentId();
            s.enrollments().put(enrollmentId, new Enrollment(enrollmentId, studentId, courseId, "2026-ACID-Lab", "A"));
        });
        append(transactionLog, "COMMIT successful | seq=" + tr.sequence() + " | inserted 1 Student + 1 Course + 1 Enrollment atomically | " + tr.elapsedMillis() + " ms.");
        append(transactionLog, "The next WAL snapshot contains all three changes. Refresh counts or inspect the JOIN tab.");
        refreshAll();
    }

    private void runRollbackDemo() throws Exception {
        int before = db.readSnapshot().students().size();
        long suffix = System.currentTimeMillis() % 1_000_000;
        String email = "rollback.demo." + suffix + "@kernel.local";
        try {
            db.transact("Atomicity rollback test", List.of("student-email:" + email, "enrollment:999999"), s -> {
                int id = s.nextStudentId();
                s.students().put(id, new Student(id, "Must Not Persist " + suffix, email));
                int enrollmentId = s.nextEnrollmentId();
                // Deliberate FK violation forces validation to fail after the student was staged.
                s.enrollments().put(enrollmentId, new Enrollment(enrollmentId, 999999, 999999, "Rollback-Lab", "A"));
            });
            append(transactionLog, "UNEXPECTED: rollback test committed; investigate validation.");
        } catch (Exception expected) {
            int after = db.readSnapshot().students().size();
            append(transactionLog, "ROLLBACK verified | reason: " + expected.getMessage());
            append(transactionLog, "Student count before=" + before + ", after=" + after + ". The valid student staged before the FK error was not persisted.");
        }
        refreshAll();
    }

    private void runUniqueFailureDemo() throws Exception {
        List<Student> existing = db.students();
        if (existing.isEmpty()) throw new IllegalStateException("Add a student first.");
        String duplicateEmail = existing.get(0).email();
        int before = db.readSnapshot().students().size();
        try {
            db.transact("Unique email constraint test", List.of("student-email:" + duplicateEmail.toLowerCase(Locale.ROOT)), s -> {
                int id = s.nextStudentId();
                s.students().put(id, new Student(id, "Duplicate Email Test", duplicateEmail));
            });
            append(transactionLog, "UNEXPECTED: duplicate email was accepted.");
        } catch (Exception expected) {
            append(transactionLog, "CONSISTENCY check rejected duplicate email: " + expected.getMessage());
            append(transactionLog, "Student count remains " + before + "; no invalid state was committed.");
        }
        refreshAll();
    }

    private void runConcurrentDatabaseTest() {
        if (concurrentButton != null) concurrentButton.setDisable(true);
        append(concurrencyLog, now() + " | Starting four real Java worker threads...");
        labExecutor.submit(() -> {
            long started = System.nanoTime();
            int workers = 4, perWorker = 15;
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            AtomicInteger completed = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            java.util.ArrayList<Future<?>> futures = new java.util.ArrayList<>();
            try {
                for (int w = 1; w <= workers; w++) {
                    final int workerId = w;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        for (int i = 1; i <= perWorker; i++) {
                            String email = String.format(Locale.ROOT, "loadtest.w%d.%03d@bench.kernel", workerId, i);
                            final int taskIndex = i;
                            try {
                                db.transact("worker-" + workerId + " insert", List.of("student-email:" + email), state -> {
                                    int id = state.nextStudentId();
                                    state.students().put(id, new Student(id, "Load Test W" + workerId + " #" + taskIndex, email));
                                });
                                completed.incrementAndGet();
                            } catch (Exception ex) {
                                failed.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
                if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not become ready.");
                start.countDown();
                for (Future<?> future : futures) future.get();
            } catch (Exception ex) {
                failed.incrementAndGet();
            } finally {
                pool.shutdownNow();
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            int inserted = completed.get();
            try {
                db.transact("Cleanup load-test records", List.of("maintenance:loadtest-cleanup"), state ->
                        state.students().values().removeIf(s -> s.email().startsWith("loadtest.")));
            } catch (Exception ex) {
                failed.incrementAndGet();
            }
            int finalCompleted = completed.get();
            int finalFailed = failed.get();
            Platform.runLater(() -> {
                append(concurrencyLog, "Completed transactions: " + finalCompleted + " / " + (workers * perWorker));
                append(concurrencyLog, "Failed transactions: " + finalFailed);
                append(concurrencyLog, "Elapsed time (including scheduling/commit work): " + elapsed + " ms");
                append(concurrencyLog, "Temporary worker rows inserted=" + inserted + " then removed in a cleanup transaction; normal demo rows retained.");
                append(concurrencyLog, "Engine conflict reapplications observed: " + db.conflictRetryCount());
                append(concurrencyLog, "Fair resource-lock acquisitions observed: " + db.lockAcquisitionCount());
                append(concurrencyLog, "Do not compare this one run as a benchmark conclusion; repeat with fixed dataset sizes and report measured values.");
                if (concurrentButton != null) concurrentButton.setDisable(false);
                refreshAll();
            });
        });
    }

    private void runFairnessTest() {
        if (fairnessButton != null) fairnessButton.setDisable(true);
        append(concurrencyLog, now() + " | Comparing fair and non-fair lock acquisition...");
        labExecutor.submit(() -> {
            String output;
            try {
                FairnessExperiment.Result unfair = FairnessExperiment.run(false, 4, 25);
                FairnessExperiment.Result fair = FairnessExperiment.run(true, 4, 25);
                StringBuilder b = new StringBuilder();
                appendExperiment(b, unfair);
                appendExperiment(b, fair);
                b.append("Interpretation: fair ReentrantLock discourages barging and generally improves queue fairness. This short experiment reports actual waits; it is not proof that starvation is impossible under every OS schedule.\n");
                output = b.toString();
            } catch (Exception ex) {
                output = "Fairness experiment failed: " + ex.getMessage() + "\n";
            }
            String finalOutput = output;
            Platform.runLater(() -> {
                append(concurrencyLog, finalOutput);
                if (fairnessButton != null) fairnessButton.setDisable(false);
            });
        });
    }

    private static void appendExperiment(StringBuilder b, FairnessExperiment.Result result) {
        b.append("\nLOCK MODE: ").append(result.fairLock() ? "FAIR" : "NON-FAIR")
                .append(" | workers=").append(result.workers())
                .append(" | operations/worker=").append(result.operationsPerWorker())
                .append(" | elapsed=").append(result.elapsedMillis()).append(" ms")
                .append(" | max observed single wait=").append(result.maxObservedWaitMillis()).append(" ms\n");
        for (FairnessExperiment.WorkerResult w : result.workerResults()) {
            b.append("  Worker ").append(w.workerId()).append(": completed ").append(w.completedOperations())
                    .append(" | max wait ").append(w.maxWaitMillis()).append(" ms | cumulative wait ")
                    .append(w.totalWaitMillis()).append(" ms\n");
        }
    }

    private void runConcurrentPageRequestTest() {
        if (pageRequestsButton != null) pageRequestsButton.setDisable(true);
        append(concurrencyLog, now() + " | Starting four workers against the persisted 4 KB page file...");
        labExecutor.submit(() -> {
            String output;
            try {
                int pages = db.pageMetrics().pagesOnDisk();
                if (pages < 1) throw new IllegalStateException("The database page file has no pages yet.");
                db.resetPageMetrics();
                int workers = 4, requestsPerWorker = 25;
                ExecutorService pool = Executors.newFixedThreadPool(workers);
                CountDownLatch ready = new CountDownLatch(workers);
                CountDownLatch start = new CountDownLatch(1);
                java.util.ArrayList<Future<?>> futures = new java.util.ArrayList<>();
                AtomicInteger requests = new AtomicInteger();
                try {
                    for (int w = 1; w <= workers; w++) {
                        final int workerId = w;
                        futures.add(pool.submit(() -> {
                            ready.countDown(); start.await();
                            for (int i = 0; i < requestsPerWorker; i++) {
                                int pageIndex = ThreadLocalRandom.current().nextInt(pages);
                                db.requestPage(pageIndex);
                                requests.incrementAndGet();
                            }
                            return null;
                        }));
                    }
                    if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not become ready.");
                    long started = System.nanoTime();
                    start.countDown();
                    for (Future<?> future : futures) future.get();
                    long elapsed = (System.nanoTime() - started) / 1_000_000;
                    PageBufferManager.Metrics m = db.pageMetrics();
                    output = "Page workers completed: " + requests.get() + " requests (4 workers × 25), pages on disk=" + m.pagesOnDisk() + ", elapsed=" + elapsed + " ms\n"
                            + "Cache hits=" + m.hits() + ", misses=" + m.misses() + ", FileChannel page reads=" + m.filePageReads()
                            + ", hit rate=" + String.format(Locale.ROOT, "%.1f", m.hitRatePercent()) + "%\n"
                            + "These are application-level file-page reads, not a count of physical disk-device operations.\n";
                } finally { pool.shutdownNow(); }
            } catch (Exception ex) {
                output = "Page-request worker experiment failed: " + ex.getMessage() + "\n";
            }
            String finalOutput = output;
            Platform.runLater(() -> {
                append(concurrencyLog, finalOutput);
                if (pageRequestsButton != null) pageRequestsButton.setDisable(false);
                refreshAll();
            });
        });
    }

    private void runPageBufferBenchmark() {
        if (pageBenchmarkButton != null) pageBenchmarkButton.setDisable(true);
        append(recoveryLog, now() + " | Starting 3-pass sequential page benchmark...");
        labExecutor.submit(() -> {
            String output;
            try {
                int pages = db.pageMetrics().pagesOnDisk();
                if (pages < 1) throw new IllegalStateException("The database page file has no pages yet.");
                db.resetPageMetrics();
                long started = System.nanoTime();
                for (int pass = 1; pass <= 3; pass++) {
                    for (int p = 0; p < pages; p++) db.requestPage(p);
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                PageBufferManager.Metrics m = db.pageMetrics();
                output = "3 sequential passes over " + pages + " pages = " + (pages * 3L) + " page requests in " + elapsed + " ms.\n"
                        + "Requests=" + m.requests() + ", hits=" + m.hits() + ", misses=" + m.misses()
                        + ", FileChannel reads=" + m.filePageReads() + ", LRU evictions=" + m.evictions()
                        + ", hit rate=" + String.format(Locale.ROOT, "%.1f", m.hitRatePercent()) + "%\n"
                        + (pages <= m.capacity() ? "Expected: after the first pass warms the cache, later passes should mostly hit." : "The working set is larger than the 32-page buffer; sequential passes can evict earlier pages before reuse.") + "\n";
            } catch (Exception ex) {
                output = "Page I/O benchmark failed: " + ex.getMessage() + "\n";
            }
            String finalOutput = output;
            Platform.runLater(() -> {
                append(recoveryLog, finalOutput);
                if (pageBenchmarkButton != null) pageBenchmarkButton.setDisable(false);
                refreshAll();
            });
        });
    }

    private void configureTables() {
        studentTable.setItems(studentRows); courseTable.setItems(courseRows);
        enrollmentTable.setItems(enrollmentRows); joinTable.setItems(joinRows);
        for (TableView<?> t : List.of(studentTable, courseTable, enrollmentTable, joinTable)) {
            t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            t.setPrefHeight(225);
            t.setStyle("-fx-background-color: #101a2c; -fx-control-inner-background: #101a2c; -fx-text-background-color: #e2e8f0;");
        }
    }

    private void configureStudentTable() {
        TableColumn<Student, Number> id = new TableColumn<>("ID"); id.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().id()));
        TableColumn<Student, String> name = new TableColumn<>("Name"); name.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().name()));
        TableColumn<Student, String> email = new TableColumn<>("Email"); email.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().email()));
        studentTable.getColumns().setAll(id, name, email);
    }

    private void configureCourseTable() {
        TableColumn<Course, Number> id = new TableColumn<>("ID"); id.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().id()));
        TableColumn<Course, String> code = new TableColumn<>("Code"); code.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().code()));
        TableColumn<Course, String> title = new TableColumn<>("Title"); title.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().title()));
        TableColumn<Course, Number> credits = new TableColumn<>("Credits"); credits.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().credits()));
        courseTable.getColumns().setAll(id, code, title, credits);
    }

    private void configureEnrollmentTable() {
        TableColumn<Enrollment, Number> id = new TableColumn<>("ID"); id.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().id()));
        TableColumn<Enrollment, Number> student = new TableColumn<>("Student ID"); student.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().studentId()));
        TableColumn<Enrollment, Number> course = new TableColumn<>("Course ID"); course.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().courseId()));
        TableColumn<Enrollment, String> semester = new TableColumn<>("Semester"); semester.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().semester()));
        TableColumn<Enrollment, String> grade = new TableColumn<>("Grade"); grade.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().grade()));
        enrollmentTable.getColumns().setAll(id, student, course, semester, grade);
    }

    private void configureJoinTable() {
        TableColumn<JoinedEnrollment, Number> id = new TableColumn<>("Enrollment ID"); id.setCellValueFactory(d -> new ReadOnlyIntegerWrapper(d.getValue().enrollmentId()));
        TableColumn<JoinedEnrollment, String> student = new TableColumn<>("Student"); student.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().studentName()));
        TableColumn<JoinedEnrollment, String> course = new TableColumn<>("Course"); course.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().courseTitle()));
        TableColumn<JoinedEnrollment, String> semester = new TableColumn<>("Semester"); semester.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().semester()));
        TableColumn<JoinedEnrollment, String> grade = new TableColumn<>("Grade"); grade.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().grade()));
        joinTable.getColumns().setAll(id, student, course, semester, grade);
    }

    private void configureInputs() {
        for (TextField field : List.of(studentName, studentEmail, courseCode, courseTitle,
                courseCredits, enrollmentStudentId, enrollmentCourseId, enrollmentSemester, newGradeField)) {
            field.setStyle("-fx-background-color: #101a2c; -fx-text-fill: #e2e8f0; -fx-prompt-text-fill: #7d8ca6; -fx-border-color: #2b3a53; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 8;");
        }
        for (ComboBox<?> combo : List.of(enrollmentGrade, queryGrade)) {
            combo.setStyle("-fx-background-color: #101a2c; -fx-text-fill: #e2e8f0;");
        }
    }

    private void refreshAll() {
        if (db == null) return;
        studentRows.setAll(db.students()); courseRows.setAll(db.courses()); enrollmentRows.setAll(db.enrollments());
        studentsMetric.setText(Integer.toString(studentRows.size()));
        coursesMetric.setText(Integer.toString(courseRows.size()));
        enrollmentsMetric.setText(Integer.toString(enrollmentRows.size()));
        commitsMetric.setText(Long.toString(db.commitCount()));
        rollbacksMetric.setText(Long.toString(db.rollbackCount()));
        sequenceMetric.setText(Long.toString(db.currentSequence()));
        lockCountMetric.setText(Long.toString(db.lockAcquisitionCount()));
        maxWaitMetric.setText(Long.toString(db.maxSingleLockWaitMillis()));
        String pageSummary = "Page metrics unavailable";
        try {
            PageBufferManager.Metrics pm = db.pageMetrics();
            pageCountMetric.setText(Integer.toString(pm.pagesOnDisk()));
            cacheHitsMetric.setText(Long.toString(pm.hits()));
            cacheMissesMetric.setText(Long.toString(pm.misses()));
            fileReadsMetric.setText(Long.toString(pm.filePageReads()));
            evictionsMetric.setText(Long.toString(pm.evictions()));
            hitRateMetric.setText(String.format(Locale.ROOT, "%.1f%%", pm.hitRatePercent()));
            pageSummary = "Pages " + pm.pagesOnDisk() + " | cache hits " + pm.hits() + " | misses " + pm.misses();
        } catch (Exception ex) {
            pageCountMetric.setText("ERR");
            pageSummary = "Page metrics error: " + ex.getMessage();
        }
        String storageWarning = db.storageWarning();
        statusLabel.setText("Ready  •  WAL seq " + db.currentSequence() + "  •  Locks " + db.lockAcquisitionCount()
                + "  •  Max lock wait " + db.maxSingleLockWaitMillis() + " ms  •  " + pageSummary
                + "  •  Data: " + db.dataDirectory() + (storageWarning.isBlank() ? "" : "  •  WARNING: " + storageWarning));
    }

    private void runUiAction(String label, CheckedRunnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            logActivity(label + " failed: " + ex.getMessage());
            append(transactionLog, "FAILED / ROLLED BACK: " + label + " | " + ex.getMessage());
            statusLabel.setText("Operation rejected: " + ex.getMessage());
            refreshAll();
        }
    }

    private void logActivity(String message) {
        append(activityLog, now() + " | " + message);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " cannot be empty.");
        return value.trim();
    }

    private static void append(TextArea area, String message) {
        if (area == null) return;
        area.appendText((area.getText().isEmpty() ? "" : "\n") + message);
        area.positionCaret(area.getLength());
    }

    private static String now() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")); }

    private static TextArea logArea(int rows) {
        TextArea area = new TextArea();
        area.setEditable(false); area.setWrapText(true); area.setPrefRowCount(rows);
        area.setStyle("-fx-control-inner-background: #08101f; -fx-text-fill: #d5e0f0; -fx-font-family: 'Consolas'; -fx-font-size: 11px; -fx-border-color: #27354d;");
        VBox.setVgrow(area, Priority.ALWAYS);
        return area;
    }

    private static VBox heading(String title, String subtitle) {
        Label t = new Label(title); t.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #f1f5f9;");
        Label s = new Label(subtitle); s.setWrapText(true); s.setStyle("-fx-font-size: 12px; -fx-text-fill: #aab7cf;");
        return new LabelGroup(t, s).node();
    }

    private static Label subheading(String text) {
        Label l = new Label(text); l.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #e2e8f0;"); return l;
    }
    private static Label body(String text) {
        Label l = new Label(text); l.setWrapText(true); l.setStyle("-fx-font-size: 12px; -fx-text-fill: #b8c7df;"); return l;
    }
    private static Label sectionHeader(String text) {
        Label l = new Label(text); l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #8ab4f8; -fx-padding: 3 0 1 0;"); return l;
    }
    private static Label metricValue(String text) {
        Label l = new Label(text); l.setStyle("-fx-text-fill: #f8fafc; -fx-font-size: 20px; -fx-font-weight: bold;"); return l;
    }
    private static VBox metricCard(String title, Label value) {
        Label l = new Label(title); l.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 9px; -fx-font-weight: bold;");
        VBox card = new VBox(5, l, value); card.setPadding(new Insets(9, 11, 9, 11));
        card.setStyle("-fx-background-color: #18243a; -fx-border-color: #2a3a54; -fx-border-radius: 8; -fx-background-radius: 8;");
        card.setMinWidth(88); return card;
    }
    private static VBox schemaCard(String title, String schema, String fd) {
        Label t = subheading(title); Label relation = new Label(schema); relation.setStyle("-fx-text-fill: #e2e8f0; -fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        Label explanation = body(fd);
        VBox b = new VBox(9, t, relation, new Separator(), explanation); b.setPadding(new Insets(13)); b.setMinHeight(165); b.setStyle(cardStyle()); return b;
    }
    private static VBox acidCard(String title, String description) {
        return new VBox(7, subheading(title), body(description));
    }
    private static VBox panel(String title, Node... nodes) {
        VBox box = new VBox(9); box.getChildren().add(sectionHeader(title)); box.getChildren().addAll(nodes); box.setPadding(new Insets(13)); box.setStyle(cardStyle()); return box;
    }
    private static String cardStyle() {
        return "-fx-background-color: #111b2e; -fx-border-color: #293851; -fx-border-radius: 9; -fx-background-radius: 9;";
    }
    private static VBox labelGroup(String title, String subtitle) {
        return new VBox(5, heading(title, subtitle));
    }
    private static Button button(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 9 13 9 13; -fx-background-radius: 6;");
        b.setOnMouseEntered(e -> b.setOpacity(0.88)); b.setOnMouseExited(e -> b.setOpacity(1.0));
        return b;
    }
    private static Node scrollable(Node content) {
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true); scroll.setStyle("-fx-background: #0b1120; -fx-background-color: #0b1120;");
        return scroll;
    }

    private record LabelGroup(Label title, Label subtitle) {
        VBox node() {
            VBox b = new VBox(5, title, subtitle); b.setPadding(new Insets(0, 0, 2, 0)); return b;
        }
    }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }

    @Override
    public void stop() {
        labExecutor.shutdownNow();
    }

    public static void main(String[] args) { launch(args); }
}
