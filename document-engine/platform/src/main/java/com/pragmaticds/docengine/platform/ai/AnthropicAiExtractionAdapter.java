package com.pragmaticds.docengine.platform.ai;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic Messages API adapter. It returns content-free error results for every request,
 * transport, HTTP, and response-parsing failure; no exception escapes {@link #extract}.
 */
public final class AnthropicAiExtractionAdapter implements AiExtractionPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAiExtractionAdapter.class);
    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private final String model;
    private final String apiKey;
    private final URI messagesUri;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final BankStatementExtractionSchema bankStatementSchema;
    private final BankStatementExtractionParser bankStatementParser;
    private final BankStatementPrompt bankStatementPrompt;

    public AnthropicAiExtractionAdapter(
            String model, String apiKey, String baseUrl, Duration timeout) {
        this.model = requireText(model, "model");
        this.apiKey = requireText(apiKey, "apiKey");
        this.messagesUri = messagesUri(requireText(baseUrl, "baseUrl"));
        this.timeout = requirePositive(timeout);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.mapper =
                JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .build();
        this.bankStatementSchema = new BankStatementExtractionSchema();
        this.bankStatementParser = new BankStatementExtractionParser(bankStatementSchema);
        this.bankStatementPrompt = new BankStatementPrompt();
    }

    /** Provider and model: WHICH model answers is behavior, so it rides the fingerprint. */
    @Override
    public java.util.Optional<String> behaviorIdentity() {
        return java.util.Optional.of("anthropic/" + model);
    }

    @Override
    public AiExtractionResult extract(AiExtractionRequest request) {
        if (!valid(request)) {
            return error("invalid_request");
        }
        // This adapter still speaks only the bank-statement dialect; a request for any other
        // type fails closed with a reason that names the gap instead of a generic refusal.
        if (request.documentType() != AiDocumentType.BANK_STATEMENT) {
            return error("unsupported_document_type");
        }

        String requestBody;
        try {
            requestBody = buildRequestBody(request);
        } catch (IOException | RuntimeException invalidRequest) {
            return error("invalid_request");
        }

        HttpRequest httpRequest;
        try {
            httpRequest =
                    HttpRequest.newBuilder(messagesUri)
                            .timeout(timeout)
                            .header("content-type", "application/json")
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", API_VERSION)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody, UTF_8))
                            .build();
        } catch (RuntimeException invalidRequest) {
            return error("invalid_request");
        }

        HttpResponse<String> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error("provider_transient");
        } catch (IOException | RuntimeException transportFailure) {
            return error("provider_transient");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return error(providerFailureReason(response.statusCode()));
        }

        try {
            AiExtractionResult parsed = bankStatementParser.parse(parseResponse(response.body()));
            if (parsed.status() == AiExtractionStatus.OK) {
                logSuccess(parsed.tokenCounts());
            } else {
                log.warn(
                        "AI extraction provider={} status=ERROR reason={}",
                        PROVIDER,
                        parsed.reason());
            }
            return parsed;
        } catch (IOException | RuntimeException invalidResponse) {
            return error("invalid_response");
        }
    }

    private String buildRequestBody(AiExtractionRequest request) throws JsonProcessingException {
        JsonNode schemaHint = mapper.readTree(request.outputSchemaJson());
        JsonNode canonicalSchema = mapper.readTree(bankStatementSchema.schemaJson());
        if (schemaHint == null
                || !schemaHint.isObject()
                || !schemaHint.equals(canonicalSchema)) {
            throw new IllegalArgumentException("output schema must be a JSON object");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_OUTPUT_TOKENS);

        ObjectNode systemBlock = body.putArray("system").addObject();
        systemBlock.put("type", "text");
        systemBlock.put("text", bankStatementPrompt.cachedPrefix(bankStatementSchema.schemaJson()));
        systemBlock.putObject("cache_control").put("type", "ephemeral");

        ObjectNode userMessage = body.putArray("messages").addObject();
        userMessage.put("role", "user");
        ObjectNode userBlock = userMessage.putArray("content").addObject();
        userBlock.put("type", "text");
        ObjectNode documentData = mapper.createObjectNode();
        documentData.put("documentType", request.documentType().name());
        documentData.put("documentText", request.documentText());
        documentData.put("tableStructureText", request.tableStructureText());
        userBlock.put(
                "text",
                "UNTRUSTED_DOCUMENT_DATA_JSON:\n"
                        + mapper.writeValueAsString(documentData));

        ObjectNode format = body.putObject("output_config").putObject("format");
        format.put("type", "json_schema");
        format.set("schema", canonicalSchema);
        return mapper.writeValueAsString(body);
    }

    private AiExtractionResult parseResponse(String responseBody) throws JsonProcessingException {
        JsonNode root = mapper.readTree(responseBody);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("response must be a JSON object");
        }
        String stopReason = root.path("stop_reason").asText();
        if ("max_tokens".equals(stopReason) || "refusal".equals(stopReason)) {
            throw new IllegalArgumentException("response did not complete");
        }

        String structuredJson = firstTextBlock(root.path("content"));
        JsonNode structured = mapper.readTree(structuredJson);
        if (structured == null) {
            throw new IllegalArgumentException("missing structured response");
        }

        JsonNode usage = root.path("usage");
        AiTokenCounts tokenCounts =
                new AiTokenCounts(
                        usage.path("input_tokens").asLong(),
                        usage.path("output_tokens").asLong(),
                        usage.path("cache_read_input_tokens").asLong(),
                        usage.path("cache_creation_input_tokens").asLong());
        String responseModel = root.path("model").asText(model);
        return new AiExtractionResult(
                structuredJson,
                PROVIDER,
                responseModel,
                AiExtractionStatus.OK,
                tokenCounts,
                null);
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

    private static String firstTextBlock(JsonNode content) {
        if (content instanceof ArrayNode blocks) {
            for (JsonNode block : blocks) {
                if ("text".equals(block.path("type").asText())
                        && block.path("text").isTextual()) {
                    return block.path("text").asText();
                }
            }
        }
        throw new IllegalArgumentException("missing text response");
    }

    private AiExtractionResult error(String reason) {
        log.warn("AI extraction provider={} status=ERROR reason={}", PROVIDER, reason);
        return new AiExtractionResult(
                null,
                PROVIDER,
                model,
                AiExtractionStatus.ERROR,
                AiTokenCounts.ZERO,
                reason);
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

    private static URI messagesUri(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return URI.create(normalized).resolve("v1/messages");
    }
}
