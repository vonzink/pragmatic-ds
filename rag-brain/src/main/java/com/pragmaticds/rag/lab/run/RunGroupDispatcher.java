package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisCommand;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.service.InstanceExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Claims one queued member at a time, within four concurrency limits, and runs it.
 *
 * <p><b>Claiming and executing are separate, and only claiming touches the database.</b> The claim
 * runs in one short transaction under a cluster-wide advisory lock: count what is PROCESSING at
 * each scope, take the oldest eligible member with {@code FOR UPDATE SKIP LOCKED}, give it a
 * bounded lease, commit. Execution then happens with no transaction and no connection held, which
 * matters because a run spends most of its life waiting on Document Engine, retrieval, and a
 * provider. Holding a connection across that would make database capacity a function of provider
 * latency.
 *
 * <p><b>The advisory lock is what makes the counts mean anything.</b> Without it two nodes could
 * both count three PROCESSING runs against a limit of four and both claim, producing five. The
 * lock is held only for the counting and the update, never across the work.
 *
 * <p><b>A member's failure is its own.</b> Execution already moves one run to exactly one terminal
 * state; the dispatcher only rolls the group up afterwards. One member erroring never cancels its
 * siblings, because a comparison where one model failed still tells you about the others.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances.execution", name = "enabled",
        havingValue = "true")
