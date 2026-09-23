package com.pragmaticds.docengine.classification.repo;

import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only classification attempts. Derived queries travel through {@code @TenantId}
 * filtering; the supersession bulk update carries an explicit org guard because bulk JPQL does
 * not (the same rule PageRepository documents).
 */
public interface ClassificationResultRepository extends JpaRepository<ClassificationResult, UUID> {

    Optional<ClassificationResult> findBySubjectTypeAndSubjectIdAndCurrentTrue(
            String subjectType, UUID subjectId);

    List<ClassificationResult> findBySubjectTypeAndSubjectIdInAndCurrentTrue(
            String subjectType, Collection<UUID> subjectIds);

    /** Full append-only history of one subject, oldest first. */
    List<ClassificationResult> findBySubjectTypeAndSubjectIdOrderByCreatedAt(
            String subjectType, UUID subjectId);

    /**
     * The is_current supersession helper: flips every current row for the subject off so exactly
     * one new row can become current. The flip is the ONLY mutation this table ever sees.
     */
    @Modifying
    @Query(
            """
            update ClassificationResult r set r.current = false
            where r.orgId = :orgId and r.subjectType = :subjectType
              and r.subjectId = :subjectId and r.current = true
            """)
    void supersedeCurrent(
            @Param("orgId") UUID orgId,
            @Param("subjectType") String subjectType,
            @Param("subjectId") UUID subjectId);

    /**
     * Retention purge: classification_result.subject_id is POLYMORPHIC with NO foreign key
     * (subject_type PAGE → page.id, LOGICAL_DOCUMENT → logical_document.id), so a package's results
     * are deleted by subject id across both kinds — and this MUST run before the pages and logical
     * documents it references are deleted. Explicit org guard (bulk JPQL bypasses {@code @TenantId});
     * returns the deleted count for the purge audit.
     */
    @Modifying
    @Query(
            """
            delete from ClassificationResult r
            where r.orgId = :orgId
              and ((r.subjectType = 'PAGE'
                    and r.subjectId in (select p.id from Page p where p.packageId = :packageId))
                or (r.subjectType = 'LOGICAL_DOCUMENT'
                    and r.subjectId in (select d.id from LogicalDocument d where d.packageId = :packageId)))
            """)
    int deleteByPackageIdAndOrgId(
            @Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
