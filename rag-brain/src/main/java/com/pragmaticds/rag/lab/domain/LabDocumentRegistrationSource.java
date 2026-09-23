package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One engine source a registration resolved to, as identity only.
 *
 * <p>The engine source id, the digest that id was reconciled against, and the caller's selection
 * order. There is deliberately no filename, URL, storage locator, media type, text, or parsed
 * value field, so no code path has anywhere to put source content.
 *
 * <p>{@code contentSha256} is nullable for exactly one reason: V34's upload registrations predate
 * source-digest verification and no digest for them exists anywhere in V1-V37. A null states
 * "never verified" rather than inventing a hash. Every existing-parse selection reconciles each id
 * and digest against the verified envelope before writing, so a null can never mean "skipped".
 */
@Entity
@Table(name = "lab_document_registration_source")
public class LabDocumentRegistrationSource {

    @EmbeddedId
    private Id id;

    @Column(name = "content_sha256", length = 64, updatable = false)
    private String contentSha256;

    @Column(name = "source_position", nullable = false, updatable = false)
    private int sourcePosition;

    protected LabDocumentRegistrationSource() {}

    public LabDocumentRegistrationSource(UUID registrationId, UUID engineSourceId,
                                         String contentSha256, int sourcePosition) {
        this.id = new Id(registrationId, engineSourceId);
        this.contentSha256 = contentSha256;
        this.sourcePosition = sourcePosition;
    }

    public Id getId() { return id; }
    public UUID getRegistrationId() { return id.registrationId; }
    public UUID getEngineSourceId() { return id.engineSourceId; }
    public String getContentSha256() { return contentSha256; }
    public int getSourcePosition() { return sourcePosition; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "registration_id", nullable = false, updatable = false)
        private UUID registrationId;

        @Column(name = "engine_source_id", nullable = false, updatable = false)
        private UUID engineSourceId;

        protected Id() {}

        public Id(UUID registrationId, UUID engineSourceId) {
            this.registrationId = registrationId;
            this.engineSourceId = engineSourceId;
        }

        public UUID getRegistrationId() { return registrationId; }
        public UUID getEngineSourceId() { return engineSourceId; }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return Objects.equals(registrationId, that.registrationId)
                    && Objects.equals(engineSourceId, that.engineSourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(registrationId, engineSourceId);
        }
    }
}
