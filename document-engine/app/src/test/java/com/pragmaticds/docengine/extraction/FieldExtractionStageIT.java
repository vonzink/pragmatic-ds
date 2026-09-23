package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The EXTRACTING stage against fixture truth. The evidence-heavy tests are ENGINE-DEPENDENT: in
 * this worktree the placeholder engine reports every field missing, so they fail on their FIRST
 * assertion (expected TABLE_CLUSTER/ANCHOR_LABEL but was NONE) and turn green when the real
 * DefaultFieldExtractionEngine lands at merge. Everything else — schema-skip, the
 * missing-field-is-a-result shape, retry idempotency — is green against the placeholder too.
 */
class FieldExtractionStageIT extends AbstractExtractionIT {

    // ── ENGINE-DEPENDENT: red on the placeholder, green at merge ───────────

    @Test
    void ten_fields_with_full_evidence_on_the_complete_paystub() {
        UUID packageId = insertPackage("extract-complete-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        // paystub@1.5.0 (V53): the ten rule-read fields plus eight AI-only ones that a rules-only
        // run carries as MISSING rows — one per field, null-keyed even for the ROW group.
        assertThat(fields).hasSize(18);

        for (JsonNode expected : truth("paystub_complete").get("expectedFields")) {
            String name = expected.get("field").asText();
            Map<String, Object> row = fields.get(name);
            assertThat(row).as("current row for %s", name).isNotNull();

            // The first engine-dependent assertion: the placeholder answers NONE here.
            assertThat(row.get("extraction_method"))
                    .as("field %s extraction method", name)
                    .isEqualTo(expected.get("method").asText());
            assertThat(row.get("displayed_text"))
                    .as("field %s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
            assertNormalized(name, row, expected.get("normalized"));
            assertThat((BigDecimal) row.get("confidence"))
                    .as("field %s confidence", name)
                    .isGreaterThan(BigDecimal.ZERO);

            UUID fieldId = (UUID) row.get("id");
            List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(valueEvidence).as("field %s VALUE evidence", name).isNotEmpty();
            // At least one VALUE box equals a truth value word box, on the right page.
            int truthPage = expected.get("pageIndex").asInt();
            boolean valueBoxMatched = false;
            for (Map<String, Object> evidence : valueEvidence) {
                assertThat(packagePageIndexOf((UUID) evidence.get("page_id")))
                        .as("field %s VALUE evidence page", name)
                        .isEqualTo(truthPage);
                for (JsonNode word : expected.get("valueWords")) {
                    valueBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(valueBoxMatched)
                    .as("field %s: some VALUE evidence box equals a truth valueWords box", name)
                    .isTrue();

            String method = expected.get("method").asText();
            if (method.equals("ANCHOR_LABEL") || method.equals("TABLE_CLUSTER")) {
                List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
                assertThat(labelEvidence).as("field %s LABEL evidence", name).isNotEmpty();
                boolean labelBoxMatched = false;
                for (Map<String, Object> evidence : labelEvidence) {
                    for (JsonNode word : expected.get("labelWords")) {
                        labelBoxMatched |= boxMatches(evidence, word);
                    }
                }
                assertThat(labelBoxMatched)
                        .as("field %s: some LABEL evidence box equals a truth labelWords box", name)
                        .isTrue();
            }
            if (name.equals("currentGrossPay") || name.equals("ytdGrossPay")) {
                // The grid fields must carry the cell reference — the traceability spine.
                for (Map<String, Object> evidence : valueEvidence) {
                    UUID elementId = (UUID) evidence.get("layout_element_id");
                    assertThat(elementId)
                            .as("field %s VALUE evidence layout element", name)
                            .isNotNull();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT element_type FROM layout_element WHERE id = ?",
                                            String.class,
                                            elementId))
                            .as("field %s evidence element type", name)
                            .isEqualTo("TABLE_CELL");
                }
            }
        }
    }

    @Test
    void the_other_nine_fields_extract_for_real_on_the_missing_field_fixture() {
        UUID packageId = insertPackage("extract-missing-nine-it");
        insertFixturePages(packageId, "paystub_missing_field");
        insertFixtureLayout(packageId, "paystub_missing_field");
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));
        for (JsonNode expected : truth("paystub_missing_field").get("expectedFields")) {
            String name = expected.get("field").asText();
            if (name.equals("payDate")) {
                continue; // the deliberately missing one — covered by the shape test below
            }
            assertThat(fields.get(name).get("extraction_method"))
                    .as("field %s extraction method", name)
                    .isEqualTo(expected.get("method").asText());
            assertThat(fields.get(name).get("displayed_text"))
                    .as("field %s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
        }
    }

    @Test
    void fields_come_from_the_page_they_were_read_from() {
        UUID packageId = insertPackage("extract-twopage-it");
        insertFixturePages(packageId, "paystub_twopage");
        insertFixtureLayout(packageId, "paystub_twopage"); // no grid words → no-op, by design
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));

