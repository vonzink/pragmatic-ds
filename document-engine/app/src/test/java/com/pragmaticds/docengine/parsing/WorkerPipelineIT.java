package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The Phase 2 acceptance surface: the FULL pipeline with {@code docengine.processing.adapter=worker}
 * against MockWebServer serving contract fixtures.
 *
 * <p>Key assertions, per docs/IMPLEMENTATION_PLAN.md Phase 2: package-wide page ordering across a
 * 2-file package; geometry and spans stored VERBATIM (0.1pt values); a NATIVE page produces ZERO
 * {@code /v1/ocr} calls and a SCANNED page exactly one — asserted on the recorded request log, not
 * assumed; per-span OCR engines; both OCR raws in parser_output; worker failure and resume through
 * the existing stage machinery.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true",
            "docengine.processing.adapter=worker",
            "docengine.worker.shared-secret=it-worker-secret"
        })
class WorkerPipelineIT extends AbstractPostgresIT {

    private static final MockWebServer WORKER = new MockWebServer();

    static {
        try {
            WORKER.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void workerUrl(DynamicPropertyRegistry registry) {
        registry.add("docengine.worker.base-url", () -> WORKER.url("/").toString());
    }

    private static final String TEXT_A =
            """
            {
              "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "NATIVE",
                  "spans": [
                    { "ordinal": 0, "text": "YTD Gross",
                      "x": 112.3, "y": 84.0, "width": 58.2, "height": 10.5,
                      "fontSize": 10.5, "fontName": "Helvetica-Bold" },
                    { "ordinal": 1, "text": "$48,231.30",
                      "x": 176.1, "y": 84.0, "width": 61.0, "height": 10.5,
                      "fontSize": 10.5, "fontName": "Helvetica" }
                  ]
                },
                {
                  "pageIndex": 1, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "NATIVE",
                  "spans": [
                    { "ordinal": 0, "text": "Deductions",
                      "x": 72.0, "y": 61.9, "width": 65.3, "height": 13.0,
                      "fontSize": 13.0, "fontName": "Helvetica-Bold" }
                  ]
                }
              ]
            }
            """;

    private static final String TEXT_B_SCANNED =
            """
            {
              "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "SCANNED", "spans": [] }
              ]
            }
            """;

    /** A layout response with no elements — PARSING still runs once per source file (Phase 3). */
    private static final String LAYOUT_EMPTY_ONE_PAGE =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "elements": [], "notImplemented": ["CHECKBOX", "SIGNATURE"] }
              ]
            }
            """;

    private static final String LAYOUT_EMPTY_TWO_PAGES =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "elements": [], "notImplemented": ["CHECKBOX", "SIGNATURE"] },
                { "pageIndex": 1, "elements": [], "notImplemented": ["CHECKBOX", "SIGNATURE"] }
              ]
            }
            """;

    /** Fallback ran: page winner RAPIDOCR, one reconciled TESSERACT span, BOTH raws present. */
    private static final String OCR_B =
            """
            {
              "worker": { "version": "0.2.0", "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
              "pageIndex": 0,
              "detectedRotation": 90,
              "osdConfidence": 0.94,
              "engine": "RAPIDOCR",
              "fallbackReason": "G4_NUMERIC",
              "confidenceMedian": 0.91,
              "spans": [
                { "ordinal": 0, "text": "$48,231.30",
                  "x": 112.3, "y": 84.0, "width": 61.0, "height": 11.2,
                  "engine": "RAPIDOCR", "confidence": 0.97 },
                { "ordinal": 1, "text": "NET",
                  "x": 112.3, "y": 104.6, "width": 24.8, "height": 11.2,
                  "engine": "TESSERACT", "confidence": 0.81 }
              ],
              "gates": {
                "G4_NUMERIC": {"tripped": true, "value": 0.61, "threshold": 0.75}
              },
              "raw": {
                "rapidocr": { "spans": ["raw-rapid"] },
                "tesseract": { "spans": ["raw-tess"] }
              }
            }
            """;

    @Autowired JobService jobService;
    @Autowired ProcessingStageRepository stageRepository;
    @Autowired BlobStoragePort storage;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        WORKER.setDispatcher(new QueueDispatcher());
        drainRecordedRequests();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void drainRecordedRequests() {
        try {
            while (WORKER.takeRequest(5, TimeUnit.MILLISECONDS) != null) {
                // discard requests from earlier tests
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<RecordedRequest> takeRequests(int count) {
        List<RecordedRequest> requests = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                RecordedRequest request = WORKER.takeRequest(2, TimeUnit.SECONDS);
                if (request == null) {
                    break;
                }
                requests.add(request);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return requests;
    }

    private UUID insertPackage() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "worker-it");
        return packageId;
    }

    private UUID insertSourceFile(UUID packageId, int ordinal, byte[] pdfBytes) {
        UUID fileId = UUID.randomUUID();
        String key = ORG_DEV + "/" + packageId + "/" + fileId + "/original";
        storage.put(key, pdfBytes);
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, ?, ?, 'application/pdf', ?, ?, ?)
                """,
                fileId,
                ORG_DEV,
                packageId,
                ordinal,
                "file-" + ordinal + ".pdf",
                pdfBytes.length,
                Digests.sha256Hex(pdfBytes),
                key);
        return fileId;
    }

    private ProcessingStage stageRow(UUID jobId, ProcessingStatus stage) {
        List<ProcessingStage> rows =
                stageRepository.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                        .filter(row -> row.getStage() == stage)
                        .toList();
        assertThat(rows).as("rows for %s", stage).isNotEmpty();
        return rows.get(rows.size() - 1);
    }

    @Test
    void two_file_package_persists_pages_spans_verdicts_and_raws_with_zero_ocr_for_native() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-file-A".getBytes(StandardCharsets.UTF_8));
        insertSourceFile(packageId, 1, "%PDF-file-B".getBytes(StandardCharsets.UTF_8));

        WORKER.enqueue(WorkerFixtures.renderResponse("PNGDATA-A-0", "PNGDATA-A-1"));
        WORKER.enqueue(WorkerFixtures.renderResponse("PNGDATA-B-0"));
        WORKER.enqueue(WorkerFixtures.json(TEXT_A));
        WORKER.enqueue(WorkerFixtures.json(TEXT_B_SCANNED));
        WORKER.enqueue(WorkerFixtures.json(OCR_B));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_EMPTY_TWO_PAGES));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_EMPTY_ONE_PAGE));

        ProcessingJob job = jobService.createJob(packageId, "worker-happy-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        // ── request log: NATIVE pages produce ZERO ocr calls, the SCANNED page exactly ONE;
        // PARSING calls /v1/layout once per source file ──
        List<RecordedRequest> requests = takeRequests(7);
        List<String> paths = requests.stream().map(RecordedRequest::getPath).toList();
        assertThat(paths)
                .containsExactly(
                        "/v1/render",
                        "/v1/render",
                        "/v1/text",
                        "/v1/text",
                        "/v1/ocr",
                        "/v1/layout",
                        "/v1/layout");
        assertThat(paths.stream().filter("/v1/ocr"::equals)).hasSize(1);
        assertThat(requests.get(0).getHeader("X-Worker-Secret")).isEqualTo("it-worker-secret");
        // The OCR call carries the SCANNED page's PNG and whole-page (empty) regions.
        String ocrBody = requests.get(4).getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(ocrBody).contains("PNGDATA-B-0").contains("\"regions\":[]");

        // ── pages: package_page_index runs across files by ordinal ──
        List<Map<String, Object>> pages =
                jdbc.queryForList(
                        "SELECT * FROM page WHERE package_id = ? ORDER BY package_page_index",
                        packageId);
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0).get("package_page_index")).isEqualTo(0);
        assertThat(pages.get(1).get("package_page_index")).isEqualTo(1);
        assertThat(pages.get(2).get("package_page_index")).isEqualTo(2);
        assertThat(pages.get(0).get("page_index")).isEqualTo(0);
        assertThat(pages.get(1).get("page_index")).isEqualTo(1);
        assertThat(pages.get(2).get("page_index")).isEqualTo(0);
        assertThat((BigDecimal) pages.get(0).get("width_pt")).isEqualByComparingTo("612.0");
        assertThat((BigDecimal) pages.get(0).get("height_pt")).isEqualByComparingTo("792.0");
        assertThat(pages.get(0).get("render_dpi")).isEqualTo(200);

        // PNG blobs in storage. (content_hash is REWRITTEN by PARSING with the span-content
        // hash — the raster sha only survives until then; WorkerParsingStageIT pins the format.)
        byte[] pngA0 = "PNGDATA-A-0".getBytes(StandardCharsets.UTF_8);
        assertThat(storage.get((String) pages.get(0).get("render_storage_key"))).isEqualTo(pngA0);
        assertThat(pages.get(0).get("content_hash"))
                .isNotEqualTo(Digests.sha256Hex(pngA0))
                .isNotNull();

        // Verdicts landed per page.
        assertThat(pages.get(0).get("text_layer")).isEqualTo("NATIVE");
        assertThat(pages.get(1).get("text_layer")).isEqualTo("NATIVE");
        assertThat(pages.get(2).get("text_layer")).isEqualTo("SCANNED");

        // ── NATIVE spans verbatim at 0.1pt ──
        List<Map<String, Object>> nativeSpans =
                jdbc.queryForList(
                        "SELECT * FROM text_span WHERE page_id = ? ORDER BY ordinal",
                        pages.get(0).get("id"));
        assertThat(nativeSpans).hasSize(2);
        assertThat(nativeSpans.get(0).get("text")).isEqualTo("YTD Gross");
        assertThat((BigDecimal) nativeSpans.get(0).get("x")).isEqualByComparingTo("112.3");
        assertThat((BigDecimal) nativeSpans.get(0).get("y")).isEqualByComparingTo("84.0");
        assertThat((BigDecimal) nativeSpans.get(0).get("width")).isEqualByComparingTo("58.2");
        assertThat((BigDecimal) nativeSpans.get(0).get("height")).isEqualByComparingTo("10.5");
        assertThat(nativeSpans.get(0).get("source")).isEqualTo("NATIVE");
        assertThat((BigDecimal) nativeSpans.get(0).get("confidence")).isEqualByComparingTo("1");

        // ── OCR spans carry PER-SPAN engines ──
        List<Map<String, Object>> ocrSpans =
                jdbc.queryForList(
                        "SELECT * FROM text_span WHERE page_id = ? ORDER BY ordinal",
                        pages.get(2).get("id"));
        assertThat(ocrSpans).hasSize(2);
        assertThat(ocrSpans.get(0).get("source")).isEqualTo("OCR");
        assertThat(ocrSpans.get(0).get("ocr_engine")).isEqualTo("RAPIDOCR");
        assertThat((BigDecimal) ocrSpans.get(0).get("confidence")).isEqualByComparingTo("0.97");
        assertThat(ocrSpans.get(1).get("ocr_engine")).isEqualTo("TESSERACT");

        // Page-level OCR columns.
        assertThat(pages.get(2).get("ocr_engine")).isEqualTo("RAPIDOCR");
        assertThat(pages.get(2).get("ocr_fallback_reason")).isEqualTo("G4_NUMERIC");
        assertThat((BigDecimal) pages.get(2).get("ocr_confidence_median"))
                .isEqualByComparingTo("0.91");
        assertThat((BigDecimal) pages.get(2).get("osd_confidence")).isEqualByComparingTo("0.94");
        // Phase 3 review: the "rotated fixture flagged correctly" leg of acceptance
        // criterion 1 had NO test asserting a nonzero detected rotation reaches the
        // page row — a transposed argument in Page.applyOcrOutcome would have passed
        // the whole suite. The mocked worker reports 90; the row must say 90.
        assertThat(pages.get(2).get("detected_rotation")).isEqualTo(90);

        // ── parser_output: raw text payloads per file + BOTH ocr raws ──
        List<Map<String, Object>> textOutputs =
                jdbc.queryForList(
                        """
                        SELECT * FROM parser_output WHERE stage = 'TEXT_EXTRACTION'
                          AND source_file_id IN (SELECT id FROM source_file WHERE package_id = ?)
                        """,
                        packageId);
        assertThat(textOutputs).hasSize(2);
        assertThat(textOutputs)
                .allSatisfy(row -> assertThat(row.get("parser_name")).isEqualTo("pdfplumber"));

        List<Map<String, Object>> ocrOutputs =
                jdbc.queryForList(
                        "SELECT * FROM parser_output WHERE stage = 'OCR_PROCESSING' AND page_id = ?",
                        pages.get(2).get("id"));
        assertThat(ocrOutputs)
                .extracting(row -> row.get("parser_name"))
                .containsExactlyInAnyOrder("rapidocr", "tesseract");
        for (Map<String, Object> output : ocrOutputs) {
            byte[] payload = storage.get((String) output.get("payload_storage_key"));
            assertThat(output.get("payload_sha256")).isEqualTo(Digests.sha256Hex(payload));
        }

        // ── stage rows: digest + worker versions persisted ──
        ProcessingStage rendering = stageRow(job.getId(), ProcessingStatus.RENDERING);
        String expectedDigest =
                Digests.sha256Hex(
                        Digests.sha256Hex(pngA0)
                                + Digests.sha256Hex("PNGDATA-A-1".getBytes(StandardCharsets.UTF_8))
                                + Digests.sha256Hex("PNGDATA-B-0".getBytes(StandardCharsets.UTF_8)));
        assertThat(rendering.getOutputDigest()).isEqualTo(expectedDigest);
        assertThat(rendering.getWorkerVersion()).isEqualTo("0.2.0");
        assertThat(rendering.getParserVersions()).contains("pypdfium2").contains("5.12.1");
        assertThat(stageRow(job.getId(), ProcessingStatus.TEXT_EXTRACTION).getParserVersions())
                .contains("pdfplumber");
        assertThat(stageRow(job.getId(), ProcessingStatus.OCR_PROCESSING).getParserVersions())
                .contains("rapidocr-onnxruntime");

        // ── PARSING is REAL now (Phase 3): a layout digest, never the placeholder ──
        ProcessingStage parsing = stageRow(job.getId(), ProcessingStatus.PARSING);
        assertThat(parsing.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(parsing.getOutputDigest())
                .isNotEqualTo(Digests.sha256Hex("{\"phase2\":\"not-yet-implemented\"}"));
        assertThat(parsing.getWorkerVersion()).isEqualTo("0.3.0");

        // ── CLASSIFYING and SPLITTING are REAL now (Phase 4): real digests, never the
        // placeholder. Their behavior is specified by the classification ITs; here the pipeline
        // only proves they run and succeed in sequence. ──
        for (ProcessingStatus phase4 :
                List.of(ProcessingStatus.CLASSIFYING, ProcessingStatus.SPLITTING)) {
            ProcessingStage row = stageRow(job.getId(), phase4);
            assertThat(row.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
            assertThat(row.getOutputDigest())
                    .isNotEqualTo(Digests.sha256Hex("{\"phase2\":\"not-yet-implemented\"}"));
        }

        // ── EXTRACTING is REAL now (Phase 5): a real digest, never the placeholder. Its
        // behavior is specified by the extraction ITs; here the pipeline only proves it runs
        // and succeeds in sequence. ──
        ProcessingStage extracting = stageRow(job.getId(), ProcessingStatus.EXTRACTING);
        assertThat(extracting.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(extracting.getOutputDigest())
                .isNotEqualTo(Digests.sha256Hex("{\"phase2\":\"not-yet-implemented\"}"));
    }

    @Test
    void mixed_page_sends_uncovered_regions_and_merges_native_and_ocr_spans() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-mixed".getBytes(StandardCharsets.UTF_8));

        WORKER.enqueue(WorkerFixtures.renderResponse("PNGDATA-M-0"));
        WORKER.enqueue(
                WorkerFixtures.json(
                        """
                        {
                          "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
                          "pages": [
                            {
                              "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                              "verdict": "MIXED",
                              "spans": [
                                { "ordinal": 0, "text": "Employer",
                                  "x": 72.0, "y": 61.9, "width": 52.0, "height": 10.2,
                                  "fontSize": 10.2, "fontName": "Helvetica" }
                              ],
                              "uncoveredRegions": [
                                {"x": 36.0, "y": 400.0, "width": 540.0, "height": 300.5}
                              ]
                            }
                          ]
                        }
                        """));
        WORKER.enqueue(
                WorkerFixtures.json(
                        """
                        {
                          "worker": { "version": "0.2.0", "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
                          "pageIndex": 0,
                          "detectedRotation": 0,
                          "osdConfidence": 0.99,
                          "engine": "RAPIDOCR",
                          "fallbackReason": null,
                          "confidenceMedian": 0.95,
                          "spans": [
                            { "ordinal": 0, "text": "$1,200.00",
                              "x": 40.1, "y": 410.7, "width": 55.0, "height": 11.2,
                              "engine": "RAPIDOCR", "confidence": 0.95 }
                          ],
                          "gates": {},
                          "raw": { "rapidocr": { "spans": [] }, "tesseract": null }
                        }
                        """));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_EMPTY_ONE_PAGE));

        jobService.createJob(packageId, "worker-mixed-" + UUID.randomUUID());

        List<RecordedRequest> requests = takeRequests(4);
        assertThat(requests.stream().map(RecordedRequest::getPath))
                .containsExactly("/v1/render", "/v1/text", "/v1/ocr", "/v1/layout");
        // A MIXED page is ruling-eligible: the ORIGINAL PDF rides along on /v1/layout, and the
        // request carries BOTH sources' spans under unique request ordinals.
        String layoutBody = requests.get(3).getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(layoutBody).contains("name=\"file\"").contains("%PDF-mixed");
        assertThat(layoutBody).contains("\"text\":\"Employer\"").contains("\"text\":\"$1,200.00\"");
        assertThat(layoutBody).contains("\"ordinal\":0").contains("\"ordinal\":1");
        String ocrBody = requests.get(2).getBody().readString(StandardCharsets.ISO_8859_1);
        // The MIXED page's uncovered regions travel to /v1/ocr as OCR regions, verbatim.
        assertThat(ocrBody)
                .contains("\"regions\":[{")
                .contains("\"x\":36.0")
                .contains("\"height\":300.5");

        Map<String, Object> page =
                jdbc.queryForMap("SELECT * FROM page WHERE package_id = ?", packageId);
        assertThat(page.get("text_layer")).isEqualTo("MIXED");
        // jsonb comes back as PGobject; its toString is the document.
        assertThat(String.valueOf(page.get("uncovered_regions"))).contains("36.0").contains("300.5");

        List<Map<String, Object>> spans =
                jdbc.queryForList(
                        "SELECT * FROM text_span WHERE page_id = ? ORDER BY source, ordinal",
                        page.get("id"));
        assertThat(spans).hasSize(2);
        assertThat(spans)
                .extracting(row -> row.get("source"))
                .containsExactlyInAnyOrder("NATIVE", "OCR");
    }

    @Test
    void all_gates_failed_engine_none_flags_page_low_confidence_and_job_continues() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-hopeless".getBytes(StandardCharsets.UTF_8));

        WORKER.enqueue(WorkerFixtures.renderResponse("PNGDATA-N-0"));
        WORKER.enqueue(
                WorkerFixtures.json(
                        """
                        {
                          "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
                          "pages": [
                            { "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                              "verdict": "SCANNED", "spans": [] }
                          ]
                        }
                        """));
        WORKER.enqueue(
                WorkerFixtures.json(
                        """
                        {
                          "worker": { "version": "0.2.0", "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
                          "pageIndex": 0,
                          "detectedRotation": 0,
                          "osdConfidence": 0.31,
                          "engine": "NONE",
                          "fallbackReason": "G3_CONFIDENCE",
                          "confidenceMedian": 0.22,
                          "spans": [],
                          "gates": {
                            "G3_CONFIDENCE": {"tripped": true, "value": 0.22, "threshold": 0.70}
                          },
                          "raw": {
                            "rapidocr": { "spans": [] },
                            "tesseract": { "spans": [] }
                          }
                        }
                        """));
        // Pure-scanned file, zero spans persisted: PARSING still runs — layout gets NO file part
        // and empty span lists, and the page's content_hash stays null (nothing to hash).
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_EMPTY_ONE_PAGE));

        ProcessingJob job = jobService.createJob(packageId, "worker-none-" + UUID.randomUUID());

        // The JOB continues — HUMAN_REVIEW_REQUIRED is the Phase 1/2 end state anyway; the
        // page-level flag is what review reads.
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        assertThat(stageRow(job.getId(), ProcessingStatus.OCR_PROCESSING).getStatus())
                .isEqualTo(StageStatus.SUCCEEDED);

        Map<String, Object> page =
                jdbc.queryForMap("SELECT * FROM page WHERE package_id = ?", packageId);
        assertThat(page.get("ocr_engine")).isEqualTo("NONE");
        assertThat(page.get("ocr_fallback_reason")).isEqualTo("OCR_LOW_CONFIDENCE");

        // Both engines ran and failed — BOTH raws still persist.
        List<Map<String, Object>> raws =
                jdbc.queryForList(
                        "SELECT * FROM parser_output WHERE stage = 'OCR_PROCESSING' AND page_id = ?",
                        page.get("id"));
        assertThat(raws)
                .extracting(row -> row.get("parser_name"))
                .containsExactlyInAnyOrder("rapidocr", "tesseract");
    }

    @Test
    void engine_none_on_a_mixed_page_records_the_gate_but_never_flags_low_confidence() {
        // A browser-printed statement: real text layer, plus a chart image nobody printed words
        // over. The chart region goes to OCR, both engines find no words in a graphic, and the
        // worker answers engine NONE. That is NOT an unread page — the native layer is what the
        // reader trusts — so the tripped gate is recorded as-is and the review flag stays off.
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-browser-print".getBytes(StandardCharsets.UTF_8));

        WORKER.enqueue(WorkerFixtures.renderResponse("PNGDATA-MX-0"));
        WORKER.enqueue(
                WorkerFixtures.json(
                        """
                        {
                          "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
                          "pages": [
                            {
                              "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                              "verdict": "MIXED",
                              "spans": [
                                { "ordinal": 0, "text": "Balance",
                                  "x": 72.0, "y": 61.9, "width": 52.0, "height": 10.2,
                                  "fontSize": 10.2, "fontName": "Helvetica" }
                              ],
                              "uncoveredRegions": [
                                {"x": 36.0, "y": 120.0, "width": 540.0, "height": 160.0}
                              ]
                            }
                          ]
                        }
                        """));
        WORKER.enqueue(
                WorkerFixtures.json(
                        """
                        {
                          "worker": { "version": "0.2.0", "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
                          "pageIndex": 0,
                          "detectedRotation": 0,
                          "osdConfidence": 0.40,
                          "engine": "NONE",
                          "fallbackReason": "G1_COVERAGE",
                          "confidenceMedian": 0.0,
                          "spans": [],
                          "gates": {
                            "G1_COVERAGE": {"tripped": true, "value": 0.0, "threshold": 0.60}
                          },
                          "raw": {
                            "rapidocr": { "spans": [] },
                            "tesseract": { "spans": [] }
                          }
                        }
                        """));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_EMPTY_ONE_PAGE));

        ProcessingJob job = jobService.createJob(packageId, "worker-mixed-none-" + UUID.randomUUID());

        assertThat(stageRow(job.getId(), ProcessingStatus.OCR_PROCESSING).getStatus())
                .isEqualTo(StageStatus.SUCCEEDED);

        Map<String, Object> page =
                jdbc.queryForMap("SELECT * FROM page WHERE package_id = ?", packageId);
        assertThat(page.get("text_layer")).isEqualTo("MIXED");
        assertThat(page.get("ocr_engine")).isEqualTo("NONE");
        // The gate that tripped, not the unread-page flag.
        assertThat(page.get("ocr_fallback_reason")).isEqualTo("G1_COVERAGE");

        // The native span is still there for parsing; nothing from OCR, as expected.
        List<Map<String, Object>> spans =
                jdbc.queryForList("SELECT * FROM text_span WHERE page_id = ?", page.get("id"));
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).get("source")).isEqualTo("NATIVE");
    }

    @Test
    void worker_500_fails_the_stage_after_retries_and_resume_recovers() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-flaky".getBytes(StandardCharsets.UTF_8));

        // Three attempts, three 500s with a non-contract body.
        for (int i = 0; i < 3; i++) {
            WORKER.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        }

        ProcessingJob job = jobService.createJob(packageId, "worker-500-" + UUID.randomUUID());

        JobService.JobDetails failed = jobService.getJob(job.getId());
        assertThat(failed.job().getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(failed.job().getCurrentStage()).isEqualTo(ProcessingStatus.RENDERING);
        List<ProcessingStage> renderRows =
                failed.stages().stream()
                        .filter(row -> row.getStage() == ProcessingStatus.RENDERING)
                        .toList();
        assertThat(renderRows).hasSize(3);
        assertThat(renderRows)
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED);
                            assertThat(row.getErrorCode()).isEqualTo(ErrorCode.WORKER_UNAVAILABLE);
                            // Never body text in the persisted detail.
                            assertThat(row.getErrorDetail()).doesNotContain("boom");
                        });

        // Resume with a healthy worker: the existing machinery re-runs from RENDERING.
        WORKER.enqueue(WorkerFixtures.renderResponse("PNGDATA-R-0"));
        WORKER.enqueue(WorkerFixtures.json(TEXT_B_SCANNED.replace("SCANNED", "NATIVE")));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_EMPTY_ONE_PAGE));
        jobService.resume(job.getId());

        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        List<Map<String, Object>> pages =
                jdbc.queryForList("SELECT * FROM page WHERE package_id = ?", packageId);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).get("text_layer")).isEqualTo("NATIVE");
    }
}
