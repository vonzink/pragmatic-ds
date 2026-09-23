package com.pragmaticds.docengine.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /v1/documents/{id}/body.md} — the document's own prose, for a consumer that must read
 * a page rather than cite a field.
 *
 * <p>Why this endpoint has to exist. DOCENGINE-BODY-1 has been composable since the body module
 * landed, and nothing served it: {@code DocumentBodyMarkdown} had no caller outside its own
 * package. The only document rendering on the wire was {@code /fields.md}, which is a projection of
 * the EXTRACTION read model -- so a consumer could read exactly the fields some schema author named
 * in advance, and nothing else on the page.
 *
 * <p>That is the wrong artifact for an LLM asked to write a report across several documents, and it
 * fails hardest exactly where it is needed most: no classification means no schema, no schema means
 * no occurrences, and {@code /fields.md} renders an empty document. {@link DocumentBody}'s own
 * contract names the property that fixes it -- a body "needs no schema at all, which is also why it
 * is the only artifact that survives a document classified UNKNOWN".
 *
 * <p>So the headline test below seeds a document typed {@code UNKNOWN} with zero extracted fields
 * and demands its words back. If that ever regresses to an empty body, every downstream report is
 * reasoning about a blank page while looking like it worked.
 */
@AutoConfigureMockMvc
class DocumentBodyApiIT extends AbstractPostgresIT {

    @Autowired private MockMvc mockMvc;

    private JdbcTemplate jdbc;
    private UUID documentId;
    private UUID pageOne;
    private UUID pageTwo;

    @BeforeEach
    void seed() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);

        // Page-id order is deliberately the REVERSE of document order, as in
        // DocumentBodySourceLoaderIT: a body served in arrival order reads plausibly and is wrong.
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        boolean leftFirst = left.compareTo(right) < 0;
        pageOne = leftFirst ? right : left;
        pageTwo = leftFirst ? left : right;

        UUID packageId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'body-api-fixture')",
                packageId,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO source_file
                    (id, org_id, package_id, ordinal, original_filename, content_type,
                     declared_content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'body.pdf', 'application/pdf', 'application/pdf',
                        1024, ?, 'body-api-key')
                """,
                sourceId,
                ORG_DEV,
                packageId,
                "d".repeat(64));
        insertPage(pageOne, sourceId, packageId, 0, 4);
        insertPage(pageTwo, sourceId, packageId, 1, 5);

        documentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO logical_document
                    (id, org_id, package_id, ordinal, document_type_code)
                VALUES (?, ?, ?, 0, 'UNKNOWN')
                """,
                documentId,
                ORG_DEV,
                packageId);
        insertMembership(pageOne, 0);
        insertMembership(pageTwo, 1);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void servesTheProseOfADocumentNoSchemaEverNamed() throws Exception {
        insertElement(pageOne, "PARAGRAPH", 0, "Gross Pay 4030.77");
        insertElement(pageTwo, "PARAGRAPH", 0, "Net Pay 2705.73");

        String body =
                mockMvc.perform(get("/v1/documents/{id}/body.md", documentId))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        // The words are there, and in DOCUMENT order rather than page-id order.
        assertThat(body).contains("Gross Pay 4030.77", "Net Pay 2705.73");
        assertThat(body.indexOf("Gross Pay")).isLessThan(body.indexOf("Net Pay"));
    }

    @Test
    void isByteIdenticalOnASecondRead() throws Exception {
        insertElement(pageOne, "PARAGRAPH", 0, "same bytes every time");

        assertThat(render()).isEqualTo(render());
    }

    @Test
    void answersNotFoundForAnUnknownDocument() throws Exception {
        mockMvc.perform(get("/v1/documents/{id}/body.md", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void answersNotModifiedWhenTheCallerAlreadyHasTheseBytes() throws Exception {
        insertElement(pageOne, "PARAGRAPH", 0, "cacheable prose");

        String etag =
                mockMvc.perform(get("/v1/documents/{id}/body.md", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        mockMvc.perform(
                        get("/v1/documents/{id}/body.md", documentId)
                                .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    private String render() throws Exception {
        return mockMvc.perform(get("/v1/documents/{id}/body.md", documentId))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void insertPage(
            UUID pageId, UUID sourceId, UUID packageId, int pageIndex, int packagePageIndex) {
        jdbc.update(
                """
                INSERT INTO page
                    (id, org_id, source_file_id, package_id, page_index, package_page_index,
                     width_pt, height_pt, rotation, text_layer)
                VALUES (?, ?, ?, ?, ?, ?, 612, 792, 0, 'NATIVE')
                """,
                pageId,
                ORG_DEV,
                sourceId,
                packageId,
                pageIndex,
                packagePageIndex);
    }

    private void insertMembership(UUID pageId, int ordinal) {
        jdbc.update(
                """
                INSERT INTO logical_document_page (org_id, logical_document_id, page_id, ordinal)
                VALUES (?, ?, ?, ?)
                """,
                ORG_DEV,
                documentId,
                pageId,
                ordinal);
    }

    private void insertElement(UUID pageId, String elementType, int ordinal, String text) {
        jdbc.update(
                """
                INSERT INTO layout_element
                    (id, org_id, page_id, parent_element_id, element_type, ordinal,
                     x, y, width, height, text, confidence, detector, detector_version, attributes)
                VALUES (?, ?, ?, NULL, ?, ?, 10.00, 20.00, 30.00, 40.00, ?, 0.9500,
                        'fixture', '1.0.0', NULL)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                pageId,
                elementType,
                ordinal,
                text);
    }
}
