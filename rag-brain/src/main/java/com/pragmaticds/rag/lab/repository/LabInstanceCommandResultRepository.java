package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabInstanceCommandResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LabInstanceCommandResultRepository extends JpaRepository<LabInstanceCommandResult, UUID> {
    Optional<LabInstanceCommandResult> findByIdAndBrainId(UUID id, UUID brainId);
}
