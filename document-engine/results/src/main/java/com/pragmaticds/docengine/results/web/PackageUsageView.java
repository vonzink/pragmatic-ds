package com.pragmaticds.docengine.results.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response shape of {@code GET /v1/packages/{id}/usage}: what one package cost to parse.
 *
 * <p><b>The headline, stated once so no reader has to infer it.</b> Parsing this package made
 * ZERO paid model calls. Every rung of the extraction ladder is deterministic — rule packs,
 * geometry, regex, normalizers — and no model is invoked anywhere in the pipeline. So the cost of a
 * document today is COMPUTE, and this view reports compute: pages, the OCR share of them, elapsed,
 * and the attempts that produced nothing. {@link ModelCostView} reports a MEASURED zero beside it,
 * not an absent field and not a dollar figure derived from a rate nobody has agreed to.
 *
 * <p><b>The one variable that actually moves the compute bill is OCR.</b> A page with a usable text
 * layer is read; a page without one is rendered and put through an OCR engine, which is far more
 * expensive. {@link PageUsageView#ocrPages()} is therefore called out separately from the total
 * rather than left for a caller to add up, and it counts SCANNED + MIXED because that is exactly
 * the set the worker adapter sends to {@code /v1/ocr} ({@code WorkerParserAdapter.OCR_ELIGIBLE}).
 *
 * <p><b>What this view deliberately does NOT claim.</b> There is no per-document elapsed, because
 * there is no per-document timing to report — see {@link ElapsedView}.
 */
public record PackageUsageView(
        UUID packageId,
        PageUsageView pages,
        List<DocumentUsageView> documents,
        long unassignedPages,
        ElapsedView elapsed,
        AttemptsView attempts,
        ModelCostView modelCost) {

    /**
     * Page counts for the whole package.
     *
     * @param byTextLayer every {@code TextLayer} value with its count, all four keys always
     *     present. A zero that is written down is a measurement; an omitted key is a question.
     * @param ocrPages SCANNED + MIXED — the pages that were actually rendered and OCR'd. The
     *     expensive ones.
     */
    public record PageUsageView(long total, long ocrPages, Map<String, Long> byTextLayer) {}

    /**
     * Per-document page accounting. This IS derivable: {@code logical_document_page} maps each
     * page to at most one document, and {@code text_layer} lives on the page — so the OCR share of
     * a document is a real measurement, not an allocation. Pages left unassigned (blank or
     * duplicate) belong to no document and are counted once in
     * {@link PackageUsageView#unassignedPages()}.
     */
    public record DocumentUsageView(
            UUID documentId,
            int ordinal,
            String documentTypeCode,
            long pages,
            long ocrPages,
            Map<String, Long> byTextLayer) {}

    /**
     * Elapsed time, at PACKAGE scope and named so.
     *
     * <p><b>Why there is no per-document elapsed.</b> A {@code processing_stage} row is keyed by
     * job, and a job covers the whole package: RENDERING, TEXT_EXTRACTION, OCR_PROCESSING and
     * PARSING run over every page before any logical document exists at all — SPLITTING is what
     * creates documents, and it runs after them. Dividing package elapsed by document count would
     * produce a number with a decimal point and no meaning, and would be wrong in the direction
     * that matters: a one-page W-2 bundled with a 26-page scanned tax return would appear to cost
     * the same. So {@link #scope()} says {@code PACKAGE} and
     * {@link #perDocumentElapsedAvailable()} says {@code false}, in the payload, where a consumer
     * can read it.
     *
     * @param packageWallClockMs job start to job finish — includes time queued and time between
     *     stages. Null until the job has both timestamps.
     * @param packageStageElapsedMs the sum of every attempt's {@code duration_ms}: time actually
     *     spent inside stages. Always {@code <=} wall clock; the difference is queueing.
     * @param failedAttemptElapsedMs the share of {@code packageStageElapsedMs} spent on attempts
     *     that FAILED. Compute that was paid for and produced no result.
     */
    public record ElapsedView(
            String scope,
            boolean perDocumentElapsedAvailable,
            Long packageWallClockMs,
            long packageStageElapsedMs,
            long failedAttemptElapsedMs,
            List<StageUsageView> stages) {}

    /**
     * One attempt of one stage — the row itself, not a summary of it, so a reader can see which
     * stage was expensive and which one was retried.
     *
     * <p>Ordered by pipeline position then attempt. Deliberately NOT by {@code created_at}: that
     * column defaults to {@code now()}, which Postgres holds constant for a transaction, so every
     * stage written in one transaction shares a timestamp and ordering by it is a coin flip.
     */
    public record StageUsageView(
            String stage,
            String status,
            int attempt,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String skipReason,
            String errorCode,
            String workerVersion,
            JsonNode parserVersions) {}

    /**
     * Retries, counted, because a retry is compute paid for twice.
     *
     * @param jobAttempt null when no job row exists yet. Not 1 — a package that has never been
     *     processed has no attempt count, and writing one down would be the same class of
     *     invention as writing down a dollar figure nobody measured.
     * @param stageAttempts total attempt rows across every stage.
     * @param retriedAttempts rows with {@code attempt > 1}: the re-runs.
     * @param failedAttempts rows that ended FAILED.
     */
    public record AttemptsView(
            Integer jobAttempt,
            String jobStatus,
            Integer parseGeneration,
            long stageAttempts,
            long retriedAttempts,
            long failedAttempts,
            List<RetriedStageView> retries) {}

    /**
     * @param attempts the highest attempt number reached for this stage.
     * @param lastErrorCode the most recent non-null error code among the stage's attempts — the
     *     reason the retries happened. Null if a stage was retried without recording one.
     */
    public record RetriedStageView(String stage, int attempts, String lastErrorCode) {}

    /**
     * Model spend for this package. Today: zero calls, zero tokens, zero dollars — and the
     * {@link #producers()} list says which systems that zero actually covers.
     *
     * <p><b>How a future producer fills this in, with no second mechanism.</b> The totals are read
     * from {@code ai_interpretation} (V8), which already carries {@code provider}, {@code model},
     * {@code tokens_in}, {@code tokens_out} and {@code cost_usd} and is the provider ledger the
     * suggestions design assigns that role
     * ({@code docs/superpowers/specs/2026-08-17-suggestions-and-human-override-design.md} §6.2).
     * The engine's Phase L LLM suggestion tier writes rows there against a PAGE, LOGICAL_DOCUMENT
     * or EXTRACTED_FIELD of the package; this view sums them and the numbers stop being zero. No
     * new table, no new column, no shape change, and therefore no UI change.
     *
     * <p><b>What this cannot see, said out loud.</b> rag-brain's analyzer spends against its own
     * store in another service. It is listed in {@link #producers()} as NOT observed, so a reader
     * knows the zero is "the engine spent nothing", not "nothing was spent anywhere". It becomes
     * observed the day it writes {@code ai_interpretation} rows for this package's subjects —
     * the same mechanism, not a second one.
     *
     * @param costUsd always present, always at the ledger's own scale (6dp). Zero here is a
     *     measurement — the count of calls is zero — never a rate applied to a guess.
     */
    public record ModelCostView(
            long calls,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            String currency,
            List<ModelSpendView> byModel,
            List<CostProducerView> producers) {}

    /** One (producer, provider, model) triple's spend. Empty list while nothing has spent. */
    public record ModelSpendView(
            String producer,
            String provider,
            String model,
            long calls,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd) {}

    /**
     * A known spender and whether this endpoint can actually see its spend. The anti-lie
     * mechanism: a total without a stated scope invites the reader to assume it is complete.
     *
     * @param observed true when the totals above include this producer's rows.
     * @param reason plain-language note, PII-free and constant per producer.
     */
    public record CostProducerView(String producer, boolean observed, String reason) {}
}
