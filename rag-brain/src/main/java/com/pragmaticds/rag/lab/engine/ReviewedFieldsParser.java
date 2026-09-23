package com.pragmaticds.rag.lab.engine;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.NormalizedValue;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReviewState;
import com.pragmaticds.rag.lab.engine.LabContractException.Code;
import com.pragmaticds.rag.lab.engine.ReviewedFields.GroupKind;
import com.pragmaticds.rag.lab.engine.ReviewedFields.ReviewedField;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict parser for the engine's {@code DocumentFieldsView} (engine main @ 9a300e3). Exact member
 * sets at every level and a closed vocabulary for every enumerated member, so a shape the engine
 * changes is refused here rather than read under a stale contract. Members rag-brain does not
 * consume are validated for presence and dropped.
 */
public final class ReviewedFieldsParser {

    private static final ObjectMapper STRICT =
            new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Set<String> VIEW_MEMBERS =
            Set.of("documentId", "documentTypeCode", "schemaVersion", "fields");
    private static final Set<String> FIELD_MEMBERS =
            Set.of("id", "fieldName", "groupKey", "groupKind", "textProvenance", "dataType",
                    "displayedText", "rawValue", "normalized", "extractionMethod",
                    "extractorVersion", "confidence", "confidenceComponents", "validationStatus",
                    "reviewStatus", "effectiveStatus", "sensitive", "evidence");
    private static final Set<String> NORMALIZED_MEMBERS = Set.of("text", "number", "date");
    private static final Set<String> PROVENANCE_MEMBERS = Set.of("source", "ocrEngine");
    private static final Set<String> COMPONENT_MEMBERS =
            Set.of("spanConfidence", "anchorStrength", "normalizerCertainty");
    private static final Set<String> EVIDENCE_MEMBERS =
            Set.of("role", "ordinal", "pageId", "packagePageIndex", "x", "y", "width", "height",
                    "textSpanId", "layoutElementId");

