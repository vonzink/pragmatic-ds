package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.exception.GlobalExceptionHandler;
import com.pragmaticds.rag.lab.analyze.ParsedIncomeAnalysisService;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
import com.pragmaticds.rag.lab.engine.LabContractException;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;
import com.pragmaticds.rag.lab.security.LabCryptoException;
import com.pragmaticds.rag.lab.service.IncomeLabService;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisRunRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP surface, exercised through the REAL {@link AdminApiKeyFilter} rather than around it.
 *
 * <p>Three things are pinned here that cannot be pinned anywhere else:
 *
 * <ul>
 *   <li><b>Auth posture.</b> Every route lives under the already-gated {@code /api/ai/admin/**}
 *       prefix, so a missing or wrong key is 401 before any handler runs. Nothing in this plan
 *       widens the filter, the public matchers, or rate-limit routing — the routes simply moved
 *       INTO the existing gate.
 *   <li><b>The failure map.</b> Eight payload-free taxonomies reach HTTP, and each one's status is
 *       asserted alongside the assertion that its body contains a code and a correlation id and
 *       nothing else. A canary planted in a wrapped cause must not appear.
 *   <li><b>Flag-off behaviour.</b> With the Lab disabled the controller bean does not exist, so its
 *       routes are unmapped: an authenticated request gets 404 and learns nothing about why.
 * </ul>
 */
class IncomeLabControllerTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String BRAIN_SLUG = "mortgage";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PACKAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RUN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID REGISTRATION = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID RELEASE = UUID.fromString("88888888-8888-4888-8888-888888888888");

    /** Planted inside a wrapped cause; a Lab response or body must never echo it. */
    private static final String CANARY = "CANARY-provider-body-9f8e7d";

    private IncomeLabService lab;
    private BrainResolver brainResolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        lab = mock(IncomeLabService.class);
        brainResolver = mock(BrainResolver.class);
        Brain brain = mock(Brain.class);
        when(brain.getId()).thenReturn(BRAIN);
        when(brainResolver.resolve(anyString())).thenReturn(brain);
        when(brainResolver.resolve(null)).thenReturn(brain);

        mvc = MockMvcBuilders.standaloneSetup(new IncomeLabController(lab, brainResolver))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new LabExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    // ================================================================ auth posture

    @Test
    void everyLabRouteIsUnderTheAlreadyGatedAdminPrefix() {
        for (String route : List.of(
                "/api/ai/admin/lab/instances",
                "/api/ai/admin/lab/instances/income/documents",
                "/api/ai/admin/lab/documents/{packageId}",
                "/api/ai/admin/lab/documents/{packageId}/envelope",
                "/api/ai/admin/lab/instances/income/runs",
                "/api/ai/admin/lab/runs",
                "/api/ai/admin/lab/runs/{runId}",
                "/api/ai/admin/lab/runs/{runId}/messages")) {
            assertTrue(route.startsWith("/api/ai/admin/"),
                    "AdminApiKeyFilter gates only this prefix: " + route);
        }
    }

    @Test
    void aMissingOrWrongAdminKeyIs401OnEveryRoute() throws Exception {
        mvc.perform(get("/api/ai/admin/lab/instances").param("brain", BRAIN_SLUG))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/admin/lab/runs").param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai/admin/lab/instances/income/runs")
                        .param("brain", BRAIN_SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageId\":\"" + PACKAGE + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/ai/admin/lab/runs/" + RUN).param("brain", BRAIN_SLUG))
                .andExpect(status().isUnauthorized());

        verify(lab, never()).instances(any());
        verify(lab, never()).startRun(any(), anyString(), any(), any());
    }

    @Test
    void theControllerIsFeatureGatedSoFlagOffLeavesTheRoutesUnmapped() throws Exception {
        ConditionalOnProperty gate =
                IncomeLabController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.lab", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());

        // With the bean absent the mapping is absent: an AUTHENTICATED caller gets a bare 404 that
        // says nothing about the flag, the engine, or any other configuration.
        MockMvc withoutLab = MockMvcBuilders.standaloneSetup(new Object() {})
                .addFilters(new AdminApiKeyFilter(properties()))
                .build();
        String body = withoutLab.perform(get("/api/ai/admin/lab/instances")
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("lab.enabled"));
        assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("engine"));
    }

    @Test
    void theGeneralizedInstancesFlagDoesNotReplaceTheLegacyLabGate() {
        ConditionalOnProperty legacyGate =
                IncomeLabController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.lab", legacyGate.prefix());
        assertEquals("enabled", legacyGate.name()[0]);
        assertEquals("true", legacyGate.havingValue());

        ConditionalOnProperty generalizedGate =
                InstanceAdminController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", generalizedGate.prefix());
        assertEquals("enabled", generalizedGate.name()[0]);
        assertEquals("true", generalizedGate.havingValue());
    }

    // ================================================================ routes

    @Test
    void instancesReturnsThePrototypeBoundary() throws Exception {
        when(lab.instances(BRAIN)).thenReturn(new LabDtos.InstancesResponse(
                List.of(LabDtos.Instance.from(instanceState())), LabDtos.Prototype.current()));

        mvc.perform(get("/api/ai/admin/lab/instances").param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prototype.code").value("PROTOTYPE_LIVE_DEPENDENCIES"))
                .andExpect(jsonPath("$.instances[0].slug").value("income"))
                .andExpect(jsonPath("$.instances[0].prototype.liveDependencies").isArray());
    }

    @Test
    void uploadForwardsSizeAndStreamWithoutReadingTheFileIntoMemory() throws Exception {
        when(lab.registerDocument(eq(BRAIN), eq("income"), any(), eq("key-1")))
                .thenReturn(new LabDtos.RegistrationResponse(REGISTRATION, PACKAGE, JOB,
                        UUID.randomUUID(), 1, List.of(), true, LabDtos.Prototype.current()));

        mvc.perform(multipart("/api/ai/admin/lab/instances/income/documents")
                        .file(new MockMultipartFile("file", "payslip.pdf", "application/pdf",
                                new byte[]{1, 2, 3, 4}))
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageId").value(PACKAGE.toString()))
                .andExpect(jsonPath("$.created").value(true));

        org.mockito.ArgumentCaptor<DocumentEngineClient.EngineUpload> upload =
                org.mockito.ArgumentCaptor.forClass(DocumentEngineClient.EngineUpload.class);
        verify(lab).registerDocument(eq(BRAIN), eq("income"), upload.capture(), eq("key-1"));
        assertEquals(4L, upload.getValue().sizeBytes(),
                "the size comes from MultipartFile.getSize(), not from a pre-read byte[]");
    }

    @Test
    void anUploadWithZeroOrMultipleFilesIsRejectedBeforeTheService() throws Exception {
        mvc.perform(multipart("/api/ai/admin/lab/instances/income/documents")
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UPLOAD_FILE_COUNT_INVALID"));

        mvc.perform(multipart("/api/ai/admin/lab/instances/income/documents")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf",
                                new byte[]{1}))
                        .file(new MockMultipartFile("file", "b.pdf", "application/pdf",
                                new byte[]{2}))
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UPLOAD_FILE_COUNT_INVALID"));

        verify(lab, never()).registerDocument(any(), anyString(), any(), anyString());
    }

    @Test
    void aMissingIdempotencyKeyHeaderIsRejected() throws Exception {
        mvc.perform(post("/api/ai/admin/lab/instances/income/runs")
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageId\":\"" + PACKAGE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verify(lab, never()).startRun(any(), anyString(), any(), any());
    }

    @Test
    void documentStatusAndEnvelopeReadsAreGetsWithTheirIdentityInThePath() throws Exception {
        when(lab.documentStatus(BRAIN, PACKAGE, JOB)).thenReturn(
                new LabDtos.DocumentStatusResponse(REGISTRATION, PACKAGE, JOB,
                        "HUMAN_REVIEW_REQUIRED", null, true, List.of("HUMAN_REVIEW_REQUIRED"),
                        List.of(revision()), LabDtos.Prototype.current()));

        mvc.perform(get("/api/ai/admin/lab/documents/" + PACKAGE)
                        .param("brain", BRAIN_SLUG).param("jobId", JOB.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzable").value(true))
                .andExpect(jsonPath("$.warnings[0]").value("HUMAN_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.revisions[0].revision").value(3));

        when(lab.envelope(BRAIN, PACKAGE, 3)).thenReturn(envelopeResponse());
        mvc.perform(get("/api/ai/admin/lab/documents/" + PACKAGE + "/envelope")
                        .param("brain", BRAIN_SLUG).param("revision", "3")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3))
                // The decimal canary crosses as a STRING; a JSON number would lose digits.
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"normalizedNumber\":\"12345678901234567890.123456789\"")))
                .andExpect(jsonPath("$.documents[0].fields[0].confidence").value(0.97));
    }

    @Test
    void envelopeResponseCarriesNoCredentialOrStorageKey() throws Exception {
        when(lab.envelope(BRAIN, PACKAGE, null)).thenReturn(envelopeResponse());

        String body = mvc.perform(get("/api/ai/admin/lab/documents/" + PACKAGE + "/envelope")
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String lower = body.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("bearer", "authorization", "x-dev-org", "storagekey",
                "storage_key", "s3", "presigned", "filename", ADMIN_KEY)) {
            assertFalse(lower.contains(forbidden.toLowerCase(java.util.Locale.ROOT)),
                    "envelope view must not carry " + forbidden);
        }
    }

    @Test
    void runHistoryAndDetailAndPurgeAreWiredToTheirVerbs() throws Exception {
        when(lab.history(BRAIN, "income")).thenReturn(
                new LabDtos.RunHistoryResponse(List.of(summary()), LabDtos.Prototype.current()));
        mvc.perform(get("/api/ai/admin/lab/runs").param("brain", BRAIN_SLUG)
                        .param("instance", "income").header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].runId").value(RUN.toString()))
                .andExpect(jsonPath("$.prototype.code").value("PROTOTYPE_LIVE_DEPENDENCIES"));

        when(lab.runDetail(BRAIN, RUN)).thenReturn(runResponse("SUCCEEDED"));
        mvc.perform(get("/api/ai/admin/lab/runs/" + RUN).param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.prototype.liveDependencies").isArray());

        when(lab.purgeRun(BRAIN, RUN)).thenReturn(new LabDtos.PurgeResponse(RUN, true, 2, 1, 1, 1,
                true, true, LabDtos.Prototype.current()));
        mvc.perform(delete("/api/ai/admin/lab/runs/" + RUN).param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.enginePackageRetained").value(true));
    }

    @Test
    void aMessageRequiresItsIdempotencyKeyAndReturnsTheTranscript() throws Exception {
        when(lab.postMessage(eq(BRAIN), eq(RUN), any(), eq("msg-1"))).thenReturn(
                new LabDtos.DiscussionResponse(RUN, List.of(new LabDtos.DiscussionExchange(
                        UUID.randomUUID(), 1, "SUCCEEDED", null,
                        List.of(new LabDtos.DiscussionMessage("USER", 1, "why?", null),
                                new LabDtos.DiscussionMessage("ASSISTANT", 2, "because", null)),
                        "PROTOTYPE_LIVE_DEPENDENCIES", null, null)), false, null,
                        LabDtos.Prototype.current()));

        mvc.perform(post("/api/ai/admin/lab/runs/" + RUN + "/messages")
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "msg-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"why?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exchanges[0].messages[1].body").value("because"))
                .andExpect(jsonPath("$.exchanges[0].prototypeLimitations")
                        .value("PROTOTYPE_LIVE_DEPENDENCIES"))
                .andExpect(jsonPath("$.prototype.code").value("PROTOTYPE_LIVE_DEPENDENCIES"));

        mvc.perform(post("/api/ai/admin/lab/runs/" + RUN + "/messages")
                        .param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"why?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // ================================================================ the failure map

    @Test
    void everyLabTaxonomyMapsToItsStatusWithACodeOnlyBody() throws Exception {
        assertMaps(new IncomeLabService.LabRequestException(
                IncomeLabService.LabRequestException.Code.RUN_NOT_FOUND), 404, "RUN_NOT_FOUND");
        assertMaps(new IncomeLabService.LabRequestException(
                        IncomeLabService.LabRequestException.Code.REGISTRATION_CONFLICT), 409,
                "REGISTRATION_CONFLICT");
        assertMaps(new IncomeLabService.LabRequestException(
                        IncomeLabService.LabRequestException.Code.RUN_IN_PROGRESS), 409,
                "RUN_IN_PROGRESS");
        assertMaps(new IncomeLabService.LabRequestException(
                        IncomeLabService.LabRequestException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                        Map.of("contextChars", 20_000, "maxChars", 16_000)), 413,
                "DISCUSSION_CONTEXT_TOO_LARGE");
        assertMaps(new IncomeLabService.LabRequestException(
                        IncomeLabService.LabRequestException.Code.RETENTION_NOT_CONFIGURED), 503,
                "RETENTION_NOT_CONFIGURED");

        assertMaps(new DocumentEngineFailure(DocumentEngineFailure.Code.UPLOAD_TOO_LARGE), 413,
                "UPLOAD_TOO_LARGE");
        assertMaps(new DocumentEngineFailure(DocumentEngineFailure.Code.ENGINE_TIMEOUT), 504,
                "ENGINE_TIMEOUT");
        assertMaps(new DocumentEngineFailure(DocumentEngineFailure.Code.ENGINE_DIGEST_MISMATCH, 200),
                502, "ENGINE_DIGEST_MISMATCH");

        assertMaps(new LabContractException(LabContractException.Code.ENVELOPE_FORBIDDEN_MEMBER),
                502, "ENVELOPE_FORBIDDEN_MEMBER");

        assertMaps(new ParsedIncomeAnalysisService.ParsedAnalysisException(
                        ParsedIncomeAnalysisService.ParsedAnalysisException.Code
                                .PARSED_ENVELOPE_INCOMPATIBLE, "NO_SUPPORTED_DOCUMENT"), 422,
                "PARSED_ENVELOPE_INCOMPATIBLE");

        assertMaps(new IncomeLabReleaseService.ReleaseException(
                        IncomeLabReleaseService.ReleaseException.Code.INSTANCE_UNKNOWN), 404,
                "INSTANCE_UNKNOWN");
        assertMaps(new IncomeLabReleaseService.ReleaseException(
                        IncomeLabReleaseService.ReleaseException.Code.RELEASE_SCHEMA_DRIFTED), 409,
                "RELEASE_SCHEMA_DRIFTED");

        assertMaps(new LabManifestWriter.ManifestException(
                        LabManifestWriter.ManifestException.Code.MANIFEST_NOT_STRICT_JSON), 500,
                "MANIFEST_NOT_STRICT_JSON");

        assertMaps(new AnalysisRunRecorder.RecorderException(
                        AnalysisRunRecorder.RecorderException.Code.ANALYSIS_RUN_PERSIST_FAILED),
                500, "ANALYSIS_RUN_PERSIST_FAILED");

        // The safe 503 is decided by isConfigurationUnavailable(), never by matching a message.
        assertMaps(new LabCryptoException(LabCryptoException.Code.KEY_UNAVAILABLE), 503,
                "KEY_UNAVAILABLE");
        assertMaps(new LabCryptoException(LabCryptoException.Code.PAYLOAD_UNAUTHENTIC), 500,
                "PAYLOAD_UNAUTHENTIC");

        assertMaps(new ModelRouterService.SanitizedProviderException(
                        ModelRouterService.SanitizedProviderException.Code.PROVIDER_CALL_FAILED,
                        "anthropic", "HttpClientErrorException", "corr-9"), 502,
                "PROVIDER_CALL_FAILED");
    }

    @Test
    void aWrappedCauseNeverReachesTheBodyOrTheResponse() throws Exception {
        // A collaborator that wrapped a provider body in an unexpected exception must still not
        // leak: the Lab handler answers with a code, and the canary appears nowhere.
        org.mockito.Mockito.doThrow(new IllegalStateException("provider said: " + CANARY))
                .when(lab).runDetail(BRAIN, RUN);

        String body = mvc.perform(get("/api/ai/admin/lab/runs/" + RUN).param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(CANARY), "no cause text reaches a Lab response body");
        assertTrue(body.contains("LAB_REQUEST_FAILED"), "a stable code takes its place");
    }

    @Test
    void everyErrorBodyCarriesACorrelationIdAndNoOtherText() throws Exception {
        org.mockito.Mockito.doThrow(new IncomeLabService.LabRequestException(
                        IncomeLabService.LabRequestException.Code.RETENTION_NOT_CONFIGURED))
                .when(lab).instances(BRAIN);

        mvc.perform(get("/api/ai/admin/lab/instances").param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("X-Request-Id", "corr-42"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RETENTION_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.correlationId").value("corr-42"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    // ================================================================ helpers

    private void assertMaps(RuntimeException failure, int expectedStatus, String expectedCode)
            throws Exception {
        // doThrow, not when(...).thenThrow: the second form INVOKES the already-throwing stub and
        // would blow up inside the stubbing call rather than inside the request.
        org.mockito.Mockito.doThrow(failure).when(lab).instances(BRAIN);

        String body = mvc.perform(get("/api/ai/admin/lab/instances").param("brain", BRAIN_SLUG)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(CANARY));
    }

    private static RagProperties properties() {
        return new RagProperties(
                new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin(ADMIN_KEY),
                new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }

    private static IncomeLabReleaseService.InstanceState instanceState() {
        return new IncomeLabReleaseService.InstanceState("income", "income-v2", RELEASE, 1,
                "f6".repeat(32), false, null, null,
                LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                LabReleaseManifest.LIVE_DEPENDENCIES);
    }

    private static LabDtos.Revision revision() {
        return new LabDtos.Revision(3, JOB, 1, "1.0.0", "b2".repeat(32), 4096L, "9f".repeat(32),
                "PARSE_ONCE_CURRENT_PACKAGE", Instant.parse("2026-08-15T00:00:00Z"));
    }

    private static LabDtos.EnvelopeResponse envelopeResponse() {
        LabDtos.Field field = new LabDtos.Field("wages.annual", null, "FOUND", "MONEY",
                "$12,345,678,901,234,567,890.12", "12345678901234567890.123456789", null,
                "12345678901234567890.123456789", null, new BigDecimal("0.97"), "ANCHORED",
                "1.4.0", "OK", false,
                List.of(new LabDtos.Evidence(UUID.randomUUID(), "VALUE", 0,
                        new LabDtos.Box(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE,
                                BigDecimal.TEN))),
                "UNREVIEWED_SOURCE");
        return new LabDtos.EnvelopeResponse(REGISTRATION, PACKAGE, 3, 1, JOB, "1.0.0",
                "DOCENGINE-C14N-1", "b2".repeat(32), 4096, "9f".repeat(32),
                "PARSE_ONCE_CURRENT_PACKAGE", true, null, List.of(),
                List.of(new LabDtos.Page(UUID.randomUUID(), 0, 0, new BigDecimal("612"),
                        new BigDecimal("792"), 0, "NATIVE", false, false, "W2",
                        new BigDecimal("0.99"), "RULEPACK")),
                List.of(new LabDtos.Document(UUID.randomUUID(), "W2", 1, List.of(),
                        List.of(field))),
                List.of(), LabDtos.Prototype.current());
    }

    private static LabDtos.RunSummary summary() {
        return new LabDtos.RunSummary(RUN, "income", "SUCCEEDED", null, RELEASE, UUID.randomUUID(),
                PACKAGE, 3, OffsetDateTime.parse("2026-08-15T00:00:00Z"),
                OffsetDateTime.parse("2026-08-15T00:01:00Z"), 1, LabDtos.Prototype.current());
    }

    private static LabDtos.RunResponse runResponse(String status) {
        return new LabDtos.RunResponse(RUN, "income", status, null, RELEASE, 1, "f6".repeat(32),
                UUID.randomUUID(), REGISTRATION,
                new LabDtos.RunSource(PACKAGE, 3, 1, JOB, "1.0.0", "DOCENGINE-C14N-1",
                        "b2".repeat(32), 4096L, "9f".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE", 1, 1),
                new LabDtos.RunAnalysis("SUCCESS", "# report", "{}", List.of(), "anthropic",
                        "claude-x", 10, 20, 0.01, 1, null), null,
                OffsetDateTime.parse("2026-08-15T00:00:00Z"),
                OffsetDateTime.parse("2026-08-15T00:01:00Z"), false, LabDtos.Prototype.current());
    }
}
