package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstancePointerId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** The one production release pointer per brain and instance. */
public interface LabInstancePointerRepository
        extends JpaRepository<LabInstancePointer, LabInstancePointerId> {

    Optional<LabInstancePointer> findByBrainIdAndInstanceSlug(UUID brainId, String instanceSlug);

    List<LabInstancePointer> findAllByBrainId(UUID brainId);

    /**
     * Takes the row lock the lazy first-use release bootstrap needs, so two concurrent first
     * requests cannot both write release 1. Spelled as an explicit query rather than a derived
     * name so the lock intent cannot be lost to name parsing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LabInstancePointer p "
            + "where p.brainId = :brainId and p.instanceSlug = :instanceSlug")
    Optional<LabInstancePointer> lockByBrainIdAndInstanceSlug(
            @Param("brainId") UUID brainId, @Param("instanceSlug") String instanceSlug);
}
