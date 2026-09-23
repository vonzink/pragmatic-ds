package com.pragmaticds.rag.lab.release;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Encodes typed v2 releases and dispatches stored manifests without changing their version. */
public interface InstanceManifestCodec {

    EncodedManifest encode(InstanceReleaseManifest manifest);

    /**
     * Decodes an already-exact manifest map.
     *
     * <p><b>Not the map a JPA entity hands back.</b> Hibernate deserializes a JSONB attribute
     * through its format mapper, which returns a {@code BigDecimal} as a {@code Double} and a
     * {@code Long} as an {@code Integer}; {@code 0.250} cannot survive a {@code Double} at all, so
     * a manifest decoded from one would no longer hash to the digest stored beside it. This
     * overload therefore refuses a floating-point carrier rather than widening it, and the only
     * supported way to read a stored release is
     * {@code com.pragmaticds.rag.lab.instance.VerifiedInstanceReleaseReader}, which reads the JSONB
     * text itself and checks the digest before calling {@link #decode(byte[])}.
     */
    DecodedInstanceManifest decode(Map<String, Object> storedManifest);

    /**
     * Strict byte entrypoint for persisted JSON. Duplicate members, trailing tokens, and
     * non-finite literals are rejected before version dispatch, never normalized away.
     */
    DecodedInstanceManifest decode(byte[] storedManifestBytes);

    static InstanceManifestCodec strict(LabManifestWriter writer) {
        return new StrictInstanceManifestCodec(writer);
    }
}

final class StrictInstanceManifestCodec implements InstanceManifestCodec {

    private static final JsonFactory RAW_JSON_FACTORY = JsonFactory.builder().build();

    private final LabManifestWriter writer;

