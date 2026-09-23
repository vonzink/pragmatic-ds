package com.pragmaticds.rag.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key for {@link BrainDailyUsage}: (brainId, usageDate). */
public class BrainDailyUsageId implements Serializable {

    private UUID brainId;
    private LocalDate usageDate;

    public BrainDailyUsageId() {}

    public BrainDailyUsageId(UUID brainId, LocalDate usageDate) {
        this.brainId = brainId;
        this.usageDate = usageDate;
    }

    public UUID getBrainId() { return brainId; }
    public LocalDate getUsageDate() { return usageDate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrainDailyUsageId that)) return false;
        return Objects.equals(brainId, that.brainId)
                && Objects.equals(usageDate, that.usageDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(brainId, usageDate);
    }
}
