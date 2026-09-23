package com.pragmaticds.docengine.lifecycle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * FIX 4 (MEDIUM): a tombstoned package must be unreadable AND unwritable on its fields, history and
 * correction/review paths. The 7c soft-delete read-exclusion fixed package/files/render/pages/
 * export but MISSED {@code GET /v1/documents/{id}/fields}, {@code GET /v1/documents/{id}/history},
 * {@code PATCH /v1/fields/{id}} and {@code POST /v1/documents/{id}/review|classification}. So a
 * tombstoned package's field values and correction history stayed readable, and its fields could
 * still be corrected — writing MORE PII destined to be orphaned. A tombstoned package's document
 * must answer 404 on read and 404 on write, exactly like a nonexistent one.
 */
class TombstonedPackageAccessIT extends AbstractExtractionIT {

    private record Seeded(UUID packageId, UUID documentId, UUID fieldId) {}

    private Seeded seed(String name) {
        UUID packageId = insertPackage(name);
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("netPay").get("id");
        return new Seeded(packageId, documentId, fieldId);
    }

    @Test
    void a_tombstoned_packages_fields_history_and_writes_all_404() throws Exception {
        Seeded s = seed("tombstone-access-it");

        // Before: every path serves the live package.
        mockMvc.perform(get("/v1/documents/{id}/fields", s.documentId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/documents/{id}/history", s.documentId()))
                .andExpect(status().isOk());

        jdbc.update(
                "UPDATE document_package SET deleted_at = now(), purge_after = now() + interval '30"
                        + " days' WHERE id = ?",
                s.packageId());

        // Reads now answer absent.
        mockMvc.perform(get("/v1/documents/{id}/fields", s.documentId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/documents/{id}/history", s.documentId()))
                .andExpect(status().isNotFound());

        // Writes now answer absent — no more PII can be appended to a tombstoned package.
        mockMvc.perform(
                        patch("/v1/fields/{id}", s.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"CORRECT\",\"value\":\"1.00\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/documents/{id}/review", s.documentId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/v1/documents/{id}/classification", s.documentId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"documentTypeCode\":\"W2\"}"))
                .andExpect(status().isNotFound());
    }
}