    StrictInstanceManifestCodec(LabManifestWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public EncodedManifest encode(InstanceReleaseManifest manifest) {
        Map<String, Object> json = toMap(Objects.requireNonNull(manifest, "manifest"));
        return new EncodedManifest(json, writer.sha256Hex(writer.canonicalize(json)));
    }

    @Override
    public DecodedInstanceManifest decode(byte[] storedManifestBytes) {
        Map<String, Object> stored = writer.readCanonical(storedManifestBytes);
        if (stored.get("manifestVersion") instanceof Number
                && !hasExactV2IntegerToken(storedManifestBytes)) {
            throw unsupportedVersion();
        }
        return decode(stored);
    }

    @Override
    public DecodedInstanceManifest decode(Map<String, Object> storedManifest) {
        Map<String, Object> root = requireObject(storedManifest);
        Object version = root.get("manifestVersion");
        if (version instanceof String v1 && LabReleaseManifest.MANIFEST_VERSION.equals(v1)) {
            // This is intentionally an adaptation, not an upgrade: old releases remain
            // read-only evidence of their prototype/live-dependency contract.
            return new DecodedInstanceManifest.V1Income(LabReleaseManifest.fromMap(root));
        }
        // A schema discriminator is an integer token, not a numeric value: accepting 2.0 or
        // 2e0 would make distinct stored bytes mean the same version. The strict byte path also
        // checks the original token spelling because a parser may normalize an exponent to 2.
        if (version instanceof Integer integer && integer == 2) {
            return new DecodedInstanceManifest.V2(fromV2Map(root));
        }
        throw unsupportedVersion();
    }

    private static Map<String, Object> toMap(InstanceReleaseManifest manifest) {
        InstanceReleaseManifest.ParsedDataContract parsed = manifest.parsedData();
        Map<String, Object> parsedData = new LinkedHashMap<>();
        parsedData.put("envelopeVersion", parsed.envelopeVersion());
        parsedData.put("canonicalizationVersion", parsed.canonicalizationVersion());
        parsedData.put("allowedDocumentTypes", sorted(parsed.allowedDocumentTypes()));
        parsedData.put("requireAnyDocumentTypes", sorted(parsed.requireAnyDocumentTypes()));
        parsedData.put("minimumSupportedDocuments", parsed.minimumSupportedDocuments());
        parsedData.put("reviewRequired", parsed.reviewRequired().name());
        parsedData.put("missingFields", parsed.missingFields().name());

        InstanceReleaseManifest.ModelContract model = manifest.model();
        Map<String, Object> modelJson = new LinkedHashMap<>();
        modelJson.put("provider", model.provider());
        modelJson.put("model", model.model());
        modelJson.put("fallbackPolicy", model.fallbackPolicy().name());

        List<Map<String, Object>> collections = new ArrayList<>();
        for (InstanceReleaseManifest.CollectionRef collection : manifest.corpus().collections()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("collectionId", collection.collectionId().toString());
            value.put("collectionVersion", collection.collectionVersion());
            collections.add(value);
        }

        InstanceReleaseManifest.BehaviorContract behavior = manifest.behavior();
        Map<String, Object> behaviorJson = new LinkedHashMap<>();
        behaviorJson.put("systemPrompt", behavior.systemPrompt());
        behaviorJson.put("taskPrompt", behavior.taskPrompt());
        behaviorJson.put("retrievalQuery", behavior.retrievalQuery());
        behaviorJson.put("temperature", behavior.temperature());

        List<Map<String, Object>> tools = new ArrayList<>();
        for (InstanceReleaseManifest.ToolContract tool : manifest.tools()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", tool.name());
            value.put("version", tool.version());
            value.put("inputSchemaSha256", tool.inputSchemaSha256());
            value.put("outputSchemaSha256", tool.outputSchemaSha256());
            tools.add(value);
        }

        InstanceReleaseManifest.OutputContract output = manifest.output();
        Map<String, Object> outputJson = new LinkedHashMap<>();
        outputJson.put("schemaId", output.schemaId());
        outputJson.put("schemaSha256", output.schemaSha256());

        InstanceReleaseManifest.LimitContract limits = manifest.limits();
        Map<String, Object> limitsJson = new LinkedHashMap<>();
        limitsJson.put("maximumInputTokens", limits.maximumInputTokens());
        limitsJson.put("maximumRetrievedTokens", limits.maximumRetrievedTokens());
        limitsJson.put("maximumOutputTokens", limits.maximumOutputTokens());
        limitsJson.put("maximumDiscussionTokens", limits.maximumDiscussionTokens());
        limitsJson.put("maximumConcurrentRuns", limits.maximumConcurrentRuns());
        limitsJson.put("maximumExpectedCostUsd", limits.maximumExpectedCostUsd());

        InstanceReleaseManifest.EvaluationContract evaluations = manifest.evaluations();
        Map<String, Object> evaluationsJson = new LinkedHashMap<>();
        evaluationsJson.put("scenarioSetId", evaluations.scenarioSetId());
        evaluationsJson.put("scenarioSetVersion", evaluations.scenarioSetVersion());
        evaluationsJson.put("minimumScore", evaluations.minimumScore());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("manifestVersion", manifest.manifestVersion());
        root.put("parsedData", parsedData);
        root.put("model", modelJson);
        root.put("corpus", Map.of("collections", collections));
        root.put("behavior", behaviorJson);
        root.put("tools", tools);
        root.put("output", outputJson);
        root.put("limits", limitsJson);
        root.put("evaluations", evaluationsJson);
        return root;
    }

    private static InstanceReleaseManifest fromV2Map(Map<String, Object> root) {
        exactKeys(root, "manifestVersion", "parsedData", "model", "corpus", "behavior", "tools",
                "output", "limits", "evaluations");
        Map<String, Object> parsed = object(root, "parsedData");
        exactKeys(parsed, "envelopeVersion", "canonicalizationVersion", "allowedDocumentTypes",
                "requireAnyDocumentTypes", "minimumSupportedDocuments", "reviewRequired", "missingFields");
        Map<String, Object> model = object(root, "model");
        exactKeys(model, "provider", "model", "fallbackPolicy");
        Map<String, Object> corpus = object(root, "corpus");
        exactKeys(corpus, "collections");
        Map<String, Object> behavior = object(root, "behavior");
        exactKeys(behavior, "systemPrompt", "taskPrompt", "retrievalQuery", "temperature");
        Map<String, Object> output = object(root, "output");
        exactKeys(output, "schemaId", "schemaSha256");
        Map<String, Object> limits = object(root, "limits");
        exactKeys(limits, "maximumInputTokens", "maximumRetrievedTokens", "maximumOutputTokens",
                "maximumDiscussionTokens", "maximumConcurrentRuns", "maximumExpectedCostUsd");
        Map<String, Object> evaluations = object(root, "evaluations");
        exactKeys(evaluations, "scenarioSetId", "scenarioSetVersion", "minimumScore");

        List<InstanceReleaseManifest.CollectionRef> collections = new ArrayList<>();
        for (Object raw : list(corpus, "collections")) {
            Map<String, Object> collection = requireObject(raw);
            exactKeys(collection, "collectionId", "collectionVersion");
            collections.add(new InstanceReleaseManifest.CollectionRef(
                    UUID.fromString(text(collection, "collectionId")), longNumber(collection, "collectionVersion")));
        }

        List<InstanceReleaseManifest.ToolContract> tools = new ArrayList<>();
        for (Object raw : list(root, "tools")) {
            Map<String, Object> tool = requireObject(raw);
            exactKeys(tool, "name", "version", "inputSchemaSha256", "outputSchemaSha256");
            tools.add(new InstanceReleaseManifest.ToolContract(text(tool, "name"), text(tool, "version"),
                    text(tool, "inputSchemaSha256"), text(tool, "outputSchemaSha256")));
        }

        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract(text(parsed, "envelopeVersion"),
                        text(parsed, "canonicalizationVersion"), stringSet(parsed, "allowedDocumentTypes"),
                        stringSet(parsed, "requireAnyDocumentTypes"), integer(parsed, "minimumSupportedDocuments"),
                        enumValue(InstanceReleaseManifest.ReviewPolicy.class, text(parsed, "reviewRequired")),
                        enumValue(InstanceReleaseManifest.MissingFieldPolicy.class, text(parsed, "missingFields"))),
                new InstanceReleaseManifest.ModelContract(text(model, "provider"), text(model, "model"),
                        enumValue(InstanceReleaseManifest.FallbackPolicy.class, text(model, "fallbackPolicy"))),
                new InstanceReleaseManifest.CorpusContract(collections),
                new InstanceReleaseManifest.BehaviorContract(text(behavior, "systemPrompt"),
                        text(behavior, "taskPrompt"), text(behavior, "retrievalQuery"), decimal(behavior, "temperature")),
                tools,
                new InstanceReleaseManifest.OutputContract(text(output, "schemaId"), text(output, "schemaSha256")),
                new InstanceReleaseManifest.LimitContract(longNumber(limits, "maximumInputTokens"),
                        integer(limits, "maximumRetrievedTokens"), integer(limits, "maximumOutputTokens"),
                        integer(limits, "maximumDiscussionTokens"), integer(limits, "maximumConcurrentRuns"),
                        decimal(limits, "maximumExpectedCostUsd")),
                new InstanceReleaseManifest.EvaluationContract(text(evaluations, "scenarioSetId"),
                        integer(evaluations, "scenarioSetVersion"), decimal(evaluations, "minimumScore")));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw malformed();
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw malformed();
            }
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> object(Map<String, Object> parent, String key) {
        return requireObject(parent.get(key));
    }

