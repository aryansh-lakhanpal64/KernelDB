package com.kerneldb.csv;

import com.kerneldb.model.Student;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** CSV import/export for inspection and sharing; students.db remains the custom binary database. */
public final class CsvService {
    private CsvService() { }

    public static List<Student> readStudents(Path csvPath) throws IOException {
        List<Student> students = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            boolean firstNonBlank = true;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                List<String> cells = parseLine(line);
                if (firstNonBlank) {
                    firstNonBlank = false;
                    if (!cells.isEmpty() && cells.getFirst().trim().equalsIgnoreCase("student_id")) continue;
                }
                if (cells.size() != 5) {
                    throw new IOException("CSV line " + lineNumber + " must contain 5 columns: student_id,name,course,marks,attendance.");
                }
                try {
                    int id = Integer.parseInt(cells.get(0).trim());
                    String name = cells.get(1).trim();
                    String course = cells.get(2).trim();
                    double marks = Double.parseDouble(cells.get(3).trim());
                    double attendance = Double.parseDouble(cells.get(4).trim());
                    students.add(new Student(id, name, course, marks, attendance));
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid student data on CSV line " + lineNumber + ": " + ex.getMessage(), ex);
                }
            }
        }
        return students;
    }

    public static void writeStudents(Path outputPath, List<Student> students) throws IOException {
        Path parent = outputPath.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("student_id,name,course,marks,attendance");
            writer.newLine();
            for (Student student : students) {
                writer.write(student.id() + "," + escape(student.name()) + "," + escape(student.course()) + ","
                        + student.marks() + "," + student.attendance());
                writer.newLine();
            }
        }
    }

    public static void generateStudents(Path outputPath, int count, int startId) throws IOException {
        if (count < 1 || count > 100_000) throw new IllegalArgumentException("Record count must be between 1 and 100,000.");
        String[] firstNames = {"Aarav", "Aditi", "Ananya", "Arjun", "Diya", "Ishaan", "Kabir", "Meera", "Neha", "Rohan", "Sana", "Vivaan"};
        String[] lastNames = {"Sharma", "Verma", "Singh", "Kapoor", "Joshi", "Gupta", "Chauhan", "Rao", "Mehta", "Khan"};
        String[] courses = {"CSE", "IT", "ECE", "AI-ML", "Data Science", "Cyber Security"};
        List<Student> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = firstNames[i % firstNames.length] + " " + lastNames[(i * 7 + 3) % lastNames.length];
            String course = courses[(i * 5 + 1) % courses.length];
            double marks = 45 + ((i * 17) % 56);
            double attendance = 60 + ((i * 13) % 41);
            batch.add(new Student(startId + i, name, course, marks, attendance));
        }
        writeStudents(outputPath, batch);
    }

    private static List<String> parseLine(String line) throws IOException {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        if (quoted) throw new IOException("CSV contains an unclosed quoted field.");
        cells.add(cell.toString());
        return cells;
    }

    private static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
