package com.pragmaticds.docengine.results.service;

import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-bound, tombstone-aware reads of immutable engine-result descriptors and bytes. */
@Service
public class EngineResultQueryService {

    public static final String CANONICAL_MEDIA_TYPE =
            "application/vnd.pragmaticds.document-engine-result+json;version=1";

    private final DocumentPackageRepository packages;
    private final ProcessingJobRepository jobs;
    private final ProcessingStageRepository stages;
    private final EngineResultRepository results;
    private final BlobStoragePort blobs;

    public EngineResultQueryService(
            DocumentPackageRepository packages,
            ProcessingJobRepository jobs,
            ProcessingStageRepository stages,
            EngineResultRepository results,
            BlobStoragePort blobs) {
        this.packages = packages;
        this.jobs = jobs;
        this.stages = stages;
        this.results = results;
        this.blobs = blobs;
    }

    /** Ordered descriptor metadata only. This path deliberately never touches blob storage. */
    @Transactional(readOnly = true)
    public List<EngineResult> history(UUID packageId) {
        UUID orgId = requireLivePackage(packageId);
        return List.copyOf(results.findByPackageIdAndOrgIdOrderByRevisionAsc(packageId, orgId));
    }

    /** The current job's exact parse generation, never the greatest historical revision. */
    @Transactional(readOnly = true)
    public VerifiedContent current(UUID packageId) {
        UUID orgId = requireLivePackage(packageId);
        ProcessingJob job;
        try {
            // One job per package is an explicit prototype boundary, not a schema invariant. A
            // future new-job reparse requires an authoritative current-job pointer or database
            // invariant before this lookup may choose one; guessing newest/max would serve the
            // wrong immutable generation.
            job =
                    jobs.findByPackageIdAndOrgId(packageId, orgId)
                            .orElseThrow(EngineResultQueryService::notReady);
        } catch (IncorrectResultSizeDataAccessException duplicatePackageJobs) {
            throw corrupt();
        }

        List<ProcessingStage> finalized =
                stages.findByJobIdOrderByCreatedAtAsc(job.getId()).stream()
                        .filter(stage -> stage.getStage() == ProcessingStatus.FINALIZING)
                        .filter(stage -> stage.getStatus() == StageStatus.SUCCEEDED)
                        .toList();
        if (finalized.isEmpty()) {
            throw notReady();
        }
        if (finalized.size() != 1) {
            throw corrupt();
        }

        EngineResult descriptor =
                results.findByProcessingJobIdAndParseGenerationAndOrgId(
                                job.getId(), job.getParseGeneration(), orgId)
                        .orElseThrow(EngineResultQueryService::corrupt);
        if (!descriptor.getPackageId().equals(packageId)
                || finalized.get(0).getOutputDigest() == null
                || !finalized.get(0).getOutputDigest().equals(descriptor.getEnvelopeSha256())) {
            throw corrupt();
        }
        return verify(descriptor, orgId);
    }

    /** One exact historical package revision. */
    @Transactional(readOnly = true)
    public VerifiedContent revision(UUID packageId, int revision) {
        UUID orgId = requireLivePackage(packageId);
        EngineResult descriptor =
                results.findByPackageIdAndRevisionAndOrgId(packageId, revision, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        return verify(descriptor, orgId);
    }

    private UUID requireLivePackage(UUID packageId) {
        UUID orgId = TenantContext.require();
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(packageId, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        return orgId;
    }

    private VerifiedContent verify(EngineResult descriptor, UUID orgId) {
        String expectedKey;
        try {
            expectedKey =
                    EngineResultStorageKey.forEnvelope(orgId, descriptor.getEnvelopeSha256());
        } catch (IllegalArgumentException malformedDescriptor) {
            throw corrupt();
        }
        if (!orgId.equals(descriptor.getOrgId())
                || !expectedKey.equals(descriptor.getEnvelopeStorageKey())) {
            // Blob storage is a common root with no tenant context of its own. Reject a malformed
            // descriptor before it can turn a tenant-scoped row into a cross-tenant object read.
            throw corrupt();
        }

        byte[] loaded;
        try {
            loaded = blobs.get(expectedKey);
        } catch (RuntimeException failure) {
            throw corrupt();
        }
        if (loaded.length != descriptor.getEnvelopeSizeBytes()
                || !sha256(loaded).equals(descriptor.getEnvelopeSha256())) {
            throw corrupt();
        }
        return new VerifiedContent(
                descriptor.getId(),
                descriptor.getPackageId(),
                descriptor.getProcessingJobId(),
                descriptor.getRevision(),
                descriptor.getEnvelopeSha256(),
                loaded);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw corrupt();
        }
    }

    private static DomainException notReady() {
        return DomainException.conflict(ErrorCode.ENGINE_RESULT_NOT_READY, java.util.Map.of());
    }

    private static DomainException corrupt() {
        return new DomainException(ErrorCode.ENGINE_RESULT_CORRUPT, 500);
    }

    /** Verified bytes are copied on construction and access so callers cannot mutate the result. */
    public record VerifiedContent(
            UUID resultId,
            UUID packageId,
            UUID processingJobId,
            int revision,
            String envelopeSha256,
            byte[] bytes) {
        public VerifiedContent {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
