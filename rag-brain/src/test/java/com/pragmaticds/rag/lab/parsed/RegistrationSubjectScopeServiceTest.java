package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.domain.LabRegistrationSubjectScope;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService.LoanFactsException;
import com.pragmaticds.rag.lab.repository.LabRegistrationSubjectScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The opaque per-loan scope a finding's subject key is hashed against.
 *
 * <p>Stored in plaintext, unlike loan facts: a subject scope is a token the host app derives from a
 * loan id and this process contractually cannot reverse. Encrypting it would buy no
 * confidentiality — the same argument that leaves {@code tenant_id} and {@code externalRequestId}
 * in plaintext beside it.
 */
class RegistrationSubjectScopeServiceTest {

    private final UUID brainId = UUID.randomUUID();
    private final UUID registrationId = UUID.randomUUID();

    private LabRegistrationSubjectScopeRepository repository;
    private RegistrationSubjectScopeService service;
    private Map<UUID, LabRegistrationSubjectScope> stored;

    @BeforeEach
    void setUp() {
        repository = mock(LabRegistrationSubjectScopeRepository.class);
        stored = new HashMap<>();
        when(repository.findByRegistrationId(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.save(any(LabRegistrationSubjectScope.class))).thenAnswer(call -> {
            LabRegistrationSubjectScope row = call.getArgument(0);
            stored.put(row.getRegistrationId(), row);
            return row;
        });
        service = new RegistrationSubjectScopeService(repository);
    }

    @Test
    void aStoredScopeIsFoundAgain() {
        service.store(brainId, registrationId, "scope-a");

        assertEquals("scope-a", service.find(brainId, registrationId));
    }

    @Test
    void aRegistrationWithNoScopeFindsNull() {
        assertNull(service.find(brainId, registrationId));
    }

    @Test
    void aNullScopeWritesNothing() {
        service.store(brainId, registrationId, null);

        // An empty row would collide with a later real write, so supplying no scope must not
        // create one. Absent is a legitimate request, not a value.
        verify(repository, never()).save(any(LabRegistrationSubjectScope.class));
        assertNull(service.find(brainId, registrationId));
    }

    @Test
    void aBlankScopeWritesNothing() {
        service.store(brainId, registrationId, "   ");

        verify(repository, never()).save(any(LabRegistrationSubjectScope.class));
        assertNull(service.find(brainId, registrationId));
    }

    @Test
    void theSameScopeTwiceIsANormalReplay() {
        service.store(brainId, registrationId, "scope-a");
        service.store(brainId, registrationId, "scope-a");

        // A connector retry re-sends the whole registration. Refusing the second one would turn
        // an ordinary replay into a failure.
        assertEquals("scope-a", service.find(brainId, registrationId));
        verify(repository, times(1)).save(any(LabRegistrationSubjectScope.class));
    }

    @Test
    void omittingTheScopeOnARetryLeavesTheStoredOneAlone() {
        service.store(brainId, registrationId, "scope-a");
        service.store(brainId, registrationId, null);

        assertEquals("scope-a", service.find(brainId, registrationId));
    }

    @Test
    void aDifferentScopeIsRefused() {
        service.store(brainId, registrationId, "scope-a");

        LoanFactsException thrown = assertThrows(LoanFactsException.class,
                () -> service.store(brainId, registrationId, "scope-b"));

        // Silently replacing it would rebind a queued run to a different loan.
        assertEquals(LoanFactsException.Code.SUBJECT_SCOPE_CONFLICT, thrown.code());
        assertEquals("scope-a", service.find(brainId, registrationId));
    }

    @Test
    void aScopeStoredUnderAnotherBrainIsRefused() {
        service.store(brainId, registrationId, "scope-a");

        assertEquals(LoanFactsException.Code.LOAN_FACTS_SCOPE_MISMATCH,
                assertThrows(LoanFactsException.class,
                        () -> service.find(UUID.randomUUID(), registrationId)).code());
    }
}
