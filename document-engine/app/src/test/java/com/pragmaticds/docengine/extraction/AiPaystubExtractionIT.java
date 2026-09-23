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
import com.pragmaticds.docengine.platform.ai.PaystubExtraction;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * PAYSTUB through the type-generic AI seam: its own gate, its own schema and prompt-version
 * markers, the SAME persist pipeline — precedence, anchoring, and the trust posture. Anchoring
 * LOCATES a value; only the stub's own arithmetic PROVES one, so both halves are pinned here: a
 * stub that states no total deductions is UNPROVEN and promotes nothing, and a stub whose
 * gross-minus-deductions closes promotes exactly the fields that identity contains.
 * The flag-off state is pinned by {@code AiExtractionStageIT}'s non-bank skip test, whose
 * context leaves {@code docengine.ai.paystub.enabled} at its false default.
 */
@Import(AiExtractionStageIT.AiTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.ai.enabled=true",
            "docengine.ai.paystub.enabled=true",
            "docengine.ai.max-pages=10",
            "docengine.ai.max-input-characters=65536",
            "spring.main.allow-bean-definition-overriding=true"
        })
class AiPaystubExtractionIT extends AbstractExtractionIT {

    @Autowired AiExtractionStageIT.CannedAiPort ai;

    @BeforeEach
    void resetAi() {
        ai.recorded().clear();
    }

