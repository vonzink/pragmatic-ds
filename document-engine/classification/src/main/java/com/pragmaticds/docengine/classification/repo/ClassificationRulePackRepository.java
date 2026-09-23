package com.pragmaticds.docengine.classification.repo;

import com.pragmaticds.docengine.classification.domain.ClassificationRulePack;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code classification_rule_pack} reads. Same explicit visibility as {@link
 * DocumentTypeRepository}: own-org rows plus global built-ins. Which of the visible packs actually
 * APPLIES (org shadows global per type, highest version wins) is {@code RulePackLoader}'s rule —
 * the repository only answers what this org may see.
 */
public interface ClassificationRulePackRepository
        extends JpaRepository<ClassificationRulePack, UUID> {

    /** Active packs visible to this org: own rows and global built-ins. */
    @Query(
            """
            select p from ClassificationRulePack p
            where p.active = true and (p.orgId = :orgId or p.orgId is null)
            """)
    List<ClassificationRulePack> findActiveVisibleTo(@Param("orgId") UUID orgId);
}
