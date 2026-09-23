package com.pragmaticds.docengine.review.triage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The jsonb envelope of a triage LABEL — a {@code CLASSIFICATION} / {@code RECLASSIFY}
 * {@code review_decision} whose subject is a logical document but whose scope is a page RUN inside
 * it.
 *
 * <p>Not a {@link com.pragmaticds.docengine.review.ReviewJson} single-key envelope, because a label is not
 * a single value: the run it applies to is part of the decision, and a decision that cannot say
 * WHICH pages a reviewer labelled is not an audit record. It is the same shape choice, for the same
 * reason, as the regroup grouping snapshot, which stores a whole object through
 * {@code ReviewJson.raw} — ids, type codes and confidences only, never page content, so it is safe
 * to store verbatim in the audit trail.
 *
 * <p>The history strip renders it with no special case, because {@code ReviewJson.soleValue} reads
 * the value by the NAME {@code documentTypeCode} rather than by position — key order is not
 * preserved by {@code jsonb}, which stores an object's keys ordered by length, so a multi-key
 * envelope has no "first" key its author controls. A label therefore reads as "UNKNOWN → W2" in
 * the strip, exactly like a document-level reclassification.
 */
public final class TriageLabelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TriageLabelJson() {}

    /**
     * What the MACHINE said about this run, preserved before the human speaks.
     *
     * @param pageTypeCode the run's own current classification — {@code UNKNOWN} for real triage
     *     work, {@code MIXED} when a caller labels a run whose pages disagree
     * @param logicalDocumentTypeCode the type of the document that ABSORBED the run, which for an
     *     absorbed run is the more interesting half: it records what the pages were being read as
     */
    public static String previousValue(
            String pageTypeCode, String logicalDocumentTypeCode, List<UUID> pageIds) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("documentTypeCode", pageTypeCode);
        value.put("logicalDocumentTypeCode", logicalDocumentTypeCode);
        value.put("pageIds", pageIds.stream().map(UUID::toString).toList());
        return write(value);
    }

    /** What the HUMAN said, and for exactly which pages. */
    public static String newValue(String documentTypeCode, List<UUID> pageIds) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("documentTypeCode", documentTypeCode);
        value.put("pageIds", pageIds.stream().map(UUID::toString).toList());
        return write(value);
    }

    /**
     * The page ids a stored label applies to. Unparseable or absent yields an empty list, which
     * matches NO triage item — a corrupt label silently claims nothing rather than attaching itself
     * to the wrong run.
     */
    public static List<UUID> pageIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<UUID> ids = new ArrayList<>();
            for (JsonNode id : MAPPER.readTree(json).path("pageIds")) {
                try {
                    ids.add(UUID.fromString(id.asText()));
                } catch (IllegalArgumentException e) {
                    return List.of();
                }
            }
            return List.copyOf(ids);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String write(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Ids and type codes only — never page content — so a failure here is a bug, not a
            // leak, and it must not be swallowed into a half-written audit row.
            throw new IllegalStateException("triage label envelope is not serialisable");
        }
    }
}
