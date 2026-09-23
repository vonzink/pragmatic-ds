package com.pragmaticds.docengine.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.security.DevAuthFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Document-level review decisions (MARK_REVIEWED, RECLASSIFY) and the read-side history strip.
 * Each write appends an append-only {@code review_decision}, mutates the sanctioned
 * {@code logical_document} columns in place, and audits.
 */
class DocumentReviewIT extends AbstractExtractionIT {

    private static final UUID DEV_USER = DevAuthFilter.DEV_USER;

    private UUID seedDocument(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
    }

    @Test
    void mark_reviewed_flips_the_status_records_a_decision_and_audits() throws Exception {
        UUID documentId = seedDocument("mark-reviewed-it");

        mockMvc.perform(post("/v1/documents/{id}/review", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REVIEWED"))
                .andExpect(jsonPath("$.decision.action").value("MARK_REVIEWED"))
                .andExpect(jsonPath("$.decision.decidedBy").value(DEV_USER.toString()));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM logical_document WHERE id = ?",
                                String.class,
                                documentId))
                .isEqualTo("REVIEWED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reviewed_by FROM logical_document WHERE id = ?",
                                UUID.class,
                                documentId))
                .isEqualTo(DEV_USER);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reviewed_at IS NOT NULL FROM logical_document WHERE id = ?",
                                Boolean.class,
                                documentId))
                .isTrue();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM review_decision WHERE subject_id = ? AND"
                                        + " subject_type = 'LOGICAL_DOCUMENT' AND action ="
                                        + " 'MARK_REVIEWED'",
                                Integer.class,
                                documentId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_event WHERE action = 'DOCUMENT_REVIEWED'"
                                        + " AND subject_id = ?",
                                Integer.class,
                                documentId))
                .isEqualTo(1);
    }

    @Test
    void reclassify_updates_the_type_and_records_previous_to_new() throws Exception {
        UUID documentId = seedDocument("reclassify-it");

        mockMvc.perform(
                        post("/v1/documents/{id}/classification", documentId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"documentTypeCode\":\"W2\",\"reason\":\"actually a W2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentTypeCode").value("W2"))
                .andExpect(jsonPath("$.decision.action").value("RECLASSIFY"))
                .andExpect(jsonPath("$.decision.previousValue").value("PAYSTUB"))
                .andExpect(jsonPath("$.decision.newValue").value("W2"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT document_type_code FROM logical_document WHERE id = ?",
                                String.class,
                                documentId))
                .isEqualTo("W2");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_event WHERE action ="
                                        + " 'DOCUMENT_RECLASSIFIED' AND subject_id = ?",
                                Integer.class,
                                documentId))
                .isEqualTo(1);
    }

    @Test
    void the_history_strip_returns_document_and_field_decisions_newest_first() throws Exception {
        UUID documentId = seedDocument("history-it");
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("netPay").get("id");

        // Three decisions, in order: a field correction, a reclassify, then the sign-off.
        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"CORRECT\",\"value\":\"3,333.33\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/v1/documents/{id}/classification", documentId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"documentTypeCode\":\"W2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/documents/{id}/review", documentId)).andExpect(status().isOk());

        mockMvc.perform(get("/v1/documents/{id}/history", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                // Newest first: the sign-off is on top.
                .andExpect(jsonPath("$.entries[0].action").value("MARK_REVIEWED"))
                // The field correction carries its field name and original -> corrected values.
                .andExpect(
                        jsonPath("$.entries[?(@.action=='CORRECT')].fieldName")
                                .value(hasItem("netPay")))
                .andExpect(
                        jsonPath("$.entries[?(@.action=='CORRECT')].newValue")
                                .value(hasItem("3,333.33")))
                .andExpect(
                        jsonPath("$.entries[?(@.action=='RECLASSIFY')].newValue")
                                .value(hasItem("W2")));
    }

    @Test
    void a_readonly_principal_cannot_review_or_reclassify() throws Exception {
        UUID documentId = seedDocument("readonly-review-it");
        mockMvc.perform(post("/v1/documents/{id}/review", documentId).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/v1/documents/{id}/classification", documentId)
                                .header("X-Dev-Role", "READONLY")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"documentTypeCode\":\"W2\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void another_orgs_document_is_404_for_review_reclassify_and_history() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        UUID foreignDocument = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package for review");
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)"
                        + " VALUES (?, ?, ?, 0, 'PAYSTUB')",
                foreignDocument,
                ORG_OTHER,
                foreignPackage);

        mockMvc.perform(post("/v1/documents/{id}/review", foreignDocument))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/v1/documents/{id}/classification", foreignDocument)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"documentTypeCode\":\"W2\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/documents/{id}/history", foreignDocument))
                .andExpect(status().isNotFound());
    }
}
