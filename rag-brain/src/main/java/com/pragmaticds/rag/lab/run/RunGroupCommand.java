package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.run.domain.LabRunGroup;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One submission: what to run, and — for a comparison — what is allowed to differ.
 *
 * <p>Members are identified entirely by id. A caller names a release, a parsed registration, and a
 * corpus snapshot; it never supplies a prompt, a model, a price, or a document. Everything that
 * decides what a run does is read from the release the caller pointed at, which is what makes a
 * comparison meaningful: the two members differ in exactly the declared dimension because there is
 * no other channel through which they could differ.
 */
public record RunGroupCommand(
        UUID brainId,
        LabRunGroup.Mode mode,
        LabRunGroup.ComparisonDimension comparisonDimension,
        List<RunMemberCommand> members) {

    public RunGroupCommand {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }

    /** One member. Ids only — see the record's own javadoc for why that matters. */
    public record RunMemberCommand(
            String instanceSlug,
            UUID releaseId,
            UUID registrationId,
            UUID corpusSnapshotId) {}

    /**
     * What one member resolved to, and what it is expected to consume.
     *
     * <p>{@code blockingCodes} is per member so a caller fixing a five-member group can see all
     * five problems at once rather than one per submission.
     */
    public record MemberPreflight(
            int memberIndex,
            String instanceSlug,
            UUID releaseId,
            UUID registrationId,
            UUID corpusSnapshotId,
            String provider,
            String model,
            UUID pricingVersionId,
            long inputTokensMin,
            long inputTokensMax,
            long outputTokensMin,
            long outputTokensMax,
            BigDecimal costUsdMin,
            BigDecimal costUsdMax,
            String estimateQuality,
            List<String> blockingCodes) {

        public MemberPreflight {
            blockingCodes = List.copyOf(Objects.requireNonNull(blockingCodes, "blockingCodes"));
        }
    }

    /**
     * The whole group's verdict before anything is written.
     *
     * <p>{@code reservedMaximumUsd} is the sum of member maxima, not of midpoints. Reserving the
     * maximum is what lets a budget rejection happen before dispatch rather than after the bill:
     * a group that reserved its expected cost and then landed at its upper bound would overshoot a
     * budget that had already approved it.
     */
    public record RunGroupPreflight(
            String requestSha256,
            String comparisonBasisSha256,
            List<MemberPreflight> members,
            BigDecimal reservedMaximumUsd,
            BigDecimal committedTodayUsd,
            BigDecimal alreadyReservedUsd,
            BigDecimal dailyBudgetUsd,
            boolean withinBudget,
            List<String> blockingCodes) {

        public RunGroupPreflight {
            members = List.copyOf(Objects.requireNonNull(members, "members"));
            blockingCodes = List.copyOf(Objects.requireNonNull(blockingCodes, "blockingCodes"));
        }

        /** True when nothing blocks the group and nothing blocks any member. */
        public boolean acceptable() {
            return blockingCodes.isEmpty()
                    && members.stream().allMatch(member -> member.blockingCodes().isEmpty());
        }
    }
}
