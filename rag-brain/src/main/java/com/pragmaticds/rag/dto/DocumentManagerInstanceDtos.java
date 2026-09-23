package com.pragmaticds.rag.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The connector-facing wire shapes for live instance runs.
 *
 * <p>The request is nearly empty by design: a package, a revision, a source selection, a tenant,
 * and an opaque correlation id. Every field a caller could use to steer execution — release,
 * candidate, provider, model, prompt, tool, schema, corpus, pricing, fallback, budget — is absent
 * from the type, so it cannot be smuggled in and cannot be added without it being a visible API
 * change.
 *
 * <p>The responses carry what an integrator can act on and nothing a tenant should never see:
 * statuses, sanitized codes, token categories with their quality labels, cost ranges marked as
 * estimates, and the decrypted result for a member that succeeded. No prompts, no corpus chunk
 * bodies, no provider error text, no encrypted provenance internals.
 */
public final class DocumentManagerInstanceDtos {
    private DocumentManagerInstanceDtos() {}

    // ================================================================ requests

    /**
     * @param subjectScope the host app's opaque per-loan scope, or null. Opaque by contract: the
     *     Suite derives it from a loan id and RAG Brain never reverses it. Excluded from the
     *     connector-visible request digest for the same reason {@code loanFacts} is — what a
     *     registration IS decides replay, not the loan it belongs to.
     */
    public record StartLiveInstanceRequest(
            String tenantId,
            String externalRequestId,
            UUID packageId,
            Integer revision,
            List<UUID> selectedSourceIds,
            LoanFactsDto loanFacts,
            String subjectScope) {}

    /**
     * The loan-level facts an assets run's large-deposit rule is selected from.
     *
     * <p>Optional, and every field within it is optional. There is no single large-deposit test —
     * Fannie Mae measures against 50% of qualifying monthly income and only on a purchase, FHA
     * against 1% of the Adjusted Value — so without a program and a purpose the engine reports
     * the screen as not run rather than applying a threshold that may not be this loan's. An
     * unrecognized value is treated as absent, never as a default.
     *
     * <p>Attached to the parsed package, not to the run group: a run group's members are
     * identified entirely by id so that a comparison differs in exactly its declared dimension.
     * Sending different facts for a package already registered is refused with
     * {@code LOAN_FACTS_CONFLICT} rather than changing a queued run's basis.
     *
     * @param program                 FANNIE_MAE, FREDDIE_MAC, FHA, VA, or USDA
     * @param loanPurpose             PURCHASE or REFINANCE
     * @param qualifyingMonthlyIncome the Fannie/Freddie threshold basis
     * @param adjustedValue           the FHA threshold basis; for most purchases the sales price
     */
    public record LoanFactsDto(
            String program,
            String loanPurpose,
            BigDecimal qualifyingMonthlyIncome,
            BigDecimal adjustedValue) {}

    // ================================================================ responses

    /** One member's expected consumption, before it runs. Ranges, never single numbers. */
    public record MemberEstimateDto(
            UUID runId,
            String provider,
            String model,
            UUID pricingVersionId,
            long inputTokensMin,
            long inputTokensMax,
            long outputTokensMin,
            long outputTokensMax,
            BigDecimal costUsdMin,
            BigDecimal costUsdMax,
            String estimateQuality) {}

    /**
     * The {@code 202} body. {@code statusUrl} is the polling location, duplicated from the
     * {@code Location} header because integrators lose headers in middleware more often than they
     * lose body fields.
     */
    public record AcceptedRunGroup(
            UUID runGroupId,
            List<UUID> memberIds,
            String status,
            String statusUrl,
            List<MemberEstimateDto> estimates) {}

    /**
     * One member as polling sees it.
     *
     * <p>Every {@code actual*} field is nullable and stays null until the provider reported that
     * category; {@code usageQuality} says how to read the numbers beside it. A missing category is
     * never rendered as zero anywhere downstream, and this shape is what makes that possible.
     */
    public record MemberStatusDto(
            UUID runId,
            String status,
            String failureCode,
            String provider,
            String model,
            UUID pricingVersionId,
            long expectedInputMin,
            long expectedInputMax,
            long expectedOutputMin,
            long expectedOutputMax,
            BigDecimal expectedCostUsdMin,
            BigDecimal expectedCostUsdMax,
            String estimateQuality,
            Long actualInputTokens,
            Long actualCachedTokens,
            Long actualOutputTokens,
            Long actualTotalTokens,
            BigDecimal actualCostUsd,
            String usageQuality,
            OffsetDateTime createdAt,
            OffsetDateTime terminalAt,
            /** Wall milliseconds from creation to terminal; null while running. */
            Long latencyMillis,
            /** Decrypted output, present only for a member that succeeded. */
            Map<String, Object> result) {}

    public record RunGroupStatusDto(
            UUID runGroupId,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime terminalAt,
            List<MemberStatusDto> members) {}

    /** Stable machine-readable refusal. The code is the entire disclosure. */
    public record ErrorResponse(String code, List<String> blockingCodes) {}
}
