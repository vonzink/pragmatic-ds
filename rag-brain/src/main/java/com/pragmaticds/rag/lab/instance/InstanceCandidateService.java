package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.ConstraintResult;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.CreateInstanceCommand;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.WizardValidationScope;
import com.pragmaticds.rag.lab.release.EncodedManifest;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates instances and their candidate releases.
 *
 * <p><b>Nothing here can change what production answers with.</b> Every release this writes is
 * CANDIDATE, and the live pointer is moved only by {@link InstancePromotionService} behind its own
 * switch. Authoring and shipping are separate authorities on purpose: an administrator can build
 * and evaluate a release all day on a deployment where nothing may go live.
 *
 * <p><b>No server-side draft.</b> A create carries the complete definition and is validated as a
 * whole. There is no partial instance to garbage-collect, expire, or accidentally share between
 * two administrators editing the same instance, and a form abandoned halfway leaves nothing behind.
 *
 * <p><b>Releases are appended, never edited.</b> Editing an instance writes the next candidate with
 * the previous release as its predecessor, so the chain of what was proposed stays readable even
 * for versions that never shipped.
 */
public interface InstanceCandidateService {

    /** An instance and the candidate release just written for it. */
    record CandidateRelease(UUID instanceId, UUID releaseId, int releaseNumber,
                            String manifestSha256, UUID predecessorReleaseId) {}

    /** Why a candidate cannot be written. Stable codes; the gate's reasons ride along. */
    final class CandidateException extends RuntimeException {
        public enum Code {
            /** The definition failed one or more constraints; see {@link #violations()}. */
            INSTANCE_DEFINITION_INVALID,
            /** A create named a slug this brain already uses. */
            INSTANCE_ALREADY_EXISTS,
            /** An edit named an instance this brain does not have. */
            INSTANCE_NOT_FOUND
        }

        private final Code code;
        private final List<String> violations;

        public CandidateException(Code code) {
            this(code, List.of());
        }

        public CandidateException(Code code, List<String> violations) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
            this.violations = List.copyOf(violations);
        }

        public Code code() {
            return code;
        }

        public List<String> violations() {
            return violations;
        }
    }

    /** Validates without writing. The wizard's per-step and whole-form check are the same call. */
    ConstraintResult validate(WizardValidationScope scope, CreateInstanceCommand command);

    /** Creates the instance and its first candidate release. */
    CandidateRelease create(CreateInstanceCommand command);

    /** Appends the next candidate release to an existing instance. */
    CandidateRelease addCandidate(CreateInstanceCommand command);
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultInstanceCandidateService implements InstanceCandidateService {

    private final InstanceConstraintValidator validator;
    private final InstanceManifestCodec codec;
    private final LabInstanceRepository instances;
    private final LabInstanceReleaseRepository releases;
    private final TransactionTemplate isolated;

    DefaultInstanceCandidateService(InstanceConstraintValidator validator,
                                    InstanceManifestCodec codec,
                                    LabInstanceRepository instances,
                                    LabInstanceReleaseRepository releases,
                                    PlatformTransactionManager transactionManager) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.isolated = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ConstraintResult validate(WizardValidationScope scope, CreateInstanceCommand command) {
        return validator.validate(scope, command);
    }

    @Override
    public CandidateRelease create(CreateInstanceCommand command) {
        requireValid(command);
        return isolated.execute(status -> {
            if (instances.findByBrainIdAndSlug(command.brainId(), command.slug()).isPresent()) {
                throw new CandidateException(CandidateException.Code.INSTANCE_ALREADY_EXISTS);
            }
            LabInstance instance = new LabInstance(command.brainId(), command.slug(),
                    command.displayName(), command.purpose());
            LabInstance saved = instances.saveAndFlush(instance);
            LabInstanceRelease release = write(command, 1, null);
            return new CandidateRelease(saved.getId(), release.getId(), release.getReleaseNumber(),
                    release.getManifestSha256(), null);
        });
    }

    @Override
    public CandidateRelease addCandidate(CreateInstanceCommand command) {
        requireValid(command);
        return isolated.execute(status -> {
            // Locked, not merely read: two administrators saving edits at the same moment would
            // otherwise both compute the same next release number and one insert would lose to
            // the release-number uniqueness constraint rather than simply queueing behind the
            // other.
            LabInstance instance = instances
                    .lockByBrainIdAndSlug(command.brainId(), command.slug())
                    .orElseThrow(() -> new CandidateException(
                            CandidateException.Code.INSTANCE_NOT_FOUND));

            Optional<LabInstanceRelease> latest = releases
                    .findFirstByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(
                            command.brainId(), command.slug());
            int next = latest.map(LabInstanceRelease::getReleaseNumber).orElse(0) + 1;
            UUID predecessor = latest.map(LabInstanceRelease::getId).orElse(null);

            LabInstanceRelease release = write(command, next, predecessor);
            return new CandidateRelease(instance.getId(), release.getId(),
                    release.getReleaseNumber(), release.getManifestSha256(), predecessor);
        });
    }

    /**
     * One immutable candidate release.
     *
     * <p>The manifest is encoded through the shared codec rather than serialized here, so the
     * stored digest is the one every reader recomputes. A release whose hash was produced any
     * other way would fail its own drift check.
     */
    private LabInstanceRelease write(CreateInstanceCommand command, int number, UUID predecessor) {
        EncodedManifest encoded = codec.encode(command.manifest());
        LabInstanceRelease release = new LabInstanceRelease();
        release.setBrainId(command.brainId());
        release.setInstanceSlug(command.slug());
        release.setReleaseNumber(number);
        // CANDIDATE, always. Selecting a candidate is not shipping it, and this service has no
        // path that writes PRODUCTION.
        release.setProvenanceMode(LabInstanceRelease.ProvenanceMode.CANDIDATE);
        release.setManifest(encoded.json());
        release.setManifestSha256(encoded.sha256());
        release.setPredecessorReleaseId(predecessor);
        return releases.saveAndFlush(release);
    }

    private void requireValid(CreateInstanceCommand command) {
        ConstraintResult result = validator.validate(WizardValidationScope.COMPLETE, command);
        if (!result.valid()) {
            // Every reason at once. Fixing one blocker only to be shown the next is how a create
            // takes six attempts.
            throw new CandidateException(
                    CandidateException.Code.INSTANCE_DEFINITION_INVALID, result.codes());
        }
    }
}
