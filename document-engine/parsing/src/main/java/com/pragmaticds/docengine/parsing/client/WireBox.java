package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;

/** A rectangle in canonical space — {@code {x, y, width, height}} exactly as on the wire. */
public record WireBox(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}
