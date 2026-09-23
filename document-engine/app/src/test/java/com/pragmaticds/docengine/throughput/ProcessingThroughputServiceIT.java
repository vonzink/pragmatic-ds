package com.pragmaticds.docengine.throughput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seconds per page are measured from THIS org's completed jobs only, and fall back to the
 * seeded defaults when there is nothing to measure. Rows are planted with JdbcTemplate so the
 * test controls every duration and text layer exactly.
 */
class ProcessingThroughputServiceIT extends AbstractPostgresIT {

    @Autowired ProcessingThroughputService service;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        // The throughput query aggregates over ALL of an org's recent jobs, so — unlike
        // most ITs, which only ever look at rows by id — it is sensitive to rows any
        // earlier test in the shared container left behind for ORG_DEV, the fixed org every
        // other IT in the suite runs as (DevTenantFilter). See AbstractPostgresIT#wipeOrgData
        // for the FK ordering (engine_result before processing_job, layout_element_span before
        // its two FK targets, everything before page and document_package).
        wipeOrgData(ORG_DEV, ORG_OTHER);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void with_no_completed_jobs_the_seeded_defaults_are_reported_and_marked_unmeasured() {
        Throughput t = service.current();

        assertThat(t.measured()).isFalse();
        assertThat(t.sampleJobs()).isZero();
        assertThat(t.ocrSecondsPerPage()).isEqualTo(70.0);
        assertThat(t.otherSecondsPerPage()).isEqualTo(2.0);
        assertThat(t.fixedSecondsPerJob()).isEqualTo(10.0);
    }

    @Test
    void rates_are_stage_seconds_over_the_pages_those_stages_worked_on() {
        // Job A: 4 pages, 3 scanned + 1 native. OCR took 210s -> 70 s per OCR'd page.
        // Render 8s + text 4s + parsing 4s = 16s over 4 pages -> 4 s per page.
        // Validating 1s + classifying 3s + splitting 1s + extracting 2s + finalizing 1s = 8s fixed.
        UUID packageA = plantPackage(ORG_DEV);
        UUID jobA = plantCompletedJob(ORG_DEV, packageA);
        plantPages(ORG_DEV, packageA, "SCANNED", "SCANNED", "SCANNED", "NATIVE");
        plantStage(ORG_DEV, jobA, "OCR_PROCESSING", 210_000);
        plantStage(ORG_DEV, jobA, "RENDERING", 8_000);
        plantStage(ORG_DEV, jobA, "TEXT_EXTRACTION", 4_000);
        plantStage(ORG_DEV, jobA, "PARSING", 4_000);
        plantStage(ORG_DEV, jobA, "VALIDATING", 1_000);
        plantStage(ORG_DEV, jobA, "CLASSIFYING", 3_000);
        plantStage(ORG_DEV, jobA, "SPLITTING", 1_000);
        plantStage(ORG_DEV, jobA, "EXTRACTING", 2_000);
        plantStage(ORG_DEV, jobA, "FINALIZING", 1_000);

        // Job B: 2 native pages, no OCR stage at all. Render+text+parsing 4s -> 2 s per page; fixed 4s.
        UUID packageB = plantPackage(ORG_DEV);
        UUID jobB = plantCompletedJob(ORG_DEV, packageB);
        plantPages(ORG_DEV, packageB, "NATIVE", "NATIVE");
        plantStage(ORG_DEV, jobB, "RENDERING", 2_000);
        plantStage(ORG_DEV, jobB, "TEXT_EXTRACTION", 1_000);
        plantStage(ORG_DEV, jobB, "PARSING", 1_000);
        plantStage(ORG_DEV, jobB, "CLASSIFYING", 4_000);

        Throughput t = service.current();

        assertThat(t.measured()).isTrue();
        assertThat(t.sampleJobs()).isEqualTo(2);
        // OCR: 210s over the 3 OCR'd pages (job B contributes none).
        assertThat(t.ocrSecondsPerPage()).isCloseTo(70.0, within(0.01));
        // Other: (16s + 4s) over (4 + 2) pages.
        assertThat(t.otherSecondsPerPage()).isCloseTo(20.0 / 6.0, within(0.01));
        // Fixed: (8s + 4s) over 2 jobs.
        assertThat(t.fixedSecondsPerJob()).isCloseTo(6.0, within(0.01));
    }

