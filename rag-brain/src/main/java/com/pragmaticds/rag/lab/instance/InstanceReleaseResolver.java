package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The only generalized, read-only path from an instance identity to a stored release. */
public interface InstanceReleaseResolver {
    ResolvedInstanceRelease live(InstanceKey key);
    ResolvedInstanceRelease byId(InstanceKey key, UUID releaseId);
    List<ResolvedInstanceRelease> history(InstanceKey key);

    /** Stable, value-free resolution failures. */
    final class ReleaseResolutionException extends RuntimeException {
        public enum Code { LIVE_RELEASE_NOT_FOUND, RELEASE_NOT_FOUND, RELEASE_SCOPE_MISMATCH }
        private final Code code;
        public ReleaseResolutionException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }
        public Code code() { return code; }
    }
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultInstanceReleaseResolver implements InstanceReleaseResolver {
    private final LabInstanceRepository instances;
    private final LabInstancePointerRepository pointers;
    private final LabInstanceReleaseRepository releases;
    private final VerifiedInstanceReleaseReader manifests;

    DefaultInstanceReleaseResolver(LabInstanceRepository instances,
                                   LabInstancePointerRepository pointers,
                                   LabInstanceReleaseRepository releases,
                                   VerifiedInstanceReleaseReader manifests) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.pointers = Objects.requireNonNull(pointers, "pointers");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedInstanceRelease live(InstanceKey key) {
        LabInstance instance = requireInstance(key);
        LabInstancePointer pointer = pointers.findByBrainIdAndInstanceSlug(key.brainId(), key.slug())
                .orElseThrow(() -> new ReleaseResolutionException(
                        ReleaseResolutionException.Code.LIVE_RELEASE_NOT_FOUND));
        LabInstanceRelease release = scopedPointerTarget(key, pointer);
        return resolved(instance, release, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedInstanceRelease byId(InstanceKey key, UUID releaseId) {
        LabInstance instance = requireInstance(key);
        LabInstanceRelease release = releases.findByIdAndBrainIdAndInstanceSlug(
                        releaseId, key.brainId(), key.slug())
                .orElseThrow(() -> new ReleaseResolutionException(
                        ReleaseResolutionException.Code.RELEASE_NOT_FOUND));
        verifyScope(key, null, release);
        boolean live = pointers.findByBrainIdAndInstanceSlug(key.brainId(), key.slug())
                .map(pointer -> releaseId.equals(scopedPointerTarget(key, pointer).getId())).orElse(false);
        return resolved(instance, release, live);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedInstanceRelease> history(InstanceKey key) {
        LabInstance instance = requireInstance(key);
        UUID liveId = pointers.findByBrainIdAndInstanceSlug(key.brainId(), key.slug())
                .map(pointer -> scopedPointerTarget(key, pointer).getId()).orElse(null);
        List<LabInstanceRelease> rows = releases.findAllByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(key.brainId(), key.slug());
        Map<UUID, DecodedInstanceManifest> decoded = manifests.readAll(rows);
        return rows
                .stream().map(release -> {
                    verifyScope(key, null, release);
                    return new ResolvedInstanceRelease(instance, release, decoded.get(release.getId()), release.getId().equals(liveId));
                }).toList();
    }

    private LabInstance requireInstance(InstanceKey key) {
        return instances.findByBrainIdAndSlug(key.brainId(), key.slug())
                .orElseThrow(() -> new InstanceRegistryService.InstanceException(
                        InstanceRegistryService.InstanceException.Code.INSTANCE_NOT_FOUND));
    }

    private ResolvedInstanceRelease resolved(LabInstance instance, LabInstanceRelease release,
                                             boolean live) {
        DecodedInstanceManifest manifest = manifests.read(release);
        return new ResolvedInstanceRelease(instance, release, manifest, live);
    }

    private LabInstanceRelease scopedPointerTarget(InstanceKey key, LabInstancePointer pointer) {
        verifyPointerScope(key, pointer);
        LabInstanceRelease target = releases.findByIdAndBrainIdAndInstanceSlug(
                        pointer.getProductionReleaseId(), key.brainId(), key.slug())
                .orElseThrow(() -> new ReleaseResolutionException(
                        ReleaseResolutionException.Code.RELEASE_SCOPE_MISMATCH));
        verifyScope(key, pointer, target);
        return target;
    }

    private static void verifyScope(InstanceKey key, LabInstancePointer pointer,
                                    LabInstanceRelease release) {
        boolean releaseMatches = key.brainId().equals(release.getBrainId())
                && key.slug().equals(release.getInstanceSlug());
        boolean pointerMatches = pointer == null || (pointerMatches(key, pointer)
                && pointer.getProductionReleaseId().equals(release.getId()));
        if (!releaseMatches || !pointerMatches) {
            throw new ReleaseResolutionException(ReleaseResolutionException.Code.RELEASE_SCOPE_MISMATCH);
        }
    }

    private static void verifyPointerScope(InstanceKey key, LabInstancePointer pointer) {
        if (!pointerMatches(key, pointer)) {
            throw new ReleaseResolutionException(ReleaseResolutionException.Code.RELEASE_SCOPE_MISMATCH);
        }
    }

    private static boolean pointerMatches(InstanceKey key, LabInstancePointer pointer) {
        return key.brainId().equals(pointer.getBrainId()) && key.slug().equals(pointer.getInstanceSlug());
    }
}
