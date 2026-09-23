package com.pragmaticds.docengine.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /v1/packages/{id}/usage — what one package COST to parse.
 *
 * <p>The honest answer today is COMPUTE, not money: no rung of the extraction ladder calls a paid
 * model, so the money arm reports zero calls and zero spend and names what it can see. These tests
 * pin that honesty in both directions — the resource numbers must be real, and the money number
 * must be a MEASURED zero rather than an absent field or an invented rate.
 *
 * <p>Stage rows are asserted BY INDEX in pipeline order. {@code created_at} defaults to
 * {@code now()}, which in Postgres is transaction-constant, so every stage seeded in one
 * transaction shares a timestamp — ordering by it is a coin flip. The endpoint orders by the
 * {@code ProcessingStatus} declaration order instead, which is the pipeline order and is stable.
 */
class PackageUsageApiIT extends AbstractExtractionIT {

    /** Index of a stage within {@code seedCompletedJob}'s pipeline-ordered rows. */
    private static final int PARSING = 5;

    private static final int OCR_PROCESSING = 4;
    private static final int AI_REVIEW = 11;

    @Test
    void a_packages_usage_reports_its_pages_by_text_layer_with_the_ocr_count_called_out()
            throws Exception {
        UUID packageId = insertPackage("usage-api-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageId").value(packageId.toString()))
                // The fixture is a native-text paystub: every page has a usable text layer, so
                // NOTHING went to OCR. That is the cheap end of the only real cost variable.
                .andExpect(jsonPath("$.pages.total").value(1))
                .andExpect(jsonPath("$.pages.ocrPages").value(0))
                .andExpect(jsonPath("$.pages.byTextLayer.NATIVE").value(1))
                .andExpect(jsonPath("$.pages.byTextLayer.SCANNED").value(0))
                .andExpect(jsonPath("$.pages.byTextLayer.MIXED").value(0))
                .andExpect(jsonPath("$.pages.byTextLayer.NONE").value(0));
    }

    @Test
    void the_ocr_count_is_scanned_plus_mixed_because_that_is_what_the_adapter_sends_to_ocr()
            throws Exception {
        // Mirrors WorkerParserAdapter.OCR_ELIGIBLE = EnumSet.of(SCANNED, MIXED). A count that
        // used SCANNED alone would understate the bill for every mixed page.
        UUID packageId = insertPackage("usage-ocr-it");
        insertFixturePages(packageId, "paystub_complete");
        jdbc.update("UPDATE page SET text_layer = 'MIXED' WHERE package_id = ?", packageId);

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages.total").value(1))
                .andExpect(jsonPath("$.pages.ocrPages").value(1))
                .andExpect(jsonPath("$.pages.byTextLayer.MIXED").value(1))
                .andExpect(jsonPath("$.pages.byTextLayer.NATIVE").value(0));
    }

    @Test
    void the_money_arm_reports_a_measured_zero_and_names_what_it_can_and_cannot_see()
            throws Exception {
        UUID packageId = insertPackage("usage-cost-it");
        insertFixturePages(packageId, "paystub_complete");

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                // Zero CALLS is the fact; zero dollars follows from it. Both are present rather
                // than omitted, so the UI needs no shape change when Phase L makes them non-zero.
                .andExpect(jsonPath("$.modelCost.calls").value(0))
                .andExpect(jsonPath("$.modelCost.inputTokens").value(0))
                .andExpect(jsonPath("$.modelCost.outputTokens").value(0))
                .andExpect(jsonPath("$.modelCost.costUsd").value(0))
                .andExpect(jsonPath("$.modelCost.currency").value("USD"))
                .andExpect(jsonPath("$.modelCost.byModel").isArray())
                .andExpect(jsonPath("$.modelCost.byModel.length()").value(0))
                // A zero that does not say what it counted is a lie by omission. Every known
                // producer is listed with whether this endpoint can actually observe its spend.
                .andExpect(jsonPath("$.modelCost.producers[0].producer").value("ENGINE"))
                .andExpect(jsonPath("$.modelCost.producers[0].observed").value(true))
                .andExpect(jsonPath("$.modelCost.producers[1].producer").value("RAG_BRAIN"))
                .andExpect(jsonPath("$.modelCost.producers[1].observed").value(false));
    }

    @Test
    void a_model_call_recorded_against_the_packages_own_subject_lands_in_the_money_arm()
            throws Exception {
        // The forward-compatibility proof. Phase L's producer writes ai_interpretation rows
        // (V8's provider ledger) and NOTHING else has to change for the money arm to become
        // non-zero — no second mechanism, no new table, no UI shape change.
        UUID packageId = insertPackage("usage-cost-nonzero-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        jdbc.update(
                """
                INSERT INTO ai_interpretation (id, org_id, subject_type, subject_id, provider,
                    model, prompt_version, interpretation, tokens_in, tokens_out, cost_usd)
                VALUES (?, ?, 'LOGICAL_DOCUMENT', ?, 'anthropic', 'a-model', 'v1',
                    '{}'::jsonb, 1200, 340, 0.004500)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                documentId);

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCost.calls").value(1))
                .andExpect(jsonPath("$.modelCost.inputTokens").value(1200))
                .andExpect(jsonPath("$.modelCost.outputTokens").value(340))
                .andExpect(jsonPath("$.modelCost.costUsd").value(0.0045))
                .andExpect(jsonPath("$.modelCost.byModel.length()").value(1))
                .andExpect(jsonPath("$.modelCost.byModel[0].producer").value("ENGINE"))
                .andExpect(jsonPath("$.modelCost.byModel[0].provider").value("anthropic"))
                .andExpect(jsonPath("$.modelCost.byModel[0].model").value("a-model"))
                .andExpect(jsonPath("$.modelCost.byModel[0].calls").value(1));
    }

    @Test
    void another_orgs_model_spend_never_lands_in_this_packages_money_arm() throws Exception {
        // ai_interpretation is read with raw SQL (it has no entity, deliberately — V8 built it
        // TABLE-ONLY), so @TenantId does not guard it. The org filter must be written by hand,
        // and this is the test that proves it was.
        UUID packageId = insertPackage("usage-cost-tenant-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        jdbc.update(
                """
                INSERT INTO ai_interpretation (id, org_id, subject_type, subject_id, provider,
                    model, prompt_version, interpretation, tokens_in, tokens_out, cost_usd)
                VALUES (?, ?, 'LOGICAL_DOCUMENT', ?, 'anthropic', 'a-model', 'v1',
                    '{}'::jsonb, 9999, 9999, 99.999999)
                """,
                UUID.randomUUID(),
                ORG_OTHER,
                documentId);

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCost.calls").value(0))
                .andExpect(jsonPath("$.modelCost.costUsd").value(0));
    }

    @Test
    void elapsed_is_reported_at_package_scope_because_stages_do_not_run_per_document()
            throws Exception {
        UUID packageId = insertPackage("usage-elapsed-it");
        insertFixturePages(packageId, "paystub_complete");
        seedCompletedJob(packageId);
        setStageDuration(packageId, "PARSING", 1200);
        setStageDuration(packageId, "EXTRACTING", 300);
        jdbc.update(
                "UPDATE processing_job SET started_at = now(), finished_at = now() +"
                        + " interval '5 seconds' WHERE package_id = ?",
                packageId);

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                // Wall clock (5000) exceeds time spent inside stages (1500). The gap is queueing,
                // and reporting only one of the two numbers would misattribute it as compute.
                .andExpect(jsonPath("$.elapsed.packageWallClockMs").value(5000))
                // The NAME carries the caveat: a stage runs over the whole package, so there is
                // no honest per-document elapsed to divide out, and the field says so rather than
                // implying a precision the telemetry does not have.
                .andExpect(jsonPath("$.elapsed.scope").value("PACKAGE"))
                .andExpect(jsonPath("$.elapsed.perDocumentElapsedAvailable").value(false))
                .andExpect(jsonPath("$.elapsed.packageStageElapsedMs").value(1500))
                .andExpect(jsonPath("$.elapsed.stages[" + PARSING + "].stage").value("PARSING"))
                .andExpect(jsonPath("$.elapsed.stages[" + PARSING + "].durationMs").value(1200))
                .andExpect(jsonPath("$.elapsed.stages[" + PARSING + "].status").value("SUCCEEDED"))
                // A stage this spec does not implement records WHY it was skipped; a usage report
                // that hid the skip would overstate what the elapsed number covers.
                .andExpect(jsonPath("$.elapsed.stages[" + AI_REVIEW + "].stage").value("AI_REVIEW"))
                .andExpect(jsonPath("$.elapsed.stages[" + AI_REVIEW + "].status").value("SKIPPED"))
                .andExpect(
                        jsonPath("$.elapsed.stages[" + AI_REVIEW + "].skipReason")
                                .value("SPEC_5_NOT_IMPLEMENTED"));
    }

    @Test
    void a_retried_stage_is_counted_because_a_retry_is_compute_paid_for_twice() throws Exception {
        UUID packageId = insertPackage("usage-retry-it");
        insertFixturePages(packageId, "paystub_complete");
        seedCompletedJob(packageId);
        UUID jobId =
                jdbc.queryForObject(
                        "SELECT id FROM processing_job WHERE package_id = ?", UUID.class, packageId);
        // The shape the owner actually hit: OCR_PROCESSING failed twice on WORKER_UNAVAILABLE
        // before the third attempt succeeded. Three attempt rows, two of them wasted work.
        jdbc.update(
                "UPDATE processing_stage SET attempt = 3, duration_ms = 2000 WHERE job_id = ?"
                        + " AND stage = 'OCR_PROCESSING'",
                jobId);
        jdbc.update(
                """
                INSERT INTO processing_stage (id, org_id, job_id, stage, status, attempt,
                    error_code, duration_ms)
                VALUES (?, ?, ?, 'OCR_PROCESSING', 'FAILED', 1, 'WORKER_UNAVAILABLE', 4000),
                       (?, ?, ?, 'OCR_PROCESSING', 'FAILED', 2, 'WORKER_UNAVAILABLE', 4000)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                jobId,
                UUID.randomUUID(),
                ORG_DEV,
                jobId);

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.jobAttempt").value(1))
                .andExpect(jsonPath("$.attempts.stageAttempts").value(14))
                // Two attempt rows beyond the first — the compute that produced nothing. Elapsed
                // counts them too: 8000ms of the 10000 total bought no result.
                .andExpect(jsonPath("$.attempts.retriedAttempts").value(2))
                .andExpect(jsonPath("$.attempts.failedAttempts").value(2))
                .andExpect(jsonPath("$.attempts.retries.length()").value(1))
                .andExpect(jsonPath("$.attempts.retries[0].stage").value("OCR_PROCESSING"))
                .andExpect(jsonPath("$.attempts.retries[0].attempts").value(3))
                .andExpect(
                        jsonPath("$.attempts.retries[0].lastErrorCode").value("WORKER_UNAVAILABLE"))
                .andExpect(jsonPath("$.elapsed.packageStageElapsedMs").value(10000))
                // The wasted share is stated, not left to the reader to subtract.
                .andExpect(jsonPath("$.elapsed.failedAttemptElapsedMs").value(8000))
                // Every attempt row is present, ordered by pipeline position then attempt.
                .andExpect(
                        jsonPath("$.elapsed.stages[" + OCR_PROCESSING + "].stage")
                                .value("OCR_PROCESSING"))
                .andExpect(jsonPath("$.elapsed.stages[" + OCR_PROCESSING + "].attempt").value(1))
                .andExpect(
                        jsonPath("$.elapsed.stages[" + OCR_PROCESSING + "].errorCode")
                                .value("WORKER_UNAVAILABLE"))
                .andExpect(jsonPath("$.elapsed.stages[" + (OCR_PROCESSING + 2) + "].attempt").value(3))
                .andExpect(
                        jsonPath("$.elapsed.stages[" + (OCR_PROCESSING + 2) + "].status")
                                .value("SUCCEEDED"));
    }

    @Test
    void per_document_pages_are_derivable_so_the_ocr_share_is_reported_per_document()
            throws Exception {
        UUID packageId = insertPackage("usage-per-document-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                // Pages ARE attributable to a document (logical_document_page), so the OCR count —
                // the expensive variable — is honest per document even though elapsed is not.
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.documents[0].documentTypeCode").value("PAYSTUB"))
                .andExpect(jsonPath("$.documents[0].pages").value(1))
                .andExpect(jsonPath("$.documents[0].ocrPages").value(0))
                .andExpect(jsonPath("$.documents[0].byTextLayer.NATIVE").value(1))
                .andExpect(jsonPath("$.unassignedPages").value(0));
    }

    @Test
    void a_package_with_no_job_yet_answers_with_zeroed_timing_rather_than_an_error()
            throws Exception {
        UUID packageId = insertPackage("usage-no-job-it");
        insertFixturePages(packageId, "paystub_complete");

        mockMvc.perform(get("/v1/packages/{id}/usage", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elapsed.packageStageElapsedMs").value(0))
                .andExpect(jsonPath("$.elapsed.stages.length()").value(0))
                .andExpect(jsonPath("$.attempts.stageAttempts").value(0))
                // Null, not 1: there is no job, and inventing an attempt count would be the same
                // class of lie as inventing a dollar figure.
                .andExpect(jsonPath("$.attempts.jobAttempt").value(Matchers.nullValue()));
    }

    @Test
    void an_unknown_package_id_is_not_found() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}/usage", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void another_orgs_package_is_indistinguishable_from_a_nonexistent_one() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package with usage");

        MvcResult result =
                mockMvc.perform(get("/v1/packages/{id}/usage", foreignPackage))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("foreign package with usage");
    }

    private void setStageDuration(UUID packageId, String stage, long durationMs) {
        jdbc.update(
                "UPDATE processing_stage SET duration_ms = ? WHERE stage = ? AND job_id IN"
                        + " (SELECT id FROM processing_job WHERE package_id = ?)",
                durationMs,
                stage,
                packageId);
    }
}
