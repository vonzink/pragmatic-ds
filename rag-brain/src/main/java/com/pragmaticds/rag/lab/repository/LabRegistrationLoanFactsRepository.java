package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabRegistrationLoanFacts;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * One registration's encrypted loan facts.
 *
 * <p>There is no "find all" read and no lookup by brain: a list view must never decrypt, and the
 * only legitimate read is for a registration the caller is already authorized for. The brain is
 * checked against the row rather than used to search for it.
 */
public interface LabRegistrationLoanFactsRepository
        extends JpaRepository<LabRegistrationLoanFacts, UUID> {

    Optional<LabRegistrationLoanFacts> findByRegistrationId(UUID registrationId);

    /**
     * FK-safe purge step, for a retention sweep that eventually reaches registrations.
     *
     * <p>Nothing calls it yet: registrations are not purged today. It exists because the table
     * holds borrower financial figures and the alternative — discovering there is no way to
     * remove them — is the situation worth designing out now rather than later.
     */
    long deleteByRegistrationId(UUID registrationId);
}
