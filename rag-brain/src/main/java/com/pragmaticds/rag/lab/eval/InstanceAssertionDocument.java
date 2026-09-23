package com.pragmaticds.rag.lab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;

/**
 * The document a scenario's JSON pointers address, and the only thing that decides what is
 * addressable.
 *
 * <p><b>Why this type exists.</b> A run seals a serialized {@code AnalysisResult}, whose findings
 * live in {@code findingsJson} — a <em>string</em> holding the whole enriched envelope, not a
 * node. A pointer like {@code /findings/facts/0} therefore cannot resolve against the raw stored
 * bytes no matter how the scenario is written. Inflating that string into a real {@code findings}
 * node once, here, is what lets a scenario author address the envelope the way they think about
 * it, and keeps every scenario in every set addressing the same shape.
 *
 * <p><b>Why the addressable set is enforced rather than documented.</b> A required pointer that
 * cannot resolve fails loudly and gets fixed. A <em>forbidden</em> pointer that cannot resolve is
 * satisfied by every release forever — it records a pass that measured nothing, and a promotion
 * gate whose assertions cannot fail is worse than no gate, because it produces a signed verdict
 * saying it checked. The shipped income set had exactly that: it forbade {@code /findings/items/0}
 * from a v1-era shape while the v2 envelope calls that array {@code facts}, so the one scenario
 * written to prove an instance does not fabricate findings would have passed for a release that
 * fabricated them freely.
 *
 * <p>So an unaddressable pointer is refused when the set is read, at startup, rather than passing
 * quietly at evaluation time. That converts the entire class of mistake into a boot failure.
 *
 * <p><b>Two levels, deliberately.</b> Validation covers the top level and, under {@code findings},
 * the envelope's own properties — the levels that carry the shape contract. Deeper paths into a
 * fact or a citation object are allowed unchecked, because that is content rather than contract
 * and pretending to type-check it would be a schema validator wearing a gate's clothes.
 */
public final class InstanceAssertionDocument {

    private InstanceAssertionDocument() {}

    /** The node an inflated {@code findingsJson} is published under. */
    public static final String FINDINGS = "findings";

    /**
     * Top-level fields of a serialized {@code AnalysisResult}, plus the inflated findings node.
     *
     * <p>Kept as an explicit list rather than derived by reflection: a field silently appearing or
     * disappearing from the result record should break a scenario set loudly, not widen or narrow
     * what scenarios may assert without anyone deciding to.
     */
    private static final Set<String> RESULT_FIELDS = Set.of(
            "status", "reportMarkdown", "findingsJson", FINDINGS, "citations",
            "provider", "model", "inputTokens", "outputTokens", "costUsd",
            "pageCount", "skippedDocs", "reason", "filtered");

    /**
     * Properties of the v2 analyzer envelope, which is what {@code findingsJson} carries.
     *
     * <p>Mirrors {@code ai/analyzer-envelope-v2.schema.json}. {@code facts} is the array a v1-era
     * set would have called {@code items}; naming it here is what makes that mistake a refusal.
     */
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "envelopeVersion", "analyzer", "reportMarkdown", "facts", "assumptions",
            "warnings", "recommendations", "calculations", "missingItems", "citations",
            "confidence", "domain", "findings");

    /**
     * Builds the document pointers are evaluated against.
     *
     * <p>{@code findingsJson} is left in place as well as inflated. Removing it would make the
     * assertion document differ from what was actually sealed, and the point of this document is
     * to be a readable view of the stored result rather than a substitute for it.
     */
    public static JsonNode of(byte[] serializedResult, ObjectMapper mapper) {
        Objects.requireNonNull(serializedResult, "serializedResult");
        Objects.requireNonNull(mapper, "mapper");
        try {
            JsonNode root = mapper.readTree(serializedResult);
            if (!(root instanceof ObjectNode object)) {
                return root;
            }
            JsonNode findings = object.path("findingsJson");
            if (findings.isTextual() && !findings.asText().isBlank()) {
                try {
                    object.set(FINDINGS, mapper.readTree(findings.asText()));
                } catch (Exception notJson) {
                    // A findings payload that is not JSON is a real outcome, not a crash: the
                    // scenario's pointers into it simply will not resolve, which is a failure the
                    // verdict should carry rather than an exception that loses the whole run.
                    object.putNull(FINDINGS);
                }
            }
            return object;
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("stored analysis result was not readable JSON");
        }
    }

    /**
     * Whether a pointer could address anything in this document's shape.
     *
     * <p>Shape, not presence: {@code /findings/facts/0} is addressable whether or not a particular
     * run produced a first fact. Presence is what the scenario is asking about; addressability is
     * whether the question is well formed at all.
     */
    public static boolean addressable(String pointer) {
        if (pointer == null || pointer.isBlank() || !pointer.startsWith("/")) {
            return false;
        }
        String[] tokens = pointer.substring(1).split("/", -1);
        if (tokens.length == 0 || tokens[0].isBlank() || !RESULT_FIELDS.contains(tokens[0])) {
            return false;
        }
        if (!FINDINGS.equals(tokens[0]) || tokens.length == 1) {
            // Anything below a result field other than findings is content, not contract.
            return allTokensNonBlank(tokens, 1);
        }
        if (!ENVELOPE_FIELDS.contains(tokens[1])) {
            return false;
        }
        return allTokensNonBlank(tokens, 2);
    }

    private static boolean allTokensNonBlank(String[] tokens, int from) {
        for (int i = from; i < tokens.length; i++) {
            if (tokens[i].isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a pointer resolves to a present value in this document.
     *
     * <p>An explicit JSON null counts as absent. A scenario asking whether the analyzer produced a
     * fact is not answered by a null sitting where the fact would be, and a forbidden pointer
     * should not be satisfied by one either.
     */
    public static boolean resolves(JsonNode document, String pointer) {
        if (document == null || pointer == null || pointer.isBlank()) {
            return false;
        }
        JsonNode at = document.at(pointer);
        return !at.isMissingNode() && !at.isNull();
    }
}
