package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase 5 acceptance criterion 4: the pipeline stages Phase 5 does NOT implement —
 * VALIDATING_DATA (Spec 4) and AI_REVIEW (Spec 5) — must appear as SKIPPED stage rows with their
 * explicit reasons after a full pipeline run, never silently absent. Runs the real StageRunner
 * through JobService with the stub adapter and a same-thread executor (the lightest full-pipeline
 * path — same context as OrchestrationPipelineIT).
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class SkippedStagesRecordedIT extends AbstractPostgresIT {

    @Autowired JobService jobService;
    @Autowired ProcessingStageRepository stageRepository;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void validating_data_and_ai_review_are_recorded_skipped_with_reasons() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "skipped-stages-it");

        ProcessingJob job = jobService.createJob(packageId, "skipped-" + UUID.randomUUID());

        List<ProcessingStage> rows = stageRepository.findByJobIdOrderByCreatedAtAsc(job.getId());
        ProcessingStage validatingData = onlyRowFor(rows, ProcessingStatus.VALIDATING_DATA);
        assertThat(validatingData.getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(validatingData.getSkipReason()).isEqualTo("SPEC_4_NOT_IMPLEMENTED");

        ProcessingStage aiReview = onlyRowFor(rows, ProcessingStatus.AI_REVIEW);
        assertThat(aiReview.getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(aiReview.getSkipReason()).isEqualTo("SPEC_5_NOT_IMPLEMENTED");
    }

    private static ProcessingStage onlyRowFor(List<ProcessingStage> rows, ProcessingStatus stage) {
        List<ProcessingStage> matching = rows.stream().filter(r -> r.getStage() == stage).toList();
        assertThat(matching).as("exactly one %s stage row", stage).hasSize(1);
        return matching.get(0);
    }
}
