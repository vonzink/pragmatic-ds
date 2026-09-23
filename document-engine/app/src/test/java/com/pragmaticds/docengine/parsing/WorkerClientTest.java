package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pragmaticds.docengine.parsing.client.LayoutRequest;
import com.pragmaticds.docengine.parsing.client.LayoutRequestPage;
import com.pragmaticds.docengine.parsing.client.LayoutRequestSpan;
import com.pragmaticds.docengine.parsing.client.LayoutResult;
import com.pragmaticds.docengine.parsing.client.OcrRequest;
import com.pragmaticds.docengine.parsing.client.OcrResult;
import com.pragmaticds.docengine.parsing.client.RenderResult;
import com.pragmaticds.docengine.parsing.client.TextResult;
import com.pragmaticds.docengine.parsing.client.WireBox;
import com.pragmaticds.docengine.parsing.client.WorkerCallException;
import com.pragmaticds.docengine.parsing.client.WorkerClient;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Java half of the wire contract (docs/WORKER_CONTRACT.md), pinned against MockWebServer with
 * contract-VERBATIM fixtures: the JSON bodies below are the contract document's own examples.
 * Golden files pin the Python side; these fixtures pin this side. Neither side may change the
 * contract unilaterally.
 */
class WorkerClientTest {

    private static final String SECRET = "test-worker-secret";
    private static final byte[] PDF = "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 7, 7};

    /** docs/WORKER_CONTRACT.md POST /v1/text response example, verbatim. */
    private static final String TEXT_RESPONSE =
            """
            {
              "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0,
                  "widthPt": 612.0,
                  "heightPt": 792.0,
                  "rotation": 0,
                  "verdict": "NATIVE",
                  "spans": [
                    {
                      "ordinal": 0,
                      "text": "YTD Gross",
                      "x": 112.3, "y": 84.0, "width": 58.2, "height": 10.5,
                      "fontSize": 10.5,
                      "fontName": "Helvetica-Bold"
                    }
                  ]
                }
              ]
            }
            """;

    /** docs/WORKER_CONTRACT.md POST /v1/ocr response example, verbatim (tesseract raw added). */
    private static final String OCR_RESPONSE =
            """
            {
              "worker": { "version": "0.2.0", "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
              "pageIndex": 3,
              "detectedRotation": 90,
              "osdConfidence": 0.94,
              "engine": "RAPIDOCR",
              "fallbackReason": null,
              "confidenceMedian": 0.91,
              "spans": [
                {
                  "ordinal": 0,
                  "text": "$48,231.30",
                  "x": 112.3, "y": 84.0, "width": 61.0, "height": 11.2,
                  "engine": "RAPIDOCR",
                  "confidence": 0.97
                }
              ],
              "gates": {
                "G1_COVERAGE": {"tripped": false, "value": 0.93, "threshold": 0.60},
                "G2_WORD_YIELD": {"tripped": false, "value": 1.02, "threshold": 0.50},
                "G3_CONFIDENCE": {"tripped": false, "value": 0.91, "threshold": 0.70},
                "G4_NUMERIC": {"tripped": false, "value": 0.97, "threshold": 0.75},
                "G6_GEOMETRY": {"tripped": false, "value": 0.01, "threshold": 0.15}
              },
              "raw": {
                "rapidocr": { "spans": ["..."] },
                "tesseract": null
              }
            }
            """;

