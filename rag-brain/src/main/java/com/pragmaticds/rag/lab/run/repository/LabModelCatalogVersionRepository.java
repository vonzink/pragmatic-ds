package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabModelCatalogVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Catalog versions are append-only; a matching hash is reused rather than rewritten. */
public interface LabModelCatalogVersionRepository
        extends JpaRepository<LabModelCatalogVersion, UUID> {

    Optional<LabModelCatalogVersion> findByCatalogSha256(String catalogSha256);
}
