package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates analyzer findings against the strict envelope v2 JSON Schema
 * ({@code ai/analyzer-envelope-v2.schema.json} — the shared engine/suite contract), and a
 * declared analyzer domain against the schema its {@code schemaVersion} marker names
 * ({@code ai/income-domain-v2.schema.json}, {@code ai/submission-domain-v1.schema.json}).
 *
 * All schemas are loaded once at construction and the bean fails fast at boot if any
 * classpath resource is missing or unparseable.
 *
 * <p>The domain schemas are separate files on purpose: every stored Lab release pins the
 * envelope schema's SHA-256, so growing the envelope file would drift every release at once.
 */
@Component
public class EnvelopeValidator {

    private static final String SCHEMA_RESOURCE = "ai/analyzer-envelope-v2.schema.json";

    /** Domain marker -> classpath schema. A marker absent here is a validation error. */
    private static final Map<String, String> DOMAIN_SCHEMA_RESOURCES = Map.of(
            "income-domain-v2", "ai/income-domain-v2.schema.json",
            "submission-domain-v1", "ai/submission-domain-v1.schema.json");

    private final JsonSchema schema;
    private final Map<String, JsonSchema> domainSchemas;

    public EnvelopeValidator() {
        this.schema = load(SCHEMA_RESOURCE, "Envelope v2 schema");
        Map<String, JsonSchema> loaded = new java.util.LinkedHashMap<>();
        DOMAIN_SCHEMA_RESOURCES.forEach((marker, resource) ->
                loaded.put(marker, load(resource, "Domain schema " + marker)));
        this.domainSchemas = Map.copyOf(loaded);
    }

    private static JsonSchema load(String resource, String label) {
        try (InputStream in = EnvelopeValidator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(label + " not found on classpath: " + resource);
            }
            // Deliberately a fresh ObjectMapper: the schema contract must not inherit app-level Jackson config.
            JsonNode schemaNode = new ObjectMapper().readTree(in);
            JsonSchema loaded = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
            // Surface $ref resolution failures at boot rather than on first validate.
            loaded.initializeValidators();
            return loaded;
        } catch (IOException e) {
            throw new IllegalStateException(label + " is unparseable: " + resource, e);
        }
    }

    /**
     * Validates an envelope against the v2 schema.
     *
     * @return validation errors as networknt messages (each already prefixed with its
     *         instance location, e.g. {@code $: required property 'confidence' not found}),
     *         sorted for determinism; empty means the envelope is valid
     */
    public List<String> validate(JsonNode envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return schema.validate(envelope).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
    }

    /**
     * Validates {@code envelope.domain} against the schema its {@code schemaVersion} names — but
     * only when the domain declares one. A domain without a marker is the pre-contract shape a
     * previously promoted release's prompt produces, and must keep passing untouched.
     *
     * <p>The gate is deliberately the domain's own {@code schemaVersion} marker rather than the
     * analyzer slug: instance releases name their own analyzer slugs, so gating on slug would miss
     * them. A marker this engine does not know is a contract violation, not a pass: it routes into
     * the same retry-once-then-fail-closed flow as any shape error.
     *
     * <p>After schema validation the submission contract also checks what JSON Schema cannot:
     * {@code conflicts[].field} and {@code keyDates[].kind} are unique per domain.
     *
     * @return sorted messages re-rooted at {@code $.domain}; empty when valid or not declared
     */
    public List<String> validateDomain(JsonNode envelope) {
        Objects.requireNonNull(envelope, "envelope");
        JsonNode domain = envelope.path("domain");
        if (!domain.isObject() || !domain.has("schemaVersion")) {
            return List.of();
        }
        String marker = domain.path("schemaVersion").asText("");
        JsonSchema domainSchema = domainSchemas.get(marker);
        if (domainSchema == null) {
            return List.of("$.domain.schemaVersion: unknown domain schema '" + marker + "'");
        }
        List<String> errors = new ArrayList<>(domainSchema.validate(domain).stream()
                .map(ValidationMessage::getMessage)
                .map(message -> message.startsWith("$") ? "$.domain" + message.substring(1) : message)
                .toList());
        if ("submission-domain-v1".equals(marker)) {
            errors.addAll(duplicates(domain.path("conflicts"), "field", "$.domain.conflicts: duplicate field '%s'"));
            errors.addAll(duplicates(domain.path("keyDates"), "kind", "$.domain.keyDates: duplicate kind '%s'"));
        }
        return errors.stream().sorted().toList();
    }

    /**
     * As {@link #validateDomain(JsonNode)}, but when {@code requiredMarker} is non-null the domain
     * MUST be present and declare exactly that marker: an analyzer that declares a domain contract
     * has no pre-contract releases to protect, so a missing marker is a shape error, not a pass.
     */
    public List<String> validateDomain(JsonNode envelope, String requiredMarker) {
        if (requiredMarker == null) {
            return validateDomain(envelope);
        }
        JsonNode domain = envelope.path("domain");
        if (!domain.isObject() || !domain.has("schemaVersion")) {
            return List.of("$.domain.schemaVersion: required — this analyzer declares the " + requiredMarker + " contract");
        }
        String marker = domain.path("schemaVersion").asText("");
        if (!requiredMarker.equals(marker)) {
            return List.of("$.domain.schemaVersion: must be '" + requiredMarker + "' for this analyzer but was '" + marker + "'");
        }
        return validateDomain(envelope);
    }

    /** One message per member value that appears more than once across the array's rows. */
    private static List<String> duplicates(JsonNode rows, String member, String messageFormat) {
        if (!rows.isArray()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (JsonNode row : rows) {
            String value = row.path(member).asText(null);
            if (value == null) {
                continue;
            }
            if (!seen.add(value) && reported.add(value)) {
                out.add(messageFormat.formatted(value));
            }
        }
        return out;
    }
}
