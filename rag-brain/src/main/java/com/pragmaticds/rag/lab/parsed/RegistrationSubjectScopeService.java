package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.domain.LabRegistrationSubjectScope;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService.LoanFactsException;
import com.pragmaticds.rag.lab.repository.LabRegistrationSubjectScopeRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Attaches an opaque per-loan subject scope to one registration, and reads it back.
 *
 * <p>This is what makes a finding's {@code subjectKey} exist in production. A tool holds no run
 * context and cannot supply the scope; the analyzer resolves it here from the registration the run
 * already names, exactly as it resolves loan facts.
 *
 * <p>Nothing here logs. A subject scope is an opaque correlator, not a value in itself, but it is
 * still per-loan and there is no reason for it to reach a log line.
 */
@Service
public class RegistrationSubjectScopeService {

    private final LabRegistrationSubjectScopeRepository scopes;

    public RegistrationSubjectScopeService(LabRegistrationSubjectScopeRepository scopes) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    /**
     * Attaches a subject scope to one registration.
     *
     * <p>A null or blank scope writes nothing: supplying no scope is a legitimate request — the
     * caller may not track loans at all — and an empty row would collide with a later real write.
     */
    public void store(UUID brainId, UUID registrationId, String subjectScope) {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(registrationId, "registrationId");
        if (subjectScope == null || subjectScope.isBlank()) {
            return;
        }

        Optional<LabRegistrationSubjectScope> existing =
                scopes.findByRegistrationId(registrationId);
        if (existing.isPresent()) {
            LabRegistrationSubjectScope stored = existing.get();
            requireBrain(brainId, stored);
            // The same scope twice is an idempotent retry — a connector re-sends the whole
            // registration — and must succeed. A DIFFERENT scope is refused rather than
            // replacing the stored one: a queued run re-resolves its registration at dispatch,
            // so an editable scope would let a run that was queued for one loan be reported
            // against another.
            if (!subjectScope.equals(stored.getSubjectScope())) {
                throw new LoanFactsException(LoanFactsException.Code.SUBJECT_SCOPE_CONFLICT);
            }
            return;
        }

        LabRegistrationSubjectScope row = new LabRegistrationSubjectScope();
        row.setRegistrationId(registrationId);
        row.setBrainId(brainId);
        row.setSubjectScope(subjectScope);
        scopes.save(row);
    }

    /** A row reached from the wrong brain is a scope error, never a silent read. */
    private static void requireBrain(UUID brainId, LabRegistrationSubjectScope stored) {
        if (!brainId.equals(stored.getBrainId())) {
            throw new LoanFactsException(LoanFactsException.Code.LOAN_FACTS_SCOPE_MISMATCH);
        }
    }

    /**
     * The scope attached to one registration, or null when none was supplied.
     *
     * <p>Null is the ordinary answer, not an error: findings still publish, their subject keys stay
     * null, and waivers simply cannot carry forward.
     */
    public String find(UUID brainId, UUID registrationId) {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(registrationId, "registrationId");
        return scopes.findByRegistrationId(registrationId)
                .map(stored -> {
                    requireBrain(brainId, stored);
                    return stored.getSubjectScope();
                })
                .orElse(null);
    }
}
