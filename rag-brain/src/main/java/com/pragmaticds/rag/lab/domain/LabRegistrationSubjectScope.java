package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One registration's opaque per-loan subject scope.
 *
 * <p>A finding's {@code subjectKey} is hashed against this, which is what lets a loan officer's
 * waiver on Monday match the same problem on Tuesday's re-uploaded document. Without it every
 * finding publishes with a null subject key and no waiver can carry.
 *
 * <p><b>Plaintext, deliberately.</b> Unlike {@link LabRegistrationLoanFacts}, which holds borrower
 * financial figures and is therefore ciphertext-only, a subject scope is a token the host app
 * derives from a loan id and this process contractually cannot reverse. Encrypting an opaque
 * correlator buys no confidentiality while adding a key dependency to a read that must never fail
 * for want of one — the same reasoning that leaves {@code tenant_id} and the caller's
 * {@code external_request_id} in plaintext beside it. This process still never learns which loan
 * the scope names.
 *
 * <p><b>Why it hangs off the registration and not the run.</b> A run group's request is
 * identifiers only, so that two comparison members differ in exactly the declared dimension and no
 * other. A per-run scope would be exactly such a channel. A registration is the loan's package,
 * shared by every member comparing releases against it, and {@code RunGroupDispatcher} already
 * re-resolves the registration on every dispatch — so a queued member picks this up with no new
 * run-path storage. Same argument V42 makes for loan facts.
 *
 * <p>Immutable in practice: the service refuses a changed scope rather than replacing one, because
 * a queued run must never be silently rebound to a different loan.
 */
@Entity
@Table(name = "lab_registration_subject_scope")
public class LabRegistrationSubjectScope {

    @Id
    @Column(name = "registration_id")
    private UUID registrationId;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "subject_scope", nullable = false, length = 200)
    private String subjectScope;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(UUID registrationId) {
        this.registrationId = registrationId;
    }

    public UUID getBrainId() {
        return brainId;
    }

    public void setBrainId(UUID brainId) {
        this.brainId = brainId;
    }

    public String getSubjectScope() {
        return subjectScope;
    }

    public void setSubjectScope(String subjectScope) {
        this.subjectScope = subjectScope;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
