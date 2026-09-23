package com.pragmaticds.docengine.results.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.orchestration.EngineResultFinalizerPort.FinalizationResult;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobNotFoundException;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.storage.ImmutableBlobConflictException;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.canonical.EngineResultEnvelopeAssembler;
import com.pragmaticds.docengine.results.canonical.EngineResultEnvelopeAssembler.AssembledEnvelope;
import com.pragmaticds.docengine.results.canonical.EnvelopeAssemblyRequest;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshotLoader;
import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultEngineResultFinalizerTest {

    private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PACKAGE_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final byte[] BYTES = "{\"machine\":true}".getBytes(StandardCharsets.UTF_8);
    private static final String ENVELOPE_SHA = sha256(BYTES);
    private static final String SOURCE_SHA = "a".repeat(64);
    private static final String PROVENANCE_SHA = "b".repeat(64);
    private static final String KEY =
            "org/" + ORG_ID + "/engine-results/sha256/" + ENVELOPE_SHA + ".json";

    @Mock private DocumentPackageRepository packages;
    @Mock private ProcessingJobRepository jobs;
    @Mock private EngineResultRepository results;
    @Mock private MachineResultSnapshotLoader snapshots;
    @Mock private EngineResultEnvelopeAssembler assembler;
    @Mock private BlobStoragePort blobs;
    @Mock private MachineResultSnapshot machineSnapshot;

    private DefaultEngineResultFinalizer finalizer;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_ID);
        finalizer =
                new DefaultEngineResultFinalizer(
                        packages, jobs, results, snapshots, assembler, blobs);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void locksBeforeAssemblyThenPublishesBeforeSavingRevisionOne() {
        seedValidJob(1, 1);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID))
                .thenReturn(Optional.empty());
        when(results.findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.empty());
        stubAssembly();

        FinalizationResult finalized = finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1);

        ArgumentCaptor<EnvelopeAssemblyRequest> request =
                ArgumentCaptor.forClass(EnvelopeAssemblyRequest.class);
        ArgumentCaptor<EngineResult> descriptor = ArgumentCaptor.forClass(EngineResult.class);
        InOrder order = inOrder(packages, jobs, results, snapshots, assembler, blobs);
        order.verify(packages).lockAccessibleByIdAndOrgId(PACKAGE_ID, ORG_ID);
        order.verify(jobs).findByIdAndOrgId(JOB_ID, ORG_ID);
        order.verify(results)
                .findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID);
        order.verify(results).findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID);
        order.verify(snapshots).load(request.capture());
        order.verify(assembler).assemble(request.getValue(), snapshot());
        order.verify(blobs).putImmutable(KEY, BYTES, ENVELOPE_SHA);
        order.verify(results).saveAndFlush(descriptor.capture());

        assertThat(request.getValue().packageRevision()).isEqualTo(1);
        assertThat(request.getValue().envelopeVersion()).isEqualTo("1.0.0");
        assertThat(request.getValue().canonicalizationVersion()).isEqualTo("DOCENGINE-C14N-1");
        assertThat(descriptor.getValue().getPackageId()).isEqualTo(PACKAGE_ID);
        assertThat(descriptor.getValue().getProcessingJobId()).isEqualTo(JOB_ID);
        assertThat(descriptor.getValue().getParseGeneration()).isEqualTo(1);
        assertThat(descriptor.getValue().getMaterializedJobAttempt()).isEqualTo(1);
        assertThat(descriptor.getValue().getRevision()).isEqualTo(1);
        assertThat(descriptor.getValue().getSupersedesResultId()).isNull();
        assertThat(descriptor.getValue().getEnvelopeSchemaVersion()).isEqualTo("1.0.0");
        assertThat(descriptor.getValue().getCanonicalizationVersion())
                .isEqualTo("DOCENGINE-C14N-1");
        assertThat(descriptor.getValue().getCanonicalMediaType())
                .isEqualTo(
                        "application/vnd.pragmaticds.document-engine-result+json;version=1");
        assertThat(descriptor.getValue().getSourceSetSha256()).isEqualTo(SOURCE_SHA);
        assertThat(descriptor.getValue().getProvenanceSha256()).isEqualTo(PROVENANCE_SHA);
        assertThat(descriptor.getValue().getEnvelopeStorageKey()).isEqualTo(KEY);
        assertThat(descriptor.getValue().getEnvelopeSha256()).isEqualTo(ENVELOPE_SHA);
        assertThat(descriptor.getValue().getEnvelopeSizeBytes()).isEqualTo(BYTES.length);
        assertThat(descriptor.getValue().getReuseEligibility())
                .isEqualTo(ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);
        assertThat(finalized.envelopeSha256()).isEqualTo(ENVELOPE_SHA);
        assertThat(finalized.envelopeSizeBytes()).isEqualTo(BYTES.length);
    }

    @Test
    void allocatesRevisionTwoWithItsImmediatePredecessor() {
        seedValidJob(2, 2);
        EngineResult predecessor = descriptor(1, 1, null, 1);
        when(predecessor.getId()).thenReturn(UUID.randomUUID());
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 2, ORG_ID))
                .thenReturn(Optional.empty());
        when(results.findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.of(1));
        when(results.findByPackageIdAndRevisionAndOrgId(PACKAGE_ID, 1, ORG_ID))
                .thenReturn(Optional.of(predecessor));
        stubAssembly();

        finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 2, 2);

        ArgumentCaptor<EnvelopeAssemblyRequest> request =
                ArgumentCaptor.forClass(EnvelopeAssemblyRequest.class);
        ArgumentCaptor<EngineResult> descriptor = ArgumentCaptor.forClass(EngineResult.class);
        verify(snapshots).load(request.capture());
        verify(results).saveAndFlush(descriptor.capture());
        assertThat(request.getValue().packageRevision()).isEqualTo(2);
        assertThat(descriptor.getValue().getRevision()).isEqualTo(2);
        assertThat(descriptor.getValue().getSupersedesResultId()).isEqualTo(predecessor.getId());
    }

    @Test
    void existingGenerationWithExactBlobAndDescriptorIsANoOpEvenForANewerJobAttempt() {
        seedValidJob(1, 2);
        EngineResult existing = descriptor(1, 1, null, 1);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID))
                .thenReturn(Optional.of(existing));
        when(blobs.get(KEY)).thenReturn(BYTES);
        stubAssembly();

        FinalizationResult finalized = finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 2);

        verify(blobs, never()).putImmutable(any(), any(), any());
        verify(results, never()).saveAndFlush(any());
        assertThat(finalized.envelopeSha256()).isEqualTo(ENVELOPE_SHA);
        assertThat(finalized.envelopeSizeBytes()).isEqualTo(BYTES.length);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("immutableDescriptorMutations")
    void everyExistingImmutableDescriptorMutationFailsConflictWithoutRepair(
            String ignoredName, Consumer<EngineResult> mutation) {
        seedValidJob(1, 1);
        EngineResult existing = descriptor(1, 1, null, 1);
        mutation.accept(existing);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID))
                .thenReturn(Optional.of(existing));
        when(blobs.get(KEY)).thenReturn(BYTES);
        stubAssembly();

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.ENGINE_RESULT_CONFLICT,
                409,
                "IMMUTABLE_DESCRIPTOR_MISMATCH");
        verify(blobs, never()).putImmutable(any(), any(), any());
        verify(results, never()).saveAndFlush(any());
    }

    static Stream<Arguments> immutableDescriptorMutations() {
        return Stream.of(
                Arguments.of(
                        "source digest",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getSourceSetSha256())
                                                .thenReturn("c".repeat(64))),
                Arguments.of(
                        "provenance digest",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getProvenanceSha256())
                                                .thenReturn("d".repeat(64))),
                Arguments.of(
                        "envelope version",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getEnvelopeSchemaVersion())
                                                .thenReturn("2.0.0")),
                Arguments.of(
                        "canonicalization version",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getCanonicalizationVersion())
                                                .thenReturn("OTHER-C14N")),
                Arguments.of(
                        "canonical media type",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getCanonicalMediaType())
                                                .thenReturn("application/json")),
                Arguments.of(
                        "reuse eligibility",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getReuseEligibility()).thenReturn(null)),
                Arguments.of(
                        "tenant",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getOrgId()).thenReturn(UUID.randomUUID())),
                Arguments.of(
                        "package",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getPackageId()).thenReturn(UUID.randomUUID())),
                Arguments.of(
                        "job",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getProcessingJobId())
                                                .thenReturn(UUID.randomUUID())),
                Arguments.of(
                        "parse generation",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getParseGeneration()).thenReturn(2)),
                Arguments.of(
                        "supersedes identity",
                        (Consumer<EngineResult>)
                                descriptor ->
                                        when(descriptor.getSupersedesResultId())
                                                .thenReturn(UUID.randomUUID())));
    }

    @Test
    void missingWrongLengthWrongDigestAndWrongKeyAreCorruptAndNeverRepaired() {
        seedValidJob(1, 1);
        EngineResult existing = descriptor(1, 1, null, 1);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID))
                .thenReturn(Optional.of(existing));
        when(blobs.get(KEY)).thenThrow(new BlobNotFoundException(KEY));

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500,
                "BLOB_MISSING");
        verify(blobs, never()).putImmutable(any(), any(), any());
        verify(results, never()).saveAndFlush(any());

        org.mockito.Mockito.reset(blobs);
        when(blobs.get(KEY)).thenReturn("short".getBytes(StandardCharsets.UTF_8));
        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500,
                "BLOB_INTEGRITY_MISMATCH");

        org.mockito.Mockito.reset(blobs);
        byte[] sameLengthWrongDigest = BYTES.clone();
        sameLengthWrongDigest[0] = '[';
        when(blobs.get(KEY)).thenReturn(sameLengthWrongDigest);
        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500,
                "BLOB_INTEGRITY_MISMATCH");

        org.mockito.Mockito.reset(blobs);
        when(existing.getEnvelopeStorageKey()).thenReturn("not-the-derived-key");
        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500,
                "STORAGE_KEY_MISMATCH");
        verifyNoInteractions(blobs);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidGenerationIdentities")
    void invalidGenerationIdentityHasNoMaterializationReadAssemblyStorageOrSaveEffects(
            String ignoredName,
            UUID jobPackageId,
            int jobGeneration,
            int jobAttempt,
            int requestGeneration,
            int requestAttempt) {
        seedJob(jobPackageId, jobGeneration, jobAttempt);

        assertDomain(
                () ->
                        finalizer.finalizeResult(
                                JOB_ID, PACKAGE_ID, requestGeneration, requestAttempt),
                ErrorCode.ENGINE_RESULT_CONFLICT,
                409,
                "GENERATION_IDENTITY_MISMATCH");
        verifyNoInteractions(results, snapshots, assembler, blobs);
    }

    static Stream<Arguments> invalidGenerationIdentities() {
        return Stream.of(
                Arguments.of("wrong job-package binding", UUID.randomUUID(), 1, 1, 1, 1),
                Arguments.of("stale materializing attempt", PACKAGE_ID, 1, 2, 1, 1),
                Arguments.of("future materializing attempt", PACKAGE_ID, 1, 2, 1, 3),
                Arguments.of("stale parse generation", PACKAGE_ID, 2, 1, 1, 1),
                Arguments.of("zero parse generation", PACKAGE_ID, 1, 1, 0, 1),
                Arguments.of("negative parse generation", PACKAGE_ID, 1, 1, -1, 1),
                Arguments.of("zero materializing attempt", PACKAGE_ID, 1, 1, 1, 0),
                Arguments.of("negative materializing attempt", PACKAGE_ID, 1, 1, 1, -1));
    }

    @Test
    void missingJobIsOpaqueNotFoundBeforeAnyResultOrArtifactEffects() {
        when(packages.lockAccessibleByIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.of(mock(DocumentPackage.class)));
        when(jobs.findByIdAndOrgId(JOB_ID, ORG_ID)).thenReturn(Optional.empty());

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.NOT_FOUND,
                404,
                null);
        verifyNoInteractions(results, snapshots, assembler, blobs);
    }

    @Test
    void foreignOrTombstonedPackageIsOpaqueNotFoundAndHasNoEffects() {
        when(packages.lockAccessibleByIdAndOrgId(PACKAGE_ID, ORG_ID)).thenReturn(Optional.empty());

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.NOT_FOUND,
                404,
                null);
        verifyNoInteractions(jobs, results, snapshots, assembler, blobs);
    }

    @Test
    void missingPredecessorAndNewKeyCollisionAreCorruptWithoutDescriptorInsert() {
        seedValidJob(2, 2);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 2, ORG_ID))
                .thenReturn(Optional.empty());
        when(results.findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.of(1));
        when(results.findByPackageIdAndRevisionAndOrgId(PACKAGE_ID, 1, ORG_ID))
                .thenReturn(Optional.empty());

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 2, 2),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500,
                "PREDECESSOR_MISSING");
        verifyNoInteractions(snapshots, assembler, blobs);

        org.mockito.Mockito.reset(results);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 2, ORG_ID))
                .thenReturn(Optional.empty());
        when(results.findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.empty());
        stubAssembly();
        org.mockito.Mockito.doThrow(new ImmutableBlobConflictException(KEY))
                .when(blobs)
                .putImmutable(KEY, BYTES, ENVELOPE_SHA);

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 2, 2),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500,
                "CONTENT_ADDRESS_COLLISION");
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void inconsistentMachineSnapshotMapsToStablePayloadFreeNotReady() {
        seedValidJob(1, 1);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID))
                .thenReturn(Optional.empty());
        when(results.findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.empty());
        when(snapshots.load(any())).thenThrow(new IllegalStateException("borrower-value"));

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.ENGINE_RESULT_NOT_READY,
                409,
                "MACHINE_SNAPSHOT_UNAVAILABLE");
        verifyNoInteractions(assembler, blobs);
        verify(results, never()).saveAndFlush(any());
    }

    /**
     * The loader's OWN refusals name their invariant in structural terms, and that reason — and
     * only that reason — is published as {@code detail}. An arbitrary IllegalStateException (the
     * two sibling tests) still maps to a payload-free error, because its message may quote content.
     */
    @Test
    void namedSnapshotInvariantTravelsAsDetailWhileArbitraryMessagesStayPayloadFree() {
        seedValidJob(1, 1);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID))
                .thenReturn(Optional.empty());
        when(results.findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.empty());
        when(snapshots.load(any()))
                .thenThrow(
                        new com.pragmaticds.docengine.results.canonical.MachineSnapshotInconsistentException(
                                "object members differ (missing [], unexpected [rawDocumentText])"));

        assertThatThrownBy(() -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(ErrorCode.ENGINE_RESULT_NOT_READY);
                            assertThat(failure.params())
                                    .containsExactlyInAnyOrderEntriesOf(
                                            java.util.Map.of(
                                                    "reason",
                                                    "MACHINE_SNAPSHOT_UNAVAILABLE",
                                                    "detail",
                                                    "object members differ (missing [], unexpected"
                                                            + " [rawDocumentText])"));
                        });
        verifyNoInteractions(assembler, blobs);
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void inconsistentEnvelopeAssemblyMapsToStablePayloadFreeNotReady() {
        seedValidJob(1, 1);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 1, ORG_ID))
                .thenReturn(Optional.empty());
        when(results.findMaximumRevisionByPackageIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.empty());
        when(snapshots.load(any())).thenReturn(snapshot());
        when(assembler.assemble(any(), any()))
                .thenThrow(new IllegalStateException("sensitive-envelope-canary"));

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 1, 1),
                ErrorCode.ENGINE_RESULT_NOT_READY,
                409,
                "MACHINE_ENVELOPE_UNAVAILABLE");
        verifyNoInteractions(blobs);
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void existingRevisionTwoWithExactImmediatePredecessorIsIdempotent() {
        seedValidJob(2, 2);
        UUID predecessorId = UUID.randomUUID();
        EngineResult predecessor = descriptor(1, 1, null, 1);
        when(predecessor.getId()).thenReturn(predecessorId);
        EngineResult existing = descriptor(2, 2, predecessorId, 2);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 2, ORG_ID))
                .thenReturn(Optional.of(existing));
        when(results.findByPackageIdAndRevisionAndOrgId(PACKAGE_ID, 1, ORG_ID))
                .thenReturn(Optional.of(predecessor));
        when(blobs.get(KEY)).thenReturn(BYTES);
        stubAssembly();

        FinalizationResult finalized = finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 2, 2);

        assertThat(finalized.envelopeSha256()).isEqualTo(ENVELOPE_SHA);
        assertThat(finalized.envelopeSizeBytes()).isEqualTo(BYTES.length);
        verify(blobs, never()).putImmutable(any(), any(), any());
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void existingRevisionTwoWithWrongPredecessorConflictsWithoutRepair() {
        seedValidJob(2, 2);
        UUID predecessorId = UUID.randomUUID();
        EngineResult predecessor = descriptor(1, 1, null, 1);
        when(predecessor.getId()).thenReturn(predecessorId);
        EngineResult existing = descriptor(2, 2, UUID.randomUUID(), 2);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 2, ORG_ID))
                .thenReturn(Optional.of(existing));
        when(results.findByPackageIdAndRevisionAndOrgId(PACKAGE_ID, 1, ORG_ID))
                .thenReturn(Optional.of(predecessor));
        when(blobs.get(KEY)).thenReturn(BYTES);
        stubAssembly();

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 2, 2),
                ErrorCode.ENGINE_RESULT_CONFLICT,
                409,
                "IMMUTABLE_DESCRIPTOR_MISMATCH");
        verify(blobs, never()).putImmutable(any(), any(), any());
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void existingRevisionTwoWithMissingPredecessorIsCorruptWithoutRepair() {
        seedValidJob(2, 2);
        EngineResult existing = descriptor(2, 2, UUID.randomUUID(), 2);
        when(results.findByProcessingJobIdAndParseGenerationAndOrgId(JOB_ID, 2, ORG_ID))
                .thenReturn(Optional.of(existing));
        when(results.findByPackageIdAndRevisionAndOrgId(PACKAGE_ID, 1, ORG_ID))
                .thenReturn(Optional.empty());
        when(blobs.get(KEY)).thenReturn(BYTES);

        assertDomain(
                () -> finalizer.finalizeResult(JOB_ID, PACKAGE_ID, 2, 2),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500,
                "PREDECESSOR_MISSING");
        verifyNoInteractions(snapshots, assembler);
        verify(blobs, never()).putImmutable(any(), any(), any());
        verify(results, never()).saveAndFlush(any());
    }

    private void seedValidJob(int parseGeneration, int attempt) {
        seedJob(PACKAGE_ID, parseGeneration, attempt);
    }

    private void seedJob(UUID packageId, int parseGeneration, int attempt) {
        when(packages.lockAccessibleByIdAndOrgId(PACKAGE_ID, ORG_ID))
                .thenReturn(Optional.of(mock(DocumentPackage.class)));
        ProcessingJob job = mock(ProcessingJob.class);
        lenient().when(job.getPackageId()).thenReturn(packageId);
        lenient().when(job.getParseGeneration()).thenReturn(parseGeneration);
        lenient().when(job.getAttempt()).thenReturn(attempt);
        when(jobs.findByIdAndOrgId(JOB_ID, ORG_ID)).thenReturn(Optional.of(job));
    }

    private void stubAssembly() {
        when(snapshots.load(any())).thenReturn(snapshot());
        when(assembler.assemble(any(), any()))
                .thenReturn(new AssembledEnvelope(BYTES, ENVELOPE_SHA, SOURCE_SHA, PROVENANCE_SHA));
    }

    private MachineResultSnapshot snapshot() {
        return machineSnapshot;
    }

    private EngineResult descriptor(
            int revision, int parseGeneration, UUID predecessorId, int materializedAttempt) {
        EngineResult descriptor = mock(EngineResult.class);
        lenient().when(descriptor.getOrgId()).thenReturn(ORG_ID);
        lenient().when(descriptor.getPackageId()).thenReturn(PACKAGE_ID);
        lenient().when(descriptor.getProcessingJobId()).thenReturn(JOB_ID);
        lenient().when(descriptor.getParseGeneration()).thenReturn(parseGeneration);
        lenient().when(descriptor.getMaterializedJobAttempt()).thenReturn(materializedAttempt);
        lenient().when(descriptor.getRevision()).thenReturn(revision);
        lenient().when(descriptor.getSupersedesResultId()).thenReturn(predecessorId);
        lenient().when(descriptor.getEnvelopeSchemaVersion()).thenReturn("1.0.0");
        lenient().when(descriptor.getCanonicalizationVersion()).thenReturn("DOCENGINE-C14N-1");
        lenient().when(descriptor.getCanonicalMediaType())
                .thenReturn("application/vnd.pragmaticds.document-engine-result+json;version=1");
        lenient().when(descriptor.getSourceSetSha256()).thenReturn(SOURCE_SHA);
        lenient().when(descriptor.getProvenanceSha256()).thenReturn(PROVENANCE_SHA);
        lenient().when(descriptor.getEnvelopeStorageKey()).thenReturn(KEY);
        lenient().when(descriptor.getEnvelopeSha256()).thenReturn(ENVELOPE_SHA);
        lenient().when(descriptor.getEnvelopeSizeBytes()).thenReturn((long) BYTES.length);
        lenient().when(descriptor.getReuseEligibility())
                .thenReturn(ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);
        return descriptor;
    }

    private static void assertDomain(
            Runnable call, ErrorCode code, int status, String stableReason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(code);
                            assertThat(failure.httpStatus()).isEqualTo(status);
                            if (stableReason == null) {
                                assertThat(failure.params()).isEmpty();
                            } else {
                                assertThat(failure.params())
                                        .containsExactlyEntriesOf(
                                                java.util.Map.of("reason", stableReason));
                            }
                            assertThat(failure.getMessage())
                                    .doesNotContain(
                                            KEY,
                                            ENVELOPE_SHA,
                                            SOURCE_SHA,
                                            PROVENANCE_SHA,
                                            "borrower-value",
                                            "sensitive-envelope-canary");
                            assertThat(failure.params().toString())
                                    .doesNotContain(
                                            "borrower-value", "sensitive-envelope-canary");
                        });
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
