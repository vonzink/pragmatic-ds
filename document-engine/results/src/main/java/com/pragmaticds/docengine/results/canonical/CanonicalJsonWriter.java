package com.pragmaticds.docengine.results.canonical;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes the exact UTF-8 byte identity defined by DOCENGINE-C14N-1. */
public final class CanonicalJsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ObjectMapper STRICT_MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static {
        STRICT_MAPPER.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * Parses one strict JSON value and canonicalizes it. Duplicate object keys and trailing values
     * fail before the tree model can discard their distinction.
     */
    public CanonicalArtifact parseAndWrite(byte[] json) {
        Objects.requireNonNull(json, "json");
        try {
            return write(STRICT_MAPPER.readTree(json));
        } catch (IOException exception) {
            throw new CanonicalizationException("Input is not strict JSON", exception);
        }
    }

    /** Canonicalizes one JSON tree without applying Unicode normalization. */
    public CanonicalArtifact write(JsonNode node) {
        if (node == null) {
            throw new CanonicalizationException("JSON root must be present");
        }

        StringBuilder output = new StringBuilder();
        appendNode(node, output);
        byte[] bytes = output.toString().getBytes(UTF_8);
        return new CanonicalArtifact(bytes, sha256(bytes));
    }

    private static void appendNode(JsonNode node, StringBuilder output) {
        switch (node.getNodeType()) {
            case OBJECT -> appendObject(node, output);
            case ARRAY -> appendArray(node, output);
            case STRING -> appendString(node.textValue(), output);
            case NUMBER -> appendNumber(node, output);
            case BOOLEAN -> output.append(node.booleanValue());
            case NULL -> output.append("null");
            default ->
                    throw new CanonicalizationException(
                            "Unsupported JSON node type: " + node.getNodeType());
        }
    }

    private static void appendObject(JsonNode node, StringBuilder output) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        node.properties()
                .forEach(
                        field -> {
                            validateUnicode(field.getKey());
                            fields.add(field);
                        });
        fields.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));

        output.append('{');
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            Map.Entry<String, JsonNode> field = fields.get(index);
            appendString(field.getKey(), output);
            output.append(':');
            appendNode(field.getValue(), output);
        }
        output.append('}');
    }

    private static void appendArray(JsonNode node, StringBuilder output) {
        output.append('[');
        for (int index = 0; index < node.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            appendNode(node.get(index), output);
        }
        output.append(']');
    }

    private static void appendNumber(JsonNode node, StringBuilder output) {
        if (node.isIntegralNumber()) {
            output.append(node.bigIntegerValue().toString());
            return;
        }
        if (!node.isFloatingPointNumber()) {
            throw new CanonicalizationException("Unsupported numeric node");
        }

        JsonParser.NumberType numberType = node.numberType();
        if (numberType == JsonParser.NumberType.DOUBLE && !Double.isFinite(node.doubleValue())) {
            throw new CanonicalizationException("Non-finite numbers are not JSON");
        }
        if (numberType == JsonParser.NumberType.FLOAT && !Float.isFinite(node.floatValue())) {
            throw new CanonicalizationException("Non-finite numbers are not JSON");
        }

        BigDecimal decimal = node.decimalValue();
        if (decimal.signum() == 0) {
            output.append('0');
        } else {
            output.append(decimal.stripTrailingZeros().toPlainString());
        }
    }

    private static void appendString(String value, StringBuilder output) {
        validateUnicode(value);
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\t' -> output.append("\\t");
                case '\n' -> output.append("\\n");
                case '\f' -> output.append("\\f");
                case '\r' -> output.append("\\r");
                default -> {
                    if (current < 0x20) {
                        output.append("\\u00");
                        output.append(HEX[(current >>> 4) & 0xf]);
                        output.append(HEX[current & 0xf]);
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new CanonicalizationException("String contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new CanonicalizationException("String contains an unpaired surrogate");
            }
        }
    }

    private static final Comparator<String> CODE_POINT_ORDER =
            (left, right) -> {
                int leftIndex = 0;
                int rightIndex = 0;
                while (leftIndex < left.length() && rightIndex < right.length()) {
                    int leftCodePoint = left.codePointAt(leftIndex);
                    int rightCodePoint = right.codePointAt(rightIndex);
                    int comparison = Integer.compare(leftCodePoint, rightCodePoint);
                    if (comparison != 0) {
                        return comparison;
                    }
                    leftIndex += Character.charCount(leftCodePoint);
                    rightIndex += Character.charCount(rightCodePoint);
                }
                return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
            };

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Exact canonical bytes and the lowercase hexadecimal SHA-256 over those bytes. */
    public record CanonicalArtifact(byte[] bytes, String sha256) {
        public CanonicalArtifact {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            Objects.requireNonNull(sha256, "sha256");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
