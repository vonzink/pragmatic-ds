package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
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

/**
 * Same discipline as {@link VertexGeminiAiExtractionAdapterTest}: the wire shape, the parse, and
 * the failure modes — never throwing, never logging content. The sentinel strings prove page text
 * and quotes stay out of every log line whatever the provider does.
 */
@ExtendWith(OutputCaptureExtension.class)
class VertexGeminiBoundaryExtractionAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_MODEL = "synthetic-gemini-model";
    private static final String DOCUMENT_SENTINEL = "SYNTHETIC-BOUNDARY-PAGE-DO-NOT-LOG";
    private static final String RESPONSE_SENTINEL = "SYNTHETIC-BOUNDARY-RESPONSE-DO-NOT-LOG";
    private static final BoundaryExtractionRequest REQUEST =
            new BoundaryExtractionRequest(
                    List.of(
                            new BoundaryExtractionRequest.CandidatePage(
                                    3,
                                    "PAYSTUB",
                                    new BigDecimal("0.91"),
                                    DOCUMENT_SENTINEL + " ACME WIDGETS Pay Period",
                                    "Page 1 of 1",
                                    null),
                            new BoundaryExtractionRequest.CandidatePage(
                                    4,
                                    "UNKNOWN",
                                    BigDecimal.ZERO,
                                    "Terms and Conditions",
                                    "",
                                    null)),
                    List.of(
                            new BoundaryExtractionRequest.TypeDescription(
                                    "PAYSTUB", "A wage statement for one pay period.")),
                    null);

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
    void sends_the_untrusted_payload_with_the_pinned_response_schema_and_parses_proposals()
            throws Exception {
        server.enqueue(
                json(
                        successResponse(
                                """
                                {"boundaries":[{"packagePageIndex":4,"documentTypeCode":"UNKNOWN",
                                  "confidence":0.88,"quotedHeaderText":"Terms and Conditions",
                                  "partitionValue":null}]}
                                """)));

        BoundaryExtractionResult result = adapter(Duration.ofSeconds(2)).proposeBoundaries(REQUEST);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        assertThat(body.at("/generationConfig/responseMimeType").asText())
                .isEqualTo("application/json");
        JsonNode schema = body.at("/generationConfig/responseSchema");
        assertThat(schema.at("/properties/boundaries/type").asText()).isEqualTo("ARRAY");
        assertThat(schema.at("/properties/boundaries/items/required").toString())
                .contains("packagePageIndex", "quotedHeaderText", "confidence");

        String systemText = body.at("/systemInstruction/parts/0/text").asText();
        assertThat(systemText)
                .containsIgnoringCase("verbatim")
                .containsIgnoringCase("untrusted")
                .doesNotContain(DOCUMENT_SENTINEL);
        String userText = body.at("/contents/0/parts/0/text").asText();
        assertThat(userText).startsWith("UNTRUSTED_DOCUMENT_DATA_JSON:\n");
        JsonNode payload =
                JSON.readTree(userText.substring("UNTRUSTED_DOCUMENT_DATA_JSON:\n".length()));
        assertThat(payload.at("/pages/0/packagePageIndex").asInt()).isEqualTo(3);
        assertThat(payload.at("/pages/0/headText").asText()).contains(DOCUMENT_SENTINEL);
        assertThat(payload.at("/taxonomy/0/code").asText()).isEqualTo("PAYSTUB");

        assertThat(result.status()).isEqualTo(BoundaryExtractionStatus.OK);
        assertThat(result.tokens()).isEqualTo(new AiTokenCounts(120, 42, 18, 0));
        assertThat(result.boundaries()).hasSize(1);
        BoundaryExtractionResult.ProposedBoundary boundary = result.boundaries().get(0);
        assertThat(boundary.packagePageIndex()).isEqualTo(4);
        assertThat(boundary.documentTypeCode()).isEqualTo("UNKNOWN");
        assertThat(boundary.confidence()).isEqualByComparingTo("0.88");
        assertThat(boundary.quotedHeaderText()).isEqualTo("Terms and Conditions");
        assertThat(boundary.partitionValue()).isNull();
    }

    @Test
    void an_empty_boundaries_array_is_a_valid_answer_meaning_no_cut() throws Exception {
        server.enqueue(json(successResponse("{\"boundaries\":[]}")));

        BoundaryExtractionResult result = adapter(Duration.ofSeconds(2)).proposeBoundaries(REQUEST);

        assertThat(result.status()).isEqualTo(BoundaryExtractionStatus.OK);
        assertThat(result.boundaries()).isEmpty();
    }

    @Test
    void an_empty_request_returns_error_without_an_http_call() {
        BoundaryExtractionResult result =
                adapter(Duration.ofSeconds(1))
                        .proposeBoundaries(
                                new BoundaryExtractionRequest(List.of(), List.of(), null));

        assertThat(result.status()).isEqualTo(BoundaryExtractionStatus.ERROR);
        assertThat(server.getRequestCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("badResponses")
    void provider_and_malformed_responses_never_throw_or_log_content(
            MockResponse response, CapturedOutput output) {
        server.enqueue(response);

        BoundaryExtractionResult result = adapter(Duration.ofSeconds(1)).proposeBoundaries(REQUEST);

        assertThat(result.status()).isEqualTo(BoundaryExtractionStatus.ERROR);
        assertThat(result.boundaries()).isEmpty();
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain(RESPONSE_SENTINEL)
                .doesNotContain("synthetic-access-token");
    }

    private static Stream<Arguments> badResponses() throws IOException {
        return Stream.of(
                Arguments.of(new MockResponse().setResponseCode(503).setBody(RESPONSE_SENTINEL)),
                Arguments.of(new MockResponse().setResponseCode(400).setBody(RESPONSE_SENTINEL)),
                Arguments.of(json(RESPONSE_SENTINEL)),
                // Structurally invalid proposals: whole response refused, not half-believed.
                Arguments.of(
                        json(
                                successResponse(
                                        "{\"boundaries\":[{\"documentTypeCode\":\"W2\","
                                                + "\"confidence\":0.9,"
                                                + "\"quotedHeaderText\":\"x\"}]}"))),
                Arguments.of(
                        json(
                                successResponse(
                                        "{\"boundaries\":[{\"packagePageIndex\":1,"
                                                + "\"documentTypeCode\":\"W2\","
                                                + "\"confidence\":7.5,"
                                                + "\"quotedHeaderText\":\"x\"}]}"))));
    }

    private VertexGeminiBoundaryExtractionAdapter adapter(Duration timeout) {
        GoogleCredentials credentials =
                GoogleCredentials.create(
                        new AccessToken(
                                "synthetic-access-token",
                                Date.from(Instant.now().plusSeconds(3600))));
        return new VertexGeminiBoundaryExtractionAdapter(
                TEST_MODEL,
                "synthetic-project",
                "us-central1",
                credentials,
                server.url("/").toString(),
                timeout);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
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
}
