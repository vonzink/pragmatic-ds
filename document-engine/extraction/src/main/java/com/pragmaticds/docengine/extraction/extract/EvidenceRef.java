package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.classification.match.Box;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One evidence box an extractor produced — becomes one {@code field_evidence} row. The box is
 * carried by VALUE here (denormalized downstream, D10): span and element ids may be nulled by a
 * later reparse, the coordinates never move.
 *
 * @param spanId the supporting text span, null when the evidence is element-level only
 * @param layoutElementId the supporting layout element (TABLE_CLUSTER cells), null otherwise
 * @param spanConfidence the span's OCR confidence, used by the confidence formula
 */
public record EvidenceRef(Long spanId, UUID layoutElementId, Box box, BigDecimal spanConfidence) {}
