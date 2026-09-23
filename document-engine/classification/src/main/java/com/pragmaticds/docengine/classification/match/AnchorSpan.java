package com.pragmaticds.docengine.classification.match;

import java.math.BigDecimal;

/**
 * The matcher's view of one text span: id, text, and canonical box — deliberately NOT the JPA
 * entity, so the matcher unit-tests against hand-built spans with real ids (an unpersisted {@code
 * TextSpan} has none) and never learns about persistence.
 */
public record AnchorSpan(
        Long id, String text, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {

    public Box box() {
        return new Box(x, y, width, height);
    }
}
