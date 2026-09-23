package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabRunDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** The immutable engine-result identities pinned by each run. */
public interface LabRunDocumentRepository extends JpaRepository<LabRunDocument, UUID> {

    List<LabRunDocument> findByRunId(UUID runId);

    /** Ascending revision history for one engine package; matches {@code idx_lab_run_doc_revision}. */
    List<LabRunDocument> findByEnginePackageIdOrderByPackageRevisionAsc(UUID enginePackageId);

    /** FK-safe purge step: run documents are removed before their run. */
    long deleteByRunId(UUID runId);
}
