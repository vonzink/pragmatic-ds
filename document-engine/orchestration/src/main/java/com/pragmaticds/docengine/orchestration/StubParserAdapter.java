package com.pragmaticds.docengine.orchestration;

import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-process parser: Phase 1's only {@link ParserPort} adapter, so acceptance
 * criterion 4 holds — the full pipeline runs green with the Python container stopped. Phase 2 adds
 * the HTTP worker adapter behind a property; this stub stays for tests.
 *
 * <p>Determinism is the contract: the same job and stage always produce the same digest, and
 * failures happen only when a test asks for them, through two hooks:
 *
 * <ul>
 *   <li>programmatic — {@link #failStage} fails the next N invocations of a stage,
 *       {@link #clearFailures()} resets;
 *   <li>declarative — an idempotency key containing {@code fail-hard:STAGE} always fails that
 *       stage, {@code fail-once:STAGE} fails only its first invocation. Key conventions survive a
 *       process restart and need no reference to the bean, which matters for tests that drive the
 *       pipeline purely over HTTP.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "docengine.processing.adapter", havingValue = "stub", matchIfMissing = true)
public class StubParserAdapter implements ParserPort {

    private final ConcurrentHashMap<String, AtomicInteger> invocationCounts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProcessingStatus, AtomicInteger> programmedFailures =
            new ConcurrentHashMap<>();

    /**
     * No identity, deliberately: the stub does not parse the bytes at all, it returns a digest of
     * the job id. It still writes real {@code engine_result} rows and still reaches
     * HUMAN_REVIEW_REQUIRED, so nothing downstream can tell a stub run from a real one — which is
     * exactly why the refusal has to live here, at the only place that knows. No identity means no
     * fingerprint, which means a stub parse is never stamped and never reused, in either
     * direction.
     */
    @Override
    public java.util.Optional<String> behaviorIdentity() {
        return java.util.Optional.empty();
    }

    @Override
    public StageOutcome run(StageRequest request) {
        int invocation =
                invocationCounts
                        .computeIfAbsent(
                                countKey(request.jobId(), request.stage()),
                                key -> new AtomicInteger())
                        .incrementAndGet();

        if (isPermanentFailure(request)) {
            return StageOutcome.nonRetryableFailure(
                    failureCode(request.stage()),
                    Map.of("stage", request.stage().name(), "attempt", request.attempt()));
        }
        if (shouldFail(request, invocation)) {
            return new StageOutcome(
                    false,
                    null,
                    failureCode(request.stage()),
                    // Non-sensitive by construction: names and counters, never content.
                    Map.of("stage", request.stage().name(), "attempt", request.attempt()));
        }
        if (request.stage() == ProcessingStatus.AI_EXTRACTION) {
            return StageOutcome.skipped("AI_DISABLED");
        }
        if (request.stage() == ProcessingStatus.BOUNDARY_EXTRACTION) {
            // Same honesty rule as AI_EXTRACTION: the stub does no boundary work, and a stage row
            // must never claim success for nothing.
            return StageOutcome.skipped("BOUNDARY_EXTRACTION_DISABLED");
        }
        return new StageOutcome(true, digest(request.jobId(), request.stage()), null, Map.of());
    }

    private static boolean isPermanentFailure(StageRequest request) {
        String key = request.idempotencyKey() == null ? "" : request.idempotencyKey();
        return key.contains("fail-permanent:" + request.stage().name());
    }

    private boolean shouldFail(StageRequest request, int invocation) {
        AtomicInteger remaining = programmedFailures.get(request.stage());
        if (remaining != null && remaining.getAndDecrement() > 0) {
            return true;
        }
        String key = request.idempotencyKey() == null ? "" : request.idempotencyKey();
        if (key.contains("fail-hard:" + request.stage().name())) {
            return true;
        }
        return key.contains("fail-once:" + request.stage().name()) && invocation == 1;
    }

    /** RENDERING→RENDER_FAILED, TEXT_EXTRACTION→TEXT_EXTRACTION_FAILED, OCR→OCR_FAILED, else INTERNAL. */
    private ErrorCode failureCode(ProcessingStatus stage) {
        return switch (stage) {
            case RENDERING -> ErrorCode.RENDER_FAILED;
            case TEXT_EXTRACTION -> ErrorCode.TEXT_EXTRACTION_FAILED;
            case OCR_PROCESSING -> ErrorCode.OCR_FAILED;
            default -> ErrorCode.INTERNAL;
        };
    }

    /** How many times a stage has been invoked for a job — the "did it rerun?" probe for tests. */
    public int invocations(UUID jobId, ProcessingStatus stage) {
        AtomicInteger count = invocationCounts.get(countKey(jobId, stage));
        return count == null ? 0 : count.get();
    }

    /** Makes the next {@code times} invocations of {@code stage} fail, for any job. */
    public void failStage(ProcessingStatus stage, int times) {
        programmedFailures.put(stage, new AtomicInteger(times));
    }

    /** Clears every programmed failure (key conventions are unaffected — they live in the key). */
    public void clearFailures() {
        programmedFailures.clear();
    }

    /** Full reset — failures AND invocation counters — for test isolation between cases. */
    public void reset() {
        programmedFailures.clear();
        invocationCounts.clear();
    }

    private static String countKey(UUID jobId, ProcessingStatus stage) {
        return jobId + ":" + stage.name();
    }

    /** sha256(jobId + stage): stable across invocations, unique per job and stage. */
    private static String digest(UUID jobId, ProcessingStatus stage) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest((jobId + stage.name()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