        // payFrequency lives ONLY on the continuation page; payDate only on page 0.
        assertThat(fields.get("payFrequency").get("extraction_method"))
                .as("payFrequency extraction method")
                .isEqualTo("ANCHOR_LABEL");
        List<Map<String, Object>> frequencyEvidence =
                evidenceOf((UUID) fields.get("payFrequency").get("id"), "VALUE");
        assertThat(frequencyEvidence).as("payFrequency VALUE evidence").isNotEmpty();
        assertThat(packagePageIndexOf((UUID) frequencyEvidence.get(0).get("page_id")))
                .as("payFrequency evidence page index")
                .isEqualTo(1);

        List<Map<String, Object>> payDateEvidence =
                evidenceOf((UUID) fields.get("payDate").get("id"), "VALUE");
        assertThat(payDateEvidence).as("payDate VALUE evidence").isNotEmpty();
        assertThat(packagePageIndexOf((UUID) payDateEvidence.get(0).get("page_id")))
                .as("payDate evidence page index")
                .isEqualTo(0);
    }

    @Test
    void schema_version_bump_adds_a_field_with_zero_code_change() throws Exception {
        // The ACTIVE shipped paystub schema's fields + memoNote, inserted as an ORG row:
        // pure data. Read by is_active rather than by a pinned version — V35 moved the
        // active row from 1.0.0 to 1.1.0, and a test that hardcodes the version silently
        // starts bumping a RETIRED schema the day the next supersession lands.
        JsonNode seed =
                JSON.readTree(
                        jdbc.queryForObject(
                                "SELECT definition::text FROM extraction_schema WHERE org_id IS NULL"
                                        + " AND document_type_code = 'PAYSTUB' AND is_active",
                                String.class));
        ObjectNode definition = seed.deepCopy();
        ObjectNode memoNote =
                (ObjectNode)
                        JSON.readTree(
                                """
                                {"name":"memoNote","dataType":"STRING","required":false,
                                 "normalizer":null,"sensitive":false,
                                 "extractors":[{"method":"ANCHOR_LABEL","strength":0.9,
                                   "label":{"kind":"literal","pattern":"Memo:"},
                                   "value":{"pattern":"[A-Za-z .]+","occurrence":0,"scope":"LINE_RIGHT"}}]}
                                """);
        ((ArrayNode) definition.get("fields")).add(memoNote);
        jdbc.update(
                "INSERT INTO extraction_schema (org_id, document_type_code, version, definition)"
                        + " VALUES (?, 'PAYSTUB', '9.0.0', ?::jsonb)",
                ORG_DEV,
                definition.toString());
        schemaLoader.invalidateAll();
        try {
            UUID packageId = insertPackage("extract-bump-it");
            insertFixturePages(packageId, "paystub_twopage");
            runPipelineToExtraction(packageId);

            Map<String, Map<String, Object>> fields =
                    currentFieldsByName(onlyDocumentOf(packageId));
            assertThat(fields).as("eighteen seed fields plus memoNote").hasSize(19);

            Map<String, Object> memo = fields.get("memoNote");
            assertThat(memo).as("memoNote row").isNotNull();
            // Engine-dependent from here: the placeholder reports it missing.
            assertThat(memo.get("extraction_method"))
                    .as("memoNote extraction method")
                    .isEqualTo("ANCHOR_LABEL");
            List<Map<String, Object>> evidence = evidenceOf((UUID) memo.get("id"), "VALUE");
            assertThat(evidence).as("memoNote VALUE evidence").isNotEmpty();
            assertThat(packagePageIndexOf((UUID) evidence.get(0).get("page_id")))
                    .as("memoNote evidence page index — the Memo line is on the continuation page")
                    .isEqualTo(1);
        } finally {
            // Rows referencing the org schema must go first (FK) — leaked schema rows would
            // otherwise shadow the shipped paystub schema for every later test in the
            // shared container, because ANY org schema of a type hides EVERY global one.
            jdbc.update(
                    """
                    DELETE FROM field_evidence WHERE extracted_field_id IN
                        (SELECT id FROM extracted_field WHERE schema_id IN
                            (SELECT id FROM extraction_schema WHERE org_id = ? AND version = '9.0.0'))
                    """,
                    ORG_DEV);
            jdbc.update(
                    """
                    DELETE FROM extracted_field WHERE schema_id IN
                        (SELECT id FROM extraction_schema WHERE org_id = ? AND version = '9.0.0')
                    """,
                    ORG_DEV);
            jdbc.update(
                    "DELETE FROM extraction_schema WHERE org_id = ? AND version = '9.0.0'", ORG_DEV);
            schemaLoader.invalidateAll();
        }
    }

    // ── GREEN on the placeholder too ────────────────────────────────────────

    @Test
    void a_missing_field_is_a_result_not_an_absence() {
        UUID packageId = insertPackage("extract-missing-shape-it");
        insertFixturePages(packageId, "paystub_missing_field");
        insertFixtureLayout(packageId, "paystub_missing_field");
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));
        // EVERY schema field gets a current row — found or not (paystub@1.5.0 declares 18).
        assertThat(fields).hasSize(18);

        // The fixture drops the Pay Date line; its row is the RESULT "we looked and it is not
        // there". These assertions hold under the placeholder AND under the real engine.
        Map<String, Object> payDate = fields.get("payDate");
        assertThat(payDate.get("extraction_method")).isEqualTo("NONE");
        assertThat((BigDecimal) payDate.get("confidence")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(payDate.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(payDate.get("displayed_text")).isNull();
        assertThat(payDate.get("raw_value")).isNull();
        assertThat(payDate.get("normalized_text")).isNull();
        assertThat(payDate.get("normalized_number")).isNull();
        assertThat(payDate.get("normalized_date")).isNull();
        assertThat(evidenceOf((UUID) payDate.get("id"), "VALUE")).isEmpty();
        assertThat(evidenceOf((UUID) payDate.get("id"), "LABEL")).isEmpty();
    }

    @Test
    void documents_without_a_schema_are_skipped_not_failed() {
        UUID packageId = insertPackage("extract-skip-it");
        insertFixturePages(packageId, "combined_package");
        runPipelineToExtraction(packageId);

        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT id, document_type_code FROM logical_document WHERE package_id = ?"
                                + " ORDER BY ordinal",
                        packageId);
        assertThat(documents).hasSize(3);
        // BANK_STATEMENT is 11 since V41: the print-out's two month-to-date fields land as
        // MISSING rows on a mailed statement — a missing field is a result, not an absence.
        // PAYSTUB is paystub@1.5.0's 18 (ten rule-read, eight AI-only MISSING on a rules-only run).
        Map<String, Integer> expectedRows = Map.of("PAYSTUB", 18, "BANK_STATEMENT", 11, "W2", 10);
        for (Map<String, Object> document : documents) {
            String type = (String) document.get("document_type_code");
            Integer count =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?",
                            Integer.class,
                            document.get("id"));
            assertThat(count)
                    .as("field rows for %s", type)
                    .isEqualTo(expectedRows.get(type));
        }
    }

    @Test
    void a_document_whose_type_has_no_schema_is_skipped_not_failed() {
        // V11 seeded W2 (10 fields) and BANK_STATEMENT (9 fields) schemas, so UNKNOWN is the
        // schema-less exemplar, and it needs a package of its OWN: inside the combined
        // fixture the letter pages continue the W-2's run instead of forming an UNKNOWN
        // document, so the eight letter pages are lifted out here to stand alone.
        ArrayNode letterPages = JSON.createArrayNode();
        JsonNode combined = truth("combined_package").get("pages");
        for (int pageIndex = 12; pageIndex < 20; pageIndex++) {
            letterPages.add(combined.get(pageIndex));
        }
        UUID packageId = insertPackage("extract-skip-unknown-it");
        insertFixturePages(packageId, ORG_DEV, letterPages);
        runPipelineToExtraction(packageId);

        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT id, document_type_code FROM logical_document WHERE package_id = ?"
                                + " ORDER BY ordinal",
                        packageId);
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).get("document_type_code")).isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?",
                                Integer.class,
                                documents.get(0).get("id")))
                .as("no schema for UNKNOWN: zero rows, and the stage still succeeded")
                .isZero();
    }

    @Test
    void extracting_stage_is_retry_idempotent() {
        UUID packageId = insertPackage("extract-retry-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();
        assertThat(runStage(packageId, ProcessingStatus.SPLITTING).success()).isTrue();

        StageOutcome first = runStage(packageId, ProcessingStatus.EXTRACTING);
        StageOutcome second = runStage(packageId, ProcessingStatus.EXTRACTING);
        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        // Same spans, layout, and schema — the digest is deterministic across the retry.
        assertThat(second.outputDigest()).isEqualTo(first.outputDigest());

        UUID documentId = onlyDocumentOf(packageId);
        Integer total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?",
                        Integer.class,
                        documentId);
        assertThat(total).as("delete-then-recreate: no accumulation").isEqualTo(18);
        // No duplicate current rows per (document, field) — the one_current index guards this
        // in the database; assert it observably too.
        Integer maxPerName =
                jdbc.queryForObject(
                        """
                        SELECT coalesce(max(n), 0) FROM (
                            SELECT count(*) AS n FROM extracted_field
                            WHERE logical_document_id = ? AND is_current
                            GROUP BY field_name) counts
                        """,
                        Integer.class,
                        documentId);
        assertThat(maxPerName).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void assertNormalized(String name, Map<String, Object> row, JsonNode normalized) {
        if (normalized == null || normalized.isNull()) {
            return;
        }
        if (normalized.has("text")) {
            assertThat(row.get("normalized_text"))
                    .as("field %s normalized text", name)
                    .isEqualTo(normalized.get("text").asText());
        }
        if (normalized.has("number")) {
            assertThat((BigDecimal) row.get("normalized_number"))
                    .as("field %s normalized number", name)
                    .isEqualByComparingTo(normalized.get("number").asText());
        }
        if (normalized.has("date")) {
            assertThat(String.valueOf(row.get("normalized_date")))
                    .as("field %s normalized date", name)
                    .isEqualTo(normalized.get("date").asText());
        }
    }
}
