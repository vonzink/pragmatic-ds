package com.pragmaticds.docengine.extraction.repo;

import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code extraction_schema} reads, and the two TENANT-SCOPED writes schema authoring needs. Same
 * explicit visibility as {@code ClassificationRulePackRepository}: own-org rows plus global
 * built-ins. Which of the visible schemas actually APPLIES (org shadows global per type, highest
 * version wins) is {@code ExtractionSchemaLoader}'s rule — the repository only answers what this
 * org may see.
 *
 * <p>Every write here carries {@code org_id = :orgId} in its own predicate and is never expressed
 * over the visibility query, so a global built-in ({@code org_id IS NULL}) is unreachable from any
 * tenant session by the query text alone — before RLS, which refuses it a second time
 * ({@code extraction_schema_update USING (org_id = current_org())}, and {@code NULL = <anything>}
 * is never TRUE). Authoring SHADOWS a built-in; it can never mutate one.
 */
public interface ExtractionSchemaRepository extends JpaRepository<ExtractionSchema, UUID> {

    /** Active schemas visible to this org: own rows and global built-ins. */
    @Query(
            """
            select s from ExtractionSchema s
            where s.active = true and (s.orgId = :orgId or s.orgId is null)
            """)
    List<ExtractionSchema> findActiveVisibleTo(@Param("orgId") UUID orgId);

    /**
     * Exact producing-schema identities for a frozen machine snapshot. Unlike the active-loader
     * query, this intentionally includes inactive rows: deactivation must not erase the identity
     * that an already-persisted field cites.
     */
    @Query(
            """
            select s from ExtractionSchema s
            where s.id in :ids and (s.orgId = :orgId or s.orgId is null)
            """)
    List<ExtractionSchema> findExactVisibleTo(
            @Param("ids") Collection<UUID> ids, @Param("orgId") UUID orgId);

    /**
     * Every schema this org OWNS for one document type, active or retired — the authoring path's
     * version ledger.
     *
     * <p>Retired rows are included on purpose, and that is the whole point of the query. A version
     * number is not merely a label, it is the identity {@code extracted_field.schema_id} cites; if
     * authoring only looked at ACTIVE rows, an author could retire {@code 2.0.0} and then re-author
     * a DIFFERENT definition under that same number, and every field already extracted would
     * silently start describing a schema it was never produced by. Global rows are excluded because
     * they are not this org's to number: an org's first tenant schema may be {@code 1.0.0} even
     * where a global {@code 1.3.0} exists, since shadowing replaces the global scope wholesale
     * rather than continuing its sequence.
     */
    @Query(
            """
            select s from ExtractionSchema s
            where s.orgId = :orgId and s.documentTypeCode = :documentTypeCode
            """)
    List<ExtractionSchema> findOwnedByType(
            @Param("orgId") UUID orgId, @Param("documentTypeCode") String documentTypeCode);

    /**
     * Retires this org's active schemas for one document type — the {@code UPDATE ... SET is_active
     * = false} half of the supersession dance every seed migration since V10 performs, expressed in
     * the same order and with the same meaning.
     *
     * <p>Retirement, never deletion or in-place edit: {@code extracted_field.schema_id} is a foreign
     * key onto this row, so a deleted version breaks provenance for every value it produced, and a
     * rewritten one silently changes what those values MEAN (V12's own note, design D4).
     *
     * <p>NATIVE, not JPQL, and deliberately so: {@link ExtractionSchema} is {@code @Immutable} with
     * {@code updatable = false} on every column — a stance worth keeping, because it is what stops
     * a stray managed-entity mutation from rewriting a shipped schema at flush time. Retirement is
     * the ONE sanctioned exception, so it goes around the mapping in exactly one visible place
     * rather than by loosening the entity for everybody. It also lets {@code updated_at} move, which
     * the mapping does not model at all.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(
            value =
                    """
                    UPDATE extraction_schema
                       SET is_active = false, updated_at = now()
                     WHERE org_id = :orgId
                       AND document_type_code = :documentTypeCode
                       AND is_active
                    """,
            nativeQuery = true)
    int retireOwnedActive(
            @Param("orgId") UUID orgId, @Param("documentTypeCode") String documentTypeCode);
}
