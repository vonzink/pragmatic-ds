package com.pragmaticds.docengine.throughput;

/**
 * This box's measured processing rates for one org, from its recently completed jobs.
 *
 * @param ocrSecondsPerPage seconds of OCR_PROCESSING per OCR'd page (text layer SCANNED or MIXED)
 * @param otherSecondsPerPage seconds of RENDERING + TEXT_EXTRACTION + PARSING per page with a
 *     text layer (text_layer &lt;&gt; 'NONE'), per page of the packages those jobs touched — a
 *     package with more than one completed job (resume, regroup) counts its stage time for each
 *     run, so this over-estimates rather than under-estimates
 * @param fixedSecondsPerJob seconds of the per-job stages (VALIDATING, CLASSIFYING, SPLITTING,
 *     EXTRACTING, FINALIZING) per job
 * @param sampleJobs how many completed jobs the rates were measured over; 0 = seeded defaults
 * @param measured false when the numbers are the seeded defaults rather than a measurement
 */
public record Throughput(
        double ocrSecondsPerPage,
        double otherSecondsPerPage,
        double fixedSecondsPerJob,
        int sampleJobs,
        boolean measured) {

    /** Measured on the t3.large, 2026-09-16, before the OCR speed work. */
    public static final Throughput SEEDED = new Throughput(70.0, 2.0, 10.0, 0, false);
}
