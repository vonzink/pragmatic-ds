package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.CreateInstanceCommand;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.run.domain.LabInstancePointerEvent;
import com.pragmaticds.rag.lab.run.domain.LabReleaseEvaluation;
import com.pragmaticds.rag.lab.run.repository.LabInstancePointerEventRepository;
import com.pragmaticds.rag.lab.run.repository.LabReleaseEvaluationRepository;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import com.pragmaticds.rag.lab.service.LabAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves an instance's live pointer, or refuses and explains in codes.
 *
 * <p><b>Selecting a candidate never moves live.</b> Authoring writes immutable CANDIDATE releases
 * and nothing else; this is the only path that changes what production answers with, and it is
 * separately switched by {@code ragbrain.instances.promotion-enabled} so a deployment can host the
 * whole authoring surface with promotion off.
 *
 * <p><b>Compare-and-set, not last-writer-wins.</b> A promotion states which release it believes is
 * live and at which pointer version, and it applies only if both still hold. Two administrators
 * promoting different candidates from the same page do not silently overwrite each other: the
 * second is told {@code LIVE_POINTER_CHANGED} and the pointer does not move. Without the version,
 * a rollback followed by a re-promotion of the same release would be indistinguishable from no
 * change at all, and the CAS would wrongly succeed.
 *
 * <p><b>The gate re-runs every constraint.</b> A release is immutable but the world around it is
 * not: a collection disabled, a credential removed, or a scenario set revised between authoring and
 * promotion all have to stop the promotion. Re-validating here rather than trusting the release's
 * authoring-time verdict is the difference between "this was valid once" and "this is valid now".
 */
public interface InstancePromotionService {

    /**
     * One promotion request.
     *
     * @param expectedLiveReleaseId what the caller believes is live; null asserts nothing is
     * @param expectedPointerVersion the version the caller read; ignored when nothing is live
     */
    record PromotionCommand(
            UUID brainId,
            String instanceSlug,
            UUID candidateReleaseId,
            UUID expectedLiveReleaseId,
            long expectedPointerVersion,
            String actorId,
            String changeReason) {}

    /** Allowed, or a list of blocking codes. Never a message and never a configured value. */
    record PromotionDecision(boolean allowed, List<String> blockingCodes) {
        public PromotionDecision {
            blockingCodes = List.copyOf(Objects.requireNonNull(blockingCodes, "blockingCodes"));
        }
    }

    /** What the pointer looks like after a movement that actually happened. */
    record PointerState(UUID liveReleaseId, long pointerVersion) {}

    /**
     * Whether this deployment permits the pointer to move at all.
     *
     * <p>Published so an operator can be told the switch is off <em>before</em> composing a move,
     * rather than having to attempt one and read {@code INSTANCE_PROMOTION_DISABLED} off the
     * failure. It is a method rather than a second reading of
     * {@code ragbrain.instances.promotion-enabled} elsewhere, so the answer and the behaviour are
     * the same fact and cannot drift.
     */
    boolean promotionEnabled();

    /** Checks the gate without moving anything. Safe to call from a preview. */
    PromotionDecision evaluateGate(PromotionCommand command);

    /** Promotes a candidate to live, or refuses. */
    PointerState promote(PromotionCommand command);

    /**
     * Points live back at an earlier immutable release.
     *
     * <p>Runs the same gate and the same compare-and-set. It never edits the target release's
     * provenance mode, manifest, or hash: rolling back means pointing at what was already there,
     * not rewriting history so that it looks current.
     */
    PointerState rollback(PromotionCommand command);

    /** Why a promotion cannot proceed. Stable, value-free codes. */
    final class PromotionException extends RuntimeException {
        public enum Code {
            /** Promotion and rollback are switched off for this deployment. */
            INSTANCE_PROMOTION_DISABLED,
            /** The caller's view of live was stale; the pointer did not move. */
            LIVE_POINTER_CHANGED,
            /** The gate refused. The decision carries the individual reasons. */
            PROMOTION_BLOCKED,
            /** The request itself was unusable. */
            PROMOTION_REQUEST_INVALID
        }

