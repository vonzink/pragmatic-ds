package com.pragmaticds.docengine.classification.match;

import java.math.BigDecimal;

/** A canonical-space box (PDF points, top-left, rotation-0) carried verbatim into evidence. */
public record Box(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}
