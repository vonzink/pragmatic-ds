package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What one discussion turn cost, priced against the run's pinned catalog version.
 *
 * <p>A discussion turn answers from a run's saved context rather than re-executing it, but it is
 * still a billed provider call, so it is accounted for exactly like a run's own usage: absent
 * categories stay null, an absent report is UNAVAILABLE rather than zero, and the row reports once.
 *
 * <p>There is no estimate here, because a discussion turn is not budgeted ahead of time — which is
 * also why this cannot share {@code lab_model_usage}'s trigger and has its own.
 */
@Entity
@Table(name = "lab_discussion_model_usage")
public class LabDiscussionModelUsage {

    @Id
    private UUID id;

    @Column(name = "exchange_id", nullable = false)
    private UUID exchangeId;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "pricing_version_id", nullable = false)
    private UUID pricingVersionId;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 160)
    private String model;

    @Column(name = "actual_input_tokens")
    private Long actualInputTokens;

    @Column(name = "actual_cached_tokens")
    private Long actualCachedTokens;

    @Column(name = "actual_output_tokens")
    private Long actualOutputTokens;

    @Column(name = "actual_total_tokens")
    private Long actualTotalTokens;

    @Column(name = "actual_cost_usd", precision = 18, scale = 6)
    private BigDecimal actualCostUsd;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_quality", nullable = false, length = 16)
    private UsageQuality usageQuality = UsageQuality.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "reported_at")
    private OffsetDateTime reportedAt;

    protected LabDiscussionModelUsage() {}

    public LabDiscussionModelUsage(UUID exchangeId, UUID brainId, UUID pricingVersionId,
                                   String provider, String model) {
        this.exchangeId = exchangeId;
        this.brainId = brainId;
        this.pricingVersionId = pricingVersionId;
        this.provider = provider;
        this.model = model;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public void report(UsageQuality quality, Long inputTokens, Long cachedTokens,
                       Long outputTokens, long totalTokens, BigDecimal costUsd) {
        if (quality != UsageQuality.REPORTED && quality != UsageQuality.INFERRED) {
            throw new IllegalArgumentException("report() records measured usage only");
        }
        this.usageQuality = quality;
        this.actualInputTokens = inputTokens;
        this.actualCachedTokens = cachedTokens;
        this.actualOutputTokens = outputTokens;
        this.actualTotalTokens = totalTokens;
        this.actualCostUsd = costUsd;
        this.reportedAt = OffsetDateTime.now();
    }

    public void unavailable() {
        this.usageQuality = UsageQuality.UNAVAILABLE;
    }

    public UUID getId() { return id; }
    public UUID getExchangeId() { return exchangeId; }
    public UUID getBrainId() { return brainId; }
    public UUID getPricingVersionId() { return pricingVersionId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Long getActualInputTokens() { return actualInputTokens; }
    public Long getActualCachedTokens() { return actualCachedTokens; }
    public Long getActualOutputTokens() { return actualOutputTokens; }
    public Long getActualTotalTokens() { return actualTotalTokens; }
    public BigDecimal getActualCostUsd() { return actualCostUsd; }
    public UsageQuality getUsageQuality() { return usageQuality; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReportedAt() { return reportedAt; }
}
