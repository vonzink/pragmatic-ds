package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabReleaseEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A promotion gate asks for an exact scenario-set version, never "the latest evaluation": a
 * release that passed version 1 has not passed version 2.
 */
public interface LabReleaseEvaluationRepository extends JpaRepository<LabReleaseEvaluation, UUID> {

    Optional<LabReleaseEvaluation> findByReleaseIdAndScenarioSetIdAndScenarioSetVersion(
            UUID releaseId, String scenarioSetId, int scenarioSetVersion);

    List<LabReleaseEvaluation> findByBrainIdAndReleaseIdOrderByCreatedAtDesc(
            UUID brainId, UUID releaseId);
}
