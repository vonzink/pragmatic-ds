package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.analyze.ParsedInstanceAnalysisService.InstanceAnalysisCommand;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.service.InstanceExecutionService;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to one claimed member, and what must not happen to the others.
 *
 * <p>The dispatcher is where a queued row becomes a billed provider call, and where the four
 * concurrency limits are the only thing standing between a queue and a provider's rate limiter. It
 * is also the one place that settles what a member reserved. Two failure directions matter here
 * and they are opposites: a member that fails must not take its siblings down with it, and a
 * member that cannot even be prepared must not reach a provider at all.
 */
class RunGroupDispatcherTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RUN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SIBLING = UUID.fromString("22222222-2222-4222-8222-222222222223");
    private static final UUID GROUP = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RELEASE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID REGISTRATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SNAPSHOT = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String INSTANCE = "income";

    private LabRunRepository runs;
    private InstanceReleaseResolver releases;
    private ParsedDataResolver parsedInputs;
    private CorpusSnapshotService snapshots;
    private InstanceExecutionService execution;
    private RunGroupStatusService status;
    private JdbcTemplate jdbc;
    private RunGroupDispatcher dispatcher;

    // Everything the claim path reads or writes, held as fields and served by the mock's own
    // answer. No argument matchers are involved on this path at all — see setUp.
    private int processingEverywhere;
    private int processingPerBrain;
    private int processingPerProvider;
    private String saturatedInstance = "";
    private int processingForSaturatedInstance;
    private final List<Map<String, Object>> queue = new ArrayList<>();
    private final List<Object[]> updates = new ArrayList<>();
    private final Deque<Integer> claimResults = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        runs = mock(LabRunRepository.class);
        releases = mock(InstanceReleaseResolver.class);
        parsedInputs = mock(ParsedDataResolver.class);
        snapshots = mock(CorpusSnapshotService.class);
        execution = mock(InstanceExecutionService.class);
        status = mock(RunGroupStatusService.class);
        // Every JdbcTemplate call on the claim path is answered from the mock itself rather than
        // from a stub. The dispatcher passes its arguments as varargs — empty for the
        // deployment-wide count, two for the claiming update — and matching that position with
        // matchers proved unreliable here: the counts failed first, and once those were fixed the
        // two tests that actually claim a member failed on the same shape. Reading the invocation
        // removes the question rather than answering it, and it makes the negative assertions
        // real: "no row was touched" is now a recorded fact rather than a verification that could
        // pass by not matching.
        jdbc = mock(JdbcTemplate.class, invocation -> {
            String method = invocation.getMethod().getName();
            Object[] arguments = flatten(invocation.getArguments());
            if ("queryForObject".equals(method) && arguments.length >= 2
                    && Integer.class.equals(arguments[1])) {
                return countFor(String.valueOf(arguments[0]), arguments);
            }
            if ("queryForList".equals(method)) {
                return List.copyOf(queue);
            }
            if ("update".equals(method)) {
                updates.add(arguments);
                return claimResults.isEmpty() ? 1 : claimResults.poll();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        when(runs.findById(RUN)).thenReturn(Optional.of(run(LabRun.Status.PROCESSING)));
        // The real terminal path answers with the outcome it wrote; the mock mirrors that
        // contract so settle() sees what a preparation failure actually produces.
        when(execution.failUnprepared(any(), any(), anyString())).thenAnswer(invocation ->
                new InstanceExecutionService.ExecutionOutcome(invocation.getArgument(0),
                        "FAILED", invocation.getArgument(2)));
        when(releases.byId(any(), any())).thenReturn(new ResolvedInstanceRelease(
                null, null, new DecodedInstanceManifest.V2(manifest()), true));
        when(parsedInputs.resolveRegistered(any(), anyString(), any(), any()))
                .thenReturn(verifiedParse());
        when(snapshots.require(BRAIN, SNAPSHOT)).thenReturn(
                new CorpusSnapshotService.FrozenCorpusSnapshot(
                        SNAPSHOT, BRAIN, "a".repeat(64), List.of(), List.of()));

        dispatcher = new RunGroupDispatcher(properties(4, 2, 2, 1), runs, releases, parsedInputs,
                snapshots, execution, status, jdbc, mock(InstanceControlMetrics.class),
                transactions);
    }

    // ============================================================ settling one member

    @Test
    void aSuccessfulMemberConsumesWhatItHeldAndItsGroupIsRolledUp() {
        when(execution.execute(eq(RUN), any())).thenReturn(
                new InstanceExecutionService.ExecutionOutcome(RUN, "SUCCEEDED", null));

        dispatcher.executeClaimed(RUN);

        verify(status).consumeReservation(RUN, BRAIN);
        verify(status, never()).releaseReservation(any(), any());
        verify(status).rollUp(GROUP);
    }

    @Test
    void aFailedMemberGivesBackWhatItHeldRatherThanLeavingItAgainstTheBudget() {
        when(execution.execute(eq(RUN), any())).thenReturn(
                new InstanceExecutionService.ExecutionOutcome(RUN, "FAILED", "INSTANCE_RUN_FAILED"));

        // Money reserved for work that produced nothing should not sit against a brain's budget
        // until a sweeper notices.
        dispatcher.executeClaimed(RUN);

        verify(status).releaseReservation(RUN, BRAIN);
        verify(status, never()).consumeReservation(any(), any());
        verify(status).rollUp(GROUP);
    }

    @Test
    void aMemberThatIsNoLongerProcessingIsLeftAloneRatherThanRunASecondTime() {
        // A lease that expired and was recovered, or a row a sweeper already settled. Executing it
        // again would be a second billed call for one queued member.
        for (LabRun.Status settled : Set.of(LabRun.Status.QUEUED, LabRun.Status.SUCCEEDED,
                LabRun.Status.FAILED, LabRun.Status.CANCELLED)) {
            when(runs.findById(RUN)).thenReturn(Optional.of(run(settled)));
            dispatcher.executeClaimed(RUN);
        }
        when(runs.findById(RUN)).thenReturn(Optional.empty());
        dispatcher.executeClaimed(RUN);

        verify(execution, never()).execute(any(), any());
        verify(status, never()).rollUp(any());
        verify(status, never()).consumeReservation(any(), any());
        verify(status, never()).releaseReservation(any(), any());
    }

    // ============================================================ never reaching a provider

    @Test
    void aParseThatHasMovedFailsTheMemberWithThatLayersOwnCodeAndNeverReachesAProvider() {
        when(parsedInputs.resolveRegistered(any(), anyString(), any(), any()))
                .thenThrow(new ParsedDataResolver.ParsedDataException(
                        ParsedDataResolver.ParsedDataException.Code.PARSE_REVISION_NOT_FOUND));

        dispatcher.executeClaimed(RUN);

        // Re-verified rather than trusted: a member queued an hour ago must not run against
        // inputs that have moved since, and finding out costs nothing because it happens first.
        verify(execution, never()).execute(any(), any());
        assertEquals("PARSE_REVISION_NOT_FOUND", recordedFailureCode());
        verify(status).releaseReservation(RUN, BRAIN);
        verify(status).rollUp(GROUP);
    }

    @Test
    void aSnapshotThatHasGoneFailsTheMemberWithTheSnapshotsOwnCode() {
        when(snapshots.require(BRAIN, SNAPSHOT)).thenThrow(
                new CorpusSnapshotService.SnapshotException(
                        CorpusSnapshotService.SnapshotException.Code.CORPUS_SNAPSHOT_NOT_FOUND));

        dispatcher.executeClaimed(RUN);

        verify(execution, never()).execute(any(), any());
        assertEquals("CORPUS_SNAPSHOT_NOT_FOUND", recordedFailureCode());
    }

    @Test
    void aReleaseThatHasMovedOutOfScopeFailsTheMemberWithTheResolversOwnCode() {
        when(releases.byId(any(), any())).thenThrow(
                new InstanceReleaseResolver.ReleaseResolutionException(
                        InstanceReleaseResolver.ReleaseResolutionException.Code
                                .RELEASE_SCOPE_MISMATCH));

        dispatcher.executeClaimed(RUN);

        verify(execution, never()).execute(any(), any());
        assertEquals("RELEASE_SCOPE_MISMATCH", recordedFailureCode());
    }

    @Test
    void anUnmodelledFailureRecordsAGenericCodeRatherThanWhateverItsMessageSaid() {
        when(releases.byId(any(), any()))
                .thenThrow(new IllegalStateException("connect to db-primary:5432 refused"));

        dispatcher.executeClaimed(RUN);

        // Each layer publishes its own value-free vocabulary and the dispatcher only selects
        // between them. A message is not a code: this one names a host and a port, and a
        // resolution failure's message routinely carries a request URI or a provider's body.
        assertEquals("INSTANCE_RUN_FAILED", recordedFailureCode());
        verify(execution, never()).execute(any(), any());
    }

    // ============================================================ isolation

    @Test
    void oneMembersFailureIsItsOwnAndNeverCancelsItsSiblings() {
        when(execution.execute(eq(RUN), any())).thenReturn(
                new InstanceExecutionService.ExecutionOutcome(RUN, "FAILED", "INSTANCE_RUN_FAILED"));

        dispatcher.executeClaimed(RUN);

        // A comparison where one model failed still tells you about the others, so the group is
        // rolled up — to PARTIAL — and nothing else in it is touched. Cancelling siblings here
        // would throw away results that were already paid for.
        verify(status).rollUp(GROUP);
        verify(status, never()).cancel(any(), any());
        verify(execution, never()).execute(eq(SIBLING), any());
    }

    @Test
    void aFailureThatEscapesExecutionStillSettlesTheMemberAndRollsTheGroupUp() {
        when(execution.execute(eq(RUN), any()))
                .thenThrow(new IllegalStateException("provider socket closed"));

        // A resolution failure between the claim and the run is a terminal state for that member,
        // not a dispatcher that crashed and leaves a PROCESSING row holding a reservation forever.
        dispatcher.executeClaimed(RUN);

        assertEquals("INSTANCE_RUN_FAILED", recordedFailureCode());
        verify(status).releaseReservation(RUN, BRAIN);
        verify(status).rollUp(GROUP);
    }

    // ============================================================ the rebuilt command

    @Test
    void theCommandIsRebuiltFromTheMembersOwnColumnsAndReVerifiedRatherThanTrusted() {
        when(execution.execute(eq(RUN), any())).thenReturn(
                new InstanceExecutionService.ExecutionOutcome(RUN, "SUCCEEDED", null));

        dispatcher.executeClaimed(RUN);

        ArgumentCaptor<InstanceAnalysisCommand> command =
                ArgumentCaptor.forClass(InstanceAnalysisCommand.class);
        verify(execution).execute(eq(RUN), command.capture());
        assertEquals(BRAIN, command.getValue().brainId());
        assertEquals(INSTANCE, command.getValue().instanceSlug());
        assertEquals(SNAPSHOT, command.getValue().corpusSnapshot().id());
        assertEquals("run-" + RUN, command.getValue().correlationId());

        // The parse is checked against the engine again and the snapshot re-read; everything else
        // is exactly what the member pinned at submission.
        verify(parsedInputs).resolveRegistered(eq(BRAIN), eq(INSTANCE), eq(REGISTRATION), any());
        verify(releases).byId(new InstanceKey(BRAIN, INSTANCE), RELEASE);
        verify(snapshots).require(BRAIN, SNAPSHOT);
    }

    // ============================================================ saturation

    @Test
    void aSaturatedDeploymentClaimsNothingAndNeverEvenReadsTheQueue() {
        processing(4, 0, 0, INSTANCE, 0);
        queued(candidate(RUN, INSTANCE, "anthropic"));

        // The proof that concurrency saturation costs nothing: the queue is not empty and no
        // candidate is read from it anyway, so no member is claimed and no provider is called.
        // The limit is not a retry budget — it is the thing standing between a queue and a
        // provider's rate limiter.
        assertTrue(dispatcher.claimNext().isEmpty());
        // queryForList(String) takes no varargs, so this verification matches on its own terms
        // and says the stronger thing the name promises: the limit is checked before the queue
        // is read, not after every candidate has been considered and refused.
        verify(jdbc, never()).queryForList(anyString());
        assertTrue(updates.isEmpty(), "a saturated deployment must not touch a row");
    }

    @Test
    void aMemberBreachingAnyOneScopeIsSkippedRatherThanStallingTheWholeQueue() {
        // Deployment has room, but the first candidate's instance is already at its limit of one.
        // Skipping it rather than stopping matters: the next candidate may well be eligible, and
        // a comparison group would otherwise monopolise the queue behind its own limit.
        processing(1, 0, 0, INSTANCE, 1);
        queued(candidate(RUN, INSTANCE, "anthropic"),
                candidate(SIBLING, "assets", "anthropic"));

        assertEquals(Optional.of(SIBLING), dispatcher.claimNext());
        assertEquals(1, updates.size(), "the skipped candidate must not be claimed");
    }

    @Test
    void losingTheRowToAnotherNodeMovesOnRatherThanClaimingItTwice() {
        processing(1, 0, 0, INSTANCE, 0);
        queued(candidate(RUN, INSTANCE, "anthropic"),
                candidate(SIBLING, "assets", "anthropic"));
        // The update is itself the claim: guarded on QUEUED, so a row another node already won
        // affects zero rows and the loop moves on rather than claiming it twice.
        claimAttempts(0, 1);

        assertEquals(Optional.of(SIBLING), dispatcher.claimNext());
        assertEquals(2, updates.size(), "both rows were attempted, one of them lost");
    }

    @Test
    void anEmptyQueueClaimsNothingWithoutTouchingAnyRow() {
        processing(0, 0, 0, INSTANCE, 0);

        assertFalse(dispatcher.claimNext().isPresent());
        assertTrue(updates.isEmpty());
    }

    // ============================================================ fixtures

    /** The failure code the dispatcher actually wrote to the member's row. */
    private String recordedFailureCode() {
        // The dispatcher no longer writes the row itself: a preparation failure goes through the
        // execution service's terminal path, which also settles the audit and usage rows.
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(execution).failUnprepared(eq(RUN), eq(BRAIN), code.capture());
        return code.getValue();
    }

    /**
     * Says what each PROCESSING count should report.
     *
     * <p>{@code saturatedInstance} names the one instance slug already at its limit, so a test can
     * saturate a single scope for a single candidate rather than every scope at once — which is
     * the only way to tell skipping a candidate apart from stopping the queue.
     */
    private void processing(int everywhere, int perBrain, int perProvider,
                            String instanceSlug, int perSaturatedInstance) {
        this.processingEverywhere = everywhere;
        this.processingPerBrain = perBrain;
        this.processingPerProvider = perProvider;
        this.saturatedInstance = instanceSlug;
        this.processingForSaturatedInstance = perSaturatedInstance;
    }

    /** Which scope this count query is asking about, decided by the query itself. */
    private Object countFor(String sql, Object[] arguments) {
        if (sql.contains("instance_slug")) {
            return mentions(arguments, saturatedInstance) ? processingForSaturatedInstance : 0;
        }
        if (sql.contains("requested_provider")) {
            return processingPerProvider;
        }
        return sql.contains("brain_id") ? processingPerBrain : processingEverywhere;
    }

    /** Whether one value was bound to this query. */
    private static boolean mentions(Object[] arguments, String value) {
        for (Object argument : arguments) {
            if (value.equals(argument)) {
                return true;
            }
        }
        return false;
    }

    /** Mockito may present varargs expanded or as one array; this reads the same either way. */
    private static Object[] flatten(Object[] arguments) {
        List<Object> flat = new ArrayList<>();
        for (Object argument : arguments) {
            if (argument instanceof Object[] nested) {
                flat.addAll(java.util.Arrays.asList(nested));
            } else {
                flat.add(argument);
            }
        }
        return flat.toArray();
    }

    /** The queued members the dispatcher will see, oldest first. */
    @SafeVarargs
    private void queued(Map<String, Object>... candidates) {
        queue.clear();
        queue.addAll(java.util.Arrays.asList(candidates));
    }

    /** What each claiming update returns, in order; anything beyond the list claims. */
    private void claimAttempts(int... results) {
        claimResults.clear();
        for (int result : results) {
            claimResults.add(result);
        }
    }

    /** A pinned parse with only the fields a dispatched command carries through. */
    private static ParsedDataResolver.VerifiedParsedInput verifiedParse() {
        return new ParsedDataResolver.VerifiedParsedInput(REGISTRATION, null, 1, null, 1,
                "1.0.0", "DOCENGINE-C14N-1", "b".repeat(64), 4096L, "c".repeat(64),
                List.of(), null, null, null);
    }

    private static Map<String, Object> candidate(UUID id, String slug, String provider) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("brain_id", BRAIN);
        row.put("instance_slug", slug);
        row.put("requested_provider", provider);
        return row;
    }

    private static LabRun run(LabRun.Status runStatus) {
        LabRun run = new LabRun();
        run.setId(RUN);
        run.setBrainId(BRAIN);
        run.setInstanceSlug(INSTANCE);
        run.setStatus(runStatus);
        run.setRunGroupId(GROUP);
        run.setMemberIndex(0);
        run.setReleaseId(RELEASE);
        run.setRegistrationId(REGISTRATION);
        run.setCorpusSnapshotId(SNAPSHOT);
        run.setRequestedProvider("anthropic");
        run.setRequestedModel("claude-opus-5");
        return run;
    }

    private static InstanceExecutionProperties properties(
            int deployment, int brain, int provider, int instance) {
        return new InstanceExecutionProperties(true, deployment, brain, provider, instance,
                Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    private static InstanceReleaseManifest manifest() {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of()),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query",
                        new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.9500")));
    }
}
