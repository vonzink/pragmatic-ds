package com.pragmaticds.docengine.parsing.client;

/**
 * Marker for the wire DTO package. The records in this package mirror docs/WORKER_CONTRACT.md
 * verbatim: every coordinate is CANONICAL (PDF points, top-left origin, rotation-0, 0.1pt) and is
 * carried as {@link java.math.BigDecimal} precisely so the Java side stores what the worker sent —
 * never a re-derived double that could drift (contract invariant 1).
 */
final class WireTypes {
    private WireTypes() {}
}
