package com.pragmaticds.rag.lab.connect;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.lab.parsed.RegistrationSubjectScopeService;
import com.pragmaticds.rag.lab.parsed.ParsedInputSelection;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand;
import com.pragmaticds.rag.lab.run.RunGroupService;
import com.pragmaticds.rag.lab.run.RunGroupService.RunGroupException;
import com.pragmaticds.rag.lab.run.RunOrigin;
import com.pragmaticds.rag.lab.run.domain.LabConnectorRunGroupContext;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.repository.LabConnectorRunGroupContextRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The composition that lets Document Manager run only what production answers with.
 *
 * <p>Most of these tests are about resolution happening server-side because the command has no
 * channel for anything else: no release field, no model field, no prompt, no corpus. The one test
 * that looks structural — the submitted member's release id equals what the live pointer resolved
 * to — is the load-bearing one, because it is the difference between "exercises production" and
 * "runs whatever the caller smuggled in".
 *
 * <p>The replay tests are about money and about honesty. A retry with the same key and the same
 * visible request must return the original group without touching the Document Engine or the live
 * pointer — including after a promotion changed what live means, which is precisely when the
 * internal request hash would have refused it. A new key resolves the new live release, so
 * deployment changes are never hidden behind a replay.
 */
class DocumentManagerRunServiceTest {

    private static final UUID BRAIN = TestBrains.DEFAULT_ID;
    private static final UUID CONNECTOR =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID PACKAGE =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SOURCE_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID SOURCE_B =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
    private static final UUID LIVE_RELEASE =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REGISTRATION =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID SNAPSHOT =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID COLLECTION =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID GROUP =
            UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String KEY = "dm-key-1";

    private InstanceRegistryService registry;
    private InstanceReleaseResolver releases;
    private ParsedDataResolver parsedInputs;
    private CorpusSnapshotService snapshots;
    private RunGroupService runGroups;
    private LabRunGroupRepository groups;
    private LabRunRepository runs;
    private LabConnectorRunGroupContextRepository contexts;
    private RegistrationLoanFactsService loanFacts;
    private DocumentManagerRunService service;

    private LabInstance instance;

    @BeforeEach
    void setUp() {
        registry = mock(InstanceRegistryService.class);
        releases = mock(InstanceReleaseResolver.class);
        parsedInputs = mock(ParsedDataResolver.class);
        snapshots = mock(CorpusSnapshotService.class);
        runGroups = mock(RunGroupService.class);
        groups = mock(LabRunGroupRepository.class);
        runs = mock(LabRunRepository.class);
        contexts = mock(LabConnectorRunGroupContextRepository.class);
        loanFacts = mock(RegistrationLoanFactsService.class);
        service = new DocumentManagerRunService(registry, releases, parsedInputs, snapshots,
                runGroups, groups, runs, contexts, loanFacts,
                mock(RegistrationSubjectScopeService.class));

        instance = new LabInstance(BRAIN, "income", "Income", "Analyze income.");
        when(groups.findByBrainIdAndIdempotencyKey(any(), anyString()))
                .thenReturn(Optional.empty());
        when(registry.require(new InstanceKey(BRAIN, "income"))).thenReturn(instance);
        when(releases.live(new InstanceKey(BRAIN, "income"))).thenReturn(resolvedLive());
        when(parsedInputs.verifyExisting(any(), any())).thenReturn(verifiedSelection(true));
        when(parsedInputs.pin(any())).thenReturn(pinnedInput());
        when(snapshots.freeze(any())).thenReturn(frozen());
        when(runGroups.create(any(), anyString(), any())).thenReturn(
                new RunGroupService.CreatedRunGroup(GROUP, true, List.of(UUID.randomUUID())));
    }

    private static DocumentManagerRunCommand command() {
        return new DocumentManagerRunCommand(CONNECTOR, BRAIN, "income", "tenant-a",
                "req-77", PACKAGE, 4, List.of(SOURCE_B, SOURCE_A));
    }

    // ============================================================ resolution

    @Test
    void pinsTheMemberToWhatTheLivePointerResolvedAndNothingTheCallerSent() {
        service.start(command(), KEY);

        ArgumentCaptor<RunGroupCommand> submitted = ArgumentCaptor.forClass(RunGroupCommand.class);
        verify(runGroups).create(submitted.capture(), anyString(), any());

        RunGroupCommand group = submitted.getValue();
        assertEquals(LabRunGroup.Mode.INDEPENDENT, group.mode());
        assertEquals(1, group.members().size());
        RunGroupCommand.RunMemberCommand member = group.members().get(0);
        // Server-resolved, all four. The command has no field that could have supplied any of
        // them, so this is the whole channel — and it points at live.
        assertEquals(LIVE_RELEASE, member.releaseId());
        assertEquals(REGISTRATION, member.registrationId());
        assertEquals(SNAPSHOT, member.corpusSnapshotId());
        assertEquals("income", member.instanceSlug());
    }

