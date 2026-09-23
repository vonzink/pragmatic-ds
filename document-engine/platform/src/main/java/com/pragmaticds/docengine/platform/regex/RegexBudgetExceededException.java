package com.pragmaticds.docengine.platform.regex;

/**
 * A pattern consumed its step budget mid-match and was abandoned.
 *
 * <p>Unchecked, because it is thrown from {@link CharSequence#charAt} deep inside
 * {@code java.util.regex.Matcher} — the only place a running match can be stopped — and no
 * signature between here and the call site can declare it.
 *
 * <p>Carries the budget and the subject's length, never the pattern and never the subject. A
 * pathological pattern is authored content and the subject is borrower text; an exception message
 * is exactly the sort of place both leak from.
 */
public final class RegexBudgetExceededException extends RuntimeException {

    private final long budget;
    private final int subjectLength;

    public RegexBudgetExceededException(long budget, int subjectLength) {
        super("regex step budget " + budget + " exhausted over a subject of " + subjectLength
                + " chars");
        this.budget = budget;
        this.subjectLength = subjectLength;
    }

    public long budget() {
        return budget;
    }

    public int subjectLength() {
        return subjectLength;
    }
}
