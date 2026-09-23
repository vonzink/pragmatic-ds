package com.pragmaticds.docengine.extraction.overlay;

import java.util.List;
import java.util.UUID;

/**
 * The WRITE half of the review seam, the mirror of {@link FieldOverlayPort}'s read half: when a
 * re-extraction replaces the row a human's correction was bound to, this re-attaches the correction
 * to the replacement.
 *
 * <p>Direction of dependency is the same as the read port and for the same reason — {@code :review}
 * depends on {@code :extraction}, never the reverse — so the interface lives here and the
 * implementation lives in {@code :review}. Extraction injects it through an {@code ObjectProvider};
 * with {@code :review} off the classpath there are no decisions to carry and nothing happens.
 *
 * <p>The implementation APPENDS: a carried correction is a new {@code review_decision} row naming
 * the new field id, credited to the human who originally made the call, with the carry recorded in
 * its reason. Nothing is ever rewritten in place, so the audit trail shows both the original
 * decision and the fact that a re-extraction carried it.
 */
public interface FieldDecisionCarryForwardPort {

    /**
     * One replaced-to-replacement pair whose decisions should follow.
     *
     * @param previousFieldId the {@code extracted_field} id the existing decisions name
     * @param newFieldId the replacement row at the same coordinate
     * @param machineValue the replacement's own machine reading — recorded as the carried
     *     decision's {@code previous_value}, so the trail still shows what the human's value is
     *     standing in front of
     */
    record Carry(UUID previousFieldId, UUID newFieldId, String machineValue) {}

    /**
     * Re-attaches each pair's latest correction, if it has one.
     *
     * @return the number of corrections actually carried (a pair whose decisions were CONFIRM or
     *     REJECT only has no value to carry and contributes nothing)
     */
    int carryForward(List<Carry> carries);
}
