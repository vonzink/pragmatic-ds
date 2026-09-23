package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One Lab run's terminal analysis output, stored as AES-256-GCM ciphertext and nothing else.
 *
 * <p>There is no plaintext field here and no plaintext column in {@code lab_run_payload}: report
 * text, structured findings, and citations exist in the database only inside {@link #getCiphertext()}.
 * The nonce is per record; the associated data that authenticates the ciphertext is rebuilt from
 * this row's own brain/run/record/type identity, so a row copied to another run cannot decrypt.
 *
 * <p>Byte arrays are defensively copied on both the way in and the way out, so a caller cannot
 * mutate stored ciphertext through a retained reference.
 *
 * <p><b>Append-only in the mapping, not only in the database.</b> V34's {@code BEFORE UPDATE}
 * trigger refuses any rewrite of this table with {@code LAB_ROW_IMMUTABLE}, so a mutation of a
 * managed instance would not merely be wrong — it would roll back the whole surrounding
 * transaction. {@code @Immutable} plus {@code updatable = false} keeps Hibernate from composing
 * an {@code UPDATE} at all; see commit {@code 6517c35} and {@link LabInstanceRelease} for the
 * dirty-check round-trip that made this declaration load-bearing.
 */
@Entity
@Immutable
@Table(name = "lab_run_payload")
public class LabRunPayload {

    /** What this ciphertext is. One payload of each type per run. */
    public enum PayloadType {
        ANALYSIS_OUTPUT,
        /** The pinned execution provenance a generalized instance run was produced under. */
        RUN_PROVENANCE
    }

    /** The only algorithm V34 will store. */
    public static final String ALGORITHM = "AES-256-GCM";

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payload_type", nullable = false, updatable = false, length = 32)
    private PayloadType payloadType;

    @Column(name = "cipher_algorithm", nullable = false, updatable = false, length = 24)
    private String cipherAlgorithm = ALGORITHM;

    @Column(nullable = false, updatable = false)
    private byte[] nonce;

    @Column(nullable = false, updatable = false)
    private byte[] ciphertext;

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
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public PayloadType getPayloadType() { return payloadType; }
    public void setPayloadType(PayloadType payloadType) { this.payloadType = payloadType; }
    public String getCipherAlgorithm() { return cipherAlgorithm; }
    public void setCipherAlgorithm(String cipherAlgorithm) { this.cipherAlgorithm = cipherAlgorithm; }

    public byte[] getNonce() { return nonce == null ? null : nonce.clone(); }
    public void setNonce(byte[] nonce) { this.nonce = nonce == null ? null : nonce.clone(); }
    public byte[] getCiphertext() { return ciphertext == null ? null : ciphertext.clone(); }
    public void setCiphertext(byte[] ciphertext) { this.ciphertext = ciphertext == null ? null : ciphertext.clone(); }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