    @Test
    void verifiesExactlyThePackageRevisionAndSourcesTheCallerNamed() {
        service.start(command(), KEY);

        ArgumentCaptor<ParsedDataResolver.ExistingParseRequest> request =
                ArgumentCaptor.forClass(ParsedDataResolver.ExistingParseRequest.class);
        verify(parsedInputs).verifyExisting(request.capture(), any());
        assertEquals(PACKAGE, request.getValue().packageId());
        assertEquals(4, request.getValue().revision());
        // Exactly the named sources — never widened to the whole package, never narrowed.
        assertEquals(List.of(SOURCE_B, SOURCE_A), request.getValue().selectedSourceIds());
    }

    @Test
    void snapshotsExactlyTheCollectionsAndVersionsTheLiveReleasePins() {
        service.start(command(), KEY);

        ArgumentCaptor<CorpusSnapshotService.SnapshotRequest> request =
                ArgumentCaptor.forClass(CorpusSnapshotService.SnapshotRequest.class);
        verify(snapshots).freeze(request.capture());
        assertEquals(BRAIN, request.getValue().brainId());
        assertEquals(List.of(new CorpusSnapshotService.CollectionVersionRef(COLLECTION, 7L)),
                request.getValue().collections());
    }

    @Test
    void carriesTheConnectorOriginSoOwnershipCommitsWithTheGroup() {
        service.start(command(), KEY);

        ArgumentCaptor<RunOrigin> origin = ArgumentCaptor.forClass(RunOrigin.class);
        verify(runGroups).create(any(), anyString(), origin.capture());
        RunOrigin.Connector connector = (RunOrigin.Connector) origin.getValue();
        assertEquals(CONNECTOR, connector.connectorClientId());
        assertEquals("tenant-a", connector.tenantId());
        assertEquals("req-77", connector.externalRequestId());
        assertEquals(DocumentManagerRunService.externalRequestSha256(command()),
                connector.externalRequestSha256());
    }

    @Test
    void neverTouchesAnyUploadPath() {
        service.start(command(), KEY);

        // There is no connector upload and no raw-document fallback. Not "unused" — never called.
        verify(parsedInputs, never()).acceptUpload(any());
        verify(parsedInputs, never()).claimUpload(any(), any());
    }

    // ============================================================ refusals

    @Test
    void refusesADisabledInstanceBeforeAnyEngineRead() {
        instance.setState(LabInstance.State.DISABLED);

        assertThrows(InstanceRegistryService.InstanceException.class,
                () -> service.start(command(), KEY));

        verifyNoInteractions(parsedInputs);
        verifyNoInteractions(snapshots);
        verifyNoInteractions(runGroups);
    }

    @Test
    void refusesWhenNothingIsLive() {
        when(releases.live(any())).thenThrow(new InstanceReleaseResolver.ReleaseResolutionException(
                InstanceReleaseResolver.ReleaseResolutionException.Code.LIVE_RELEASE_NOT_FOUND));

        assertThrows(InstanceReleaseResolver.ReleaseResolutionException.class,
                () -> service.start(command(), KEY));
        verifyNoInteractions(parsedInputs);
    }

    @Test
    void refusesALegacyReleaseThisBuildCannotPin() {
        when(releases.live(any())).thenReturn(new ResolvedInstanceRelease(
                instance, liveRelease(), new DecodedInstanceManifest.V1Income(null), true));

        assertThrows(InstanceReleaseResolver.ReleaseResolutionException.class,
                () -> service.start(command(), KEY));
        verifyNoInteractions(parsedInputs);
    }

    @Test
    void refusesAnIncompatibleParseBeforeAnythingDurableExists() {
        when(parsedInputs.verifyExisting(any(), any())).thenReturn(verifiedSelection(false));

        ParsedDataResolver.ParsedDataException refused = assertThrows(
                ParsedDataResolver.ParsedDataException.class,
                () -> service.start(command(), KEY));

        assertEquals(ParsedDataResolver.ParsedDataException.Code.PARSE_INCOMPATIBLE,
                refused.code());
        // Compatibility before snapshot, and before the registration pin: an incompatible parse
        // creates nothing — no snapshot nobody will use, no registration row, no reservation.
        verify(parsedInputs, never()).pin(any());
        verifyNoInteractions(snapshots);
        verifyNoInteractions(runGroups);
    }

