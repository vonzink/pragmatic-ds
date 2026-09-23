package com.pragmaticds.docengine.extraction.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The two read shapes for authored extraction schemas.
 *
 * <h2>On the masking boundary</h2>
 *
 * <p>{@code ResponseDtoMaskingArchTest} guards value-bearing components in this package, and
 * nothing here is one. A schema definition is CONFIGURATION — form captions, regexes, geometry —
 * authored by a person and never read off a borrower's document. It carries no extracted value, so
 * {@code MaskableValue} would have nothing to hide and would mangle the labels that make a schema
 * readable.
 *
 * <p>That reasoning is written down because the arch test's own javadoc names the adjacent hazard:
 * a component carrying a VALUE under a name outside its whitelist bypasses the rule silently. This
 * is the legitimate case of a component outside it. A future member here that echoed captured text
 * back — a sample match, a preview of what a pattern found — would NOT be, and would need the rule
 * widened in the same commit.
 */
public final class ExtractionSchemaView {

    private ExtractionSchemaView() {}

    /**
     * One document type's EFFECTIVE schema: the one that decides extraction for this org right now,
     * after shadowing.
     *
     * @param scope {@code ORG} when this org authored the winner, {@code GLOBAL} when it inherits
     *     the built-in. The single most useful bit in the listing — it answers "which of these are
     *     mine to change" without a second call.
     * @param fieldCount how many fields the winner declares. Enough to spot the case that
     *     surprises people: a tenant schema shadows a global WHOLESALE rather than merging, so a
     *     count far below the global's means fields that used to extract no longer do.
     */
    public record EffectiveSchema(
            String documentTypeCode, String version, String scope, int fieldCount) {

        public static final String SCOPE_ORG = "ORG";
        public static final String SCOPE_GLOBAL = "GLOBAL";
    }

    /** The effective set, one entry per document type, ordered by type code. */
    public record EffectiveSchemaList(List<EffectiveSchema> schemas) {}

    /**
     * One version this org authored, with the definition it authored.
     *
     * @param definition the stored definition as JSON, not as a string — a schema that came back
     *     double-encoded would have to be unwrapped before it could be edited and re-posted, which
     *     is the whole use for this endpoint
     */
    public record AuthoredVersion(String version, boolean active, JsonNode definition) {}

    /**
     * Every version this org has authored for one document type, newest first, retired ones
     * included.
     *
     * <p>Retired versions are part of the answer rather than clutter: {@code
     * extracted_field.schema_id} cites them, so a field extracted last month describes a version
     * that no longer applies, and a reader trying to explain that field needs to see it. It is also
     * what makes the authoring rule legible — a new version must exceed the highest EVER used, not
     * merely the active one.
     */
    public record AuthoredHistory(String documentTypeCode, List<AuthoredVersion> versions) {}
}
