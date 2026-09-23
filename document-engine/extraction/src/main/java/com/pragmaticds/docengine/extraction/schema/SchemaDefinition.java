package com.pragmaticds.docengine.extraction.schema;

import java.util.List;

/**
 * A parsed extraction schema — the jsonb {@code extraction_schema.definition} column turned into
 * records (V7). One schema defines every field extracted for one document type at one version;
 * adding a field is a new schema version, never a code change (Phase 5 acceptance criterion 5).
 */
public record SchemaDefinition(
        String documentTypeCode, String version, List<FieldSpec> fields, List<String> instanceKey) {

    /**
     * The pre-Phase-C shape: a type whose documents never repeat inside one run of pages.
     * Empty {@code instanceKey}, which is also what every schema authored before V25 parses to.
     */
    public SchemaDefinition(String documentTypeCode, String version, List<FieldSpec> fields) {
        this(documentTypeCode, version, fields, List.of());
    }

    /**
     * This schema, narrowed to the fields that identify ONE instance — the projection the
     * per-page instance probe extracts with. Empty when the schema declares no key, which is the
     * probe's signal to do nothing at all.
     *
     * <p>Narrowing rather than re-implementing matters: the probe then reads the period with the
     * schema's OWN authored extractor ladder, at its own anchors, so a pack that changes how a
     * period is found changes how instances are separated, with no second place to update.
     */
    public SchemaDefinition instanceKeyProjection() {
        if (instanceKey.isEmpty()) {
            return new SchemaDefinition(documentTypeCode, version, List.of(), List.of());
        }
        return new SchemaDefinition(
                documentTypeCode,
                version,
                fields.stream().filter(field -> instanceKey.contains(field.name())).toList(),
                instanceKey);
    }
}
