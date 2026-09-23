package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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
 * Same discipline as {@link VertexGeminiBoundaryExtractionAdapterTest}: the wire shape, the parse,
 * and the failure modes — never throwing, never logging content. The sentinel strings prove page
 * text and quotes stay out of every log line whatever the provider does.
 */
@ExtendWith(OutputCaptureExtension.class)
class VertexGeminiPageTypeClassificationAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_MODEL = "synthetic-gemini-model";
    private static final String DOCUMENT_SENTINEL = "SYNTHETIC-CLASSIFY-PAGE-DO-NOT-LOG";
    private static final String RESPONSE_SENTINEL = "SYNTHETIC-CLASSIFY-RESPONSE-DO-NOT-LOG";
    private static final UUID FIRST_PAGE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID SECOND_PAGE = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final PageTypeClassificationRequest REQUEST =
            new PageTypeClassificationRequest(
                    List.of(
                            new PageTypeClassificationRequest.CandidatePage(
                                    FIRST_PAGE,
                                    3,
                                    DOCUMENT_SENTINEL + " ACME WIDGETS Earnings Statement",
                                    "Page 1 of 1"),
                            new PageTypeClassificationRequest.CandidatePage(
                                    SECOND_PAGE, 4, "COLORADO DRIVER LICENSE", "")),
                    List.of(
                            new PageTypeClassificationRequest.TypeDescription(
                                    "PAYSTUB", "A wage statement for one pay period."),
                            new PageTypeClassificationRequest.TypeDescription(
                                    "DRIVERS_LICENSE", "A state-issued driving licence.")));

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
                                {"pages":[{"pageId":"00000000-0000-4000-8000-00000000000a",
                                  "documentTypeCode":"PAYSTUB","confidence":0.82,
                                  "quotedEvidenceText":"Earnings Statement"}]}
                                """)));

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(2)).classify(REQUEST);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        assertThat(body.at("/generationConfig/responseMimeType").asText())
                .isEqualTo("application/json");
        JsonNode schema = body.at("/generationConfig/responseSchema");
        assertThat(schema.at("/properties/pages/type").asText()).isEqualTo("ARRAY");
        assertThat(schema.at("/properties/pages/items/required").toString())
                .contains("pageId", "documentTypeCode", "confidence", "quotedEvidenceText");

        String systemText = body.at("/systemInstruction/parts/0/text").asText();
        assertThat(systemText)
                .containsIgnoringCase("verbatim")
                .containsIgnoringCase("untrusted")
                .doesNotContain(DOCUMENT_SENTINEL);
        String userText = body.at("/contents/0/parts/0/text").asText();
        assertThat(userText).startsWith("UNTRUSTED_DOCUMENT_DATA_JSON:\n");
        JsonNode payload =
                JSON.readTree(userText.substring("UNTRUSTED_DOCUMENT_DATA_JSON:\n".length()));
        assertThat(payload.at("/pages/0/pageId").asText()).isEqualTo(FIRST_PAGE.toString());
        assertThat(payload.at("/pages/0/packagePageIndex").asInt()).isEqualTo(3);
        assertThat(payload.at("/pages/0/headText").asText()).contains(DOCUMENT_SENTINEL);
        assertThat(payload.at("/taxonomy/0/code").asText()).isEqualTo("PAYSTUB");

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.tokens()).isEqualTo(new AiTokenCounts(120, 42, 18, 0));
        assertThat(result.proposals()).hasSize(1);
        PageTypeClassificationResult.PageTypeProposal proposal = result.proposals().get(0);
        assertThat(proposal.pageId()).isEqualTo(FIRST_PAGE);
        assertThat(proposal.documentTypeCode()).isEqualTo("PAYSTUB");
        assertThat(proposal.confidence()).isEqualByComparingTo("0.82");
        assertThat(proposal.quotedEvidenceText()).isEqualTo("Earnings Statement");
    }

    @Test
    void an_empty_pages_array_is_a_valid_answer_meaning_nothing_could_be_typed() throws Exception {
        server.enqueue(json(successResponse("{\"pages\":[]}")));

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(2)).classify(REQUEST);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.proposals()).isEmpty();
    }

    @Test
    void an_unknown_answer_survives_because_the_model_may_honestly_decline() throws Exception {
        server.enqueue(
                json(
                        successResponse(
                                """
                                {"pages":[{"pageId":"00000000-0000-4000-8000-00000000000b",
                                  "documentTypeCode":"UNKNOWN","confidence":0.10,
                                  "quotedEvidenceText":"COLORADO"}]}
                                """)));

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(2)).classify(REQUEST);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.proposals())
                .singleElement()
                .extracting(PageTypeClassificationResult.PageTypeProposal::documentTypeCode)
                .isEqualTo("UNKNOWN");
    }

    @Test
    void a_code_outside_the_request_taxonomy_is_dropped_and_the_rest_survive() throws Exception {
        server.enqueue(
                json(
                        successResponse(
                                """
                                {"pages":[
                                  {"pageId":"00000000-0000-4000-8000-00000000000a",
                                   "documentTypeCode":"INVENTED_TYPE","confidence":0.99,
                                   "quotedEvidenceText":"Earnings Statement"},
                                  {"pageId":"00000000-0000-4000-8000-00000000000b",
                                   "documentTypeCode":"DRIVERS_LICENSE","confidence":0.77,
                                   "quotedEvidenceText":"COLORADO DRIVER LICENSE"}]}
                                """)));

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(2)).classify(REQUEST);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.proposals()).hasSize(1);
        assertThat(result.proposals().get(0).pageId()).isEqualTo(SECOND_PAGE);
        assertThat(result.proposals().get(0).documentTypeCode()).isEqualTo("DRIVERS_LICENSE");
    }

    @Test
    void a_blank_evidence_quote_is_dropped_because_no_gate_could_ever_verify_it() throws Exception {
        server.enqueue(
                json(
                        successResponse(
                                """
                                {"pages":[
                                  {"pageId":"00000000-0000-4000-8000-00000000000a",
                                   "documentTypeCode":"PAYSTUB","confidence":0.95,
                                   "quotedEvidenceText":"   "},
                                  {"pageId":"00000000-0000-4000-8000-00000000000b",
                                   "documentTypeCode":"DRIVERS_LICENSE","confidence":0.77,
                                   "quotedEvidenceText":"COLORADO DRIVER LICENSE"}]}
                                """)));

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(2)).classify(REQUEST);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.proposals()).hasSize(1);
        assertThat(result.proposals().get(0).pageId()).isEqualTo(SECOND_PAGE);
    }

    @Test
    void an_answer_about_a_page_that_was_never_asked_about_is_dropped() throws Exception {
        server.enqueue(
                json(
                        successResponse(
                                """
                                {"pages":[{"pageId":"00000000-0000-4000-8000-0000000000ff",
                                  "documentTypeCode":"PAYSTUB","confidence":0.95,
                                  "quotedEvidenceText":"Earnings Statement"}]}
                                """)));

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(2)).classify(REQUEST);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.proposals()).isEmpty();
    }

    @Test
    void two_answers_about_one_page_leave_the_adapter_as_one() throws Exception {
        // A second answer for a page is not a second question: downstream the two would retype
        // the same page twice, last one winning, with no unique index to notice. The page id is
        // consumed by the first proposal that survives the shape checks.
        server.enqueue(
                json(
                        successResponse(
                                """
                                {"pages":[
                                  {"pageId":"00000000-0000-4000-8000-00000000000a",
                                   "documentTypeCode":"PAYSTUB","confidence":0.95,
                                   "quotedEvidenceText":"Earnings Statement"},
                                  {"pageId":"00000000-0000-4000-8000-00000000000a",
                                   "documentTypeCode":"DRIVERS_LICENSE","confidence":0.99,
                                   "quotedEvidenceText":"COLORADO DRIVER LICENSE"}]}
                                """)));

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(2)).classify(REQUEST);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.proposals()).hasSize(1);
        assertThat(result.proposals().get(0).documentTypeCode()).isEqualTo("PAYSTUB");
    }

    @Test
    void the_adapter_names_the_provider_the_ledger_attributes_spend_to() {
        assertThat(adapter(Duration.ofSeconds(2)).provider()).isEqualTo("vertex-gemini");
    }

    @Test
    void an_empty_request_returns_error_without_an_http_call() {
        PageTypeClassificationResult result =
                adapter(Duration.ofSeconds(1))
                        .classify(new PageTypeClassificationRequest(List.of(), List.of()));

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.ERROR);
        assertThat(server.getRequestCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("badResponses")
    void provider_and_malformed_responses_never_throw_or_log_content(
            MockResponse response, CapturedOutput output) {
        server.enqueue(response);

        PageTypeClassificationResult result = adapter(Duration.ofSeconds(1)).classify(REQUEST);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.ERROR);
        assertThat(result.proposals()).isEmpty();
        assertThat(result.tokens()).isEqualTo(AiTokenCounts.ZERO);
        assertThat(output.getAll())
                .doesNotContain(DOCUMENT_SENTINEL)
                .doesNotContain(RESPONSE_SENTINEL)
                .doesNotContain("synthetic-access-token");
    }

    private static Stream<Arguments> badResponses() throws IOException {
        return Stream.of(
                Arguments.of(new MockResponse().setResponseCode(503).setBody(RESPONSE_SENTINEL)),
                Arguments.of(new MockResponse().setResponseCode(400).setBody(RESPONSE_SENTINEL)),
                // Not JSON at all.
                Arguments.of(json(RESPONSE_SENTINEL)),
                // Valid transport, but the model's own answer is not JSON.
                Arguments.of(json(successResponse("not json at all " + RESPONSE_SENTINEL))),
                // Structurally invalid proposals: whole response refused, not half-believed.
                Arguments.of(
                        json(
                                successResponse(
                                        "{\"pages\":[{\"documentTypeCode\":\"PAYSTUB\","
                                                + "\"confidence\":0.9,"
                                                + "\"quotedEvidenceText\":\"x\"}]}"))),
                Arguments.of(
                        json(
                                successResponse(
                                        "{\"pages\":[{\"pageId\":\"not-a-uuid\","
                                                + "\"documentTypeCode\":\"PAYSTUB\","
                                                + "\"confidence\":0.9,"
                                                + "\"quotedEvidenceText\":\"x\"}]}"))),
                Arguments.of(
                        json(
                                successResponse(
                                        "{\"pages\":[{\"pageId\":"
                                                + "\"00000000-0000-4000-8000-00000000000a\","
                                                + "\"documentTypeCode\":\"PAYSTUB\","
                                                + "\"confidence\":7.5,"
                                                + "\"quotedEvidenceText\":\"x\"}]}"))));
    }

    private VertexGeminiPageTypeClassificationAdapter adapter(Duration timeout) {
        GoogleCredentials credentials =
                GoogleCredentials.create(
                        new AccessToken(
                                "synthetic-access-token",
                                Date.from(Instant.now().plusSeconds(3600))));
        return new VertexGeminiPageTypeClassificationAdapter(
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
