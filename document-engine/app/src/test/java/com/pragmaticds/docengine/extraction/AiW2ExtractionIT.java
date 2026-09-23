package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.ai.AiDocumentType;
import com.pragmaticds.docengine.platform.ai.AiExtractionRequest;
import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiExtractionStatus;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Confidence;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;
import com.pragmaticds.docengine.platform.ai.W2Extraction;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * W2 through the type-generic AI seam: its own gate, schema and prompt version, and the SAME
 * persist pipeline. A W-2 has no arithmetic identity, so an AI value is never promoted — it fills
 * a hole (anchored, NOT_VALIDATED), loses to a rules reading (WARNING + suggestion), and never
 * touches a human decision or the SSN.
 */
@Import(AiExtractionStageIT.AiTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.ai.enabled=true",
            "docengine.ai.w2.enabled=true",
            "docengine.ai.max-pages=10",
            "docengine.ai.max-input-characters=65536",
            "spring.main.allow-bean-definition-overriding=true"
        })
class AiW2ExtractionIT extends AbstractExtractionIT {

    @Autowired AiExtractionStageIT.CannedAiPort ai;

    @BeforeEach
    void resetAi() {
        ai.recorded().clear();
    }

    @Test
    void a_box_1_the_rules_missed_is_filled_by_the_ai_and_the_ssn_is_never_asked_for() {
        UUID packageId = seededW2("ai-w2-fill-it");
        UUID documentId = onlyDocumentOf(packageId);
        clearForAi(documentId, "wagesTipsOtherComp");
        PageRef page = firstPageOf(documentId);
        plantSpan(page, "WAGES TIPS OTHER COMP $61,538.72");
        Map<String, Object> ssnBefore = currentOccurrences(documentId).get("employeeSsn#");

        ai.respondWith(w2With(page.printedPage(), box1(page.printedPage(), "61538.72", "$61,538.72")));
        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.skipped()).isFalse();
        assertThat(ai.recorded()).hasSize(1);
        AiExtractionRequest request = ai.recorded().get(0);
        assertThat(request.documentType()).isEqualTo(AiDocumentType.W2);
        assertThat(request.outputSchemaJson())
                .contains("wagesTipsOtherComp")
                .doesNotContain("employeeSsn");

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);
        Map<String, Object> box1 = fields.get("wagesTipsOtherComp#");
        assertThat(box1.get("extraction_method")).isEqualTo("AI");
        assertThat(box1.get("normalized_number"))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("61538.72");
        // Located, not proved: nothing on a W-2 can vouch for it arithmetically.
        assertThat(box1.get("validation_status")).isEqualTo("NOT_VALIDATED");
        assertThat(String.valueOf(box1.get("normalized_json")))
                .contains("\"anchorStatus\": \"MATCHED\"", "NOT_APPLICABLE");

        // The SSN row is exactly what the rules wrote.
        assertThat(fields.get("employeeSsn#").get("extraction_method"))
                .isEqualTo(ssnBefore.get("extraction_method"))
                .isNotEqualTo("AI");
        assertThat(fields.get("employeeSsn#").get("displayed_text"))
                .isEqualTo(ssnBefore.get("displayed_text"));

        Map<String, Object> interpretation =
                jdbc.queryForMap(
                        "SELECT prompt_version FROM ai_interpretation"
                                + " WHERE subject_type = 'LOGICAL_DOCUMENT' AND subject_id = ?",
                        documentId);
        assertThat(interpretation.get("prompt_version")).isEqualTo("w2/1.0.0");
    }

    @Test
    void a_different_ai_reading_of_a_box_the_rules_read_is_a_conflict_never_an_overwrite() {
        UUID packageId = seededW2("ai-w2-conflict-it");
        UUID documentId = onlyDocumentOf(packageId);
        PageRef page = firstPageOf(documentId);
        Map<String, Object> before = currentOccurrences(documentId).get("wagesTipsOtherComp#");
        assertThat(before.get("extraction_method")).as("rules read Box 1").isNotEqualTo("NONE");
        assertThat(before.get("validation_status"))
                .as("rules-only baseline must not already be WARNING")
                .isNotEqualTo("WARNING");

        ai.respondWith(w2With(page.printedPage(), box1(page.printedPage(), "62538.72", "$62,538.72")));
        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.skipped()).isFalse();
        assertThat(ai.recorded()).hasSize(1);

        Map<String, Object> after = currentOccurrences(documentId).get("wagesTipsOtherComp#");
        assertThat(after.get("displayed_text")).isEqualTo(before.get("displayed_text"));
        assertThat(after.get("extraction_method")).isEqualTo(before.get("extraction_method"));
        assertThat(after.get("validation_status")).isEqualTo("WARNING");
    }

    @Test
    void a_human_corrected_box_1_keeps_the_reviewers_value() {
        UUID packageId = seededW2("ai-w2-reviewed-it");
        UUID documentId = onlyDocumentOf(packageId);
        PageRef page = firstPageOf(documentId);
        UUID rowId =
                jdbc.queryForObject(
                        "SELECT id FROM extracted_field WHERE logical_document_id = ?"
                                + " AND field_name = 'wagesTipsOtherComp' AND is_current",
                        UUID.class,
                        documentId);
        jdbc.update(
                "UPDATE extracted_field SET review_status = 'CORRECTED',"
                        + " displayed_text = '61,500.00', normalized_number = 61500.00"
                        + " WHERE id = ?",
                rowId);

        ai.respondWith(w2With(page.printedPage(), box1(page.printedPage(), "61538.72", "$61,538.72")));
        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.skipped()).isFalse();
        assertThat(ai.recorded()).hasSize(1);

        Map<String, Object> after = currentOccurrences(documentId).get("wagesTipsOtherComp#");
        assertThat(after.get("review_status")).isEqualTo("CORRECTED");
        assertThat(after.get("displayed_text")).isEqualTo("61,500.00");
        assertThat(after.get("normalized_number"))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("61500.00");
    }

    /**
     * W7: an AI reading that states EXACTLY the rules rows' own stored values — read back from
     * the database, never hard-coded from the fixture — must be recorded as AGREEMENT (silent,
     * no row touched) rather than CONFLICT (WARNING + suggestion). The name-field false-conflict
     * this pins: printed-caps names now round-trip through the parser byte-identical to the rules
     * normalizer's collapsed-whitespace, case-preserving reading, so a real all-caps W-2 name
     * does not manufacture a conflict on nearly every document.
     */
    @Test
    void an_ai_reading_that_equals_the_rules_reading_is_agreement_not_conflict() {
        UUID packageId = seededW2("ai-w2-agreement-it");
        UUID documentId = onlyDocumentOf(packageId);
        PageRef page = firstPageOf(documentId);

        Map<String, Map<String, Object>> before = currentOccurrences(documentId);
        String[] coordinates = {"employeeName#", "employerName#", "employerEin#", "taxYear#"};
        for (String coordinate : coordinates) {
            assertThat(before.get(coordinate))
                    .as("rules must have a current row for " + coordinate)
                    .isNotNull();
            assertThat(before.get(coordinate).get("extraction_method"))
                    .as("rules must have actually read " + coordinate)
                    .isNotEqualTo("NONE");
            assertThat(before.get(coordinate).get("normalized_text"))
                    .as("rules must have a stored normalized value for " + coordinate)
                    .isNotNull();
        }

        W2Extraction extraction =
                new W2Extraction(
                        equalReading(page.printedPage(), before.get("employeeName#")),
                        equalReading(page.printedPage(), before.get("employerName#")),
                        equalReading(page.printedPage(), before.get("employerEin#")),
                        equalReading(page.printedPage(), before.get("taxYear#")),
                        null,
                        null,
                        null,
                        null,
                        null);
        ai.respondWith(
                new AiExtractionResult(
                        "{}",
                        extraction,
                        "test",
                        "synthetic",
                        AiExtractionStatus.OK,
                        new AiTokenCounts(120, 42, 80, 40),
                        null));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.skipped()).isFalse();
        assertThat(ai.recorded()).hasSize(1);

        Map<String, Map<String, Object>> after = currentOccurrences(documentId);
        for (String coordinate : coordinates) {
            assertThat(after.get(coordinate).get("validation_status"))
                    .as(coordinate)
                    .isNotEqualTo("WARNING");
            assertThat(after.get(coordinate).get("displayed_text"))
                    .as(coordinate)
                    .isEqualTo(before.get(coordinate).get("displayed_text"));
        }
    }

    /** A TextCell that states the rules row's own stored normalized value, anchored by it. */
    private static TextCell equalReading(int page, Map<String, Object> rulesRow) {
        String value = (String) rulesRow.get("normalized_text");
        return new TextCell(value, value, page, Confidence.HIGH);
    }

    private UUID seededW2(String name) {
        UUID packageId = insertPackage(name);
        insertFixturePages(packageId, "w2_form");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    private void clearForAi(UUID documentId, String fieldName) {
        UUID schemaId =
                jdbc.queryForObject(
                        "SELECT schema_id FROM extracted_field WHERE logical_document_id = ?"
                                + " AND is_current LIMIT 1",
                        UUID.class,
                        documentId);
        jdbc.update(
                "DELETE FROM field_evidence WHERE extracted_field_id IN"
                        + " (SELECT id FROM extracted_field WHERE logical_document_id = ?"
                        + " AND field_name = ?)",
                documentId,
                fieldName);
        jdbc.update(
                "DELETE FROM extracted_field WHERE logical_document_id = ? AND field_name = ?",
                documentId,
                fieldName);
        jdbc.update(
                """
                INSERT INTO extracted_field
                    (id, org_id, logical_document_id, schema_id, field_name, data_type,
                     extraction_method, extractor_version, confidence, confidence_components,
                     validation_status, review_status, is_sensitive, is_current)
                VALUES (?, ?, ?, ?, ?, 'MONEY', 'NONE', 'deterministic/it',
                        0, '{"spanConfidence":0,"anchorStrength":0,"normalizerCertainty":0}'::jsonb,
                        'MANUAL_REVIEW_REQUIRED', 'NOT_REVIEWED', false, true)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                documentId,
                schemaId,
                fieldName);
    }

    private void plantSpan(PageRef page, String text) {
        Long spanId =
                jdbc.queryForObject(
                        "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id"
                                + " LIMIT 1",
                        Long.class,
                        page.id());
        jdbc.update("UPDATE text_span SET text = ? WHERE id = ?", text, spanId);
    }

    private static MoneyCell box1(int page, String value, String printed) {
        return new MoneyCell(new BigDecimal(value), printed, page, Confidence.HIGH);
    }

    /** A W-2 reading that states only Box 1 — every other cell null, so nothing else is touched. */
    private static AiExtractionResult w2With(int page, MoneyCell box1) {
        W2Extraction extraction =
                new W2Extraction(null, null, null, null, box1, null, null, null, null);
        return new AiExtractionResult(
                "{}",
                extraction,
                "test",
                "synthetic",
                AiExtractionStatus.OK,
                new AiTokenCounts(120, 42, 80, 40),
                null);
    }

    private PageRef firstPageOf(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT p.id, p.package_page_index + 1 AS printed_page"
                        + " FROM logical_document_page l JOIN page p ON p.id = l.page_id"
                        + " WHERE l.logical_document_id = ? ORDER BY l.ordinal LIMIT 1",
                (rs, row) -> new PageRef(rs.getObject("id", UUID.class), rs.getInt("printed_page")),
                documentId);
    }

    private record PageRef(UUID id, int printedPage) {}
}
