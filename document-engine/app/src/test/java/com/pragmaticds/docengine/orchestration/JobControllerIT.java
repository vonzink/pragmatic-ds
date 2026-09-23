package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The REST face of the job machine. Requests run as ORG_DEV via DevTenantFilter, so the
 * cross-tenant case plants a row under ORG_OTHER by SQL and proves the API answers 404 — a miss,
 * not a existence leak (the house rule from TenantScopedEntity).
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class JobControllerIT extends AbstractPostgresIT {

    @Autowired TestRestTemplate rest;
    @Autowired JobService jobService;
    @Autowired StubParserAdapter stub;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bindTenantAndResetStub() {
        TenantContext.set(ORG_DEV);
        stub.reset();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID insertPackage(UUID orgId) {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                orgId,
                "rest-package");
        return packageId;
    }

    @Test
    void get_returns_the_job_with_its_ordered_stage_list() {
        ProcessingJob job =
                jobService.createJob(insertPackage(ORG_DEV), "rest-happy-" + UUID.randomUUID());

        ResponseEntity<JsonNode> response =
                rest.getForEntity("/v1/jobs/" + job.getId(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("id").asText()).isEqualTo(job.getId().toString());
        assertThat(body.get("packageId").asText()).isEqualTo(job.getPackageId().toString());
        assertThat(body.get("status").asText()).isEqualTo("HUMAN_REVIEW_REQUIRED");
        assertThat(body.get("attempt").asInt()).isEqualTo(1);
        assertThat(body.get("createdAt").isNull()).isFalse();
        assertThat(body.get("startedAt").isNull()).isFalse();
        assertThat(body.get("finishedAt").isNull()).isFalse();

        JsonNode stages = body.get("stages");
        // 10 executed stages + the two gated model stages + 2 skipped placeholders, in
        // pipeline order.
        assertThat(stages).hasSize(14);
        assertThat(stages.get(0).get("stage").asText()).isEqualTo("VALIDATING");
        assertThat(stages.get(0).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(stages.get(0).get("attempt").asInt()).isEqualTo(1);
        assertThat(stages.get(0).get("durationMs").isNull()).isFalse();
        assertThat(stages.get(8).get("stage").asText()).isEqualTo("BOUNDARY_EXTRACTION");
        assertThat(stages.get(8).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(stages.get(8).get("skipReason").asText())
                .isEqualTo("BOUNDARY_EXTRACTION_DISABLED");
        assertThat(stages.get(10).get("stage").asText()).isEqualTo("AI_EXTRACTION");
        assertThat(stages.get(10).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(stages.get(10).get("skipReason").asText()).isEqualTo("AI_DISABLED");
        assertThat(stages.get(11).get("stage").asText()).isEqualTo("FINALIZING");
        assertThat(stages.get(11).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(stages.get(12).get("stage").asText()).isEqualTo("VALIDATING_DATA");
        assertThat(stages.get(12).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(stages.get(12).get("skipReason").asText()).isEqualTo("SPEC_4_NOT_IMPLEMENTED");
        assertThat(stages.get(13).get("stage").asText()).isEqualTo("AI_REVIEW");
        assertThat(stages.get(13).get("skipReason").asText()).isEqualTo("SPEC_5_NOT_IMPLEMENTED");
    }

    @Test
    void failed_stage_rows_expose_the_error_code_over_rest() {
        ProcessingJob job =
                jobService.createJob(
                        insertPackage(ORG_DEV), UUID.randomUUID() + "/fail-hard:RENDERING");

        JsonNode body = rest.getForEntity("/v1/jobs/" + job.getId(), JsonNode.class).getBody();

        assertThat(body.get("status").asText()).isEqualTo("FAILED");
        assertThat(body.get("currentStage").asText()).isEqualTo("RENDERING");
        JsonNode stages = body.get("stages");
        long failedRenderRows = 0;
        for (JsonNode stage : stages) {
            if ("RENDERING".equals(stage.get("stage").asText())) {
                assertThat(stage.get("status").asText()).isEqualTo("FAILED");
                assertThat(stage.get("errorCode").asText()).isEqualTo("RENDER_FAILED");
                failedRenderRows++;
            }
        }
        assertThat(failedRenderRows).isEqualTo(3);
    }

    @Test
    void resume_replays_a_failed_job_and_returns_202() {
        stub.failStage(ProcessingStatus.RENDERING, 3);
        ProcessingJob job =
                jobService.createJob(insertPackage(ORG_DEV), "rest-resume-" + UUID.randomUUID());
        stub.clearFailures();

        ResponseEntity<JsonNode> response =
                rest.postForEntity("/v1/jobs/" + job.getId() + "/resume", null, JsonNode.class);

        // 202 means ACCEPTED-and-queued: dispatch is deferred to after the resume transaction
        // commits (review finding — an eager dispatch raced its own claim), so the response body
        // reports the claimed/queued state, never a completed one.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UPLOADED");
        assertThat(response.getBody().get("attempt").asInt()).isEqualTo(2);

        // The pipeline then actually runs: the follow-up read shows the terminal state.
        ResponseEntity<JsonNode> after =
                rest.getForEntity("/v1/jobs/" + job.getId(), JsonNode.class);
        assertThat(after.getBody().get("status").asText()).isEqualTo("HUMAN_REVIEW_REQUIRED");
    }

    @Test
    void resume_on_a_job_that_is_not_failed_conflicts() {
        ProcessingJob job =
                jobService.createJob(insertPackage(ORG_DEV), "rest-conflict-" + UUID.randomUUID());

        ResponseEntity<JsonNode> response =
                rest.postForEntity("/v1/jobs/" + job.getId() + "/resume", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void another_orgs_job_is_a_404_miss_not_an_existence_leak() {
        UUID packageId = insertPackage(ORG_OTHER);
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO processing_job (id, org_id, package_id, idempotency_key) VALUES (?, ?, ?, ?)",
                jobId,
                ORG_OTHER,
                packageId,
                "other-org-" + UUID.randomUUID());

        ResponseEntity<JsonNode> response = rest.getForEntity("/v1/jobs/" + jobId, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }
}
