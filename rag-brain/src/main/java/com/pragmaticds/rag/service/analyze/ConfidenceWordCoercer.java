package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the confidence WORDS a model sometimes writes on an envelope-v2 response into the
 * numeric scores the envelope schema requires, before schema validation sees them.
 *
 * <p>Why this exists. The v2 contract carries two different confidence notions side by side:
 * {@code domain.sources[].confidence} is the word HIGH / MEDIUM / LOW (the income domain
 * schema), while the envelope's own {@code confidence}, and every {@code facts[]} and
 * {@code assumptions[]} entry, is a number in [0, 1]. On 2026-09-22 the first production
 * income-v2 run wrote the words everywhere; schema validation rejected it, the corrective
 * retry wrote the same words again, and the run failed closed — a report lost to a formatting
 * habit the rest of the contract itself invites. The mapping is unambiguous, so the engine
 * accepts the words and records that it did, rather than spending a second model call on them.
 *
 * <p>Scope is deliberately narrow: only the envelope-level {@code confidence} and the
 * {@code confidence} member of objects in the top-level envelope arrays, and only the three
 * words (case-insensitive). Anything else — a stray string, a number out of range, a word on
 * a citation that the schema does not allow — is left exactly as the model wrote it, so the
 * validator still reports it. Domain content is never touched: the domain schema owns it.
 */
final class ConfidenceWordCoercer {

    /** HIGH / MEDIUM / LOW → the scores the domain enricher treats as those bands. */
    static final Map<String, Double> SCORES = Map.of(
            "HIGH", 0.9,
            "MEDIUM", 0.6,
            "MED", 0.6,
            "LOW", 0.3);

    /** The envelope arrays whose elements carry a schema-typed numeric confidence. */
    private static final List<String> ARRAYS = List.of(
            "facts", "assumptions", "warnings", "recommendations", "missingItems");

    private ConfidenceWordCoercer() {}

    /**
     * Rewrites, in place, every recognised confidence word under {@code root}.
     *
     * @return how many values were rewritten (0 when the response already conformed, or when
     *     {@code root} is not an object)
     */
    static int coerce(JsonNode root) {
        if (!(root instanceof ObjectNode envelope)) {
            return 0;
        }
        int rewritten = coerceMember(envelope);
        for (String array : ARRAYS) {
            JsonNode items = envelope.path(array);
            if (!items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                if (item instanceof ObjectNode entry) {
                    rewritten += coerceMember(entry);
                }
            }
        }
        return rewritten;
    }

    private static int coerceMember(ObjectNode node) {
        JsonNode value = node.get("confidence");
        if (value == null || !value.isTextual()) {
            return 0;
        }
        Double score = SCORES.get(value.asText().trim().toUpperCase(Locale.ROOT));
        if (score == null) {
            return 0;
        }
        node.put("confidence", score);
        return 1;
    }
}
