package com.pragmaticds.docengine.extraction.schema;

/**
 * A SIGNATURE_PRESENCE search window, expressed as per-side growth of the region label's box in
 * canonical points. Canonical space is top-left origin with y increasing DOWNWARD, so
 * {@code above} grows toward smaller y and {@code below} toward larger y.
 */
public record Window(double left, double right, double above, double below) {}
