package com.pragmaticds.docengine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The RBAC matrix and the cross-tenant-is-404 rule, exercised through the real filter chain (dev
 * profile). Roles are driven per request via {@code X-Dev-Role}; org via {@code X-Dev-Org}. No IdP.
 *
 * <p>RBAC: READONLY may read but not upload or resume; PROCESSOR/REVIEWER may. Cross-tenant: a
 * subject that belongs to another org is 404 on every read endpoint — never 403, which would
 * confirm the id exists elsewhere. The {@code X-Dev-Org} switch proving the same id returns 200 for
 * its owner is what shows the 404 was tenancy, not absence.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2"})
class RbacAndCrossTenantIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    private JdbcTemplate jdbc;

    // ORG_OTHER-owned subjects, seeded per test.
    private UUID otherPackage;
    private UUID otherFile;
    private UUID otherPage;
    private UUID otherDocument;

    @BeforeEach
    void seedForeignFixtures() {
        jdbc = new JdbcTemplate(dataSource);
        otherPackage = UUID.randomUUID();
        otherFile = UUID.randomUUID();
        otherPage = UUID.randomUUID();
        otherDocument = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                otherPackage,
                ORG_OTHER,
                "foreign-pkg");
        jdbc.update(
                "INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,"
                    + " content_type, size_bytes, sha256, storage_key_original) VALUES"
                    + " (?, ?, ?, 0, 'f.pdf', 'application/pdf', 10, ?, 'k/original')",
                otherFile,
                ORG_OTHER,
                otherPackage,
                "a".repeat(64));
        jdbc.update(
                "INSERT INTO page (id, org_id, source_file_id, package_id, page_index,"
                    + " package_page_index, width_pt, height_pt) VALUES (?, ?, ?, ?, 0, 0, 612.00,"
                    + " 792.00)",
                otherPage,
                ORG_OTHER,
                otherFile,
                otherPackage);
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)"
                    + " VALUES (?, ?, ?, 0, 'PAYSTUB')",
                otherDocument,
                ORG_OTHER,
                otherPackage);
    }

    // ── RBAC ────────────────────────────────────────────────────────────────

    @Test
    void readonly_may_read() throws Exception {
        // Authorized read of a nonexistent id resolves to 404, not 403 — the role gate passed.
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readonly_may_not_upload() throws Exception {
        mockMvc.perform(multipart("/v1/packages").header("X-Dev-Role", "READONLY"))
                .andExpect(status().isForbidden());
    }

    @Test
    void processor_may_upload() throws Exception {
        // Authorized: reaches the controller and its EMPTY_UPLOAD guard (400), not 403.
        mockMvc.perform(multipart("/v1/packages").header("X-Dev-Role", "PROCESSOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_UPLOAD"));
    }

    @Test
    void reviewer_is_at_least_a_processor() throws Exception {
        mockMvc.perform(multipart("/v1/packages").header("X-Dev-Role", "REVIEWER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_UPLOAD"));
    }

    @Test
    void readonly_may_not_resume() throws Exception {
        mockMvc.perform(
                        post("/v1/jobs/{id}/resume", UUID.randomUUID()).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isForbidden());
    }

    @Test
    void processor_may_resume() throws Exception {
        // Authorized: an unknown job id is a tenancy-invisible 404, not a 403.
        mockMvc.perform(
                        post("/v1/jobs/{id}/resume", UUID.randomUUID()).header("X-Dev-Role", "PROCESSOR"))
                .andExpect(status().isNotFound());
    }

    // ── Cross-tenant is 404, never 403 ────────────────────────────────────────

    @Test
    void another_orgs_package_is_404() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}", otherPackage))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void another_orgs_file_content_is_404() throws Exception {
        mockMvc.perform(get("/v1/files/{id}/content", otherFile))
                .andExpect(status().isNotFound());
    }

    @Test
    void another_orgs_page_render_is_404() throws Exception {
        mockMvc.perform(get("/v1/pages/{id}/render", otherPage)).andExpect(status().isNotFound());
    }

    @Test
    void another_orgs_documents_projection_is_404() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}/documents", otherPackage))
                .andExpect(status().isNotFound());
    }

    @Test
    void another_orgs_document_fields_are_404() throws Exception {
        mockMvc.perform(get("/v1/documents/{id}/fields", otherDocument))
                .andExpect(status().isNotFound());
    }

    @Test
    void another_orgs_export_is_404() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}/export", otherPackage))
                .andExpect(status().isNotFound());
    }

    @Test
    void the_owner_org_sees_its_own_package_so_the_404_was_tenancy_not_absence() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}", otherPackage).header("X-Dev-Org", ORG_OTHER.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(otherPackage.toString()));
    }
}
