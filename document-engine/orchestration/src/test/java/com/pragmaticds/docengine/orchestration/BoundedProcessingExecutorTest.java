package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The processing executor's contract, proved without a single wall-clock sleep: every assertion
 * rides a latch, a barrier or a semaphore, so the suite tells the truth on a machine at load
 * average 100 as well as on an idle one.
 *
 * <p>The incident these tests exist for: "Classify 27 documents" dispatched 27 jobs onto a
 * {@code SimpleAsyncTaskExecutor} (one unbounded thread per dispatch, no queue). Each job wanted a
 * connection from a 10-connection Hikari pool; ten won and the rest died on a 30s pool timeout
 * with {@code CannotCreateTransactionException}.
 */
class BoundedProcessingExecutorTest {

    private static final int AWAIT_SECONDS = 30;

    private ThreadPoolTaskExecutor executor(int poolSize, int headroom, int queueCapacity) {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor)
                        new OrchestrationConfig()
                                .processingExecutor(poolSize, headroom, 0, queueCapacity, 30);
        executor.afterPropertiesSet();
        return executor;
    }

    /**
     * The regression. A bulk classify far wider than the connection pool must QUEUE — every job
     * completes, none is refused a connection. The semaphore stands in for the pool: a task that
     * cannot take a permit is the {@code CannotCreateTransactionException} the incident logged.
     * The barrier forces a full batch to overlap, so an unbounded executor genuinely fails here
     * rather than passing on scheduling luck.
     */
    @Test
    void aBulkDispatchWiderThanTheConnectionPoolCompletesEveryJob() throws Exception {
        int concurrency = 4; // pool 12 - headroom 8
        int jobs = 28; // the incident's 27, rounded to whole barrier batches
        ThreadPoolTaskExecutor executor = executor(12, 8, 1000);
        try {
            Semaphore connections = new Semaphore(concurrency);
            CyclicBarrier lockstep = new CyclicBarrier(concurrency);
            AtomicInteger refusedAConnection = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(jobs);

            for (int i = 0; i < jobs; i++) {
                executor.execute(
                        () -> {
                            try {
                                if (!connections.tryAcquire()) {
                                    refusedAConnection.incrementAndGet();
                                    return;
                                }
                                try {
                                    lockstep.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                                    completed.incrementAndGet();
                                } finally {
                                    connections.release();
                                }
                            } catch (Exception e) {
                                refusedAConnection.incrementAndGet();
                            } finally {
                                done.countDown();
                            }
                        });
            }

            assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(refusedAConnection).hasValue(0);
            assertThat(completed).hasValue(jobs);
            // The incident's literal signature was threads processing-1 … processing-21 against a
            // 10-connection pool. A ThreadPoolExecutor mints a fresh core thread on every dispatch
            // until the core is full, so this count is exact, not a sample: 28 dispatches created
            // 4 threads, not 28.
            assertThat(executor.getThreadPoolExecutor().getLargestPoolSize())
                    .isEqualTo(concurrency);
        } finally {
            executor.shutdown();
        }
    }

    /** Concurrency reaches the configured bound and never exceeds it — both directions pinned. */
    @Test
    void concurrencyReachesTheBoundAndNeverExceedsIt() throws Exception {
        int concurrency = 3; // pool 11 - headroom 8
        int jobs = 21;
        ThreadPoolTaskExecutor executor = executor(11, 8, 1000);
        try {
            CyclicBarrier lockstep = new CyclicBarrier(concurrency);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger highWater = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(jobs);

            for (int i = 0; i < jobs; i++) {
                executor.execute(
                        () -> {
                            try {
                                highWater.accumulateAndGet(active.incrementAndGet(), Math::max);
                                lockstep.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                            } catch (Exception ignored) {
                                // A blown barrier shows up as a wrong high-water mark below.
                            } finally {
                                active.decrementAndGet();
                                done.countDown();
                            }
                        });
            }

            assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(highWater).hasValue(concurrency);
            assertThat(executor.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(concurrency);
            assertThat(executor.getThreadPoolExecutor().getLargestPoolSize())
                    .isEqualTo(concurrency);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Queue saturation degrades to CALLER-RUNS, never to a dropped job. A rejected dispatch that
     * threw would strand its job row in a non-terminal status with no resume path (resume only
     * accepts FAILED jobs), which is the silent-data-loss version of the same incident.
     */
    @Test
    void aFullQueueRunsTheJobOnTheCallerRatherThanDroppingIt() throws Exception {
        ThreadPoolTaskExecutor executor = executor(9, 8, 1); // concurrency 1, queue 1
        try {
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(3);
            ConcurrentLinkedQueue<String> ranOn = new ConcurrentLinkedQueue<>();

            executor.execute(
                    () -> {
                        ranOn.add(Thread.currentThread().getName());
                        occupied.countDown();
                        try {
                            release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        done.countDown();
                    });
            // The single worker is now genuinely busy, so nothing drains the queue underneath us.
            assertThat(occupied.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            executor.execute(
                    () -> {
                        ranOn.add(Thread.currentThread().getName());
                        done.countDown();
                    }); // fills the one queue slot
            executor.execute(
                    () -> {
                        ranOn.add(Thread.currentThread().getName());
                        done.countDown();
                    }); // rejected -> runs here, on the caller

            assertThat(ranOn).contains(Thread.currentThread().getName());
            release.countDown();
            assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(ranOn).hasSize(3);
            assertThat(List.copyOf(ranOn).stream().filter(n -> n.startsWith("processing-")).count())
                    .isEqualTo(2);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Shutdown lets an in-flight stage finish. A stage attempt runs inside its own REQUIRES_NEW
     * transaction; interrupting it mid-transaction abandons the attempt row the resume path reads.
     */
    @Test
    void shutdownWaitsForAnInFlightTaskInsteadOfInterruptingIt() throws Exception {
        ThreadPoolTaskExecutor executor = executor(12, 8, 1000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger finishedCleanly = new AtomicInteger();

        executor.execute(
                () -> {
                    started.countDown();
                    try {
                        if (release.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                            finishedCleanly.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(started.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Thread shutdown = new Thread(executor::destroy, "shutdown-caller");
        shutdown.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!executor.getThreadPoolExecutor().isShutdown() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(executor.getThreadPoolExecutor().isShutdown()).isTrue();

        release.countDown();
        shutdown.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        assertThat(shutdown.isAlive()).isFalse();
        assertThat(finishedCleanly).hasValue(1);
    }
}
