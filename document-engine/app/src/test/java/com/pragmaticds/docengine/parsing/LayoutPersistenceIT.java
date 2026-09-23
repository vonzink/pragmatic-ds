package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.parsing.client.LayoutWireElement;
import com.pragmaticds.docengine.parsing.client.NativeSpan;
import com.pragmaticds.docengine.parsing.client.RenderPageMeta;
import com.pragmaticds.docengine.parsing.client.RenderResult;
import com.pragmaticds.docengine.parsing.client.RenderedPage;
import com.pragmaticds.docengine.parsing.client.WorkerBlock;
import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.LayoutElementRepository;
import com.pragmaticds.docengine.parsing.repo.LayoutElementSpanRepository;
import com.pragmaticds.docengine.parsing.service.LayoutElementService;
import com.pragmaticds.docengine.parsing.service.PageService;
import com.pragmaticds.docengine.parsing.service.TextSpanService;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Layout element persistence (Phase 3): the V4 layout tables get their entities. Elements arrive
 * with REQUEST-SCOPED elementId/parentElementId strings and spanOrdinals; persistence resolves
 * parentage to row UUIDs (in any arrival order) and span links to real {@code text_span} rows via
 * the REQUEST ordinal — the caller's identifier on the wire, defined by the deterministic request
 * span list ({@code requestSpansFor}): NATIVE spans by ordinal, then OCR spans by ordinal. On
 * single-source pages that index equals the span's own DB ordinal; on MIXED pages (where both
 * sources restart at ordinal 0) it is what keeps the identifier unique.
 */
class LayoutPersistenceIT extends AbstractPostgresIT {

    @Autowired PageService pageService;
    @Autowired TextSpanService textSpanService;
    @Autowired LayoutElementService layoutElementService;
    @Autowired LayoutElementRepository layoutElements;
    @Autowired LayoutElementSpanRepository layoutElementSpans;

    private JdbcTemplate jdbc;
    private UUID packageId;
    private UUID sourceFileId;
    private Page page;

