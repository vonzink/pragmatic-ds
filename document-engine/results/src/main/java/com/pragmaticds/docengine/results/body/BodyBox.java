package com.pragmaticds.docengine.results.body;

import java.math.BigDecimal;

/**
 * One box in the engine's canonical coordinate space: PDF points, top-left origin, rotation-0.
 *
 * <p>Declared here rather than reused from {@code parsing.web.PageStructureView.BoxView} on
 * purpose. That record is another module's WIRE shape, and a body composed against it would inherit
 * every future change to the L2 contract as a change to this one. The numbers are the same numbers;
 * the coupling is what is avoided.
 *
 * <p>{@link BigDecimal} rather than {@code double}, matching {@code LayoutElement}: the worker
 * rounds to 0.1pt and the stored scale IS the value. A double would reintroduce a representation
 * the persisted record deliberately does not have.
 */
public record BodyBox(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}
