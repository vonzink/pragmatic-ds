package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceCommandResult;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceSnapshotService;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Deliberately narrow public views of registry and manifest metadata. */
public final class InstanceAdminDtos {
    private InstanceAdminDtos() {}

    public record UpdateInstanceRequest(String displayName, String purpose) {}

    public record InstanceSummary(
            UUID brainId, String slug, String displayName, String purpose, String state,
            Integer liveReleaseNumber, int candidateCount, Integer manifestVersion,
            String provider, String model, Integer collectionCount, boolean hasCandidateRelease,
            String limitationCode, List<String> limitationFlags,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /**
     * A summary plus the release it currently has live.
     *
     * <p>{@code liveRelease} is null exactly when {@code liveReleaseNumber} is — an instance that
     * has never been promoted. The two say the same thing and must not disagree; a screen that
     * reads either one is reading a state the registry can genuinely be in, not an outage.
     */
    public record InstanceDetail(
            UUID brainId, String slug, String displayName, String purpose, String state,
            Integer liveReleaseNumber, int candidateCount, Integer manifestVersion,
            String provider, String model, Integer collectionCount, boolean hasCandidateRelease,
            String limitationCode, List<String> limitationFlags,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, ReleaseSummary liveRelease) {}

    public record ReleaseSummary(
            UUID releaseId, int releaseNumber, String provenance, boolean live,
            Integer manifestVersion, String provider, String model, Integer collectionCount,
            String limitationCode, List<String> limitationFlags, OffsetDateTime createdAt) {}

    static InstanceSummary summary(LabInstance instance, List<ResolvedInstanceRelease> history) {
        ResolvedInstanceRelease live = history.stream().filter(ResolvedInstanceRelease::live).findFirst()
                .orElse(null);
        int candidates = (int) history.stream().filter(release ->
                release.release().getProvenanceMode() == LabInstanceRelease.ProvenanceMode.CANDIDATE).count();
        ManifestMetadata metadata = live == null ? ManifestMetadata.empty() : metadata(live.manifest());
        return new InstanceSummary(instance.getBrainId(), instance.getSlug(), instance.getDisplayName(),
                instance.getPurpose(), instance.getState().name(),
                live == null ? null : live.release().getReleaseNumber(), candidates,
                metadata.manifestVersion(), metadata.provider(), metadata.model(), metadata.collectionCount(),
                candidates > 0, metadata.limitationCode(), metadata.limitationFlags(),
                instance.getCreatedAt(), instance.getUpdatedAt());
    }

    static InstanceDetail detail(LabInstance instance, ResolvedInstanceRelease live,
                                 List<ResolvedInstanceRelease> history) {
        InstanceSummary summary = summary(instance, history);
        return new InstanceDetail(summary.brainId(), summary.slug(), summary.displayName(), summary.purpose(),
                summary.state(), summary.liveReleaseNumber(), summary.candidateCount(),
                summary.manifestVersion(), summary.provider(), summary.model(), summary.collectionCount(),
                summary.hasCandidateRelease(), summary.limitationCode(), summary.limitationFlags(),
                summary.createdAt(), summary.updatedAt(), live == null ? null : release(live));
    }

    static InstanceSummary summary(InstanceSnapshotService.InstanceSnapshot snapshot) {
        return summary(snapshot.instance(), snapshot.history());
    }

    static InstanceDetail detail(InstanceSnapshotService.InstanceSnapshot snapshot) {
        return detail(snapshot.instance(), snapshot.live(), snapshot.history());
    }

    /**
     * The stored, body-free replay row for one mutation.
     *
     * <p>Every {@code live*} column is nullable except the limitation flags, so an instance with
     * nothing live stores nulls and an empty flag list — the same shape
     * {@link #summary(LabInstance, List)} composes from an absent live release, so the row and a
     * fresh read of the same instance agree. Dereferencing the live release unconditionally here
     * would turn a replay of an update to a never-promoted instance into a 500.
     */
    static LabInstanceCommandResult result(InstanceDetail detail) {
        LabInstanceCommandResult result = new LabInstanceCommandResult();
        result.setBrainId(detail.brainId()); result.setInstanceSlug(detail.slug());
        result.setDisplayName(detail.displayName()); result.setPurpose(detail.purpose()); result.setState(detail.state());
        result.setInstanceCreatedAt(detail.createdAt()); result.setInstanceUpdatedAt(detail.updatedAt());
        result.setCandidateCount(detail.candidateCount()); result.setHasCandidateRelease(detail.hasCandidateRelease());
        ReleaseSummary live = detail.liveRelease();
        // NOT NULL at the column, so an instance with nothing live stores the empty list.
        result.setLiveLimitationFlags(List.of());
        if (live != null) {
            result.setLiveReleaseId(live.releaseId()); result.setLiveReleaseNumber(live.releaseNumber());
            result.setLiveProvenance(live.provenance()); result.setLiveManifestVersion(live.manifestVersion());
            result.setLiveProvider(live.provider()); result.setLiveModel(live.model());
            result.setLiveCollectionCount(live.collectionCount()); result.setLiveLimitationCode(live.limitationCode());
            result.setLiveLimitationFlags(live.limitationFlags()); result.setLiveCreatedAt(live.createdAt());
        }
        return result;
    }

    /**
     * The replay of a stored mutation, rebuilt into the response the original call returned.
     *
     * <p>A row written for an instance with nothing live carries a null release id and a null
     * release number; {@link ReleaseSummary#releaseNumber()} is a primitive, so reconstructing one
     * unconditionally would unbox null and answer a replay with a 500. Both are checked because
     * either alone being absent means there is no release to describe.
     */
    static InstanceDetail detail(LabInstanceCommandResult result) {
        ReleaseSummary live = result.getLiveReleaseId() == null || result.getLiveReleaseNumber() == null
                ? null
                : new ReleaseSummary(result.getLiveReleaseId(), result.getLiveReleaseNumber(),
                result.getLiveProvenance(), true, result.getLiveManifestVersion(), result.getLiveProvider(),
                result.getLiveModel(), result.getLiveCollectionCount(), result.getLiveLimitationCode(),
                result.getLiveLimitationFlags(), result.getLiveCreatedAt());
        return new InstanceDetail(result.getBrainId(), result.getInstanceSlug(), result.getDisplayName(),
                result.getPurpose(), result.getState(), result.getLiveReleaseNumber(), result.getCandidateCount(),
                result.getLiveManifestVersion(), result.getLiveProvider(), result.getLiveModel(),
                result.getLiveCollectionCount(), result.isHasCandidateRelease(), result.getLiveLimitationCode(),
                result.getLiveLimitationFlags(), result.getInstanceCreatedAt(), result.getInstanceUpdatedAt(), live);
    }

    static ReleaseSummary release(ResolvedInstanceRelease resolved) {
        ManifestMetadata metadata = metadata(resolved.manifest());
        LabInstanceRelease release = resolved.release();
        return new ReleaseSummary(release.getId(), release.getReleaseNumber(),
                release.getProvenanceMode().name(), resolved.live(), metadata.manifestVersion(),
                metadata.provider(), metadata.model(), metadata.collectionCount(), metadata.limitationCode(),
                metadata.limitationFlags(), release.getCreatedAt());
    }

    private static ManifestMetadata metadata(DecodedInstanceManifest decoded) {
        if (decoded instanceof DecodedInstanceManifest.V2 v2) {
            InstanceReleaseManifest manifest = v2.manifest();
            return new ManifestMetadata(manifest.manifestVersion(), manifest.model().provider(),
                    manifest.model().model(), manifest.corpus().collections().size(), null, List.of());
        }
        if (decoded instanceof DecodedInstanceManifest.V1Income v1) {
            LabReleaseManifest.PrototypeLimitations limitations = v1.manifest().prototypeLimitations();
            return new ManifestMetadata(1, null, null, null, limitations.code(),
                    List.copyOf(limitations.liveDependencies()));
        }
        throw new InstanceAdminController.InstanceAdminException(
                InstanceAdminController.InstanceAdminException.Code.MANIFEST_UNSUPPORTED);
    }

    private record ManifestMetadata(Integer manifestVersion, String provider, String model,
                                    Integer collectionCount, String limitationCode,
                                    List<String> limitationFlags) {
        private static ManifestMetadata empty() {
            return new ManifestMetadata(null, null, null, null, null, List.of());
        }
    }
}
