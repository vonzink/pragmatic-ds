package com.pragmaticds.docengine.ingestion.repo;

import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentPackageRepository extends JpaRepository<DocumentPackage, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<DocumentPackage> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The read-path load: a soft-deleted (tombstoned) package reads as ABSENT. Every content read
     * — package, pages, render, file bytes — resolves accessibility through this, so a deleted
     * package's borrower data can never be served.
     */
    Optional<DocumentPackage> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    /** Serializes package revision allocation and refuses to lock a tombstoned package. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select p from DocumentPackage p where p.id = :id and p.orgId = :orgId"
                    + " and p.deletedAt is null")
    Optional<DocumentPackage> lockAccessibleByIdAndOrgId(
            @Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * The retention purge candidates for the CURRENTLY bound tenant: tombstoned and past their
     * purge deadline. Tenant-scoped by {@code @TenantId} (the sweep binds one org at a time), so no
     * explicit org param — the derived query travels through tenant filtering the way bulk JPQL
     * does not.
     */
    List<DocumentPackage> findByDeletedAtIsNotNullAndPurgeAfterLessThanEqual(Instant cutoff);

    /**
     * Distinct orgs that currently have at least one package due for purge. The ONE deliberately
     * cross-tenant read in the whole lifecycle: the scheduled sweep has no bound tenant, so it
     * discovers WHICH orgs to sweep, then binds each and does all package selection and deletion
     * tenant-scoped. Native (so {@code @TenantId} does not filter it to nothing); under a deployed
     * non-owner role it must run on a connection permitted to read this maintenance view — see
     * {@code RetentionPurgeJob}.
     */
    @Query(
            value =
                    "SELECT DISTINCT org_id FROM document_package"
                            + " WHERE deleted_at IS NOT NULL AND purge_after <= :cutoff",
            nativeQuery = true)
    List<UUID> findOrgsWithDuePackages(@Param("cutoff") Instant cutoff);

    /**
     * The purge root delete. Explicit org guard — bulk JPQL does not travel through {@code @TenantId}
     * filtering — and it is the LAST delete in the FK-safe order (every child is already gone).
     */
    @Modifying
    @Query("delete from DocumentPackage p where p.id = :id and p.orgId = :orgId")
    int deleteByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);
}
