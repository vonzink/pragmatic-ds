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
 * One uploaded file within a package (docs/DATA_MODEL.md 2, V2__ingestion.sql).
 *
 * <p>{@code contentType} is what the magic bytes said; {@code declaredContentType} is what the
 * client claimed — kept for audit, never for decisions.
 *
 * <p>Rows are effectively immutable records of the bytes received; {@code updated_at} exists only
 * because {@link TenantScopedEntity} maps it uniformly (V2 carries the column).
 */
@Entity
@Table(name = "source_file")
public class SourceFile extends TenantScopedEntity {

    /**
     * Never visible in a committed row. The real key embeds this entity's generated id, which
     * exists only after persist — but {@code storage_key_original} is NOT NULL and Hibernate
     * captures insert state AT persist, so a new entity carries this placeholder through the
     * queued INSERT and {@link #assignStorageKey} dirty-updates it within the same flush.
     */
    private static final String STORAGE_KEY_PENDING = "PENDING";

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** Position within the upload request — the stable review ordering. */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "original_filename", nullable = false, updatable = false)
    private String originalFilename;

    /** Sniffed from magic bytes, never trusted from the client. */
    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    /** What the client claimed — kept for audit, never for decisions. */
    @Column(name = "declared_content_type", updatable = false)
    private String declaredContentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String sha256;

    @Column(name = "storage_key_original", nullable = false)
    private String storageKeyOriginal;

    /** Written by the Phase 2 normalization worker; always null at upload. */
    @Column(name = "storage_key_normalized")
    private String storageKeyNormalized;

    /**
     * From PdfProbe for PDFs, ImageProbe for images (1 for a JPEG/PNG, the frame count for a
     * multi-page TIFF, and 1 for a HEIC — asserted from the format rather than decoded, because
     * the JVM has no HEIF reader). Nullable only because rows written before images were probed
     * exist.
     */
    @Column(name = "page_count", updatable = false)
    private Integer pageCount;

    /**
     * The file carried PDF encryption on disk yet opened without a password — owner-password-only,
     * which restricts printing/editing but not reading. Such files are accepted and parsed
     * normally; this records the provenance fact for a reviewer, and is never a rejection reason.
     * A file that truly needs a user password is rejected at upload and no row is ever written.
     * Always false for images, which this column does not describe.
     */
    @Column(name = "is_encrypted", nullable = false, updatable = false)
    private boolean encrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "malware_scan_status", nullable = false)
    private MalwareScanStatus malwareScanStatus = MalwareScanStatus.PENDING;

    @Column(name = "uploaded_by", updatable = false)
    private UUID uploadedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected SourceFile() {}

    public SourceFile(
            UUID packageId,
            int ordinal,
            String originalFilename,
            String contentType,
            String declaredContentType,
            long sizeBytes,
            String sha256,
            Integer pageCount,
            boolean encrypted,
            MalwareScanStatus malwareScanStatus) {
        this.packageId = packageId;
        this.ordinal = ordinal;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.declaredContentType = declaredContentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.pageCount = pageCount;
        this.encrypted = encrypted;
        this.malwareScanStatus = malwareScanStatus;
        this.storageKeyOriginal = STORAGE_KEY_PENDING;
    }

    /**
     * The storage key embeds this entity's generated id, so it can only be assigned after persist.
     * Must be called before the transaction commits — the placeholder the constructor sets exists
     * only to satisfy NOT NULL on the queued INSERT.
     */
    public void assignStorageKey(String storageKeyOriginal) {
        this.storageKeyOriginal = storageKeyOriginal;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public String getDeclaredContentType() {
        return declaredContentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKeyOriginal() {
        return storageKeyOriginal;
    }

    public String getStorageKeyNormalized() {
        return storageKeyNormalized;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public MalwareScanStatus getMalwareScanStatus() {
        return malwareScanStatus;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
