package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * <b>Upload to extracted fields, in one continuous pass, from a JPEG.</b>
 *
 * <p>This is the acceptance surface for the image defect: four borrower paystubs arrived as JPEG
 * photos, ingestion accepted them, and RENDERING failed CORRUPT_PDF three times with zero pages
 * persisted. Nothing downstream ran, and no test anywhere covered upload → fields in one pass, so
 * nothing noticed.
 *
 * <p>Everything before the worker is REAL: the HTTP upload endpoint, MimeSniffer, ImageProbe, the
 * job seam, StageRunner, and — after the worker calls — PageService, TextSpanService, the
 * classifier, the splitter and FieldExtractionService, all against the built-in PAYSTUB rule pack
 * and extraction schema from the migrations. The one seam is the worker itself, which is Python and
 * cannot run inside a Gradle test.
 *
 * <p><b>The worker responses below are not invented.</b> They are the bytes the real worker
 * produced for the real JPEG this test uploads — page geometry from {@code /v1/render}, the
 * verdict from {@code /v1/text}, and OCR spans from {@code /v1/ocr}, all reproduced by
 * {@code worker/tests/image/test_image_pipeline.py} against the same fixture drawn by
 * {@code worker/tests/image/image_fixtures.py}. The Python suite proves the worker really says
 * this for an image; this test proves the rest of the engine turns it into fields. The two halves
 * meet at the page geometry: 1224x1584 px of JPEG becomes a 612.0 x 792.0 pt page at 144 DPI
 * (source.py's nominal-DPI rule), and every span box below is a pixel box halved.
 */
@AutoConfigureMockMvc
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true",
            "docengine.processing.adapter=worker",
            "docengine.worker.shared-secret=image-it-secret",
            "docengine.storage.local-root=build/test-blobs"
        })
class ImageSourcePipelineIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    // ── the real worker's answers for this JPEG ─────────────────────────────

    /** 1224x1584 px at the derived 144 DPI = 612.0 x 792.0 pt, rotation 0, no resampling. */
    private static final String IMAGE_RENDER_METADATA =
            """
            {
              "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
              "dpi": 144, "widthPx": 1224, "heightPx": 1584, "pngPart": "page-0"
            }
            """;

    /** No glyph operators anywhere in a photograph: SCANNED, no spans, real ink. */
    private static final String IMAGE_TEXT =
            """
            {
              "worker": { "version": "0.4.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "SCANNED", "inkFraction": 0.0209, "spans": [] }
              ]
            }
            """;

    private static final String IMAGE_OCR =
            """
            {
              "worker": { "version": "0.4.0",
                          "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
              "pageIndex": 0,
              "detectedRotation": 0,
              "osdConfidence": 0.95,
              "engine": "RAPIDOCR",
              "fallbackReason": "G2_WORD_YIELD",
              "confidenceMedian": 0.9906,
              "spans": [
                {"ordinal": 0,  "text": "PAYROLL",     "x": 31.0,  "y": 33.5,  "width": 66.0,  "height": 15.0, "engine": "RAPIDOCR", "confidence": 0.9963},
                {"ordinal": 1,  "text": "STATEMENT",   "x": 104.0, "y": 33.5,  "width": 85.5,  "height": 15.0, "engine": "RAPIDOCR", "confidence": 0.9963},
                {"ordinal": 2,  "text": "Employer:",   "x": 28.5,  "y": 65.0,  "width": 81.0,  "height": 23.5, "engine": "RAPIDOCR", "confidence": 0.9996},
                {"ordinal": 3,  "text": "ACMEWIDGETSLLC", "x": 216.0, "y": 68.5, "width": 158.5, "height": 14.5, "engine": "RAPIDOCR", "confidence": 0.9946},
                {"ordinal": 4,  "text": "Employee:",   "x": 28.5,  "y": 95.0,  "width": 84.0,  "height": 23.5, "engine": "RAPIDOCR", "confidence": 0.9987},
                {"ordinal": 5,  "text": "Jordan",      "x": 214.5, "y": 97.0,  "width": 49.6,  "height": 18.5, "engine": "RAPIDOCR", "confidence": 0.9853},
                {"ordinal": 6,  "text": "Rivera",      "x": 272.4, "y": 97.0,  "width": 49.6,  "height": 18.5, "engine": "RAPIDOCR", "confidence": 0.9853},
                {"ordinal": 7,  "text": "Pay",         "x": 29.0,  "y": 126.5, "width": 24.8,  "height": 20.5, "engine": "RAPIDOCR", "confidence": 0.9823},
                {"ordinal": 8,  "text": "Period",      "x": 62.0,  "y": 126.5, "width": 49.5,  "height": 20.5, "engine": "RAPIDOCR", "confidence": 0.9823},
                {"ordinal": 9,  "text": "01/01/2026",  "x": 214.5, "y": 127.5, "width": 90.5,  "height": 17.0, "engine": "RAPIDOCR", "confidence": 0.9997},
                {"ordinal": 10, "text": "01/15/2026",  "x": 349.0, "y": 126.5, "width": 91.0,  "height": 18.0, "engine": "RAPIDOCR", "confidence": 0.9998},
                {"ordinal": 11, "text": "Pay",         "x": 27.5,  "y": 155.5, "width": 27.0,  "height": 23.5, "engine": "RAPIDOCR", "confidence": 0.9646},
                {"ordinal": 12, "text": "Date",        "x": 63.5,  "y": 155.5, "width": 36.0,  "height": 23.5, "engine": "RAPIDOCR", "confidence": 0.9646},
                {"ordinal": 13, "text": "01/20/2026",  "x": 215.0, "y": 157.5, "width": 90.0,  "height": 16.5, "engine": "RAPIDOCR", "confidence": 0.9997},
                {"ordinal": 14, "text": "Pay",         "x": 29.0,  "y": 187.0, "width": 26.7,  "height": 21.5, "engine": "RAPIDOCR", "confidence": 0.9959},
                {"ordinal": 15, "text": "Frequency",   "x": 64.6,  "y": 187.0, "width": 80.0,  "height": 21.5, "engine": "RAPIDOCR", "confidence": 0.9959},
                {"ordinal": 16, "text": "Bi-Weekly",   "x": 213.5, "y": 185.0, "width": 78.5,  "height": 23.0, "engine": "RAPIDOCR", "confidence": 0.9981},
                {"ordinal": 17, "text": "Earnings",    "x": 28.5,  "y": 226.0, "width": 71.0,  "height": 22.5, "engine": "RAPIDOCR", "confidence": 0.9998},
                {"ordinal": 18, "text": "Gross",       "x": 28.5,  "y": 255.0, "width": 44.2,  "height": 23.5, "engine": "RAPIDOCR", "confidence": 0.969},
                {"ordinal": 19, "text": "Pay",         "x": 81.5,  "y": 255.0, "width": 26.5,  "height": 23.5, "engine": "RAPIDOCR", "confidence": 0.969},
                {"ordinal": 20, "text": "$2,450.00",   "x": 214.5, "y": 256.5, "width": 78.5,  "height": 20.0, "engine": "RAPIDOCR", "confidence": 0.9998},
                {"ordinal": 21, "text": "Federal",     "x": 28.5,  "y": 286.0, "width": 58.1,  "height": 20.0, "engine": "RAPIDOCR", "confidence": 0.9767},
                {"ordinal": 22, "text": "Withholding", "x": 94.8,  "y": 286.0, "width": 91.2,  "height": 20.0, "engine": "RAPIDOCR", "confidence": 0.9767},
                {"ordinal": 23, "text": "$312.45",     "x": 213.5, "y": 286.0, "width": 67.0,  "height": 20.5, "engine": "RAPIDOCR", "confidence": 1.0},
                {"ordinal": 24, "text": "Net",         "x": 27.5,  "y": 314.5, "width": 27.7,  "height": 25.0, "engine": "RAPIDOCR", "confidence": 0.9993},
                {"ordinal": 25, "text": "Pay",         "x": 64.4,  "y": 314.5, "width": 27.7,  "height": 25.0, "engine": "RAPIDOCR", "confidence": 0.9993},
                {"ordinal": 26, "text": "$1,884.10",   "x": 214.5, "y": 316.5, "width": 78.0,  "height": 18.5, "engine": "RAPIDOCR", "confidence": 0.9996},
                {"ordinal": 27, "text": "YTD",         "x": 28.5,  "y": 346.5, "width": 28.0,  "height": 19.5, "engine": "RAPIDOCR", "confidence": 0.9906},
                {"ordinal": 28, "text": "Gross",       "x": 65.9,  "y": 346.5, "width": 46.7,  "height": 19.5, "engine": "RAPIDOCR", "confidence": 0.9906},
                {"ordinal": 29, "text": "$9,800.00",   "x": 215.0, "y": 346.5, "width": 77.5,  "height": 19.0, "engine": "RAPIDOCR", "confidence": 0.9995}
              ],
              "gates": { "G2_WORD_YIELD": {"tripped": true, "value": 0.4, "threshold": 0.6} },
              "raw": { "rapidocr": { "spans": [] }, "tesseract": { "spans": [] } }
            }
            """;

    /** An image has no vector rulings; the pixel detectors still looked, so nothing is retired. */
    private static final String IMAGE_LAYOUT =
            """
            {
              "worker": { "version": "0.4.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [ { "pageIndex": 0, "elements": [], "notImplemented": [] } ]
            }
            """;

    @Autowired MockMvc mockMvc;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        WORKER.setDispatcher(new QueueDispatcher());
        drain();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void drain() {
        try {
            while (WORKER.takeRequest(5, TimeUnit.MILLISECONDS) != null) {
                // discard requests from earlier tests
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> takePaths(int count) {
        List<String> paths = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                RecordedRequest request = WORKER.takeRequest(2, TimeUnit.SECONDS);
                if (request == null) {
                    break;
                }
                paths.add(request.getPath());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return paths;
    }

    /**
     * The paystub photo, drawn here — the Java twin of
     * {@code worker/tests/image/image_fixtures.py}, same 1224x1584 canvas and same column layout,
     * so the geometry the worker fixtures above describe is the geometry of THIS file.
     */
    private static byte[] paystubJpeg() throws IOException {
        BufferedImage image = new BufferedImage(1224, 1584, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1224, 1584);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 34));
        record Line(int x, int y, String text) {}
        for (Line line :
                List.of(
                        new Line(60, 94, "PAYROLL STATEMENT"),
                        new Line(60, 164, "Employer:"),
                        new Line(430, 164, "ACME WIDGETS LLC"),
                        new Line(60, 224, "Employee:"),
                        new Line(430, 224, "Jordan Rivera"),
                        new Line(60, 284, "Pay Period"),
                        new Line(430, 284, "01/01/2026"),
                        new Line(700, 284, "01/15/2026"),
                        new Line(60, 344, "Pay Date"),
                        new Line(430, 344, "01/20/2026"),
                        new Line(60, 404, "Pay Frequency"),
                        new Line(430, 404, "Bi-Weekly"),
                        new Line(60, 484, "Earnings"),
                        new Line(60, 544, "Gross Pay"),
                        new Line(430, 544, "$2,450.00"),
                        new Line(60, 604, "Federal Withholding"),
                        new Line(430, 604, "$312.45"),
                        new Line(60, 664, "Net Pay"),
                        new Line(430, 664, "$1,884.10"),
                        new Line(60, 724, "YTD Gross"),
                        new Line(430, 724, "$9,800.00"))) {
            graphics.drawString(line.text(), line.x(), line.y());
        }
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private void enqueueWorkerResponses() {
        WORKER.enqueue(
                WorkerFixtures.renderResponseWithMetadata(IMAGE_RENDER_METADATA, "PNG-IMAGE-PAGE-0"));
        WORKER.enqueue(WorkerFixtures.json(IMAGE_TEXT));
        WORKER.enqueue(WorkerFixtures.json(IMAGE_OCR));
        WORKER.enqueue(WorkerFixtures.json(IMAGE_LAYOUT));
    }

    private UUID uploadPaystubPhoto() throws Exception {
        enqueueWorkerResponses();
        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(
                                                new MockMultipartFile(
                                                        "files",
                                                        "IMG_4417.jpg",
                                                        "image/jpeg",
                                                        paystubJpeg()))
                                        .param("name", "borrower photos")
                                        .header("Idempotency-Key", "image-e2e-" + UUID.randomUUID()))
                        .andExpect(status().isAccepted())
                        // Fixed at ingest: an image is a page, and page_count says so.
                        .andExpect(jsonPath("$.files[0].contentType").value("image/jpeg"))
                        .andExpect(jsonPath("$.files[0].pageCount").value(1))
                        .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("packageId").asText());
    }

    @Test
    void a_photographed_paystub_travels_from_upload_all_the_way_to_extracted_fields()
            throws Exception {
        UUID packageId = uploadPaystubPhoto();

        // ── the job ran the whole pipeline, and every stage the image touches succeeded ──
        Map<String, Object> job =
                jdbc.queryForMap("SELECT * FROM processing_job WHERE package_id = ?", packageId);
        assertThat(job.get("status"))
                .as("a JPEG upload must reach review, not FAILED")
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED.name());

        List<Map<String, Object>> stages =
                jdbc.queryForList(
                        "SELECT stage, status, error_code FROM processing_stage WHERE job_id = ?"
                                + " ORDER BY created_at",
                        job.get("id"));
        assertThat(stages)
                .as("no stage may fail, and none may fail as CORRUPT_PDF ever again")
                .noneSatisfy(row -> assertThat(row.get("status")).isEqualTo("FAILED"));
        assertThat(stages).extracting(row -> row.get("error_code")).containsOnlyNulls();

        // The worker was called exactly once per stage that needs it — an image is one page.
        assertThat(takePaths(4))
                .containsExactly("/v1/render", "/v1/text", "/v1/ocr", "/v1/layout");

        // ── the page: persisted, geometry from the pixels, raster from the upload ──
        Map<String, Object> page =
                jdbc.queryForMap("SELECT * FROM page WHERE package_id = ?", packageId);
        assertThat((BigDecimal) page.get("width_pt")).isEqualByComparingTo("612.0");
        assertThat((BigDecimal) page.get("height_pt")).isEqualByComparingTo("792.0");
        assertThat(page.get("rotation")).isEqualTo(0);
        // 144, not the 200 the request asked for: an image has the pixels it has.
        assertThat(page.get("render_dpi")).isEqualTo(144);
        assertThat(page.get("render_storage_key")).isNotNull();

        // ── text layer: an image has none, which is exactly what sends it to OCR ──
        assertThat(page.get("text_layer")).isEqualTo("SCANNED");
        assertThat(page.get("is_blank")).isEqualTo(false);
        assertThat((BigDecimal) page.get("blank_score")).isEqualByComparingTo("0.0209");

        // ── spans: every one from OCR, none native, all inside the page box ──
        List<Map<String, Object>> spans =
                jdbc.queryForList(
                        "SELECT * FROM text_span WHERE page_id = ? ORDER BY ordinal",
                        page.get("id"));
        assertThat(spans).hasSize(30);
        assertThat(spans).extracting(row -> row.get("source")).containsOnly("OCR");
        assertThat(spans)
                .allSatisfy(
                        row -> {
                            BigDecimal x = (BigDecimal) row.get("x");
                            BigDecimal y = (BigDecimal) row.get("y");
                            assertThat(x.add((BigDecimal) row.get("width")))
                                    .isLessThanOrEqualTo(new BigDecimal("612.00"));
                            assertThat(y.add((BigDecimal) row.get("height")))
                                    .isLessThanOrEqualTo(new BigDecimal("792.00"));
                        });

        // ── classification and splitting ──
        Map<String, Object> classification =
                jdbc.queryForMap(
                        "SELECT * FROM classification_result WHERE subject_type = 'PAGE'"
                                + " AND subject_id = ? AND is_current",
                        page.get("id"));
        assertThat(classification.get("document_type_code")).isEqualTo("PAYSTUB");

        UUID documentId =
                jdbc.queryForObject(
                        "SELECT id FROM logical_document WHERE package_id = ?", UUID.class, packageId);

        // ── EXTRACTED FIELDS: the proof that matters ──
        Map<String, Map<String, Object>> fields =
                jdbc.queryForList(
                                "SELECT * FROM extracted_field WHERE logical_document_id = ?"
                                        + " AND is_current",
                                documentId)
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        row -> (String) row.get("field_name"), row -> row));

        // The WHOLE PAYSTUB schema — all eighteen fields of paystub@1.5.0 — off a phone photo.
        // The last eight are AI-only (V53) and land as MISSING rows on a rules-only run.
        assertThat(fields.keySet())
                .as("a JPEG photo produced real captures, not an empty document")
                .containsExactlyInAnyOrder(
                        "borrowerName",
                        "employerName",
                        "payPeriodStart",
                        "payPeriodEnd",
                        "payDate",
                        "payFrequency",
                        "currentGrossPay",
                        "netPay",
                        "federalWithholding",
                        "ytdGrossPay",
                        "currentTotalDeductions",
                        "ytdTotalDeductions",
                        "ytdNetPay",
                        "earningDescription",
                        "earningHours",
                        "earningRate",
                        "earningCurrentAmount",
                        "earningYtdAmount");
        // Nine of the ten rule-read fields actually CAPTURED — a row exists for every schema
        // field whether or not it was found, so "eighteen rows" alone would prove nothing.
        assertThat(fields.values())
                .filteredOn(row -> !"NONE".equals(row.get("extraction_method")))
                .as("captured fields")
                .hasSize(9);
        // The tenth is a layout-shape miss, not an image one: ytdGrossPay's extractors want a
        // `Gross | Current | YTD` grid (TABLE_CLUSTER, or a second money value on the Gross Pay
        // line), and this fixture is a plain label/value stub. A PDF of the same layout misses it
        // identically.
        assertThat(fields.get("ytdGrossPay").get("extraction_method")).isEqualTo("NONE");

        assertThat(fields.get("borrowerName").get("normalized_text")).isEqualTo("Jordan Rivera");
        assertThat(String.valueOf(fields.get("payPeriodStart").get("normalized_date")))
                .isEqualTo("2026-01-01");
        assertThat(String.valueOf(fields.get("payPeriodEnd").get("normalized_date")))
                .isEqualTo("2026-01-15");
        assertThat(String.valueOf(fields.get("payDate").get("normalized_date")))
                .isEqualTo("2026-01-20");
        assertThat((BigDecimal) fields.get("currentGrossPay").get("normalized_number"))
                .isEqualByComparingTo("2450.00");
        assertThat((BigDecimal) fields.get("netPay").get("normalized_number"))
                .isEqualByComparingTo("1884.10");
        assertThat((BigDecimal) fields.get("federalWithholding").get("normalized_number"))
                .isEqualByComparingTo("312.45");
        assertThat(fields.get("netPay").get("displayed_text")).isEqualTo("$1,884.10");
        // employerName has no normalizer, so the capture rides on displayed_text. RapidOCR read
        // the all-caps header without word gaps — a real property of this recogniser on tight
        // capitals, recorded here rather than papered over.
        assertThat(String.valueOf(fields.get("employerName").get("displayed_text")))
                .contains("ACME");
        assertThat(fields.get("currentGrossPay").get("displayed_text")).isEqualTo("$2,450.00");

        // Confidence stays exactly three components, on OCR'd image spans as on native ones.
        assertThat(String.valueOf(fields.get("currentGrossPay").get("confidence_components")))
                .contains("spanConfidence")
                .contains("anchorStrength")
                .contains("normalizerCertainty");
    }

    @Test
    void every_evidence_box_points_at_the_image_page_in_its_own_canonical_frame() throws Exception {
        UUID packageId = uploadPaystubPhoto();
        UUID pageId = jdbc.queryForObject(
                "SELECT id FROM page WHERE package_id = ?", UUID.class, packageId);

        List<Map<String, Object>> evidence =
                jdbc.queryForList(
                        """
                        SELECT e.* FROM field_evidence e
                          JOIN extracted_field f ON f.id = e.extracted_field_id
                         WHERE f.logical_document_id IN
                               (SELECT id FROM logical_document WHERE package_id = ?)
                        """,
                        packageId);

        assertThat(evidence).as("extraction produced no evidence at all").isNotEmpty();
        for (Map<String, Object> box : evidence) {
            // One page in the package, and every box must be on it: an evidence row pointing at
            // another page is how an overlay silently highlights the wrong document.
            assertThat(box.get("page_id")).isEqualTo(pageId);
            // Canonical rotation-0 points, inside the 612x792 box the pixels derived. A box still
            // in the image's PIXEL frame would read 1224-wide here and fail loudly.
            BigDecimal x = (BigDecimal) box.get("x");
            BigDecimal y = (BigDecimal) box.get("y");
            assertThat(x).isBetween(BigDecimal.ZERO, new BigDecimal("612.00"));
            assertThat(y).isBetween(BigDecimal.ZERO, new BigDecimal("792.00"));
            assertThat(x.add((BigDecimal) box.get("width")))
                    .isLessThanOrEqualTo(new BigDecimal("612.00"));
            assertThat(y.add((BigDecimal) box.get("height")))
                    .isLessThanOrEqualTo(new BigDecimal("792.00"));
        }
    }

    @Test
    void the_pages_endpoint_tells_a_viewer_the_source_is_an_image() throws Exception {
        UUID packageId = uploadPaystubPhoto();

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/packages/{id}/pages", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].sourceContentType").value("image/jpeg"))
                .andExpect(jsonPath("$.pages[0].widthPt").value(612.00))
                .andExpect(jsonPath("$.pages[0].heightPt").value(792.00))
                .andExpect(jsonPath("$.pages[0].rotation").value(0))
                .andExpect(jsonPath("$.pages[0].hasRender").value(true))
                .andExpect(jsonPath("$.pages[0].textLayer").value("SCANNED"));
    }
}
