package com.pragmaticds.docengine.results;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.body.DocumentBody;
import com.pragmaticds.docengine.results.body.DocumentBody.BodyBlock;
import com.pragmaticds.docengine.results.body.DocumentBodyComposer;
import com.pragmaticds.docengine.results.body.DocumentBodySource;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceElement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database contract the unit tests cannot state: that the REAL batch layout query, whose ORDER
 * BY is a UUID, still yields a body in document reading order.
 *
 * <p>The two pages are seeded so that page-id order is the exact REVERSE of document order. A
 * loader that passed arrival order through — or a composer that trusted it —
 * emits both pages' prose transposed while every citation box stays individually correct, which is
 * the failure that reads plausibly and survives a smoke test.
 */
class DocumentBodySourceLoaderIT extends AbstractPostgresIT {

    @Autowired
    private com.pragmaticds.docengine.results.body.DocumentBodySourceLoader loader;

    private JdbcTemplate jdbc;
    private UUID documentId;

    /** Sorts SECOND by page id, and is the document's FIRST page. */
    private UUID pageOne;

    /** Sorts FIRST by page id, and is the document's SECOND page. */
    private UUID pageTwo;

    @BeforeEach
    void seed() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);

        // Fresh ids each run (the container outlives a test class), ASSIGNED so that page-id
        // order is the reverse of document order — the disagreement is the whole fixture.
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        boolean leftFirst = left.compareTo(right) < 0;
        pageOne = leftFirst ? right : left;
        pageTwo = leftFirst ? left : right;

        UUID packageId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'body-fixture')",
                packageId,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO source_file
                    (id, org_id, package_id, ordinal, original_filename, content_type,
                     declared_content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'body.pdf', 'application/pdf', 'application/pdf',
                        1024, ?, 'body-key')
                """,
                sourceId,
                ORG_DEV,
                packageId,
                "c".repeat(64));
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
        // Document order deliberately disagrees with page-id order.
        insertMembership(pageOne, 0);
        insertMembership(pageTwo, 1);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void composesInDocumentOrderEvenThoughTheBatchQueryReturnsPageIdOrder() {
        insertElement(pageOne, null, "PARAGRAPH", 0, "first page", null);
        insertElement(pageTwo, null, "PARAGRAPH", 0, "second page", null);

        DocumentBody body = DocumentBodyComposer.compose(loader.load(documentId).orElseThrow());

        assertThat(body.blocks())
                .extracting(BodyBlock::text)
                .containsExactly("first page", "second page");
        assertThat(body.blocks())
                .extracting(BodyBlock::packagePageIndex)
                .containsExactly(4, 5);
        // The headline case: no schema, no extraction, still a body.
        assertThat(body.documentTypeCode()).isEqualTo("UNKNOWN");
        assertThat(body.pageCount()).isEqualTo(2);
    }

    @Test
    void decodesAttributesAndMinimumSpanConfidenceOffRealRows() {
        UUID table =
                insertElement(pageOne, null, "TABLE", 0, null, "{\"rows\":1,\"cols\":2,\"ruled\":true}");
        UUID row = insertElement(pageOne, table, "TABLE_ROW", 1, null, null);
        UUID cell = insertElement(pageOne, row, "TABLE_CELL", 2, "gross", "{\"row\":0,\"col\":1}");
        UUID checkbox = insertElement(pageOne, null, "CHECKBOX", 3, null, "{}");
        long low = insertSpan(pageOne, 0, "gross", "0.4100");
        long high = insertSpan(pageOne, 1, "gross", "0.9900");
        // Link order is the LINK's ordinal, not the span id: the high span is claimed first.
        insertLink(cell, high, 0);
        insertLink(cell, low, 1);

        DocumentBodySource source = loader.load(documentId).orElseThrow();
        var elements = source.pages().get(0).elements();

        SourceElement tableRow = byId(elements, table);
        assertThat(tableRow.tableRows()).isEqualTo(1);
        assertThat(tableRow.tableCols()).isEqualTo(2);
        assertThat(tableRow.tableRuled()).isTrue();

        SourceElement cellRow = byId(elements, cell);
        assertThat(cellRow.cellRow()).isZero();
        assertThat(cellRow.cellCol()).isEqualTo(1);
        assertThat(cellRow.spanIds()).containsExactly(high, low);
        assertThat(cellRow.textConfidence()).isEqualByComparingTo("0.4100");

        // An undeclared checkbox state is "the detector could not tell", never false.
        assertThat(byId(elements, checkbox).checked()).isNull();
        assertThat(byId(elements, checkbox).textConfidence()).isNull();

        // Children arrive unfiltered — the composer needs the whole tree.
        assertThat(elements).hasSize(4);
    }

    @Test
    void aDocumentInAnotherOrgIsIndistinguishableFromOneThatDoesNotExist() {
        TenantContext.set(ORG_OTHER);

        assertThat(loader.load(documentId)).isEmpty();
        assertThat(loader.load(UUID.randomUUID())).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static SourceElement byId(java.util.List<SourceElement> elements, UUID id) {
        return elements.stream()
                .filter(element -> element.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void insertPage(
            UUID id, UUID sourceId, UUID packageId, int pageIndex, int packagePageIndex) {
        jdbc.update(
                """
                INSERT INTO page
                    (id, org_id, source_file_id, package_id, page_index, package_page_index,
                     width_pt, height_pt, rotation, text_layer)
                VALUES (?, ?, ?, ?, ?, ?, 612.00, 792.00, 0, 'NATIVE')
                """,
                id,
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

    private UUID insertElement(
            UUID pageId, UUID parentId, String type, int ordinal, String text, String attributes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element
                    (id, org_id, page_id, parent_element_id, element_type, ordinal,
                     x, y, width, height, text, confidence, detector, detector_version, attributes)
                VALUES (?, ?, ?, ?, ?, ?, 10.00, 20.00, 30.00, 40.00, ?, 0.9500,
                        'fixture', '1.0.0', ?::jsonb)
                """,
                id,
                ORG_DEV,
                pageId,
                parentId,
                type,
                ordinal,
                text,
                attributes);
        return id;
    }

    private long insertSpan(UUID pageId, int ordinal, String text, String confidence) {
        return jdbc.queryForObject(
                """
                INSERT INTO text_span
                    (org_id, page_id, ordinal, text, x, y, width, height, source, confidence)
                VALUES (?, ?, ?, ?, 10.00, 20.00, 30.00, 40.00, 'NATIVE', ?::numeric)
                RETURNING id
                """,
                Long.class,
                ORG_DEV,
                pageId,
                ordinal,
                text,
                confidence);
    }

    private void insertLink(UUID elementId, long spanId, int ordinal) {
        jdbc.update(
                """
                INSERT INTO layout_element_span
                    (layout_element_id, text_span_id, org_id, ordinal)
                VALUES (?, ?, ?, ?)
                """,
                elementId,
                spanId,
                ORG_DEV,
                ordinal);
    }
}
