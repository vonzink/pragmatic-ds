package com.pragmaticds.rag.domain;

/**
 * Lifecycle of a source-weight change. Stored as VARCHAR(16) in
 * brain_source_weight_events.status.
 * <ul>
 *   <li>APPLIED  — job applied the change automatically.</li>
 *   <li>PENDING  — proposed change routed to the admin review queue.</li>
 *   <li>APPROVED — admin approved a pending proposal (weight then applied).</li>
 *   <li>REJECTED — admin discarded a pending proposal.</li>
 *   <li>REVERTED — weight deleted via reset (back to neutral 1.0).</li>
 * </ul>
 */
public enum WeightEventStatus {
    APPLIED, PENDING, APPROVED, REJECTED, REVERTED;

    public static boolean isValid(String raw) {
        if (raw == null) return false;
        for (WeightEventStatus s : values()) {
            if (s.name().equals(raw)) return true;
        }
        return false;
    }
}
