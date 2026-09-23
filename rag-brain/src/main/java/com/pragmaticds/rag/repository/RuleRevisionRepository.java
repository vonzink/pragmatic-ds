package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.RuleRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleRevisionRepository extends JpaRepository<RuleRevision, UUID> {

    Optional<RuleRevision> findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(UUID brainId, String ruleKey);

    List<RuleRevision> findTop20ByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(UUID brainId, String ruleKey);
}
