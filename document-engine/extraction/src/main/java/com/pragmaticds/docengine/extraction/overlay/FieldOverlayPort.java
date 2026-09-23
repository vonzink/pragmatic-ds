package com.pragmaticds.docengine.extraction.overlay;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The read-time overlay seam that lets the extraction read endpoints show the CORRECTED value of a
 * field without {@code :extraction} depending on {@code :review}.
 *
 * <p>The core principle (docs/ARCHITECTURE.md 5-6): the machine's Layer-2 {@code extracted_field}
 * value is never overwritten — a correction is a Layer-4 {@code review_decision} row, and the
 * EFFECTIVE value is derived at READ time. {@code :review} depends on {@code :extraction}, never
 * the reverse, so the overlay is defined HERE as a port and IMPLEMENTED in {@code :review}. The
 * extraction controllers inject it optionally ({@code ObjectProvider}): with no implementation on
 * the classpath they render the raw machine value, exactly as before this port existed.
 */
public interface FieldOverlayPort {

    /**
     * The effective (human-corrected) displayed value for each of the given current fields.
     *
     * @param fieldIds {@code extracted_field} ids
     * @return a map keyed only by the fields that have a latest {@code CORRECT} decision; a field
     *     absent from the map has no correction and keeps its machine value
     */
    Map<UUID, String> effectiveValues(Collection<UUID> fieldIds);
}
