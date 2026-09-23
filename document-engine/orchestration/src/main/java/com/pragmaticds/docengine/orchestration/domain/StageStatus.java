package com.pragmaticds.docengine.orchestration.domain;

/**
 * Lifecycle of one {@code processing_stage} attempt row. SKIPPED is deliberate and explicit — a
 * stage that Spec 1 does not implement records that fact with a reason; it never pretends to have
 * run (V3 check constraint mirrors these values).
 */
public enum StageStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
