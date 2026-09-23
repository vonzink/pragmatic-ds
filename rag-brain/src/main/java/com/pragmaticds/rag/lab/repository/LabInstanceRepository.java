package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Brain-scoped registry access for Lab instance administration. */
public interface LabInstanceRepository extends JpaRepository<LabInstance, UUID> {
    Optional<LabInstance> findByBrainIdAndSlug(UUID brainId, String slug);
    List<LabInstance> findAllByBrainIdOrderByDisplayNameAsc(UUID brainId);
    boolean existsByBrainIdAndSlug(UUID brainId, String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LabInstance i where i.brainId = :brainId and i.slug = :slug")
    Optional<LabInstance> lockByBrainIdAndSlug(@Param("brainId") UUID brainId,
                                                @Param("slug") String slug);
}
