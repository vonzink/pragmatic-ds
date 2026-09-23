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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vertex Gemini adapter for page-type classification — the same discipline as {@link
 * VertexGeminiBoundaryExtractionAdapter}: structured output pinned by a response schema,
 * temperature 0, no SDK retries (the stage machine owns retry), content-free errors and logs, and
 * it NEVER throws — every failure shape degrades to an ERROR result, which downstream means "the
 * page stays UNKNOWN exactly as the rule packs left it".
 *
 * <p>The prompt is generic; the mortgage knowledge arrives as taxonomy DATA in the request. Nothing
 * the model returns is trusted: the parse below validates SHAPE (types, ranges, a page id that was
 * actually asked about) and drops proposals it can see are unusable — a type code outside the
 * request's taxonomy, or an evidence quote that is blank. Truth about the quote itself is not
 * decidable here (this module holds no spans) and belongs to the stage's verification gate.
 */
public final class VertexGeminiPageTypeClassificationAdapter
        implements PageTypeClassificationPort, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(VertexGeminiPageTypeClassificationAdapter.class);
    private static final String PROVIDER = "vertex-gemini";

    /**
     * The taxonomy code that always survives the allowlist: the model's honest "I could not tell
     * either", which the stage reads as "leave the deterministic result alone".
     */
    private static final String UNKNOWN_TYPE_CODE = "UNKNOWN";

    /**
     * Proposals are tiny (an id, a code, a number and a short quote each) and the batch is capped by
     * the stage, so classification never needs extraction-sized output.
     */
    private static final int MAX_OUTPUT_TOKENS = 4096;

    private final String model;
    private final Client client;
    private final ObjectMapper mapper;
    private final PageClassificationPrompt prompt = new PageClassificationPrompt();
    private final Schema responseSchema;
    private final HttpOptions requestHttpOptions;

    public VertexGeminiPageTypeClassificationAdapter(
            String model,
            String project,
            String region,
            GoogleCredentials credentials,
            Duration timeout) {
        this(model, project, region, credentials, null, timeout);
    }

    VertexGeminiPageTypeClassificationAdapter(
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

    /** {@code {pages: [{pageId, documentTypeCode, confidence, quotedEvidenceText}]}} */
    private static Schema responseSchema() {
        Map<String, Schema> page = new LinkedHashMap<>();
        page.put(
                "pageId",
                Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The pageId given for this page, copied back exactly")
                        .build());
        page.put(
                "documentTypeCode",
                Schema.builder()
                        .type(Type.Known.STRING)
                        .description("A taxonomy code, or UNKNOWN")
                        .build());
        page.put(
                "confidence",
                Schema.builder().type(Type.Known.NUMBER).minimum(0.0).maximum(1.0).build());
        page.put(
                "quotedEvidenceText",
                Schema.builder()
                        .type(Type.Known.STRING)
                        .description("Short text READ from the page, verbatim as printed")
                        .build());
        Schema pageSchema =
                Schema.builder()
                        .type(Type.Known.OBJECT)
                        .properties(page)
                        .propertyOrdering(new ArrayList<>(page.keySet()))
                        .required(
                                List.of(
                                        "pageId",
                                        "documentTypeCode",
                                        "confidence",
                                        "quotedEvidenceText"))
                        .build();
        Map<String, Schema> root = new LinkedHashMap<>();
        root.put("pages", Schema.builder().type(Type.Known.ARRAY).items(pageSchema).build());
        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(root)
                .propertyOrdering(new ArrayList<>(root.keySet()))
                .required(List.of("pages"))
                .build();
    }

    /** The same string this adapter's own log lines carry, so logs and ledger agree. */
    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public PageTypeClassificationResult classify(PageTypeClassificationRequest request) {
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
                            .systemInstruction(
                                    Content.fromParts(Part.fromText(prompt.cachedPrefix())))
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
            List<PageTypeClassificationResult.PageTypeProposal> proposals =
                    parse(structuredJson, request);
            if (proposals == null) {
                return error("invalid_response");
            }
            GenerateContentResponseUsageMetadata usage =
                    response
                            .usageMetadata()
                            .orElseGet(() -> GenerateContentResponseUsageMetadata.builder().build());
            AiTokenCounts tokenCounts =
                    new AiTokenCounts(
                            usage.promptTokenCount().orElse(0),
                            usage.candidatesTokenCount().orElse(0),
                            usage.cachedContentTokenCount().orElse(0),
                            0);
            log.info(
                    "page classification provider={} status=OK proposals={} inputTokens={} outputTokens={}",
                    PROVIDER,
                    proposals.size(),
                    tokenCounts.inputTokens(),
                    tokenCounts.outputTokens());
            return new PageTypeClassificationResult(
                    PageTypeClassificationStatus.OK, proposals, tokenCounts);
        } catch (RuntimeException invalidResponse) {
            return error("invalid_response");
        }
    }

    /** The request as one JSON payload — the untrusted-data envelope the prompt names. */
    private String payload(PageTypeClassificationRequest request) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode taxonomy = root.putArray("taxonomy");
        for (PageTypeClassificationRequest.TypeDescription type : taxonomyOf(request)) {
            ObjectNode node = taxonomy.addObject();
            node.put("code", type.code());
            node.put("description", type.description());
        }
        ArrayNode pages = root.putArray("pages");
        for (PageTypeClassificationRequest.CandidatePage page : request.pages()) {
            ObjectNode node = pages.addObject();
            node.put("pageId", page.pageId() == null ? null : page.pageId().toString());
            node.put("packagePageIndex", page.packagePageIndex());
            node.put("headText", page.headText());
            node.put("footText", page.footText());
        }
        return mapper.writeValueAsString(root);
    }

    /**
     * SHAPE validation and allowlisting — verifying the QUOTE is the stage's job, not this module's.
     * A structurally broken entry (missing field, wrong JSON type, confidence out of range, a page
     * id that is not a UUID) makes the whole response untrustworthy and returns null, so the caller
     * reports one invalid response rather than acting on a fragment of it.
     *
     * <p>Individually unusable-but-well-formed entries are DROPPED instead, because they cost the
     * other pages nothing: a code outside the request's taxonomy (the model inventing a type), a
     * page id nobody asked about (an answer we could not attribute), and a blank quote (evidence the
     * engine could never verify, so the adapter must not emit it and let a later gate mistake
     * emptiness for a match).
     */
    private List<PageTypeClassificationResult.PageTypeProposal> parse(
            String structuredJson, PageTypeClassificationRequest request) {
        JsonNode root;
        try {
            root = mapper.readTree(structuredJson);
        } catch (JsonProcessingException unparseable) {
            return null;
        }
        JsonNode pages = root == null ? null : root.get("pages");
        if (pages == null || !pages.isArray()) {
            return null;
        }
        Set<String> allowedCodes = new HashSet<>();
        for (PageTypeClassificationRequest.TypeDescription type : taxonomyOf(request)) {
            if (type != null && type.code() != null) {
                allowedCodes.add(type.code());
            }
        }
        Set<UUID> askedPageIds = new HashSet<>();
        for (PageTypeClassificationRequest.CandidatePage page : request.pages()) {
            if (page != null && page.pageId() != null) {
                askedPageIds.add(page.pageId());
            }
        }
        // Consumed, not merely consulted: a page id answered once is answered. Two proposals for
        // one page would be two retypes of that page downstream, the later one silently winning.
        Set<UUID> alreadyProposed = new HashSet<>();
        List<PageTypeClassificationResult.PageTypeProposal> parsed = new ArrayList<>();
        for (JsonNode node : pages) {
            JsonNode pageId = node.get("pageId");
            JsonNode type = node.get("documentTypeCode");
            JsonNode confidence = node.get("confidence");
            JsonNode quote = node.get("quotedEvidenceText");
            if (pageId == null
                    || !pageId.isTextual()
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
            UUID identifiedPage;
            try {
                identifiedPage = UUID.fromString(pageId.asText());
            } catch (IllegalArgumentException notAnIdentity) {
                return null;
            }
            if (!askedPageIds.contains(identifiedPage)) {
                continue;
            }
            String typeCode = type.asText();
            if (!UNKNOWN_TYPE_CODE.equals(typeCode) && !allowedCodes.contains(typeCode)) {
                continue;
            }
            String evidence = quote.asText();
            if (evidence.isBlank()) {
                continue;
            }
            if (!alreadyProposed.add(identifiedPage)) {
                // Claimed at EMISSION, so a proposal dropped above (unusable type, blank quote)
                // does not spend the page's one slot on an answer nobody will ever see.
                continue;
            }
            parsed.add(
                    new PageTypeClassificationResult.PageTypeProposal(
                            identifiedPage, typeCode, confidenceValue, evidence));
        }
        return List.copyOf(parsed);
    }

    private static List<PageTypeClassificationRequest.TypeDescription> taxonomyOf(
            PageTypeClassificationRequest request) {
        return request.taxonomy() == null
                ? List.<PageTypeClassificationRequest.TypeDescription>of()
                : request.taxonomy();
    }

    @Override
    public void close() {
        client.close();
    }

    private PageTypeClassificationResult error(String reason) {
        log.warn("page classification provider={} status=ERROR reason={}", PROVIDER, reason);
        return new PageTypeClassificationResult(
                PageTypeClassificationStatus.ERROR, List.of(), AiTokenCounts.ZERO);
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