        private final Code code;
        private final List<String> blockingCodes;

        public PromotionException(Code code) {
            this(code, List.of());
        }

        public PromotionException(Code code, List<String> blockingCodes) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
            this.blockingCodes = List.copyOf(blockingCodes);
        }

        public Code code() {
            return code;
        }

        public List<String> blockingCodes() {
            return blockingCodes;
        }
    }
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultInstancePromotionService implements InstancePromotionService {

    private final InstanceRegistryService registry;
    private final InstanceConstraintValidator validator;
    private final LabInstanceReleaseRepository releases;
    private final LabInstancePointerRepository pointers;
    private final LabInstancePointerEventRepository pointerEvents;
    private final LabReleaseEvaluationRepository evaluations;
    /**
     * The exact stored JSON is the read boundary, not the entity's mapped {@code Map}.
     *
     * <p>Hibernate deserializes a JSONB attribute through its format mapper, which returns a
     * {@code BigDecimal} as a {@code Double} and a {@code Long} as an {@code Integer} — and a
     * {@code Double} cannot carry {@code 0.250} at all. Decoding that map therefore either fails
     * outright or silently gates on a manifest whose digest is no longer the stored one, so the
     * gate reads through the verified reader, which reads the JSONB text itself and checks it
     * against {@code manifest_sha256} before decoding.
     */
    private final VerifiedInstanceReleaseReader manifests;
    private final LabAuditService audit;
    private final InstanceControlMetrics metrics;
    private final TransactionTemplate isolated;
    private final boolean promotionEnabled;

    DefaultInstancePromotionService(
            InstanceRegistryService registry,
            InstanceConstraintValidator validator,
            LabInstanceReleaseRepository releases,
            LabInstancePointerRepository pointers,
            LabInstancePointerEventRepository pointerEvents,
            LabReleaseEvaluationRepository evaluations,
            VerifiedInstanceReleaseReader manifests,
            LabAuditService audit,
            InstanceControlMetrics metrics,
            PlatformTransactionManager transactionManager,
            @Value("${ragbrain.instances.promotion-enabled:false}") boolean promotionEnabled) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.pointers = Objects.requireNonNull(pointers, "pointers");
        this.pointerEvents = Objects.requireNonNull(pointerEvents, "pointerEvents");
        this.evaluations = Objects.requireNonNull(evaluations, "evaluations");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.isolated = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.promotionEnabled = promotionEnabled;
    }

    @Override
    public boolean promotionEnabled() {
        return promotionEnabled;
    }

    @Override
    public PromotionDecision evaluateGate(PromotionCommand command) {
        require(command);
        // Reads only. A caller may check the gate with promotion switched off, because knowing
        // why a release cannot ship is useful even where shipping is not permitted.
        return gate(command);
    }

    @Override
    public PointerState promote(PromotionCommand command) {
        return move(command, LabInstancePointerEvent.Action.PROMOTE);
    }

    @Override
    public PointerState rollback(PromotionCommand command) {
        return move(command, LabInstancePointerEvent.Action.ROLLBACK);
    }

    private PointerState move(PromotionCommand command, LabInstancePointerEvent.Action action) {
        require(command);
        try {
            if (!promotionEnabled) {
                throw new PromotionException(PromotionException.Code.INSTANCE_PROMOTION_DISABLED);
            }
            PromotionDecision decision = gate(command);
            if (!decision.allowed()) {
                throw new PromotionException(
                        PromotionException.Code.PROMOTION_BLOCKED, decision.blockingCodes());
            }
            PointerState moved = isolated.execute(status -> apply(command, action));
            // The action name and the refusal codes are both bounded enums — exactly what a
            // metric tag is allowed to be.
            metrics.promotion(action.name());
            return moved;
        } catch (PromotionException refusal) {
            metrics.promotion(refusal.code().name());
            throw refusal;
        }
    }

    /**
     * The compare-and-set itself, inside one transaction.
     *
     * <p>The pointer row is locked before it is read, so the check and the write cannot straddle
     * another promotion. The row lock is what makes the version comparison meaningful; without it
     * two callers could both read version 3 and both write version 4.
     */
    private PointerState apply(PromotionCommand command, LabInstancePointerEvent.Action action) {
        Optional<LabInstancePointer> existing = pointers.lockByBrainIdAndInstanceSlug(
                command.brainId(), command.instanceSlug());

        UUID from;
        long nextVersion;
        if (existing.isEmpty()) {
            // A first promotion asserts that nothing is live. A caller that believed otherwise is
            // working from a view that no longer matches, which is the same failure as a stale
            // version and gets the same answer.
            if (command.expectedLiveReleaseId() != null) {
                throw new PromotionException(PromotionException.Code.LIVE_POINTER_CHANGED);
            }
            if (action == LabInstancePointerEvent.Action.ROLLBACK) {
                // Nothing to roll back to, and the pointer event's own constraint would refuse a
                // rollback with no predecessor anyway.
                throw new PromotionException(PromotionException.Code.LIVE_POINTER_CHANGED);
            }
            from = null;
            nextVersion = 1L;
            LabInstancePointer created = new LabInstancePointer();
            created.setBrainId(command.brainId());
            created.setInstanceSlug(command.instanceSlug());
            created.setProductionReleaseId(command.candidateReleaseId());
            created.setPointerVersion(nextVersion);
            pointers.saveAndFlush(created);
        } else {
            LabInstancePointer pointer = existing.get();
            // Both halves must match. The release alone is not enough: promote A, roll back to B,
            // promote A again, and a release-only check would let a caller holding the first view
            // succeed against a pointer that has moved twice.
            if (!pointer.getProductionReleaseId().equals(command.expectedLiveReleaseId())
                    || pointer.getPointerVersion() != command.expectedPointerVersion()) {
                throw new PromotionException(PromotionException.Code.LIVE_POINTER_CHANGED);
            }
            from = pointer.getProductionReleaseId();
            nextVersion = pointer.getPointerVersion() + 1;
            pointer.setProductionReleaseId(command.candidateReleaseId());
            pointer.setPointerVersion(nextVersion);
            pointers.saveAndFlush(pointer);
        }

        pointerEvents.saveAndFlush(new LabInstancePointerEvent(
                command.brainId(), command.instanceSlug(), action, from,
                command.candidateReleaseId(), nextVersion,
                command.actorId(), command.changeReason()));

        // Counts and identifiers only, exactly as every other Lab audit row: the change reason is
        // already recorded on the immutable pointer event and does not belong in a second place.
        audit.record(command.brainId(), LabAuditService.RELEASE_POINTER_MOVED,
                LabAuditEvent.Status.SUCCEEDED, LabAuditEvent.SubjectType.RELEASE,
                command.candidateReleaseId(), null,
                Map.of("pointerVersion", nextVersion, "rollback",
                        action == LabInstancePointerEvent.Action.ROLLBACK));

        return new PointerState(command.candidateReleaseId(), nextVersion);
    }

    /**
     * Every reason this release may not go live, collected rather than short-circuited.
     *
     * <p>Collected because an administrator fixing one blocker only to be shown the next one is
     * how a promotion takes six attempts. The order is stable so the same state always produces
     * the same list.
     */
    private PromotionDecision gate(PromotionCommand command) {
        List<String> blocking = new ArrayList<>();

        LabInstance instance;
        try {
            instance = registry.require(new InstanceKey(command.brainId(), command.instanceSlug()));
        } catch (InstanceRegistryService.InstanceException absent) {
            // Nothing further can be checked without an instance, and reporting six consequences
            // of one missing row would bury the cause.
            return new PromotionDecision(false, List.of(absent.code().name()));
        }
        if (instance.getState() != LabInstance.State.ACTIVE) {
            blocking.add(InstanceRegistryService.InstanceException.Code.INSTANCE_DISABLED.name());
        }

        Optional<LabInstanceRelease> found = releases.findByIdAndBrainIdAndInstanceSlug(
                command.candidateReleaseId(), command.brainId(), command.instanceSlug());
        if (found.isEmpty()) {
            // A release id that belongs to another brain or another instance is reported as
            // absent, not as forbidden: an admin key for one brain must not be able to probe.
            return new PromotionDecision(false, List.of("RELEASE_NOT_FOUND"));
        }
        LabInstanceRelease release = found.get();

        DecodedInstanceManifest decoded = manifests.read(release);
        if (!(decoded instanceof DecodedInstanceManifest.V2 v2)) {
            // A v1 Income release predates every contract this gate checks, so there is nothing
            // to validate it against and nothing that could honestly be called a pass.
            return new PromotionDecision(false, List.of("INSTANCE_RELEASE_NOT_PINNABLE"));
        }
        InstanceReleaseManifest manifest = v2.manifest();

        // The world may have moved since authoring: re-run every constraint against it now.
        blocking.addAll(validator.validate(
                InstanceConstraintValidator.WizardValidationScope.COMPLETE,
                new CreateInstanceCommand(command.brainId(), command.instanceSlug(),
                        instance.getDisplayName(), instance.getPurpose(), manifest)).codes());

        blocking.addAll(evaluationBlockers(command, manifest));
        return new PromotionDecision(blocking.isEmpty(), blocking);
    }

    /**
     * The scenario set must have been run against this exact release, at this exact version, and
     * must have passed at or above the release's own minimum.
     *
     * <p>Exact rather than latest on both axes. A pass recorded for a different release says
     * nothing about this one, and a pass against version 1 of a scenario set says nothing about
     * version 2 — which is the whole reason evaluations are keyed by version.
     */
    private List<String> evaluationBlockers(PromotionCommand command,
                                            InstanceReleaseManifest manifest) {
        InstanceReleaseManifest.EvaluationContract contract = manifest.evaluations();
        if (contract == null) {
            return List.of("EVALUATION_CONTRACT_INVALID");
        }
        Optional<LabReleaseEvaluation> evaluation =
                evaluations.findByReleaseIdAndScenarioSetIdAndScenarioSetVersion(
                        command.candidateReleaseId(), contract.scenarioSetId(),
                        contract.scenarioSetVersion());
        if (evaluation.isEmpty()) {
            return List.of("EVALUATION_MISSING");
        }
        LabReleaseEvaluation result = evaluation.get();
        if (!result.isPassed()) {
            return List.of("EVALUATION_FAILED");
        }
        if (contract.minimumScore() != null
                && result.getScore().compareTo(contract.minimumScore()) < 0) {
            // A pass flag and a threshold can disagree if the threshold was raised after the run.
            return List.of("EVALUATION_BELOW_MINIMUM_SCORE");
        }
        return List.of();
    }

    private static void require(PromotionCommand command) {
        if (command == null || command.brainId() == null || command.instanceSlug() == null
                || command.instanceSlug().isBlank() || command.candidateReleaseId() == null
                || blankOrControl(command.actorId()) || blankOrControl(command.changeReason())
                || command.expectedPointerVersion() < 0) {
            throw new PromotionException(PromotionException.Code.PROMOTION_REQUEST_INVALID);
        }
    }

    /**
     * A pointer event's prose is bounded and control-character free at the database too. Rejecting
     * it here means the caller gets a code rather than a constraint violation, and a newline never
     * reaches an audit export where it could fake a second record.
     */
    private static boolean blankOrControl(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.chars().anyMatch(Character::isISOControl);
    }
}
