package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainConnectorEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BrainConnectorEventRepository extends JpaRepository<BrainConnectorEvent, UUID> {
    List<BrainConnectorEvent> findTop25ByConnectorClientIdOrderByCreatedAtDesc(UUID connectorClientId);
}
