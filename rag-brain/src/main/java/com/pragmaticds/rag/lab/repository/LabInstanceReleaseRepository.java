package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Brain-scoped access to immutable release snapshots.
 *
 * <p>Every finder takes {@code brainId} first: there is deliberately no lookup that can return
 * another brain's release, so a leaked release UUID is not an access path.
 */
public interface LabInstanceReleaseRepository extends JpaRepository<LabInstanceRelease, UUID> {

    Optional<LabInstanceRelease> findByIdAndBrainIdAndInstanceSlug(
            UUID id, UUID brainId, String instanceSlug);

    Optional<LabInstanceRelease> findByBrainIdAndInstanceSlugAndManifestSha256(
            UUID brainId, String instanceSlug, String manifestSha256);

    List<LabInstanceRelease> findByBrainIdAndInstanceSlugOrderByReleaseNumberAsc(
            UUID brainId, String instanceSlug);

    List<LabInstanceRelease> findAllByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(
            UUID brainId, String instanceSlug);

    List<LabInstanceRelease> findAllByBrainIdOrderByInstanceSlugAscReleaseNumberDesc(UUID brainId);

    Optional<LabInstanceRelease> findFirstByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(
            UUID brainId, String instanceSlug);

    @Query(value = "select id, manifest::text as manifest_json from lab_instance_release where id in (:ids)",
            nativeQuery = true)
    List<ExactManifestJson> findExactManifestJsonByIdIn(@Param("ids") List<UUID> ids);

    interface ExactManifestJson {
        UUID getId();
        String getManifestJson();
    }
}
