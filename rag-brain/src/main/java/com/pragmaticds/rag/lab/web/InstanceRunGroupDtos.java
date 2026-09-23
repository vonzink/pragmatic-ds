package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.model.InstanceModelCatalogService.CatalogModel;
import com.pragmaticds.rag.lab.run.RunGroupCommand;
import com.pragmaticds.rag.lab.run.RunGroupCommand.MemberPreflight;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunGroupPreflight;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunMemberCommand;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.domain.LabRun;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire shape of run groups, members, and cost.
 *
 * <p><b>Numbers, identifiers, and codes.</b> There is no member of any record here that a prompt,
 * an answer, a document, evidence, or a provider's response body could travel in. A member's
 * result is fetched by its own detail route, which decrypts for one authorized read; the listing
 * and status shapes carry counts and money only.
 *
 * <p><b>Absent is rendered as null, never as zero.</b> A provider that reported no usage produces
 * nulls here and a quality of {@code UNAVAILABLE}, so a reader can tell "we do not know" from "it
 * was free" — which is the whole reason those two are separate states in the database.
 */
public final class InstanceRunGroupDtos {
    private InstanceRunGroupDtos() {}

    // ================================================================ requests

    /** One member of a submission. Identifiers only; the release decides everything else. */
    public record MemberRequest(
            String instanceSlug, UUID releaseId, UUID registrationId, UUID corpusSnapshotId) {}

    /** A submission. {@code comparisonDimension} is required exactly when the mode is COMPARISON. */
    public record CreateRunGroupRequest(
            String mode, String comparisonDimension, List<MemberRequest> members) {}

    // ================================================================ responses

    /** One offerable model, as the wizard lists them. */
    public record CatalogModelView(
            String provider, String model, long contextTokenCeiling, long outputTokenCeiling,
            String tokenizerStrategy, BigDecimal inputUsdPerMillion,
            BigDecimal cachedInputUsdPerMillion, BigDecimal outputUsdPerMillion) {}

    /** What one member is expected to consume, before it runs. */
    public record MemberEstimateView(
            int memberIndex, String instanceSlug, UUID releaseId, UUID registrationId,
            UUID corpusSnapshotId, String provider, String model, UUID pricingVersionId,
            long inputTokensMin, long inputTokensMax, long outputTokensMin, long outputTokensMax,
            BigDecimal costUsdMin, BigDecimal costUsdMax, String estimateQuality,
            List<String> blockingCodes) {}

    /** A priced submission that has not been created. */
    public record PreflightView(
            String requestSha256, String comparisonBasisSha256, List<MemberEstimateView> members,
            BigDecimal reservedMaximumUsd, BigDecimal committedTodayUsd,
            BigDecimal alreadyReservedUsd, BigDecimal dailyBudgetUsd, boolean withinBudget,
            boolean acceptable, List<String> blockingCodes) {}

    /** A created or replayed group. */
    public record CreatedRunGroupView(UUID groupId, boolean created, List<UUID> memberRunIds) {}

    /** What one purge removed. Counts and booleans only, like the outcome it mirrors. */
    public record GroupPurgeView(
            boolean deleted, int runsDeleted, boolean connectorContextDeleted) {}

    /** A group in a listing: status and shape, never results. */
    public record RunGroupSummaryView(
            UUID groupId, UUID brainId, String mode, String comparisonDimension, String status,
            int memberCount, OffsetDateTime createdAt, OffsetDateTime terminalAt,
            OffsetDateTime cancellationRequestedAt) {}

    /**
     * One member's outcome and cost.
     *
     * <p>{@code result} is present only on the authorized detail read and only for a succeeded
     * member; everywhere else it is null, so a listing can never become a way to read answers.
     */
    public record MemberDetailView(
            int memberIndex, UUID runId, String instanceSlug, UUID releaseId, UUID registrationId,
            UUID corpusSnapshotId, String status, String failureCode, String provider,
            String model, UUID pricingVersionId,
            long expectedInputMin, long expectedInputMax, long expectedOutputMin,
            long expectedOutputMax, BigDecimal expectedCostUsdMin, BigDecimal expectedCostUsdMax,
            String estimateQuality,
            Long actualInputTokens, Long actualCachedTokens, Long actualOutputTokens,
            Long actualTotalTokens, BigDecimal actualCostUsd, String usageQuality,
            OffsetDateTime createdAt, OffsetDateTime terminalAt,
            Map<String, Object> result) {}

