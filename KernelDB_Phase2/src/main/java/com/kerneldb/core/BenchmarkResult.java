package com.kerneldb.core;

public record BenchmarkResult(
        int pagesInWorkingSet,
        int repeats,
        long baselineMillis,
        long baselineFileReadCalls,
        long bufferedMillis,
        long bufferedFileReadCalls,
        long cacheHits,
        long cacheMisses) {

    public long savedReadCalls() {
        return Math.max(0, baselineFileReadCalls - bufferedFileReadCalls);
    }

    public double reductionPercent() {
        if (baselineFileReadCalls == 0) return 0.0;
        return 100.0 * (baselineFileReadCalls - bufferedFileReadCalls) / baselineFileReadCalls;
    }
}
