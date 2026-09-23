package com.pragmaticds.docengine.ingestion.domain;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One upload session — the unit a user submits and later reviews (docs/DATA_MODEL.md 2,
 * V2__ingestion.sql).
 *
 * <p>{@code loanId} is a plain UUID, not a JPA association: loans belong to host-app and the
 * platform module; ingestion only records which loan a package was filed under.
 */
@Entity
@Table(name = "document_package")
public class DocumentPackage extends TenantScopedEntity {

    @Column(name = "loan_id")
    private UUID loanId;

    @Column(name = "name")
    private String name;

    /** Sum of the page counts known at upload; images contribute nothing until Phase 2 normalizes them. */
    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.NOT_REVIEWED;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Set on soft delete from the retention policy; the purge job keys off it. */
    @Column(name = "purge_after")
    private Instant purgeAfter;

    protected DocumentPackage() {}

    public DocumentPackage(UUID loanId, String name) {
        this.loanId = loanId;
        this.name = name;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    /**
     * Soft delete: tombstone the package now and record when it becomes eligible for permanent
     * purge. The rows and blobs survive until the retention purge job runs — this only flips the
     * package out of every read path (see the {@code deleted_at IS NULL} finders). Idempotency is
     * the caller's job: it loads via {@code findByIdAndOrgIdAndDeletedAtIsNull}, so a
     * second delete never reaches here.
     */
    public void softDelete(Instant purgeAfter) {
        this.deletedAt = Instant.now();
        this.purgeAfter = purgeAfter;
    }

    public UUID getLoanId() {
        return loanId;
    }

    public String getName() {
        return name;
    }

    public int getPageCount() {
        return pageCount;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getPurgeAfter() {
        return purgeAfter;
    }
}
