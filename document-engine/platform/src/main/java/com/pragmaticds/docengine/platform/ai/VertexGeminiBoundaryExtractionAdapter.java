package com.pragmaticds.docengine.platform.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vertex Gemini adapter for boundary extraction (Phase E2) — the same discipline as {@link
 * VertexGeminiAiExtractionAdapter}: structured output pinned by a response schema, temperature 0,
 * no SDK retries (the stage machine owns retry), content-free errors and logs, and it NEVER
 * throws — every failure shape degrades to an ERROR result, which downstream means "today's
 * deterministic split stands".
 *
 * <p>The prompt is generic; the mortgage knowledge arrives as taxonomy DATA in the request. The
 * model's answers earn belief only through the engine's anchoring gates — nothing returned here
 * is trusted, so the parse below validates SHAPE (types, ranges) and leaves truth to the gates.
 */
public final class VertexGeminiBoundaryExtractionAdapter
        implements BoundaryExtractionPort, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(VertexGeminiBoundaryExtractionAdapter.class);
    private static final String PROVIDER = "vertex-gemini";

    /** Proposals are tiny (a page index and a quote each); windows never need extraction-sized output. */
    private static final int MAX_OUTPUT_TOKENS = 2048;

    private final String model;
    private final Client client;
    private final ObjectMapper mapper;
    private final BoundaryExtractionPrompt prompt = new BoundaryExtractionPrompt();
    private final Schema responseSchema;
    private final HttpOptions requestHttpOptions;

    public VertexGeminiBoundaryExtractionAdapter(
            String model,
            String project,
            String region,
            GoogleCredentials credentials,
            Duration timeout) {
        this(model, project, region, credentials, null, timeout);
    }

    VertexGeminiBoundaryExtractionAdapter(
            String model,
            String project,
            String region,
            GoogleCredentials credentials,
            String baseUrl,
            Duration timeout) {
        this.model = requireText(model, "model");
        String requiredProject = requireText(project, "project");
        String requiredRegion = requireText(region, "region");
        Objects.requireNonNull(credentials, "credentials");
        Duration requiredTimeout = requirePositive(timeout);
        int timeoutMillis = Math.toIntExact(requiredTimeout.toMillis());
        HttpRetryOptions noSdkRetries = HttpRetryOptions.builder().attempts(1).build();
        this.requestHttpOptions =
                HttpOptions.builder().timeout(timeoutMillis).retryOptions(noSdkRetries).build();
        HttpOptions.Builder httpOptions =
                HttpOptions.builder()
                        .apiVersion("v1")
                        .timeout(timeoutMillis)
                        .retryOptions(noSdkRetries);
        if (baseUrl != null) {
            httpOptions.baseUrl(requireText(baseUrl, "baseUrl"));
        }
        this.client =
                Client.builder()
                        .vertexAI(true)
                        .project(requiredProject)
                        .location(requiredRegion)
                        .credentials(credentials)
                        .httpOptions(httpOptions.build())
                        .build();
        this.mapper =
                JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .build();
        this.responseSchema = responseSchema();
    }

    /** {@code {boundaries: [{packagePageIndex, documentTypeCode, confidence, quotedHeaderText, partitionValue}]}} */
    private static Schema responseSchema() {
        Map<String, Schema> boundary = new LinkedHashMap<>();
        boundary.put(
                "packagePageIndex",
                Schema.builder()
                        .type(Type.Known.INTEGER)
                        .description("The page that STARTS a new document")
                        .minimum(0.0)
                        .build());
        boundary.put(
                "documentTypeCode",
                Schema.builder()
                        .type(Type.Known.STRING)
                        .description("A taxonomy code, or UNKNOWN")
                        .build());
        boundary.put(
                "confidence",
                Schema.builder().type(Type.Known.NUMBER).minimum(0.0).build());
        boundary.put(
                "quotedHeaderText",
                Schema.builder()
                        .type(Type.Known.STRING)
                        .description("Header text READ from the page, verbatim as printed")
                        .build());
        boundary.put(
                "partitionValue",
                Schema.builder().type(Type.Known.STRING).nullable(true).build());
        Schema boundarySchema =
                Schema.builder()
                        .type(Type.Known.OBJECT)
                        .properties(boundary)
                        .propertyOrdering(new ArrayList<>(boundary.keySet()))
                        .required(
                                List.of(
                                        "packagePageIndex",
                                        "documentTypeCode",
                                        "confidence",
                                        "quotedHeaderText"))
                        .build();
        Map<String, Schema> root = new LinkedHashMap<>();
        root.put(
                "boundaries",
                Schema.builder().type(Type.Known.ARRAY).items(boundarySchema).build());
        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(root)
                .propertyOrdering(new ArrayList<>(root.keySet()))
                .required(List.of("boundaries"))
                .build();
    }

    @Override
    public BoundaryExtractionResult proposeBoundaries(BoundaryExtractionRequest request) {
        if (request == null || request.pages() == null || request.pages().isEmpty()) {
            return error("invalid_request");
        }

        Content content;
        GenerateContentConfig config;
        try {
            content =
                    Content.builder()
                            .role("user")
                            .parts(
                                    Part.fromText(
                                            "UNTRUSTED_DOCUMENT_DATA_JSON:\n" + payload(request)))
                            .build();
            config =
                    GenerateContentConfig.builder()
                            .systemInstruction(Content.fromParts(Part.fromText(prompt.cachedPrefix())))
                            .temperature(0.0f)
                            .maxOutputTokens(MAX_OUTPUT_TOKENS)
                            .responseMimeType("application/json")
                            .responseSchema(responseSchema)
                            .httpOptions(requestHttpOptions)
                            .build();
        } catch (RuntimeException | JsonProcessingException invalidRequest) {
            return error("invalid_request");
        }

        GenerateContentResponse response;
        try {
            response = client.models.generateContent(model, content, config);
        } catch (ApiException providerFailure) {
            return error(providerFailureReason(providerFailure.code()));
        } catch (GenAiIOException transportFailure) {
            return error(
                    causedByJsonProcessing(transportFailure)
                            ? "invalid_response"
                            : "provider_transient");
        } catch (RuntimeException invalidResponse) {
            return error("invalid_response");
        }

        try {
            if (response == null
                    || response.finishReason().knownEnum() == FinishReason.Known.MAX_TOKENS) {
                return error("invalid_response");
            }
            String structuredJson = response.text();
            if (structuredJson == null || structuredJson.isBlank()) {
                return error("invalid_response");
            }
            List<BoundaryExtractionResult.ProposedBoundary> boundaries = parse(structuredJson);
            if (boundaries == null) {
                return error("invalid_response");
            }
            GenerateContentResponseUsageMetadata usage =
                    response
                            .usageMetadata()
                            .orElseGet(
                                    () ->
                                            GenerateContentResponseUsageMetadata.builder()
                                                    .build());
            AiTokenCounts tokenCounts =
                    new AiTokenCounts(
                            usage.promptTokenCount().orElse(0),
                            usage.candidatesTokenCount().orElse(0),
                            usage.cachedContentTokenCount().orElse(0),
                            0);
            log.info(
                    "boundary extraction provider={} status=OK proposals={} inputTokens={} outputTokens={}",
                    PROVIDER,
                    boundaries.size(),
                    tokenCounts.inputTokens(),
                    tokenCounts.outputTokens());
            return new BoundaryExtractionResult(
                    BoundaryExtractionStatus.OK, boundaries, tokenCounts);
        } catch (RuntimeException invalidResponse) {
            return error("invalid_response");
        }
    }

    /** The request as one JSON payload — the untrusted-data envelope the prompt names. */
    private String payload(BoundaryExtractionRequest request) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode taxonomy = root.putArray("taxonomy");
        for (BoundaryExtractionRequest.TypeDescription type :
                request.taxonomy() == null ? List.<BoundaryExtractionRequest.TypeDescription>of() : request.taxonomy()) {
            ObjectNode node = taxonomy.addObject();
            node.put("code", type.code());
            node.put("description", type.description());
        }
        ArrayNode pages = root.putArray("pages");
        for (BoundaryExtractionRequest.CandidatePage page : request.pages()) {
            ObjectNode node = pages.addObject();
            node.put("packagePageIndex", page.packagePageIndex());
            node.put("deterministicTypeCode", page.deterministicTypeCode());
            node.put(
                    "deterministicConfidence",
                    page.deterministicConfidence() == null
                            ? null
                            : page.deterministicConfidence().toPlainString());
            node.put("headText", page.headText());
            node.put("footText", page.footText());
            node.put("tableStructureText", page.tableStructureText());
        }
        if (request.partitionHint() != null) {
            root.put("partitionHint", request.partitionHint());
        }
        return mapper.writeValueAsString(root);
    }

    /**
     * SHAPE validation only — truth is the gates' job. Null on any structural violation, which
     * the caller reports as one invalid response rather than acting on a fragment of it.
     */
    private List<BoundaryExtractionResult.ProposedBoundary> parse(String structuredJson) {
        JsonNode root;
        try {
            root = mapper.readTree(structuredJson);
        } catch (JsonProcessingException unparseable) {
            return null;
        }
        JsonNode boundaries = root == null ? null : root.get("boundaries");
        if (boundaries == null || !boundaries.isArray()) {
            return null;
        }
        List<BoundaryExtractionResult.ProposedBoundary> parsed = new ArrayList<>();
        for (JsonNode node : boundaries) {
            JsonNode index = node.get("packagePageIndex");
            JsonNode type = node.get("documentTypeCode");
            JsonNode confidence = node.get("confidence");
            JsonNode quote = node.get("quotedHeaderText");
            if (index == null
                    || !index.canConvertToInt()
                    || index.asInt() < 0
                    || type == null
                    || !type.isTextual()
                    || confidence == null
                    || !confidence.isNumber()
                    || quote == null
                    || !quote.isTextual()) {
                return null;
            }
            BigDecimal confidenceValue = confidence.decimalValue();
            if (confidenceValue.compareTo(BigDecimal.ZERO) < 0
                    || confidenceValue.compareTo(BigDecimal.ONE) > 0) {
                return null;
            }
            JsonNode partition = node.get("partitionValue");
            parsed.add(
                    new BoundaryExtractionResult.ProposedBoundary(
                            index.asInt(),
                            type.asText(),
                            confidenceValue,
                            quote.asText(),
                            partition == null || partition.isNull() ? null : partition.asText()));
        }
        return List.copyOf(parsed);
    }

    @Override
    public void close() {
        client.close();
    }

    private BoundaryExtractionResult error(String reason) {
        log.warn("boundary extraction provider={} status=ERROR reason={}", PROVIDER, reason);
        return new BoundaryExtractionResult(
                BoundaryExtractionStatus.ERROR, List.of(), AiTokenCounts.ZERO);
    }

    private static String providerFailureReason(int statusCode) {
        return statusCode == 408
                        || statusCode == 409
                        || statusCode == 425
                        || statusCode == 429
                        || statusCode >= 500
                ? "provider_transient"
                : "provider_permanent";
    }

    private static boolean causedByJsonProcessing(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof JsonProcessingException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        return value;
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return value;
    }
}
