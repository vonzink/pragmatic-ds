package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabRegistrationSubjectScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * One registration's opaque subject scope.
 *
 * <p>No "find all" and no lookup by brain, for the same reason
 * {@link LabRegistrationLoanFactsRepository} has neither: the only legitimate read is for a
 * registration the caller is already authorized for, and a listing keyed by brain would turn an
 * opaque per-loan correlator into a way to enumerate a brain's loans. The brain is checked against
 * the row rather than used to search for it.
 */
public interface LabRegistrationSubjectScopeRepository
        extends JpaRepository<LabRegistrationSubjectScope, UUID> {

    Optional<LabRegistrationSubjectScope> findByRegistrationId(UUID registrationId);

    /**
     * FK-safe purge step, for a retention sweep that eventually reaches registrations.
     *
     * <p>Nothing calls it yet, for the same reason its loan-facts sibling is uncalled: registrations
     * are not purged today. It exists because discovering later that a per-loan correlator cannot
     * be removed is the situation worth designing out now.
     */
    long deleteByRegistrationId(UUID registrationId);
}
