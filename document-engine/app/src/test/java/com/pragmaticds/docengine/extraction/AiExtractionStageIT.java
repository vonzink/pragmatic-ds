package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.platform.ai.AiExtractionPort;
import com.pragmaticds.docengine.platform.ai.AiExtractionRequest;
import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiExtractionStatus;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Check;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Confidence;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Direction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Summary;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Txn;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Phase 3's package seam: linked-page input, precedence, and grouped AI occurrences. */
@Import(AiExtractionStageIT.AiTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.ai.enabled=true",
            "docengine.ai.max-pages=10",
            "docengine.ai.max-input-characters=65536",
            "spring.main.allow-bean-definition-overriding=true"
        })
class AiExtractionStageIT extends AbstractExtractionIT {

    @Autowired CannedAiPort ai;

    @BeforeEach
    void resetAi() {
        ai.requests.clear();
        ai.respondWith(successfulExtraction());
    }

    @Test
    void enriches_only_missing_fields_and_persists_grouped_occurrences_from_all_linked_pages() {
        UUID packageId = insertPackage("ai-bank-stage-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        UUID bankSchemaId =
                jdbc.queryForObject(
                        "SELECT schema_id FROM extracted_field WHERE logical_document_id = ? LIMIT 1",
                        UUID.class,
                        documentId);
        UUID unrelatedSchemaId =
                jdbc.queryForObject(
                        "SELECT id FROM extraction_schema WHERE document_type_code = 'W2' AND is_active LIMIT 1",
                        UUID.class);
        UUID missingId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO extracted_field
                    (id, org_id, logical_document_id, schema_id, field_name, data_type,
                     extraction_method, extractor_version, confidence, confidence_components,
                     validation_status, review_status, is_sensitive, is_current)
                VALUES (?, ?, ?, ?, 'accountHolderAddress', 'STRING', 'NONE', 'deterministic/it',
                        0, '{"spanConfidence":0,"anchorStrength":0,"normalizerCertainty":0}'::jsonb,
                        'MANUAL_REVIEW_REQUIRED', 'NOT_REVIEWED', true, true)
                """,
                missingId,
                ORG_DEV,
                documentId,
                unrelatedSchemaId);

        UUID continuationPage =
                jdbc.queryForObject(
                        "SELECT page_id FROM logical_document_page WHERE logical_document_id = ? ORDER BY ordinal DESC LIMIT 1",
                        UUID.class,
                        documentId);
        jdbc.update(
                "UPDATE text_span SET text = text || ' CONTINUATION_SENTINEL' WHERE page_id = ? AND ordinal = 0",
                continuationPage);
        jdbc.update(
                "UPDATE classification_result SET document_type_code = 'UNKNOWN'"
                        + " WHERE subject_type = 'PAGE' AND subject_id = ? AND is_current",
                continuationPage);
        jdbc.update(
                """
                INSERT INTO layout_element
                    (id, org_id, page_id, element_type, ordinal, x, y, width, height,
                     confidence, detector, detector_version, attributes, text)
                VALUES (?, ?, ?, 'TABLE_CELL', 10000, 1, 1, 10, 10, 0.9,
                        'it', 'it', '{"row":0,"col":0}'::jsonb, 'TABLE_SENTINEL')
                """,
                UUID.randomUUID(),
                ORG_DEV,
                continuationPage);

        String originalBankName =
                jdbc.queryForObject(
                        "SELECT displayed_text FROM extracted_field WHERE logical_document_id = ? AND field_name = 'bankName' AND is_current",
                        String.class,
                        documentId);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.skipped()).isFalse();
        assertThat(ai.requests).hasSize(1);
        AiExtractionRequest request = ai.requests.get(0);
        // Phase F gate off (this context's default): text-only, no pixels ever.
        assertThat(request.pageImages()).isEmpty();
        assertThat(request.documentText()).contains("PAGE 1", "PAGE 2", "PAGE 3", "CONTINUATION_SENTINEL");
        assertThat(request.tableStructureText()).contains("PAGE 3", "TABLE_CELL", "TABLE_SENTINEL");

        Map<String, Map<String, Object>> fields = currentOccurrences(documentId);
        assertThat(fields.get("bankName#").get("displayed_text")).isEqualTo(originalBankName);
        assertThat(fields.get("bankName#").get("validation_status")).isEqualTo("WARNING");

        Map<String, Object> address = fields.get("accountHolderAddress#");
        assertThat(address.get("id")).isNotEqualTo(missingId);
        assertThat(address.get("extraction_method")).isEqualTo("AI");
        assertThat(address.get("displayed_text")).isEqualTo("123 Main Street");
        assertThat(address.get("schema_id")).isEqualTo(bankSchemaId);
        assertThat(address.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(String.valueOf(address.get("normalized_json"))).contains("page", "123 Main Street");

        assertThat(fields.get("transactionDescription#000001").get("extraction_method"))
                .isEqualTo("AI");
        assertThat(fields.get("transactionAmount#000001").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("42.50");
        assertThat(fields.get("checkNumber#1042").get("displayed_text")).isEqualTo("1042");
        assertThat(fields.get("checkAmount#1042").get("normalized_number"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo("25.00");

        Map<String, Object> interpretation =
                jdbc.queryForMap(
                        "SELECT provider, model, prompt_version, tokens_in, tokens_out, interpretation"
                                + " FROM ai_interpretation WHERE subject_type = 'LOGICAL_DOCUMENT'"
                                + " AND subject_id = ?",
                        documentId);
        assertThat(interpretation.get("provider")).isEqualTo("test");
        assertThat(interpretation.get("model")).isEqualTo("synthetic");
        assertThat(interpretation.get("prompt_version")).isEqualTo("bank-statement/1.1.1");
        assertThat(interpretation.get("tokens_in")).isEqualTo(240);
        assertThat(interpretation.get("tokens_out")).isEqualTo(42);
        assertThat(String.valueOf(interpretation.get("interpretation")))
                .contains(
                        "PROVIDER_CALL",
                        "bankName",
                        "Different Bank",
                        "anchorStatus",
                        "evidence");
    }

    @Test
    void anchored_reconciled_ledger_persists_value_evidence_and_validates_the_set() {
        UUID packageId = insertPackage("ai-reconciled-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        PageRef page = firstPageOf(documentId);
        replaceFirstTwoSpans(
                page.id(),
                "12/30/2099 SYNTHETIC DEPOSIT ALPHA $1,234.56 $11,234.56",
                "12/31/2099 SYNTHETIC WITHDRAWAL OMEGA $234.56 $11,000.00");
        ai.next = reconciledExtraction(page.printedPage(), false);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?"
                                        + " AND field_name LIKE 'transaction%'"
                                        + " AND validation_status = 'VALID'",
                                Integer.class,
                                documentId))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM field_evidence e JOIN extracted_field f"
                                        + " ON f.id = e.extracted_field_id"
                                        + " WHERE f.logical_document_id = ?"
                                        + " AND f.field_name LIKE 'transaction%'",
                                Integer.class,
                                documentId))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT normalized_json::text FROM extracted_field"
                                        + " WHERE logical_document_id = ?"
                                        + " AND field_name = 'transactionAmount'"
                                        + " AND group_key = '000001'",
                                String.class,
                                documentId))
                .contains("MATCHED", "RECONCILED");
    }

    /**
     * The confidence contract, asserted as an INVARIANT over every persisted AI row rather than
     * against one fixture's expected numbers: a row's score must BE the product of the three
     * components that row publishes. Written this way on purpose — the defect it guards was not a
     * wrong number but two implementations of one formula drifting apart. This path stored the
     * model's own self-report as the score and recorded spanConfidence and anchorStrength beside it
     * without ever multiplying them in, so a value with zero of both still reported 0.90 and the
     * review panel showed "90% confidence" next to "Unanchored". A test pinned to fixture values
     * would have agreed with whichever formula was in the code; this one cannot.
     */
    @Test
    void every_ai_row_scores_the_product_of_its_own_published_components() {
        UUID packageId = insertPackage("ai-confidence-contract-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        PageRef page = firstPageOf(documentId);
        replaceFirstTwoSpans(
                page.id(),
                "12/30/2099 SYNTHETIC DEPOSIT ALPHA $1,234.56 $11,234.56",
                "12/31/2099 SYNTHETIC WITHDRAWAL OMEGA $234.56 $11,000.00");
        ai.next = reconciledExtraction(page.printedPage(), false);

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field"
                                        + " WHERE logical_document_id = ?"
                                        + " AND extraction_method = 'AI'"
                                        + " AND (confidence_components->>'spanConfidence' IS NULL"
                                        + "  OR confidence_components->>'anchorStrength' IS NULL"
                                        + "  OR confidence_components->>'normalizerCertainty' IS NULL"
                                        + "  OR confidence <> round("
                                        + "       (confidence_components->>'spanConfidence')::numeric"
                                        + "     * (confidence_components->>'anchorStrength')::numeric"
                                        + "     * (confidence_components->>'normalizerCertainty')::numeric,"
                                        + "     4))",
                                Integer.class,
                                documentId))
                .as("rows whose score is not the product of their own published components")
                .isZero();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field"
                                        + " WHERE logical_document_id = ?"
                                        + " AND extraction_method = 'AI'"
                                        + " AND confidence_components->>'anchorStatus' <> 'MATCHED'"
                                        + " AND confidence <> 0",
                                Integer.class,
                                documentId))
                .as("nothing without a matched anchor may report any confidence at all")
                .isZero();

        // Not vacuous: the document really does persist AI rows that score above zero, so the two
        // assertions above are checking a populated table rather than an empty one.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field"
                                        + " WHERE logical_document_id = ?"
                                        + " AND extraction_method = 'AI' AND confidence > 0",
                                Integer.class,
                                documentId))
                .isPositive();
    }

    @Test
    void non_reconciling_ledger_remains_visible_but_every_row_requires_review() {
        UUID packageId = insertPackage("ai-non-reconciling-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        PageRef page = firstPageOf(documentId);
        replaceFirstTwoSpans(
                page.id(),
                "12/30/2099 SYNTHETIC DEPOSIT ALPHA $1,234.56 $11,234.56",
                "12/31/2099 SYNTHETIC WITHDRAWAL OMEGA $234.56 $10,999.99");
        ai.next = reconciledExtraction(page.printedPage(), true);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?"
                                        + " AND field_name LIKE 'transaction%'"
                                        + " AND validation_status = 'MANUAL_REVIEW_REQUIRED'",
                                Integer.class,
                                documentId))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT normalized_json::text FROM extracted_field"
                                        + " WHERE logical_document_id = ?"
                                        + " AND field_name = 'transactionBalance'"
                                        + " AND group_key = '000002'",
                                String.class,
                                documentId))
                .contains("MATCHED", "NON_RECONCILING");
    }

    @Test
    void provider_error_leaves_deterministic_rows_untouched() {
        UUID packageId = insertPackage("ai-error-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        List<Map<String, Object>> before =
                jdbc.queryForList(
                        "SELECT field_name, displayed_text, extraction_method, validation_status"
                                + " FROM extracted_field WHERE logical_document_id = ? AND is_current"
                                + " ORDER BY field_name",
                        documentId);
        ai.next =
                new AiExtractionResult(
                        null,
                        null,
                        "test",
                        "synthetic",
                        AiExtractionStatus.ERROR,
                        AiTokenCounts.ZERO,
                        "synthetic_error");

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_EXTRACTION_FAILED);
        assertThat(outcome.retryable()).isFalse();
        assertThat(
                        jdbc.queryForList(
                                "SELECT field_name, displayed_text, extraction_method, validation_status"
                                        + " FROM extracted_field WHERE logical_document_id = ? AND is_current"
                                        + " ORDER BY field_name",
                                documentId))
                .isEqualTo(before);
    }

    @Test
    void transient_provider_error_remains_retryable() {
        UUID packageId = insertPackage("ai-transient-error-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        ai.next =
                new AiExtractionResult(
                        null,
                        null,
                        "test",
                        "synthetic",
                        AiExtractionStatus.ERROR,
                        AiTokenCounts.ZERO,
                        "provider_transient");

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void oversized_document_is_rejected_before_any_provider_call_and_is_not_retryable() {
        UUID packageId = insertPackage("ai-input-limit-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        UUID firstPage = firstPageOf(documentId).id();
        Long firstSpan =
                jdbc.queryForObject(
                        "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id LIMIT 1",
                        Long.class,
                        firstPage);
        jdbc.update("UPDATE text_span SET text = ? WHERE id = ?", "X".repeat(65_537), firstSpan);
        ai.requests.clear();

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.detail()).containsEntry("reason", "INPUT_LIMIT_EXCEEDED");
        assertThat(ai.requests).isEmpty();
    }

    // ── Issue #58: per-document isolation ───────────────────────────────────

    /**
     * The whole bug: one sibling's provider failure used to unwind every document that had
     * already extracted, re-recording them NOT_APPLIED and persisting nothing. Now a failure
     * costs exactly the document it happened to.
     */
    @Test
    void a_failed_sibling_does_not_discard_the_documents_that_succeeded() {
        TwoDocuments docs = twoBankStatementDocuments("ai-isolation-mixed-it");
        ai.respondInOrder(successfulExtraction(), failed("response_truncated"));

        StageOutcome outcome = runStage(docs.packageId(), ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.skipped()).isFalse();
        assertThat(ai.requests).hasSize(2);
        assertThat(outcome.detail())
                .containsEntry("documents", 2)
                .containsEntry("documentsFailed", 1)
                .containsEntry("failureReason", "response_truncated");

        assertThat(aiRowCount(docs.first())).as("the OK document keeps its AI fields").isPositive();
        assertThat(aiRowCount(docs.second())).as("the failed document gets none").isZero();

        assertThat(ledgerStatuses(docs.first())).containsExactly("APPLIED");
        assertThat(ledgerStatuses(docs.second())).containsExactly("ERROR");
        assertThat(ledgerReasons(docs.second())).containsExactly("response_truncated");
        assertThat(notAppliedCount(docs.packageId()))
                .as("no sibling is ever re-recorded NOT_APPLIED")
                .isZero();
    }

    @Test
    void every_document_failing_is_a_stage_error_retryable_if_any_failure_was_transient() {
        assertEveryDocumentFailed("provider_transient", "response_truncated");
    }

    @Test
    void retryability_does_not_depend_on_which_document_failed_transiently_first() {
        assertEveryDocumentFailed("response_truncated", "provider_transient");
    }

    private void assertEveryDocumentFailed(String firstReason, String secondReason) {
        TwoDocuments docs =
                twoBankStatementDocuments("ai-isolation-all-fail-" + firstReason + "-it");
        ai.respondInOrder(failed(firstReason), failed(secondReason));

        StageOutcome outcome = runStage(docs.packageId(), ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_EXTRACTION_FAILED);
        assertThat(outcome.retryable()).as("a transient failure anywhere keeps the retry").isTrue();
        assertThat(outcome.detail())
                .containsEntry("reason", firstReason)
                .containsEntry("documents", 2);
        assertThat(ai.requests).hasSize(2);
        assertThat(aiRowCount(docs.first())).isZero();
        assertThat(aiRowCount(docs.second())).isZero();
        assertThat(ledgerStatuses(docs.first())).containsExactly("ERROR");
        assertThat(ledgerStatuses(docs.second())).containsExactly("ERROR");
        assertThat(notAppliedCount(docs.packageId())).isZero();
    }

    /** The same defect one step earlier: an oversized INPUT used to fail the package before any call. */
    @Test
    void an_oversized_sibling_is_recorded_and_the_normal_one_still_extracts() {
        TwoDocuments docs = twoBankStatementDocuments("ai-isolation-input-limit-it");
        UUID firstPage = firstPageOf(docs.first()).id();
        Long firstSpan =
                jdbc.queryForObject(
                        "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id LIMIT 1",
                        Long.class,
                        firstPage);
        jdbc.update("UPDATE text_span SET text = ? WHERE id = ?", "X".repeat(65_537), firstSpan);
        ai.requests.clear();
        ai.next = successfulExtraction();

        StageOutcome outcome = runStage(docs.packageId(), ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(ai.requests).as("the oversized document never reaches the provider").hasSize(1);
        assertThat(outcome.detail())
                .containsEntry("documents", 2)
                .containsEntry("documentsFailed", 1)
                .containsEntry("failureReason", "INPUT_LIMIT_EXCEEDED");
        assertThat(aiRowCount(docs.first())).isZero();
        assertThat(aiRowCount(docs.second())).isPositive();
        assertThat(ledgerStatuses(docs.first())).containsExactly("ERROR");
        assertThat(ledgerReasons(docs.first())).containsExactly("INPUT_LIMIT_EXCEEDED");
        assertThat(ledgerStatuses(docs.second())).containsExactly("APPLIED");
        assertThat(notAppliedCount(docs.packageId())).isZero();
    }

    // ── Issue #66: the per-document AI re-run ───────────────────────────────

    /**
     * The recovery #65 left open: a document whose provider call failed while its sibling applied
     * stayed unenriched, because the only re-run was the whole stage — and that would re-bill the
     * sibling. The re-run calls the provider for THIS document only.
     */
    @Test
    void a_failed_document_is_recovered_without_recalling_its_sibling() throws Exception {
        TwoDocuments docs = twoBankStatementDocuments("ai-rerun-recover-it");
        // A real job row in HUMAN_REVIEW_REQUIRED, so the guard's happy path — lock the row,
        // find it settled, proceed — is what runs here, not the no-job branch.
        seedCompletedJob(docs.packageId());
        ai.respondInOrder(successfulExtraction(), failed("provider_transient"));

        StageOutcome outcome = runStage(docs.packageId(), ProcessingStatus.AI_EXTRACTION);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.detail()).containsEntry("documentsFailed", 1);
        assertThat(ai.requests).hasSize(2);
        assertThat(aiRowCount(docs.second())).isZero();
        List<Map<String, Object>> siblingLedgerBefore = ledgerRows(docs.first());

        ai.respondWith(successfulExtraction());
        mockMvc.perform(post("/v1/documents/{id}/ai-extract", docs.second()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docs.second().toString()))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.fieldsInserted").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.conflicts").isNumber());
        TenantContext.set(ORG_DEV);

        assertThat(ai.requests)
                .as("exactly one more provider call — the failed document, never its sibling")
                .hasSize(3);
        assertThat(aiRowCount(docs.second())).as("the failed document now has its AI rows").isPositive();
        assertThat(ledgerStatuses(docs.second())).containsExactlyInAnyOrder("ERROR", "APPLIED");
        assertThat(latestLedgerStatus(docs.second())).isEqualTo("APPLIED");
        assertThat(ledgerRows(docs.first())).as("the sibling's ledger is untouched").isEqualTo(siblingLedgerBefore);
        assertThat(notAppliedCount(docs.packageId())).isZero();
    }

    /**
     * The #53 invariant, restated for the re-run: a reviewer's correction is the effective value
     * no matter how many times the machine reads the document again. The re-run goes through the
     * same persist() as the stage, so the corrected row is never replaced and the overlay still
     * resolves the decision.
     */
    @Test
    void a_rerun_never_overwrites_a_reviewers_correction() throws Exception {
        TwoDocuments docs = twoBankStatementDocuments("ai-rerun-correction-it");
        seedCompletedJob(docs.packageId());
        ai.respondWith(successfulExtraction());
        assertThat(runStage(docs.packageId(), ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        // bankName is one the canned extraction proposes ("Different Bank"), so the re-run WILL
        // have an opinion about this field — the correction must win over it.
        Map<String, Object> field = currentOccurrences(docs.second()).get("bankName#");
        assertThat(field).as("the second document has a current bankName row").isNotNull();
        UUID fieldId = (UUID) field.get("id");
        String corrected = "Reviewer Bank";
        assertThat(field.get("displayed_text")).isNotEqualTo(corrected);

        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"CORRECT\",\"value\":\"" + corrected + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveValue").value(corrected));

        mockMvc.perform(post("/v1/documents/{id}/ai-extract", docs.second()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", docs.second()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        TenantContext.set(ORG_DEV);
        List<String> displayed =
                JsonPath.read(body, "$.fields[?(@.fieldName=='bankName')].displayedText");
        assertThat(displayed)
                .as("the reviewer's value stands after the re-run (row %s, method %s)", fieldId, field.get("extraction_method"))
                .containsExactly(corrected);
        List<String> reviewStatus =
                JsonPath.read(body, "$.fields[?(@.fieldName=='bankName')].reviewStatus");
        assertThat(reviewStatus).containsExactly("CORRECTED");
        assertThat(latestLedgerStatus(docs.second())).isEqualTo("APPLIED");
    }

    /** PAYSTUB has no profile in this context (its gate is off): nothing to call, nothing to record. */
    @Test
    void a_rerun_on_an_ineligible_document_says_so() throws Exception {
        UUID packageId = insertPackage("ai-rerun-ineligible-it");
        insertFixturePages(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        ai.requests.clear();

        mockMvc.perform(post("/v1/documents/{id}/ai-extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("DOCUMENT_TYPE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.fieldsInserted").value(0))
                .andExpect(jsonPath("$.conflicts").value(0));
        TenantContext.set(ORG_DEV);

        assertThat(ai.requests).isEmpty();
        assertThat(ledgerStatuses(documentId)).isEmpty();
    }

    /**
     * The job row is free but its status is a pipeline stage — a run between stages. Its next
     * EXTRACTING or AI_EXTRACTION would race the re-run's rows, so the answer is 409 naming the
     * status.
     */
    @Test
    void a_rerun_is_refused_while_the_package_is_processing() throws Exception {
        TwoDocuments docs = twoBankStatementDocuments("ai-rerun-processing-it");
        jdbc.update(
                "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status)"
                        + " VALUES (?, ?, ?, ?, 'AI_EXTRACTION')",
                UUID.randomUUID(),
                ORG_DEV,
                docs.packageId(),
                "ai-rerun-processing-" + docs.packageId());

        mockMvc.perform(post("/v1/documents/{id}/ai-extract", docs.second()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.params.status").value("AI_EXTRACTION"));
        TenantContext.set(ORG_DEV);

        assertThat(ai.requests).isEmpty();
        assertThat(ledgerStatuses(docs.second())).isEmpty();
        assertThat(aiRowCount(docs.second())).isZero();
    }

    /**
     * The row itself is held by another transaction — what a RUNNING stage looks like, since
     * StageRunner writes the job row inside the stage's transaction before it calls the port and
     * holds it until the port answers (minutes, under OCR). The lock is taken NOWAIT, so the
     * refusal is immediate: a reviewer must not sit on a hung request to be told 409.
     */
    @Test
    void a_rerun_answers_409_promptly_while_a_running_stage_holds_the_job_row() throws Exception {
        TwoDocuments docs = twoBankStatementDocuments("ai-rerun-locked-it");
        seedCompletedJob(docs.packageId()); // settled by status — only the lock says otherwise
        UUID jobId =
                jdbc.queryForObject(
                        "SELECT id FROM processing_job WHERE package_id = ?",
                        UUID.class,
                        docs.packageId());

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder =
                new Thread(
                        () -> {
                            TenantContext.set(ORG_DEV);
                            try (Connection connection = dataSource.getConnection()) {
                                connection.setAutoCommit(false);
                                try (PreparedStatement lock =
                                        connection.prepareStatement(
                                                "SELECT id FROM processing_job WHERE id = ? FOR UPDATE")) {
                                    lock.setObject(1, jobId);
                                    try (ResultSet locked = lock.executeQuery()) {
                                        if (!locked.next()) {
                                            throw new IllegalStateException("job row not locked");
                                        }
                                    }
                                }
                                held.countDown();
                                // Bounded: if the re-run WAITS on the lock, this releases it
                                // after a while so the test fails on elapsed time, not forever.
                                release.await(30, TimeUnit.SECONDS);
                                connection.rollback();
                            } catch (Throwable failure) {
                                holderFailure.set(failure);
                                held.countDown();
                            } finally {
                                TenantContext.clear();
                            }
                        });
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(holderFailure.get()).isNull();
        try {
            long started = System.nanoTime();
            mockMvc.perform(post("/v1/documents/{id}/ai-extract", docs.second()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.params.status").value("PROCESSING"));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMs)
                    .as("NOWAIT: the refusal must not wait for the stage to release the row")
                    .isLessThan(5_000L);
        } finally {
            release.countDown();
            holder.join(30_000);
        }
        assertThat(holder.isAlive()).isFalse();
        assertThat(holderFailure.get()).isNull();
        TenantContext.set(ORG_DEV);
        assertThat(ai.requests).isEmpty();
        assertThat(ledgerStatuses(docs.second())).isEmpty();
        assertThat(aiRowCount(docs.second())).isZero();
    }

    /**
     * FAILED is settled: nothing is running, and the failure may be the AI stage itself — the
     * re-run is one way back. (The regroup re-extract admits only HUMAN_REVIEW_REQUIRED, but that
     * claim re-kicks the pipeline and guards ABA; this one only needs the job to be at rest.)
     */
    @Test
    void a_rerun_proceeds_on_a_failed_job() throws Exception {
        TwoDocuments docs = twoBankStatementDocuments("ai-rerun-failed-job-it");
        seedCompletedJob(docs.packageId());
        jdbc.update(
                "UPDATE processing_job SET status = 'FAILED' WHERE package_id = ?",
                docs.packageId());
        ai.respondWith(successfulExtraction());

        mockMvc.perform(post("/v1/documents/{id}/ai-extract", docs.second()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        TenantContext.set(ORG_DEV);

        assertThat(ai.requests).hasSize(1);
        assertThat(aiRowCount(docs.second())).isPositive();
        assertThat(latestLedgerStatus(docs.second())).isEqualTo("APPLIED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_job WHERE package_id = ?",
                                String.class,
                                docs.packageId()))
                .as("the re-run changes nothing about the job")
                .isEqualTo("FAILED");
    }

    /** Fail closed: no job row means nothing to lock against, so the re-run declines. */
    @Test
    void a_rerun_on_a_package_with_no_job_is_not_eligible() throws Exception {
        TwoDocuments docs = twoBankStatementDocuments("ai-rerun-no-job-it");

        mockMvc.perform(post("/v1/documents/{id}/ai-extract", docs.second()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("NO_JOB"))
                .andExpect(jsonPath("$.fieldsInserted").value(0));
        TenantContext.set(ORG_DEV);

        assertThat(ai.requests).isEmpty();
        assertThat(ledgerStatuses(docs.second())).isEmpty();
    }

    @Test
    void another_orgs_document_is_not_found() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        UUID foreignDocument = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package for ai re-run");
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)"
                        + " VALUES (?, ?, ?, 0, 'BANK_STATEMENT')",
                foreignDocument,
                ORG_OTHER,
                foreignPackage);

        mockMvc.perform(post("/v1/documents/{id}/ai-extract", foreignDocument))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/documents/{id}/ai-extract", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        TenantContext.set(ORG_DEV);
        assertThat(ai.requests).isEmpty();
    }

    /** REVIEWER+ like the regroup that re-extracts: a human decision on machine output. */
    @Test
    void a_rerun_is_reviewer_work() throws Exception {
        TwoDocuments docs = twoBankStatementDocuments("ai-rerun-rbac-it");
        seedCompletedJob(docs.packageId());
        for (String role : List.of("READONLY", "PROCESSOR")) {
            mockMvc.perform(
                            post("/v1/documents/{id}/ai-extract", docs.second())
                                    .header("X-Dev-Role", role))
                    .andExpect(status().isForbidden());
        }
        ai.respondWith(successfulExtraction());
        mockMvc.perform(
                        post("/v1/documents/{id}/ai-extract", docs.second())
                                .header("X-Dev-Role", "REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        TenantContext.set(ORG_DEV);
        assertThat(ai.requests).hasSize(1);
    }

    private List<Map<String, Object>> ledgerRows(UUID documentId) {
        return jdbc.queryForList(
                "SELECT id, prompt_version, interpretation::text AS interpretation, created_at"
                        + " FROM ai_interpretation WHERE subject_type = 'LOGICAL_DOCUMENT'"
                        + " AND subject_id = ? ORDER BY created_at, id",
                documentId);
    }

    private String latestLedgerStatus(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT interpretation->>'applicationStatus' FROM ai_interpretation"
                        + " WHERE subject_type = 'LOGICAL_DOCUMENT' AND subject_id = ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class,
                documentId);
    }

    private record TwoDocuments(UUID packageId, UUID first, UUID second) {}

    /**
     * One package, two eligible BANK_STATEMENT documents, deterministic rows on both. The fixture
     * splits to a single three-page statement, so the third page is regrouped into a second
     * document by hand (the same shape a reviewer's regroup produces) BEFORE the EXTRACTING stage
     * runs, which lays down the deterministic rows the AI persist path requires on each.
     */
    private TwoDocuments twoBankStatementDocuments(String name) {
        UUID packageId = insertPackage(name);
        insertFixturePages(packageId, "bank_statement");
        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();
        assertThat(runStage(packageId, ProcessingStatus.SPLITTING).success()).isTrue();
        UUID first = onlyDocumentOf(packageId);
        UUID second = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code,"
                        + " classification_confidence, boundary_provenance)"
                        + " VALUES (?, ?, ?, 1, 'BANK_STATEMENT', 0.9900, 'HUMAN')",
                second,
                ORG_DEV,
                packageId);
        UUID thirdPage =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ? AND package_page_index = 2",
                        UUID.class,
                        packageId);
        jdbc.update(
                "UPDATE logical_document_page SET logical_document_id = ?, ordinal = 0"
                        + " WHERE page_id = ?",
                second,
                thirdPage);
        assertThat(runStage(packageId, ProcessingStatus.EXTRACTING).success()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?"
                                        + " AND is_current",
                                Integer.class,
                                second))
                .as("the second document has deterministic rows to enrich")
                .isPositive();
        ai.requests.clear();
        return new TwoDocuments(packageId, first, second);
    }

    private static AiExtractionResult failed(String reason) {
        return new AiExtractionResult(
                null, null, "test", "synthetic", AiExtractionStatus.ERROR, AiTokenCounts.ZERO, reason);
    }

    private int aiRowCount(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?"
                        + " AND extraction_method = 'AI'",
                Integer.class,
                documentId);
    }

    private List<String> ledgerStatuses(UUID documentId) {
        return jdbc.queryForList(
                "SELECT interpretation->>'applicationStatus' FROM ai_interpretation"
                        + " WHERE subject_type = 'LOGICAL_DOCUMENT' AND subject_id = ?",
                String.class,
                documentId);
    }

    private List<String> ledgerReasons(UUID documentId) {
        return jdbc.queryForList(
                "SELECT interpretation->>'reason' FROM ai_interpretation"
                        + " WHERE subject_type = 'LOGICAL_DOCUMENT' AND subject_id = ?",
                String.class,
                documentId);
    }

    private int notAppliedCount(UUID packageId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ai_interpretation i JOIN logical_document d"
                        + " ON d.id = i.subject_id WHERE d.package_id = ?"
                        + " AND i.interpretation->>'applicationStatus' = 'NOT_APPLIED'",
                Integer.class,
                packageId);
    }

    @Test
    void non_bank_document_is_skipped_without_calling_the_provider() {
        UUID packageId = insertPackage("ai-non-bank-it");
        insertFixturePages(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        ai.requests.clear();

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).isEqualTo("DOCUMENT_TYPE_NOT_ALLOWED");
        assertThat(ai.requests).isEmpty();
    }

    @Test
    void a_w2_is_skipped_while_its_own_flag_is_off() {
        UUID packageId = insertPackage("ai-w2-flag-off-it");
        insertFixturePages(packageId, "w2_form");
        runPipelineToExtraction(packageId);
        ai.requests.clear();

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).isEqualTo("DOCUMENT_TYPE_NOT_ALLOWED");
        assertThat(ai.requests).isEmpty();
    }

    static AiExtractionResult successfulExtraction() {
        TextCell none = null;
        Summary summary =
                new Summary(
                        new TextCell("Different Bank", "DIFFERENT BANK", 1, Confidence.HIGH),
                        none,
                        new TextCell("123 Main Street", "123 Main Street", 1, Confidence.MEDIUM),
                        none,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        Txn txn =
                new Txn(
                        new DateCell(LocalDate.of(2026, 8, 1), "08/01/2026", 3, Confidence.HIGH),
                        new TextCell("Utility payment", "UTILITY PAYMENT", 3, Confidence.MEDIUM),
                        new MoneyCell(new BigDecimal("42.50"), "$42.50", 3, Confidence.HIGH),
                        new MoneyCell(new BigDecimal("957.50"), "$957.50", 3, Confidence.MEDIUM),
                        Direction.WITHDRAWAL,
                        3);
        Check check =
                new Check(
                        new TextCell("1042", "1042", 3, Confidence.HIGH),
                        new DateCell(LocalDate.of(2026, 8, 2), "08/02/2026", 3, Confidence.MEDIUM),
                        new MoneyCell(new BigDecimal("25.00"), "$25.00", 3, Confidence.HIGH),
                        3);
        BankStatementExtraction extraction =
                new BankStatementExtraction(summary, List.of(txn), List.of(check));
        return new AiExtractionResult(
                "{}",
                extraction,
                "test",
                "synthetic",
                AiExtractionStatus.OK,
                new AiTokenCounts(120, 42, 80, 40),
                null);
    }

    private static AiExtractionResult reconciledExtraction(int page, boolean wrongRunningBalance) {
        Summary summary =
                new Summary(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        money("10000.00", null, null),
                        money("11000.00", null, null),
                        money("1234.56", null, null),
                        money("234.56", null, null));
        Txn deposit =
                new Txn(
                        new DateCell(LocalDate.of(2099, 12, 30), "12/30/2099", page, Confidence.HIGH),
                        new TextCell(
                                "SYNTHETIC DEPOSIT ALPHA",
                                "SYNTHETIC DEPOSIT ALPHA",
                                page,
                                Confidence.HIGH),
                        money("1234.56", "$1,234.56", page),
                        money("11234.56", "$11,234.56", page),
                        Direction.DEPOSIT,
                        page);
        Txn withdrawal =
                new Txn(
                        new DateCell(LocalDate.of(2099, 12, 31), "12/31/2099", page, Confidence.HIGH),
                        new TextCell(
                                "SYNTHETIC WITHDRAWAL OMEGA",
                                "SYNTHETIC WITHDRAWAL OMEGA",
                                page,
                                Confidence.HIGH),
                        money("234.56", "$234.56", page),
                        money(
                                wrongRunningBalance ? "10999.99" : "11000.00",
                                wrongRunningBalance ? "$10,999.99" : "$11,000.00",
                                page),
                        Direction.WITHDRAWAL,
                        page);
        return new AiExtractionResult(
                "{}",
                new BankStatementExtraction(summary, List.of(deposit, withdrawal), List.of()),
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

    private void replaceFirstTwoSpans(UUID pageId, String first, String second) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id LIMIT 2",
                        Long.class,
                        pageId);
        assertThat(ids).hasSize(2);
        jdbc.update("UPDATE text_span SET text = ? WHERE id = ?", first, ids.get(0));
        jdbc.update("UPDATE text_span SET text = ? WHERE id = ?", second, ids.get(1));
    }

    private static MoneyCell money(String value, String text, Integer page) {
        return new MoneyCell(new BigDecimal(value), text, page, Confidence.HIGH);
    }

    private record PageRef(UUID id, int printedPage) {}

    static final class CannedAiPort implements AiExtractionPort {
        private final List<AiExtractionRequest> requests = new ArrayList<>();
        private final java.util.ArrayDeque<AiExtractionResult> scripted =
                new java.util.ArrayDeque<>();
        private AiExtractionResult next;

        @Override
        public AiExtractionResult extract(AiExtractionRequest request) {
            requests.add(request);
            // A scripted sequence wins while it lasts; the standing answer serves every call
            // after it (and every call when nothing was scripted).
            return scripted.isEmpty() ? next : scripted.poll();
        }

        // Package-private access for sibling ITs sharing AiTestConfig (AiPageImagesIT).
        List<AiExtractionRequest> recorded() {
            return requests;
        }

        void respondWith(AiExtractionResult result) {
            scripted.clear();
            next = result;
        }

        /** One answer per call, in document order — how a multi-document package is scripted. */
        void respondInOrder(AiExtractionResult... results) {
            scripted.clear();
            scripted.addAll(List.of(results));
        }
    }

    @TestConfiguration
    static class AiTestConfig {
        @Bean
        @Primary
        CannedAiPort cannedAiPort() {
            return new CannedAiPort();
        }
    }
}
