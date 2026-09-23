package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.domain.LabDocumentRegistration;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.lab.parsed.ParsedInputSelection;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for feature-gated, brain-scoped parsed-input administration. */
class ParsedInputControllerTest {
    private static final String ADMIN_KEY = "test-admin-key";
    private static final String KEY = "idempotency-key-1";
    private static final String INSTANCE = "income";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PACKAGE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REGISTRATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SOURCE_A = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SOURCE_B = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID PAGE_A = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID PAGE_B = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String SOURCE_SET = "ab".repeat(32);

    /** Values a parsed field can carry. None of them may ever appear in a response body. */
    private static final String DISPLAYED_TEXT = "$8,412.55";
    private static final String RAW_VALUE = "8412.55";
    private static final String NORMALIZED_TEXT = "JANE Q BORROWER";
    private static final String NORMALIZED_DATE = "2026-01-31";

    private ParsedDataResolver parsedInputs;
    private InstanceReleaseResolver releases;
    private LabIdempotencyService idempotency;
    private RegistrationLoanFactsService loanFacts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        parsedInputs = mock(ParsedDataResolver.class);
        releases = mock(InstanceReleaseResolver.class);
        idempotency = mock(LabIdempotencyService.class);
        loanFacts = mock(RegistrationLoanFactsService.class);
        doAnswer(invocation -> invocation
                .<LabIdempotencyService.IdempotentCommand<Object>>getArgument(0)
                .action().get()).when(idempotency).execute(any());
        when(releases.live(new InstanceKey(BRAIN, INSTANCE))).thenReturn(release());
        mvc = MockMvcBuilders
                .standaloneSetup(new ParsedInputController(
                        parsedInputs, releases, idempotency, loanFacts))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new ParsedInputExceptionHandler())
                .build();
    }

    @Test
    void routesAreAdminGatedExplicitlyBrainScopedAndIndependentlyFeatureGated() throws Exception {
        for (String route : List.of(
                "/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/" + REGISTRATION,
                "/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/" + REGISTRATION
                        + "/envelope")) {
            mvc.perform(get(route).param("brain", BRAIN.toString()))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(parsedInputs);

        when(parsedInputs.registered(BRAIN, INSTANCE, REGISTRATION)).thenReturn(registered(null));
        mvc.perform(get("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/" + REGISTRATION)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brainId").value(BRAIN.toString()))
                .andExpect(jsonPath("$.registrationMode").value("UPLOAD_ONE"))
                .andExpect(jsonPath("$.pinned").value(false));

        // The surface is gated on instances alone, so it can ship with the Lab prototype off.
        ConditionalOnProperty gate =
                ParsedInputController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());
    }

    @Test
    void bothPostsRequireAnIdempotencyKeyBeforeReachingTheResolver() throws Exception {
        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(selectBody(SOURCE_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mvc.perform(multipart("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/upload")
                        .file(upload())
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        verifyNoInteractions(parsedInputs);
    }

    @Test
    void aKeyBoundToADifferentRequestIsRefusedBeforeTheEngineIsCalled() throws Exception {
        doThrow(new LabIdempotencyService.IdempotencyException(
                LabIdempotencyService.IdempotencyException.Code.IDEMPOTENCY_KEY_REUSED))
                .when(idempotency).requireUnusedOrMatching(any(), anyString(), anyString(), anyString());

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(selectBody(SOURCE_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(multipart("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/upload")
                        .file(upload())
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // The whole point of binding the hash first: neither body reached the Document Engine.
        verify(parsedInputs, never()).verifyExisting(any(), any());
        verify(parsedInputs, never()).acceptUpload(any());
        verify(idempotency, never()).execute(any());
    }

    @Test
    void theSameSelectionInAnyOrderIsTheSameRequestAndADifferentOneIsNot() throws Exception {
        when(parsedInputs.verifyExisting(any(), any())).thenReturn(selection());
        when(parsedInputs.pin(any())).thenReturn(verified(List.of(SOURCE_A, SOURCE_B)));

        selectWith("[\"" + SOURCE_A + "\",\"" + SOURCE_B + "\"]");
        selectWith("[\"" + SOURCE_B + "\",\"" + SOURCE_A + "\"]");
        selectWith("[\"" + SOURCE_A + "\"]");

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(idempotency, org.mockito.Mockito.times(3)).requireUnusedOrMatching(
                eq(BRAIN), anyString(), eq(KEY), hashes.capture());
        List<String> captured = hashes.getAllValues();
        assertEquals(captured.get(0), captured.get(1),
                "the parse's own ordinal decides what is analyzed, so listing order is not part "
                        + "of the request");
        assertNotEquals(captured.get(0), captured.get(2),
                "a narrower selection is a different request and must not replay the wider one");
    }

    @Test
    void aDuplicateOrAbsentSelectionIsRefusedRatherThanCanonicalized() throws Exception {
        for (String sources : List.of(
                "[\"" + SOURCE_A + "\",\"" + SOURCE_A + "\"]", "[]", "[null]")) {
            mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs")
                            .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                            .header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"packageId\":\"" + PACKAGE + "\",\"revision\":1,"
                                    + "\"selectedSourceIds\":" + sources + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PARSED_INPUT_REQUEST_INVALID"));
        }
        verifyNoInteractions(parsedInputs);
    }

    @Test
    void nothingIsPinnedUntilTheParseHasBeenVerified() throws Exception {
        ParsedDataResolver.VerifiedSelection selection = selection();
        when(parsedInputs.verifyExisting(any(), any())).thenReturn(selection);
        when(parsedInputs.pin(selection)).thenReturn(verified(List.of(SOURCE_A)));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(selectBody(SOURCE_A)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationId").value(REGISTRATION.toString()))
                .andExpect(jsonPath("$.compatibility.compatible").value(true));

        InOrder order = inOrder(idempotency, parsedInputs);
        // Bind the key, then read the engine, then — and only then — open the write transaction.
        order.verify(idempotency).requireUnusedOrMatching(eq(BRAIN), anyString(), eq(KEY), anyString());
        order.verify(parsedInputs).verifyExisting(any(), any());
        order.verify(idempotency).execute(any());
        order.verify(parsedInputs).pin(selection);
    }

    @Test
    void aVerificationFailureLeavesNoReceiptAndNoRegistration() throws Exception {
        when(parsedInputs.verifyExisting(any(), any())).thenThrow(
                new ParsedDataResolver.ParsedDataException(
                        ParsedDataResolver.ParsedDataException.Code.PARSE_REVISION_NOT_FOUND));

        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(selectBody(SOURCE_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARSE_REVISION_NOT_FOUND"));

        verify(idempotency, never()).execute(any());
        verify(parsedInputs, never()).pin(any());
    }

    @Test
    void noExtractedValueCrossesTheAdminBoundary() throws Exception {
        when(parsedInputs.registered(BRAIN, INSTANCE, REGISTRATION)).thenReturn(registered(3));
        when(parsedInputs.review(eq(BRAIN), eq(INSTANCE), eq(REGISTRATION), any()))
                .thenReturn(verified(List.of(SOURCE_A, SOURCE_B)));

        MvcResult result = mvc.perform(
                        get("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/"
                                + REGISTRATION + "/envelope")
                                .param("brain", BRAIN.toString())
                                .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.revision").value(3))
                // Structure IS returned — otherwise the absence of values would prove nothing.
                .andExpect(jsonPath("$.sources[0].contentSha256").value("a1".repeat(32)))
                .andExpect(jsonPath("$.documents[0].fields[0].name").value("gross_monthly_income"))
                .andExpect(jsonPath("$.documents[0].fields[0].status").value("FOUND"))
                .andExpect(jsonPath("$.documents[0].fields[0].evidencePageIds[0]")
                        .value(PAGE_A.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        for (String value : List.of(DISPLAYED_TEXT, RAW_VALUE, NORMALIZED_TEXT, NORMALIZED_DATE)) {
            assertFalse(body.contains(value),
                    "an admin metadata route must never quote a parsed value: " + value);
        }
        for (String member : List.of("rawValue", "displayedText", "normalized")) {
            assertFalse(body.contains(member),
                    "there must be no member a parsed value could travel in: " + member);
        }
    }

    @Test
    void anUploadNothingHasPinnedYetIsReviewedAsTheWholeParse() throws Exception {
        when(parsedInputs.registered(BRAIN, INSTANCE, REGISTRATION)).thenReturn(registered(null));
        when(parsedInputs.review(eq(BRAIN), eq(INSTANCE), eq(REGISTRATION), any()))
                .thenReturn(verified(List.of(SOURCE_A, SOURCE_B)));

        mvc.perform(get("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/" + REGISTRATION
                                + "/envelope")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(false))
                .andExpect(jsonPath("$.sources.length()").value(2));

        // Looking is not pinning: a read must never create a registration.
        verify(parsedInputs, never()).pin(any());
        verify(idempotency, never()).execute(any());
    }

    @Test
    void oneUploadIsForwardedWithTheCallersKeyAndNoFilename() throws Exception {
        DocumentEngineClient.UploadRegistration accepted = accepted();
        when(parsedInputs.acceptUpload(any())).thenReturn(accepted);
        when(parsedInputs.claimUpload(any(), eq(accepted))).thenReturn(
                new ParsedDataResolver.RegisteredUpload(REGISTRATION, PACKAGE, JOB, SOURCE_A, 1,
                        List.of("c0ffee"), true));

        MvcResult result = mvc.perform(
                        multipart("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/upload")
                                .file(upload())
                                .param("brain", BRAIN.toString())
                                .header("X-Admin-Api-Key", ADMIN_KEY)
                                .header("Idempotency-Key", KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationId").value(REGISTRATION.toString()))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.duplicateShaPrefixes[0]").value("c0ffee"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("borrower-paystub"),
                "the browser's filename must have nothing to travel in");

        ArgumentCaptor<ParsedDataResolver.UploadRequest> request =
                ArgumentCaptor.forClass(ParsedDataResolver.UploadRequest.class);
        verify(parsedInputs).acceptUpload(request.capture());
        assertEquals(BRAIN, request.getValue().brainId());
        assertEquals(INSTANCE, request.getValue().instanceSlug());
        assertEquals(KEY, request.getValue().idempotencyKey(),
                "the caller's key must reach the engine unchanged, or a retry becomes a second "
                        + "package");
        assertTrue(request.getValue().sizeBytes() > 0);
    }

    @Test
    void aRefusalNamesItsCodeAndNothingElse() throws Exception {
        when(parsedInputs.registered(BRAIN, INSTANCE, REGISTRATION)).thenThrow(
                new ParsedDataResolver.ParsedDataException(
                        ParsedDataResolver.ParsedDataException.Code.PARSE_REGISTRATION_NOT_FOUND));
        mvc.perform(get("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/" + REGISTRATION)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARSE_REGISTRATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").doesNotExist());

        // The engine's exception quotes a request URI and a response body; only its code escapes.
        when(parsedInputs.acceptUpload(any())).thenThrow(new DocumentEngineFailure(
                DocumentEngineFailure.Code.ENGINE_STATUS_UNEXPECTED, 503));
        MvcResult result = mvc.perform(
                        multipart("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/upload")
                                .file(upload())
                                .param("brain", BRAIN.toString())
                                .header("X-Admin-Api-Key", ADMIN_KEY)
                                .header("Idempotency-Key", KEY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ENGINE_STATUS_UNEXPECTED"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("503"));
    }

    @Test
    void anInstanceWithNoLivePinnableReleaseNeverReachesTheEngine() throws Exception {
        when(releases.live(new InstanceKey(BRAIN, INSTANCE))).thenReturn(
                new ResolvedInstanceRelease(mock(LabInstance.class), mock(LabInstanceRelease.class),
                        new DecodedInstanceManifest.V1Income(null), true));

        mvc.perform(multipart("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs/upload")
                        .file(upload())
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTANCE_RELEASE_NOT_PINNABLE"));

        verify(parsedInputs, never()).acceptUpload(any());
    }

    // ================================================================ fixtures

    private void selectWith(String sources) throws Exception {
        mvc.perform(post("/api/ai/admin/instances/" + INSTANCE + "/parsed-inputs")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageId\":\"" + PACKAGE + "\",\"revision\":1,"
                                + "\"selectedSourceIds\":" + sources + "}"))
                .andExpect(status().isCreated());
    }

    private static String selectBody(UUID source) {
        return "{\"packageId\":\"" + PACKAGE + "\",\"revision\":1,"
                + "\"selectedSourceIds\":[\"" + source + "\"]}";
    }

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "borrower-paystub.pdf", "application/pdf",
                "%PDF-1.7 original bytes".getBytes(StandardCharsets.UTF_8));
    }

    private static DocumentEngineClient.UploadRegistration accepted() {
        return new DocumentEngineClient.UploadRegistration(PACKAGE, JOB,
                List.of(new DocumentEngineClient.RegisteredSource(
                        SOURCE_A, "a1".repeat(32), 48211L, 2)),
                List.of("c0ffee"));
    }

    private static ParsedDataResolver.RegisteredInput registered(Integer revision) {
        return new ParsedDataResolver.RegisteredInput(REGISTRATION, BRAIN, INSTANCE, PACKAGE, JOB,
                revision == null ? SOURCE_A : null,
                revision == null
                        ? LabDocumentRegistration.RegistrationMode.UPLOAD_ONE
                        : LabDocumentRegistration.RegistrationMode.EXISTING_PARSE,
                revision,
                revision == null ? null : SOURCE_SET,
                revision == null ? List.of() : List.of(SOURCE_A, SOURCE_B),
                OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private static ParsedDataResolver.VerifiedParsedInput verified(List<UUID> sources) {
        EngineResultEnvelope envelope = envelope();
        EngineArtifactDescriptor artifact =
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8));
        return new ParsedDataResolver.VerifiedParsedInput(REGISTRATION, PACKAGE, 3, JOB, 1,
                "1.0.0", "DOCENGINE-C14N-1", artifact.sha256(), artifact.byteCount(), SOURCE_SET,
                sources, envelope, envelope,
                new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 2));
    }

    private static ParsedDataResolver.VerifiedSelection selection() {
        EngineResultEnvelope envelope = envelope();
        EngineArtifactDescriptor artifact =
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8));
        return new ParsedDataResolver.VerifiedSelection(BRAIN, INSTANCE, PACKAGE, 3,
                new DocumentEngineClient.RevisionDescriptor(3, JOB, 1, 1, "1.0.0", SOURCE_SET,
                        "d4".repeat(32), artifact.sha256(), artifact.byteCount(), "REUSABLE",
                        Instant.parse("2026-01-01T00:00:00Z")),
                new DocumentEngineClient.VerifiedEnvelope(artifact, envelope, 3),
                new ParsedInputSelection.SelectionResult(envelope,
                        List.of(new ParsedInputSelection.SelectedSource(
                                SOURCE_A, "a1".repeat(32), 0)),
                        SOURCE_SET),
                new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 1));
    }

    private static ResolvedInstanceRelease release() {
        InstanceReleaseManifest.ParsedDataContract contract =
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB", "W2"), Set.of(), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE);
        InstanceReleaseManifest manifest = new InstanceReleaseManifest(2, contract,
                new InstanceReleaseManifest.ModelContract("openai", "gpt-decimal",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(
                                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 1))),
                new InstanceReleaseManifest.BehaviorContract(
                        "system", "task", "query", new BigDecimal("0.250")),
                List.of(new InstanceReleaseManifest.ToolContract(
                        "income.calculate", "1", "a".repeat(64), "b".repeat(64))),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract(
                        "golden", 1, new BigDecimal("0.950")));
        return new ResolvedInstanceRelease(mock(LabInstance.class), mock(LabInstanceRelease.class),
                new DecodedInstanceManifest.V2(manifest), true);
    }

    private static EngineResultEnvelope envelope() {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                PACKAGE,
                new EngineResultEnvelope.Generation(JOB, 1, 3, SOURCE_SET,
                        "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new EngineResultEnvelope.SourceFile(
                                SOURCE_A, 0, "a1".repeat(32), 48211L, "application/pdf"),
                        new EngineResultEnvelope.SourceFile(
                                SOURCE_B, 1, "a2".repeat(32), 51200L, "application/pdf")),
                List.of(page(PAGE_A, SOURCE_A, 0), page(PAGE_B, SOURCE_B, 1)),
                List.of(new EngineResultEnvelope.LogicalDocument(
                        new UUID(0x9999, 0), "PAYSTUB", 0, List.of(PAGE_A), List.of(field()))),
                List.of(PAGE_B),
                new EngineResultEnvelope.Provenance(
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        List.of(new EngineResultEnvelope.StageAttempt(
                                "EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    /** One FOUND field carrying every value-bearing member the engine can populate. */
    private static EngineResultEnvelope.FieldOccurrence field() {
        return new EngineResultEnvelope.FieldOccurrence(
                "gross_monthly_income",
                null,
                EngineResultEnvelope.FieldStatus.FOUND,
                "MONEY",
                DISPLAYED_TEXT,
                RAW_VALUE,
                new EngineResultEnvelope.NormalizedValue(NORMALIZED_TEXT,
                        new BigDecimal(RAW_VALUE), LocalDate.parse(NORMALIZED_DATE), null),
                new EngineResultEnvelope.SchemaRef(
                        UUID.fromString("99999999-9999-4999-8999-999999999999"), "3.1.0"),
                "ANCHORED_SPAN",
                "1.4.0",
                new BigDecimal("0.97"),
                new EngineResultEnvelope.ConfidenceComponents(
                        new BigDecimal("0.98"), new BigDecimal("0.96"), new BigDecimal("0.99")),
                "VALID",
                false,
                List.of(new EngineResultEnvelope.EvidenceSpan(PAGE_A, null, null, "VALUE", 0,
                        new EngineResultEnvelope.Box(new BigDecimal("10"), new BigDecimal("20"),
                                new BigDecimal("30"), new BigDecimal("40")))));
    }

    private static EngineResultEnvelope.EnginePage page(UUID id, UUID sourceId, int packageIndex) {
        return new EngineResultEnvelope.EnginePage(id, sourceId, 0, packageIndex,
                new BigDecimal("612"), new BigDecimal("792"), 0, null, "NATIVE", false, false,
                null);
    }

    private static RagProperties properties() {
        return new RagProperties(new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin(ADMIN_KEY), new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }
}