    @Test
    void refusesAnUnusableRequestShape() {
        assertThrows(RunGroupException.class, () -> service.start(null, KEY));
        assertThrows(RunGroupException.class, () -> service.start(command(), " "));
        assertThrows(RunGroupException.class, () -> service.start(
                new DocumentManagerRunCommand(CONNECTOR, BRAIN, "income", "  ", null,
                        PACKAGE, 4, List.of()), KEY));
        assertThrows(RunGroupException.class, () -> service.start(
                new DocumentManagerRunCommand(CONNECTOR, BRAIN, "income", "tenant-a", null,
                        PACKAGE, 0, List.of()), KEY));
        verifyNoInteractions(registry);
    }

    // ============================================================ replay

    @Test
    void replaysTheSameVisibleRequestWithoutTouchingTheEngineOrTheLivePointer() {
        UUID memberRun = UUID.randomUUID();
        stubExistingGroup(contextFor(command()));
        when(runs.findByRunGroupIdOrderByMemberIndexAsc(GROUP))
                .thenReturn(List.of(runRow(memberRun)));

        RunGroupService.CreatedRunGroup replayed = service.start(command(), KEY);

        assertEquals(GROUP, replayed.groupId());
        assertFalse(replayed.created());
        assertEquals(List.of(memberRun), replayed.memberRunIds());
        // This is the promotion-safety property: live may have moved since the original
        // submission, and a retry must not care — it never asks.
        verifyNoInteractions(releases);
        verifyNoInteractions(parsedInputs);
        verifyNoInteractions(snapshots);
        verify(runGroups, never()).create(any(), anyString(), any());
    }

    @Test
    void refusesTheSameKeyWithADifferentVisibleRequest() {
        stubExistingGroup(contextFor(command()));

        DocumentManagerRunCommand different = new DocumentManagerRunCommand(
                CONNECTOR, BRAIN, "income", "tenant-a", "req-77", PACKAGE, 5,
                List.of(SOURCE_B, SOURCE_A));
        RunGroupException refused = assertThrows(RunGroupException.class,
                () -> service.start(different, KEY));

        assertEquals(RunGroupException.Code.IDEMPOTENCY_KEY_REUSED, refused.code());
    }

    @Test
    void refusesAnotherConnectorOrTenantReplayingSomeoneElsesKey() {
        stubExistingGroup(new LabConnectorRunGroupContext(GROUP, UUID.randomUUID(), BRAIN,
                "tenant-a", "req-77", DocumentManagerRunService.externalRequestSha256(command())));
        assertEquals(RunGroupException.Code.IDEMPOTENCY_KEY_REUSED,
                assertThrows(RunGroupException.class,
                        () -> service.start(command(), KEY)).code());

        stubExistingGroup(new LabConnectorRunGroupContext(GROUP, CONNECTOR, BRAIN,
                "tenant-b", "req-77", DocumentManagerRunService.externalRequestSha256(command())));
        assertEquals(RunGroupException.Code.IDEMPOTENCY_KEY_REUSED,
                assertThrows(RunGroupException.class,
                        () -> service.start(command(), KEY)).code());
    }

    @Test
    void refusesAKeyThatBelongsToAnAdminSubmission() {
        // The group exists but no context row does: an administrator created it. The connector
        // gets the same single code as every other collision — nothing confirms whose key it is.
        stubExistingGroup(null);

        assertEquals(RunGroupException.Code.IDEMPOTENCY_KEY_REUSED,
                assertThrows(RunGroupException.class,
                        () -> service.start(command(), KEY)).code());
    }

    // ============================================================ the visible-request digest

    @Test
    void theDigestIgnoresSourceOrderAndRespectsTheCorrelationId() {
        DocumentManagerRunCommand ordered = new DocumentManagerRunCommand(CONNECTOR, BRAIN,
                "income", "tenant-a", "req-77", PACKAGE, 4, List.of(SOURCE_A, SOURCE_B));
        DocumentManagerRunCommand reversed = new DocumentManagerRunCommand(CONNECTOR, BRAIN,
                "income", "tenant-a", "req-77", PACKAGE, 4, List.of(SOURCE_B, SOURCE_A));
        DocumentManagerRunCommand otherCorrelation = new DocumentManagerRunCommand(CONNECTOR,
                BRAIN, "income", "tenant-a", "req-78", PACKAGE, 4, List.of(SOURCE_A, SOURCE_B));

        // The same selection expressed in two orders is the same request; a different correlation
        // id is a different request, because it is the caller's own dedup handle.
        assertEquals(DocumentManagerRunService.externalRequestSha256(ordered),
                DocumentManagerRunService.externalRequestSha256(reversed));
        assertNotEquals(DocumentManagerRunService.externalRequestSha256(ordered),
                DocumentManagerRunService.externalRequestSha256(otherCorrelation));
    }

