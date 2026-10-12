package com.kerneldb.model;

import java.nio.charset.StandardCharsets;

/** Immutable student row stored by the KernelDB page engine. */
public record Student(int id, String name, String course, double marks, double attendance) {
    public static final int MAX_NAME_BYTES = 40;
    public static final int MAX_COURSE_BYTES = 24;

    public Student {
        if (id <= 0) {
            throw new IllegalArgumentException("Student ID must be greater than zero.");
        }
        name = requireText(name, "Name", MAX_NAME_BYTES);
        course = requireText(course, "Course", MAX_COURSE_BYTES);
        if (!Double.isFinite(marks) || marks < 0 || marks > 100) {
            throw new IllegalArgumentException("Marks must be between 0 and 100.");
        }
        if (!Double.isFinite(attendance) || attendance < 0 || attendance > 100) {
            throw new IllegalArgumentException("Attendance must be between 0 and 100.");
        }
    }

    private static String requireText(String value, String label, int maxBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be empty.");
        }
        String trimmed = value.trim();
        if (trimmed.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " is too long (maximum " + maxBytes + " UTF-8 bytes).");
        }
        return trimmed;
    }
}
