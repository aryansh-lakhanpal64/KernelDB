package com.kerneldb.core;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe counters shown on the monitoring dashboard. */
public final class KernelDBMetrics {
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong fileReadCalls = new AtomicLong();
    private final AtomicLong fileWriteCalls = new AtomicLong();
    private final AtomicLong workerPageRequests = new AtomicLong();

    public void cacheHit() { cacheHits.incrementAndGet(); }
    public void cacheMiss() { cacheMisses.incrementAndGet(); }
    public void fileRead() { fileReadCalls.incrementAndGet(); }
    public void fileWrite() { fileWriteCalls.incrementAndGet(); }
    public void workerPageRequest() { workerPageRequests.incrementAndGet(); }

    public long cacheHits() { return cacheHits.get(); }
    public long cacheMisses() { return cacheMisses.get(); }
    public long fileReadCalls() { return fileReadCalls.get(); }
    public long fileWriteCalls() { return fileWriteCalls.get(); }
    public long workerPageRequests() { return workerPageRequests.get(); }

    public synchronized void reset() {
        cacheHits.set(0);
        cacheMisses.set(0);
        fileReadCalls.set(0);
        fileWriteCalls.set(0);
        workerPageRequests.set(0);
    }

    public long usedJvmHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
