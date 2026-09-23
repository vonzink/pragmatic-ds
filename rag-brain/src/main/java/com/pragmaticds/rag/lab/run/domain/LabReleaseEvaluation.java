package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One evaluation of one immutable release against one versioned scenario set.
 *
 * <p>Only the digest, score, and verdict are stored. The report itself contains model responses to
 * synthetic borrower documents, so it lives outside the database and is referenced by hash — which
 * is also what makes "this release passed" checkable rather than merely asserted.
 *
 * <p>Immutable, and scoped to an exact scenario-set version. A release that passed version 1 has
 * not passed version 2, so a promotion gate cannot be satisfied by a stale result.
 *
 * <p>Declared {@code @Immutable} with {@code updatable = false} columns so Hibernate can never
 * compose an {@code UPDATE} against this append-only row; see commit {@code 6517c35} and
 * {@link com.pragmaticds.rag.lab.domain.LabInstanceRelease} for the dirty-check round-trip that
 * made the declaration load-bearing.
 */
@Entity
@Immutable
@Table(name = "lab_release_evaluation")
public class LabReleaseEvaluation {

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "release_id", nullable = false, updatable = false)
    private UUID releaseId;

    @Column(name = "scenario_set_id", nullable = false, updatable = false, length = 64)
    private String scenarioSetId;

    @Column(name = "scenario_set_version", nullable = false, updatable = false)
    private int scenarioSetVersion;

    @Column(nullable = false, updatable = false, precision = 6, scale = 4)
    private BigDecimal score;

    @Column(nullable = false, updatable = false)
    private boolean passed;

    @Column(name = "report_sha256", nullable = false, updatable = false, length = 64)
    private String reportSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LabReleaseEvaluation() {}

    public LabReleaseEvaluation(UUID brainId, UUID releaseId, String scenarioSetId,
                                int scenarioSetVersion, BigDecimal score, boolean passed,
                                String reportSha256) {
        this.brainId = brainId;
        this.releaseId = releaseId;
        this.scenarioSetId = scenarioSetId;
        this.scenarioSetVersion = scenarioSetVersion;
        this.score = score;
        this.passed = passed;
        this.reportSha256 = reportSha256;
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

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public UUID getReleaseId() { return releaseId; }
    public String getScenarioSetId() { return scenarioSetId; }
    public int getScenarioSetVersion() { return scenarioSetVersion; }
    public BigDecimal getScore() { return score; }
    public boolean isPassed() { return passed; }
    public String getReportSha256() { return reportSha256; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
