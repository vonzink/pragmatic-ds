package com.pragmaticds.docengine.platform.regex;

/**
 * A {@link CharSequence} that abandons a match once it has been read more times than any honest
 * match would need.
 *
 * <h2>What this defends against</h2>
 *
 * <p>{@code java.util.regex} backtracks. A pattern with nested quantifiers over a long subject can
 * take exponential time, and there is no timeout on {@code Matcher} — a runaway match pins its
 * thread until the process dies. That was theoretical while every pattern in the system arrived
 * through a reviewed Flyway migration. It stopped being theoretical the day
 * {@code POST /v1/extraction-schemas} began accepting tenant-authored patterns: validation proves a
 * pattern COMPILES, which says nothing about what it costs to RUN. Extraction runs on a shared
 * pool, so one org's schema could degrade every other org's processing.
 *
 * <h2>Why a step budget and not a clock</h2>
 *
 * <p>A wall-clock deadline makes the cutoff depend on how loaded the box is: the same schema over
 * the same page passes on an idle machine and fails on a busy one, so a parse stops being a
 * function of its inputs. This project spends real effort on that property — see the canonical
 * envelope and the parse-once fingerprint — and a nondeterministic extraction result would
 * undermine both.
 *
 * <p>Counting reads is deterministic, machine-independent, reproducible in a test, and needs no
 * clock call in the hot path. Catastrophic backtracking is exponential in reads, so a budget
 * discriminates just as sharply as a stopwatch: a legitimate pattern over a page of text spends a
 * small multiple of the subject's length, while a pathological one blows through millions.
 *
 * <h2>Use</h2>
 *
 * <p>One instance per match attempt — the counter is mutable and deliberately not thread-safe.
 * Wrap the SUBJECT, not the pattern:
 *
 * <pre>{@code
 * Matcher m = pattern.matcher(BoundedCharSequence.over(text));
 * }</pre>
 *
 * <p>The caller catches {@link RegexBudgetExceededException} at whatever boundary can absorb one
 * lost answer — a field that yields nothing, an anchor that does not match — never at a boundary
 * that would fail a whole document.
 */
public final class BoundedCharSequence implements CharSequence {

    /**
     * Reads allowed per match attempt.
     *
     * <p>Calibrated by measurement, not taste. Two numbers set it:
     *
     * <ul>
     *   <li>A legitimate money pattern over a subject larger than a dense page of extracted text
     *       spends fewer than 10,000 reads.
     *   <li>{@code (.*a){20}b} over 200 characters — a shape that genuinely backtracks on this JDK
     *       — burns 50,000,000 reads in about 330ms.
     * </ul>
     *
     * <p>Two million therefore leaves roughly 200x headroom over honest work while capping a
     * pathological pattern near 13ms. The cost of being wrong low is one skipped field; the cost of
     * being wrong high is a thread that does not come back.
     *
     * <p><b>The textbook ReDoS patterns are not the threat here.</b> Measured on Java 21,
     * {@code (a+)+$}, {@code ^(a+)+$}, {@code (a|aa)+$} and {@code ([a-z]+)*$} all finish in
     * hundreds of reads: the engine's loop optimisations defuse them. Anyone re-testing this guard
     * with a pattern from a ReDoS article will conclude it does nothing. Use a nested-{@code .*}
     * shape.
     */
    public static final long DEFAULT_BUDGET = 2_000_000L;

    private final CharSequence text;
    private final long budget;
    private long reads;

    private BoundedCharSequence(CharSequence text, long budget) {
        this.text = text;
        this.budget = budget;
    }

    /** Bounds {@code text} at {@link #DEFAULT_BUDGET} reads. */
    public static BoundedCharSequence over(CharSequence text) {
        return new BoundedCharSequence(text, DEFAULT_BUDGET);
    }

    /** Bounds {@code text} at an explicit budget. For tests, which cannot afford ten million. */
    public static BoundedCharSequence over(CharSequence text, long budget) {
        return new BoundedCharSequence(text, budget);
    }

    /** Reads spent so far — the measurement a test asserts on. */
    public long reads() {
        return reads;
    }

    @Override
    public char charAt(int index) {
        if (++reads > budget) {
            throw new RegexBudgetExceededException(budget, text.length());
        }
        return text.charAt(index);
    }

    @Override
    public int length() {
        return text.length();
    }

    /**
     * Bounded like the parent and sharing its counter, so a pattern cannot buy itself an unbounded
     * subject by taking a subsequence. Matcher does this for {@code group()}, which is post-match
     * and cheap — but the guarantee should not depend on that staying true.
     */
    @Override
    public CharSequence subSequence(int start, int end) {
        BoundedCharSequence child = new BoundedCharSequence(text.subSequence(start, end), budget);
        child.reads = reads;
        return child;
    }

    /**
     * The unwrapped text. Deliberately NOT counted: this is what {@code Matcher.group()} and error
     * formatting reach for after a match has already succeeded, and charging for it would make the
     * budget depend on how a caller reads its own result.
     */
    @Override
    public String toString() {
        return text.toString();
    }
}
