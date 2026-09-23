package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A CHECKBOX or SIGNATURE layout element projected for the detector-backed rungs
 * (CHECKBOX_STATE / SIGNATURE_PRESENCE). Detections are ordinary layout elements (Spec 3 D1);
 * this is their engine-facing shape — id and box ready to become an element-backed VALUE
 * evidence row (layout_element_id set, no span).
 *
 * @param checked the worker's {@code attributes.checked} — CHECKBOX only; null for SIGNATURE,
 *     and null for a checkbox row whose attributes carry no verdict (never guessed here)
 * @param confidence the detector's per-element confidence — it occupies the spanConfidence slot
 *     of the confidence formula when a detection backs a value
 */
public record DetectionRef(
        UUID elementId, LayoutElementType type, Box box, Boolean checked, BigDecimal confidence) {}
