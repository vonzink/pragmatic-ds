package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabConnectorRunGroupContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Connector ownership of run groups. Written once per group; the database refuses updates. */
public interface LabConnectorRunGroupContextRepository
        extends JpaRepository<LabConnectorRunGroupContext, UUID> {
}