public class RunGroupDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunGroupDispatcher.class);

    /** Namespaces the advisory lock so it cannot collide with the budget lock's key space. */
    private static final int LOCK_NAMESPACE = 0x1A5D;

    /** One fixed key: every node claiming members serializes on the same lock. */
    private static final int DISPATCH_LOCK_KEY = 1;

    /**
     * Retrieval breadth for a dispatched member.
     *
     * <p>Matches the Lab prototype's default. A release pins its retrieval ceiling in tokens
     * rather than in chunks, so there is no per-release value to read here, and inventing one
     * from the collection count would make breadth depend on how a corpus happens to be split.
     */
    private static final int RETRIEVAL_TOP_K = 8;

    private final InstanceExecutionProperties properties;
    private final LabRunRepository runs;
    private final InstanceReleaseResolver releases;
    private final ParsedDataResolver parsedInputs;
    private final CorpusSnapshotService snapshots;
    private final InstanceExecutionService execution;
    private final RunGroupStatusService status;
    private final JdbcTemplate jdbc;
    private final InstanceControlMetrics metrics;
    private final TransactionTemplate isolated;

    public RunGroupDispatcher(InstanceExecutionProperties properties,
                              LabRunRepository runs,
                              InstanceReleaseResolver releases,
                              ParsedDataResolver parsedInputs,
                              CorpusSnapshotService snapshots,
                              InstanceExecutionService execution,
                              RunGroupStatusService status,
                              JdbcTemplate jdbc,
                              InstanceControlMetrics metrics,
                              PlatformTransactionManager transactionManager) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        // Validated here rather than at startup: a malformed limit must fail dispatch, not
        // prevent the application from booting for deployments that never enable execution.
        properties.validate();
        this.runs = Objects.requireNonNull(runs, "runs");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.parsedInputs = Objects.requireNonNull(parsedInputs, "parsedInputs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.status = Objects.requireNonNull(status, "status");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.isolated = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Claims the oldest eligible queued member, or nothing.
     *
     * <p>Oldest-first across the whole queue rather than per group, so a large comparison
     * submitted first cannot be indefinitely overtaken by a stream of single-member groups.
     */
    public Optional<UUID> claimNext() {
        String[] outcome = {"EMPTY"};
        Optional<UUID> claimed = Optional.ofNullable(isolated.execute(transaction -> {
            lockDispatch();
            if (processingEverywhere() >= properties.maxConcurrentRuns()) {
                outcome[0] = "SATURATED";
                return null;
            }
            for (Map<String, Object> candidate : eligible()) {
                UUID runId = (UUID) candidate.get("id");
                UUID brainId = (UUID) candidate.get("brain_id");
                String slug = (String) candidate.get("instance_slug");
                String provider = (String) candidate.get("requested_provider");

                // Every scope, every time. A member that would breach any one of them is skipped
                // rather than delayed as a whole queue: the next candidate may well be eligible.
                if (processingForBrain(brainId) >= properties.maxConcurrentRunsPerBrain()
                        || processingForProvider(provider)
                            >= properties.maxConcurrentRunsPerProvider()
                        || processingForInstance(brainId, slug)
                            >= properties.maxConcurrentRunsPerInstance()) {
                    continue;
                }
                if (markProcessing(runId) == 1) {
                    outcome[0] = "CLAIMED";
                    return runId;
                }
            }
            return null;
        }));
        metrics.dispatchClaim(outcome[0]);
        return claimed;
    }

    /**
     * Runs a claimed member to a terminal state, with no transaction held.
     *
     * <p>Every failure between the claim and the run is a terminal state for that member rather
     * than an exception thrown at a scheduler: a member whose parse has moved or whose snapshot
     * has gone is a run that failed, not a dispatcher that crashed.
     */
    public void executeClaimed(UUID runId) {
        LabRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() != LabRun.Status.PROCESSING) {
            return;
        }
        InstanceExecutionService.ExecutionOutcome outcome;
        try {
            outcome = execution.execute(runId, command(run));
        } catch (RuntimeException unresolvable) {
            // Class name only: a resolution failure's message can quote a request URI.
            log.warn("Instance run {} could not be prepared ({})",
                    runId, unresolvable.getClass().getSimpleName());
            // Terminal semantics identical to an execution failure — audit row, value-free
            // code, usage settled UNAVAILABLE — so a member that dies before its command
            // exists cannot hold a PENDING usage row forever.
            outcome = execution.failUnprepared(runId, run.getBrainId(), safeCode(unresolvable));
        }
        settle(run, outcome);
        status.rollUp(run.getRunGroupId());
    }

    // ================================================================ internals

    /**
     * Rebuilds the pinned command from the member's own columns.
     *
     * <p>Re-verified rather than trusted: the parse is checked against the engine again and the
     * snapshot re-read, so a member queued an hour ago cannot run against inputs that have moved
     * since. Everything else — release, model, corpus — is exactly what was pinned at submission.
     */
    private InstanceAnalysisCommand command(LabRun run) {
        InstanceKey key = new InstanceKey(run.getBrainId(), run.getInstanceSlug());
        var release = releases.byId(key, run.getReleaseId());
        if (!(release.manifest() instanceof DecodedInstanceManifest.V2 v2)) {
            throw new IllegalStateException("INSTANCE_RELEASE_NOT_PINNABLE");
        }
        ParsedDataResolver.VerifiedParsedInput parsed = parsedInputs.resolveRegistered(
                run.getBrainId(), run.getInstanceSlug(), run.getRegistrationId(),
                v2.manifest().parsedData());
        CorpusSnapshotService.FrozenCorpusSnapshot snapshot =
                snapshots.require(run.getBrainId(), run.getCorpusSnapshotId());

        return new InstanceAnalysisCommand(run.getBrainId(), run.getInstanceSlug(),
                UUID.randomUUID(), release, parsed, snapshot, RETRIEVAL_TOP_K,
                false, "run-" + run.getId());
    }

    /**
     * Settles the member's reservation once it is terminal.
     *
     * <p>A successful run consumed what it held; a failed one gives it back, because money
     * reserved for work that produced nothing should not sit against a brain's budget until a
     * sweeper notices.
     */
    private void settle(LabRun run, InstanceExecutionService.ExecutionOutcome outcome) {
        if ("SUCCEEDED".equals(outcome.status())) {
            status.consumeReservation(run.getId(), run.getBrainId());
        } else {
            status.releaseReservation(run.getId(), run.getBrainId());
        }
    }

    /** Queued members, oldest first. Skips rows another node is already looking at. */
    private List<Map<String, Object>> eligible() {
        return jdbc.queryForList(
                "SELECT id, brain_id, instance_slug, requested_provider FROM lab_run "
                        + "WHERE status = 'QUEUED' ORDER BY created_at ASC "
                        + "LIMIT 50 FOR UPDATE SKIP LOCKED");
    }

    // Four explicit queries rather than one with nullable predicates: a null-cast condition is
    // easy to get subtly wrong and impossible to read, and these are each one line.

    private int processingEverywhere() {
        return count("SELECT count(*) FROM lab_run WHERE status = 'PROCESSING'");
    }

    private int processingForBrain(UUID brainId) {
        return count("SELECT count(*) FROM lab_run WHERE status = 'PROCESSING' AND brain_id = ?",
                brainId);
    }

    private int processingForProvider(String provider) {
        // A member with no recorded provider cannot breach a per-provider limit it is not in.
        return provider == null ? 0 : count(
                "SELECT count(*) FROM lab_run WHERE status = 'PROCESSING' "
                        + "AND requested_provider = ?", provider);
    }

    private int processingForInstance(UUID brainId, String instanceSlug) {
        return count("SELECT count(*) FROM lab_run WHERE status = 'PROCESSING' "
                + "AND brain_id = ? AND instance_slug = ?", brainId, instanceSlug);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    /**
     * Moves one member to PROCESSING with a bounded lease.
     *
     * <p>Guarded on {@code status = 'QUEUED'} so the update is itself the claim: if another node
     * won the row between the select and here, this affects zero rows and the loop moves on.
     */
    private int markProcessing(UUID runId) {
        return jdbc.update(
                "UPDATE lab_run SET status = 'PROCESSING', lease_expires_at = ? "
                        + "WHERE id = ? AND status = 'QUEUED'",
                OffsetDateTime.now().plus(properties.leaseDuration()), runId);
    }

    /**
     * Serializes claiming across every node.
     *
     * <p>Transaction-scoped, so it releases on commit or rollback whatever happens, and held only
     * for the counting and the update — never across a provider call.
     */
    private void lockDispatch() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class,
                LOCK_NAMESPACE, DISPATCH_LOCK_KEY);
    }

    /** Each layer publishes its own value-free vocabulary; this only selects between them. */
    private static String safeCode(RuntimeException failure) {
        return switch (failure) {
            case ParsedDataResolver.ParsedDataException e -> e.code().name();
            case CorpusSnapshotService.SnapshotException e -> e.code().name();
            case InstanceReleaseResolver.ReleaseResolutionException e -> e.code().name();
            default -> "INSTANCE_RUN_FAILED";
        };
    }
}
