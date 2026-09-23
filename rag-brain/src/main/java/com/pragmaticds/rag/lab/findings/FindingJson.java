package com.pragmaticds.rag.lab.findings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The only place a finding becomes JSON, and the only place it comes back.
 *
 * <p>One conversion site rather than one per caller, because the wire shape is pinned by
 * {@code findings-v1.output.schema.json} and a second serializer is how a shape drifts from its
 * schema without the digest noticing.
 *
 * <p>{@link #toOutput} is the tool stage: it is schema-governed and rejects a {@code subjectKey}
 * outright, because a tool holds no run context and one carrying a subject key is a bug to catch,
 * not a shape to permit. {@link #toArray} is the publication stage, called again after a subject
 * key has been stamped onto a finding, and deliberately allows what {@code toOutput} refuses.
 * {@link #publishInto} is also publication stage: it takes the array {@link #toArray} builds and
 * grafts it onto an already-validated analyzer envelope, after the model has answered and the
 * pinned output schema has already done its job.
 *
 * <p>Field order is fixed by construction order, so the same findings always serialize to the
 * same bytes — which is what the golden-file tests compare.
 */
public final class FindingJson {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FindingJson() {}

    /**
     * The tool output shape: {@code {"findings": [...]}}, matching
     * {@code findings-v1.output.schema.json}.
     *
     * @throws IllegalArgumentException if any finding carries a non-null {@code subjectKey} — a
     *     tool holds no run context, so a stamped subject key at this stage means a tool assigned
     *     one itself rather than leaving it for the assembler.
     */
    public static ObjectNode toOutput(List<Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        for (Finding finding : findings) {
            if (finding.subjectKey() != null) {
                throw new IllegalArgumentException("TOOL_OUTPUT_CARRIES_SUBJECT_KEY");
            }
        }
        ObjectNode output = NF.objectNode();
        output.set("findings", toArray(findings));
        return output;
    }

    /**
     * Just the array. Used both by {@link #toOutput} for tool output, and again after assembly to
     * publish stamped findings into the analyzer envelope — the latter is where a non-null
     * {@code subjectKey} is expected and emitted.
     */
    public static ArrayNode toArray(List<Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        ArrayNode array = NF.arrayNode(findings.size());
        for (Finding finding : findings) {
            array.add(toNode(finding));
        }
        return array;
    }

    /**
     * Returns the envelope JSON with a top-level {@code findings} array added.
     *
     * <p>Called after envelope validation, never before: the model does not emit findings, the
     * pinned output schema does not describe them, and that schema's digest is part of every
     * promoted release. Adding the property here is what lets findings ship without invalidating
     * a single release.
     *
     * <p>An envelope that will not parse is returned unchanged. Such a run has already failed for
     * a clearer reason, and turning that into a findings failure would bury it.
     */
    public static String publishInto(String envelopeJson, List<Finding> findings) {
        if (envelopeJson == null) {
            return null;
        }
        try {
            JsonNode parsed = MAPPER.readTree(envelopeJson);
            if (!parsed.isObject()) {
                return envelopeJson;
            }
            ObjectNode envelope = (ObjectNode) parsed;
            envelope.set("findings", toArray(findings));
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException unparseable) {
            // Deliberately not chained and not logged: the text may carry borrower values.
            return envelopeJson;
        }
    }

    /** Reads back a tool's output. Absent or non-array findings is an empty list, not a failure. */
    public static List<Finding> fromOutput(JsonNode output) {
        if (output == null || !output.path("findings").isArray()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode node : output.get("findings")) {
            findings.add(fromNode(node));
        }
        return List.copyOf(findings);
    }

    private static ObjectNode toNode(Finding finding) {
        ObjectNode node = NF.objectNode();
        node.put("ruleId", finding.ruleId());
        node.put("ruleVersion", finding.ruleVersion());
        node.put("severity", finding.severity().name());
        node.put("summary", finding.summary());
        if (finding.subjectKey() != null) {
            node.put("subjectKey", finding.subjectKey());
        }
        node.put("inputDigest", finding.inputDigest());

        ArrayNode anchors = node.putArray("anchors");
        for (FindingAnchor anchor : finding.anchors()) {
            ObjectNode a = anchors.addObject();
            a.put("logicalDocumentId", anchor.logicalDocumentId().toString());
            a.put("documentTypeCode", anchor.documentTypeCode());
            a.put("documentOrdinal", anchor.documentOrdinal());
            a.put("fieldName", anchor.fieldName());
            if (anchor.groupKey() == null) {
                a.putNull("groupKey");
            } else {
                a.put("groupKey", anchor.groupKey());
            }
            if (anchor.pageId() == null) {
                a.putNull("pageId");
                a.putNull("box");
            } else {
                a.put("pageId", anchor.pageId().toString());
                ObjectNode box = a.putObject("box");
                box.put("x", anchor.box().x());
                box.put("y", anchor.box().y());
                box.put("width", anchor.box().width());
                box.put("height", anchor.box().height());
            }
        }

        ArrayNode citationIds = node.putArray("citationIds");
        for (String id : finding.citationIds()) {
            citationIds.add(id);
        }
        return node;
    }

    private static Finding fromNode(JsonNode node) {
        List<FindingAnchor> anchors = new ArrayList<>();
        for (JsonNode a : node.path("anchors")) {
            JsonNode box = a.path("box");
            anchors.add(new FindingAnchor(
                    UUID.fromString(a.path("logicalDocumentId").asText()),
                    a.path("documentTypeCode").asText(),
                    a.path("documentOrdinal").asInt(),
                    a.path("fieldName").asText(),
                    a.path("groupKey").isNull() ? null : a.path("groupKey").asText(null),
                    a.path("pageId").isNull() || a.path("pageId").isMissingNode()
                            ? null : UUID.fromString(a.path("pageId").asText()),
                    box.isObject()
                            ? new Box(box.path("x").decimalValue(), box.path("y").decimalValue(),
                                    box.path("width").decimalValue(),
                                    box.path("height").decimalValue())
                            : null));
        }
        List<String> citationIds = new ArrayList<>();
        for (JsonNode id : node.path("citationIds")) {
            citationIds.add(id.asText());
        }
        return new Finding(
                node.path("ruleId").asText(),
                node.path("ruleVersion").asText(),
                Finding.Severity.valueOf(node.path("severity").asText()),
                node.path("summary").asText(),
                node.path("subjectKey").isMissingNode() ? null : node.path("subjectKey").asText(),
                node.path("inputDigest").asText(),
                anchors,
                citationIds);
    }
}
