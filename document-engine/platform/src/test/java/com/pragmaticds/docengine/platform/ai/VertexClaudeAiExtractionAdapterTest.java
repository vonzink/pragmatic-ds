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
class VertexClaudeAiExtractionAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_MODEL = "synthetic-claude-model";
    private static final String DOCUMENT_SENTINEL = "SYNTHETIC-VERTEX-CLAUDE-DOCUMENT-DO-NOT-LOG";
    private static final String RESPONSE_SENTINEL = "SYNTHETIC-VERTEX-CLAUDE-RESPONSE-DO-NOT-LOG";
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
    void sends_vertex_messages_request_with_cache_and_schema_then_parses_shared_contract()
            throws Exception {
        server.enqueue(json(successResponse(fixture("good-clean-multipage.json"))));

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(REQUEST);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath())
                .isEqualTo(
                        "/v1/projects/synthetic-project/locations/global/"
                                + "publishers/anthropic/models/"
                                + TEST_MODEL
                                + ":rawPredict");
        assertThat(recorded.getHeader("Authorization"))
                .isEqualTo("Bearer synthetic-access-token");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");
        assertThat(recorded.getHeader("x-api-key")).isNull();

        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        assertThat(body.path("model").isMissingNode()).isTrue();
        assertThat(body.path("anthropic_version").asText()).isEqualTo("vertex-2023-10-16");
        assertThat(body.path("max_tokens").asInt()).isPositive();
        assertThat(body.at("/system/0/cache_control/type").asText()).isEqualTo("ephemeral");
        assertThat(body.at("/system/0/text").asText())
                .contains("A missing value is acceptable. A wrong value is not.")
                .containsIgnoringCase("untrusted document data")
                .contains("additionalProperties")
                .doesNotContain(DOCUMENT_SENTINEL);
        String userText = body.at("/messages/0/content").asText();
        assertThat(userText).startsWith("UNTRUSTED_DOCUMENT_DATA_JSON:\n");
        JsonNode documentData =
                JSON.readTree(
                        userText.substring("UNTRUSTED_DOCUMENT_DATA_JSON:\n".length()));
        assertThat(documentData.path("documentText").asText())
                .isEqualTo(REQUEST.documentText());
        assertThat(documentData.path("tableStructureText").asText())
                .isEqualTo(REQUEST.tableStructureText());
        assertThat(body.at("/output_config/format/type").asText())
                .isEqualTo("json_schema");
        assertThat(body.at("/output_config/format/schema"))
                .isEqualTo(JSON.readTree(SCHEMA));

        assertThat(result.provider()).isEqualTo("vertex-claude");
        assertThat(result.model()).isEqualTo("synthetic-claude-response-model");
        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(result.reason()).isNull();
        assertThat(result.tokenCounts()).isEqualTo(new AiTokenCounts(120, 42, 80, 40));
        assertThat(result.extraction()).isInstanceOf(BankStatementExtraction.class);
        BankStatementExtraction extraction = (BankStatementExtraction) result.extraction();
        assertThat(extraction.summary().bankName().value())
                .isEqualTo("Freeport Community Bank");
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
                Arguments.of(json("{\"content\":["), "invalid_response"));
    }

    private VertexClaudeAiExtractionAdapter adapter(Duration timeout) {
        GoogleCredentials credentials =
                GoogleCredentials.create(
                        new AccessToken(
                                "synthetic-access-token",
                                Date.from(Instant.now().plusSeconds(3600))));
        return new VertexClaudeAiExtractionAdapter(
                TEST_MODEL,
                "synthetic-project",
                "global",
                credentials,
                server.url("/").toString(),
                timeout);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String successResponse(String structuredJson) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", "synthetic-message-id");
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", "synthetic-claude-response-model");
        root.putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", structuredJson);
        root.put("stop_reason", "end_turn");
        root.putNull("stop_sequence");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 120);
        usage.put("output_tokens", 42);
        usage.put("cache_read_input_tokens", 80);
        usage.put("cache_creation_input_tokens", 40);
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
                VertexClaudeAiExtractionAdapterTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing synthetic fixture: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
