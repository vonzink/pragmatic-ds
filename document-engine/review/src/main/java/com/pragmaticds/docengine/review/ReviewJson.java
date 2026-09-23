package com.pragmaticds.docengine.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * The tiny jsonb envelope for {@code review_decision.previous_value} / {@code new_value}: a
 * single-key object, e.g. {@code {"value":"3,600.00"}} for a field correction or
 * {@code {"documentTypeCode":"W2"}} for a reclassification. Keeping the shape in one place is what
 * lets the overlay, the history endpoint, and the correction service agree on where the value is.
 */
public final class ReviewJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReviewJson() {}

    /**
     * Pass-through for an ALREADY-serialised JSON payload — the regroup grouping snapshot
     * (docs/types/confidence/ordered pageIds), which is a whole object, not a single-key envelope.
     * The value is stored verbatim in the {@code review_decision} jsonb column; it carries only ids,
     * type codes, and confidences, never page content, so no masking applies.
     */
    public static String raw(String json) {
        return json;
    }

    /** {@code {"<key>":"<value>"}}, or null when the value is null (a null jsonb column). */
    public static String object(String key, String value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(java.util.Map.of(key, value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserialisable review value");
        }
    }

    /**
     * The CORRECT envelope: {@code {"value":"…"}} plus {@code "pageIndex":n} when the reviewer said
     * which page the value is printed on. The page is what turns a correction into a gold-set truth
     * entry ({@code expectedFields[].pageIndex}); a correction without it is still valid — the
     * exporter falls back to the field's evidence page.
     */
    public static String correction(String value, Integer pageIndex) {
        java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("value", value);
        if (pageIndex != null) {
            envelope.put("pageIndex", pageIndex);
        }
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserialisable review value");
        }
    }

    /** The integer under {@code key}, or null if absent/null/not a number/unparseable. */
    public static Integer readInt(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode value = MAPPER.readTree(json).get(key);
            return value == null || !value.isNumber() ? null : value.asInt();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** The value under {@code key} in a single-key envelope, or null if absent/blank/unparseable. */
    public static String read(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode value = node.get(key);
            return value == null || value.isNull() ? null : value.asText();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * The value the history strip should render for a decision — {@code value},
     * {@code documentTypeCode} or {@code reviewStatus} — so field corrections, reclassifications,
     * mark-reviewed sign-offs and triage labels all render "original -> corrected" uniformly.
     *
     * <p><b>Named keys, not "the first key".</b> The column is {@code jsonb}, and Postgres does not
     * store an object's keys in insertion order — it orders them by length, then bytewise. So "the
     * first key of the envelope" is a property of the KEY NAMES, not of the code that wrote them,
     * and any envelope carrying more than one key (a triage label carries its page run beside the
     * type; a regroup carries a whole grouping snapshot) would render whichever key happened to be
     * shortest. Reading the value by NAME is the only stable rule.
     *
     * <p>A single-key envelope still resolves by that one key whatever it is called, which keeps
     * every pre-existing decision rendering exactly as before. Anything else — a snapshot object
     * with no renderable scalar, an array, a missing key — is null rather than a guess.
     */
    public static String soleValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                return null;
            }
            for (String key : List.of("value", "documentTypeCode", "reviewStatus")) {
                JsonNode named = node.get(key);
                if (named != null && named.isValueNode() && !named.isNull()) {
                    return named.asText();
                }
            }
            if (node.size() == 1) {
                JsonNode only = node.properties().iterator().next().getValue();
                return only == null || !only.isValueNode() || only.isNull() ? null : only.asText();
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
