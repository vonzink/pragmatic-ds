package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand.MemberPreflight;
import com.pragmaticds.rag.lab.run.domain.LabConnectorRunGroupContext;
import com.pragmaticds.rag.lab.run.repository.LabConnectorRunGroupContextRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunGroupPreflight;
import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.LabSpendReservation;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.run.repository.LabSpendReservationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an accepted submission into a group, its queued members, and their reservations.
 *
 * <p><b>The preflight runs again here.</b> Not as a formality: everything it checks — a release,
 * a collection's version, a credential, a parse, today's spend — can change between the preview a
 * caller saw and the submission they sent. Accepting a client-supplied preflight would let a stale
 * "acceptable" be replayed past a constraint that has since started applying.
 *
 * <p><b>Idempotency is on the group, not the members.</b> The caller's key lives on
 * {@code lab_run_group}, and each member gets an internal key of {@code group:{id}:{index}}. That
 * is what lets one submission produce several runs of the same instance — two releases in one
 * comparison — without violating V34's per-instance run-key uniqueness, which was written when one
 * key meant one run.
 *
 * <p><b>The budget is checked under a lock.</b> Two groups submitted at once would otherwise both
 * read the same committed total, both find room, and both insert; the advisory lock is per brain,
 * so submissions for different brains never wait on each other.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class RunGroupService {

    /** Why a group cannot be created. Stable, value-free codes. */
    public static final class RunGroupException extends RuntimeException {
        public enum Code {
            /** The submission was refused; {@link #blockingCodes()} says why. */
            RUN_GROUP_BLOCKED,
            /** The key was already used for a different submission. */
            IDEMPOTENCY_KEY_REUSED,
            /** The request itself was unusable. */
            RUN_GROUP_REQUEST_INVALID
        }

        private final Code code;
        private final List<String> blockingCodes;

        public RunGroupException(Code code) {
            this(code, List.of());
        }

        public RunGroupException(Code code, List<String> blockingCodes) {
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

    /** A created — or replayed — group and the members it queued. */
    public record CreatedRunGroup(UUID groupId, boolean created, List<UUID> memberRunIds) {
        public CreatedRunGroup {
            memberRunIds = List.copyOf(Objects.requireNonNull(memberRunIds, "memberRunIds"));
        }
    }

    private final RunGroupPreflightService preflight;
    private final LabRunGroupRepository groups;
    private final LabRunRepository runs;
    private final LabModelUsageRepository usage;
    private final LabSpendReservationRepository reservations;
    private final LabConnectorRunGroupContextRepository connectorContexts;
    private final JdbcTemplate jdbc;
    private final InstanceControlMetrics metrics;
    private final TransactionTemplate isolated;

    public RunGroupService(RunGroupPreflightService preflight,
                           LabRunGroupRepository groups,
                           LabRunRepository runs,
                           LabModelUsageRepository usage,
                           LabSpendReservationRepository reservations,
                           LabConnectorRunGroupContextRepository connectorContexts,
                           JdbcTemplate jdbc,
                           InstanceControlMetrics metrics,
                           PlatformTransactionManager transactionManager) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.connectorContexts = Objects.requireNonNull(connectorContexts, "connectorContexts");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.isolated = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Creates the group, or replays the one this key already produced.
     *
     * <p>A true replay returns immediately, before any resolution: re-running a submission's
     * Document Engine reads to answer "you already sent this" would make a retry as expensive as
     * the original.
     */
    public CreatedRunGroup create(RunGroupCommand command, String idempotencyKey) {
        return create(command, idempotencyKey, new RunOrigin.Admin("admin"));
    }

    /**
     * Creates the group with its origin recorded in the same transaction.
     *
     * <p>For a {@link RunOrigin.Connector} the ownership context row commits or rolls back with
     * the group, the members, the estimates and the reservations as one unit — a group that
     * existed without its context would be one nobody is ever allowed to poll, and a context
     * without its group would authorize reading nothing.
     */
    public CreatedRunGroup create(RunGroupCommand command, String idempotencyKey,
                                  RunOrigin origin) {
        if (command == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 200 || origin == null) {
            throw new RunGroupException(RunGroupException.Code.RUN_GROUP_REQUEST_INVALID);
        }
        String requestSha256 = RunGroupRequestCodec.requestSha256(command);

        Optional<CreatedRunGroup> replayed =
                replay(command.brainId(), idempotencyKey, requestSha256);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        // Resolution and engine reads happen outside the transaction, so no pooled connection and
        // no advisory lock is held across Document Engine latency.
        RunGroupPreflight checked = preflight.preflight(command);
        if (!checked.acceptable()) {
            throw new RunGroupException(RunGroupException.Code.RUN_GROUP_BLOCKED,
                    allBlockers(checked));
        }

        return isolated.execute(status -> {
            // Under the lock, re-check: another submission of the same key may have committed
            // while this one was talking to the engine.
            Optional<CreatedRunGroup> raced =
                    replay(command.brainId(), idempotencyKey, requestSha256);
            if (raced.isPresent()) {
                return raced.get();
            }
            lockBrainBudget(command.brainId());
            // Re-priced under the lock: the committed total and everything reserved can both have
            // moved since the unlocked read above, and the budget check only means something if
            // it is the last thing to happen before the insert.
            RunGroupPreflight confirmed = preflight.preflight(command);
            if (!confirmed.acceptable()) {
                throw new RunGroupException(RunGroupException.Code.RUN_GROUP_BLOCKED,
                        allBlockers(confirmed));
            }
            return insert(command, idempotencyKey, confirmed, origin);
        });
    }

    // ================================================================ internals

    private CreatedRunGroup insert(RunGroupCommand command, String idempotencyKey,
                                   RunGroupPreflight checked, RunOrigin origin) {
        LabRunGroup group = new LabRunGroup();
        group.setBrainId(command.brainId());
        group.setMode(command.mode());
        group.setComparisonDimension(command.comparisonDimension());
        group.setIdempotencyKey(idempotencyKey);
        group.setRequestSha256(checked.requestSha256());
        group.setComparisonBasisSha256(checked.comparisonBasisSha256());
        group.setStatus(LabRunGroup.Status.QUEUED);
        LabRunGroup saved = groups.saveAndFlush(group);

        List<UUID> memberRunIds = new ArrayList<>(checked.members().size());
        for (MemberPreflight member : checked.members()) {
            LabRun run = new LabRun();
            run.setBrainId(command.brainId());
            run.setInstanceSlug(member.instanceSlug());
            // Internal identity. The caller's key lives on the group, so two releases of one
            // instance can coexist in a comparison without colliding on V34's run-key uniqueness.
            run.setIdempotencyKey("group:" + saved.getId() + ":" + member.memberIndex());
            run.setReleaseId(member.releaseId());
            run.setRegistrationId(member.registrationId());
            run.setCorpusSnapshotId(member.corpusSnapshotId());
            run.setRequestedProvider(member.provider());
            run.setRequestedModel(member.model());
            run.setPricingVersionId(member.pricingVersionId());
            run.setRunGroupId(saved.getId());
            run.setMemberIndex(member.memberIndex());
            // QUEUED, not PROCESSING: creating a group schedules work, it does not perform any.
            // The dispatcher claims members under its own concurrency limits.
            run.setStatus(LabRun.Status.QUEUED);
            LabRun savedRun = runs.saveAndFlush(run);
            memberRunIds.add(savedRun.getId());

            usage.saveAndFlush(new LabModelUsage(savedRun.getId(), command.brainId(),
                    member.pricingVersionId(), member.provider(), member.model(),
                    member.inputTokensMin(), member.inputTokensMax(),
                    member.outputTokensMin(), member.outputTokensMax(),
                    member.costUsdMin(), member.costUsdMax(),
                    EstimateQuality.valueOf(member.estimateQuality())));

            // The maximum, not the estimate: a reservation that held the midpoint would let a run
            // landing at its upper bound overshoot a budget that had already approved it.
            reservations.saveAndFlush(new LabSpendReservation(
                    savedRun.getId(), command.brainId(), member.costUsdMax()));
            // Counted here, once: a replay returns long before this insert runs.
            metrics.runCreated(command.mode(), member.provider());
            metrics.reservation(LabSpendReservation.Status.RESERVED);
        }
        if (origin instanceof RunOrigin.Connector connector) {
            connectorContexts.saveAndFlush(new LabConnectorRunGroupContext(
                    saved.getId(), connector.connectorClientId(), command.brainId(),
                    connector.tenantId(), connector.externalRequestId(),
                    connector.externalRequestSha256()));
        }
        return new CreatedRunGroup(saved.getId(), true, memberRunIds);
    }

    /**
     * The group this key already created, if the request matches.
     *
     * <p>Same key and same request is a retry and returns what exists. Same key and a different
     * request is a client bug that must not silently start different work, so it is refused.
     */
    private Optional<CreatedRunGroup> replay(UUID brainId, String key, String requestSha256) {
        return groups.findByBrainIdAndIdempotencyKey(brainId, key).map(existing -> {
            if (!existing.getRequestSha256().equals(requestSha256)) {
                throw new RunGroupException(RunGroupException.Code.IDEMPOTENCY_KEY_REUSED);
            }
            return new CreatedRunGroup(existing.getId(), false,
                    runs.findByRunGroupIdOrderByMemberIndexAsc(existing.getId()).stream()
                            .map(LabRun::getId).toList());
        });
    }

    /** Group-level blockers and every member's, in one list. */
    private static List<String> allBlockers(RunGroupPreflight checked) {
        List<String> all = new ArrayList<>(checked.blockingCodes());
        checked.members().forEach(member -> all.addAll(member.blockingCodes()));
        return all;
    }

    /**
     * Serializes budget decisions for one brain.
     *
     * <p>A transaction-scoped advisory lock keyed by the brain: it releases with the transaction
     * whatever happens, and submissions for different brains hash to different keys and never
     * wait on each other.
     */
    private void lockBrainBudget(UUID brainId) {
        byte[] hash = sha256(("run-group-budget:" + brainId).getBytes(StandardCharsets.UTF_8));
        int first = ByteBuffer.wrap(hash, 0, 4).getInt();
        int second = ByteBuffer.wrap(hash, 4, 4).getInt();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class, first, second);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
