package com.pragmaticds.docengine.platform.tenancy;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A lightweight loan reference. The engine does not own loans — host-app does. This exists so
 * packages can be grouped and Spec 4 cross-document validation has a subject.
 */
@Entity
@Table(name = "loan")
public class Loan extends TenantScopedEntity {

    @Column(name = "external_loan_id")
    private UUID externalLoanId;

    @Column(name = "loan_number")
    private String loanNumber;

    protected Loan() {}

    public Loan(UUID externalLoanId, String loanNumber) {
        this.externalLoanId = externalLoanId;
        this.loanNumber = loanNumber;
    }

    public UUID getExternalLoanId() {
        return externalLoanId;
    }

    public String getLoanNumber() {
        return loanNumber;
    }
}
