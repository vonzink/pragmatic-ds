package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.parsing.client.NativeSpan;
import com.pragmaticds.docengine.parsing.client.OcrSpan;
import com.pragmaticds.docengine.parsing.client.RenderPageMeta;
import com.pragmaticds.docengine.parsing.client.RenderResult;
import com.pragmaticds.docengine.parsing.client.RenderedPage;
import com.pragmaticds.docengine.parsing.client.WorkerBlock;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextLayer;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.service.PageService;
import com.pragmaticds.docengine.parsing.service.ParserOutputService;
import com.pragmaticds.docengine.parsing.service.TextSpanService;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The parsing persistence layer: page rows + PNG blobs, text spans stored VERBATIM in canonical
 * space (0.1pt values arrive as-sent, never re-derived), and parser_output rows whose payload
 * lives in blob storage with only the digest in the row.
 */
class ParsedContentPersistenceIT extends AbstractPostgresIT {

    @Autowired PageService pageService;
    @Autowired TextSpanService textSpanService;
    @Autowired ParserOutputService parserOutputService;
    @Autowired PageRepository pageRepository;
    @Autowired BlobStoragePort storage;

    private JdbcTemplate jdbc;
    private UUID packageId;
    private UUID sourceFileId;

    private static final WorkerBlock WORKER =
            new WorkerBlock("0.2.0", Map.of("pypdfium2", "5.12.1"));

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
                "persistence-it");
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
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private RenderResult twoPageRender(byte[] png0, byte[] png1) {
        return new RenderResult(
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
                                png0),
                        new RenderedPage(
                                new RenderPageMeta(
                                        1,
                                        new BigDecimal("612.0"),
                                        new BigDecimal("792.0"),
                                        90,
                                        200,
                                        2200,
                                        1700,
                                        "page-1"),
                                png1)));
    }

    @Test
    void persistRenderedPages_stores_rows_blobs_and_content_hashes() {
        byte[] png0 = "png-zero".getBytes(StandardCharsets.UTF_8);
        byte[] png1 = "png-one".getBytes(StandardCharsets.UTF_8);

        List<Page> pages =
                pageService.persistRenderedPages(packageId, sourceFileId, 5, twoPageRender(png0, png1));

        assertThat(pages).hasSize(2);
        Page first = pages.get(0);
        assertThat(first.getPageIndex()).isEqualTo(0);
        assertThat(first.getPackagePageIndex()).isEqualTo(5);
        assertThat(pages.get(1).getPackagePageIndex()).isEqualTo(6);
        assertThat(pages.get(1).getRotation()).isEqualTo(90);

        Map<String, Object> row =
                jdbc.queryForMap("SELECT * FROM page WHERE id = ?", first.getId());
        assertThat(((BigDecimal) row.get("width_pt"))).isEqualByComparingTo("612.0");
        assertThat(((BigDecimal) row.get("height_pt"))).isEqualByComparingTo("792.0");
        assertThat(row.get("render_dpi")).isEqualTo(200);
        assertThat(row.get("text_layer")).isEqualTo("NONE");
        assertThat(row.get("content_hash")).isEqualTo(Digests.sha256Hex(png0));
        assertThat(row.get("org_id")).isEqualTo(ORG_DEV);

        String key = ORG_DEV + "/" + packageId + "/pages/" + first.getId() + ".png";
        assertThat(row.get("render_storage_key")).isEqualTo(key);
        assertThat(storage.get(key)).isEqualTo(png0);
    }

    @Test
    void native_spans_persist_verbatim_with_confidence_one() {
        Page page =
                pageService
                        .persistRenderedPages(
                                packageId, sourceFileId, 0, twoPageRender(new byte[] {1}, new byte[] {2}))
                        .get(0);
        page.applyTextVerdict(TextLayer.NATIVE, null, null);

        textSpanService.persistNativeSpans(
                page,
                List.of(
                        new NativeSpan(
                                0,
                                "YTD Gross",
                                new BigDecimal("112.3"),
                                new BigDecimal("84.0"),
                                new BigDecimal("58.2"),
                                new BigDecimal("10.5"),
                                new BigDecimal("10.5"),
                                "Helvetica-Bold")));

        Map<String, Object> row =
                jdbc.queryForMap("SELECT * FROM text_span WHERE page_id = ?", page.getId());
        assertThat(row.get("text")).isEqualTo("YTD Gross");
        assertThat((BigDecimal) row.get("x")).isEqualByComparingTo("112.3");
        assertThat((BigDecimal) row.get("y")).isEqualByComparingTo("84.0");
        assertThat((BigDecimal) row.get("width")).isEqualByComparingTo("58.2");
        assertThat((BigDecimal) row.get("height")).isEqualByComparingTo("10.5");
        assertThat(row.get("source")).isEqualTo("NATIVE");
        assertThat(row.get("ocr_engine")).isNull();
        assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo("1.0");
        assertThat((BigDecimal) row.get("font_size")).isEqualByComparingTo("10.5");
        assertThat(row.get("font_name")).isEqualTo("Helvetica-Bold");
    }

    @Test
    void ocr_spans_record_engine_per_span() {
        Page page =
                pageService
                        .persistRenderedPages(
                                packageId, sourceFileId, 0, twoPageRender(new byte[] {1}, new byte[] {2}))
                        .get(0);

        textSpanService.persistOcrSpans(
                page,
                List.of(
                        new OcrSpan(
                                0,
                                "$48,231.30",
                                new BigDecimal("112.3"),
                                new BigDecimal("84.0"),
                                new BigDecimal("61.0"),
                                new BigDecimal("11.2"),
                                "RAPIDOCR",
                                new BigDecimal("0.97")),
                        new OcrSpan(
                                1,
                                "NET",
                                new BigDecimal("10.0"),
                                new BigDecimal("20.0"),
                                new BigDecimal("30.0"),
                                new BigDecimal("11.0"),
                                "TESSERACT",
                                new BigDecimal("0.81"))));

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM text_span WHERE page_id = ? ORDER BY ordinal", page.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("source")).isEqualTo("OCR");
        assertThat(rows.get(0).get("ocr_engine")).isEqualTo("RAPIDOCR");
        assertThat((BigDecimal) rows.get(0).get("confidence")).isEqualByComparingTo("0.97");
        assertThat(rows.get(1).get("ocr_engine")).isEqualTo("TESSERACT");
        assertThat((BigDecimal) rows.get(1).get("confidence")).isEqualByComparingTo("0.81");
    }

    @Test
    void delete_by_source_clears_only_that_source_for_retry_idempotency() {
        Page page =
                pageService
                        .persistRenderedPages(
                                packageId, sourceFileId, 0, twoPageRender(new byte[] {1}, new byte[] {2}))
                        .get(0);
        textSpanService.persistNativeSpans(
                page,
                List.of(
                        new NativeSpan(
                                0,
                                "keep",
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                null,
                                null)));
        textSpanService.persistOcrSpans(
                page,
                List.of(
                        new OcrSpan(
                                0,
                                "drop",
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                "RAPIDOCR",
                                new BigDecimal("0.5"))));

        textSpanService.deleteBySource(packageId, SpanSource.OCR);

        List<String> remaining =
                jdbc.queryForList(
                        "SELECT source FROM text_span WHERE page_id = ?", String.class, page.getId());
        assertThat(remaining).containsExactly("NATIVE");
    }

    @Test
    void parser_output_blobs_payload_and_records_digest() {
        byte[] payload = "{\"worker\":{\"version\":\"0.2.0\"}}".getBytes(StandardCharsets.UTF_8);

        parserOutputService.persist(
                sourceFileId, null, "TEXT_EXTRACTION", "pdfplumber", "0.11.10", payload);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM parser_output WHERE source_file_id = ?", sourceFileId);
        assertThat(row.get("stage")).isEqualTo("TEXT_EXTRACTION");
        assertThat(row.get("parser_name")).isEqualTo("pdfplumber");
        assertThat(row.get("parser_version")).isEqualTo("0.11.10");
        assertThat(row.get("payload_sha256")).isEqualTo(Digests.sha256Hex(payload));
        assertThat(row.get("page_id")).isNull();
        String key = (String) row.get("payload_storage_key");
        assertThat(key).startsWith(ORG_DEV + "/parser-output/");
        assertThat(storage.get(key)).isEqualTo(payload);
    }

    @Test
    void deleteAllForPackage_removes_pages_and_their_spans() {
        Page page =
                pageService
                        .persistRenderedPages(
                                packageId, sourceFileId, 0, twoPageRender(new byte[] {1}, new byte[] {2}))
                        .get(0);
        textSpanService.persistNativeSpans(
                page,
                List.of(
                        new NativeSpan(
                                0,
                                "x",
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                new BigDecimal("1.0"),
                                null,
                                null)));

        UUID pageId = page.getId();
        pageService.deleteAllForPackage(packageId);

        assertThat(pageRepository.findByPackageIdOrderByPackagePageIndex(packageId)).isEmpty();
        // Scoped to this package's pages: the Postgres container is shared across IT classes.
        Integer spanCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM text_span WHERE page_id = ?", Integer.class, pageId);
        assertThat(spanCount).isZero();
    }
}
