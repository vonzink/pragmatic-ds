package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-brain, per-day accumulated LLM usage backing the spend-cap circuit breaker
 * (design: per-brain daily spend cap). One row per (brain, day); accumulated by
 * SpendGuardService.recordSpend via an UPSERT after every successful paid model
 * call. See {@link BrainDailyUsageId} for the composite primary key.
 */
@Entity
@Table(name = "brain_daily_usage")
@IdClass(BrainDailyUsageId.class)
public class BrainDailyUsage {

    @Id
    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Id
    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "cost_estimate_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costEstimateUsd = BigDecimal.ZERO;

    protected BrainDailyUsage() {}

    public BrainDailyUsage(UUID brainId,
                            LocalDate usageDate,
                            int requestCount,
                            long promptTokens,
                            long completionTokens,
                            BigDecimal costEstimateUsd) {
        this.brainId = brainId;
        this.usageDate = usageDate;
        this.requestCount = requestCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.costEstimateUsd = costEstimateUsd;
    }

    public UUID getBrainId() { return brainId; }
    public LocalDate getUsageDate() { return usageDate; }
    public int getRequestCount() { return requestCount; }
    public void setRequestCount(int requestCount) { this.requestCount = requestCount; }
    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
    public BigDecimal getCostEstimateUsd() { return costEstimateUsd; }
    public void setCostEstimateUsd(BigDecimal costEstimateUsd) { this.costEstimateUsd = costEstimateUsd; }
}
