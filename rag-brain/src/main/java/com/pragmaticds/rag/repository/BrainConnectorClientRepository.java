package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainConnectorClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrainConnectorClientRepository extends JpaRepository<BrainConnectorClient, UUID> {
    Optional<BrainConnectorClient> findByTokenHash(String tokenHash);
    List<BrainConnectorClient> findByBrainIdOrBrainIdIsNull(UUID brainId);
}