    @Test
    void two_jobs_on_the_same_package_do_not_double_count_its_pages() {
        // Resume/regroup can create more than one completed job for the same package. The page
        // count must come from the DISTINCT packages those jobs touched, not once per job.
        UUID packageId = plantPackage(ORG_DEV);
        plantPages(ORG_DEV, packageId, "SCANNED", "SCANNED");
        UUID jobOne = plantCompletedJob(ORG_DEV, packageId);
        plantStage(ORG_DEV, jobOne, "OCR_PROCESSING", 100_000);
        UUID jobTwo = plantCompletedJob(ORG_DEV, packageId);
        plantStage(ORG_DEV, jobTwo, "OCR_PROCESSING", 200_000);

        Throughput t = service.current();

        assertThat(t.sampleJobs()).isEqualTo(2);
        // 300s of OCR over the package's 2 pages (not 4 — the pages are not planted twice).
        assertThat(t.ocrSecondsPerPage()).isCloseTo(150.0, within(0.01));
    }

    @Test
    void another_orgs_jobs_never_enter_the_measurement() {
        UUID packageOther = plantPackage(ORG_OTHER);
        UUID jobOther = plantCompletedJob(ORG_OTHER, packageOther);
        plantPages(ORG_OTHER, packageOther, "SCANNED");
        plantStage(ORG_OTHER, jobOther, "OCR_PROCESSING", 5_000);

        Throughput t = service.current();

        assertThat(t.measured()).isFalse();
        assertThat(t.sampleJobs()).isZero();
    }

    @Test
    void a_failed_job_and_a_blank_page_do_not_count() {
        UUID packageId = plantPackage(ORG_DEV);
        UUID jobId = plantCompletedJob(ORG_DEV, packageId);
        plantPages(ORG_DEV, packageId, "SCANNED", "NONE");
        plantStage(ORG_DEV, jobId, "OCR_PROCESSING", 70_000);
        UUID failedPackage = plantPackage(ORG_DEV);
        UUID failedJob = plantJob(ORG_DEV, failedPackage, "FAILED");
        plantPages(ORG_DEV, failedPackage, "SCANNED", "SCANNED");
        plantStage(ORG_DEV, failedJob, "OCR_PROCESSING", 1_000);

        Throughput t = service.current();

        assertThat(t.sampleJobs()).isEqualTo(1);
        // One OCR'd page (the NONE page is excluded), 70s.
        assertThat(t.ocrSecondsPerPage()).isCloseTo(70.0, within(0.01));
    }

    // ── planting helpers: every NOT NULL column without a default is supplied ──────────

    private UUID plantPackage(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)", id, orgId, "tp-" + id);
        return id;
    }

    private UUID plantCompletedJob(UUID orgId, UUID packageId) {
        return plantJob(orgId, packageId, "HUMAN_REVIEW_REQUIRED");
    }

    private UUID plantJob(UUID orgId, UUID packageId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, now() - interval '10 minutes', now() - interval '1 minute')
                """,
                id, orgId, packageId, "tp-" + id, status);
        return id;
    }

    private void plantStage(UUID orgId, UUID jobId, String stage, long durationMs) {
        jdbc.update(
                """
                INSERT INTO processing_stage (org_id, job_id, stage, status, attempt, started_at, finished_at, duration_ms)
                VALUES (?, ?, ?, 'SUCCEEDED', 1, now() - interval '5 minutes', now() - interval '4 minutes', ?)
                """,
                orgId, jobId, stage, durationMs);
    }

    private void plantPages(UUID orgId, UUID packageId, String... textLayers) {
        UUID fileId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename, content_type,
                    size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'tp.pdf', 'application/pdf', 1, ?, ?)
                """,
                fileId, orgId, packageId, "0".repeat(64), orgId + "/" + packageId + "/" + fileId + "/original");
        for (int i = 0; i < textLayers.length; i++) {
            jdbc.update(
                    """
                    INSERT INTO page (org_id, source_file_id, package_id, page_index, package_page_index,
                        width_pt, height_pt, text_layer)
                    VALUES (?, ?, ?, ?, ?, 612, 792, ?)
                    """,
                    orgId, fileId, packageId, i, i, textLayers[i]);
        }
    }
}
