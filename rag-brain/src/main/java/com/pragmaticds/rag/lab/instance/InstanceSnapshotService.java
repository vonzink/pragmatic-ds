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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coherent registry/release snapshots. A brain list is loaded in four bounded queries. */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceSnapshotService {
    private final LabInstanceRepository instances;
    private final LabInstancePointerRepository pointers;
    private final LabInstanceReleaseRepository releases;
    private final VerifiedInstanceReleaseReader manifests;

    public InstanceSnapshotService(LabInstanceRepository instances, LabInstancePointerRepository pointers,
                                   LabInstanceReleaseRepository releases, VerifiedInstanceReleaseReader manifests) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.pointers = Objects.requireNonNull(pointers, "pointers");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<InstanceSnapshot> list(UUID brainId) {
        List<LabInstance> allInstances = instances.findAllByBrainIdOrderByDisplayNameAsc(brainId);
        Map<String, LabInstancePointer> bySlug = new HashMap<>();
        for (LabInstancePointer pointer : pointers.findAllByBrainId(brainId)) {
            bySlug.put(pointer.getInstanceSlug(), pointer);
        }
        Map<String, List<LabInstanceRelease>> history = new HashMap<>();
        for (LabInstanceRelease release : releases.findAllByBrainIdOrderByInstanceSlugAscReleaseNumberDesc(brainId)) {
            history.computeIfAbsent(release.getInstanceSlug(), ignored -> new ArrayList<>()).add(release);
        }
        List<LabInstanceRelease> allRows = history.values().stream().flatMap(List::stream).toList();
        Map<UUID, DecodedInstanceManifest> decoded = manifests.readAll(allRows);
        return allInstances.stream().map(instance -> snapshot(instance, bySlug.get(instance.getSlug()),
                history.getOrDefault(instance.getSlug(), List.of()), decoded)).toList();
    }

    /**
     * One instance, with whatever it currently has live.
     *
     * <p>{@code live} is null when nothing has ever been promoted, and that is a state this read
     * must represent rather than refuse. An instance is registered by
     * {@code InstanceCandidateService.create}, which writes a candidate release and no pointer, so
     * every freshly created instance is release-less until its first promotion. Failing here would
     * make the administration surface — the instance itself, its release list, and therefore the
     * screen that composes that first promotion — unreachable for exactly the instances that need
     * it, while {@code candidateCount} and {@code hasCandidateRelease} already say the release
     * exists.
     *
     * <p>This is deliberately not the same judgement as {@link InstanceReleaseResolver#live}:
     * <em>reading</em> an instance does not require a live release, <em>running</em> one does, and
     * that resolver still refuses a release-less instance.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InstanceSnapshot detail(InstanceKey key) {
        LabInstance instance = instances.findByBrainIdAndSlug(key.brainId(), key.slug())
                .orElseThrow(() -> new InstanceRegistryService.InstanceException(
                        InstanceRegistryService.InstanceException.Code.INSTANCE_NOT_FOUND));
        LabInstancePointer pointer = pointers.findByBrainIdAndInstanceSlug(key.brainId(), key.slug()).orElse(null);
        List<LabInstanceRelease> rows = releases.findAllByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(key.brainId(), key.slug());
        return snapshot(instance, pointer, rows, manifests.readAll(rows));
    }

    private InstanceSnapshot snapshot(LabInstance instance, LabInstancePointer pointer,
                                      List<LabInstanceRelease> rows, Map<UUID, DecodedInstanceManifest> decoded) {
        List<ResolvedInstanceRelease> history = new ArrayList<>();
        UUID liveId = pointer == null ? null : pointer.getProductionReleaseId();
        boolean foundLive = liveId == null;
        for (LabInstanceRelease release : rows) {
            boolean live = release.getId().equals(liveId);
            foundLive |= live;
            history.add(new ResolvedInstanceRelease(instance, release, decoded.get(release.getId()), live));
        }
        if (!foundLive) {
            throw new InstanceReleaseResolver.ReleaseResolutionException(
                    InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_SCOPE_MISMATCH);
        }
        ResolvedInstanceRelease live = history.stream().filter(ResolvedInstanceRelease::live).findFirst().orElse(null);
        return new InstanceSnapshot(instance, live, List.copyOf(history));
    }

    /** {@code live} is null when nothing has been promoted yet; {@code history} may be empty. */
    public record InstanceSnapshot(LabInstance instance, ResolvedInstanceRelease live,
                                   List<ResolvedInstanceRelease> history) {}

    /**
     * The closed, value-free taxonomy for snapshot read failures.
     *
     * <p>{@code LIVE_RELEASE_NOT_FOUND} is no longer thrown on this path — a release-less instance
     * is a representable snapshot, not a failure — and it is kept declared, and mapped by the admin
     * advice, so that a future refusal on this read reaches a caller as a code rather than as
     * exception text.
     */
    public static final class InstanceSnapshotException extends RuntimeException {
        public enum Code { MANIFEST_UNSUPPORTED, LIVE_RELEASE_NOT_FOUND }
        private final Code code;
        public InstanceSnapshotException(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }
}