    /** A group with its members. */
    public record RunGroupDetailView(
            RunGroupSummaryView group, List<MemberDetailView> members) {}

    /** How much of a cancellation took effect. */
    public record CancellationView(int cancelledMembers, int stillProcessingMembers) {}

    // ================================================================ projections

    static RunGroupCommand command(UUID brainId, CreateRunGroupRequest request) {
        LabRunGroup.Mode mode = enumOrNull(LabRunGroup.Mode.class, request.mode());
        LabRunGroup.ComparisonDimension dimension = enumOrNull(
                LabRunGroup.ComparisonDimension.class, request.comparisonDimension());
        List<RunMemberCommand> members = request.members() == null ? List.of()
                : request.members().stream()
                        .map(member -> new RunMemberCommand(member.instanceSlug(),
                                member.releaseId(), member.registrationId(),
                                member.corpusSnapshotId()))
                        .toList();
        return new RunGroupCommand(brainId, mode, dimension, members);
    }

    static PreflightView preflight(RunGroupPreflight checked) {
        return new PreflightView(checked.requestSha256(), checked.comparisonBasisSha256(),
                checked.members().stream().map(InstanceRunGroupDtos::estimate).toList(),
                checked.reservedMaximumUsd(), checked.committedTodayUsd(),
                checked.alreadyReservedUsd(), checked.dailyBudgetUsd(), checked.withinBudget(),
                checked.acceptable(), checked.blockingCodes());
    }

    static CatalogModelView catalogModel(CatalogModel model) {
        return new CatalogModelView(model.provider(), model.model(), model.contextTokenCeiling(),
                model.outputTokenCeiling(), model.tokenizerStrategy().name(),
                model.inputUsdPerMillion(), model.cachedInputUsdPerMillion(),
                model.outputUsdPerMillion());
    }

    static RunGroupSummaryView summary(LabRunGroup group, int memberCount) {
        return new RunGroupSummaryView(group.getId(), group.getBrainId(), group.getMode().name(),
                group.getComparisonDimension() == null
                        ? null : group.getComparisonDimension().name(),
                group.getStatus().name(), memberCount, group.getCreatedAt(),
                group.getTerminalAt(), group.getCancellationRequestedAt());
    }

    /**
     * One member, with usage where it exists.
     *
     * <p>A missing usage row and an unavailable one both render as nulls, because from a reader's
     * side both mean the same thing: nothing was measured.
     */
    static MemberDetailView member(LabRun run, LabModelUsage usage, Map<String, Object> result) {
        return new MemberDetailView(run.getMemberIndex(), run.getId(), run.getInstanceSlug(),
                run.getReleaseId(), run.getRegistrationId(), run.getCorpusSnapshotId(),
                run.getStatus().name(), run.getFailureCode(), run.getRequestedProvider(),
                run.getRequestedModel(), run.getPricingVersionId(),
                usage == null ? 0 : usage.getExpectedInputMin(),
                usage == null ? 0 : usage.getExpectedInputMax(),
                usage == null ? 0 : usage.getExpectedOutputMin(),
                usage == null ? 0 : usage.getExpectedOutputMax(),
                usage == null ? null : usage.getExpectedCostUsdMin(),
                usage == null ? null : usage.getExpectedCostUsdMax(),
                usage == null ? null : usage.getEstimateQuality().name(),
                usage == null ? null : usage.getActualInputTokens(),
                usage == null ? null : usage.getActualCachedTokens(),
                usage == null ? null : usage.getActualOutputTokens(),
                usage == null ? null : usage.getActualTotalTokens(),
                usage == null ? null : usage.getActualCostUsd(),
                usage == null ? null : usage.getUsageQuality().name(),
                run.getCreatedAt(), run.getTerminalAt(), result);
    }

    private static MemberEstimateView estimate(MemberPreflight member) {
        return new MemberEstimateView(member.memberIndex(), member.instanceSlug(),
                member.releaseId(), member.registrationId(), member.corpusSnapshotId(),
                member.provider(), member.model(), member.pricingVersionId(),
                member.inputTokensMin(), member.inputTokensMax(), member.outputTokensMin(),
                member.outputTokensMax(), member.costUsdMin(), member.costUsdMax(),
                member.estimateQuality(), member.blockingCodes());
    }

    /** An unrecognised enum name becomes null so validation reports it, not a parse failure. */
    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unrecognised) {
            return null;
        }
    }
}
