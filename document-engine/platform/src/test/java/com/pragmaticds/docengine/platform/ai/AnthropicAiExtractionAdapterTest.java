package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
class AnthropicAiExtractionAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_MODEL = "synthetic-model-v1";
    private static final String DOCUMENT_SENTINEL = "SYNTHETIC-DOCUMENT-SENTINEL-DO-NOT-LOG";
    private static final String RESPONSE_SENTINEL = "SYNTHETIC-RESPONSE-SENTINEL-DO-NOT-LOG";
    private static final String SCHEMA = new BankStatementExtractionSchema().schemaJson();
    private static final AiExtractionRequest REQUEST =
            new AiExtractionRequest(
                    AiDocumentType.BANK_STATEMENT,
                    DOCUMENT_SENTINEL + "\nExample Bank ending balance 100.00",
                    "FAKE TABLE: date | description | amount",
                    SCHEMA);
    private static final String SUCCESS_RESPONSE =
            """
            {
              "id": "msg_synthetic_01",
              "type": "message",
              "role": "assistant",
              "model": "synthetic-response-model",
              "content": [
                {
                  "type": "text",
                  "text": "{\\\"summary\\\":{\\\"bankName\\\":\\\"Example Bank\\\"}}"
                }
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {
                "input_tokens": 120,
                "output_tokens": 42,
                "cache_read_input_tokens": 80,
                "cache_creation_input_tokens": 40
              }
            }
            """;

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
    void sends_cached_schema_prefix_and_structured_output_then_parses_usage() throws Exception {
        server.enqueue(json(successResponse(fixture("good-clean-multipage.json"))));

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(REQUEST);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/messages");
        assertThat(recorded.getHeader("content-type")).startsWith("application/json");
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("synthetic-test-key");
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(body.path("model").asText()).isEqualTo(TEST_MODEL);
        assertThat(body.path("max_tokens").asInt()).isPositive();
        assertThat(body.at("/system/0/cache_control/type").asText()).isEqualTo("ephemeral");
        assertThat(body.at("/system/0/text").asText())
                .contains("additionalProperties")
                .contains("A missing value is acceptable. A wrong value is not.")
                .contains("exactly as printed")
                .containsIgnoringCase("untrusted document data")
                .contains("\"value\": null")
                .doesNotContain(DOCUMENT_SENTINEL);
        assertThat(body.at("/system/0/text").asText()).containsIgnoringCase("read all pages");
        String userText = body.at("/messages/0/content/0/text").asText();
        assertThat(userText).startsWith("UNTRUSTED_DOCUMENT_DATA_JSON:\n");
        JsonNode documentData =
                JSON.readTree(userText.substring("UNTRUSTED_DOCUMENT_DATA_JSON:\n".length()));
        assertThat(documentData.path("documentText").asText())
                .isEqualTo(REQUEST.documentText());
        assertThat(documentData.path("tableStructureText").asText())
                .isEqualTo(REQUEST.tableStructureText());
        assertThat(body.at("/messages/0/content/0/cache_control").isMissingNode()).isTrue();
        assertThat(body.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/output_config/format/schema")).isEqualTo(JSON.readTree(SCHEMA));
        assertThat(body.at("/output_config/format/schema/additionalProperties").asBoolean())
                .isFalse();

        assertThat(result.provider()).isEqualTo("anthropic");
        assertThat(result.model()).isEqualTo("synthetic-response-model");
        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(result.reason()).isNull();
        assertThat(result.tokenCounts()).isEqualTo(new AiTokenCounts(120, 42, 80, 40));
        assertThat(
                        JSON.readTree(result.structuredJson())
                                .path("summary")
                                .path("bankName")
                                .path("value")
                                .asText())
                .isEqualTo("Freeport Community Bank");
        assertThat(result.extraction()).isInstanceOf(BankStatementExtraction.class);
    }

    @Test
    void schema_valid_but_unparseable_output_returns_error_without_logging_content(
            CapturedOutput output) throws Exception {
        server.enqueue(json(successResponse(fixture("malformed-unparseable-money.json"))));

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("validation_error");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.extraction()).isNull();
        assertThat(output.getAll())
                .doesNotContain("one thousand")
                .doesNotContain("Z Bank")
                .doesNotContain("synthetic-test-key");
    }

    @Test
    void semantically_equal_schema_hint_still_sends_the_exact_bundled_schema() throws Exception {
        server.enqueue(json(successResponse(fixture("good-missing-summary-field.json"))));
        String minifiedSchema = JSON.writeValueAsString(JSON.readTree(SCHEMA));
        AiExtractionRequest request =
                new AiExtractionRequest(
                        AiDocumentType.BANK_STATEMENT,
                        DOCUMENT_SENTINEL,
                        "FAKE TABLE",
                        minifiedSchema);

        AiExtractionResult result = adapter(Duration.ofSeconds(2)).extract(request);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(body.at("/system/0/text").asText()).endsWith(SCHEMA);
        assertThat(body.at("/output_config/format/schema")).isEqualTo(JSON.readTree(SCHEMA));
    }

    @Test
    void invalid_request_returns_error_without_an_http_call() {
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
    void null_request_returns_error_without_an_http_call() {
        AiExtractionResult result = adapter(Duration.ofSeconds(1)).extract(null);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("invalid_request");
        assertThat(server.getRequestCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("badResponses")
    void provider_and_malformed_responses_return_error_without_logging_content(
            MockResponse response, String expectedReason, CapturedOutput output) {
        server.enqueue(response);

        AiExtractionResult result = adapter(Duration.ofSeconds(1)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.structuredJson()).isNull();
        assertThat(result.reason()).isEqualTo(expectedReason);
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain(RESPONSE_SENTINEL)
                .doesNotContain("synthetic-test-key");
    }

    @Test
    void stalled_response_returns_error_within_the_configured_timeout(CapturedOutput output) {
        server.enqueue(
                json(SUCCESS_RESPONSE).setHeadersDelay(1, TimeUnit.SECONDS));
        long started = System.nanoTime();

        AiExtractionResult result = adapter(Duration.ofMillis(100)).extract(REQUEST);

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("provider_transient");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain("synthetic-test-key");
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

    private AnthropicAiExtractionAdapter adapter(Duration timeout) {
        return new AnthropicAiExtractionAdapter(
                TEST_MODEL,
                "synthetic-test-key",
                server.url("/").toString(),
                timeout);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String successResponse(String structuredJson) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(SUCCESS_RESPONSE);
        ((ObjectNode) root.path("content").get(0)).put("text", structuredJson);
        return JSON.writeValueAsString(root);
    }

    private static String fixture(String name) throws IOException {
        String path = "/ai/golden/" + name;
        try (InputStream input = AnthropicAiExtractionAdapterTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing synthetic fixture: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
