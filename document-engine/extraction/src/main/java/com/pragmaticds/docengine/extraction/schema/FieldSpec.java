package com.pragmaticds.docengine.extraction.schema;

import java.util.List;

/**
 * One field of an extraction schema. {@code extractors} are ordered — the engine tries each in
 * turn and the first success wins, so a schema encodes its own fallback ladder (e.g. TABLE_CLUSTER
 * first, ANCHOR_LABEL when clustering found no grid).
 *
 * @param normalizer normalizer name ({@code money}, {@code date}, {@code payFrequency},
 *     {@code personName}) or null for raw text
 * @param group the repeating-group declaration (Spec 5a), or null for a single-valued field —
 *     the shape every schema shipped before Spec 5a has, and one that must keep behaving
 *     identically. When present, the extractor ladder runs ONCE PER GROUP KEY and each
 *     occurrence persists its own row, evidence and confidence.
 * @param derivation the field's schema-declared derivation (spec 2026-09-23 §4), or null for
 *     every field whose value comes only from its own rungs — the shape every schema shipped
 *     before this task has. When present the engine fills the field from other captured
 *     outcomes ONLY when its own rungs left it MISSING; a captured rung is never overridden.
 */
public record FieldSpec(
        String name,
        DataType dataType,
        boolean required,
        String normalizer,
        boolean sensitive,
        List<ExtractorSpec> extractors,
        GroupSpec group,
        DerivationSpec derivation) {

    /** The pre-Spec-5a shape — every single-valued field, in the loader and in every test. */
    public FieldSpec(
            String name,
            DataType dataType,
            boolean required,
            String normalizer,
            boolean sensitive,
            List<ExtractorSpec> extractors) {
        this(name, dataType, required, normalizer, sensitive, extractors, null, null);
    }

    /** The pre-derivation shape — every schema field before this task, group or not. */
    public FieldSpec(
            String name,
            DataType dataType,
            boolean required,
            String normalizer,
            boolean sensitive,
            List<ExtractorSpec> extractors,
            GroupSpec group) {
        this(name, dataType, required, normalizer, sensitive, extractors, group, null);
    }
}
