package com.pragmaticds.docengine.classification.rules;

/**
 * How an anchor pattern is matched against the page's joined reading-order text. The v1 pack
 * format ships {@code literal} and {@code regex}; {@code positional} is reserved by the plan and
 * arrives with a pack-format bump, not a guess here.
 */
public enum AnchorKind {
    /** Case-insensitive containment. */
    LITERAL,
    /** {@code java.util.regex} over the joined text, flags exactly as authored in the pattern. */
    REGEX;

    /** Pack jsonb uses lowercase kind names. */
    public static AnchorKind fromWire(String wire) {
        return valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
