package com.pragmaticds.docengine.orchestration;

import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Orchestration wiring. The processing executor is a named bean so tests can swap in a
 * same-thread executor and observe a fully-run pipeline synchronously;
 * {@code @ConditionalOnMissingBean} makes this the default rather than a competitor — a
 * test-supplied {@code processingExecutor} wins no matter which definition Spring registers
 * first.
 *
 * <p>It is a BOUNDED pool, and it is bounded by the database connection pool. The Phase 1
 * {@code SimpleAsyncTaskExecutor} (one new thread per dispatch, unbounded, no queue) shipped on
 * the assumption that job volume was developer-scale; a production "Classify 27 documents" then
 * dispatched 27 jobs against a 10-connection Hikari pool, ten of which took a connection and
 * seventeen of which blocked 30 seconds and aborted with {@code CannotCreateTransactionException}.
 * The three documents that parsed were the ones that won the race. See {@link
 * ProcessingExecutorSizing} for why concurrency is derived from the pool rather than chosen beside
 * it.
 *
 * <p>Saturation policy: the queue is generous ({@code docengine.processing.queue-capacity}, 1000
 * by default — a bulk classify of any realistic size QUEUES and simply takes longer). If it does
 * fill, the dispatch runs on the CALLER rather than being rejected. That costs the caller its
 * thread for one pipeline run, which is backpressure; the alternative is worse, because a rejected
 * dispatch leaves an already-committed job row in a non-terminal status that {@code resume} —
 * which only accepts FAILED jobs — cannot recover. A job must never silently vanish.
 */
@Configuration
public class OrchestrationConfig {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationConfig.class);

    /**
     * @param connectionPoolSize the Hikari maximum pool size the processing threads share with the
     *     API's request threads
     * @param connectionHeadroom connections reserved for those request threads
     * @param configuredConcurrency explicit override; {@code <= 0} derives from the two above
     * @param queueCapacity queued dispatches before the caller-runs fallback engages
     * @param shutdownAwaitSeconds how long a container restart waits for in-flight stages
     */
    @Bean("processingExecutor")
    @ConditionalOnMissingBean(name = "processingExecutor")
    public TaskExecutor processingExecutor(
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int connectionPoolSize,
            @Value("${docengine.processing.connection-headroom:8}") int connectionHeadroom,
            @Value("${docengine.processing.max-concurrent-jobs:0}") int configuredConcurrency,
            @Value("${docengine.processing.queue-capacity:1000}") int queueCapacity,
            @Value("${docengine.processing.shutdown-await-seconds:60}") int shutdownAwaitSeconds) {

        int concurrency =
                ProcessingExecutorSizing.resolveConcurrency(
                        configuredConcurrency, connectionPoolSize, connectionHeadroom);
        if (ProcessingExecutorSizing.wouldClamp(
                configuredConcurrency, connectionPoolSize, connectionHeadroom)) {
            log.warn(
                    "docengine.processing.max-concurrent-jobs={} exceeds the connection-pool"
                            + " ceiling and was clamped to {} (pool={}, headroom={}); raise"
                            + " spring.datasource.hikari.maximum-pool-size to process more at once",
                    configuredConcurrency,
                    concurrency,
                    connectionPoolSize,
                    connectionHeadroom);
        }

        int queue = Math.max(1, queueCapacity);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("processing-");
        // core == max with a bounded queue: a ThreadPoolExecutor only grows past its core once the
        // queue is FULL, so any other pairing would make the effective concurrency depend on queue
        // depth. Equal sizes make the bound mean exactly what it says.
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queue);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // A stage attempt is mid-transaction; interrupting it abandons the attempt row that resume
        // reads, and dropping a queued dispatch strands its job in a status resume will not accept.
        // So a restart drains rather than interrupts, with a bound so it cannot hang forever.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, shutdownAwaitSeconds));
        executor.setAcceptTasksAfterContextClose(false);

        log.info(
                "processing executor bounded concurrency={} queueCapacity={} connectionPool={}"
                        + " headroom={}",
                concurrency,
                queue,
                connectionPoolSize,
                connectionHeadroom);
        return executor;
    }
}
