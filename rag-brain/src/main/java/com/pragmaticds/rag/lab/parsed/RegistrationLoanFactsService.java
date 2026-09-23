package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.domain.LabRegistrationLoanFacts;
import com.pragmaticds.rag.lab.repository.LabRegistrationLoanFactsRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.service.analyze.calc.LoanBasis;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and reads the loan-level facts attached to one parsed-input registration.
 *
 * <p>Which agency's rule applies, whether the transaction is a purchase or a refinance, the
 * qualifying monthly income, and the Adjusted Value are all facts the loan file holds and the
 * Assets folder does not. They live here rather than on a run because a run group's request is
 * identifiers only by design — two comparison members must differ in exactly the declared
 * dimension — while a registration is the loan's package and is shared by every member comparing
 * releases against it.
 *
 * <p><b>Immutable once written.</b> A queued member re-resolves its registration at dispatch, so
 * facts that could be edited in place would let two members of one group run against different
 * income figures depending only on when each was picked up. Writing the same facts again is a
 * no-op; writing DIFFERENT facts is a refusal, not a silent replacement, so a correction surfaces
 * as a visible failure rather than changing the answer under a run that has already queued.
 *
 * <p>Nothing here is logged. Income and Adjusted Value pass through {@link #store} and
 * {@link #find} and exist in the database only as ciphertext.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class RegistrationLoanFactsService {

    /** Version prefix, so a future encoding change is a visible break rather than a silent one. */
    private static final String ENCODING_VERSION = "LOANFACTS-1";

    /** Written for a fact the caller did not supply; never confused with a zero. */
    private static final String ABSENT = "-";

    /** Stable, value-free failures. */
    public static final class LoanFactsException extends RuntimeException {
        public enum Code {
            /** Facts already exist for this registration and the new ones differ. */
            LOAN_FACTS_CONFLICT,
            /** The stored row belongs to another brain. */
            LOAN_FACTS_SCOPE_MISMATCH,
            /**
             * A different {@code subjectScope} was already recorded for this registration.
             *
             * <p>Reserved alongside {@link #LOAN_FACTS_CONFLICT} for the same reason: what a
             * registration IS decides replay, and the loan it belongs to does not, so a changed
             * scope for an already-registered package is refused rather than silently replacing
             * it. Resubmitting the same scope, or omitting it on a retry of a run that had one,
             * is a normal replay and must not raise this. Not yet wired to a check — no caller
             * persists a {@code subjectScope} for comparison yet, so this code cannot be thrown.
             */
            SUBJECT_SCOPE_CONFLICT
        }

        private final Code code;

        public LoanFactsException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    private final LabRegistrationLoanFactsRepository facts;
    private final LabPayloadCipher cipher;

    public RegistrationLoanFactsService(LabRegistrationLoanFactsRepository facts,
                                        LabPayloadCipher cipher) {
        this.facts = Objects.requireNonNull(facts, "facts");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /**
     * Attaches loan facts to one registration.
     *
     * <p>A null or empty {@code basis} writes nothing: supplying no facts is a legitimate request
     * and must not create an empty row that a later, real write would then collide with.
     *
     * @throws LoanFactsException {@link LoanFactsException.Code#LOAN_FACTS_CONFLICT} when this
     *     registration already carries different facts
     */
    public void store(UUID brainId, UUID registrationId, LoanBasis basis) {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(registrationId, "registrationId");
        if (basis == null || isEmpty(basis)) {
            return;
        }

        String canonical = canonical(basis);
        String digest = sha256Hex(canonical);

        Optional<LabRegistrationLoanFacts> existing = facts.findByRegistrationId(registrationId);
        if (existing.isPresent()) {
            LabRegistrationLoanFacts stored = existing.get();
            requireBrain(brainId, stored);
            // Same facts twice is an idempotent retry, which is normal and must succeed. The
            // comparison is on the digest, so deciding it never decrypts anything.
            if (!digest.equals(stored.getFactsSha256())) {
                throw new LoanFactsException(LoanFactsException.Code.LOAN_FACTS_CONFLICT);
            }
            return;
        }

        LabPayloadCipher.SealedPayload sealed = cipher.seal(
                brainId, registrationId, registrationId,
                LabPayloadCipher.RecordType.REGISTRATION_LOAN_FACTS,
                canonical.getBytes(StandardCharsets.UTF_8));

        LabRegistrationLoanFacts row = new LabRegistrationLoanFacts();
        row.setRegistrationId(registrationId);
        row.setBrainId(brainId);
        row.setFactsSha256(digest);
        row.setCipherAlgorithm(sealed.algorithm());
        row.setNonce(sealed.nonce());
        row.setCiphertext(sealed.ciphertext());
        facts.save(row);
    }

    /**
     * The facts attached to one registration, or null when none were supplied.
     *
     * <p>Null is the ordinary answer, not an error: an instance that does not need loan facts
     * never stores any, and an assets run without them reports its large-deposit screen as
     * unavailable rather than guessing a threshold.
     */
    public LoanBasis find(UUID brainId, UUID registrationId) {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(registrationId, "registrationId");
        return facts.findByRegistrationId(registrationId)
                .map(stored -> {
                    requireBrain(brainId, stored);
                    return decode(new String(cipher.open(brainId, registrationId, registrationId,
                            LabPayloadCipher.RecordType.REGISTRATION_LOAN_FACTS,
                            new LabPayloadCipher.SealedPayload(
                                    stored.getNonce(), stored.getCiphertext())),
                            StandardCharsets.UTF_8));
                })
                .orElse(null);
    }

    // ---------------------------------------------------------------- encoding

    /**
     * The exact bytes that are sealed and digested.
     *
     * <p>Deliberately a fixed-arity delimited string rather than JSON: the digest decides whether
     * a second write is a retry or a conflict, so the encoding has to be stable against anything
     * a serializer might reasonably change — key order, number formatting, a new field defaulting
     * to null. Money is normalized to two decimal places so 9216.6 and 9216.60 are one fact.
     */
    static String canonical(LoanBasis basis) {
        return String.join("|",
                ENCODING_VERSION,
                basis.program() == null ? ABSENT : basis.program().name(),
                basis.purpose() == null ? ABSENT : basis.purpose().name(),
                money(basis.qualifyingMonthlyIncome()),
                money(basis.adjustedValue()));
    }

    static LoanBasis decode(String canonical) {
        String[] parts = canonical.split("\\|", -1);
        if (parts.length != 5 || !ENCODING_VERSION.equals(parts[0])) {
            // A record this build cannot read is not silently treated as "no facts": that would
            // turn an encoding change into every assets run quietly losing its threshold.
            throw new IllegalStateException("unreadable loan-facts encoding");
        }
        return new LoanBasis(
                LoanBasis.programOf(nullIfAbsent(parts[1])),
                LoanBasis.purposeOf(nullIfAbsent(parts[2])),
                amount(parts[3]),
                amount(parts[4]));
    }

    private static boolean isEmpty(LoanBasis basis) {
        return basis.program() == null && basis.purpose() == null
                && basis.qualifyingMonthlyIncome() == null && basis.adjustedValue() == null;
    }

    private static String money(BigDecimal value) {
        return value == null ? ABSENT : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal amount(String raw) {
        return ABSENT.equals(raw) ? null : new BigDecimal(raw);
    }

    private static String nullIfAbsent(String raw) {
        return ABSENT.equals(raw) ? null : raw;
    }

    private static void requireBrain(UUID brainId, LabRegistrationLoanFacts stored) {
        if (!brainId.equals(stored.getBrainId())) {
            throw new LoanFactsException(LoanFactsException.Code.LOAN_FACTS_SCOPE_MISMATCH);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
