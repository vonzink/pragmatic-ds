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
 * What one run was expected to cost, and — once the provider answers — what it did.
 *
 * <p>The estimate is immutable, including through the one legal report. An estimate that could be
 * rewritten after the fact would make every budget decision unauditable: you could never tell
 * afterwards whether a run was approved because it looked cheap or looked cheap because it was
 * approved. V39 enforces that with a trigger; this class simply has no setter for it.
 *
 * <p>Reporting happens exactly once. {@link #report} refuses to invent numbers and
 * {@link #unavailable()} refuses to invent zeros.
 */
@Entity
@Table(name = "lab_model_usage")
public class LabModelUsage {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "pricing_version_id", nullable = false)
    private UUID pricingVersionId;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 160)
    private String model;

    @Column(name = "expected_input_min", nullable = false)
    private long expectedInputMin;

    @Column(name = "expected_input_max", nullable = false)
    private long expectedInputMax;

    @Column(name = "expected_output_min", nullable = false)
    private long expectedOutputMin;

    @Column(name = "expected_output_max", nullable = false)
    private long expectedOutputMax;

    @Column(name = "expected_cost_usd_min", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedCostUsdMin;

    @Column(name = "expected_cost_usd_max", nullable = false, precision = 18, scale = 6)
    private BigDecimal expectedCostUsdMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "estimate_quality", nullable = false, length = 24)
    private EstimateQuality estimateQuality;

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

    protected LabModelUsage() {}

    public LabModelUsage(UUID runId, UUID brainId, UUID pricingVersionId, String provider,
                         String model, long expectedInputMin, long expectedInputMax,
                         long expectedOutputMin, long expectedOutputMax,
                         BigDecimal expectedCostUsdMin, BigDecimal expectedCostUsdMax,
                         EstimateQuality estimateQuality) {
        this.runId = runId;
        this.brainId = brainId;
        this.pricingVersionId = pricingVersionId;
        this.provider = provider;
        this.model = model;
        this.expectedInputMin = expectedInputMin;
        this.expectedInputMax = expectedInputMax;
        this.expectedOutputMin = expectedOutputMin;
        this.expectedOutputMax = expectedOutputMax;
        this.expectedCostUsdMin = expectedCostUsdMin;
        this.expectedCostUsdMax = expectedCostUsdMax;
        this.estimateQuality = estimateQuality;
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

    /**
     * Records what the provider actually reported. Every category the provider omitted stays null
     * rather than becoming zero, which is why the individual counts are nullable and the total is
     * not: a report with no total is not a report.
     */
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

    /** The provider told us nothing. Deliberately writes no numbers at all — not even zeros. */
    public void unavailable() {
        this.usageQuality = UsageQuality.UNAVAILABLE;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public UUID getBrainId() { return brainId; }
    public UUID getPricingVersionId() { return pricingVersionId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getExpectedInputMin() { return expectedInputMin; }
    public long getExpectedInputMax() { return expectedInputMax; }
    public long getExpectedOutputMin() { return expectedOutputMin; }
    public long getExpectedOutputMax() { return expectedOutputMax; }
    public BigDecimal getExpectedCostUsdMin() { return expectedCostUsdMin; }
    public BigDecimal getExpectedCostUsdMax() { return expectedCostUsdMax; }
    public EstimateQuality getEstimateQuality() { return estimateQuality; }
    public Long getActualInputTokens() { return actualInputTokens; }
    public Long getActualCachedTokens() { return actualCachedTokens; }
    public Long getActualOutputTokens() { return actualOutputTokens; }
    public Long getActualTotalTokens() { return actualTotalTokens; }
    public BigDecimal getActualCostUsd() { return actualCostUsd; }
    public UsageQuality getUsageQuality() { return usageQuality; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReportedAt() { return reportedAt; }
}
