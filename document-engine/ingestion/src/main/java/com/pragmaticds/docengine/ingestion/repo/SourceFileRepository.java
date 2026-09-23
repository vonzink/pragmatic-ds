package com.pragmaticds.docengine.ingestion.repo;

import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<SourceFile> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Retention purge: a package's files, deleted AFTER page and parser_output (both carry a plain
     * FK to source_file) and before the package root. Explicit org guard — bulk JPQL does not
     * travel through {@code @TenantId} filtering. Returns the deleted count for the purge audit.
     */
    @Modifying
    @Query("delete from SourceFile f where f.packageId = :packageId and f.orgId = :orgId")
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);

    /** Ordinal is the stable review ordering; insertion order is an accident of upload threading. */
    List<SourceFile> findByPackageIdOrderByOrdinal(UUID packageId);

    /**
     * Cross-package duplicate probe: the same paystub legitimately appears in multiple loan files,
     * so a hit here is a WARNING to the caller, never a rejection (unlike the same sha twice within
     * one package, which the DB uniquely rejects).
     */
    Optional<SourceFile> findFirstByOrgIdAndSha256AndPackageIdNot(
            UUID orgId, String sha256, UUID packageId);
}
