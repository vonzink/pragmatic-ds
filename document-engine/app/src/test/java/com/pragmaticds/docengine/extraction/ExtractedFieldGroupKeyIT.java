package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The JPA half of Spec 5a D1: {@code ExtractedField} maps {@code group_key}, and the entity can
 * therefore express three occurrences of ONE field name in ONE document — which the rebuilt
 * {@code extracted_field_one_current} index permits because their keys differ.
 *
 * <p>The other half is the contract this task must NOT break: every field an existing schema
 * extracts is single-valued and persists a NULL key, i.e. exactly the pre-V13 row.
 */
class ExtractedFieldGroupKeyIT extends AbstractExtractionIT {

    @Autowired private ExtractedFieldRepository fields;

    @Test
    void three_occurrences_of_one_field_name_round_trip_through_the_entity() {
        UUID packageId = insertPackage("group-key-entity-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        UUID schemaId =
                jdbc.queryForObject(
                        "SELECT schema_id FROM extracted_field WHERE logical_document_id = ?"
                                + " LIMIT 1",
                        UUID.class,
                        documentId);

        // Schedule E Part I's shape: one field name, three printed property columns. Every
        // occurrence is a missing field here (method NONE, confidence 0) because T1 owns the
        // COLUMN only — locating the values is T3's job.
        for (String key : List.of("A", "B", "C")) {
            fields.save(
                    new ExtractedField(
                            documentId,
                            schemaId,
                            "rentsReceived",
                            "MONEY",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "NONE",
                            "engine/1.0.0",
                            new BigDecimal("0.0000"),
                            null,
                            ExtractedField.VALIDATION_MANUAL_REVIEW_REQUIRED,
                            ExtractedField.REVIEW_NOT_REVIEWED,
                            false,
                            key));
        }

        assertThat(
                        fields
                                .findCurrentOccurrencesByLogicalDocumentId(documentId)
                                .stream()
                                .filter(field -> field.getFieldName().equals("rentsReceived"))
                                .map(ExtractedField::getGroupKey)
                                .sorted()
                                .toList())
                .as("one field name, three occurrences, each keyed by its printed column")
                .containsExactly("A", "B", "C");
    }

    @Test
    void every_field_of_an_existing_single_valued_schema_persists_a_null_group_key() {
        UUID packageId = insertPackage("group-key-null-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT field_name, group_key FROM extracted_field"
                                + " WHERE logical_document_id = ? AND is_current"
                                + " ORDER BY field_name",
                        documentId);

        // paystub@1.5.0 (V53): ten rule-read scalars, three AI-only totals, and five AI-only
        // earnings-line fields whose ROW group the rules cannot address — so each of those five
        // is ONE null-keyed missing occurrence, and every row here still carries a NULL key.
        assertThat(rows).hasSize(18);
        assertThat(rows)
                .as("NULL group_key on every row — nothing about the ungrouped contract changed")
                .allSatisfy(row -> assertThat(row.get("group_key")).isNull());
    }
}
