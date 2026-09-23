package com.pragmaticds.rag.lab.release;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical serialization and digesting for Lab release manifests — {@code LAB-MANIFEST-C14N-1}.
 *
 * <p>The digest is the release's <em>identity</em>. {@code lab_instance_release} keys idempotent
 * bootstrap on {@code UNIQUE (brain_id, instance_slug, manifest_sha256)}, and drift detection is
 * nothing more than "the live manifest hashes differently from the pointed one". Both collapse
 * unless the bytes are a pure function of manifest content, so this writer refuses every input
 * that would make them anything else rather than serializing it and hoping.
 *
 * <p>The form, stated so it can be depended on rather than inferred:
 *
 * <ul>
 *   <li>UTF-8, no insignificant whitespace, root must be a JSON object.
 *   <li>Object members ascend by the <em>UTF-8 byte sequence</em> of the member name. Byte order
 *       rather than {@link String#compareTo} so the ordering is one rule for all of Unicode, and
 *       never a locale collation.
 *   <li>Arrays keep their given order: sequence is manifest content, not presentation.
 *   <li>Numbers are written in plain decimal, never an exponent. A {@link BigDecimal} keeps its
 *       exact scale ({@code 0.50} stays {@code 0.50}), because re-reading and re-hashing a stored
 *       manifest has to reproduce the same bytes.
 *   <li>Non-finite numbers, non-string member names, and value types outside the manifest
 *       vocabulary are rejected.
 * </ul>
 *
 * <p>Deliberately not Jackson's {@code ObjectMapper#writeValueAsBytes}: default serialization
 * gives no guarantee about member order, and would print {@code 1E+3} for a {@link BigDecimal}
 * that a strict re-read then hashes differently.
 *
 * <p>Failures are payload-free — a {@link ManifestException} carries a code and nothing else, so
 * a malformed manifest can never echo its own content into a response, a log, or an audit row.
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "${ragbrain.lab.enabled:false} or ${ragbrain.instances.enabled:false}")
public final class LabManifestWriter {

    /** The canonicalization contract these bytes obey; recorded inside every manifest. */
    public static final String CANONICALIZATION_VERSION = "LAB-MANIFEST-C14N-1";

    /**
     * Member names ordered by unsigned UTF-8 byte sequence. {@link String#compareTo} compares
     * UTF-16 code units, which disagrees with byte order for anything above the BMP — one rule
     * for all of Unicode is worth the encode.
     */
    private static final Comparator<String> UTF8_BYTE_ORDER = (left, right) -> {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int shared = Math.min(a.length, b.length);
        for (int i = 0; i < shared; i++) {
            int difference = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (difference != 0) {
                return difference;
            }
        }
        return a.length - b.length;
    };

    private static final JsonFactory STRICT_FACTORY = JsonFactory.builder().build();

    /** Canonical UTF-8 bytes for one manifest. */
    public byte[] canonicalize(Map<String, Object> manifest) {
        if (manifest == null) {
            throw new ManifestException(ManifestException.Code.MANIFEST_ROOT_NOT_OBJECT);
        }
        StringBuilder out = new StringBuilder(1024);
        writeObject(manifest, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Lowercase hex SHA-256 of already-canonical bytes. */
    public String sha256Hex(byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    /**
     * Strictly re-reads canonical manifest bytes — the direction that matters when a stored
     * release is verified against its own recorded digest.
     *
     * <p>Duplicate members are rejected rather than last-one-winning: a duplicate is precisely
     * the shape where the bytes and the parsed map disagree, so a stored manifest could pass a
     * digest check while meaning something else. Non-finite literals and trailing tokens are
     * refused for the same reason.
     */
    public Map<String, Object> readCanonical(byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
        try (JsonParser parser = STRICT_FACTORY.createParser(canonicalBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new ManifestException(ManifestException.Code.MANIFEST_ROOT_NOT_OBJECT);
            }
            Map<String, Object> root = readObject(parser);
            if (parser.nextToken() != null) {
                throw new ManifestException(ManifestException.Code.MANIFEST_NOT_STRICT_JSON);
            }
            return root;
        } catch (ManifestException failure) {
            throw failure;
        } catch (IOException notStrictJson) {
            // Includes NaN/Infinity literals: Jackson rejects them unless explicitly allowed.
            throw new ManifestException(ManifestException.Code.MANIFEST_NOT_STRICT_JSON);
        }
    }

    // ------------------------------------------------------------------ writing

    private void writeObject(Map<String, Object> value, StringBuilder out) {
        List<String> names = new ArrayList<>(value.size());
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new ManifestException(
                        ManifestException.Code.MANIFEST_MEMBER_NAME_NOT_STRING);
            }
            names.add(name);
        }
        names.sort(UTF8_BYTE_ORDER);

        out.append('{');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(names.get(i), out);
            out.append(':');
            writeValue(value.get(names.get(i)), out);
        }
        out.append('}');
    }

