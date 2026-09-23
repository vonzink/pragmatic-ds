package com.pragmaticds.docengine.extraction.repo;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtractedFieldRepository extends JpaRepository<ExtractedField, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<ExtractedField> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * A document's current OCCURRENCES in stable order — the API projection. Field name first,
     * then the repeating-group key, because a grouped field has one row per occurrence and
     * sorting by name alone left their order undefined: "property A" was whichever row the
     * planner returned first, and an update rewrites the tuple, so ordinary review traffic
     * reshuffles it.
     *
     * <p>Written out rather than derived because the null position is a DECISION and a derived
     * {@code OrderByFieldNameAscGroupKeyAsc} would inherit Postgres' ASC NULLS LAST silently. An
     * unkeyed occurrence sorts FIRST within its field name — the same order
     * {@code AbstractExtractionIT.currentOccurrences} reads the table in, and the same order
     * {@code PackageExportController} sorts the export with, so the two read paths cannot drift
     * apart. Ungrouped documents are unaffected: every key is null, so the order stays exactly
     * alphabetical, which is what {@code DocumentFieldsApiIT}'s positional assertions pin.
     */
    @Query(
            """
            select f from ExtractedField f
            where f.logicalDocumentId = :logicalDocumentId and f.current = true
            order by f.fieldName asc, f.groupKey asc nulls first
            """)
    List<ExtractedField> findCurrentOccurrencesByLogicalDocumentId(
            @Param("logicalDocumentId") UUID logicalDocumentId);

    /** Batch variant for the package export — one query for every document's current fields. */
    List<ExtractedField> findByLogicalDocumentIdInAndCurrentTrue(
            Collection<UUID> logicalDocumentIds);

    /**
     * EXTRACTING retry idempotency: clear a package's fields before re-extracting (evidence goes
     * first — children before parents). Explicit org guard — bulk JPQL does not travel through
     * {@code @TenantId} filtering.
     */
    @Modifying
    @Query(
            """
            delete from ExtractedField f
            where f.orgId = :orgId and f.logicalDocumentId in
                (select d.id from LogicalDocument d where d.packageId = :packageId)
            """)
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);

    /**
     * Every field id of a package (any {@code is_current}) — the retention purge needs these as
     * {@code review_decision} subject ids BEFORE the fields are deleted, since a correction's
     * decision references the field id with no cascading FK.
     */
    @Query(
            """
            select f.id from ExtractedField f
            where f.orgId = :orgId and f.logicalDocumentId in
                (select d.id from LogicalDocument d where d.packageId = :packageId)
            """)
    List<UUID> findIdsByPackageIdAndOrgId(
            @Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
