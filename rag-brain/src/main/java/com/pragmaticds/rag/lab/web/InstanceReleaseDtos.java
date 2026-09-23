package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.eval.InstanceEvaluationService.EvaluationResult;
import com.pragmaticds.rag.lab.instance.InstanceCandidateService.CandidateRelease;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.ConstraintResult;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.Violation;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PointerState;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PromotionDecision;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The wire shape of authoring, evaluating, and promoting a release.
 *
 * <p><b>A manifest goes in; nothing about it comes back out.</b> Creating a candidate accepts the
 * complete definition — prompts included — and answers with identifiers and digests. Reading a
 * release back is the existing admin surface's job, and no response here echoes a configured
 * value, because a validation error that quoted a prompt would put content into a log line.
 *
 * <p><b>Violations are a section plus a code.</b> That is enough for a wizard to mark the right
 * field, and not enough to reveal what was typed into it.
 */
public final class InstanceReleaseDtos {
    private InstanceReleaseDtos() {}

    // ================================================================ requests

    /** A complete instance definition. There is no partial form on the server. */
    public record CreateInstanceRequest(
            String slug, String displayName, String purpose, InstanceReleaseManifest manifest) {}

    /**
     * A promotion or rollback.
     *
     * <p>{@code expectedLiveReleaseId} and {@code expectedPointerVersion} are the caller's view of
     * what is live. Both must still hold, which is what stops two administrators promoting
     * different candidates from the same page and one silently winning.
     */
    public record PointerMoveRequest(
            UUID expectedLiveReleaseId, Long expectedPointerVersion,
            String actorId, String changeReason) {}

    // ================================================================ responses

    /** One refusal: which section, and a stable code. */
    public record ViolationView(String section, String code) {}

    /** A validation verdict. */
    public record ValidationView(boolean valid, List<ViolationView> violations) {}

    /** A written candidate release. */
    public record CandidateReleaseView(
            UUID instanceId, UUID releaseId, int releaseNumber, String provenanceMode,
            String manifestSha256, UUID predecessorReleaseId) {}

    /** An evaluation verdict. The report is referenced by digest and never returned. */
    public record EvaluationView(
            UUID evaluationId, UUID releaseId, String scenarioSetId, int scenarioSetVersion,
            BigDecimal score, boolean passed, String reportSha256,
            int scenariosRun, int scenariosPassed) {}

    /** Whether a release may go live, and everything stopping it if not. */
    public record PromotionDecisionView(boolean allowed, List<String> blockingCodes) {}

    /** Where the live pointer ended up. */
    public record PointerStateView(UUID liveReleaseId, long pointerVersion) {}

    // ================================================================ projections

    static ValidationView validation(ConstraintResult result) {
        return new ValidationView(result.valid(), result.violations().stream()
                .map(InstanceReleaseDtos::violation).toList());
    }

    static CandidateReleaseView candidate(CandidateRelease release) {
        return new CandidateReleaseView(release.instanceId(), release.releaseId(),
                release.releaseNumber(),
                // Always CANDIDATE: this surface has no path that writes PRODUCTION, and saying
                // so in the response is cheaper than a reader having to know that.
                "CANDIDATE", release.manifestSha256(), release.predecessorReleaseId());
    }

    static EvaluationView evaluation(EvaluationResult result) {
        return new EvaluationView(result.evaluationId(), result.releaseId(),
                result.scenarioSetId(), result.scenarioSetVersion(), result.score(),
                result.passed(), result.reportSha256(), result.scenariosRun(),
                result.scenariosPassed());
    }

    static PromotionDecisionView decision(PromotionDecision decision) {
        return new PromotionDecisionView(decision.allowed(), decision.blockingCodes());
    }

    static PointerStateView pointer(PointerState state) {
        return new PointerStateView(state.liveReleaseId(), state.pointerVersion());
    }

    private static ViolationView violation(Violation violation) {
        return new ViolationView(violation.section().name(), violation.code());
    }
}
