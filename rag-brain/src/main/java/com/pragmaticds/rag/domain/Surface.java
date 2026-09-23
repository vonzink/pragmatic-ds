package com.pragmaticds.rag.domain;

/**
 * Audience a record applies to (spec §6.1 D4). Reused by page guides in a later
 * phase. Stored as the bare enum name via @Enumerated(EnumType.STRING).
 */
public enum Surface {
    PUBLIC,     // borrower-facing only
    INTERNAL,   // staff-facing only
    BOTH        // both audiences
}
