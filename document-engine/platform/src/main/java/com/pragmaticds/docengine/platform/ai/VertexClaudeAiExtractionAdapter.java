package com.pragmaticds.docengine.platform.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.LogLevel;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.anthropic.vertex.backends.VertexBackend;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Claude-on-Vertex structured-output adapter with content-free errors and logs. */
public final class VertexClaudeAiExtractionAdapter implements AiExtractionPort, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(VertexClaudeAiExtractionAdapter.class);
    private static final String PROVIDER = "vertex-claude";
    private static final long MAX_OUTPUT_TOKENS = 8192;
    private final String model;
    private final AnthropicClient client;
    private final ObjectMapper mapper;
    private final BankStatementExtractionSchema bankStatementSchema;
    private final BankStatementExtractionParser bankStatementParser;
    private final BankStatementPrompt bankStatementPrompt;
    private final JsonOutputFormat.Schema responseSchema;

    public VertexClaudeAiExtractionAdapter(
            String model,
            String project,
            String region,
            GoogleCredentials credentials,
            Duration timeout) {
        this(model, project, region, credentials, null, timeout);
    }

    VertexClaudeAiExtractionAdapter(
            String model,
            String project,
            String region,
            GoogleCredentials credentials,
            String baseUrl,
            Duration timeout) {
        this.model = requireText(model, "model");
        VertexBackend.Builder backend =
                VertexBackend.builder()
                        .project(requireText(project, "project"))
                        .region(requireText(region, "region"))
                        .googleCredentials(Objects.requireNonNull(credentials, "credentials"));
        if (baseUrl != null) {
            backend.baseUrl(requireText(baseUrl, "baseUrl"));
        }
        this.client =
                AnthropicOkHttpClient.builder()
                        .backend(backend.build())
                        .timeout(requirePositive(timeout))
                        .maxRetries(0)
                        .logLevel(LogLevel.OFF)
                        .build();
        this.mapper =
                JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .build();
        this.bankStatementSchema = new BankStatementExtractionSchema();
        this.bankStatementParser = new BankStatementExtractionParser(bankStatementSchema);
        this.bankStatementPrompt = new BankStatementPrompt();
        try {
            JsonNode canonicalSchema = mapper.readTree(bankStatementSchema.schemaJson());
            if (canonicalSchema == null || !canonicalSchema.isObject()) {
                throw new IllegalArgumentException("canonical schema must be an object");
            }
            Map<String, JsonValue> schemaProperties = new LinkedHashMap<>();
            canonicalSchema
                    .properties()
                    .forEach(
                            property ->
                                    schemaProperties.put(
                                            property.getKey(),
                                            JsonValue.fromJsonNode(property.getValue())));
            this.responseSchema =
                    JsonOutputFormat.Schema.builder()
                            .additionalProperties(schemaProperties)
                            .build();
        } catch (java.io.IOException invalidBundledSchema) {
            throw new IllegalArgumentException("canonical schema is invalid", invalidBundledSchema);
        }
    }

    /** Provider and model: WHICH model answers is behavior, so it rides the fingerprint. */
    @Override
    public java.util.Optional<String> behaviorIdentity() {
        return java.util.Optional.of("vertex-claude/" + model);
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

        MessageCreateParams params;
        try {
            JsonNode schemaHint = mapper.readTree(request.outputSchemaJson());
            JsonNode canonicalSchema = mapper.readTree(bankStatementSchema.schemaJson());
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
            TextBlockParam systemBlock =
                    TextBlockParam.builder()
                            .text(
                                    bankStatementPrompt.cachedPrefix(
                                            bankStatementSchema.schemaJson()))
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build();
            params =
                    MessageCreateParams.builder()
                            .model(model)
                            .maxTokens(MAX_OUTPUT_TOKENS)
                            .systemOfTextBlockParams(List.of(systemBlock))
                            .addUserMessage(userText)
                            .outputConfig(
                                    OutputConfig.builder()
                                            .format(JsonOutputFormat.of(responseSchema))
                                            .build())
                            .build();
        } catch (RuntimeException | java.io.IOException invalidRequest) {
            return error("invalid_request");
        }

        Message message;
        try {
            message = client.messages().create(params);
        } catch (AnthropicServiceException providerFailure) {
            return error(providerFailureReason(providerFailure.statusCode()));
        } catch (AnthropicIoException transportFailure) {
            return error("provider_transient");
        } catch (AnthropicException | IllegalArgumentException invalidResponse) {
            return error("invalid_response");
        } catch (RuntimeException invalidResponse) {
            return error("invalid_response");
        }

        try {
            if (message.stopReason()
                    .filter(
                            stopReason ->
                                    stopReason.equals(StopReason.MAX_TOKENS)
                                            || stopReason.equals(StopReason.REFUSAL)
                                            || stopReason.equals(
                                                    StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED))
                    .isPresent()) {
                return error("invalid_response");
            }
            String structuredJson =
                    message.content().stream()
                            .filter(ContentBlock::isText)
                            .map(block -> block.asText().text())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "missing text response"));
            Usage usage = message.usage();
            AiTokenCounts tokenCounts =
                    new AiTokenCounts(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            usage.cacheReadInputTokens().orElse(0L),
                            usage.cacheCreationInputTokens().orElse(0L));
            AiExtractionResult parsed =
                    bankStatementParser.parse(
                            new AiExtractionResult(
                                    structuredJson,
                                    PROVIDER,
                                    message.model().asString(),
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
