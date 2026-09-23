package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The value-free binding of one brain/instance to the exact Document Engine package and job.
 *
 * <p>Deliberately has no field for a filename, byte, declared MIME type, source URL, engine
 * storage key, content hash, or extracted value: UUIDs and lifecycle timestamps only. The engine
 * remains the document authority; this row records only <em>which</em> engine package a brain's
 * Lab instance is allowed to read.
 *
 * <p>A package belongs to exactly one brain, enforced by {@link LabEnginePackageBinding} and a
 * composite foreign key from this row, so a cross-brain package substitution fails on insert
 * before any engine read is attempted. Inside the owning brain the package may be registered by
 * several instances, which is what lets two instances be compared against one immutable parse.
 *
 * <p>The two modes are structurally exclusive, enforced by a database CHECK rather than by
 * convention. {@code UPLOAD_ONE} is the admin/fallback path and carries exactly one engine source
 * id. {@code EXISTING_PARSE} selects an already-immutable package revision and carries the pinned
 * revision plus the digest of the reconciled source set instead, with the individual sources in
 * {@link LabDocumentRegistrationSource}.
 */
@Entity
@Table(name = "lab_document_registration")
public class LabDocumentRegistration {

    /** How this registration obtained its parse. Structurally exclusive; see the class javadoc. */
    public enum RegistrationMode {
        UPLOAD_ONE,
        EXISTING_PARSE
    }

    @Id
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "instance_slug", nullable = false, length = 32)
    private String instanceSlug;

    @Column(name = "engine_package_id", nullable = false)
    private UUID enginePackageId;

    @Column(name = "engine_job_id", nullable = false)
    private UUID engineJobId;

    /** Set for UPLOAD_ONE only; an existing parse records its sources as child rows instead. */
    @Column(name = "engine_source_id")
    private UUID engineSourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_mode", nullable = false, length = 24)
    private RegistrationMode registrationMode = RegistrationMode.UPLOAD_ONE;

    /** The exact immutable package revision an EXISTING_PARSE selection pinned. */
    @Column(name = "selected_revision")
    private Integer selectedRevision;

    /** Digest of the reconciled source set an EXISTING_PARSE selection verified. */
    @Column(name = "source_set_sha256", length = 64)
    private String sourceSetSha256;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt;

    @Column(name = "last_read_at")
    private OffsetDateTime lastReadAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (registeredAt == null) {
            registeredAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getInstanceSlug() { return instanceSlug; }
    public void setInstanceSlug(String instanceSlug) { this.instanceSlug = instanceSlug; }
    public UUID getEnginePackageId() { return enginePackageId; }
    public void setEnginePackageId(UUID enginePackageId) { this.enginePackageId = enginePackageId; }
    public UUID getEngineJobId() { return engineJobId; }
    public void setEngineJobId(UUID engineJobId) { this.engineJobId = engineJobId; }
    public UUID getEngineSourceId() { return engineSourceId; }
    public void setEngineSourceId(UUID engineSourceId) { this.engineSourceId = engineSourceId; }
    public RegistrationMode getRegistrationMode() { return registrationMode; }
    public void setRegistrationMode(RegistrationMode registrationMode) { this.registrationMode = registrationMode; }
    public Integer getSelectedRevision() { return selectedRevision; }
    public void setSelectedRevision(Integer selectedRevision) { this.selectedRevision = selectedRevision; }
    public String getSourceSetSha256() { return sourceSetSha256; }
    public void setSourceSetSha256(String sourceSetSha256) { this.sourceSetSha256 = sourceSetSha256; }
    public OffsetDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(OffsetDateTime registeredAt) { this.registeredAt = registeredAt; }
    public OffsetDateTime getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(OffsetDateTime lastReadAt) { this.lastReadAt = lastReadAt; }
}
