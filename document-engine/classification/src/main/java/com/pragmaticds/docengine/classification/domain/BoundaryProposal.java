package com.pragmaticds.docengine.classification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * One model-proposed document boundary and the verdict the anchoring gates reached (V26, Phase D).
 * APPEND-ONLY: a proposal is a recorded fact about what the model said and what the engine
 * decided; nothing updates it, and only the retention purge deletes it.
 *
 * <p>Not a {@code TenantScopedEntity}: append-only means no {@code updated_at} column exists to
 * map (same reasoning as {@code ClassificationResult}). Still {@code @TenantId org_id}, so
 * application-layer tenant filtering and RLS both apply.
 *
 * <p>ACCEPTED rows are load-bearing, not just audit: {@code PackageSplitter.split} reads them on
 * every run, which is what lets a replayed SPLITTING converge on the model's cuts without
 * re-calling the model (roadmap R2). {@code pageId} is a plain uuid, no FK — a reprocess rotates
 * page ids, and a stale accepted row must degrade to a no-op, never to an error.
 */
@Entity
@Table(name = "boundary_proposal")
public class BoundaryProposal {

    public static final String VERDICT_ACCEPTED = "ACCEPTED";
    public static final String VERDICT_REJECTED_QUOTE_MATCH = "REJECTED_QUOTE_MATCH";
    public static final String VERDICT_REJECTED_OUT_OF_WINDOW = "REJECTED_OUT_OF_WINDOW";
    public static final String VERDICT_REJECTED_OVERRIDE = "REJECTED_OVERRIDE";
    public static final String VERDICT_REJECTED_TRANSPARENT = "REJECTED_TRANSPARENT";
    public static final String VERDICT_REJECTED_CONFIDENCE_FLOOR = "REJECTED_CONFIDENCE_FLOOR";

    @Id @GeneratedValue private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "package_page_index", nullable = false, updatable = false)
    private int packagePageIndex;

    @Column(name = "page_id", updatable = false)
    private UUID pageId;

    @Column(name = "proposed_type_code", updatable = false)
    private String proposedTypeCode;

    @Column(name = "confidence", updatable = false)
    private BigDecimal confidence;

    @Column(name = "quoted_header_text", updatable = false)
    private String quotedHeaderText;

    @Column(name = "partition_value", updatable = false)
    private String partitionValue;

    @Column(name = "window_start_index", updatable = false)
    private Integer windowStartIndex;

    @Column(name = "window_end_index", updatable = false)
    private Integer windowEndIndex;

    @Column(name = "verdict", nullable = false, updatable = false)
    private String verdict;

    @Column(name = "input_tokens", updatable = false)
    private Long inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Long outputTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BoundaryProposal() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public BoundaryProposal(
            UUID packageId,
            UUID jobId,
            int packagePageIndex,
            UUID pageId,
            String proposedTypeCode,
            BigDecimal confidence,
            String quotedHeaderText,
            String partitionValue,
            Integer windowStartIndex,
            Integer windowEndIndex,
            String verdict,
            Long inputTokens,
            Long outputTokens) {
        this.packageId = packageId;
        this.jobId = jobId;
        this.packagePageIndex = packagePageIndex;
        this.pageId = pageId;
        this.proposedTypeCode = proposedTypeCode;
        this.confidence = confidence;
        this.quotedHeaderText = quotedHeaderText;
        this.partitionValue = partitionValue;
        this.windowStartIndex = windowStartIndex;
        this.windowEndIndex = windowEndIndex;
        this.verdict = verdict;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getPackagePageIndex() {
        return packagePageIndex;
    }

    public UUID getPageId() {
        return pageId;
    }

    public String getProposedTypeCode() {
        return proposedTypeCode;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getQuotedHeaderText() {
        return quotedHeaderText;
    }

    public String getPartitionValue() {
        return partitionValue;
    }

    public Integer getWindowStartIndex() {
        return windowStartIndex;
    }

    public Integer getWindowEndIndex() {
        return windowEndIndex;
    }

    public String getVerdict() {
        return verdict;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
