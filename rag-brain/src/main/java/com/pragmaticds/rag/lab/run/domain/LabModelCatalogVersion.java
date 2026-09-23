package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One immutable, configuration-derived price list.
 *
 * <p>Appended when the canonical hash of the configured entries changes and never overwritten, so
 * a run priced months ago can still be re-derived from the exact numbers it was priced against.
 * Editing a price in configuration produces a new version; it does not retroactively restate what
 * anything cost.
 *
 * <p>Declared {@code @Immutable} with {@code updatable = false} columns so Hibernate can never
 * compose an {@code UPDATE} against this append-only row; see commit {@code 6517c35} and
 * {@link com.pragmaticds.rag.lab.domain.LabInstanceRelease} for the dirty-check round-trip that
 * made the declaration load-bearing.
 */
@Entity
@Immutable
@Table(name = "lab_model_catalog_version")
public class LabModelCatalogVersion {

    /**
     * The empty version historical and prototype runs point at. It prices nothing on purpose: a
     * run that was never costed against a versioned catalog has no price.
     */
    public static final UUID LEGACY_UNPRICED =
            UUID.fromString("00000000-0000-4000-8000-00000000f001");

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "catalog_sha256", nullable = false, updatable = false, length = 64)
    private String catalogSha256;

    @Column(name = "entry_count", nullable = false, updatable = false)
    private int entryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LabModelCatalogVersion() {}

    public LabModelCatalogVersion(String catalogSha256, int entryCount) {
        this.catalogSha256 = catalogSha256;
        this.entryCount = entryCount;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public String getCatalogSha256() { return catalogSha256; }
    public int getEntryCount() { return entryCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
