package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.AnalyzerPromptRevision;
import com.pragmaticds.rag.domain.AnalyzerPromptRevision.State;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalyzerPromptRevisionRepository extends JpaRepository<AnalyzerPromptRevision, UUID> {

    Optional<AnalyzerPromptRevision> findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
            UUID brainId, String analyzerSlug, State state);

    List<AnalyzerPromptRevision> findTop20ByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
            UUID brainId, String analyzerSlug, State state);

    void deleteByBrainIdAndAnalyzerSlugAndState(UUID brainId, String analyzerSlug, State state);
}
