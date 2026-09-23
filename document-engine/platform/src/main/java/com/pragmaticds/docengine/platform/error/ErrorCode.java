package com.pragmaticds.docengine.platform.error;

/**
 * The PII-free error taxonomy (docs/ARCHITECTURE.md 10). Every failure surfaces as one of these
 * stable codes plus non-sensitive parameters — never a message containing document content, a
 * borrower name, or an account number.
 */
public enum ErrorCode {
    // Ingestion
    UNSUPPORTED_MIME,
    FILE_TOO_LARGE,
    PAGE_LIMIT_EXCEEDED,
    PASSWORD_PROTECTED,
    CORRUPT_PDF,
    /**
     * An image whose bytes do not decode — a truncated phone upload, a mislabelled file, a format
     * no decoder on the path supports. Distinct from {@link #CORRUPT_PDF} on purpose: every JPEG
     * paystub used to fail as a "corrupt PDF", which named neither the file nor the problem and
     * sent whoever read the stage row looking for a PDF that never existed.
     */
    CORRUPT_IMAGE,
    DUPLICATE_FILE,
    EMPTY_UPLOAD,

    // Parsing
    RENDER_FAILED,
    TEXT_EXTRACTION_FAILED,
    OCR_FAILED,
    OCR_LOW_CONFIDENCE,
    WORKER_UNAVAILABLE,
    WORKER_TIMEOUT,
    /**
     * The job's wall-clock budget ({@code docengine.processing.job-time-budget-seconds}) ran out
     * before a worker-bound stage, or between OCR pages. One document may not hold the queue for an
     * hour: a 75-page scan at ~70s a page is exactly that (2026-09-16). Never retried.
     */
    JOB_TIME_BUDGET_EXCEEDED,
    /** The user cancelled the job; honoured at the next checkpoint. Never retried. */
    JOB_CANCELLED,

    // Classification
    NO_RULE_PACK,
    AMBIGUOUS,
    BELOW_THRESHOLD,

    // Regroup (Spec 2): a blank/duplicate page cannot be assigned to a document until a
    // NOT_BLANK/NOT_DUPLICATE verdict clears its signal (design §6.5).
    PAGE_NOT_ASSIGNABLE,

    // Extraction
    SCHEMA_NOT_FOUND,
    FIELD_NOT_FOUND,
    NORMALIZATION_FAILED,
    LOW_CONFIDENCE,
    AI_EXTRACTION_FAILED,

    // Boundary extraction (Phase D — the model half of document splitting)
    BOUNDARY_EXTRACTION_FAILED,

    // Immutable engine results
    ENGINE_RESULT_CONFLICT,
    ENGINE_RESULT_CORRUPT,
    ENGINE_RESULT_NOT_READY,

    // Authorization
    FORBIDDEN_TENANT,
    FORBIDDEN_DOCUMENT,
    SCOPE_DENIED,

    // General
    NOT_FOUND,
    CONFLICT,
    INVALID_REQUEST,
    INTERNAL
}
