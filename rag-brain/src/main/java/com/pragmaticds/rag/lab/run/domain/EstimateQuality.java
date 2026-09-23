package com.pragmaticds.rag.lab.run.domain;

/**
 * How precisely a pre-run token count is known.
 *
 * <p>{@link #EXACT_TOKENIZER} means every component of the count is exact — a configured
 * allowance, an output ceiling, or text run through a real tokenizer. {@link #ESTIMATED_RANGE}
 * means at least one component was bounded rather than counted, which makes the whole sum a
 * bound. A total is only as good as its worst part.
 *
 * <p>In this build no provider tokenizer ships, so any estimate containing prompt text is a
 * range. The label follows what the process can actually compute, never what configuration
 * declares: showing a single exact-looking number derived from a character heuristic is worse
 * than showing a band, because a band invites the reader to treat it as approximate and a bare
 * number does not.
 */
public enum EstimateQuality {
    /** Every component of this count is exact; minimum and maximum are equal. */
    EXACT_TOKENIZER,
    /** At least one component was bounded rather than counted; minimum and maximum differ. */
    ESTIMATED_RANGE
}
