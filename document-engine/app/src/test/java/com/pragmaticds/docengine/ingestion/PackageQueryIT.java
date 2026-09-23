package com.pragmaticds.docengine.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /v1/packages/{id}: the package with its files in ordinal order, and — the isolation case
 * that matters — a cross-tenant id answers 404 exactly like a nonexistent one, so another org's
 * package ids are not even confirmed to exist.
 */
class PackageQueryIT extends AbstractIngestionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void a_package_comes_back_with_files_in_ordinal_order() throws Exception {
        MvcResult uploaded =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("first.pdf", "application/pdf", pdfWithPages(1)))
                                        .file(filePart("second.png", "image/png", pngBytes(32)))
                                        .file(filePart("third.jpg", "image/jpeg", jpegBytes()))
                                        .param("name", "mixed package"))
                        .andExpect(status().isAccepted())
                        .andReturn();
        String packageId =
                JSON.readTree(uploaded.getResponse().getContentAsString()).get("packageId").asText();

        mockMvc.perform(get("/v1/packages/{id}", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(packageId))
                .andExpect(jsonPath("$.name").value("mixed package"))
                .andExpect(jsonPath("$.reviewStatus").value("NOT_REVIEWED"))
                // One PDF page plus one page for each of the two images: every accepted file has
                // a page count now, so a mixed package's total finally describes the whole package.
                .andExpect(jsonPath("$.pageCount").value(3))
                .andExpect(jsonPath("$.files.length()").value(3))
                .andExpect(jsonPath("$.files[0].ordinal").value(0))
                .andExpect(jsonPath("$.files[0].originalFilename").value("first.pdf"))
                .andExpect(jsonPath("$.files[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.files[1].ordinal").value(1))
                .andExpect(jsonPath("$.files[1].originalFilename").value("second.png"))
                .andExpect(jsonPath("$.files[1].contentType").value("image/png"))
                .andExpect(jsonPath("$.files[2].ordinal").value(2))
                .andExpect(jsonPath("$.files[2].originalFilename").value("third.jpg"))
                .andExpect(jsonPath("$.files[2].contentType").value("image/jpeg"));
    }

    @Test
    void an_unknown_id_is_not_found() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()))
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
                "other org package");

        MvcResult result =
                mockMvc.perform(get("/v1/packages/{id}", foreignPackage))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                        .andReturn();

        // No existence leak: the body must not echo anything about the foreign row.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("other org package");
    }
}
