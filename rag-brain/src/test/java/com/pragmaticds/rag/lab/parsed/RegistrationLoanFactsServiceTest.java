package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.domain.LabRegistrationLoanFacts;
import com.pragmaticds.rag.lab.repository.LabRegistrationLoanFactsRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.service.analyze.calc.LoanBasis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The loan facts an assets run is selected and computed from.
 *
 * <p>A real {@link LabPayloadCipher} with a test key, so the round trip proves the sealed bytes
 * actually open again under the registration and brain they were bound to — a mocked cipher would
 * prove only that the service calls it.
 */
class RegistrationLoanFactsServiceTest {

    /** The same throwaway key the Lab integration tests use; production reads its own. */
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final UUID brainId = UUID.randomUUID();
    private final UUID registrationId = UUID.randomUUID();

    private LabRegistrationLoanFactsRepository repository;
    private RegistrationLoanFactsService service;
    private Map<UUID, LabRegistrationLoanFacts> stored;

    @BeforeEach
    void setUp() {
        repository = mock(LabRegistrationLoanFactsRepository.class);
        stored = new HashMap<>();
        when(repository.findByRegistrationId(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.save(any(LabRegistrationLoanFacts.class))).thenAnswer(call -> {
            LabRegistrationLoanFacts row = call.getArgument(0);
            stored.put(row.getRegistrationId(), row);
            return row;
        });
        service = new RegistrationLoanFactsService(repository, new LabPayloadCipher(TEST_KEY));
    }

    private static LoanBasis conventionalPurchase() {
        return new LoanBasis(LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE,
                new BigDecimal("9216.66"), null);
    }

    @Test
    void sealedFactsComeBackExactlyAsTheyWentIn() {
        service.store(brainId, registrationId, conventionalPurchase());

        LoanBasis read = service.find(brainId, registrationId);

        assertEquals(LoanBasis.Program.FANNIE_MAE, read.program());
        assertEquals(LoanBasis.Purpose.PURCHASE, read.purpose());
        assertEquals(0, new BigDecimal("9216.66").compareTo(read.qualifyingMonthlyIncome()));
        assertNull(read.adjustedValue(), "an absent fact stays absent, never becomes zero");
    }

    /** No plaintext column exists, so the stored row must carry none of the figures. */
    @Test
    void nothingReadableIsStoredBesideTheCiphertext() {
        service.store(brainId, registrationId, conventionalPurchase());

        LabRegistrationLoanFacts row = stored.get(registrationId);
        assertEquals(LabRegistrationLoanFacts.ALGORITHM, row.getCipherAlgorithm());
        assertEquals(12, row.getNonce().length, "GCM nonce width is a schema fact");
        assertTrue(row.getCiphertext().length >= 16, "at least a bare 128-bit GCM tag");
        assertTrue(row.getFactsSha256().matches("[0-9a-f]{64}"));
        assertFalse(new String(row.getCiphertext()).contains("9216.66"),
                "the income must not survive as readable bytes");
    }

    /** A retry carrying the same facts is normal and must succeed without a second write. */
    @Test
    void writingTheSameFactsAgainIsANoOp() {
        service.store(brainId, registrationId, conventionalPurchase());
        service.store(brainId, registrationId, conventionalPurchase());

        verify(repository).save(any(LabRegistrationLoanFacts.class));
    }

    /** Scale is normalized, so the same money written two ways is still one fact. */
    @Test
    void moneyIsComparedAtTwoDecimalPlacesNotByItsWrittenForm() {
        service.store(brainId, registrationId, new LoanBasis(
                LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE,
                new BigDecimal("9216.6"), null));

        assertDoesNotThrow(() -> service.store(brainId, registrationId, new LoanBasis(
                LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE,
                new BigDecimal("9216.60"), null)));
    }

    /**
     * Different facts are refused, not written over.
     *
     * <p>A queued member re-resolves its registration at dispatch, so a silent replacement would
     * change the threshold under a run that had already been submitted and priced.
     */
    @Test
    void writingDifferentFactsIsRefusedRatherThanReplacingThem() {
        service.store(brainId, registrationId, conventionalPurchase());

        RegistrationLoanFactsService.LoanFactsException refused = assertThrows(
                RegistrationLoanFactsService.LoanFactsException.class,
                () -> service.store(brainId, registrationId, new LoanBasis(
                        LoanBasis.Program.FHA, LoanBasis.Purpose.PURCHASE,
                        new BigDecimal("9216.66"), new BigDecimal("600000.00"))));

        assertEquals(RegistrationLoanFactsService.LoanFactsException.Code.LOAN_FACTS_CONFLICT,
                refused.code());
    }

    /** Supplying no facts is a legitimate request and must not create an empty row. */
    @Test
    void anEmptyBasisWritesNothing() {
        service.store(brainId, registrationId, new LoanBasis(null, null, null, null));
        service.store(brainId, registrationId, null);

        verify(repository, never()).save(any(LabRegistrationLoanFacts.class));
        assertNull(service.find(brainId, registrationId));
    }

    /** Absence is the ordinary answer for an instance that needs no loan facts. */
    @Test
    void anUnregisteredRegistrationReadsAsNullRatherThanFailing() {
        assertNull(service.find(brainId, UUID.randomUUID()));
    }

    /** The AAD binds the record to its brain, so another brain cannot read or claim it. */
    @Test
    void anotherBrainCannotReadTheFacts() {
        service.store(brainId, registrationId, conventionalPurchase());

        RegistrationLoanFactsService.LoanFactsException refused = assertThrows(
                RegistrationLoanFactsService.LoanFactsException.class,
                () -> service.find(UUID.randomUUID(), registrationId));

        assertEquals(RegistrationLoanFactsService.LoanFactsException.Code.LOAN_FACTS_SCOPE_MISMATCH,
                refused.code());
    }

    /** The encoding is fixed-arity and versioned, so a digest decides retry-versus-conflict. */
    @Test
    void theCanonicalFormIsStableAndNamesEveryAbsentFact() {
        assertEquals("LOANFACTS-1|FANNIE_MAE|PURCHASE|9216.66|-",
                RegistrationLoanFactsService.canonical(conventionalPurchase()));
        assertEquals("LOANFACTS-1|-|-|-|-",
                RegistrationLoanFactsService.canonical(new LoanBasis(null, null, null, null)));
    }

    /** A record this build cannot read must fail loudly, not degrade to "no facts". */
    @Test
    void anUnknownEncodingVersionIsARefusalNotASilentAbsence() {
        assertThrows(IllegalStateException.class,
                () -> RegistrationLoanFactsService.decode("LOANFACTS-9|FANNIE_MAE|PURCHASE|1|2"));
    }
}
