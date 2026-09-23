package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabDocumentRegistrationSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reads a registration's resolved source set back in the caller's selection order. */
public interface LabDocumentRegistrationSourceRepository
        extends JpaRepository<LabDocumentRegistrationSource, LabDocumentRegistrationSource.Id> {

    List<LabDocumentRegistrationSource> findByIdRegistrationIdOrderBySourcePositionAsc(
            UUID registrationId);
}
