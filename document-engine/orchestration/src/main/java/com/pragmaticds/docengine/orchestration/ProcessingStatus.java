package com.pragmaticds.docengine.orchestration;

/**
 * Pipeline stages, declared in execution order.
 *
 * <p>Each value is both a job status and a {@code processing_stage} row, which is what makes a
 * failed job resumable from the first failed stage rather than from the beginning. Ordering is
 * meaningful — resume replays forward from a stage — so values must not be reordered.
 *
 * <p>{@link #TEXT_EXTRACTION} is deliberately distinct from {@link #OCR_PROCESSING}. Native text
 * extraction and OCR fail differently, and most mortgage packages are mixed: a package where native
 * text succeeds on pages 1–8 and OCR is needed for 9–14 must record both outcomes independently.
 */
public enum ProcessingStatus {
    UPLOADED,
    VALIDATING,
    NORMALIZING,
    RENDERING,
    TEXT_EXTRACTION,
    OCR_PROCESSING,
    PARSING,
    CLASSIFYING,
    SPLITTING,
    /**
     * Phase D (Reducto-parity roadmap): gated model-proposed boundary extraction over the
     * deterministic split's ambiguity windows. Inserted at its true pipeline position — between
     * SPLITTING and EXTRACTING — which is safe for the same reason AI_EXTRACTION's insertion was
     * (V23): stage rows persist by STRING, and the one ordinal consumer
     * ({@code PackageUsageReader.pipelineOrder}) wants exactly this execution order.
     */
    BOUNDARY_EXTRACTION,
    EXTRACTING,
    AI_EXTRACTION,
    FINALIZING,
    /** Spec 4. Recorded SKIPPED with a reason in Spec 1 — a pipeline position, not a pretend stage. */
    VALIDATING_DATA,
    /** Spec 5. Recorded SKIPPED with a reason in Spec 1. */
    AI_REVIEW,
    HUMAN_REVIEW_REQUIRED,
    COMPLETED,
    FAILED;

    /** Terminal states hold no further work. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
