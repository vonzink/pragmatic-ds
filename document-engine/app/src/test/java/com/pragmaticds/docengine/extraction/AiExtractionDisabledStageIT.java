package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The production-safe default: even an eligible bank statement never calls AI unless enabled. */
class AiExtractionDisabledStageIT extends AbstractExtractionIT {

    @Test
    void disabled_gate_records_a_skip_and_does_not_add_llm_rows() {
        UUID packageId = insertPackage("ai-disabled-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).isEqualTo("AI_DISABLED");
        Integer aiRows =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM extracted_field f
                        JOIN logical_document d ON d.id = f.logical_document_id
                        WHERE d.package_id = ? AND f.extraction_method = 'AI'
                        """,
                        Integer.class,
                        packageId);
        assertThat(aiRows).isZero();
    }

    /** The per-document re-run (#66) honours the same gate: switched off, it says so and does nothing. */
    @Test
    void disabled_gate_makes_a_per_document_rerun_ineligible() throws Exception {
        UUID packageId = insertPackage("ai-disabled-rerun-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId); // the guard passes; the feature gate is what declines
        UUID documentId = onlyDocumentOf(packageId);

        mockMvc.perform(post("/v1/documents/{id}/ai-extract", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("AI_DISABLED"))
                .andExpect(jsonPath("$.fieldsInserted").value(0))
                .andExpect(jsonPath("$.conflicts").value(0));

        Integer ledgerRows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM ai_interpretation"
                                + " WHERE subject_type = 'LOGICAL_DOCUMENT' AND subject_id = ?",
                        Integer.class,
                        documentId);
        assertThat(ledgerRows).as("an ineligible re-run records nothing").isZero();
    }
}
