package com.pragmaticds.docengine.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import com.pragmaticds.docengine.results.service.EngineResultQueryService;
import com.pragmaticds.docengine.results.service.EngineResultQueryService.VerifiedContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP, authorization, integrity, and audit contract for immutable engine-result reads. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2"})
class EngineResultApiIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MEDIA =
            "application/vnd.pragmaticds.document-engine-result+json;version=1";

    @Autowired MockMvc mockMvc;
    @MockitoSpyBean BlobStoragePort blobs;
    @Autowired DocumentPackageRepository packages;
    @Autowired ProcessingJobRepository jobs;
    @Autowired ProcessingStageRepository stages;
    @Autowired EngineResultRepository results;

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
    void currentUsesExactJobGenerationAndNeverFallsBackToTheMaximumRevision() throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult revision1 = appendResult(fixture, 1, 1, true);
        // A later pipeline position may fail after FINALIZING committed. The immutable parse stays
        // readable; job FAILED is not itself permission to hide or refinalize it.
        jdbc.update("UPDATE processing_job SET status = 'FAILED' WHERE id = ?", fixture.jobId());

        assertContent(current(fixture), revision1);

        jdbc.update(
                "UPDATE processing_job SET parse_generation = 2, attempt = 2 WHERE id = ?",
                fixture.jobId());
        jdbc.update(
                "DELETE FROM processing_stage WHERE job_id = ? AND stage = 'FINALIZING'",
                fixture.jobId());
        insertFinalizingStage(fixture, "FAILED", 1, null);

        current(fixture)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENGINE_RESULT_NOT_READY"))
                .andExpect(jsonPath("$.params").isEmpty());
        assertThat(auditCount(revision1.id())).isEqualTo(1);

        StoredResult revision2 = appendResult(fixture, 2, 2, true);
        assertContent(current(fixture), revision2);
        mockMvc.perform(get("/v1/packages/{id}/engine-results", fixture.packageId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[1].revision").value(2));
        assertContent(historical(fixture, 1), revision1);
    }

    @Test
    void historyIsAscendingApprovedMetadataOnlyAndNeverLoadsBlobs() throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult first = appendResult(fixture, 1, 1, true);
        jdbc.update(
                "UPDATE processing_job SET parse_generation = 2, attempt = 4 WHERE id = ?",
                fixture.jobId());
        jdbc.update(
                "DELETE FROM processing_stage WHERE job_id = ? AND stage = 'FINALIZING'",
                fixture.jobId());
        StoredResult second = appendResult(fixture, 2, 2, true);
        Path firstUnreadable = makeUnreadable(first);
        Path secondUnreadable = makeUnreadable(second);
        try {
            MvcResult response =
                    mockMvc.perform(get("/v1/packages/{id}/engine-results", fixture.packageId()))
                            .andExpect(status().isOk())
                            .andReturn();
            JsonNode body = JSON.readTree(response.getResponse().getContentAsByteArray());

            assertThat(body).hasSize(2);
            assertThat(body.get(0).path("revision").asInt()).isEqualTo(1);
            assertThat(body.get(1).path("revision").asInt()).isEqualTo(2);
            assertThat(toFieldSet(body.get(0)))
                    .containsExactlyInAnyOrder(
                            "revision",
                            "processingJobId",
                            "parseGeneration",
                            "materializedJobAttempt",
                            "envelopeSchemaVersion",
                            "sourceSetSha256",
                            "provenanceSha256",
                            "envelopeSha256",
                            "envelopeSizeBytes",
                            "reuseEligibility",
                            "createdAt");
            assertThat(response.getResponse().getContentAsString())
                    .doesNotContain(first.key(), second.key(), first.id().toString(), "packageId");
            assertThat(auditCount(first.id()) + auditCount(second.id())).isZero();

            Fixture empty = seedPackage(ORG_DEV, false);
            mockMvc.perform(get("/v1/packages/{id}/engine-results", empty.packageId()))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        } finally {
            Files.deleteIfExists(firstUnreadable);
            Files.deleteIfExists(secondUnreadable);
        }
    }

    @Test
    void contentResponsesAreExactStoredBytesWithPinnedHeadersAndOneValueFreeAuditEach()
            throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult result = appendResult(fixture, 1, 1, true);
        int resultRowsBefore = count("engine_result", fixture.packageId());
        int stageRowsBefore = countStages(fixture.jobId());
        int jobAttemptBefore =
                jdbc.queryForObject(
                        "SELECT attempt FROM processing_job WHERE id = ?",
                        Integer.class,
                        fixture.jobId());

        assertContent(current(fixture), result);
        assertContent(historical(fixture, 1), result);

        assertThat(count("engine_result", fixture.packageId())).isEqualTo(resultRowsBefore);
        assertThat(countStages(fixture.jobId())).isEqualTo(stageRowsBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt FROM processing_job WHERE id = ?",
                                Integer.class,
                                fixture.jobId()))
                .isEqualTo(jobAttemptBefore);
        List<Map<String, Object>> audits =
                jdbc.queryForList(
                        "SELECT action, subject_type, subject_id, metadata::text metadata"
                                + " FROM audit_event WHERE subject_type = 'ENGINE_RESULT'"
                                + " AND subject_id = ? ORDER BY id",
                        result.id());
        assertThat(audits).hasSize(2);
        for (Map<String, Object> event : audits) {
            assertThat(event)
                    .containsEntry("action", "ENGINE_RESULT_ACCESSED")
                    .containsEntry("subject_type", "ENGINE_RESULT")
                    .containsEntry("subject_id", result.id());
            JsonNode metadata = JSON.readTree(event.get("metadata").toString());
            assertThat(toFieldSet(metadata))
                    .containsExactlyInAnyOrder(
                            "packageId",
                            "processingJobId",
                            "revision",
                            "envelopeSha256",
                            "byteCount");
            assertThat(event.get("metadata").toString())
                    .doesNotContain(result.key(), new String(result.bytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void missingUnreadableWrongLengthAndWrongDigestBlobsFailClosedWithoutBytesOrAudit()
            throws Exception {
        assertCorrupt(BlobDefect.MISSING);
        assertCorrupt(BlobDefect.UNREADABLE);
        assertCorrupt(BlobDefect.WRONG_LENGTH);
        assertCorrupt(BlobDefect.WRONG_DIGEST);
    }

    @Test
    void currentAndHistoricalContentRejectMalformedAndTenantMismatchedKeysBeforeBlobReadOrAudit()
            throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult result = appendResult(fixture, 1, 1, true);
        String foreignKey =
                "org/"
                        + ORG_OTHER
                        + "/engine-results/sha256/"
                        + result.sha256()
                        + ".json";
        blobs.put(foreignKey, result.bytes());

        dropStorageKeyBindingConstraint();
        try {
            for (String corruptedKey : List.of("engine-results/malformed.json", foreignKey)) {
                jdbc.update(
                        "UPDATE engine_result SET envelope_storage_key = ? WHERE id = ?",
                        corruptedKey,
                        result.id());

                clearInvocations(blobs);
                assertCorrupt(current(fixture));
                verify(blobs, never()).get(anyString());
                assertThat(auditCount(result.id())).isZero();

                clearInvocations(blobs);
                assertCorrupt(historical(fixture, 1));
                verify(blobs, never()).get(anyString());
                assertThat(auditCount(result.id())).isZero();
            }
        } finally {
            jdbc.update(
                    "UPDATE engine_result SET envelope_storage_key = ? WHERE id = ?",
                    result.key(),
                    result.id());
            restoreStorageKeyBindingConstraint();
            blobs.delete(foreignKey);
        }
    }

    @Test
    void currentNotReadyAndCorruptStatesAreStableAndPayloadFree() throws Exception {
        Fixture noJob = seedPackage(ORG_DEV, false);
        assertNotReady(current(noJob));

        Fixture noStage = seedPackageWithJob(ORG_DEV, false, 1);
        assertNotReady(current(noStage));

        Fixture failed = seedPackageWithJob(ORG_DEV, false, 1);
        insertFinalizingStage(failed, "FAILED", 1, null);
        assertNotReady(current(failed));

        Fixture missingDescriptor = seedPackageWithJob(ORG_DEV, false, 1);
        insertFinalizingStage(missingDescriptor, "SUCCEEDED", 1, "c".repeat(64));
        assertCorrupt(current(missingDescriptor));

        Fixture mismatchedDigest = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult stored = appendResult(mismatchedDigest, 1, 1, false);
        insertFinalizingStage(mismatchedDigest, "SUCCEEDED", 1, "d".repeat(64));
        assertCorrupt(current(mismatchedDigest));
        assertThat(auditCount(stored.id())).isZero();

        Fixture duplicateSuccess = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult duplicateResult = appendResult(duplicateSuccess, 1, 1, true);
        insertFinalizingStage(duplicateSuccess, "SUCCEEDED", 2, duplicateResult.sha256());
        assertCorrupt(current(duplicateSuccess));
        assertThat(auditCount(duplicateResult.id())).isZero();
    }

    @Test
    void duplicatePackageJobsFailClosedWithoutGuessingAResultOrCreatingAnAudit() throws Exception {
        Fixture firstJob = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult firstResult = appendResult(firstJob, 1, 1, true);
        Fixture secondJob = seedSecondJob(firstJob, 1);
        StoredResult secondResult = appendResult(secondJob, 2, 1, true);
        int firstStages = countStages(firstJob.jobId());
        int secondStages = countStages(secondJob.jobId());

        MvcResult response =
                current(firstJob)
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("ENGINE_RESULT_CORRUPT"))
                        .andExpect(jsonPath("$.params").isEmpty())
                        .andReturn();

        assertThat(response.getResponse().getContentAsByteArray())
                .isNotEqualTo(firstResult.bytes())
                .isNotEqualTo(secondResult.bytes());
        assertThat(auditCount(firstResult.id()) + auditCount(secondResult.id())).isZero();
        assertThat(count("engine_result", firstJob.packageId())).isEqualTo(2);
        assertThat(countStages(firstJob.jobId())).isEqualTo(firstStages);
        assertThat(countStages(secondJob.jobId())).isEqualTo(secondStages);
        assertThat(blobs.get(firstResult.key())).isEqualTo(firstResult.bytes());
        assertThat(blobs.get(secondResult.key())).isEqualTo(secondResult.bytes());

        ReadDetectingBlobStorage readDetector = new ReadDetectingBlobStorage();
        EngineResultQueryService direct =
                new EngineResultQueryService(packages, jobs, stages, results, readDetector);
        // The preceding MockMvc request correctly clears its request-bound tenant in a finally;
        // bind the direct service probe explicitly.
        TenantContext.set(ORG_DEV);
        assertThatThrownBy(() -> direct.current(firstJob.packageId()))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(ErrorCode.ENGINE_RESULT_CORRUPT);
                            assertThat(failure.params()).isEmpty();
                        });
        assertThat(readDetector.reads()).isZero();
    }

    @Test
    void successfulFinalizingStageWithNullDigestIsCorruptWithoutBytesOrAudit() throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult descriptor = appendResult(fixture, 1, 1, false);
        insertFinalizingStage(fixture, "SUCCEEDED", 1, null);

        MvcResult response =
                current(fixture)
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("ENGINE_RESULT_CORRUPT"))
                        .andExpect(jsonPath("$.params").isEmpty())
                        .andReturn();

        assertThat(response.getResponse().getContentAsByteArray()).isNotEqualTo(descriptor.bytes());
        assertThat(auditCount(descriptor.id())).isZero();
    }

    @Test
    void verifiedContentDefensivelyCopiesConstructorAndAccessorBytes() {
        byte[] canary = "immutable-canary".getBytes(StandardCharsets.UTF_8);
        byte[] expected = canary.clone();
        String expectedDigest = sha256(expected);
        VerifiedContent content =
                new VerifiedContent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        3,
                        expectedDigest,
                        canary);

        canary[0] = 'X';
        byte[] firstRead = content.bytes();
        firstRead[1] = 'Y';

        assertThat(content.bytes()).isEqualTo(expected);
        assertThat(sha256(content.bytes())).isEqualTo(expectedDigest);
    }

    @Test
    void foreignMissingAndTombstonedPackagesAreIndistinguishableBeforeResultResolution()
            throws Exception {
        Fixture foreign = seedPackageWithJob(ORG_OTHER, false, 1);
        appendResult(foreign, 1, 1, true);
        Fixture tombstoned = seedPackageWithJob(ORG_DEV, true, 1);
        appendResult(tombstoned, 1, 1, true);
        UUID missing = UUID.randomUUID();

        for (UUID id : List.of(foreign.packageId(), tombstoned.packageId(), missing)) {
            mockMvc.perform(get("/v1/packages/{id}/engine-result", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mockMvc.perform(get("/v1/packages/{id}/engine-results", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mockMvc.perform(get("/v1/packages/{id}/engine-results/{revision}", id, 1))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void metadataUsesGenericReadRolesButRawContentIsAdminOnly() throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult result = appendResult(fixture, 1, 1, true);

        for (String role : List.of("READONLY", "PROCESSOR", "REVIEWER", "ADMIN")) {
            mockMvc.perform(
                            get("/v1/packages/{id}/engine-results", fixture.packageId())
                                    .header("X-Dev-Role", role))
                    .andExpect(status().isOk());
        }
        for (String role : List.of("READONLY", "PROCESSOR", "REVIEWER")) {
            mockMvc.perform(
                            get("/v1/packages/{id}/engine-result", fixture.packageId())
                                    .header("X-Dev-Role", role))
                    .andExpect(status().isForbidden());
            mockMvc.perform(
                            get(
                                            "/v1/packages/{id}/engine-results/{revision}",
                                            fixture.packageId(),
                                            1)
                                    .header("X-Dev-Role", role))
                    .andExpect(status().isForbidden());
        }
        assertThat(auditCount(result.id())).isZero();
    }

    @Test
    void auditFailurePreventsContentDelivery() throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult result = appendResult(fixture, 1, 1, true);
        String suffix = result.id().toString().replace("-", "");
        String function = "reject_result_audit_" + suffix;
        String trigger = "reject_result_audit_trigger_" + suffix;
        jdbc.execute(
                "CREATE FUNCTION "
                        + function
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " IF NEW.subject_type = 'ENGINE_RESULT' AND NEW.subject_id = '"
                        + result.id()
                        + "'::uuid THEN RAISE EXCEPTION 'synthetic audit outage'; END IF;"
                        + " RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "
                        + function
                        + "()");
        try {
            MvcResult response =
                    current(fixture)
                            .andExpect(status().isInternalServerError())
                            .andExpect(jsonPath("$.code").value("INTERNAL"))
                            .andReturn();

            assertThat(response.getResponse().getContentAsByteArray())
                    .isNotEqualTo(result.bytes());
            assertThat(auditCount(result.id())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON audit_event");
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
    }

    @Test
    void openApiPinsAllThreeAuthenticatedEndpointsMediaAndIntegrityHeaders() throws Exception {
        JsonNode api =
                JSON.readTree(
                        mockMvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());

        JsonNode current = api.at("/paths/~1v1~1packages~1{packageId}~1engine-result/get");
        JsonNode history = api.at("/paths/~1v1~1packages~1{packageId}~1engine-results/get");
        JsonNode revision =
                api.at(
                        "/paths/~1v1~1packages~1{packageId}~1engine-results~1{revision}/get");
        assertThat(current.isMissingNode()).isFalse();
        assertThat(history.isMissingNode()).isFalse();
        assertThat(revision.isMissingNode()).isFalse();
        // Adding this controller must not rename an existing sibling operation through a method
        // name collision in springdoc.
        assertThat(
                        api.at("/paths/~1v1~1documents~1{id}~1history/get/operationId")
                                .asText())
                .isEqualTo("history");
        for (JsonNode endpoint : List.of(current, history, revision)) {
            assertThat(endpoint.path("security").toString()).contains("bearerAuth");
        }
        for (JsonNode endpoint : List.of(current, revision)) {
            JsonNode ok = endpoint.at("/responses/200");
            assertThat(ok.path("content").has(MEDIA)).isTrue();
            assertThat(toFieldSet(ok.path("headers")))
                    .contains("Content-Length", "ETag", "Cache-Control", "X-Content-Type-Options");
            JsonNode schema = ok.path("content").path(MEDIA).path("schema");
            assertThat(schema.path("type").asText()).isEqualTo("object");
            assertThat(schema.has("format")).isFalse();
            assertThat(schema.toString()).doesNotContain("byte", "base64");
        }
    }

    private org.springframework.test.web.servlet.ResultActions current(Fixture fixture)
            throws Exception {
        return mockMvc.perform(get("/v1/packages/{id}/engine-result", fixture.packageId()));
    }

    private org.springframework.test.web.servlet.ResultActions historical(Fixture fixture, int revision)
            throws Exception {
        return mockMvc.perform(
                get(
                        "/v1/packages/{id}/engine-results/{revision}",
                        fixture.packageId(),
                        revision));
    }

    private void assertContent(
            org.springframework.test.web.servlet.ResultActions response, StoredResult expected)
            throws Exception {
        response.andExpect(status().isOk())
                .andExpect(content().contentType(MEDIA))
                .andExpect(header().longValue("Content-Length", expected.bytes().length))
                .andExpect(header().string("ETag", "\"" + expected.sha256() + "\""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(expected.bytes()));
    }

    private void assertNotReady(org.springframework.test.web.servlet.ResultActions response)
            throws Exception {
        response.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENGINE_RESULT_NOT_READY"))
                .andExpect(jsonPath("$.params").isEmpty());
    }

    private void assertCorrupt(org.springframework.test.web.servlet.ResultActions response)
            throws Exception {
        response.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ENGINE_RESULT_CORRUPT"))
                .andExpect(jsonPath("$.params").isEmpty());
    }

    private void assertCorrupt(BlobDefect defect) throws Exception {
        Fixture fixture = seedPackageWithJob(ORG_DEV, false, 1);
        StoredResult result = appendResult(fixture, 1, 1, true);
        Path unreadable = null;
        boolean bindingDropped = false;
        try {
            switch (defect) {
                case MISSING -> blobs.delete(result.key());
                case UNREADABLE -> unreadable = makeUnreadable(result);
                case WRONG_LENGTH ->
                        jdbc.update(
                                "UPDATE engine_result SET envelope_size_bytes ="
                                        + " envelope_size_bytes + 1 WHERE id = ?",
                                result.id());
                case WRONG_DIGEST -> {
                    dropStorageKeyBindingConstraint();
                    bindingDropped = true;
                    String wrong = "f".repeat(64);
                    jdbc.update(
                            "UPDATE engine_result SET envelope_sha256 = ? WHERE id = ?",
                            wrong,
                            result.id());
                    jdbc.update(
                            "UPDATE processing_stage SET output_digest = ? WHERE job_id = ?"
                                    + " AND stage = 'FINALIZING'",
                            wrong,
                            fixture.jobId());
                }
            }
            MvcResult response =
                    current(fixture)
                            .andExpect(status().isInternalServerError())
                            .andExpect(jsonPath("$.code").value("ENGINE_RESULT_CORRUPT"))
                            .andReturn();
            assertThat(response.getResponse().getContentAsByteArray())
                    .isNotEqualTo(result.bytes());
            assertThat(auditCount(result.id())).isZero();
        } finally {
            if (unreadable != null) {
                Files.deleteIfExists(unreadable);
            }
            if (bindingDropped) {
                try {
                    jdbc.update(
                            "UPDATE engine_result SET envelope_sha256 = ? WHERE id = ?",
                            result.sha256(),
                            result.id());
                    jdbc.update(
                            "UPDATE processing_stage SET output_digest = ? WHERE job_id = ?"
                                    + " AND stage = 'FINALIZING'",
                            result.sha256(),
                            fixture.jobId());
                } finally {
                    restoreStorageKeyBindingConstraint();
                }
            }
        }
    }

    private Fixture seedPackage(UUID orgId, boolean tombstoned) {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name, deleted_at, purge_after)"
                        + " VALUES (?, ?, 'synthetic-result-api', ?, ?)",
                packageId,
                orgId,
                tombstoned ? Timestamp.from(Instant.now()) : null,
                tombstoned ? Timestamp.from(Instant.now().plusSeconds(3600)) : null);
        return new Fixture(packageId, null, orgId);
    }

    private Fixture seedPackageWithJob(UUID orgId, boolean tombstoned, int parseGeneration) {
        Fixture packageOnly = seedPackage(orgId, tombstoned);
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO processing_job"
                        + " (id, org_id, package_id, idempotency_key, status, attempt, parse_generation)"
                        + " VALUES (?, ?, ?, ?, 'HUMAN_REVIEW_REQUIRED', 1, ?)",
                jobId,
                orgId,
                packageOnly.packageId(),
                "result-api-" + jobId,
                parseGeneration);
        return new Fixture(packageOnly.packageId(), jobId, orgId);
    }

    private Fixture seedSecondJob(Fixture existing, int parseGeneration) {
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO processing_job"
                        + " (id, org_id, package_id, idempotency_key, status, attempt, parse_generation)"
                        + " VALUES (?, ?, ?, ?, 'HUMAN_REVIEW_REQUIRED', 1, ?)",
                jobId,
                existing.orgId(),
                existing.packageId(),
                "result-api-second-" + jobId,
                parseGeneration);
        return new Fixture(existing.packageId(), jobId, existing.orgId());
    }

    private StoredResult appendResult(
            Fixture fixture, int revision, int generation, boolean successfulStage) {
        byte[] bytes =
                ("{\"package\":\""
                                + fixture.packageId()
                                + "\",\"revision\":"
                                + revision
                                + "}")
                        .getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        String key =
                "org/"
                        + fixture.orgId()
                        + "/engine-results/sha256/"
                        + digest
                        + ".json";
        UUID id = UUID.randomUUID();
        UUID predecessor =
                revision == 1
                        ? null
                        : jdbc.queryForObject(
                                "SELECT id FROM engine_result WHERE package_id = ? AND revision = ?",
                                UUID.class,
                                fixture.packageId(),
                                revision - 1);
        blobs.put(key, bytes);
        jdbc.update(
                """
                INSERT INTO engine_result
                    (id, org_id, package_id, processing_job_id, parse_generation,
                     materialized_job_attempt, revision, supersedes_result_id,
                     envelope_schema_version, canonicalization_version, canonical_media_type,
                     source_set_sha256, provenance_sha256, envelope_storage_key, envelope_sha256,
                     envelope_size_bytes, reuse_eligibility)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1.0.0', 'DOCENGINE-C14N-1', ?, ?, ?, ?, ?, ?,
                        'PARSE_ONCE_CURRENT_PACKAGE')
                """,
                id,
                fixture.orgId(),
                fixture.packageId(),
                fixture.jobId(),
                generation,
                generation,
                revision,
                predecessor,
                MEDIA,
                "a".repeat(64),
                "b".repeat(64),
                key,
                digest,
                bytes.length);
        if (successfulStage) {
            insertFinalizingStage(fixture, "SUCCEEDED", generation, digest);
        }
        return new StoredResult(id, key, digest, bytes);
    }

    private void insertFinalizingStage(Fixture fixture, String status, int attempt, String digest) {
        jdbc.update(
                "INSERT INTO processing_stage"
                        + " (id, org_id, job_id, stage, status, attempt, output_digest)"
                        + " VALUES (?, ?, ?, 'FINALIZING', ?, ?, ?)",
                UUID.randomUUID(),
                fixture.orgId(),
                fixture.jobId(),
                status,
                attempt,
                digest);
    }

    private int auditCount(UUID resultId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE subject_type = 'ENGINE_RESULT'"
                        + " AND subject_id = ?",
                Integer.class,
                resultId);
    }

    private int count(String table, UUID packageId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE package_id = ?", Integer.class, packageId);
    }

    private int countStages(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processing_stage WHERE job_id = ?", Integer.class, jobId);
    }

    private void dropStorageKeyBindingConstraint() {
        jdbc.execute(
                "ALTER TABLE engine_result"
                        + " DROP CONSTRAINT IF EXISTS engine_result_storage_key_binding_check");
    }

    private void restoreStorageKeyBindingConstraint() {
        jdbc.execute(
                "ALTER TABLE engine_result ADD CONSTRAINT engine_result_storage_key_binding_check"
                        + " CHECK (envelope_storage_key = 'org/' || org_id::text"
                        + " || '/engine-results/sha256/' || envelope_sha256::text || '.json')");
    }

    /** Replaces the synthetic blob file with a directory so the real adapter throws on read. */
    private Path makeUnreadable(StoredResult result) throws java.io.IOException {
        blobs.delete(result.key());
        Path path =
                Path.of(System.getProperty("user.dir"), "build", "test-blobs")
                        .toAbsolutePath()
                        .resolve(result.key())
                        .normalize();
        Files.createDirectories(path);
        return path;
    }

    private static Set<String> toFieldSet(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private enum BlobDefect {
        MISSING,
        UNREADABLE,
        WRONG_LENGTH,
        WRONG_DIGEST
    }

    private record Fixture(UUID packageId, UUID jobId, UUID orgId) {}

    private record StoredResult(UUID id, String key, String sha256, byte[] bytes) {
        private StoredResult {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** A blob read is a test failure; duplicate-job resolution must stop before this boundary. */
    private static final class ReadDetectingBlobStorage implements BlobStoragePort {
        private final AtomicInteger reads = new AtomicInteger();

        int reads() {
            return reads.get();
        }

        @Override
        public byte[] get(String key) {
            reads.incrementAndGet();
            throw new AssertionError("duplicate-job resolution reached blob storage");
        }

        @Override
        public void put(String key, byte[] content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putImmutable(String key, byte[] content, String expectedSha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }
    }
}
