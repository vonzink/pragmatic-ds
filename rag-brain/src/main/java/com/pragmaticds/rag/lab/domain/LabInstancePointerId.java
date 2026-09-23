package com.pragmaticds.rag.lab.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link LabInstancePointer}: {@code (brainId, instanceSlug)}.
 *
 * <p>The key <em>is</em> the "exactly one production release per brain and instance" rule — being
 * the primary key, it makes a second production pointer impossible rather than merely unlikely.
 */
public class LabInstancePointerId implements Serializable {

    private UUID brainId;
    private String instanceSlug;

    public LabInstancePointerId() {}

    public LabInstancePointerId(UUID brainId, String instanceSlug) {
        this.brainId = brainId;
        this.instanceSlug = instanceSlug;
    }

    public UUID getBrainId() { return brainId; }
    public String getInstanceSlug() { return instanceSlug; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LabInstancePointerId that)) return false;
        return Objects.equals(brainId, that.brainId)
                && Objects.equals(instanceSlug, that.instanceSlug);
    }

    @Override
    public int hashCode() {
        return Objects.hash(brainId, instanceSlug);
    }
}
