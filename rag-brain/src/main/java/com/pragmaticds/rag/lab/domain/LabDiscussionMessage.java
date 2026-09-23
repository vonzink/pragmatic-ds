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
 * One encrypted discussion body — exactly one {@link Role#USER} row and one
 * {@link Role#ASSISTANT} row per exchange, each written once.
 *
 * <p>Ciphertext only, exactly as {@link LabRunPayload}: the question and the answer exist in the
 * database only inside {@link #getCiphertext()}, under a per-record nonce and associated data
 * rebuilt from this row's own identity.
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
@Table(name = "lab_discussion_message")
public class LabDiscussionMessage {

    /** Who authored the encrypted body. */
    public enum Role {
        USER,
        ASSISTANT
    }

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "exchange_id", nullable = false, updatable = false)
    private UUID exchangeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Role role;

    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "cipher_algorithm", nullable = false, updatable = false, length = 24)
    private String cipherAlgorithm = LabRunPayload.ALGORITHM;

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
    public UUID getExchangeId() { return exchangeId; }
    public void setExchangeId(UUID exchangeId) { this.exchangeId = exchangeId; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public String getCipherAlgorithm() { return cipherAlgorithm; }
    public void setCipherAlgorithm(String cipherAlgorithm) { this.cipherAlgorithm = cipherAlgorithm; }

    public byte[] getNonce() { return nonce == null ? null : nonce.clone(); }
    public void setNonce(byte[] nonce) { this.nonce = nonce == null ? null : nonce.clone(); }
    public byte[] getCiphertext() { return ciphertext == null ? null : ciphertext.clone(); }
    public void setCiphertext(byte[] ciphertext) { this.ciphertext = ciphertext == null ? null : ciphertext.clone(); }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
