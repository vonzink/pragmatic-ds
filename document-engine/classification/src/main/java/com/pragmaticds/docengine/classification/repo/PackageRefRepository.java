package com.pragmaticds.docengine.classification.repo;

import com.pragmaticds.docengine.classification.domain.PackageRef;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Org-guarded package existence checks for the classification web layer. */
public interface PackageRefRepository extends JpaRepository<PackageRef, UUID> {

    /** The house rule: id AND org, never findById — and soft-deleted packages are absent. */
    Optional<PackageRef> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);
}
