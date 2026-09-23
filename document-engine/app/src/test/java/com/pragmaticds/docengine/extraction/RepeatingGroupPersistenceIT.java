package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Group-aware persistence: every OCCURRENCE gets its own {@code extracted_field} row, its own
 * VALUE and LABEL evidence, and its own {@code group_key}.
 *
 * <p><b>The orphan test is mandatory.</b> Supersession here is a physical, PACKAGE-WIDE
 * delete-then-recreate — {@code extracted_field.is_current} is never flipped anywhere, and the
 * retry delete's predicate carries no {@code field_name} and no {@code group_key} term — so the
 * no-orphan property holds by construction today. It is pinned here anyway because it is
 * load-bearing and nothing else pins it: a later "optimisation" into a name-keyed delete, an
 * {@code is_current} flip by name, or any reordering that let an insert reach the
 * non-deferrable unique index before the delete would all break it silently. Both readings of
 * "a form with only A and B" are covered — a NARROWED SCHEMA here, and a page whose column C is
 * blank in {@link #an_empty_column_persists_as_a_missing_occurrence_and_never_as_zero}.
 *
 * <p>The fixture is the real {@code paystub_complete} truth page with a synthetic Schedule
 * E-shaped block appended at y 500-537, clear of every existing label line, plus an org-scoped
 * schema that is the shipped paystub seed with one grouped field added. That keeps this task
 * independent of T8's SCHEDULE_E work while exercising the identical code path — the same
 * org-schema pattern {@code FieldExtractionStageIT} already uses.
 */
class RepeatingGroupPersistenceIT extends AbstractExtractionIT {

    /**
     * The grouped field appended to the paystub seed. {@code keys} is replaced per test. The
     * value pattern avoids {@code \d} deliberately: {@code \d} is not a legal JSON string
     * escape, so a character class keeps the definition parseable as jsonb.
     */
    private static final String RENTS_RECEIVED =
            """
            {"name": "rentsReceived", "dataType": "MONEY", "required": false,
             "normalizer": "money", "sensitive": false,
             "group": {"kind": "COLUMN",
                       "header": {"kind": "literal", "pattern": "Properties:"},
                       "keys": ["A", "B", "C"]},
             "extractors": [{"method": "ANCHOR_LABEL", "strength": 0.9,
               "label": {"kind": "literal", "pattern": "Rents received"},
               "value": {"pattern": "[0-9]{1,3}(?:,[0-9]{3})*\\\\.[0-9]{2}",
                         "occurrence": 0, "scope": "LINE"}}]}
            """;

    @AfterEach
    void dropOrgSchemas() {
        // Rows referencing the org schema must go first (FK). A leaked org schema row would
        // shadow paystub@1.0.0 for every later test in the shared container.
        jdbc.update(
                """
                DELETE FROM field_evidence WHERE extracted_field_id IN
                    (SELECT id FROM extracted_field WHERE schema_id IN
                        (SELECT id FROM extraction_schema WHERE org_id = ?
                           AND version IN ('9.1.0', '9.2.0')))
                """,
                ORG_DEV);
        jdbc.update(
                """
                DELETE FROM extracted_field WHERE schema_id IN
                    (SELECT id FROM extraction_schema WHERE org_id = ?
                       AND version IN ('9.1.0', '9.2.0'))
                """,
                ORG_DEV);
        jdbc.update(
                "DELETE FROM extraction_schema WHERE org_id = ?"
                        + " AND version IN ('9.1.0', '9.2.0')",
                ORG_DEV);
        schemaLoader.invalidateAll();
    }

    // ── the mandatory orphan test ────────────────────────────────────────────

    @Test
    void re_extracting_without_column_C_leaves_no_current_C_row() {
        insertGroupedSchema("9.1.0", "A", "B", "C");
        UUID packageId = insertPackage("group-orphan-it");
        insertFixturePages(packageId, ORG_DEV, groupedPaystubPage(true));
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        assertThat(occurrencesOf(documentId, "rentsReceived"))
                .as("three properties on the first pass")
                .hasSize(3);

        // The borrower's form now describes two properties, and the schema that reads it says
        // so. 9.2.0 shadows 9.1.0 (highest version within the org scope wins on load).
        insertGroupedSchema("9.2.0", "A", "B");
        assertThat(runStage(packageId, ProcessingStatus.EXTRACTING).success()).isTrue();

        List<Map<String, Object>> after = occurrencesOf(documentId, "rentsReceived");
        assertThat(after).hasSize(2);
        assertThat(after).extracting(row -> row.get("group_key")).containsExactly("A", "B");

        Integer anyCRow =
                jdbc.queryForObject(
                        "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?"
                                + " AND field_name = 'rentsReceived' AND group_key = 'C'",
                        Integer.class,
                        documentId);
        assertThat(anyCRow)
                .as("no C row survives the re-extraction — current or otherwise")
                .isZero();

        Integer orphanEvidence =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM field_evidence e
                        WHERE NOT EXISTS
                            (SELECT 1 FROM extracted_field f WHERE f.id = e.extracted_field_id)
                        """,
                        Integer.class);
        assertThat(orphanEvidence)
                .as("evidence goes before its field — never a dangling chain")
                .isZero();
    }

    // ── one row and one evidence chain per occurrence ────────────────────────

    @Test
    void every_column_persists_as_its_own_row_with_its_own_evidence() {
        insertGroupedSchema("9.1.0", "A", "B", "C");
        UUID packageId = insertPackage("group-persist-it");
        insertFixturePages(packageId, ORG_DEV, groupedPaystubPage(true));
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT document_type_code FROM logical_document WHERE id = ?",
                                String.class,
                                documentId))
                .as("the synthetic block must not disturb classification, or no schema loads")
                .isEqualTo("PAYSTUB");

        List<Map<String, Object>> occurrences = occurrencesOf(documentId, "rentsReceived");
        assertThat(occurrences).hasSize(3);
        assertThat(occurrences)
                .extracting(row -> row.get("group_key"))
                .containsExactly("A", "B", "C");
        assertThat(occurrences)
                .extracting(row -> row.get("displayed_text"))
                .containsExactly("16,800.00", "14,400.00", "9,600.00");
        assertThat(occurrences)
                .extracting(row -> row.get("extraction_method"))
                .containsExactly("ANCHOR_LABEL", "ANCHOR_LABEL", "ANCHOR_LABEL");

        List<BigDecimal> valueBoxLefts = new ArrayList<>();
        for (Map<String, Object> row : occurrences) {
            UUID fieldId = (UUID) row.get("id");
            List<Map<String, Object>> value = evidenceOf(fieldId, "VALUE");
            assertThat(value).as("VALUE evidence for column %s", row.get("group_key")).hasSize(1);
            assertThat(evidenceOf(fieldId, "LABEL"))
                    .as("LABEL evidence for column %s", row.get("group_key"))
                    .isNotEmpty();
            valueBoxLefts.add(scaled(value.get(0).get("x")));
        }
        assertThat(valueBoxLefts)
                .as("three occurrences, three DIFFERENT boxes — never one box shared")
                .doesNotHaveDuplicates();
    }

    // ── D5: a missing occurrence, never absent and never zero ────────────────

    @Test
    void an_empty_column_persists_as_a_missing_occurrence_and_never_as_zero() {
        insertGroupedSchema("9.1.0", "A", "B", "C");
        UUID packageId = insertPackage("group-missing-it");
        insertFixturePages(packageId, ORG_DEV, groupedPaystubPage(false));
        runPipelineToExtraction(packageId);

        List<Map<String, Object>> occurrences =
                occurrencesOf(onlyDocumentOf(packageId), "rentsReceived");
        assertThat(occurrences)
                .as("column C is blank on the form, so its row is MISSING — never absent")
                .hasSize(3);

        Map<String, Object> columnC = occurrences.get(2);
        assertThat(columnC.get("group_key")).isEqualTo("C");
        assertThat(columnC.get("extraction_method")).isEqualTo("NONE");
        assertThat((BigDecimal) columnC.get("confidence")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(columnC.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(columnC.get("review_status")).isEqualTo("NOT_REVIEWED");
        assertThat(columnC.get("displayed_text")).isNull();
        assertThat(columnC.get("raw_value")).isNull();
        assertThat(columnC.get("normalized_number"))
                .as("a defaulted 0.00 in a rental line silently changes qualifying income")
                .isNull();
        assertThat(evidenceOf((UUID) columnC.get("id"), "VALUE")).isEmpty();
        assertThat(evidenceOf((UUID) columnC.get("id"), "LABEL")).isEmpty();

        // The neighbours are untouched by the empty column beside them.
        assertThat(occurrences.get(0).get("displayed_text")).isEqualTo("16,800.00");
        assertThat(occurrences.get(1).get("displayed_text")).isEqualTo("14,400.00");
    }

    // ── the ungrouped path, unchanged ────────────────────────────────────────

    @Test
    void an_ungrouped_field_on_the_same_document_still_has_a_null_group_key() {
        insertGroupedSchema("9.1.0", "A", "B", "C");
        UUID packageId = insertPackage("group-ungrouped-it");
        insertFixturePages(packageId, ORG_DEV, groupedPaystubPage(true));
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        List<Map<String, Object>> netPay = occurrencesOf(documentId, "netPay");
        assertThat(netPay).as("an ungrouped field is still exactly one row").hasSize(1);
        assertThat(netPay.get(0).get("group_key")).isNull();
        assertThat(netPay.get(0).get("displayed_text"))
                .isEqualTo(expectedDisplayedText("paystub_complete", "netPay"));
        assertThat(evidenceOf((UUID) netPay.get(0).get("id"), "VALUE")).isNotEmpty();

        // Ten seed fields, of which nine are ungrouped singletons alongside netPay, plus the
        // three rentsReceived occurrences.
        Integer ungrouped =
                jdbc.queryForObject(
                        "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?"
                                + " AND is_current AND group_key IS NULL",
                        Integer.class,
                        documentId);
        assertThat(ungrouped).isEqualTo(10);
    }

    // ── the stage digest's occurrence dimension ──────────────────────────────

    /**
     * The one part of grouping that did NOT fall out of adding a constructor argument. The
     * EXTRACTING digest's tuple was {@code (documentId, fieldName, method, confidence)}, so N
     * occurrences of one field name captured by one rung at one confidence produced N BYTE-
     * IDENTICAL tuples — the digest could count them but never tell them apart.
     *
     * <p>This test moves the occurrence set from properties A and B to properties B and C on the
     * SAME document: two different rental properties' rents are now what got extracted, which is
     * a material change to the document's data. Everything the old tuple could see is deliberately
     * held constant — same document id, same field name, same {@code ANCHOR_LABEL} rung, same
     * confidence to the last digit (asserted, not assumed) — so the digest can only move if the
     * group key is part of it. Without that, the stage would report "identical output" for a
     * package whose extracted properties had changed, and any change detection or caching built
     * on the digest would act on that answer.
     */
    @Test
    void moving_the_occurrence_set_between_columns_changes_the_stage_digest() {
        insertGroupedSchema("9.1.0", "A", "B");
        UUID packageId = insertPackage("group-digest-it");
        insertFixturePages(packageId, ORG_DEV, groupedPaystubPage(true));
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        String columnsAB = runStage(packageId, ProcessingStatus.EXTRACTING).outputDigest();
        List<Map<String, Object>> ab = occurrencesOf(documentId, "rentsReceived");
        assertThat(ab).extracting(row -> row.get("group_key")).containsExactly("A", "B");

        // The same page, read for properties B and C instead. 9.2.0 shadows 9.1.0.
        insertGroupedSchema("9.2.0", "B", "C");
        String columnsBC = runStage(packageId, ProcessingStatus.EXTRACTING).outputDigest();
        List<Map<String, Object>> bc = occurrencesOf(documentId, "rentsReceived");
        assertThat(bc).extracting(row -> row.get("group_key")).containsExactly("B", "C");

        // The guard that makes the assertion below mean what it claims: everything the PRE-Spec-5a
        // tuple could see is identical between the two runs, so a moved digest is the key and
        // nothing else. (The displayed values differ — 16,800/14,400 became 14,400/9,600 — and the
        // old tuple could not see those either, which is the whole problem.)
        assertThat(methodAndConfidenceOf(bc))
                .as("same rung, same confidence — the old tuple saw NO difference at all")
                .isEqualTo(methodAndConfidenceOf(ab));

        assertThat(columnsBC)
                .as("a different pair of properties is a different extraction result")
                .isNotEqualTo(columnsAB);
    }

    // ── the confidence contract ──────────────────────────────────────────────

    @Test
    void a_grouped_occurrence_keeps_exactly_the_three_confidence_components() {
        insertGroupedSchema("9.1.0", "A", "B", "C");
        UUID packageId = insertPackage("group-components-it");
        insertFixturePages(packageId, ORG_DEV, groupedPaystubPage(true));
        runPipelineToExtraction(packageId);

        UUID fieldId =
                (UUID) occurrencesOf(onlyDocumentOf(packageId), "rentsReceived").get(0).get("id");
        String componentsJson =
                jdbc.queryForObject(
                        "SELECT confidence_components::text FROM extracted_field WHERE id = ?",
                        String.class,
                        fieldId);
        JsonNode components = readJson(componentsJson);

        List<String> names = new ArrayList<>();
        components.fieldNames().forEachRemaining(names::add);
        // In ANY order, deliberately: confidence_components is jsonb, which stores object keys
        // sorted by (length, bytewise) rather than as written, so a read-back order assertion
        // would pin Postgres's storage rule instead of the contract. The contract is the SET —
        // exactly these three, never a fourth.
        assertThat(names)
                .as("three components, never a fourth — the V7 contract")
                .containsExactlyInAnyOrder(
                        "spanConfidence", "anchorStrength", "normalizerCertainty");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A field's CURRENT rows in group-key order; one row for an ungrouped field. */
    private List<Map<String, Object>> occurrencesOf(UUID documentId, String fieldName) {
        return jdbc.queryForList(
                "SELECT * FROM extracted_field WHERE logical_document_id = ? AND is_current"
                        + " AND field_name = ? ORDER BY group_key",
                documentId,
                fieldName);
    }

    /** Exactly what the PRE-Spec-5a digest tuple could see of an occurrence, in row order. */
    private static List<String> methodAndConfidenceOf(List<Map<String, Object>> occurrences) {
        List<String> shape = new ArrayList<>();
        for (Map<String, Object> row : occurrences) {
            shape.add(
                    row.get("extraction_method")
                            + "/"
                            + ((BigDecimal) row.get("confidence")).toPlainString());
        }
        return shape;
    }

    /**
     * The paystub seed with {@link #RENTS_RECEIVED} appended and its keys replaced, inserted as
     * an ORG-scoped schema. An org schema shadows every global schema of its type WHOLESALE, so
     * the copy must start from the seed or the paystub's own ten fields would vanish.
     */
    private void insertGroupedSchema(String version, String... keys) {
        ObjectNode definition =
                readJson(
                                jdbc.queryForObject(
                                        "SELECT definition::text FROM extraction_schema"
                                                + " WHERE org_id IS NULL"
                                                + " AND document_type_code = 'PAYSTUB'"
                                                + " AND version = '1.0.0'",
                                        String.class))
                        .deepCopy();
        ObjectNode rents = (ObjectNode) readJson(RENTS_RECEIVED);
        ArrayNode declared = JSON.createArrayNode();
        for (String key : keys) {
            declared.add(key);
        }
        ((ObjectNode) rents.get("group")).set("keys", declared);
        ((ArrayNode) definition.get("fields")).add(rents);
        jdbc.update(
                "INSERT INTO extraction_schema (org_id, document_type_code, version, definition)"
                        + " VALUES (?, 'PAYSTUB', ?, ?::jsonb)",
                ORG_DEV,
                version,
                definition.toString());
        schemaLoader.invalidateAll();
    }

    /**
     * The real paystub_complete truth page with a Schedule E-shaped block appended at
     * y 500-537 — clear of every existing label line (the fixture's words all sit between
     * y 61.9 and y 343.4), so no seed field's extraction changes.
     *
     * <p>Column centers land at 363 / 433 / 503, giving bands A [328, 398), B [398, 468) and
     * C [468, 538); the amounts' centers are 369, 439 and 509.
     */
    private JsonNode groupedPaystubPage(boolean columnCFilled) {
        ObjectNode page = truth("paystub_complete").get("pages").get(0).deepCopy();
        ArrayNode words = (ArrayNode) page.get("words");
        words.add(word("Properties:", "270.0", "500.0", "40.0", "7.0"));
        words.add(word("A", "360.0", "500.0", "6.0", "7.0"));
        words.add(word("B", "430.0", "500.0", "6.0", "7.0"));
        words.add(word("C", "500.0", "500.0", "6.0", "7.0"));
        words.add(word("Rents", "56.0", "530.0", "20.0", "7.4"));
        words.add(word("received", "79.0", "530.0", "30.0", "7.4"));
        words.add(word("16,800.00", "350.0", "530.0", "38.0", "7.4"));
        words.add(word("14,400.00", "420.0", "530.0", "38.0", "7.4"));
        if (columnCFilled) {
            words.add(word("9,600.00", "490.0", "530.0", "38.0", "7.4"));
        }
        return JSON.createArrayNode().add(page);
    }

    private static ObjectNode word(String text, String x, String y, String w, String h) {
        ObjectNode word = JSON.createObjectNode();
        word.put("text", text);
        word.put("x", new BigDecimal(x));
        word.put("y", new BigDecimal(y));
        word.put("width", new BigDecimal(w));
        word.put("height", new BigDecimal(h));
        return word;
    }

    /** The truth's own expected displayed text — never a literal transcribed into this file. */
    private static String expectedDisplayedText(String fixtureName, String fieldName) {
        for (JsonNode expected : truth(fixtureName).get("expectedFields")) {
            if (expected.get("field").asText().equals(fieldName)) {
                return expected.get("displayedText").asText();
            }
        }
        throw new IllegalStateException("no expected field " + fieldName);
    }

    private static JsonNode readJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("unreadable test json");
        }
    }
}
