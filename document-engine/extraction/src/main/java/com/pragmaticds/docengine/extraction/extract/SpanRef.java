package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.classification.match.Box;
import java.math.BigDecimal;

/**
 * A text span projected for extraction — deliberately NOT the JPA entity, so extractors unit-test
 * against hand-built spans with real ids (an unpersisted {@code TextSpan} has none). Same design
 * as classification's {@code AnchorSpan}.
 *
 * @param confidence per-span OCR confidence; {@code BigDecimal.ONE} for native text
 */
public record SpanRef(Long id, String text, Box box, BigDecimal confidence) {}
