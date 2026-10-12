package com.kerneldb.core;

public record ConcurrentTestResult(int workerCount, int requestsPerWorker, long totalRequests, long elapsedMillis) { }
