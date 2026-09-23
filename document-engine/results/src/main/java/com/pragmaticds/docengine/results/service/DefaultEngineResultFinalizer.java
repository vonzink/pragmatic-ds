package com.pragmaticds.docengine.results.service;

import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.orchestration.EngineResultFinalizerPort;
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
import com.pragmaticds.docengine.results.canonical.MachineSnapshotInconsistentException;
import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes and records one immutable canonical artifact per parse generation. */
@Service
public class DefaultEngineResultFinalizer implements EngineResultFinalizerPort {

    static final String ENVELOPE_VERSION = "1.0.0";
    static final String CANONICALIZATION_VERSION = "DOCENGINE-C14N-1";
    static final String CANONICAL_MEDIA_TYPE =
            "application/vnd.pragmaticds.document-engine-result+json;version=1";

    private final DocumentPackageRepository packages;
    private final ProcessingJobRepository jobs;
    private final EngineResultRepository results;
    private final MachineResultSnapshotLoader snapshots;
    private final EngineResultEnvelopeAssembler assembler;
    private final BlobStoragePort blobs;

    public DefaultEngineResultFinalizer(
            DocumentPackageRepository packages,
            ProcessingJobRepository jobs,
            EngineResultRepository results,
            MachineResultSnapshotLoader snapshots,
            EngineResultEnvelopeAssembler assembler,
            BlobStoragePort blobs) {
        this.packages = packages;
        this.jobs = jobs;
        this.results = results;
        this.snapshots = snapshots;
        this.assembler = assembler;
        this.blobs = blobs;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public FinalizationResult finalizeResult(
            UUID jobId, UUID packageId, int parseGeneration, int materializingJobAttempt) {
        UUID orgId = TenantContext.require();
        packages
                .lockAccessibleByIdAndOrgId(packageId, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        ProcessingJob job =
                jobs.findByIdAndOrgId(jobId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        validateGenerationIdentity(
                job, packageId, parseGeneration, materializingJobAttempt);

        return results.findByProcessingJobIdAndParseGenerationAndOrgId(
                        jobId, parseGeneration, orgId)
                .map(
                        existing ->
                                verifyExisting(
                                        orgId,
                                        existing,
                                        packageId,
                                        jobId,
                                        parseGeneration))
                .orElseGet(
                        () ->
                                materializeNew(
                                        orgId,
                                        packageId,
                                        jobId,
                                        parseGeneration,
                                        materializingJobAttempt));
    }

    private FinalizationResult materializeNew(
            UUID orgId,
            UUID packageId,
            UUID jobId,
            int parseGeneration,
            int materializingJobAttempt) {
        int previousRevision =
                results.findMaximumRevisionByPackageIdAndOrgId(packageId, orgId).orElse(0);
        if (previousRevision == Integer.MAX_VALUE) {
            throw corrupt("REVISION_EXHAUSTED");
        }
        int revision = previousRevision + 1;
        UUID predecessorId =
                revision == 1
                        ? null
                        : immediatePredecessor(packageId, orgId, previousRevision).getId();

        EnvelopeAssemblyRequest request =
                request(packageId, jobId, parseGeneration, revision);
        AssembledEnvelope envelope = assemble(request);
        byte[] bytes = envelope.bytes();
        String key = EngineResultStorageKey.forEnvelope(orgId, envelope.envelopeSha256());
        try {
            blobs.putImmutable(key, bytes, envelope.envelopeSha256());
        } catch (ImmutableBlobConflictException collision) {
            throw corrupt("CONTENT_ADDRESS_COLLISION");
        }

        EngineResult descriptor =
                new EngineResult(
                        packageId,
                        jobId,
                        parseGeneration,
                        materializingJobAttempt,
                        revision,
                        predecessorId,
                        ENVELOPE_VERSION,
                        CANONICALIZATION_VERSION,
                        CANONICAL_MEDIA_TYPE,
                        envelope.sourceSetSha256(),
                        envelope.provenanceSha256(),
                        key,
                        envelope.envelopeSha256(),
                        bytes.length,
                        ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);
        results.saveAndFlush(descriptor);
        return result(envelope.envelopeSha256(), bytes.length);
    }

    private FinalizationResult verifyExisting(
            UUID orgId,
            EngineResult existing,
            UUID packageId,
            UUID jobId,
            int parseGeneration) {
        String expectedKey =
                EngineResultStorageKey.forEnvelope(orgId, existing.getEnvelopeSha256());
        if (!expectedKey.equals(existing.getEnvelopeStorageKey())) {
            throw corrupt("STORAGE_KEY_MISMATCH");
        }

        byte[] stored;
        try {
            stored = blobs.get(expectedKey);
        } catch (BlobNotFoundException missing) {
            throw corrupt("BLOB_MISSING");
        }
        if (stored.length != existing.getEnvelopeSizeBytes()
                || !sha256(stored).equals(existing.getEnvelopeSha256())) {
            throw corrupt("BLOB_INTEGRITY_MISMATCH");
        }

        UUID expectedPredecessorId = expectedPredecessorId(existing, packageId, orgId);
        EnvelopeAssemblyRequest request =
                request(packageId, jobId, parseGeneration, existing.getRevision());
        AssembledEnvelope assembled = assemble(request);
        byte[] assembledBytes = assembled.bytes();
        if (!descriptorMatches(
                        existing,
                        orgId,
                        packageId,
                        jobId,
                        parseGeneration,
                        expectedPredecessorId,
                        expectedKey,
                        assembled,
                        assembledBytes.length)
                || !Arrays.equals(stored, assembledBytes)) {
            throw conflict("IMMUTABLE_DESCRIPTOR_MISMATCH");
        }
        return result(existing.getEnvelopeSha256(), existing.getEnvelopeSizeBytes());
    }

    private UUID expectedPredecessorId(
            EngineResult existing, UUID packageId, UUID orgId) {
        if (existing.getRevision() <= 0) {
            throw corrupt("REVISION_INVALID");
        }
        if (existing.getRevision() == 1) {
            return null;
        }
        return immediatePredecessor(packageId, orgId, existing.getRevision() - 1).getId();
    }

    private EngineResult immediatePredecessor(UUID packageId, UUID orgId, int revision) {
        EngineResult predecessor =
                results.findByPackageIdAndRevisionAndOrgId(packageId, revision, orgId)
                        .orElseThrow(() -> corrupt("PREDECESSOR_MISSING"));
        if (predecessor.getId() == null
                || predecessor.getRevision() != revision
                || !packageId.equals(predecessor.getPackageId())
                || !orgId.equals(predecessor.getOrgId())) {
            throw corrupt("PREDECESSOR_INVALID");
        }
        return predecessor;
    }

    private AssembledEnvelope assemble(EnvelopeAssemblyRequest request) {
        MachineResultSnapshot snapshot;
        try {
            snapshot = snapshots.load(request);
        } catch (MachineSnapshotInconsistentException inconsistent) {
            throw notReady("MACHINE_SNAPSHOT_UNAVAILABLE", inconsistent.reason());
        } catch (IllegalStateException unavailable) {
            throw notReady("MACHINE_SNAPSHOT_UNAVAILABLE");
        }
        try {
            return assembler.assemble(request, snapshot);
        } catch (MachineSnapshotInconsistentException inconsistent) {
            throw notReady("MACHINE_ENVELOPE_UNAVAILABLE", inconsistent.reason());
        } catch (IllegalStateException unavailable) {
            throw notReady("MACHINE_ENVELOPE_UNAVAILABLE");
        }
    }

    private static EnvelopeAssemblyRequest request(
            UUID packageId,
            UUID jobId,
            int parseGeneration,
            int revision) {
        return new EnvelopeAssemblyRequest(
                packageId,
                jobId,
                parseGeneration,
                revision,
                ENVELOPE_VERSION,
                CANONICALIZATION_VERSION,
                ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);
    }

    private static boolean descriptorMatches(
            EngineResult existing,
            UUID orgId,
            UUID packageId,
            UUID jobId,
            int parseGeneration,
            UUID predecessorId,
            String storageKey,
            AssembledEnvelope assembled,
            long sizeBytes) {
        return orgId.equals(existing.getOrgId())
                && packageId.equals(existing.getPackageId())
                && jobId.equals(existing.getProcessingJobId())
                && parseGeneration == existing.getParseGeneration()
                && Objects.equals(predecessorId, existing.getSupersedesResultId())
                && ENVELOPE_VERSION.equals(existing.getEnvelopeSchemaVersion())
                && CANONICALIZATION_VERSION.equals(existing.getCanonicalizationVersion())
                && CANONICAL_MEDIA_TYPE.equals(existing.getCanonicalMediaType())
                && assembled.sourceSetSha256().equals(existing.getSourceSetSha256())
                && assembled.provenanceSha256().equals(existing.getProvenanceSha256())
                && storageKey.equals(existing.getEnvelopeStorageKey())
                && assembled.envelopeSha256().equals(existing.getEnvelopeSha256())
                && sizeBytes == existing.getEnvelopeSizeBytes()
                && existing.getReuseEligibility()
                        == ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE;
    }

    private static void validateGenerationIdentity(
            ProcessingJob job,
            UUID packageId,
            int parseGeneration,
            int materializingJobAttempt) {
        if (parseGeneration <= 0
                || materializingJobAttempt <= 0
                || !packageId.equals(job.getPackageId())
                || parseGeneration != job.getParseGeneration()
                || materializingJobAttempt != job.getAttempt()) {
            throw conflict("GENERATION_IDENTITY_MISMATCH");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static FinalizationResult result(String digest, long size) {
        return new FinalizationResult(digest, size);
    }

    private static DomainException conflict(String reason) {
        return new DomainException(
                ErrorCode.ENGINE_RESULT_CONFLICT, 409, Map.of("reason", reason));
    }

    private static DomainException corrupt(String reason) {
        return new DomainException(
                ErrorCode.ENGINE_RESULT_CORRUPT, 500, Map.of("reason", reason));
    }

    private static DomainException notReady(String reason) {
        return new DomainException(
                ErrorCode.ENGINE_RESULT_NOT_READY, 409, Map.of("reason", reason));
    }

    /**
     * Only a {@link MachineSnapshotInconsistentException}'s reason reaches the payload: it names
     * the violated invariant in structural terms (member names, row kinds — never a stored value),
     * so it is safe to persist as the stage's failure detail. Any other {@code
     * IllegalStateException} stays reason-only, because its message carries no such guarantee.
     */
    private static DomainException notReady(String reason, String detail) {
        return new DomainException(
                ErrorCode.ENGINE_RESULT_NOT_READY, 409, Map.of("reason", reason, "detail", detail));
    }
}
