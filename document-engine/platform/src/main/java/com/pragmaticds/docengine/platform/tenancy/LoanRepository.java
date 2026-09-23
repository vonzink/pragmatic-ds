package com.pragmaticds.docengine.platform.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<Loan> findByIdAndOrgId(UUID id, UUID orgId);

    List<Loan> findByOrgId(UUID orgId);
}
