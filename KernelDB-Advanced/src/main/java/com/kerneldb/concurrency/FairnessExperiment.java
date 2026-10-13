package com.kerneldb.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Measured lock-contention experiment for starvation/fair-scheduling discussion. */
public final class FairnessExperiment {
    private FairnessExperiment() { }

    public record WorkerResult(int workerId, int completedOperations, long maxWaitMillis, long totalWaitMillis) { }
    public record Result(boolean fairLock, int workers, int operationsPerWorker,
                         long elapsedMillis, long maxObservedWaitMillis,
                         List<WorkerResult> workerResults) { }

    public static Result run(boolean fair, int workerCount, int iterations) throws Exception {
        ReentrantLock lock = new ReentrantLock(fair);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<Integer, long[]> stats = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        List<Future<?>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (int worker = 1; worker <= workerCount; worker++) {
                final int workerId = worker;
                stats.put(workerId, new long[3]); // operations, max-wait nanos, total-wait nanos
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long[] own = stats.get(workerId);
                    for (int i = 0; i < iterations; i++) {
                        long before = System.nanoTime();
                        lock.lockInterruptibly();
                        long waited = System.nanoTime() - before;
                        try {
                            own[0]++;
                            own[1] = Math.max(own[1], waited);
                            own[2] += waited;
                            // Deliberately short critical section makes lock contention measurable.
                            Thread.sleep(1);
                        } finally {
                            lock.unlock();
                        }
                    }
                    return null;
                }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Workers failed to become ready.");
            started = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) future.get();
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            List<WorkerResult> results = new ArrayList<>();
            long maximum = 0;
            for (int worker = 1; worker <= workerCount; worker++) {
                long[] s = stats.get(worker);
                long waitMaxMs = s[1] / 1_000_000;
                long waitTotalMs = s[2] / 1_000_000;
                maximum = Math.max(maximum, waitMaxMs);
                results.add(new WorkerResult(worker, (int) s[0], waitMaxMs, waitTotalMs));
            }
            return new Result(fair, workerCount, iterations, elapsed, maximum, List.copyOf(results));
        } finally {
            pool.shutdownNow();
        }
    }
}
