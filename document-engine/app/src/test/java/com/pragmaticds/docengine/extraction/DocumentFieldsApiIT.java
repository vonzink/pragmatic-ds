package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /v1/documents/{id}/fields: the per-document field projection with its evidence chain.
 * Shape assertions here are placeholder-stable (they hold before AND after the real engine
 * lands); cross-tenant ids answer 404 exactly like nonexistent ones.
 */
class DocumentFieldsApiIT extends AbstractExtractionIT {

    @Test
    void every_schema_field_comes_back_with_its_evidence_array() throws Exception {
        UUID packageId = insertPackage("fields-api-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.documentTypeCode").value("PAYSTUB"))
                // V53 supersedes V48's paystub@1.4.0 with 1.5.0: the same ten fields plus the
                // three closing totals and the five earnings-line fields, all AI-only, so
                // deterministically they are eight more MISSING rows — the five line fields as
                // one null-keyed occurrence each. The API reports the version that DECIDED.
                .andExpect(jsonPath("$.schemaVersion").value("1.5.0"))
                .andExpect(jsonPath("$.fields.length()").value(18))
                // The field id is what a client PATCHes to correct the field — without it,
                // GET fields → PATCH /v1/fields/{id} is a dead end (real-stack smoke finding).
                .andExpect(jsonPath("$.fields[0].id").isNotEmpty())
                // Alphabetical by field name — a stable, documented order.
                .andExpect(jsonPath("$.fields[0].fieldName").value("borrowerName"))
                // Spec 5a: an ungrouped field reports a key that is PRESENT and NULL, never
                // omitted, and the alphabetical order these positional assertions depend on is
                // unchanged — the ORDER BY breaks ties on group_key and a paystub has no ties.
                .andExpect(jsonPath("$.fields[0].groupKey").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fields[15].groupKey").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fields[0].dataType").value("STRING"))
                .andExpect(jsonPath("$.fields[0].sensitive").value(false))
                .andExpect(jsonPath("$.fields[0].reviewStatus").value("NOT_REVIEWED"))
                .andExpect(jsonPath("$.fields[0].evidence").isArray())
                // Alphabetically, ytdGrossPay now sits behind the five earning* fields and the
                // other scalars: index 15 of 18.
                .andExpect(jsonPath("$.fields[15].fieldName").value("ytdGrossPay"))
                .andExpect(jsonPath("$.fields[15].dataType").value("MONEY"))
                // A line field the rules could not read is a GROUPED missing occurrence: ROW
                // kind, null key — the "region not read" shape, never a document-level blank.
                .andExpect(jsonPath("$.fields[4].fieldName").value("earningDescription"))
                .andExpect(jsonPath("$.fields[4].groupKind").value("ROW"))
                .andExpect(jsonPath("$.fields[4].groupKey").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fields[4].extractionMethod").value("NONE"));
    }

    @Test
    void an_unknown_document_id_is_not_found() throws Exception {
        mockMvc.perform(get("/v1/documents/{id}/fields", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void another_orgs_document_is_indistinguishable_from_a_nonexistent_one() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        UUID foreignDocument = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package with fields");
        jdbc.update(
                """
                INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)
                VALUES (?, ?, ?, 0, 'PAYSTUB')
                """,
                foreignDocument,
                ORG_OTHER,
                foreignPackage);

        MvcResult result =
                mockMvc.perform(get("/v1/documents/{id}/fields", foreignDocument))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("foreign package with fields");
    }
}
