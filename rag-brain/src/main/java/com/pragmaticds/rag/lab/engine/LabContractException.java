package com.pragmaticds.rag.lab.engine;

/**
 * A Document Engine consumer-contract violation.
 *
 * <p>Deliberately payload-free: the exception carries a stable {@link Code} and nothing else.
 * There is no cause constructor and no free-text message, so no envelope byte, parsed value,
 * filename, or provider text can ever travel inside it into a response, log, or audit row.
 */
public final class LabContractException extends RuntimeException {

    /** Stable, value-free error taxonomy for strict envelope consumption. */
    public enum Code {
        /** The received body is empty or absent. */
        ENVELOPE_EMPTY,
        /** Not one strict JSON value: syntax error, duplicate key, trailing token, non-finite. */
        ENVELOPE_NOT_STRICT_JSON,
        /** A numeric literal is not in DOCENGINE-C14N-1 form (exponent, trailing zeros, -0). */
        ENVELOPE_NUMBER_NOT_CANONICAL,
        /** Object member names are not in canonical code-point order. */
        ENVELOPE_KEY_ORDER_NOT_CANONICAL,
        /** A raw-text/storage/filename/review/correction member appeared at some depth. */
        ENVELOPE_FORBIDDEN_MEMBER,
        /** The top-level JSON value is not an object. */
        ENVELOPE_ROOT_NOT_OBJECT,
        /** A required member is absent (explicit-null members must still be present). */
        ENVELOPE_MEMBER_MISSING,
        /** A member outside the pinned envelope vocabulary appeared. */
        ENVELOPE_MEMBER_UNKNOWN,
        /** A member value has the wrong type, shape, vocabulary, or bounds. */
        ENVELOPE_VALUE_MALFORMED,
        /** The envelope version is not the pinned {@code 1.0.0}. */
        ENVELOPE_VERSION_UNSUPPORTED,
        /** The canonicalization version is not the pinned {@code DOCENGINE-C14N-1}. */
        CANONICALIZATION_VERSION_UNSUPPORTED,
        /** Two rows claim one semantic identity (id, ordinal, page index, occurrence, stage). */
        IDENTITY_DUPLICATE,
        /** A semantically ordered array is not in its pinned order. */
        ORDERING_VIOLATION,
        /** Page membership is inconsistent (foreign evidence page, unassigned mismatch, reuse). */
        PAGE_MEMBERSHIP_VIOLATION,
        /** A field contradicts its own status (FOUND/MISSING vs method/value arms/confidence). */
        FIELD_CONTRADICTION,
        /** A read-model (fields view) member outside the pinned vocabulary appeared. */
        READMODEL_MEMBER_UNKNOWN,
        /** A required read-model member is absent. */
        READMODEL_MEMBER_MISSING,
        /** A read-model member value has the wrong type, vocabulary, or bounds. */
        READMODEL_VALUE_MALFORMED
    }

    private final Code code;

    public LabContractException(Code code) {
        super(java.util.Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
