package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class VertexGeminiAiExtractionAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_MODEL = "synthetic-gemini-model";
    private static final String DOCUMENT_SENTINEL = "SYNTHETIC-GEMINI-DOCUMENT-DO-NOT-LOG";
    private static final String RESPONSE_SENTINEL = "SYNTHETIC-GEMINI-RESPONSE-DO-NOT-LOG";
    private static final String SCHEMA = new BankStatementExtractionSchema().schemaJson();
    private static final AiExtractionRequest REQUEST =
            new AiExtractionRequest(
                    AiDocumentType.BANK_STATEMENT,
                    DOCUMENT_SENTINEL + "\nExample Bank ending balance 100.00",
                    "FAKE TABLE: date | description | amount",
                    SCHEMA);

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sends_vertex_structured_output_request_and_parses_usage_through_shared_parser()
            throws Exception {
        server.enqueue(json(successResponse(fixture("good-clean-multipage.json"))));

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(REQUEST);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath())
                .isEqualTo(
                        "/v1/projects/synthetic-project/locations/us-central1/"
                                + "publishers/google/models/"
                                + TEST_MODEL
                                + ":generateContent");
        assertThat(recorded.getHeader("Authorization"))
                .isEqualTo("Bearer synthetic-access-token");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");

        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        assertThat(body.at("/generationConfig/responseMimeType").asText())
                .isEqualTo("application/json");
        assertThat(body.at("/generationConfig/maxOutputTokens").asInt()).isPositive();
        JsonNode responseSchema = body.at("/generationConfig/responseSchema");
        assertThat(responseSchema.path("type").asText()).isEqualTo("OBJECT");
        assertThat(responseSchema.at("/properties/transactions/type").asText())
                .isEqualTo("ARRAY");
        assertThat(
                        responseSchema
                                .at(
                                        "/properties/summary/properties/beginningBalance/"
                                                + "properties/value/nullable")
                                .asBoolean())
                .isTrue();
        assertThat(responseSchema.at("/required").toString())
                .contains("summary", "transactions", "checks");

        String systemText = body.at("/systemInstruction/parts/0/text").asText();
        assertThat(systemText)
                .contains("A missing value is acceptable. A wrong value is not.")
                .containsIgnoringCase("untrusted document data")
                .contains("additionalProperties")
                .doesNotContain(DOCUMENT_SENTINEL);
        String userText = body.at("/contents/0/parts/0/text").asText();
        assertThat(userText).startsWith("UNTRUSTED_DOCUMENT_DATA_JSON:\n");
        JsonNode documentData =
                JSON.readTree(
                        userText.substring("UNTRUSTED_DOCUMENT_DATA_JSON:\n".length()));
        assertThat(documentData.path("documentText").asText())
                .isEqualTo(REQUEST.documentText());
        assertThat(documentData.path("tableStructureText").asText())
                .isEqualTo(REQUEST.tableStructureText());

        assertThat(result.provider()).isEqualTo("vertex-gemini");
        assertThat(result.model()).isEqualTo("synthetic-gemini-response-model");
        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(result.reason()).isNull();
        assertThat(result.tokenCounts()).isEqualTo(new AiTokenCounts(120, 42, 18, 0));
        assertThat(result.extraction()).isInstanceOf(BankStatementExtraction.class);
        BankStatementExtraction extraction = (BankStatementExtraction) result.extraction();
        assertThat(extraction.summary().bankName().value())
                .isEqualTo("Freeport Community Bank");
    }

    @Test
    void attached_page_images_ride_as_named_inline_parts_and_default_requests_carry_none()
            throws Exception {
        server.enqueue(json(successResponse(fixture("good-clean-multipage.json"))));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7};
        AiExtractionRequest withImage =
                new AiExtractionRequest(
                        REQUEST.documentType(),
                        REQUEST.documentText(),
                        REQUEST.tableStructureText(),
                        REQUEST.outputSchemaJson(),
                        java.util.List.of(new AiExtractionRequest.PageImage(4, png)));

        adapter(Duration.ofSeconds(2)).extract(withImage);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        JsonNode parts = JSON.readTree(recorded.getBody().readUtf8()).at("/contents/0/parts");
        assertThat(parts).hasSize(3);
        // Each image is preceded by a text part naming its page, so a value read off pixels
        // still reports a checkable page.
        assertThat(parts.get(1).path("text").asText()).isEqualTo("RENDERED PAGE 5:");
        assertThat(parts.get(2).at("/inlineData/mimeType").asText()).isEqualTo("image/png");
        assertThat(parts.get(2).at("/inlineData/data").asText())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(png));

        // And the compat shape sends exactly one text part — no image key ever appears.
        server.enqueue(json(successResponse(fixture("good-clean-multipage.json"))));
        adapter(Duration.ofSeconds(2)).extract(REQUEST);
        RecordedRequest textOnly = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(JSON.readTree(textOnly.getBody().readUtf8()).at("/contents/0/parts")).hasSize(1);
    }

    @Test
    void invalid_schema_hint_returns_error_without_an_http_call() {
        AiExtractionRequest invalid =
                new AiExtractionRequest(
                        AiDocumentType.BANK_STATEMENT,
                        DOCUMENT_SENTINEL,
                        "FAKE TABLE",
                        "not-json");

        AiExtractionResult result = adapter(Duration.ofSeconds(1)).extract(invalid);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("invalid_request");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void parser_rejection_returns_content_free_validation_error(CapturedOutput output)
            throws Exception {
        server.enqueue(json(successResponse(fixture("malformed-unparseable-money.json"))));

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("validation_error");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.extraction()).isNull();
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain("one thousand")
                .doesNotContain("synthetic-access-token");
    }

    @ParameterizedTest
    @MethodSource("badResponses")
    void provider_and_malformed_responses_never_throw_or_log_content(
            MockResponse response, String expectedReason, CapturedOutput output) {
        server.enqueue(response);

        AiExtractionResult result = adapter(Duration.ofSeconds(1)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.structuredJson()).isNull();
        assertThat(result.reason()).isEqualTo(expectedReason);
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain(RESPONSE_SENTINEL)
                .doesNotContain("synthetic-access-token");
    }

    @Test
    void stalled_response_returns_transient_error_within_the_configured_timeout(
            CapturedOutput output) {
        server.enqueue(
                json(successResponseUnchecked("{}"))
                        .setHeadersDelay(2, TimeUnit.SECONDS));
        long started = System.nanoTime();

        AiExtractionResult result = adapter(Duration.ofMillis(100)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("provider_transient");
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(1));
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain("synthetic-access-token");
    }

    @Test
    void truncated_response_is_reported_as_response_truncated(CapturedOutput output) {
        // finishReason=MAX_TOKENS: the model ran out of output budget mid-JSON. The response
        // parses fine but is unusable — reported distinctly so the ledger points at raising the
        // max-output-tokens cap, not at a provider bug.
        server.enqueue(json(envelope("MAX_TOKENS", "{\"summary\":{")));

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("response_truncated");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.extraction()).isNull();
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain("synthetic-access-token");
    }

    @Test
    void blank_model_text_is_reported_as_empty_response() {
        server.enqueue(json(envelope("STOP", "")));

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("empty_response");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.extraction()).isNull();
    }

    private static Stream<Arguments> badResponses() {
        return Stream.of(
                Arguments.of(
                        new MockResponse().setResponseCode(503).setBody(RESPONSE_SENTINEL),
                        "provider_transient"),
                Arguments.of(
                        new MockResponse().setResponseCode(429).setBody(RESPONSE_SENTINEL),
                        "provider_transient"),
                Arguments.of(
                        new MockResponse().setResponseCode(400).setBody(RESPONSE_SENTINEL),
                        "provider_permanent"),
                Arguments.of(json(RESPONSE_SENTINEL), "invalid_response"),
                Arguments.of(json("{\"candidates\":["), "invalid_response"));
    }

    private VertexGeminiAiExtractionAdapter adapter(Duration timeout) {
        GoogleCredentials credentials =
                GoogleCredentials.create(
                        new AccessToken(
                                "synthetic-access-token",
                                Date.from(Instant.now().plusSeconds(3600))));
        return new VertexGeminiAiExtractionAdapter(
                TEST_MODEL,
                "synthetic-project",
                "us-central1",
                credentials,
                8192,
                server.url("/").toString(),
                timeout);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    /** A well-formed Vertex envelope with a chosen finishReason and candidate text (may be blank). */
    private static String envelope(String finishReason, String text) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode candidate = root.putArray("candidates").addObject();
        candidate.put("finishReason", finishReason);
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        content.putArray("parts").addObject().put("text", text);
        root.put("modelVersion", "synthetic-gemini-response-model");
        root.put("responseId", "synthetic-response-id");
        ObjectNode usage = root.putObject("usageMetadata");
        usage.put("promptTokenCount", 120);
        usage.put("candidatesTokenCount", 8192);
        usage.put("totalTokenCount", 8312);
        try {
            return JSON.writeValueAsString(root);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String successResponse(String structuredJson) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode candidate = root.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        content.putArray("parts").addObject().put("text", structuredJson);
        root.put("modelVersion", "synthetic-gemini-response-model");
        root.put("responseId", "synthetic-response-id");
        ObjectNode usage = root.putObject("usageMetadata");
        usage.put("promptTokenCount", 120);
        usage.put("candidatesTokenCount", 42);
        usage.put("cachedContentTokenCount", 18);
        usage.put("totalTokenCount", 162);
        return JSON.writeValueAsString(root);
    }

    private static String successResponseUnchecked(String structuredJson) {
        try {
            return successResponse(structuredJson);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String fixture(String name) throws IOException {
        String path = "/ai/golden/" + name;
        try (InputStream input =
                VertexGeminiAiExtractionAdapterTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing synthetic fixture: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
