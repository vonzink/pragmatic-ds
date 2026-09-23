package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabModelCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Prices are always read through an explicit version — there is no "current price" finder. */
public interface LabModelCatalogEntryRepository
        extends JpaRepository<LabModelCatalogEntry, UUID> {

    List<LabModelCatalogEntry> findByCatalogVersionIdOrderByProviderAscModelAsc(
            UUID catalogVersionId);

    Optional<LabModelCatalogEntry> findByCatalogVersionIdAndProviderAndModel(
            UUID catalogVersionId, String provider, String model);
}
