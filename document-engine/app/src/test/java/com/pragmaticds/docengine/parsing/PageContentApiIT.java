package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The endpoints that make the evidence chain renderable (Phase 6): page geometry, page rasters,
 * and the original file bytes. Every stored evidence box is in PDF points at rotation-0, so a
 * client without page dimensions and rotation cannot place a highlight at all.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2"})
class PageContentApiIT extends AbstractPostgresIT {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final byte[] PDF = "%PDF-1.7 synthetic".getBytes();

    @Autowired MockMvc mockMvc;
    @Autowired BlobStoragePort storage;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bind() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    private UUID insertPackage(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                id,
                orgId,
                "pages-it");
        return id;
    }

    private UUID insertSourceFile(UUID packageId, UUID orgId, String storageKey) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original, is_encrypted)
                VALUES (?, ?, ?, 0, 'doc.pdf', 'application/pdf', ?, ?, ?, false)
                """,
                id,
                orgId,
                packageId,
                (long) PDF.length,
                "a".repeat(64),
                storageKey);
        return id;
    }

    /** A JPEG upload: the owner's case, and the reason sourceContentType exists. */
    private UUID insertImageSourceFile(UUID packageId, UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original, is_encrypted, page_count)
                VALUES (?, ?, ?, 1, 'IMG_4417.jpg', 'image/jpeg', 331000, ?, 'img-orig-key', false, 1)
                """,
                id,
                orgId,
                packageId,
                "c".repeat(64));
        return id;
    }

    private UUID insertPage(
            UUID packageId, UUID sourceFileId, UUID orgId, int index, String renderKey) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO page (id, org_id, source_file_id, package_id, page_index,
                    package_page_index, width_pt, height_pt, rotation, text_layer, is_blank,
                    render_storage_key, render_dpi)
                VALUES (?, ?, ?, ?, ?, ?, 612.00, 792.00, 90, 'NATIVE', false, ?, 200)
                """,
                id,
                orgId,
                sourceFileId,
                packageId,
                index,
                index,
                renderKey);
        return id;
    }

    @Test
    void page_geometry_is_served_so_a_client_can_place_an_evidence_box() throws Exception {
        UUID packageId = insertPackage(ORG_DEV);
        UUID fileId = insertSourceFile(packageId, ORG_DEV, "orig-key");
        UUID pageId = insertPage(packageId, fileId, ORG_DEV, 0, "render-key");

        mockMvc.perform(get("/v1/packages/{id}/pages", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageId").value(packageId.toString()))
                .andExpect(jsonPath("$.pages.length()").value(1))
                .andExpect(jsonPath("$.pages[0].pageId").value(pageId.toString()))
                .andExpect(jsonPath("$.pages[0].sourceFileId").value(fileId.toString()))
                .andExpect(jsonPath("$.pages[0].packagePageIndex").value(0))
                // The three values a coordinate conversion cannot work without.
                .andExpect(jsonPath("$.pages[0].widthPt").value(612.00))
                .andExpect(jsonPath("$.pages[0].heightPt").value(792.00))
                .andExpect(jsonPath("$.pages[0].rotation").value(90))
                .andExpect(jsonPath("$.pages[0].renderDpi").value(200))
                .andExpect(jsonPath("$.pages[0].hasRender").value(true))
                // What KIND of file this page came from. A viewer that hands every source to
                // pdf.js wastes a fetch and a parse on every photographed paystub, then apologises
                // for a PDF that was never there.
                .andExpect(jsonPath("$.pages[0].sourceContentType").value("application/pdf"));
    }

    @Test
    void an_image_backed_page_reports_its_source_as_an_image() throws Exception {
        UUID packageId = insertPackage(ORG_DEV);
        UUID fileId = insertImageSourceFile(packageId, ORG_DEV);
        insertPage(packageId, fileId, ORG_DEV, 0, "render-key-img");

        mockMvc.perform(get("/v1/packages/{id}/pages", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].sourceContentType").value("image/jpeg"))
                // The raster is served the same way for both kinds — a page render is a PNG
                // whatever produced it, which is why the overlay maths never has to know.
                .andExpect(jsonPath("$.pages[0].hasRender").value(true));
    }

    @Test
    void a_package_awaiting_rendering_answers_an_empty_list_not_a_404() throws Exception {
        // The UI polls while the pipeline runs. Inferring absence from an empty page list would
        // answer 404 for a perfectly valid package that simply has not been rendered yet.
        UUID packageId = insertPackage(ORG_DEV);

        mockMvc.perform(get("/v1/packages/{id}/pages", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages.length()").value(0));
    }

    @Test
    void the_page_raster_is_served_as_png() throws Exception {
        UUID packageId = insertPackage(ORG_DEV);
        UUID fileId = insertSourceFile(packageId, ORG_DEV, "orig-key");
        String renderKey = ORG_DEV + "/" + packageId + "/pages/render.png";
        storage.put(renderKey, PNG);
        UUID pageId = insertPage(packageId, fileId, ORG_DEV, 0, renderKey);

        byte[] served =
                mockMvc.perform(get("/v1/pages/{id}/render", pageId))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(MediaType.IMAGE_PNG))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        assertThat(served).isEqualTo(PNG);
    }

    @Test
    void the_original_bytes_are_served_with_the_sniffed_type_for_pdfjs() throws Exception {
        UUID packageId = insertPackage(ORG_DEV);
        String originalKey = ORG_DEV + "/" + packageId + "/file/original";
        storage.put(originalKey, PDF);
        UUID fileId = insertSourceFile(packageId, ORG_DEV, originalKey);

        byte[] served =
                mockMvc.perform(get("/v1/files/{id}/content", fileId))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .header()
                                        .string("X-Content-Type-Options", "nosniff"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        assertThat(served).isEqualTo(PDF);
    }

    @Test
    void a_page_whose_render_blob_is_missing_is_absent_not_a_server_error() throws Exception {
        UUID packageId = insertPackage(ORG_DEV);
        UUID fileId = insertSourceFile(packageId, ORG_DEV, "orig-key");
        UUID pageId = insertPage(packageId, fileId, ORG_DEV, 0, "key-that-was-never-written");

        mockMvc.perform(get("/v1/pages/{id}/render", pageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void another_orgs_pages_are_indistinguishable_from_a_nonexistent_package() throws Exception {
        UUID foreignPackage = insertPackage(ORG_OTHER);
        UUID foreignFile = insertSourceFile(foreignPackage, ORG_OTHER, "foreign-orig");
        UUID foreignPage = insertPage(foreignPackage, foreignFile, ORG_OTHER, 0, "foreign-render");

        mockMvc.perform(get("/v1/packages/{id}/pages", foreignPackage))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/v1/pages/{id}/render", foreignPage))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/files/{id}/content", foreignFile))
                .andExpect(status().isNotFound());

        // Identical to a wholly unknown id — no id is ever confirmed to exist elsewhere.
        mockMvc.perform(get("/v1/packages/{id}/pages", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
