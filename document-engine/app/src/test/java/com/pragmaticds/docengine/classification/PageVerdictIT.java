package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The page verdict-override endpoint ({@code POST /v1/pages/{id}/verdict}, Spec 2 Task 7 / design
 * §4.2): a reviewer clears a page's blank/duplicate signal so it becomes assignable. It does NOT
 * re-run detection and does NOT assign — assignment is a subsequent regroup. The override is
 * captured as an append-only {@code review_decision} (subject_type {@code PAGE}, action
 * {@code OVERRIDE_VERDICT}) preserving the prior signal value.
 *
 * <p>Unlike {@code RegroupIT}, this path never calls {@code reExtract}, so no sync-executor import
 * and no {@code seedCompletedJob} are needed.
 */
class PageVerdictIT extends AbstractExtractionIT {

    @Test
    void not_duplicate_makes_a_duplicate_page_assignable() throws Exception {
        // combined_package pages 11-12 duplicate pages 1-2 and are unassigned.
        UUID packageId = insertPackage("verdict-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        UUID dupPage =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ? AND duplicate_of_page_id IS NOT NULL"
                                + " LIMIT 1",
                        UUID.class,
                        packageId);

        mockMvc.perform(
                        post("/v1/pages/{id}/verdict", dupPage)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"verdict\":\"NOT_DUPLICATE\",\"reason\":\"distinct"
                                                + " statement\"}"))
                .andExpect(status().isOk());

        // The signal is cleared: the page no longer points at its original.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT duplicate_of_page_id FROM page WHERE id = ?",
                                UUID.class,
                                dupPage))
                .isNull();
        // Exactly one PAGE / OVERRIDE_VERDICT decision was appended for this page.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM review_decision WHERE subject_type='PAGE'"
                                        + " AND action='OVERRIDE_VERDICT' AND subject_id = ?",
                                Long.class,
                                dupPage))
                .isEqualTo(1L);
        // And a PII-free audit event recording the verdict.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT metadata->>'verdict' FROM audit_event WHERE"
                                        + " action='PAGE_VERDICT_OVERRIDDEN' AND subject_id = ?",
                                String.class,
                                dupPage))
                .isEqualTo("NOT_DUPLICATE");
    }
}
