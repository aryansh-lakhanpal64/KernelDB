package com.kerneldb.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Mutable transaction workspace. A transaction always changes a private copy. */
public final class DatabaseState {
    private final LinkedHashMap<Integer, Student> students = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Course> courses = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Enrollment> enrollments = new LinkedHashMap<>();

    public Map<Integer, Student> students() { return students; }
    public Map<Integer, Course> courses() { return courses; }
    public Map<Integer, Enrollment> enrollments() { return enrollments; }

    public DatabaseState copy() {
        DatabaseState copy = new DatabaseState();
        copy.students.putAll(students);
        copy.courses.putAll(courses);
        copy.enrollments.putAll(enrollments);
        return copy;
    }

    public int nextStudentId() { return nextId(students); }
    public int nextCourseId() { return nextId(courses); }
    public int nextEnrollmentId() { return nextId(enrollments); }

    private static int nextId(Map<Integer, ?> map) {
        return map.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    /** Enforces primary keys, unique alternate keys, foreign keys, and domain constraints. */
    public void validate() {
        Set<String> emails = new java.util.HashSet<>();
        for (var entry : students.entrySet()) {
            Student s = entry.getValue();
            require(entry.getKey() == s.id() && s.id() > 0, "Student primary-key mismatch.");
            require(!blank(s.name()) && s.name().length() <= 80, "Student name is required and must be at most 80 characters.");
            require(!blank(s.email()) && s.email().contains("@") && s.email().length() <= 120,
                    "A valid student email is required (maximum 120 characters).");
            require(emails.add(s.email().trim().toLowerCase(Locale.ROOT)),
                    "Unique constraint failed: student email already exists.");
        }

        Set<String> codes = new java.util.HashSet<>();
        for (var entry : courses.entrySet()) {
            Course c = entry.getValue();
            require(entry.getKey() == c.id() && c.id() > 0, "Course primary-key mismatch.");
            require(!blank(c.code()) && !blank(c.title()) && c.code().length() <= 30 && c.title().length() <= 100,
                    "Course code/title are required (code max 30, title max 100 characters).");
            require(codes.add(c.code().trim().toUpperCase(Locale.ROOT)),
                    "Unique constraint failed: course code already exists.");
            require(c.credits() >= 1 && c.credits() <= 10, "Course credits must be from 1 to 10.");
        }

        Set<String> enrollmentKeys = new java.util.HashSet<>();
        Set<String> grades = Set.of("A+", "A", "B+", "B", "C", "D", "F", "P", "I");
        for (var entry : enrollments.entrySet()) {
            Enrollment e = entry.getValue();
            require(entry.getKey() == e.id() && e.id() > 0, "Enrollment primary-key mismatch.");
            require(students.containsKey(e.studentId()),
                    "Foreign-key violation: enrollment refers to a missing student ID " + e.studentId() + ".");
            require(courses.containsKey(e.courseId()),
                    "Foreign-key violation: enrollment refers to a missing course ID " + e.courseId() + ".");
            require(!blank(e.semester()) && e.semester().length() <= 60, "Semester is required and must be at most 60 characters.");
            require(!blank(e.grade()) && grades.contains(e.grade().trim().toUpperCase(Locale.ROOT)),
                    "Grade must be one of A+, A, B+, B, C, D, F, P, or I.");
            String key = e.studentId() + "|" + e.courseId() + "|" + e.semester().trim().toLowerCase(Locale.ROOT);
            require(enrollmentKeys.add(key),
                    "Unique constraint failed: student, course, and semester combination already exists.");
        }
    }

    /** Line-based state format used in the write-ahead log; field values are Base64 encoded. */
    public String encode() {
        StringBuilder out = new StringBuilder("KERNELDB_STATE_V1\n");
        students.values().forEach(s -> out.append("S\t").append(s.id()).append('\t')
                .append(b64(s.name())).append('\t').append(b64(s.email())).append('\n'));
        courses.values().forEach(c -> out.append("C\t").append(c.id()).append('\t')
                .append(b64(c.code())).append('\t').append(b64(c.title())).append('\t')
                .append(c.credits()).append('\n'));
        enrollments.values().forEach(e -> out.append("E\t").append(e.id()).append('\t')
                .append(e.studentId()).append('\t').append(e.courseId()).append('\t')
                .append(b64(e.semester())).append('\t').append(b64(e.grade())).append('\n'));
        return out.toString();
    }

    public static DatabaseState decode(String encodedState) {
        DatabaseState result = new DatabaseState();
        String[] lines = encodedState.split("\\R");
        if (lines.length == 0 || !"KERNELDB_STATE_V1".equals(lines[0])) {
            throw new IllegalArgumentException("Unsupported or corrupt KernelDB state format.");
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] p = lines[i].split("\\t", -1);
            switch (p[0]) {
                case "S" -> {
                    if (p.length != 4) throw new IllegalArgumentException("Corrupt student row in state.");
                    Student s = new Student(Integer.parseInt(p[1]), unb64(p[2]), unb64(p[3]));
                    if (result.students.put(s.id(), s) != null) throw new IllegalArgumentException("Duplicate student ID.");
                }
                case "C" -> {
                    if (p.length != 5) throw new IllegalArgumentException("Corrupt course row in state.");
                    Course c = new Course(Integer.parseInt(p[1]), unb64(p[2]), unb64(p[3]), Integer.parseInt(p[4]));
                    if (result.courses.put(c.id(), c) != null) throw new IllegalArgumentException("Duplicate course ID.");
                }
                case "E" -> {
                    if (p.length != 6) throw new IllegalArgumentException("Corrupt enrollment row in state.");
                    Enrollment e = new Enrollment(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3]), unb64(p[4]), unb64(p[5]));
                    if (result.enrollments.put(e.id(), e) != null) throw new IllegalArgumentException("Duplicate enrollment ID.");
                }
                default -> throw new IllegalArgumentException("Unknown row type in state: " + p[0]);
            }
        }
        result.validate();
        return result;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String unb64(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
