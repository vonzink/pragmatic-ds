package com.pragmaticds.rag.lab.run.domain;

/**
 * How much is actually known about what a model call consumed.
 *
 * <p>The distinction that matters is {@link #UNAVAILABLE} versus a reported zero. Providers do not
 * always return usage metadata, and writing zeros when they do not would turn "we don't know" into
 * "it was free" — a number that then propagates into every cost report as though it were measured.
 * An absent report stays absent.
 */
public enum UsageQuality {
    /** The call has not reported yet. Carries no numbers. */
    PENDING,
    /** The provider supplied the counts. */
    REPORTED,
    /** Derived rather than supplied, and only where the derivation is separately justified. */
    INFERRED,
    /** The provider supplied nothing. Carries no numbers, and never a zero standing in for one. */
    UNAVAILABLE
}