    @SuppressWarnings("unchecked")
    private void writeValue(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String text -> writeString(text, out);
            case Boolean flag -> out.append(flag.booleanValue() ? "true" : "false");
            case Map<?, ?> nested -> writeObject((Map<String, Object>) nested, out);
            case List<?> items -> {
                out.append('[');
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeValue(items.get(i), out);
                }
                out.append(']');
            }
            case BigDecimal decimal -> out.append(decimal.toPlainString());
            case BigInteger integer -> out.append(integer.toString());
            case Byte b -> out.append(b.toString());
            case Short s -> out.append(s.toString());
            case Integer i -> out.append(i.toString());
            case Long l -> out.append(l.toString());
            case Double d -> out.append(finiteDecimal(d.doubleValue()));
            case Float f -> out.append(finiteDecimal(f.doubleValue()));
            default -> throw new ManifestException(
                    ManifestException.Code.MANIFEST_VALUE_UNSUPPORTED);
        }
    }

    private static String finiteDecimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ManifestException(ManifestException.Code.MANIFEST_NUMBER_NOT_FINITE);
        }
        // Via the shortest round-trip text, never the binary expansion: new BigDecimal(0.1d)
        // would write fifty digits of floating-point noise into a release identity.
        return new BigDecimal(Double.toString(value)).toPlainString();
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        out.append(JsonStringEncoder.getInstance().quoteAsString(value));
        out.append('"');
    }

    // ------------------------------------------------------------------ reading

    private Map<String, Object> readObject(JsonParser parser) throws IOException {
        Map<String, Object> members = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.currentName();
            if (!seen.add(name)) {
                throw new ManifestException(ManifestException.Code.MANIFEST_DUPLICATE_MEMBER);
            }
            parser.nextToken();
            members.put(name, readValue(parser));
        }
        return members;
    }

    private Object readValue(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> {
                List<Object> items = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    items.add(readValue(parser));
                }
                yield List.copyOf(wrapNullable(items));
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getNumberValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new ManifestException(
                    ManifestException.Code.MANIFEST_NOT_STRICT_JSON);
        };
    }

    /** {@code List.copyOf} rejects nulls; a canonical manifest array may legitimately hold one. */
    private static List<Object> wrapNullable(List<Object> items) {
        for (Object item : items) {
            if (item == null) {
                return java.util.Collections.unmodifiableList(items);
            }
        }
        return items;
    }

    /** A payload-free canonicalization failure: a stable code and nothing else. */
    public static final class ManifestException extends RuntimeException {

        /** Stable, value-free manifest failure taxonomy. */
        public enum Code {
            /** The manifest is absent, or its serialized root is not a JSON object. */
            MANIFEST_ROOT_NOT_OBJECT,
            /** Not one strict JSON value: syntax error, trailing token, non-finite literal. */
            MANIFEST_NOT_STRICT_JSON,
            /** One object states the same member twice, so bytes and meaning can disagree. */
            MANIFEST_DUPLICATE_MEMBER,
            /** A NaN or infinite number cannot be canonically written. */
            MANIFEST_NUMBER_NOT_FINITE,
            /** A member name is not a string. */
            MANIFEST_MEMBER_NAME_NOT_STRING,
            /** A value type outside the manifest vocabulary. */
            MANIFEST_VALUE_UNSUPPORTED
        }

        private final Code code;

        public ManifestException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}