    public ReviewedFields parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new LabContractException(Code.ENVELOPE_EMPTY);
        }
        JsonNode root;
        try {
            root = STRICT.readTree(body);
        } catch (IOException malformed) {
            throw new LabContractException(Code.ENVELOPE_NOT_STRICT_JSON);
        }
        if (root == null || !root.isObject()) {
            throw new LabContractException(Code.ENVELOPE_ROOT_NOT_OBJECT);
        }
        requireExactMembers(root, VIEW_MEMBERS);
        List<ReviewedField> fields = new ArrayList<>();
        for (JsonNode item : requireArray(member(root, "fields"))) {
            fields.add(field(item));
        }
        return new ReviewedFields(
                uuid(member(root, "documentId")),
                requireNonEmptyString(member(root, "documentTypeCode")),
                requireNonEmptyString(member(root, "schemaVersion")),
                fields,
                sha256(body),
                body.length);
    }

    private static ReviewedField field(JsonNode node) {
        requireExactMembers(requireObject(node), FIELD_MEMBERS);
        uuid(member(node, "id"));
        JsonNode provenance = member(node, "textProvenance");
        requireExactMembers(requireObject(provenance), PROVENANCE_MEMBERS);
        requireNonEmptyString(member(provenance, "source"));
        nullableString(member(provenance, "ocrEngine"));
        JsonNode components = member(node, "confidenceComponents");
        if (!components.isNull()) {
            requireExactMembers(requireObject(components), COMPONENT_MEMBERS);
            requireUnitInterval(member(components, "spanConfidence"));
            requireUnitInterval(member(components, "anchorStrength"));
            requireUnitInterval(member(components, "normalizerCertainty"));
        }
        requireNonEmptyString(member(node, "extractorVersion"));
        requireNonEmptyString(member(node, "validationStatus"));
        requireNonEmptyString(member(node, "reviewStatus"));
        BigDecimal confidence = requireUnitInterval(member(node, "confidence"));
        JsonNode sensitive = member(node, "sensitive");
        if (!sensitive.isBoolean()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        JsonNode groupKey = member(node, "groupKey");
        List<UUID> pageIds = new ArrayList<>();
        for (JsonNode span : requireArray(member(node, "evidence"))) {
            requireExactMembers(requireObject(span), EVIDENCE_MEMBERS);
            requireNonEmptyString(member(span, "role"));
            if (requireInt(member(span, "ordinal")) < 0
                    || requireInt(member(span, "packagePageIndex")) < 0) {
                throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
            }
            for (String box : List.of("x", "y", "width", "height")) {
                requireDecimal(member(span, box));
            }
            JsonNode textSpanId = member(span, "textSpanId");
            if (!textSpanId.isNull()) {
                long value = requireIntegralNumber(textSpanId);
                if (value < 1) {
                    throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
                }
            }
            JsonNode layoutElementId = member(span, "layoutElementId");
            if (!layoutElementId.isNull()) {
                uuid(layoutElementId);
            }
            pageIds.add(uuid(member(span, "pageId")));
        }
        return new ReviewedField(
                requireNonEmptyString(member(node, "fieldName")),
                groupKey.isNull() ? null : requireNonEmptyString(groupKey),
                enumValue(member(node, "groupKind"), GroupKind.class),
                effectiveStatus(member(node, "effectiveStatus")),
                requireNonEmptyString(member(node, "dataType")),
                nullableString(member(node, "displayedText")),
                nullableString(member(node, "rawValue")),
                normalized(member(node, "normalized")),
                requireNonEmptyString(member(node, "extractionMethod")),
                sensitive.booleanValue(),
                pageIds);
    }

    private static ReviewState effectiveStatus(JsonNode node) {
        String value = requireNonEmptyString(node);
        return switch (value) {
            case "MACHINE" -> ReviewState.MACHINE;
            case "CORRECTED" -> ReviewState.CORRECTED;
            case "REJECTED" -> ReviewState.REJECTED;
            default -> throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        };
    }

    private static NormalizedValue normalized(JsonNode node) {
        requireExactMembers(requireObject(node), NORMALIZED_MEMBERS);
        JsonNode number = member(node, "number");
        JsonNode date = member(node, "date");
        LocalDate parsedDate = null;
        if (!date.isNull()) {
            try {
                parsedDate = LocalDate.parse(requireNonEmptyString(date));
            } catch (DateTimeParseException malformed) {
                throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
            }
        }
        return new NormalizedValue(
                nullableString(member(node, "text")),
                number.isNull() ? null : requireDecimal(number),
                parsedDate,
                null);
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type) {
        String value = requireNonEmptyString(node);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException outside) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
    }

    private static void requireExactMembers(JsonNode object, Set<String> exact) {
        Iterator<String> names = object.fieldNames();
        int present = 0;
        while (names.hasNext()) {
            if (!exact.contains(names.next())) {
                throw new LabContractException(Code.READMODEL_MEMBER_UNKNOWN);
            }
            present++;
        }
        if (present != exact.size()) {
            throw new LabContractException(Code.READMODEL_MEMBER_MISSING);
        }
    }

    private static JsonNode member(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            throw new LabContractException(Code.READMODEL_MEMBER_MISSING);
        }
        return value;
    }

    private static JsonNode requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return node;
    }

    private static JsonNode requireArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return node;
    }

    private static String requireNonEmptyString(JsonNode node) {
        if (!node.isTextual() || node.textValue().isEmpty()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return node.textValue();
    }

    private static String nullableString(JsonNode node) {
        return node.isNull() ? null : requireNonEmptyString(node);
    }

    private static BigDecimal requireDecimal(JsonNode node) {
        if (!node.isNumber()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return node.decimalValue();
    }

    private static int requireInt(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return node.intValue();
    }

    private static BigDecimal requireUnitInterval(JsonNode node) {
        BigDecimal value = requireDecimal(node);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return value;
    }

    private static long requireIntegralNumber(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return node.longValue();
    }

    private static UUID uuid(JsonNode node) {
        String value = requireNonEmptyString(node);
        if (!UUID_SHAPE.matcher(value).matches()) {
            throw new LabContractException(Code.READMODEL_VALUE_MALFORMED);
        }
        return UUID.fromString(value);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