    @Test
    void a_paystub_document_rides_its_own_dialect_and_lands_without_arithmetic_assertion() {
        UUID packageId = insertPackage("ai-paystub-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        // Force one field back to a MISSING deterministic row so the AI value has a hole to
        // fill, and plant the printed evidence its anchor must find.
        UUID paystubSchemaId =
                jdbc.queryForObject(
                        "SELECT schema_id FROM extracted_field WHERE logical_document_id = ?"
                                + " AND is_current LIMIT 1",
                        UUID.class,
                        documentId);
        jdbc.update(
                "DELETE FROM field_evidence WHERE extracted_field_id IN"
                        + " (SELECT id FROM extracted_field WHERE logical_document_id = ?"
                        + " AND field_name = 'federalWithholding')",
                documentId);
        jdbc.update(
                "DELETE FROM extracted_field WHERE logical_document_id = ?"
                        + " AND field_name = 'federalWithholding'",
                documentId);
        jdbc.update(
                """
                INSERT INTO extracted_field
                    (id, org_id, logical_document_id, schema_id, field_name, data_type,
                     extraction_method, extractor_version, confidence, confidence_components,
                     validation_status, review_status, is_sensitive, is_current)
                VALUES (?, ?, ?, ?, 'federalWithholding', 'MONEY', 'NONE', 'deterministic/it',
                        0, '{"spanConfidence":0,"anchorStrength":0,"normalizerCertainty":0}'::jsonb,
                        'MANUAL_REVIEW_REQUIRED', 'NOT_REVIEWED', false, true)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                documentId,
                paystubSchemaId);
        PageRef page = firstPageOf(documentId);
        Long spanId =
                jdbc.queryForObject(
                        "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id"
                                + " LIMIT 1",
                        Long.class,
                        page.id());
        jdbc.update(
                "UPDATE text_span SET text = 'FEDERAL TAX WITHHELD $321.09' WHERE id = ?", spanId);

        String originalBorrowerName =
                jdbc.queryForObject(
                        "SELECT displayed_text FROM extracted_field WHERE logical_document_id = ?"
                                + " AND field_name = 'borrowerName' AND is_current",
                        String.class,
                        documentId);
        ai.respondWith(paystubExtraction(page.printedPage()));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.skipped()).isFalse();
        assertThat(ai.recorded()).hasSize(1);
        AiExtractionRequest request = ai.recorded().get(0);
        assertThat(request.documentType()).isEqualTo(AiDocumentType.PAYSTUB);
        assertThat(request.outputSchemaJson()).contains("payFrequency").doesNotContain("checks");

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);
        // The deterministic FOUND row wins: the AI's different reading becomes a conflict
        // suggestion, never an overwrite — precedence is inherited from the shared persist.
        assertThat(fields.get("borrowerName#").get("displayed_text"))
                .isEqualTo(originalBorrowerName);
        assertThat(fields.get("borrowerName#").get("validation_status")).isEqualTo("WARNING");

        Map<String, Object> withheld = fields.get("federalWithholding#");
        assertThat(withheld.get("extraction_method")).isEqualTo("AI");
        assertThat(withheld.get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("321.09");
        // Anchored AND unflagged — and still NOT_VALIDATED. federalWithholding is ONE named
        // deduction inside the total, so the current-period net identity does not vouch for it
        // whatever that identity does; and this fixture states no total and no deduction table,
        // so the identity is UNPROVEN rather than either passed or disproved.
        assertThat(withheld.get("validation_status")).isEqualTo("NOT_VALIDATED");
        assertThat(String.valueOf(withheld.get("normalized_json")))
                .contains(
                        "\"anchorStatus\": \"MATCHED\"",
                        "\"status\": \"UNPROVEN\"",
                        "\"currentNetIdentity\": \"UNPROVEN\"",
                        // The distinction that has to survive to a reviewer: no deductions table
                        // was read at all, which is not the same as one that failed to sum.
                        "\"deductionsPartition\": \"NOT_CLAIMED\"");

        Map<String, Object> interpretation =
                jdbc.queryForMap(
                        "SELECT prompt_version, interpretation FROM ai_interpretation"
                                + " WHERE subject_type = 'LOGICAL_DOCUMENT' AND subject_id = ?",
                        documentId);
        assertThat(interpretation.get("prompt_version")).isEqualTo("paystub/1.1.0");
        assertThat(String.valueOf(interpretation.get("interpretation")))
                .contains("PROVIDER_CALL", "paystub/1.1.0", "UNPROVEN");
    }

    /**
     * The other half of the trust contract: when the stub's own {@code gross - deductions = net}
     * closes, the two persisted fields that identity CONTAINS earn VALID — and nothing else does.
     * Without this case, a reconciler that never promoted anything would still look green.
     */
    @Test
    void a_paystub_whose_arithmetic_closes_promotes_the_fields_that_identity_contains() {
        UUID packageId = insertPackage("ai-paystub-reconciled-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        UUID paystubSchemaId =
                jdbc.queryForObject(
                        "SELECT schema_id FROM extracted_field WHERE logical_document_id = ?"
                                + " AND is_current LIMIT 1",
                        UUID.class,
                        documentId);
        clearForAi(documentId, paystubSchemaId, "currentGrossPay");
        clearForAi(documentId, paystubSchemaId, "netPay");

        PageRef page = firstPageOf(documentId);
        // Two spans of planted evidence, because an unanchored value is review-flagged before the
        // reconciliation is ever consulted — the promotion must be proved on anchored values.
        java.util.List<Long> spanIds =
                jdbc.queryForList(
                        "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id"
                                + " LIMIT 2",
                        Long.class,
                        page.id());
        jdbc.update(
                "UPDATE text_span SET text = 'GROSS PAY $2,400.00' WHERE id = ?", spanIds.get(0));
        jdbc.update("UPDATE text_span SET text = 'NET PAY $1,850.25' WHERE id = ?", spanIds.get(1));

        ai.respondWith(balancingPaystub(page.printedPage()));

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);
        assertThat(fields.get("currentGrossPay#").get("validation_status")).isEqualTo("VALID");
        assertThat(fields.get("netPay#").get("validation_status")).isEqualTo("VALID");
        assertThat(String.valueOf(fields.get("netPay#").get("normalized_json")))
                .contains("\"status\": \"RECONCILED\"", "\"currentNetIdentity\": \"PASS\"");
    }

    /**
     * paystub@1.5.0 (V53): the earnings lines the dialect already reads land as one {@code
     * earning*} occurrence per line under the ROW group's two-digit key, the three closing totals
     * land as scalars, a null cell lands NOTHING (missing over wrong), and the deterministic
     * "region not read" placeholder each line field carried is retired once its rows exist — so
     * the Markdown a consumer reads shows an earnings table and no manual-review callout for it.
     */
    @Test
    void earnings_lines_and_closing_totals_persist_under_row_keys_and_retire_the_placeholder()
            throws Exception {
        UUID packageId = insertPackage("ai-paystub-lines-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        Map<String, Map<String, Object>> before = currentOccurrences(documentId);
        // Deterministically the new fields are honest MISSING rows: the totals ungrouped, and
        // each line field ONE null-keyed placeholder (no rule rung can address the table).
        for (String placeholder :
                java.util.List.of(
                        "currentTotalDeductions#",
                        "ytdTotalDeductions#",
                        "ytdNetPay#",
                        "earningDescription#",
                        "earningHours#",
                        "earningRate#",
                        "earningCurrentAmount#",
                        "earningYtdAmount#")) {
            assertThat(before.get(placeholder))
                    .as("deterministic row for %s", placeholder)
                    .isNotNull()
                    .containsEntry("extraction_method", "NONE");
        }
        // The emission cap and the schema's maxRows are one number, or a line could be keyed
        // past the group the schema declares.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT (f -> 'group' ->> 'maxRows')::int FROM extraction_schema s,"
                                        + " jsonb_array_elements(s.definition -> 'fields') f"
                                        + " WHERE s.org_id IS NULL AND s.is_active"
                                        + " AND s.document_type_code = 'PAYSTUB'"
                                        + " AND f ->> 'name' = 'earningDescription'",
                                Integer.class))
                .isEqualTo(
                        com.pragmaticds.docengine.ai.AiExtractionStageService.PAYSTUB_EARNINGS_MAX_ROWS);

        int page = firstPageOf(documentId).printedPage();
        ai.respondWith(paystubWithEarningsLines(page));

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);

        // Line 01, every cell read.
        assertThat(fields.get("earningDescription#01"))
                .containsEntry("extraction_method", "AI")
                .containsEntry("data_type", "STRING")
                .containsEntry("group_key", "01")
                .containsEntry("normalized_text", "Regular");
        assertThat(fields.get("earningHours#01").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("80.00");
        assertThat(fields.get("earningRate#01").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("25.00");
        assertThat(fields.get("earningCurrentAmount#01").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("2000.00");
        assertThat(fields.get("earningYtdAmount#01").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("46000.00");
        // Line 02 prints no hours and no rate: the ROW exists, so those cells are explicit
        // MISSING occurrences (method NONE, no value, zero confidence) — the same shape the
        // deterministic engine writes for a located row's blank cell — never a fabricated 0.00
        // and never silence, which would leave the group ragged and split the rendered table.
        assertThat(fields.get("earningDescription#02"))
                .containsEntry("group_key", "02")
                .containsEntry("normalized_text", "Overtime");
        for (String empty : java.util.List.of("earningHours#02", "earningRate#02")) {
            assertThat(fields.get(empty))
                    .as(empty)
                    .isNotNull()
                    .containsEntry("extraction_method", "NONE")
                    .containsEntry("group_key", "02")
                    .containsEntry("displayed_text", null)
                    .containsEntry("normalized_number", null)
                    .containsEntry("validation_status", "MANUAL_REVIEW_REQUIRED");
            assertThat(scaled(fields.get(empty).get("confidence"))).isEqualByComparingTo("0");
        }
        assertThat(fields.get("earningYtdAmount#02").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("1000.00");
        assertThat(fields).doesNotContainKeys("earningDescription#03");

        // The null-keyed placeholders are gone for every line field that gained rows, while
        // the read model keeps exactly the rows that were read.
        assertThat(fields)
                .doesNotContainKeys(
                        "earningDescription#",
                        "earningHours#",
                        "earningRate#",
                        "earningCurrentAmount#",
                        "earningYtdAmount#");

        // The totals: two read, one (ytdNetPay) not printed, whose MISSING row stays honest.
        assertThat(fields.get("currentTotalDeductions#"))
                .containsEntry("extraction_method", "AI")
                .containsEntry("group_key", null);
        assertThat(fields.get("currentTotalDeductions#").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("549.75");
        assertThat(fields.get("ytdTotalDeductions#").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("9876.54");
        assertThat(fields.get("ytdNetPay#")).containsEntry("extraction_method", "NONE");

        // The ten legacy scalars plus three totals plus a rectangular 2x5 of line cells is the
        // whole current set — and every occurrence cites the schema that decided.
        assertThat(fields).hasSize(10 + 3 + 10);
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT s.version FROM extracted_field f"
                                        + " JOIN extraction_schema s ON s.id = f.schema_id"
                                        + " WHERE f.logical_document_id = ? AND f.is_current",
                                String.class,
                                documentId))
                .containsExactly("1.5.0");

        // What a consumer sees: an earnings table, and no "region not read" callout for it.
        String markdown =
                mockMvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/v1/documents/{id}/fields.md", documentId))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(markdown)
                .contains("## Earning 01–02 (2) — ROW group")
                .contains(
                        "| Key | `earningCurrentAmount` | `earningDescription` | `earningHours` |"
                                + " `earningRate` | `earningYtdAmount` |")
                .contains("| 01 | ")
                .contains("| 02 | ")
                .contains("— missing (review)")
                .doesNotContain("Earning Hours")
                .doesNotContain("Region not read");
    }

    /**
     * A cell the parser kept but could not corroborate — {@code PaystubExtractionParser.money()}
     * returns a NON-null cell whose {@code value} is null when the printed text fails to back the
     * number — is still an empty cell of a row that exists. It must land as the same keyed
     * MISSING occurrence a null cell does, or that one field loses the key, the key sequence
     * diverges, and the renderer splits the earnings table (the ragged-group failure
     * MarkdownDocumentRendererTest pins). Same rule for a text cell whose value is null.
     */
    @Test
    void an_uncorroborated_cell_of_a_read_line_is_a_missing_occurrence_not_a_hole()
            throws Exception {
        UUID packageId = insertPackage("ai-paystub-uncorroborated-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        int page = firstPageOf(documentId).printedPage();

        ai.respondWith(
                paystubWith(
                        page,
                        java.util.List.of(
                                line(page, "Shift Differential", "40.00", "30.00", "1200.00", "4800.00"),
                                new PaystubExtraction.EarningLine(
                                        // present, value null: the text parser's own empty cell
                                        new TextCell(null, "", page, Confidence.LOW),
                                        // present, uncorroborated: number dropped, text kept
                                        new MoneyCell(null, "eighty", page, Confidence.LOW),
                                        // present, nothing at all
                                        new MoneyCell(null, null, page, Confidence.LOW),
                                        money(page, "600.00"),
                                        money(page, "2400.00")))));

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);
        for (String empty :
                java.util.List.of("earningDescription#02", "earningHours#02", "earningRate#02")) {
            assertThat(fields.get(empty))
                    .as("%s is a keyed MISSING occurrence, not a hole", empty)
                    .isNotNull()
                    .containsEntry("extraction_method", "NONE")
                    .containsEntry("group_key", "02")
                    .containsEntry("normalized_number", null)
                    .containsEntry("normalized_text", null);
        }
        // Every line field carries every key: the group is rectangular.
        for (String field :
                java.util.List.of(
                        "earningDescription",
                        "earningHours",
                        "earningRate",
                        "earningCurrentAmount",
                        "earningYtdAmount")) {
            assertThat(fields).containsKeys(field + "#01", field + "#02");
            assertThat(fields).doesNotContainKey(field + "#");
        }

        assertThat(fieldsMarkdown(documentId))
                .contains("## Earning 01–02 (2) — ROW group")
                .doesNotContain("Earning Description")
                .doesNotContain("Earning Hours")
                .doesNotContain("Earning Rate")
                .doesNotContain("Region not read");
    }

    /**
     * The placeholder is a real row a human may already have filled in by hand — this stage's
     * own precedence comment says so — and deleting it would destroy that value silently:
     * {@code ReviewCarryForward.apply(before, List.of())} returns 0 without rebinding anything. So
     * a placeholder whose review status is anything but NOT_REVIEWED SURVIVES the AI run, and the
     * keyed rows that now sit beside it come out needing a recheck, because the human's call was
     * made against a table the machine has since read differently.
     */
    @Test
    void a_reviewed_placeholder_survives_the_ai_run_and_its_keyed_rows_come_back_for_recheck() {
        UUID packageId = insertPackage("ai-paystub-reviewed-placeholder-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        PageRef page = firstPageOf(documentId);

        // A reviewer typed a value into the null-keyed earningDescription placeholder.
        UUID placeholderId =
                jdbc.queryForObject(
                        "SELECT id FROM extracted_field WHERE logical_document_id = ?"
                                + " AND field_name = 'earningDescription' AND group_key IS NULL"
                                + " AND is_current",
                        UUID.class,
                        documentId);
        jdbc.update(
                "UPDATE extracted_field SET review_status = 'CORRECTED',"
                        + " displayed_text = 'Base Salary', normalized_text = 'Base Salary'"
                        + " WHERE id = ?",
                placeholderId);

        // Planted evidence so the keyed descriptions ANCHOR: an unanchored value is
        // review-flagged on its own, which would mask the recheck this test is about.
        java.util.List<Long> spanIds =
                jdbc.queryForList(
                        "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id"
                                + " LIMIT 2",
                        Long.class,
                        page.id());
        jdbc.update("UPDATE text_span SET text = 'Shift Differential' WHERE id = ?", spanIds.get(0));
        jdbc.update("UPDATE text_span SET text = 'Holiday Premium' WHERE id = ?", spanIds.get(1));

        int printed = page.printedPage();
        ai.respondWith(
                paystubWith(
                        printed,
                        java.util.List.of(
                                line(printed, "Shift Differential", "40.00", "30.00", "1200.00", "4800.00"),
                                line(printed, "Holiday Premium", "8.00", "45.00", "360.00", "1440.00"))));

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);
        // The reviewed placeholder is still there, value intact.
        assertThat(fields.get("earningDescription#"))
                .as("reviewed placeholder survives")
                .isNotNull()
                .containsEntry("id", placeholderId)
                .containsEntry("review_status", "CORRECTED")
                .containsEntry("displayed_text", "Base Salary");
        // Its keyed rows landed, anchored, and were sent back for a recheck.
        for (String keyed : java.util.List.of("earningDescription#01", "earningDescription#02")) {
            assertThat(fields.get(keyed))
                    .as(keyed)
                    .isNotNull()
                    .containsEntry("extraction_method", "AI")
                    .containsEntry("validation_status", "MANUAL_REVIEW_REQUIRED");
            assertThat(String.valueOf(fields.get(keyed).get("normalized_json")))
                    .contains("\"anchorStatus\": \"MATCHED\"");
        }
        // An UNreviewed placeholder is retired as before — the survival is about the review,
        // not the field.
        assertThat(fields).doesNotContainKey("earningHours#");
        assertThat(fields.get("earningHours#01")).containsEntry("extraction_method", "AI");
    }

    /**
     * The emission cap is the schema's {@code maxRows}: line 41 has no coordinate to land on. It
     * is dropped with ONE warning that carries counts and nothing else — never re-keyed onto a
     * row that exists, never silently.
     */
    @Test
    void lines_past_the_row_group_cap_are_dropped_with_one_warning_and_never_re_keyed() {
        UUID packageId = insertPackage("ai-paystub-cap-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        int page = firstPageOf(documentId).printedPage();

        int cap = com.pragmaticds.docengine.ai.AiExtractionStageService.PAYSTUB_EARNINGS_MAX_ROWS;
        java.util.List<PaystubExtraction.EarningLine> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= cap + 1; i++) {
            lines.add(line(page, "Line " + i, "1.00", "1.00", "10.00", "100.00"));
        }
        ai.respondWith(paystubWith(page, lines));

        ch.qos.logback.classic.Logger stageLog =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(
                                com.pragmaticds.docengine.ai.AiExtractionStageService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        stageLog.addAppender(captured);
        try {
            assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();
        } finally {
            stageLog.detachAppender(captured);
        }

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);
        assertThat(fields.get("earningDescription#" + "%02d".formatted(cap)))
                .containsEntry("normalized_text", "Line " + cap);
        assertThat(fields).doesNotContainKey("earningDescription#" + "%02d".formatted(cap + 1));
        assertThat(fields.keySet().stream().filter(key -> key.startsWith("earningDescription#")))
                .hasSize(cap);
        // Nothing of line 41 landed anywhere: no key carries its caption.
        assertThat(fields.values().stream().map(row -> row.get("normalized_text")))
                .doesNotContain("Line " + (cap + 1));

        java.util.List<String> warnings =
                captured.list.stream()
                        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("row group cap"))
                        .toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("lines=" + (cap + 1), "cap=" + cap)
                // Counts only — no caption, no amount, nothing from the stub itself.
                .doesNotContain("Line ", "10.00", "100.00");
    }

    private String fieldsMarkdown(UUID documentId) throws Exception {
        return mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/documents/{id}/fields.md", documentId))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static MoneyCell money(int page, String amount) {
        return new MoneyCell(new BigDecimal(amount), amount, page, Confidence.HIGH);
    }

    private static PaystubExtraction.EarningLine line(
            int page, String description, String hours, String rate, String current, String ytd) {
        return new PaystubExtraction.EarningLine(
                new TextCell(description, description, page, Confidence.HIGH),
                money(page, hours),
                money(page, rate),
                money(page, current),
                money(page, ytd));
    }

    /** A stub that states only its earnings lines — no scalars, no totals, no deductions. */
    private static AiExtractionResult paystubWith(
            int page, java.util.List<PaystubExtraction.EarningLine> lines) {
        PaystubExtraction extraction =
                new PaystubExtraction(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, lines, java.util.List.of());
        return new AiExtractionResult(
                "{}",
                extraction,
                "test",
                "synthetic",
                AiExtractionStatus.OK,
                new AiTokenCounts(120, 42, 80, 40),
                null);
    }

    /** Two earnings rows (the second without hours or rate) and two of the three totals. */
    private static AiExtractionResult paystubWithEarningsLines(int page) {
        PaystubExtraction extraction =
                new PaystubExtraction(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new MoneyCell(new BigDecimal("549.75"), "$549.75", page, Confidence.HIGH),
                        new MoneyCell(
                                new BigDecimal("9876.54"), "$9,876.54", page, Confidence.HIGH),
                        null,
                        java.util.List.of(
                                new PaystubExtraction.EarningLine(
                                        new TextCell("Regular", "Regular", page, Confidence.HIGH),
                                        new MoneyCell(
                                                new BigDecimal("80.00"), "80.00", page, Confidence.HIGH),
                                        new MoneyCell(
                                                new BigDecimal("25.00"), "25.00", page, Confidence.HIGH),
                                        new MoneyCell(
                                                new BigDecimal("2000.00"),
                                                "$2,000.00",
                                                page,
                                                Confidence.HIGH),
                                        new MoneyCell(
                                                new BigDecimal("46000.00"),
                                                "$46,000.00",
                                                page,
                                                Confidence.HIGH)),
                                new PaystubExtraction.EarningLine(
                                        new TextCell("Overtime", "Overtime", page, Confidence.HIGH),
                                        null,
                                        null,
                                        new MoneyCell(
                                                new BigDecimal("0.00"), "$0.00", page, Confidence.HIGH),
                                        new MoneyCell(
                                                new BigDecimal("1000.00"),
                                                "$1,000.00",
                                                page,
                                                Confidence.HIGH))),
                        java.util.List.of());
        return new AiExtractionResult(
                "{}",
                extraction,
                "test",
                "synthetic",
                AiExtractionStatus.OK,
                new AiTokenCounts(120, 42, 80, 40),
                null);
    }

    /** Reduce one field to a MISSING deterministic row so the AI value has a hole to fill. */
    private void clearForAi(UUID documentId, UUID schemaId, String fieldName) {
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

    /** 2400.00 gross less a stated 549.75 of deductions leaves 1850.25 — an identity that closes. */
    private static AiExtractionResult balancingPaystub(int page) {
        PaystubExtraction extraction =
                new PaystubExtraction(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new MoneyCell(new BigDecimal("2400.00"), "$2,400.00", page, Confidence.HIGH),
                        null,
                        new MoneyCell(new BigDecimal("1850.25"), "$1,850.25", page, Confidence.HIGH),
                        null,
                        new MoneyCell(new BigDecimal("549.75"), "$549.75", page, Confidence.HIGH),
                        null,
                        null,
                        java.util.List.of(),
                        java.util.List.of());
        return new AiExtractionResult(
                "{}",
                extraction,
                "test",
                "synthetic",
                AiExtractionStatus.OK,
                new AiTokenCounts(120, 42, 80, 40),
                null);
    }

    private static AiExtractionResult paystubExtraction(int page) {
        PaystubExtraction extraction =
                new PaystubExtraction(
                        new TextCell("Someone Else", "SOMEONE ELSE", page, Confidence.HIGH),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new MoneyCell(
                                new BigDecimal("321.09"), "$321.09", page, Confidence.HIGH));
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