    /**
     * docs/WORKER_CONTRACT.md POST /v1/layout response shape, with the full TABLE > TABLE_ROW >
     * TABLE_CELL nesting the contract's elementId/parentElementId strings express, plus the
     * notImplemented list that distinguishes "no detector" from "looked and found none".
     */
    private static final String LAYOUT_RESPONSE =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0,
                  "elements": [
                    {
                      "elementId": "e0",
                      "parentElementId": null,
                      "elementType": "TABLE",
                      "ordinal": 4,
                      "x": 66.0, "y": 168.0, "width": 494.0, "height": 110.0,
                      "confidence": 0.95,
                      "attributes": { "rows": 5, "cols": 5, "ruled": true },
                      "spanOrdinals": [],
                      "detector": "clustering",
                      "detectorVersion": "0.3.0"
                    },
                    {
                      "elementId": "e1",
                      "parentElementId": "e0",
                      "elementType": "TABLE_ROW",
                      "ordinal": 5,
                      "x": 66.0, "y": 178.5, "width": 494.0, "height": 12.8,
                      "confidence": 0.95,
                      "attributes": { "row": 0 },
                      "spanOrdinals": [],
                      "detector": "clustering",
                      "detectorVersion": "0.3.0"
                    },
                    {
                      "elementId": "e2",
                      "parentElementId": "e1",
                      "elementType": "TABLE_CELL",
                      "ordinal": 6,
                      "x": 72.0, "y": 178.5, "width": 47.9, "height": 12.8,
                      "confidence": 0.95,
                      "attributes": { "row": 0, "col": 0 },
                      "spanOrdinals": [12],
                      "detector": "clustering",
                      "detectorVersion": "0.3.0"
                    }
                  ],
                  "notImplemented": ["CHECKBOX", "SIGNATURE"]
                }
              ]
            }
            """;

    /** docs/WORKER_CONTRACT.md POST /v1/render metadata part example, verbatim. */
    private static final String RENDER_METADATA =
            """
            {
              "worker": { "version": "0.2.0", "libraries": {"pypdfium2": "5.12.1"} },
              "pages": [
                {
                  "pageIndex": 0,
                  "widthPt": 612.0,
                  "heightPt": 792.0,
                  "rotation": 0,
                  "dpi": 200,
                  "widthPx": 1700,
                  "heightPx": 2200,
                  "pngPart": "page-0"
                }
              ]
            }
            """;

    private MockWebServer server;
    private WorkerClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client =
                new WorkerClient(
                        server.url("/").toString(),
                        SECRET,
                        Duration.ofSeconds(2),
                        Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void text_sends_secret_and_multipart_parts_and_parses_contract_fixture() throws Exception {
        server.enqueue(json(TEXT_RESPONSE));

        TextResult result = client.text(PDF);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/text");
        assertThat(recorded.getHeader("X-Worker-Secret")).isEqualTo(SECRET);
        assertThat(recorded.getHeader("Content-Type")).startsWith("multipart/form-data");
        String requestBody = recorded.getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(requestBody).contains("name=\"file\"").contains("name=\"request\"");
        assertThat(requestBody).contains("\"pages\":[]");

        assertThat(result.worker().version()).isEqualTo("0.2.0");
        assertThat(result.worker().libraries()).containsEntry("pdfplumber", "0.11.10");
        assertThat(result.pages()).hasSize(1);
        var page = result.pages().get(0);
        assertThat(page.pageIndex()).isZero();
        assertThat(page.widthPt()).isEqualByComparingTo("612.0");
        assertThat(page.verdict()).isEqualTo("NATIVE");
        assertThat(page.uncoveredRegions()).isNull();
        var span = page.spans().get(0);
        assertThat(span.text()).isEqualTo("YTD Gross");
        assertThat(span.x()).isEqualByComparingTo("112.3");
        assertThat(span.y()).isEqualByComparingTo("84.0");
        assertThat(span.width()).isEqualByComparingTo("58.2");
        assertThat(span.height()).isEqualByComparingTo("10.5");
        assertThat(span.fontSize()).isEqualByComparingTo("10.5");
        assertThat(span.fontName()).isEqualTo("Helvetica-Bold");
        assertThat(result.rawJson()).isEqualTo(TEXT_RESPONSE);
    }

    @Test
    void text_parses_mixed_verdict_with_uncovered_regions() {
        server.enqueue(
                json(
                        """
                        {
                          "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
                          "pages": [
                            {
                              "pageIndex": 0,
                              "widthPt": 612.0,
                              "heightPt": 792.0,
                              "rotation": 0,
                              "verdict": "MIXED",
                              "spans": [],
                              "uncoveredRegions": [{"x": 36.0, "y": 400.0, "width": 540.0, "height": 300.5}]
                            }
                          ]
                        }
                        """));

        TextResult result = client.text(PDF);

        var regions = result.pages().get(0).uncoveredRegions();
        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).x()).isEqualByComparingTo("36.0");
        assertThat(regions.get(0).height()).isEqualByComparingTo("300.5");
    }

    @Test
    void ocr_request_carries_the_page_rotate() throws Exception {
        // Phase 2 review (critical, coords): without the page /Rotate the worker
        // cannot know the raster's frame relative to rotation-0, and OCR boxes on
        // /Rotate pages land a quarter-turn out of the page frame.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(OCR_RESPONSE));

        client.ocr(PNG, new OcrRequest(0, new java.math.BigDecimal("792.0"),
                new java.math.BigDecimal("612.0"), 200, 90, List.of()));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"rotation\":90");
    }

    @Test
    void render_accepts_a_QUOTED_boundary_parameter() throws Exception {
        // The real worker (FastAPI/Starlette) emits boundary="..." — RFC 2045 allows the
        // quotes and Spring's MediaType.getParameter returns them verbatim. The first true
        // end-to-end run failed exactly here while every unquoted-boundary fixture passed.
        String boundary = "pds-worker-559a882b107a4e12";
        Buffer body = new Buffer();
        body.writeUtf8("--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n");
        body.writeUtf8("Content-Type: application/json\r\n\r\n");
        body.writeUtf8(RENDER_METADATA);
        body.writeUtf8("\r\n--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"page-0\"\r\n");
        body.writeUtf8("Content-Type: image/png\r\n\r\n");
        body.write(PNG);
        body.writeUtf8("\r\n--" + boundary + "--\r\n");
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "multipart/mixed; boundary=\"" + boundary + "\"")
                        .setBody(body));

        RenderResult result = client.render(PDF, List.of(), 200);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).png()).isEqualTo(PNG);
    }

    @Test
    void render_parses_multipart_mixed_metadata_then_binary_parts() throws Exception {
        String boundary = "docengine-fixture-boundary";
        Buffer body = new Buffer();
        body.writeUtf8("--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n");
        body.writeUtf8("Content-Type: application/json\r\n\r\n");
        body.writeUtf8(RENDER_METADATA);
        body.writeUtf8("\r\n--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"page-0\"\r\n");
        body.writeUtf8("Content-Type: image/png\r\n\r\n");
        body.write(PNG);
        body.writeUtf8("\r\n--" + boundary + "--\r\n");
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "multipart/mixed; boundary=" + boundary)
                        .setBody(body));

        RenderResult result = client.render(PDF, List.of(), 200);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/render");
        String requestBody = recorded.getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(requestBody).contains("\"dpi\":200");

        assertThat(result.worker().version()).isEqualTo("0.2.0");
        assertThat(result.worker().libraries()).containsEntry("pypdfium2", "5.12.1");
        assertThat(result.pages()).hasSize(1);
        var page = result.pages().get(0);
        assertThat(page.meta().pageIndex()).isZero();
        assertThat(page.meta().widthPt()).isEqualByComparingTo("612.0");
        assertThat(page.meta().heightPt()).isEqualByComparingTo("792.0");
        assertThat(page.meta().rotation()).isZero();
        assertThat(page.meta().dpi()).isEqualTo(200);
        assertThat(page.meta().widthPx()).isEqualTo(1700);
        assertThat(page.meta().heightPx()).isEqualTo(2200);
        // PNG bytes verbatim — including the embedded \r\n in the PNG signature.
        assertThat(page.png()).isEqualTo(PNG);
    }

    @Test
    void ocr_sends_regions_and_parses_contract_fixture_with_raw_blobs() throws Exception {
        server.enqueue(json(OCR_RESPONSE));

        OcrResult result =
                client.ocr(
                        PNG,
                        new OcrRequest(
                                3,
                                new BigDecimal("612.0"),
                                new BigDecimal("792.0"),
                                200,
                                0,
                                List.of(
                                        new WireBox(
                                                new BigDecimal("36.0"),
                                                new BigDecimal("400.0"),
                                                new BigDecimal("540.0"),
                                                new BigDecimal("300.5")))));

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/ocr");
        String requestBody = recorded.getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(requestBody).contains("\"pageIndex\":3").contains("\"regions\":[{");
        assertThat(requestBody).contains("\"x\":36.0");

        assertThat(result.pageIndex()).isEqualTo(3);
        assertThat(result.detectedRotation()).isEqualTo(90);
        assertThat(result.osdConfidence()).isEqualByComparingTo("0.94");
        assertThat(result.engine()).isEqualTo("RAPIDOCR");
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.confidenceMedian()).isEqualByComparingTo("0.91");
        var span = result.spans().get(0);
        assertThat(span.text()).isEqualTo("$48,231.30");
        assertThat(span.engine()).isEqualTo("RAPIDOCR");
        assertThat(span.confidence()).isEqualByComparingTo("0.97");
        assertThat(span.x()).isEqualByComparingTo("112.3");
        assertThat(result.rawRapidocrJson()).contains("\"spans\"");
        assertThat(result.rawTesseractJson()).isNull();
        assertThat(result.rawJson()).isEqualTo(OCR_RESPONSE);
    }

    private static LayoutRequest oneSpanLayoutRequest() {
        return new LayoutRequest(
                List.of(
                        new LayoutRequestPage(
                                0,
                                new BigDecimal("612.0"),
                                new BigDecimal("792.0"),
                                List.of(
                                        new LayoutRequestSpan(
                                                12,
                                                "Earnings",
                                                new BigDecimal("72.0"),
                                                new BigDecimal("178.5"),
                                                new BigDecimal("47.9"),
                                                new BigDecimal("12.8"),
                                                new BigDecimal("11.0"),
                                                "Helvetica-Bold")))));
    }

    @Test
    void layout_sends_file_and_request_parts_and_parses_nested_table_tree() throws Exception {
        server.enqueue(json(LAYOUT_RESPONSE));

        LayoutResult result = client.layout(PDF, oneSpanLayoutRequest());

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/layout");
        assertThat(recorded.getHeader("X-Worker-Secret")).isEqualTo(SECRET);
        assertThat(recorded.getHeader("Content-Type")).startsWith("multipart/form-data");
        String requestBody = recorded.getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(requestBody).contains("name=\"file\"").contains("name=\"request\"");
        // Spans ride IN on the request — canonical values verbatim, ordinal is the caller's id.
        assertThat(requestBody)
                .contains("\"pageIndex\":0")
                .contains("\"widthPt\":612.0")
                .contains("\"ordinal\":12")
                .contains("\"x\":72.0")
                .contains("\"fontName\":\"Helvetica-Bold\"");

        assertThat(result.worker().version()).isEqualTo("0.3.0");
        assertThat(result.pages()).hasSize(1);
        var page = result.pages().get(0);
        assertThat(page.pageIndex()).isZero();
        assertThat(page.notImplemented()).containsExactly("CHECKBOX", "SIGNATURE");
        assertThat(page.elements()).hasSize(3);

        var table = page.elements().get(0);
        assertThat(table.elementId()).isEqualTo("e0");
        assertThat(table.parentElementId()).isNull();
        assertThat(table.elementType()).isEqualTo("TABLE");
        assertThat(table.ordinal()).isEqualTo(4);
        assertThat(table.x()).isEqualByComparingTo("66.0");
        assertThat(table.y()).isEqualByComparingTo("168.0");
        assertThat(table.width()).isEqualByComparingTo("494.0");
        assertThat(table.height()).isEqualByComparingTo("110.0");
        assertThat(table.confidence()).isEqualByComparingTo("0.95");
        assertThat(table.detector()).isEqualTo("clustering");
        assertThat(table.detectorVersion()).isEqualTo("0.3.0");
        assertThat(table.attributesJson()).contains("\"rows\":5").contains("\"ruled\":true");
        assertThat(table.spanOrdinals()).isEmpty();

        var row = page.elements().get(1);
        assertThat(row.elementType()).isEqualTo("TABLE_ROW");
        assertThat(row.parentElementId()).isEqualTo("e0");

        var cell = page.elements().get(2);
        assertThat(cell.elementType()).isEqualTo("TABLE_CELL");
        assertThat(cell.parentElementId()).isEqualTo("e1");
        assertThat(cell.spanOrdinals()).containsExactly(12);
        assertThat(cell.attributesJson()).contains("\"row\":0").contains("\"col\":0");

        assertThat(result.rawJson()).isEqualTo(LAYOUT_RESPONSE);
    }

    @Test
    void layout_omits_the_optional_file_part_for_pure_scanned_sources() throws Exception {
        server.enqueue(json(LAYOUT_RESPONSE));

        client.layout(null, oneSpanLayoutRequest());

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/layout");
        String requestBody = recorded.getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(requestBody).doesNotContain("name=\"file\"").contains("name=\"request\"");
    }

    @Test
    void layout_omits_font_metadata_for_ocr_spans() throws Exception {
        // OCR spans have neither fontSize nor fontName — the contract marks both optional, so
        // nulls are OMITTED rather than sent as JSON null.
        server.enqueue(json(LAYOUT_RESPONSE));

        client.layout(
                null,
                new LayoutRequest(
                        List.of(
                                new LayoutRequestPage(
                                        0,
                                        new BigDecimal("612.0"),
                                        new BigDecimal("792.0"),
                                        List.of(
                                                new LayoutRequestSpan(
                                                        0,
                                                        "$48,231.30",
                                                        new BigDecimal("112.3"),
                                                        new BigDecimal("84.0"),
                                                        new BigDecimal("61.0"),
                                                        new BigDecimal("11.2"),
                                                        null,
                                                        null))))));

        String requestBody =
                server.takeRequest().getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(requestBody).doesNotContain("fontSize").doesNotContain("fontName");
    }

    @Test
    void layout_page_without_elements_or_not_implemented_parses_empty() {
        server.enqueue(
                json(
                        """
                        {
                          "worker": { "version": "0.3.0", "libraries": {} },
                          "pages": [ { "pageIndex": 0, "elements": [], "notImplemented": [] } ]
                        }
                        """));

        LayoutResult result = client.layout(null, oneSpanLayoutRequest());

        assertThat(result.pages().get(0).elements()).isEmpty();
        assertThat(result.pages().get(0).notImplemented()).isEmpty();
    }

    /**
     * The declared-empty list above means "looked for everything". A page WITHOUT the key —
     * the contract requires it, but an older or loosely conforming build may omit it — must
     * parse as null, "never declared": collapsing absent into {@code []} would let the adapter
     * persist a full-coverage claim nobody made.
     */
    @Test
    void layout_page_missing_the_not_implemented_key_parses_null_not_empty() {
        server.enqueue(
                json(
                        """
                        {
                          "worker": { "version": "0.3.0", "libraries": {} },
                          "pages": [ { "pageIndex": 0, "elements": [] } ]
                        }
                        """));

        LayoutResult result = client.layout(null, oneSpanLayoutRequest());

        assertThat(result.pages().get(0).notImplemented())
                .as("absent is a different statement from declared-empty")
                .isNull();
    }

    @Test
    void text_parses_ink_fraction_per_page_null_when_absent() {
        // /v1/text inkFraction is the blank-page signal: persisted as page.blank_score.
        // null when the page could not be rendered for the check; tolerate absence too.
        server.enqueue(
                json(
                        """
                        {
                          "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
                          "pages": [
                            { "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                              "verdict": "NONE", "spans": [], "inkFraction": 0.0031 },
                            { "pageIndex": 1, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                              "verdict": "NATIVE", "spans": [] }
                          ]
                        }
                        """));

        TextResult result = client.text(PDF);

        assertThat(result.pages().get(0).inkFraction()).isEqualByComparingTo("0.0031");
        assertThat(result.pages().get(1).inkFraction()).isNull();
    }

    @Test
    void non_2xx_maps_the_stable_code_and_never_carries_body_text() {
        server.enqueue(
                json("{\"error\": \"CORRUPT_PDF\", \"detail\": {\"reason\": \"sensitive-looking\"}}")
                        .setResponseCode(422));

        WorkerCallException e =
                catchThrowableOfType(WorkerCallException.class, () -> client.text(PDF));

        assertThat(e.errorCode()).isEqualTo(ErrorCode.CORRUPT_PDF);
        assertThat(e.workerCode()).isEqualTo("CORRUPT_PDF");
        assertThat(e.httpStatus()).isEqualTo(422);
        // Never body text: an error message may be logged, and the body may quote content.
        assertThat(e.getMessage()).doesNotContain("sensitive-looking").doesNotContain("detail");
    }

    @Test
    void an_undecodable_image_keeps_its_own_code_instead_of_collapsing_to_worker_unavailable() {
        // Unmapped worker codes collapse conservatively to WORKER_UNAVAILABLE, which would read
        // as "the worker is down" for a file the worker read perfectly well and refused. The
        // reviewer needs to see that the IMAGE is the problem.
        server.enqueue(json("{\"error\": \"CORRUPT_IMAGE\", \"detail\": {}}").setResponseCode(400));

        WorkerCallException e =
                catchThrowableOfType(WorkerCallException.class, () -> client.text(PDF));

        assertThat(e.errorCode()).isEqualTo(ErrorCode.CORRUPT_IMAGE);
        assertThat(e.workerCode()).isEqualTo("CORRUPT_IMAGE");
    }

    @Test
    void page_out_of_range_maps_to_invalid_request() {
        server.enqueue(json("{\"error\": \"PAGE_OUT_OF_RANGE\", \"detail\": {}}").setResponseCode(400));

        WorkerCallException e =
                catchThrowableOfType(
                        WorkerCallException.class, () -> client.render(PDF, List.of(99), 200));

        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(e.workerCode()).isEqualTo("PAGE_OUT_OF_RANGE");
    }

    @Test
    void unparseable_error_body_maps_to_worker_unavailable_without_leaking_it() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Traceback: boom"));

        WorkerCallException e =
                catchThrowableOfType(WorkerCallException.class, () -> client.text(PDF));

        assertThat(e.errorCode()).isEqualTo(ErrorCode.WORKER_UNAVAILABLE);
        assertThat(e.workerCode()).isNull();
        assertThat(e.getMessage()).doesNotContain("boom");
    }

    @Test
    void read_timeout_maps_to_worker_timeout() {
        server.enqueue(
                json(TEXT_RESPONSE).setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.text(PDF))
                .isInstanceOf(WorkerCallException.class)
                .satisfies(
                        e ->
                                assertThat(((WorkerCallException) e).errorCode())
                                        .isEqualTo(ErrorCode.WORKER_TIMEOUT));
    }

    @Test
    void connect_failure_maps_to_worker_unavailable() throws Exception {
        String url = server.url("/").toString();
        server.shutdown();
        WorkerClient dead =
                new WorkerClient(url, SECRET, Duration.ofMillis(300), Duration.ofMillis(300));

        assertThatThrownBy(() -> dead.text(PDF))
                .isInstanceOf(WorkerCallException.class)
                .satisfies(
                        e ->
                                assertThat(((WorkerCallException) e).errorCode())
                                        .isEqualTo(ErrorCode.WORKER_UNAVAILABLE));
    }
}
