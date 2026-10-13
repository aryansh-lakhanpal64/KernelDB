package com.kerneldb.model;

public record JoinedEnrollment(int enrollmentId, String studentName, String courseTitle,
                               String semester, String grade) { }
