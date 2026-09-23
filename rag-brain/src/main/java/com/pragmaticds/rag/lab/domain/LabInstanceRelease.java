package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable snapshot of the analyzer contract a Lab run executes against.
 *
 * <p>The manifest is analyzer <em>configuration</em> — prompt, output schema, compatibility
 * policy, calculator method list, prototype limitations — never borrower data. There is no
 * setter-driven update path that matters: V34 installs a {@code BEFORE UPDATE} trigger, so an
 * attempt to rewrite a persisted release row is refused by the database itself.
 *
 * <p><b>The mapping has to say that too, and not merely the comment above.</b> {@code manifest}
 * is a mutable {@code Map} behind a JSON type, so Hibernate deep-copies it into the dirty-check
 * snapshot by round-tripping it through the format mapper. A {@code BigDecimal} comes back as a
 * {@code Double} and a {@code Long} as an {@code Integer}, so the snapshot never equals the value
 * it was copied from and every flush of a release believes the row changed. An insert therefore
 * scheduled an {@code UPDATE} of every column in the same flush, V34's trigger refused it with
 * {@code LAB_ROW_IMMUTABLE}, and creating an instance failed. {@code @Immutable} plus
 * {@code updatable = false} is what stops the UPDATE being composed at all — the same instrument
 * every other append-only entity here already carries.
 */
@Entity
@Immutable
@Table(name = "lab_instance_release")
public class LabInstanceRelease {

    /** Why this release row exists: the pointed production snapshot, or a drift candidate. */
    public enum ProvenanceMode {
        PRODUCTION,
        CANDIDATE
    }

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "instance_slug", nullable = false, updatable = false, length = 32)
    private String instanceSlug;

    @Column(name = "release_number", nullable = false, updatable = false)
    private int releaseNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance_mode", nullable = false, updatable = false, length = 24)
    private ProvenanceMode provenanceMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> manifest;

    @Column(name = "manifest_sha256", nullable = false, updatable = false, length = 64)
    private String manifestSha256;

    @Column(name = "predecessor_release_id", updatable = false)
    private UUID predecessorReleaseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

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
    public void setId(UUID id) { this.id = id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getInstanceSlug() { return instanceSlug; }
    public void setInstanceSlug(String instanceSlug) { this.instanceSlug = instanceSlug; }
    public int getReleaseNumber() { return releaseNumber; }
    public void setReleaseNumber(int releaseNumber) { this.releaseNumber = releaseNumber; }
    public ProvenanceMode getProvenanceMode() { return provenanceMode; }
    public void setProvenanceMode(ProvenanceMode provenanceMode) { this.provenanceMode = provenanceMode; }
    /**
     * The manifest as Hibernate deserialized it — <b>not</b> a faithful copy of what was written.
     *
     * <p>The format mapper returns a {@code BigDecimal} as a {@code Double} and a {@code Long} as
     * an {@code Integer}, and a {@code Double} cannot carry {@code 0.250}, so this map no longer
     * canonicalizes to {@link #getManifestSha256()}. Decode a release through
     * {@code com.pragmaticds.rag.lab.instance.VerifiedInstanceReleaseReader}, which reads the exact
     * JSONB text, not through this accessor.
     */
    public Map<String, Object> getManifest() { return manifest; }
    public void setManifest(Map<String, Object> manifest) { this.manifest = manifest; }
    public String getManifestSha256() { return manifestSha256; }
    public void setManifestSha256(String manifestSha256) { this.manifestSha256 = manifestSha256; }
    public UUID getPredecessorReleaseId() { return predecessorReleaseId; }
    public void setPredecessorReleaseId(UUID predecessorReleaseId) { this.predecessorReleaseId = predecessorReleaseId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
