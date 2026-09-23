package com.pragmaticds.docengine.results.canonical;

/**
 * A machine snapshot or envelope refused by one of the loader's or assembler's invariants.
 *
 * <p>The {@link #reason()} is structural by construction — member names, row kinds, never a stored
 * value — which is what lets {@code DefaultEngineResultFinalizer} publish it as failure detail. An
 * arbitrary {@link IllegalStateException} carries no such guarantee (its message may quote
 * document content), so the finalizer keeps mapping those to a reason-only, payload-free error.
 * Without a named reason, MACHINE_SNAPSHOT_UNAVAILABLE was anonymous across roughly forty throw
 * sites, and the 2026-09-07 production failure took an investigation to name.
 */
public final class MachineSnapshotInconsistentException extends IllegalStateException {

    private final String reason;

    public MachineSnapshotInconsistentException(String reason) {
        super("Machine snapshot is inconsistent: " + reason);
        this.reason = reason;
    }

    /** The violated invariant, in structural terms only. */
    public String reason() {
        return reason;
    }
}
