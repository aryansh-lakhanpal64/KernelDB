package com.kerneldb.model;

public record Enrollment(int id, int studentId, int courseId, String semester, String grade) { }
