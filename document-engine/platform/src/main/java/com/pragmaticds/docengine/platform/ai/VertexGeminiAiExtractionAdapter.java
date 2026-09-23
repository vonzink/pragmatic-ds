package com.pragmaticds.docengine.platform.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Vertex Gemini structured-output adapter with content-free errors and logs. */
public final class VertexGeminiAiExtractionAdapter implements AiExtractionPort, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(VertexGeminiAiExtractionAdapter.class);
    private static final String PROVIDER = "vertex-gemini";

    /**
     * A multi-page statement's structured JSON (50–100 long-description transactions) easily
     * exceeds the old 8k output budget; hitting the ceiling truncates mid-JSON and the WHOLE
     * response is discarded ({@code finishReason=MAX_TOKENS} → {@code response_truncated}).
     * Gemini 2.5 Flash-Lite allows up to 64k out; 32k is a safe default, configurable per env.
     */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 32768;

    private final int maxOutputTokens;
    private final String model;
    private final Client client;
    private final ObjectMapper mapper;
    private final java.util.Map<AiDocumentType, TypeSupport> support;
    private final HttpOptions requestHttpOptions;

    /** One dialect plus its pre-converted Gemini response schema. */
    private record TypeSupport(AiExtractionDialect dialect, Schema responseSchema) {}

    public VertexGeminiAiExtractionAdapter(
            String model,
            String project,
            String region,
            GoogleCredentials credentials,
            int maxOutputTokens,
            Duration timeout) {
        this(model, project, region, credentials, maxOutputTokens, null, timeout);
    }

    VertexGeminiAiExtractionAdapter(
            String model,
            String project,
            String region,
            GoogleCredentials credentials,
            int maxOutputTokens,
            String baseUrl,
            Duration timeout) {
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        this.model = requireText(model, "model");
        String requiredProject = requireText(project, "project");
        String requiredRegion = requireText(region, "region");
        Objects.requireNonNull(credentials, "credentials");
        Duration requiredTimeout = requirePositive(timeout);
        int timeoutMillis = Math.toIntExact(requiredTimeout.toMillis());
        HttpRetryOptions noSdkRetries = HttpRetryOptions.builder().attempts(1).build();
        this.requestHttpOptions =
                HttpOptions.builder()
                        .timeout(timeoutMillis)
                        .retryOptions(noSdkRetries)
                        .build();
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
                        .build();
        // Every dialect's Gemini response schema is converted eagerly — a schema resource that
        // does not survive conversion fails construction, never a live request.
        GeminiBankStatementSchema converter = new GeminiBankStatementSchema();
        java.util.Map<AiDocumentType, TypeSupport> byType =
                new java.util.EnumMap<>(AiDocumentType.class);
        for (AiExtractionDialect dialect : AiExtractionDialects.all()) {
            byType.put(
                    dialect.type(),
                    new TypeSupport(dialect, converter.fromCanonical(dialect.schemaJson())));
        }
        this.support = java.util.Map.copyOf(byType);
    }

    /** Provider and model: WHICH model answers is behavior, so it rides the fingerprint. */
    @Override
    public java.util.Optional<String> behaviorIdentity() {
        return java.util.Optional.of("vertex-gemini/" + model);
    }

    @Override
    public AiExtractionResult extract(AiExtractionRequest request) {
        if (!valid(request)) {
            return error("invalid_request");
        }
        TypeSupport typeSupport = support.get(request.documentType());
        if (typeSupport == null) {
            return error("unsupported_document_type");
        }

        Content content;
        GenerateContentConfig config;
        try {
            JsonNode schemaHint = mapper.readTree(request.outputSchemaJson());
            JsonNode canonicalSchema = mapper.readTree(typeSupport.dialect().schemaJson());
            if (schemaHint == null
                    || !schemaHint.isObject()
                    || !schemaHint.equals(canonicalSchema)) {
                return error("invalid_request");
            }
            ObjectNode documentData = mapper.createObjectNode();
            documentData.put("documentType", request.documentType().name());
            documentData.put("documentText", request.documentText());
            documentData.put("tableStructureText", request.tableStructureText());
            String userText =
                    "UNTRUSTED_DOCUMENT_DATA_JSON:\n"
                            + mapper.writeValueAsString(documentData);
            // Phase F: rendered pages ride along ONLY when the stage attached them (the gate and
            // the OCR-floor trigger both live there, not here). Each image is preceded by a text
            // part naming its page, so a value read off pixels still reports a checkable page.
            java.util.List<Part> parts = new java.util.ArrayList<>();
            parts.add(Part.fromText(userText));
            if (request.pageImages() != null) {
                for (AiExtractionRequest.PageImage image : request.pageImages()) {
                    parts.add(
                            Part.fromText(
                                    "RENDERED PAGE " + (image.packagePageIndex() + 1) + ":"));
                    parts.add(Part.fromBytes(image.png(), "image/png"));
                }
            }
            content = Content.builder().role("user").parts(parts).build();
            config =
                    GenerateContentConfig.builder()
                            .systemInstruction(
                                    Content.fromParts(
                                            Part.fromText(typeSupport.dialect().cachedPrefix())))
                            .temperature(0.0f)
                            .maxOutputTokens(maxOutputTokens)
                            .responseMimeType("application/json")
                            .responseSchema(typeSupport.responseSchema())
                            .httpOptions(requestHttpOptions)
                            .build();
        } catch (RuntimeException | java.io.IOException invalidRequest) {
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
            if (response == null) {
                return error("empty_response");
            }
            // The model ran out of room before finishing the JSON: the whole structured response
            // is unusable. Distinct from a genuinely malformed envelope so the ledger says WHY —
            // this is the "raise max-output-tokens" signal, not a provider bug.
            if (response.finishReason().knownEnum() == FinishReason.Known.MAX_TOKENS) {
                return error("response_truncated");
            }
            String structuredJson = response.text();
            if (structuredJson == null || structuredJson.isBlank()) {
                return error("empty_response");
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
            AiExtractionResult parsed =
                    typeSupport
                            .dialect()
                            .parse(
                                    new AiExtractionResult(
                                            structuredJson,
                                            PROVIDER,
                                            response.modelVersion().orElse(model),
                                            AiExtractionStatus.OK,
                                            tokenCounts,
                                            null));
            if (parsed.status() == AiExtractionStatus.OK) {
                logSuccess(parsed.tokenCounts());
            } else {
                log.warn(
                        "AI extraction provider={} status=ERROR reason={}",
                        PROVIDER,
                        parsed.reason());
            }
            return parsed;
        } catch (RuntimeException invalidResponse) {
            return error("invalid_response");
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static void logSuccess(AiTokenCounts tokenCounts) {
        log.info(
                "AI extraction provider={} status=OK inputTokens={} outputTokens={} cacheReadTokens={} cacheWriteTokens={}",
                PROVIDER,
                tokenCounts.inputTokens(),
                tokenCounts.outputTokens(),
                tokenCounts.cacheReadInputTokens(),
                tokenCounts.cacheWriteInputTokens());
    }

    private AiExtractionResult error(String reason) {
        log.warn("AI extraction provider={} status=ERROR reason={}", PROVIDER, reason);
        return new AiExtractionResult(
                null, PROVIDER, model, AiExtractionStatus.ERROR, AiTokenCounts.ZERO, reason);
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

    private static boolean valid(AiExtractionRequest request) {
        return request != null
                && request.documentType() != null
                && request.documentText() != null
                && !request.documentText().isBlank()
                && request.tableStructureText() != null
                && request.outputSchemaJson() != null
                && !request.outputSchemaJson().isBlank();
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
