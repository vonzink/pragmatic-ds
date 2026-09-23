package com.pragmaticds.docengine.orchestration;

/**
 * The one place that decides how many jobs may process at once, and the reason that number is not
 * free to be chosen on its own.
 *
 * <p>A processing thread holds a JDBC connection for the whole of a stage attempt (every attempt
 * runs in its own REQUIRES_NEW transaction — see {@link StageRunner}). Processing concurrency is
 * therefore a claim on the SAME {@code HikariPool-1} the HTTP request threads draw from. When the
 * two numbers are picked independently they drift, and the drift is not gradual: it is a bulk
 * classify that dispatches 27 jobs at a 10-connection pool, ten of which win and seventeen of
 * which die on the 30-second pool timeout with {@code CannotCreateTransactionException}.
 *
 * <p>So concurrency is DERIVED: {@code pool - headroom}, where the headroom is the slice reserved
 * for the API's own request threads. The headroom is not decoration — an upload that runs the
 * parse-once reuse probe wants TWO connections at once (its own transaction plus the probe's
 * REQUIRES_NEW candidate scan), and {@code docengine.reuse.max-concurrent-probes} lets four such
 * uploads be inside that window, so the default headroom of 8 is exactly that worst case.
 *
 * <p>An explicitly configured concurrency is CLAMPED to the same ceiling rather than honoured
 * blindly: more processing throughput is bought by raising the pool, which is what keeps the two
 * numbers coupled instead of merely adjacent.
 */
public final class ProcessingExecutorSizing {

    private ProcessingExecutorSizing() {}

    /**
     * @param configured {@code docengine.processing.max-concurrent-jobs}; {@code <= 0} means derive
     * @param connectionPoolSize {@code spring.datasource.hikari.maximum-pool-size}
     * @param headroom connections reserved for API request threads
     * @return the pool size for the processing executor, always at least 1
     */
    public static int resolveConcurrency(int configured, int connectionPoolSize, int headroom) {
        int ceiling = ceiling(connectionPoolSize, headroom);
        return configured <= 0 ? ceiling : Math.min(configured, ceiling);
    }

    /** True when an explicit configuration was reduced to the ceiling — worth a startup warning. */
    public static boolean wouldClamp(int configured, int connectionPoolSize, int headroom) {
        return configured > 0 && configured > ceiling(connectionPoolSize, headroom);
    }

    private static int ceiling(int connectionPoolSize, int headroom) {
        // Nonsense inputs degrade to a single worker: slow is recoverable, a pool stampede is not.
        return Math.max(1, Math.max(1, connectionPoolSize) - Math.max(0, headroom));
    }
}
