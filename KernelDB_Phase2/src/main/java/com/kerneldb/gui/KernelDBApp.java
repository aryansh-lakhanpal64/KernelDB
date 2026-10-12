package com.kerneldb.gui;

import com.kerneldb.core.BenchmarkResult;
import com.kerneldb.core.ConcurrentTestResult;
import com.kerneldb.core.ImportResult;
import com.kerneldb.core.KernelDBEngine;
import com.kerneldb.core.KernelDBMetrics;
import com.kerneldb.csv.CsvService;
import com.kerneldb.model.Student;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;

/** Main JavaFX dashboard for the Phase-2 KernelDB prototype. */
public class KernelDBApp extends Application {
    private static final String BLUE = "#143769";
    private static final String LIGHT_BLUE = "#EEF4FB";
    private static final String BORDER = "#DCE4EF";
    private static final String TEXT = "#1F2937";
    private static final String MUTED = "#64748B";

    private KernelDBEngine engine;
    private Stage primaryStage;
    private final ObservableList<Student> students = FXCollections.observableArrayList();
    private FilteredList<Student> filteredStudents;
    private TableView<Student> studentTable;
    private TextField idField;
    private TextField nameField;
    private TextField courseField;
    private TextField marksField;
    private TextField attendanceField;
    private TextField filterField;
    private Label recordCountValue;
    private Label pageCountValue;
    private Label cacheHitValue;
    private Label cacheMissValue;
    private Label fileReadValue;
    private Label fileWriteValue;
    private Label workerValue;
    private Label heapValue;
    private ListView<String> bufferList;
    private TextArea activityLog;
    private Label statusLabel;
    private Timeline refreshTimer;
    private volatile boolean backgroundJobRunning;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;
        Path dataDirectory = Path.of("data");
        Files.createDirectories(dataDirectory);
        engine = new KernelDBEngine(dataDirectory.resolve("students.db"));

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #F4F7FB; -fx-font-family: 'Segoe UI', 'Arial';");
        root.setTop(buildHeader());
        root.setCenter(buildMainContent());
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 1450, 880);
        stage.setTitle("KernelDB | OS–DBMS Storage Engine");
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();

        refreshTable();
        refreshMonitoring();
        log("KernelDB Phase 2 started.");
        log("Database file: " + engine.databasePath());
        log("Tip: choose 'Load Sample CSV' to import the included student dataset.");

        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            if (!backgroundJobRunning) refreshMonitoring();
        }));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
    }

    private VBox buildHeader() {
        VBox wrapper = new VBox(12);
        wrapper.setPadding(new Insets(20, 22, 17, 22));
        wrapper.setStyle("-fx-background-color: white; -fx-border-color: transparent transparent " + BORDER + " transparent; -fx-border-width: 0 0 1 0;");

        HBox titleRow = new HBox(14);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("K");
        mark.setMinSize(48, 48);
        mark.setAlignment(Pos.CENTER);
        mark.setStyle("-fx-background-color: " + BLUE + "; -fx-background-radius: 13; -fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold;");

        VBox titleBlock = new VBox(3);
        Label title = new Label("KernelDB");
        title.setStyle("-fx-text-fill: " + BLUE + "; -fx-font-size: 26px; -fx-font-weight: bold;");
        Label subtitle = new Label("OS–DBMS Integrated Storage Engine  •  Phase 2 Working Prototype");
        subtitle.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 12px;");
        titleBlock.getChildren().addAll(title, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label stack = new Label("JAVA  ·  JAVAFX  ·  MAVEN  ·  CUSTOM .DB FILE");
        stack.setStyle("-fx-text-fill: " + BLUE + "; -fx-background-color: " + LIGHT_BLUE + "; -fx-background-radius: 7; -fx-padding: 9 12; -fx-font-size: 10px; -fx-font-weight: bold;");
        titleRow.getChildren().addAll(mark, titleBlock, spacer, stack);

        HBox stats = new HBox(10,
                statCard("STUDENT RECORDS", () -> recordCountValue = new Label("0")),
                statCard("DATABASE PAGES", () -> pageCountValue = new Label("0")),
                statCard("CACHE HITS", () -> cacheHitValue = new Label("0")),
                statCard("CACHE MISSES", () -> cacheMissValue = new Label("0")),
                statCard("FILE READ CALLS", () -> fileReadValue = new Label("0")),
                statCard("FILE WRITE CALLS", () -> fileWriteValue = new Label("0")));
        for (javafx.scene.Node node : stats.getChildren()) HBox.setHgrow(node, Priority.ALWAYS);
        wrapper.getChildren().addAll(titleRow, stats);
        return wrapper;
    }

    private VBox statCard(String heading, java.util.function.Supplier<Label> valueSupplier) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(10, 13, 11, 13));
        card.setMinHeight(66);
        card.setStyle("-fx-background-color: #F8FAFD; -fx-background-radius: 10; -fx-border-color: " + BORDER + "; -fx-border-radius: 10;");
        Label label = new Label(heading);
        label.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 9px; -fx-font-weight: bold;");
        Label value = valueSupplier.get();
        value.setStyle("-fx-text-fill: " + BLUE + "; -fx-font-size: 22px; -fx-font-weight: bold;");
        card.getChildren().addAll(label, value);
        return card;
    }

    private VBox buildMainContent() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16, 22, 12, 22));
        HBox main = new HBox(14);
        main.setAlignment(Pos.TOP_CENTER);
        main.setMinHeight(500);
        main.getChildren().addAll(buildRecordEditor(), buildStudentArea(), buildMonitorArea());
        HBox.setHgrow(main.getChildren().get(0), Priority.NEVER);
        HBox.setHgrow(main.getChildren().get(1), Priority.ALWAYS);
        HBox.setHgrow(main.getChildren().get(2), Priority.NEVER);
        content.getChildren().addAll(main, buildOperationsBar());
        VBox.setVgrow(main, Priority.ALWAYS);
        return content;
    }

    private VBox buildRecordEditor() {
        VBox card = panel("RECORD OPERATIONS", "Add, search, update or delete a student record");
        card.setPrefWidth(285);
        card.setMinWidth(270);
        GridPane form = new GridPane();
        form.setHgap(7);
        form.setVgap(6);
        form.setPadding(new Insets(10, 0, 8, 0));
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(70);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelColumn, fieldColumn);

        idField = input("e.g. 1001");
        nameField = input("Student name");
        courseField = input("e.g. CSE");
        marksField = input("0–100");
        attendanceField = input("0–100");
        addFormRow(form, 0, "Student ID", idField);
        addFormRow(form, 1, "Name", nameField);
        addFormRow(form, 2, "Course", courseField);
        addFormRow(form, 3, "Marks", marksField);
        addFormRow(form, 4, "Attendance", attendanceField);

        Button add = button("Insert Record", BLUE, "#FFFFFF");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> insertRecord());
        Button update = button("Update Selected / ID", "#EAF1FB", BLUE);
        update.setMaxWidth(Double.MAX_VALUE);
        update.setOnAction(event -> updateRecord());
        Button delete = button("Delete by ID", "#FFF1F2", "#BE123C");
        delete.setMaxWidth(Double.MAX_VALUE);
        delete.setOnAction(event -> deleteRecord());
        Button search = button("Find by ID", "#EAF1FB", BLUE);
        search.setMaxWidth(Double.MAX_VALUE);
        search.setOnAction(event -> findRecord());
        Button clear = button("Clear Form", "#F1F5F9", "#334155");
        clear.setMaxWidth(Double.MAX_VALUE);
        clear.setOnAction(event -> clearForm());

        Label hint = new Label("Each record is stored in a fixed-size page slot. IDs must be unique.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 10px; -fx-padding: 6 0 0 0;");
        card.getChildren().addAll(form, add, update, delete, search, clear, hint);
        return card;
    }

    private VBox buildStudentArea() {
        VBox card = panel("STUDENT DATABASE", "Records read from the custom students.db page file");
        HBox searchRow = new HBox(8);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        filterField = input("Filter by ID, name or course...");
        HBox.setHgrow(filterField, Priority.ALWAYS);
        Label count = new Label("Live view");
        count.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 10px;");
        searchRow.getChildren().addAll(filterField, count);

        studentTable = new TableView<>();
        studentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        studentTable.setPlaceholder(new Label("No records yet. Load Sample CSV or insert a record."));
        studentTable.setStyle("-fx-background-color: white; -fx-border-color: " + BORDER + "; -fx-border-radius: 8; -fx-background-radius: 8;");
        TableColumn<Student, Number> idColumn = new TableColumn<>("ID");
        idColumn.setCellValueFactory(cell -> new ReadOnlyIntegerWrapper(cell.getValue().id()));
        idColumn.setMaxWidth(90);
        TableColumn<Student, String> nameColumn = new TableColumn<>("Student Name");
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        TableColumn<Student, String> courseColumn = new TableColumn<>("Course");
        courseColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().course()));
        courseColumn.setMaxWidth(130);
        TableColumn<Student, Number> marksColumn = new TableColumn<>("Marks");
        marksColumn.setCellValueFactory(cell -> new ReadOnlyDoubleWrapper(cell.getValue().marks()));
        marksColumn.setMaxWidth(85);
        TableColumn<Student, Number> attendanceColumn = new TableColumn<>("Attendance %");
        attendanceColumn.setCellValueFactory(cell -> new ReadOnlyDoubleWrapper(cell.getValue().attendance()));
        attendanceColumn.setMaxWidth(110);
        studentTable.getColumns().addAll(idColumn, nameColumn, courseColumn, marksColumn, attendanceColumn);

        filteredStudents = new FilteredList<>(students, student -> true);
        studentTable.setItems(filteredStudents);
        filterField.textProperty().addListener((observable, oldText, newText) -> {
            String query = newText == null ? "" : newText.trim().toLowerCase();
            filteredStudents.setPredicate(student -> query.isEmpty()
                    || String.valueOf(student.id()).contains(query)
                    || student.name().toLowerCase().contains(query)
                    || student.course().toLowerCase().contains(query));
        });
        studentTable.getSelectionModel().selectedItemProperty().addListener((observable, oldStudent, student) -> {
            if (student != null) fillForm(student);
        });
        VBox.setVgrow(studentTable, Priority.ALWAYS);
        card.getChildren().addAll(searchRow, studentTable);
        VBox.setVgrow(card, Priority.ALWAYS);
        return card;
    }

    private VBox buildMonitorArea() {
        VBox card = panel("OS + DBMS MONITOR", "Buffer, worker activity and application metrics");
        card.setPrefWidth(295);
        card.setMinWidth(270);
        Label bufferTitle = new Label("LRU BUFFER (capacity: " + KernelDBEngine.BUFFER_CAPACITY + " pages)");
        bufferTitle.setStyle("-fx-text-fill: " + BLUE + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        bufferList = new ListView<>();
        bufferList.setPrefHeight(170);
        bufferList.setPlaceholder(new Label("Buffer is currently empty"));
        bufferList.setStyle("-fx-border-color: " + BORDER + "; -fx-border-radius: 7; -fx-background-radius: 7;");

        GridPane resourceGrid = new GridPane();
        resourceGrid.setHgap(8);
        resourceGrid.setVgap(6);
        Label workersLabel = mutedLabel("Worker page requests");
        workerValue = valueLabel("0");
        Label heapLabel = mutedLabel("JVM heap in use");
        heapValue = valueLabel("0 MB");
        resourceGrid.add(workersLabel, 0, 0);
        resourceGrid.add(workerValue, 1, 0);
        resourceGrid.add(heapLabel, 0, 1);
        resourceGrid.add(heapValue, 1, 1);
        resourceGrid.getColumnConstraints().add(new ColumnConstraints(140));

        Label activityTitle = new Label("ACTIVITY LOG");
        activityTitle.setStyle("-fx-text-fill: " + BLUE + "; -fx-font-size: 10px; -fx-font-weight: bold;");
        activityLog = new TextArea();
        activityLog.setEditable(false);
        activityLog.setWrapText(true);
        activityLog.setPrefRowCount(9);
        activityLog.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 10px; -fx-control-inner-background: #F8FAFD; -fx-border-color: " + BORDER + "; -fx-border-radius: 7;");
        VBox.setVgrow(activityLog, Priority.ALWAYS);
        card.getChildren().addAll(bufferTitle, bufferList, new Separator(), resourceGrid, activityTitle, activityLog);
        return card;
    }

    private HBox buildOperationsBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(11));
        bar.setStyle("-fx-background-color: white; -fx-border-color: " + BORDER + "; -fx-border-radius: 10; -fx-background-radius: 10;");

        Button importButton = button("Import CSV", BLUE, "#FFFFFF");
        importButton.setOnAction(event -> chooseAndImportCsv());
        Button sampleButton = button("Load Sample CSV", "#EAF1FB", BLUE);
        sampleButton.setOnAction(event -> loadSampleCsv());
        Button exportButton = button("Export CSV", "#EAF1FB", BLUE);
        exportButton.setOnAction(event -> exportCsv());
        Button generateButton = button("Generate Test CSV", "#F1F5F9", "#334155");
        generateButton.setOnAction(event -> generateCsv());
        Button benchmarkButton = button("Run I/O Benchmark", "#DCEFE8", "#166534");
        benchmarkButton.setOnAction(event -> runBenchmark());
        Button workersButton = button("Run 4 Worker Threads", "#DCEFE8", "#166534");
        workersButton.setOnAction(event -> runConcurrentTest());
        Button clearBufferButton = button("Clear Buffer + Reset Metrics", "#FFF7ED", "#9A3412");
        clearBufferButton.setOnAction(event -> clearBuffer());
        for (Button button : List.of(importButton, sampleButton, exportButton, generateButton, benchmarkButton, workersButton, clearBufferButton)) {
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
        }
        bar.getChildren().addAll(importButton, sampleButton, exportButton, generateButton, benchmarkButton, workersButton, clearBufferButton);
        return bar;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(6, 22, 12, 22));
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 10px;");
        Label note = new Label("Note: file read calls count application-level page reads, not physical disk-device operations.");
        note.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 10px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().addAll(statusLabel, spacer, note);
        return footer;
    }

    private VBox panel(String title, String subtitle) {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(14));
        panel.setStyle("-fx-background-color: white; -fx-border-color: " + BORDER + "; -fx-border-radius: 11; -fx-background-radius: 11;");
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: " + BLUE + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.setWrapText(true);
        subtitleLabel.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 10px;");
        panel.getChildren().addAll(titleLabel, subtitleLabel, new Separator());
        return panel;
    }

    private TextField input(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: " + BORDER + "; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8; -fx-font-size: 11px;");
        return field;
    }

    private void addFormRow(GridPane grid, int row, String labelText, TextField field) {
        Label label = mutedLabel(labelText);
        grid.add(label, 0, row);
        grid.add(field, 1, row);
    }

    private Label mutedLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 10px;");
        return label;
    }

    private Label valueLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + BLUE + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        return label;
    }

    private Button button(String text, String background, String foreground) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + foreground + "; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 9 10; -fx-background-radius: 7; -fx-cursor: hand;");
        button.setOnMouseEntered(event -> button.setOpacity(0.86));
        button.setOnMouseExited(event -> button.setOpacity(1.0));
        return button;
    }

    private void insertRecord() {
        try {
            Student student = readForm();
            engine.insert(student);
            log("Inserted student ID " + student.id() + ".");
            status("Record inserted");
            refreshTableAndMetrics();
            clearForm();
        } catch (Exception ex) {
            showError("Could not insert record", ex);
        }
    }

    private void updateRecord() {
        try {
            Student student = readForm();
            engine.update(student);
            log("Updated student ID " + student.id() + ".");
            status("Record updated");
            refreshTableAndMetrics();
        } catch (Exception ex) {
            showError("Could not update record", ex);
        }
    }

    private void deleteRecord() {
        try {
            int id = parseId();
            Optional<ButtonType> confirmed = showConfirmation("Delete student " + id + "?", "This removes the record from the custom database file.");
            if (confirmed.isEmpty() || confirmed.get() != ButtonType.OK) return;
            engine.delete(id);
            log("Deleted student ID " + id + ".");
            status("Record deleted");
            refreshTableAndMetrics();
            clearForm();
        } catch (Exception ex) {
            showError("Could not delete record", ex);
        }
    }

    private void findRecord() {
        try {
            int id = parseId();
            Student student = engine.findById(id);
            if (student == null) {
                showInfo("Student not found", "No record has ID " + id + ".");
                return;
            }
            fillForm(student);
            filterField.clear();
            studentTable.getSelectionModel().select(student);
            studentTable.scrollTo(student);
            log("Found student ID " + id + ".");
            status("Record found");
            refreshMonitoring();
        } catch (Exception ex) {
            showError("Could not search record", ex);
        }
    }

    private Student readForm() {
        int id = parseId();
        double marks = parsePercentage(marksField.getText(), "Marks");
        double attendance = parsePercentage(attendanceField.getText(), "Attendance");
        return new Student(id, nameField.getText(), courseField.getText(), marks, attendance);
    }

    private int parseId() {
        try {
            return Integer.parseInt(idField.getText().trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter a valid positive Student ID.");
        }
    }

    private double parsePercentage(String value, String label) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number between 0 and 100.");
        }
    }

    private void fillForm(Student student) {
        idField.setText(String.valueOf(student.id()));
        nameField.setText(student.name());
        courseField.setText(student.course());
        marksField.setText(String.valueOf(student.marks()));
        attendanceField.setText(String.valueOf(student.attendance()));
    }

    private void clearForm() {
        idField.clear();
        nameField.clear();
        courseField.clear();
        marksField.clear();
        attendanceField.clear();
        studentTable.getSelectionModel().clearSelection();
    }

    private void chooseAndImportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Student CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        Path data = Path.of("data").toAbsolutePath().normalize();
        if (Files.isDirectory(data)) chooser.setInitialDirectory(data.toFile());
        java.io.File selected = chooser.showOpenDialog(primaryStage);
        if (selected != null) importCsv(selected.toPath());
    }

    private void loadSampleCsv() {
        Path sample = Path.of("data", "students_sample.csv").toAbsolutePath().normalize();
        if (!Files.exists(sample)) {
            showInfo("Sample CSV not found", "Place students_sample.csv in the project's data folder, or use Import CSV to choose a file.");
            return;
        }
        importCsv(sample);
    }

    private void importCsv(Path csvPath) {
        Task<ImportResult> task = new Task<>() {
            @Override protected ImportResult call() throws Exception {
                List<Student> imported = CsvService.readStudents(csvPath);
                updateMessage("Reading " + imported.size() + " CSV rows...");
                return engine.importStudents(imported);
            }
        };
        task.setOnRunning(event -> { backgroundJobRunning = true; status("Importing " + csvPath.getFileName() + "…"); });
        task.setOnSucceeded(event -> {
            backgroundJobRunning = false;
            ImportResult result = task.getValue();
            log("CSV import: " + result.inserted() + " inserted; " + result.skippedDuplicateIds() + " duplicate IDs skipped.");
            log("Source: " + csvPath);
            status("CSV import complete");
            refreshTableAndMetrics();
            showInfo("CSV import complete", result.inserted() + " records inserted. " + result.skippedDuplicateIds() + " duplicate IDs skipped.");
        });
        task.setOnFailed(event -> { backgroundJobRunning = false; showError("CSV import failed", task.getException()); });
        new Thread(task, "KernelDB-CSV-Import").start();
    }

    private void exportCsv() {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export KernelDB Records as CSV");
            chooser.setInitialFileName("kerneldb_export.csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            Path data = Path.of("data").toAbsolutePath().normalize();
            if (Files.isDirectory(data)) chooser.setInitialDirectory(data.toFile());
            java.io.File selected = chooser.showSaveDialog(primaryStage);
            if (selected == null) return;
            Path output = selected.toPath();
            CsvService.writeStudents(output, engine.listStudents());
            log("Exported CSV to " + output);
            status("CSV exported");
            showInfo("Export complete", "Exported " + engine.recordCount() + " records to:\n" + output);
        } catch (Exception ex) {
            showError("Could not export CSV", ex);
        }
    }

    private void generateCsv() {
        TextInputDialog dialog = new TextInputDialog("10000");
        dialog.setTitle("Generate Test Dataset");
        dialog.setHeaderText("Create a CSV file for larger ingestion tests");
        dialog.setContentText("Number of records (1–100000):");
        Optional<String> input = dialog.showAndWait();
        if (input.isEmpty()) return;
        try {
            int count = Integer.parseInt(input.get().trim());
            if (count < 1 || count > 100_000) throw new IllegalArgumentException("Record count must be between 1 and 100,000.");
            int startId = engine.maxStudentId() + 1;
            Path output = Path.of("data", "generated_students_" + count + ".csv").toAbsolutePath().normalize();
            CsvService.generateStudents(output, count, startId);
            log("Generated test CSV with " + count + " rows: " + output);
            status("Test CSV generated");
            showInfo("Test CSV ready", "Created " + count + " student rows at:\n" + output + "\n\nUse Import CSV to load it into KernelDB.");
        } catch (Exception ex) {
            showError("Could not generate dataset", ex);
        }
    }

    private void runBenchmark() {
        Task<BenchmarkResult> task = new Task<>() {
            @Override protected BenchmarkResult call() throws Exception { return engine.runBenchmark(40); }
        };
        task.setOnRunning(event -> { backgroundJobRunning = true; status("Running baseline and buffered I/O tests…"); });
        task.setOnSucceeded(event -> {
            backgroundJobRunning = false;
            BenchmarkResult result = task.getValue();
            DecimalFormat oneDecimal = new DecimalFormat("0.0");
            log("BENCHMARK: working pages=" + result.pagesInWorkingSet() + ", repeats=" + result.repeats());
            log("Baseline: " + result.baselineMillis() + " ms, page-read calls=" + result.baselineFileReadCalls());
            log("KernelDB buffer: " + result.bufferedMillis() + " ms, page-read calls=" + result.bufferedFileReadCalls());
            log("Read-call reduction in this test: " + result.savedReadCalls() + " (" + oneDecimal.format(result.reductionPercent()) + "%).");
            status("Benchmark complete — see activity log");
            refreshMonitoring();
            showInfo("I/O benchmark complete",
                    "Same repeated-page request sequence\n\n"
                    + "Baseline: " + result.baselineMillis() + " ms; " + result.baselineFileReadCalls() + " page-read calls\n"
                    + "KernelDB: " + result.bufferedMillis() + " ms; " + result.bufferedFileReadCalls() + " page-read calls\n"
                    + "Page-read calls avoided: " + result.savedReadCalls() + " (" + oneDecimal.format(result.reductionPercent()) + "%)\n\n"
                    + "This is an application-level page-read comparison, not a direct measurement of physical disk-device I/O.");
        });
        task.setOnFailed(event -> { backgroundJobRunning = false; showError("Benchmark failed", task.getException()); });
        new Thread(task, "KernelDB-Benchmark").start();
    }

    private void runConcurrentTest() {
        Task<ConcurrentTestResult> task = new Task<>() {
            @Override protected ConcurrentTestResult call() throws Exception { return engine.runConcurrentTest(4, 1000); }
        };
        task.setOnRunning(event -> { backgroundJobRunning = true; status("Running 4 concurrent worker threads…"); });
        task.setOnSucceeded(event -> {
            backgroundJobRunning = false;
            ConcurrentTestResult result = task.getValue();
            log("CONCURRENCY TEST: " + result.workerCount() + " workers, " + result.requestsPerWorker()
                    + " requests each, total=" + result.totalRequests() + ", elapsed=" + result.elapsedMillis() + " ms.");
            log("Workers requested shared pages through the synchronized LRU buffer.");
            status("Worker test complete");
            refreshMonitoring();
            showInfo("Concurrent worker test complete",
                    result.workerCount() + " threads performed " + result.totalRequests() + " page requests in "
                    + result.elapsedMillis() + " ms.\n\nThe shared buffer protects its LRU/cache structure against concurrent access.");
        });
        task.setOnFailed(event -> { backgroundJobRunning = false; showError("Worker test failed", task.getException()); });
        new Thread(task, "KernelDB-Worker-Test").start();
    }

    private void clearBuffer() {
        try {
            engine.clearBufferAndResetMetrics();
            log("Buffer flushed and cleared; monitoring counters reset.");
            status("Buffer cleared; next reads start cold");
            refreshMonitoring();
        } catch (Exception ex) {
            showError("Could not clear buffer", ex);
        }
    }

    private void refreshTable() {
        try {
            List<Student> records = engine.listStudents();
            students.setAll(records);
            refreshMonitoring();
        } catch (Exception ex) {
            showError("Could not refresh database view", ex);
        }
    }

    private void refreshTableAndMetrics() {
        refreshTable();
    }

    private void refreshMonitoring() {
        if (engine == null || recordCountValue == null) return;
        try {
            KernelDBMetrics metrics = engine.metrics();
            recordCountValue.setText(String.format("%,d", engine.recordCount()));
            pageCountValue.setText(String.format("%,d", engine.pageCount()));
            cacheHitValue.setText(String.format("%,d", metrics.cacheHits()));
            cacheMissValue.setText(String.format("%,d", metrics.cacheMisses()));
            fileReadValue.setText(String.format("%,d", metrics.fileReadCalls()));
            fileWriteValue.setText(String.format("%,d", metrics.fileWriteCalls()));
            workerValue.setText(String.format("%,d", metrics.workerPageRequests()));
            heapValue.setText(String.format("%.1f MB", metrics.usedJvmHeapBytes() / (1024.0 * 1024.0)));
            bufferList.getItems().setAll(engine.cachedPageLabels());
        } catch (Exception ex) {
            status("Monitoring refresh failed: " + ex.getMessage());
        }
    }

    private void log(String message) {
        if (activityLog == null) return;
        String timestamp = java.time.LocalTime.now().withNano(0).toString();
        activityLog.appendText("[" + timestamp + "] " + message + System.lineSeparator());
        activityLog.positionCaret(activityLog.getLength());
    }

    private void status(String message) {
        if (statusLabel != null) statusLabel.setText(message);
    }

    private void showError(String title, Throwable error) {
        Throwable actual = error == null ? new Exception("Unknown error") : error;
        String message = actual.getMessage() == null ? actual.toString() : actual.getMessage();
        log("ERROR: " + title + " — " + message);
        status(title);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(primaryStage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private Optional<ButtonType> showConfirmation(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        return alert.showAndWait();
    }

    @Override
    public void stop() throws Exception {
        if (refreshTimer != null) refreshTimer.stop();
        if (engine != null) engine.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
