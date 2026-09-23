package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.ParserOutput;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParserOutputRepository extends JpaRepository<ParserOutput, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<ParserOutput> findByIdAndOrgId(UUID id, UUID orgId);

    List<ParserOutput> findBySourceFileIdOrderByCreatedAtAsc(UUID sourceFileId);

    List<ParserOutput> findByPageIdOrderByCreatedAtAsc(UUID pageId);

    /**
     * The blob keys of a package's parser outputs. parser_output is NOT package-scoped in the
     * schema (D10: it hangs off source_file OR page), so enumeration is a join through both — the
     * retention purge needs these keys BEFORE it deletes the rows so it can then delete the blobs.
     */
    @Query(
            """
            select po.payloadStorageKey from ParserOutput po
            where po.orgId = :orgId
              and (po.sourceFileId in (select f.id from SourceFile f where f.packageId = :packageId)
                   or po.pageId in (select p.id from Page p where p.packageId = :packageId))
            """)
    List<String> findPayloadKeysByPackageIdAndOrgId(
            @Param("packageId") UUID packageId, @Param("orgId") UUID orgId);

    /**
     * Retention purge: a package's parser outputs, deleted BEFORE page and source_file (it carries
     * a plain FK to both). Explicit org guard — bulk JPQL does not travel through {@code @TenantId}
     * filtering. Returns the deleted count for the purge audit.
     */
    @Modifying
    @Query(
            """
            delete from ParserOutput po
            where po.orgId = :orgId
              and (po.sourceFileId in (select f.id from SourceFile f where f.packageId = :packageId)
                   or po.pageId in (select p.id from Page p where p.packageId = :packageId))
            """)
    int deleteByPackageIdAndOrgId(
            @Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
