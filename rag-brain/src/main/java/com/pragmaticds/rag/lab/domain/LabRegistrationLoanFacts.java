package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One registration's loan-level facts, stored as AES-256-GCM ciphertext and nothing else.
 *
 * <p>The program, purpose, qualifying monthly income, and Adjusted Value an assets run needs are
 * not in the Assets folder — they are in the loan file. Income and value are borrower financial
 * figures, so there is no plaintext field here and no plaintext column in
 * {@code lab_registration_loan_facts}: they exist in the database only inside
 * {@link #getCiphertext()}, the same treatment {@link LabRunPayload} gives report text.
 *
 * <p>{@link #getFactsSha256()} digests the canonical <em>plaintext</em>, so a repeated write can
 * be recognized as the same facts without decrypting anything. It is a digest over numbers and
 * enum names and is not itself a value.
 *
 * <p>Immutable by database trigger. A queued run re-resolves its registration at dispatch, so
 * facts that could be edited in place would let two members of one comparison group run against
 * different income figures depending only on when each was picked up.
 *
 * <p>Byte arrays are defensively copied on both the way in and the way out, so a caller cannot
 * mutate stored ciphertext through a retained reference.
 */
@Entity
@Table(name = "lab_registration_loan_facts")
public class LabRegistrationLoanFacts {

    /** The only algorithm V42 will store. */
    public static final String ALGORITHM = "AES-256-GCM";

    @Id
    @Column(name = "registration_id")
    private UUID registrationId;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "facts_sha256", nullable = false, length = 64)
    private String factsSha256;

    @Column(name = "cipher_algorithm", nullable = false, length = 24)
    private String cipherAlgorithm = ALGORITHM;

    @Column(nullable = false)
    private byte[] nonce;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getRegistrationId() { return registrationId; }
    public void setRegistrationId(UUID registrationId) { this.registrationId = registrationId; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getFactsSha256() { return factsSha256; }
    public void setFactsSha256(String factsSha256) { this.factsSha256 = factsSha256; }
    public String getCipherAlgorithm() { return cipherAlgorithm; }
    public void setCipherAlgorithm(String cipherAlgorithm) { this.cipherAlgorithm = cipherAlgorithm; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public byte[] getNonce() { return nonce == null ? null : nonce.clone(); }
    public void setNonce(byte[] nonce) { this.nonce = nonce == null ? null : nonce.clone(); }
    public byte[] getCiphertext() { return ciphertext == null ? null : ciphertext.clone(); }
    public void setCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext == null ? null : ciphertext.clone();
    }
}
