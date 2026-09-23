package com.pragmaticds.docengine.extraction.schema;

/**
 * How to capture the value once the extractor has located its anchor.
 *
 * @param pattern regex run over the scope's joined text (ORIGINAL text — never a case-folded
 *     copy, whose length can drift and shift offsets)
 * @param occurrence 0-based match index within the scope — how "Pay Period: 01/01/2026 -
 *     01/15/2026" yields both a start (occurrence 0) and an end (occurrence 1)
 * @param joinNextLine an ANCHOR_LABEL or REGEX rung's next-line join (bank statement field rules,
 *     task 4): when the visual line after the captured value's last span matches this pattern
 *     WHOLE, that whole match — not a capture group inside it — is appended to the value and its
 *     spans join the evidence. Whole match, deliberately: a joint holder's second line prints as
 *     {@code OR DIEGO R LOPEZ}, and the "OR" is part of what the page shows and what the
 *     evidence must cite, so a pattern's own group (if it has one, e.g. to constrain the name
 *     shape) is never consulted for what gets appended. Null for every rung that predates this
 *     task, and for every rung that still doesn't need it.
 */
public record ValueSpec(String pattern, int occurrence, ValueScope scope, int lineOffset, String joinNextLine) {
    public ValueSpec(String pattern, int occurrence, ValueScope scope) {
        this(pattern, occurrence, scope, 0, null);
    }

    public ValueSpec(String pattern, int occurrence, ValueScope scope, int lineOffset) {
        this(pattern, occurrence, scope, lineOffset, null);
    }
}