    // ============================================================ fixtures

    private void stubExistingGroup(LabConnectorRunGroupContext context) {
        LabRunGroup existing = new LabRunGroup();
        ReflectionTestUtils.setField(existing, "id", GROUP);
        existing.setBrainId(BRAIN);
        existing.setIdempotencyKey(KEY);
        existing.setRequestSha256("f".repeat(64));
        when(groups.findByBrainIdAndIdempotencyKey(BRAIN, KEY))
                .thenReturn(Optional.of(existing));
        when(contexts.findById(GROUP)).thenReturn(Optional.ofNullable(context));
    }

    private static LabConnectorRunGroupContext contextFor(DocumentManagerRunCommand command) {
        return new LabConnectorRunGroupContext(GROUP, CONNECTOR, BRAIN, command.tenantId(),
                command.externalRequestId(),
                DocumentManagerRunService.externalRequestSha256(command));
    }

    private static com.pragmaticds.rag.lab.domain.LabRun runRow(UUID id) {
        com.pragmaticds.rag.lab.domain.LabRun run = new com.pragmaticds.rag.lab.domain.LabRun();
        run.setId(id);
        return run;
    }

    private static LabInstanceRelease liveRelease() {
        LabInstanceRelease release = new LabInstanceRelease();
        release.setId(LIVE_RELEASE);
        release.setBrainId(BRAIN);
        release.setInstanceSlug("income");
        release.setReleaseNumber(3);
        return release;
    }

    private ResolvedInstanceRelease resolvedLive() {
        return new ResolvedInstanceRelease(instance, liveRelease(),
                new DecodedInstanceManifest.V2(manifest()), true);
    }

    private static InstanceReleaseManifest manifest() {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("synthetic", "synthetic-analyzer",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(COLLECTION, 7L))),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "income",
                        BigDecimal.ZERO),
                List.of(),
                new InstanceReleaseManifest.OutputContract("analyzer-envelope-v2", "a".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100_000L, 20_000, 8_000, 0, 2,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.80")));
    }

    /**
     * A real {@code VerifiedSelection}, not a mock. The service only reads its compatibility
     * verdict, but mocking a record means stubbing a final accessor mid-{@code thenReturn} —
     * which is how every test in this class once failed at setUp — and a minimal real envelope
     * costs a few lines.
     */
    private static ParsedDataResolver.VerifiedSelection verifiedSelection(boolean compatible) {
        EngineArtifactDescriptor artifact =
                EngineArtifactDescriptor.of("engine-bytes".getBytes(StandardCharsets.UTF_8));
        EngineResultEnvelope envelope = new EngineResultEnvelope(artifact, "1.0.0",
                "DOCENGINE-C14N-1", PACKAGE,
                new EngineResultEnvelope.Generation(PACKAGE, 1, 4, "d".repeat(64),
                        "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(), List.of(), List.of(), List.of(),
                new EngineResultEnvelope.Provenance(
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        List.of()));
        return new ParsedDataResolver.VerifiedSelection(BRAIN, "income", PACKAGE, 4,
                new DocumentEngineClient.RevisionDescriptor(4, PACKAGE, 1, 1, "1.0.0",
                        "d".repeat(64), "e".repeat(64), artifact.sha256(), artifact.byteCount(),
                        "REUSABLE", Instant.parse("2026-01-01T00:00:00Z")),
                new DocumentEngineClient.VerifiedEnvelope(artifact, envelope, 4),
                new ParsedInputSelection.SelectionResult(envelope,
                        List.of(new ParsedInputSelection.SelectedSource(
                                SOURCE_A, "a1".repeat(32), 0)),
                        "d".repeat(64)),
                new ParsedDataCompatibilityService.CompatibilityDecision(
                        compatible,
                        compatible ? null
                                : ParsedDataCompatibilityService.RejectionCode.NO_SUPPORTED_DOCUMENT,
                        List.of(), compatible ? 2 : 0));
    }

    private static ParsedDataResolver.VerifiedParsedInput pinnedInput() {
        return new ParsedDataResolver.VerifiedParsedInput(REGISTRATION, PACKAGE, 4, PACKAGE, 1,
                "1.0.0", "DOCENGINE-C14N-1", "b".repeat(64), 4096L, "c".repeat(64),
                List.of(SOURCE_A, SOURCE_B), null, null,
                new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 2));
    }

    private static CorpusSnapshotService.FrozenCorpusSnapshot frozen() {
        return new CorpusSnapshotService.FrozenCorpusSnapshot(SNAPSHOT, BRAIN, "e".repeat(64),
                List.of(new CorpusSnapshotService.FrozenCollection(COLLECTION, 7L)),
                List.of());
    }

}