    private static final WorkerBlock WORKER = new WorkerBlock("0.3.0", Map.of());

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        packageId = UUID.randomUUID();
        sourceFileId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "layout-it");
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'a.pdf', 'application/pdf', 10, ?, 'k')
                """,
                sourceFileId,
                ORG_DEV,
                packageId,
                "0".repeat(64));
        page =
                pageService
                        .persistRenderedPages(
                                packageId,
                                sourceFileId,
                                0,
                                new RenderResult(
                                        WORKER,
                                        List.of(
                                                new RenderedPage(
                                                        new RenderPageMeta(
                                                                0,
                                                                new BigDecimal("612.0"),
                                                                new BigDecimal("792.0"),
                                                                0,
                                                                200,
                                                                1700,
                                                                2200,
                                                                "page-0"),
                                                        new byte[] {1}))))
                        .get(0);
        // Ordinals deliberately NOT starting at 0: span links must resolve by (page, ordinal),
        // never by row id arithmetic.
        textSpanService.persistNativeSpans(
                page,
                List.of(
                        nativeSpan(10, "Earnings"),
                        nativeSpan(11, "Rate"),
                        nativeSpan(12, "$48,231.30")));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static NativeSpan nativeSpan(int ordinal, String text) {
        return new NativeSpan(
                ordinal,
                text,
                new BigDecimal("72.0"),
                new BigDecimal("178.5"),
                new BigDecimal("47.9"),
                new BigDecimal("12.8"),
                new BigDecimal("11.0"),
                "Helvetica");
    }

    private static LayoutWireElement element(
            String id, String parentId, String type, int ordinal, List<Integer> spanOrdinals) {
        return new LayoutWireElement(
                id,
                parentId,
                type,
                ordinal,
                new BigDecimal("66.0"),
                new BigDecimal("168.0"),
                new BigDecimal("494.0"),
                new BigDecimal("110.0"),
                new BigDecimal("0.95"),
                "clustering",
                "0.3.0",
                "{\"row\":0,\"col\":0}",
                spanOrdinals);
    }

    @Test
    void persists_table_row_cell_tree_with_resolved_parentage_and_span_links() {
        // Deliberately child-first order: parentage resolution must not depend on the worker
        // emitting parents before children. Request ordinals 2 and 0 address the spans with DB
        // ordinals 12 and 10 — positions in the request list, never row-id arithmetic.
        layoutElementService.persistPageElements(
                page,
                List.of(
                        element("e2", "e1", "TABLE_CELL", 6, List.of(2, 0)),
                        element("e1", "e0", "TABLE_ROW", 5, List.of()),
                        element("e0", null, "TABLE", 4, List.of())),
                layoutElementService.requestSpansFor(page.getId()));

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM layout_element WHERE page_id = ? ORDER BY ordinal",
                        page.getId());
        assertThat(rows).hasSize(3);
        Map<String, Object> table = rows.get(0);
        Map<String, Object> row = rows.get(1);
        Map<String, Object> cell = rows.get(2);

        assertThat(table.get("element_type")).isEqualTo("TABLE");
        assertThat(table.get("parent_element_id")).isNull();
        assertThat(row.get("element_type")).isEqualTo("TABLE_ROW");
        assertThat(row.get("parent_element_id")).isEqualTo(table.get("id"));
        assertThat(cell.get("element_type")).isEqualTo("TABLE_CELL");
        assertThat(cell.get("parent_element_id")).isEqualTo(row.get("id"));

        // Canonical boxes and detector identity verbatim.
        assertThat((BigDecimal) table.get("x")).isEqualByComparingTo("66.0");
        assertThat((BigDecimal) table.get("confidence")).isEqualByComparingTo("0.95");
        assertThat(table.get("detector")).isEqualTo("clustering");
        assertThat(table.get("detector_version")).isEqualTo("0.3.0");
        assertThat(String.valueOf(cell.get("attributes"))).contains("\"col\"");
        assertThat(table.get("org_id")).isEqualTo(ORG_DEV);

        // Span links resolve to REAL text_span rows by (page, ordinal), preserving the
        // element's spanOrdinals order in the link ordinal.
        List<Map<String, Object>> links =
                jdbc.queryForList(
                        """
                        SELECT les.ordinal AS link_ordinal, ts.ordinal AS span_ordinal, ts.text
                        FROM layout_element_span les
                        JOIN text_span ts ON ts.id = les.text_span_id
                        WHERE les.layout_element_id = ?
                        ORDER BY les.ordinal
                        """,
                        cell.get("id"));
        assertThat(links).hasSize(2);
        assertThat(links.get(0).get("span_ordinal")).isEqualTo(12);
        assertThat(links.get(0).get("link_ordinal")).isEqualTo(0);
        assertThat(links.get(1).get("span_ordinal")).isEqualTo(10);
        assertThat(links.get(1).get("link_ordinal")).isEqualTo(1);

        // Denormalized convenience text follows spanOrdinals order.
        assertThat(cell.get("text")).isEqualTo("$48,231.30 Earnings");
        assertThat(table.get("text")).isNull();
    }

    @Test
    void mixed_page_request_ordinals_stay_unique_across_native_and_ocr_spans() {
        // MIXED pages carry BOTH sources, and each source's own ordinals restart at 0 — the
        // request ordinal (NATIVE block then OCR block) is what keeps the wire identifier unique.
        textSpanService.persistOcrSpans(
                page,
                List.of(
                        new com.pragmaticds.docengine.parsing.client.OcrSpan(
                                0,
                                "$1,200.00",
                                new BigDecimal("40.1"),
                                new BigDecimal("410.7"),
                                new BigDecimal("55.0"),
                                new BigDecimal("11.2"),
                                "RAPIDOCR",
                                new BigDecimal("0.95"))));

        List<com.pragmaticds.docengine.parsing.domain.TextSpan> requestSpans =
                layoutElementService.requestSpansFor(page.getId());
        assertThat(requestSpans).hasSize(4);
        assertThat(requestSpans.get(0).getText()).isEqualTo("Earnings");
        assertThat(requestSpans.get(3).getText()).isEqualTo("$1,200.00");

        layoutElementService.persistPageElements(
                page,
                List.of(element("e0", null, "PARAGRAPH", 0, List.of(3))),
                requestSpans);

        Map<String, Object> link =
                jdbc.queryForMap(
                        """
                        SELECT ts.text, ts.source FROM layout_element_span les
                        JOIN text_span ts ON ts.id = les.text_span_id
                        JOIN layout_element le ON le.id = les.layout_element_id
                        WHERE le.page_id = ?
                        """,
                        page.getId());
        assertThat(link.get("text")).isEqualTo("$1,200.00");
        assertThat(link.get("source")).isEqualTo("OCR");
    }

    @Test
    void delete_all_for_package_clears_elements_and_links_for_retry_idempotency() {
        layoutElementService.persistPageElements(
                page,
                List.of(
                        element("e0", null, "TABLE", 0, List.of()),
                        element("e1", "e0", "TABLE_CELL", 1, List.of(1))),
                layoutElementService.requestSpansFor(page.getId()));

        layoutElementService.deleteAllForPackage(packageId);
        layoutElementService.persistPageElements(
                page,
                List.of(element("e0", null, "PARAGRAPH", 0, List.of(0))),
                layoutElementService.requestSpansFor(page.getId()));

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM layout_element WHERE page_id = ?", page.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("element_type")).isEqualTo("PARAGRAPH");
        Integer linkCount =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM layout_element_span
                        WHERE layout_element_id IN
                            (SELECT id FROM layout_element WHERE page_id = ?)
                        """,
                        Integer.class,
                        page.getId());
        assertThat(linkCount).isEqualTo(1);
    }

    @Test
    void cross_tenant_layout_rows_are_invisible_to_the_new_repos() {
        // A foreign org's rows, inserted straight through SQL (the IT datasource is the container
        // superuser, so this bypasses RLS — app-layer @TenantId filtering is what's under test).
        UUID otherPage = UUID.randomUUID();
        // The id this test inserted, NOT "some package belonging to ORG_OTHER": the re-query it used
        // to do was `LIMIT 1` with no ORDER BY over a table other classes in this run also write to,
        // so which row came back was heap order. When it came back as another class's foreign
        // package — which already has a source_file at ordinal 0 — the insert below violated
        // source_file_package_ordinal_key and this test failed for a reason it is not about.
        UUID otherPackage = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'other-pkg')",
                otherPackage,
                ORG_OTHER);
        UUID otherFile = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'b.pdf', 'application/pdf', 10, ?, 'k2')
                """,
                otherFile,
                ORG_OTHER,
                otherPackage,
                "1".repeat(64));
        jdbc.update(
                """
                INSERT INTO page (id, org_id, source_file_id, package_id, page_index,
                    package_page_index, width_pt, height_pt)
                VALUES (?, ?, ?, ?, 0, 0, 612.0, 792.0)
                """,
                otherPage,
                ORG_OTHER,
                otherFile,
                otherPackage);
        UUID otherElement = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, element_type, ordinal,
                    x, y, width, height, confidence, detector, detector_version)
                VALUES (?, ?, ?, 'PARAGRAPH', 0, 1, 1, 1, 1, 0.9, 'clustering', '0.3.0')
                """,
                otherElement,
                ORG_OTHER,
                otherPage);

        // Under ORG_DEV, the foreign rows do not exist for the repositories.
        assertThat(layoutElements.findByIdAndOrgId(otherElement, ORG_DEV)).isEmpty();
        assertThat(layoutElements.findByPageIdOrderByOrdinal(otherPage)).isEmpty();
        assertThat(layoutElements.findByPageIdInOrderByPageIdAscOrdinalAsc(List.of(otherPage)))
                .isEmpty();

        // And the honest control: they DO exist under their own org.
        TenantContext.set(ORG_OTHER);
        List<LayoutElement> visible = layoutElements.findByPageIdOrderByOrdinal(otherPage);
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getId()).isEqualTo(otherElement);
    }
}
