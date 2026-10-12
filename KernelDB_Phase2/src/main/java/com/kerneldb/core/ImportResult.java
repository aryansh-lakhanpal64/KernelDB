package com.kerneldb.core;

public record ImportResult(int inserted, int skippedDuplicateIds) { }