    private static List<?> list(Map<String, Object> parent, String key) {
        if (!(parent.get(key) instanceof List<?> values)) {
            throw malformed();
        }
        return values;
    }

    private static Set<String> stringSet(Map<String, Object> parent, String key) {
        List<?> values = list(parent, key);
        List<String> strings = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String string)) {
                throw malformed();
            }
            strings.add(string);
        }
        if (strings.size() != Set.copyOf(strings).size()) {
            throw malformed();
        }
        return Set.copyOf(strings);
    }

    private static String text(Map<String, Object> parent, String key) {
        if (!(parent.get(key) instanceof String value)) {
            throw malformed();
        }
        return value;
    }

    private static int integer(Map<String, Object> parent, String key) {
        try {
            return decimal(parent, key).intValueExact();
        } catch (ArithmeticException invalid) {
            throw malformed();
        }
    }

    private static long longNumber(Map<String, Object> parent, String key) {
        try {
            return decimal(parent, key).longValueExact();
        } catch (ArithmeticException invalid) {
            throw malformed();
        }
    }

    private static BigDecimal decimal(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        // A Float/Double carrier cannot retain 0.250 versus 0.25 (or 1.50 versus 1.5). Reject
        // it rather than making a re-encoded release hash a different contract.
        throw malformed();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> enumType, String value) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException invalid) {
            throw malformed();
        }
    }

    private static void exactKeys(Map<String, Object> value, String... keys) {
        if (value.size() != keys.length) {
            throw malformed();
        }
        for (String key : keys) {
            if (!value.containsKey(key)) {
                throw malformed();
            }
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid v2 manifest");
    }

    private static IllegalArgumentException unsupportedVersion() {
        return new IllegalArgumentException("unsupported manifestVersion");
    }

    private static boolean hasExactV2IntegerToken(byte[] bytes) {
        try (JsonParser parser = RAW_JSON_FACTORY.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    return false;
                }
                String member = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("manifestVersion".equals(member)) {
                    return value == JsonToken.VALUE_NUMBER_INT && "2".equals(parser.getText());
                }
                parser.skipChildren();
            }
            return false;
        } catch (IOException invalidJson) {
            // LabManifestWriter already accepted this byte sequence as strict JSON, so this is
            // defensive only and must not lead to a permissive version dispatch.
            throw unsupportedVersion();
        }
    }
}

final class InstanceManifestValues {

    private InstanceManifestValues() {}

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copied.put(entry.getKey(), copy(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }

    @SuppressWarnings("unchecked")
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("manifest member names must be strings");
                }
                copied.put(key, copy(entry.getValue()));
            }
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object item : list) {
                copied.add(copy(item));
            }
            return Collections.unmodifiableList(copied);
        }
        return value;
    }
}
