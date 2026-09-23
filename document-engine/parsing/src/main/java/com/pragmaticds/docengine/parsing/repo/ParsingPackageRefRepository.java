package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.ParsingPackageRef;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Existence-and-tenancy lookup only — see {@link ParsingPackageRef}. */
public interface ParsingPackageRefRepository extends JpaRepository<ParsingPackageRef, UUID> {

    /** The house rule, plus soft delete: a deleted package reads as absent. */
    Optional<ParsingPackageRef> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);
}
