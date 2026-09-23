package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.parsing.support.Digests;
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
 * Phase 3 PARSING stage acceptance: {@code /v1/layout} per source file (the original PDF
 * attached to EVERY call — Spec 3 pixel path: worker-side rendering feeds the
 * checkbox/signature detectors), TABLE > TABLE_ROW > TABLE_CELL trees persisted with resolved
 * parentage and span links to real {@code text_span} rows, {@code notImplemented} recorded in the
 * raw parser_output, and the page signals that ride the same stage: span-content {@code
 * content_hash}, package-wide duplicate flags, and the /v1/text blank signals.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true",
            "docengine.processing.adapter=worker",
            "docengine.worker.shared-secret=it-worker-secret"
        })
class WorkerParsingStageIT extends AbstractPostgresIT {

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

    /** File A: page 0 native with two spans, page 1 blank (verdict NONE, inkFraction ~0). */
    private static final String TEXT_A =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "NATIVE", "inkFraction": 0.0831,
                  "spans": [
                    { "ordinal": 0, "text": "YTD Gross",
                      "x": 112.3, "y": 84.0, "width": 58.2, "height": 10.5,
                      "fontSize": 10.5, "fontName": "Helvetica-Bold" },
                    { "ordinal": 1, "text": "$48,231.30",
                      "x": 176.1, "y": 84.0, "width": 61.0, "height": 10.5,
                      "fontSize": 10.5, "fontName": "Helvetica" }
                  ]
                },
                { "pageIndex": 1, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "NONE", "inkFraction": 0.0021, "spans": [] }
              ]
            }
            """;

    /** File B: one SCANNED page whose OCR spans match file A page 0 exactly (text + geometry). */
    private static final String TEXT_B =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "SCANNED", "inkFraction": 0.0790, "spans": [] }
              ]
            }
            """;

    private static final String OCR_B =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
              "pageIndex": 0,
              "detectedRotation": 0,
              "osdConfidence": 0.97,
              "engine": "RAPIDOCR",
              "fallbackReason": null,
              "confidenceMedian": 0.95,
              "spans": [
                { "ordinal": 0, "text": "YTD Gross",
                  "x": 112.3, "y": 84.0, "width": 58.2, "height": 10.5,
                  "engine": "RAPIDOCR", "confidence": 0.97 },
                { "ordinal": 1, "text": "$48,231.30",
                  "x": 176.1, "y": 84.0, "width": 61.0, "height": 10.5,
                  "engine": "RAPIDOCR", "confidence": 0.91 }
              ],
              "gates": {},
              "raw": { "rapidocr": { "spans": [] }, "tesseract": null }
            }
            """;

    /** Layout for file A: a TABLE > TABLE_ROW > TABLE_CELL tree on page 0, child-first order. */
    private static final String LAYOUT_A =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0,
                  "elements": [
                    {
                      "elementId": "e2", "parentElementId": "e1", "elementType": "TABLE_CELL",
                      "ordinal": 2, "x": 112.3, "y": 84.0, "width": 61.0, "height": 10.5,
                      "confidence": 0.95, "attributes": { "row": 0, "col": 1 },
                      "spanOrdinals": [1],
                      "detector": "clustering", "detectorVersion": "0.3.0"
                    },
                    {
                      "elementId": "e1", "parentElementId": "e0", "elementType": "TABLE_ROW",
                      "ordinal": 1, "x": 112.3, "y": 84.0, "width": 124.8, "height": 10.5,
                      "confidence": 0.95, "attributes": { "row": 0 },
                      "spanOrdinals": [],
                      "detector": "clustering", "detectorVersion": "0.3.0"
                    },
                    {
                      "elementId": "e0", "parentElementId": null, "elementType": "TABLE",
                      "ordinal": 0, "x": 112.3, "y": 84.0, "width": 124.8, "height": 10.5,
                      "confidence": 0.95, "attributes": { "rows": 1, "cols": 2, "ruled": true },
                      "spanOrdinals": [],
                      "detector": "clustering", "detectorVersion": "0.3.0"
                    }
                  ],
                  "notImplemented": ["CHECKBOX", "SIGNATURE"]
                },
                { "pageIndex": 1, "elements": [], "notImplemented": ["CHECKBOX", "SIGNATURE"] }
              ]
            }
            """;

    private static final String LAYOUT_B =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0,
                  "elements": [
                    {
                      "elementId": "e0", "parentElementId": null, "elementType": "PARAGRAPH",
                      "ordinal": 0, "x": 112.3, "y": 84.0, "width": 124.8, "height": 10.5,
                      "confidence": 0.90, "attributes": null,
                      "spanOrdinals": [0, 1],
                      "detector": "clustering", "detectorVersion": "0.3.0"
                    }
                  ],
                  "notImplemented": ["CHECKBOX", "SIGNATURE"]
                }
              ]
            }
            """;

    /** Layout for file A with page 1 MISSING from the response entirely (retry tolerance). */
    private static final String LAYOUT_A_PAGE0_ONLY =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0,
                  "elements": [
                    {
                      "elementId": "e2", "parentElementId": "e1", "elementType": "TABLE_CELL",
                      "ordinal": 2, "x": 112.3, "y": 84.0, "width": 61.0, "height": 10.5,
                      "confidence": 0.95, "attributes": { "row": 0, "col": 1 },
                      "spanOrdinals": [1],
                      "detector": "clustering", "detectorVersion": "0.3.0"
                    },
                    {
                      "elementId": "e1", "parentElementId": "e0", "elementType": "TABLE_ROW",
                      "ordinal": 1, "x": 112.3, "y": 84.0, "width": 124.8, "height": 10.5,
                      "confidence": 0.95, "attributes": { "row": 0 },
                      "spanOrdinals": [],
                      "detector": "clustering", "detectorVersion": "0.3.0"
                    },
                    {
                      "elementId": "e0", "parentElementId": null, "elementType": "TABLE",
                      "ordinal": 0, "x": 112.3, "y": 84.0, "width": 124.8, "height": 10.5,
                      "confidence": 0.95, "attributes": { "rows": 1, "cols": 2, "ruled": true },
                      "spanOrdinals": [],
                      "detector": "clustering", "detectorVersion": "0.3.0"
                    }
                  ],
                  "notImplemented": ["CHECKBOX", "SIGNATURE"]
                }
              ]
            }
            """;

    /** Layout for file A with NO notImplemented key anywhere — a nonconforming worker build. */
    private static final String LAYOUT_A_NO_DECLARATION =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "elements": [] },
                { "pageIndex": 1, "elements": [] }
              ]
            }
            """;

    private static final String LAYOUT_B_NO_DECLARATION =
            """
            {
              "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "elements": [] }
              ]
            }
            """;

    /** The documented span-content-hash serialization, replicated so a format drift fails here. */
    private static final String EXPECTED_CONTENT_HASH =
            Digests.sha256Hex(
                    "YTD Gross\u001F112.30\u001F84.00\u001F58.20\u001F10.50\u001E"
                            + "$48,231.30\u001F176.10\u001F84.00\u001F61.00\u001F10.50\u001E");

    @Autowired JobService jobService;
    @Autowired ParserPort parserPort;
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
                "parsing-it");
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

    private void enqueueFullPipeline() {
        WORKER.enqueue(WorkerFixtures.renderResponse("PNG-A-0", "PNG-A-1"));
        WORKER.enqueue(WorkerFixtures.renderResponse("PNG-B-0"));
        WORKER.enqueue(WorkerFixtures.json(TEXT_A));
        WORKER.enqueue(WorkerFixtures.json(TEXT_B));
        WORKER.enqueue(WorkerFixtures.json(OCR_B));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_A));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_B));
    }

    @Test
    void parsing_persists_layout_trees_signals_and_duplicate_flags_across_files() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-file-A".getBytes(StandardCharsets.UTF_8));
        insertSourceFile(packageId, 1, "%PDF-file-B".getBytes(StandardCharsets.UTF_8));
        enqueueFullPipeline();

        ProcessingJob job = jobService.createJob(packageId, "parsing-happy-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        // ── request log: layout once per source file, the ORIGINAL PDF attached to BOTH ──
        List<RecordedRequest> requests = takeRequests(7);
        assertThat(requests.stream().map(RecordedRequest::getPath))
                .containsExactly(
                        "/v1/render",
                        "/v1/render",
                        "/v1/text",
                        "/v1/text",
                        "/v1/ocr",
                        "/v1/layout",
                        "/v1/layout");
        String layoutA = requests.get(5).getBody().readString(StandardCharsets.ISO_8859_1);
        // File A has NATIVE pages: rects/lines can confirm rulings worker-side, and
        // spans for BOTH its pages ride in (page 1 with an empty span list).
        assertThat(layoutA).contains("name=\"file\"").contains("%PDF-file-A");
        assertThat(layoutA)
                .contains("\"pageIndex\":0")
                .contains("\"pageIndex\":1")
                .contains("\"text\":\"YTD Gross\"")
                .contains("\"ordinal\":1");
        // File B is pure-scanned — since Spec 3 its PDF STILL rides along: the worker's
        // checkbox/signature detectors need pixels, and rendering happens worker-side.
        // Its OCR spans still ride in from the DB.
        String layoutB = requests.get(6).getBody().readString(StandardCharsets.ISO_8859_1);
        assertThat(layoutB).contains("name=\"file\"").contains("%PDF-file-B");
        assertThat(layoutB).contains("\"text\":\"YTD Gross\"");

        List<Map<String, Object>> pages =
                jdbc.queryForList(
                        "SELECT * FROM page WHERE package_id = ? ORDER BY package_page_index",
                        packageId);
        assertThat(pages).hasSize(3);
        Map<String, Object> pageA0 = pages.get(0);
        Map<String, Object> pageA1 = pages.get(1);
        Map<String, Object> pageB0 = pages.get(2);

        // ── the TABLE > TABLE_ROW > TABLE_CELL tree, parentage resolved to row UUIDs ──
        List<Map<String, Object>> elements =
                jdbc.queryForList(
                        "SELECT * FROM layout_element WHERE page_id = ? ORDER BY ordinal",
                        pageA0.get("id"));
        assertThat(elements).hasSize(3);
        Map<String, Object> table = elements.get(0);
        Map<String, Object> row = elements.get(1);
        Map<String, Object> cell = elements.get(2);
        assertThat(table.get("element_type")).isEqualTo("TABLE");
        assertThat(table.get("parent_element_id")).isNull();
        assertThat(row.get("element_type")).isEqualTo("TABLE_ROW");
        assertThat(row.get("parent_element_id")).isEqualTo(table.get("id"));
        assertThat(cell.get("element_type")).isEqualTo("TABLE_CELL");
        assertThat(cell.get("parent_element_id")).isEqualTo(row.get("id"));
        assertThat(table.get("detector")).isEqualTo("clustering");
        assertThat(table.get("detector_version")).isEqualTo("0.3.0");
        assertThat((BigDecimal) cell.get("x")).isEqualByComparingTo("112.3");

        // Span links resolve to REAL text_span rows.
        Map<String, Object> linked =
                jdbc.queryForMap(
                        """
                        SELECT ts.text, ts.source FROM layout_element_span les
                        JOIN text_span ts ON ts.id = les.text_span_id
                        WHERE les.layout_element_id = ?
                        """,
                        cell.get("id"));
        assertThat(linked.get("text")).isEqualTo("$48,231.30");
        assertThat(linked.get("source")).isEqualTo("NATIVE");
        assertThat(cell.get("text")).isEqualTo("$48,231.30");

        // File B's PARAGRAPH links to its OCR spans.
        List<Map<String, Object>> bLinks =
                jdbc.queryForList(
                        """
                        SELECT ts.text, ts.source FROM layout_element_span les
                        JOIN text_span ts ON ts.id = les.text_span_id
                        JOIN layout_element le ON le.id = les.layout_element_id
                        WHERE le.page_id = ? ORDER BY les.ordinal
                        """,
                        pageB0.get("id"));
        assertThat(bLinks).hasSize(2);
        assertThat(bLinks.get(0).get("source")).isEqualTo("OCR");

        // ── blank signals (from /v1/text inkFraction + verdict NONE) ──
        assertThat(pageA0.get("is_blank")).isEqualTo(false);
        assertThat((BigDecimal) pageA0.get("blank_score")).isEqualByComparingTo("0.0831");
        assertThat(pageA1.get("is_blank")).isEqualTo(true);
        assertThat((BigDecimal) pageA1.get("blank_score")).isEqualByComparingTo("0.0021");

        // ── content hash: sha256 over ordered span tuples; NO spans → null, not "equal blanks" ──
        assertThat(pageA0.get("content_hash")).isEqualTo(EXPECTED_CONTENT_HASH);
        assertThat(pageB0.get("content_hash")).isEqualTo(EXPECTED_CONTENT_HASH);
        assertThat(pageA1.get("content_hash")).isNull();
        assertThat(pageA1.get("duplicate_of_page_id")).isNull();

        // ── duplicate flag: B0 repeats A0's content — flagged with the FIRST page's id ──
        assertThat(pageA0.get("duplicate_of_page_id")).isNull();
        assertThat(pageB0.get("duplicate_of_page_id")).isEqualTo(pageA0.get("id"));

        // ── notImplemented recorded in the raw parser_output blob ──
        List<Map<String, Object>> outputs =
                jdbc.queryForList(
                        """
                        SELECT * FROM parser_output WHERE stage = 'PARSING'
                          AND source_file_id IN (SELECT id FROM source_file WHERE package_id = ?)
                        """,
                        packageId);
        assertThat(outputs).hasSize(2);
        for (Map<String, Object> output : outputs) {
            byte[] payload = storage.get((String) output.get("payload_storage_key"));
            assertThat(output.get("payload_sha256")).isEqualTo(Digests.sha256Hex(payload));
            String raw = new String(payload, StandardCharsets.UTF_8);
            assertThat(raw).contains("notImplemented").contains("CHECKBOX").contains("SIGNATURE");
        }

        // ── P2.4: the declaration ALSO lands on the page row, queryably — the raw blob above is
        // an audit artifact, not a read model, and the L2 detectorCoverage reads this column.
        // Every fixture layout page declares ["CHECKBOX", "SIGNATURE"], including A1, whose
        // element list is empty: an empty result stays distinguishable from "did not look".
        for (Object pageId : List.of(pageA0.get("id"), pageA1.get("id"), pageB0.get("id"))) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT jsonb_array_length(layout_not_implemented) FROM page"
                                            + " WHERE id = ?",
                                    Integer.class,
                                    (UUID) pageId))
                    .as("page %s carries the worker's two-entry declaration", pageId)
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT layout_not_implemented ?? 'CHECKBOX' AND"
                                            + " layout_not_implemented ?? 'SIGNATURE' FROM page"
                                            + " WHERE id = ?",
                                    Boolean.class,
                                    (UUID) pageId))
                    .isTrue();
        }
    }

    @Test
    void parsing_retry_is_idempotent_no_duplicate_elements_or_links() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-file-A".getBytes(StandardCharsets.UTF_8));
        insertSourceFile(packageId, 1, "%PDF-file-B".getBytes(StandardCharsets.UTF_8));
        enqueueFullPipeline();

        ProcessingJob job = jobService.createJob(packageId, "parsing-idem-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        int elementsBefore = countElements(packageId);
        int linksBefore = countLinks(packageId);
        assertThat(elementsBefore).isEqualTo(4);

        // The stage re-runs (retry semantics): same responses again, same DB state after.
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_A));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_B));
        ParserPort.StageOutcome retry =
                parserPort.run(
                        new ParserPort.StageRequest(
                                job.getId(),
                                packageId,
                                ProcessingStatus.PARSING,
                                2,
                                "parsing-idem-retry"));

        assertThat(retry.success()).isTrue();
        assertThat(countElements(packageId)).isEqualTo(elementsBefore);
        assertThat(countLinks(packageId)).isEqualTo(linksBefore);

        // Duplicate flags survive the re-run unchanged.
        List<Map<String, Object>> pages =
                jdbc.queryForList(
                        "SELECT * FROM page WHERE package_id = ? ORDER BY package_page_index",
                        packageId);
        assertThat(pages.get(2).get("duplicate_of_page_id")).isEqualTo(pages.get(0).get("id"));
    }

    /**
     * The per-attempt coverage clear, pinned on the one case it exists for: a page ABSENT from a
     * retry's layout responses (the adapter tolerates missing pages silently). Its element tree
     * is deleted at the start of the attempt, so its previous declaration must go with it —
     * keeping "the worker looked here" while the elements it looked at are gone would be a stale
     * coverage claim about a response that never mentioned the page.
     */
    @Test
    void a_page_missing_from_a_retry_layout_response_loses_its_previous_coverage_claim() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-file-A".getBytes(StandardCharsets.UTF_8));
        insertSourceFile(packageId, 1, "%PDF-file-B".getBytes(StandardCharsets.UTF_8));
        enqueueFullPipeline();

        ProcessingJob job = jobService.createJob(packageId, "parsing-omit-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        List<UUID> pageIds =
                jdbc.queryForList(
                        "SELECT id FROM page WHERE package_id = ? ORDER BY package_page_index",
                        UUID.class,
                        packageId);
        // Attempt 1 declared coverage for every page, page A1 included.
        assertThat(declarationLength(pageIds.get(1))).isEqualTo(2);

        // Attempt 2: file A's response omits page 1 ENTIRELY; file B unchanged.
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_A_PAGE0_ONLY));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_B));
        ParserPort.StageOutcome retry =
                parserPort.run(
                        new ParserPort.StageRequest(
                                job.getId(),
                                packageId,
                                ProcessingStatus.PARSING,
                                2,
                                "parsing-omit-retry"));
        assertThat(retry.success()).isTrue();

        // Pages the retry answered for keep the fresh declaration…
        assertThat(declarationLength(pageIds.get(0))).isEqualTo(2);
        assertThat(declarationLength(pageIds.get(2))).isEqualTo(2);
        // …and the page it never mentioned reads NULL: coverage unknowable, not inherited.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT layout_not_implemented IS NULL FROM page WHERE id = ?",
                                Boolean.class,
                                pageIds.get(1)))
                .as("an unanswered page must not keep attempt 1's coverage claim")
                .isTrue();
    }

    /**
     * A worker build that omits the {@code notImplemented} key — the wire contract requires it,
     * but an older or loosely conforming build may not send it — must be recorded as NULL
     * (coverage unknowable, L2 serves {@code UNKNOWN}), never as {@code []}: inventing the empty
     * list would assert "looked for everything" on a declaration nobody made, and a wrong
     * coverage claim is worse than an honest unknown.
     */
    @Test
    void an_absent_notImplemented_key_is_recorded_as_null_not_as_full_coverage() {
        UUID packageId = insertPackage();
        insertSourceFile(packageId, 0, "%PDF-file-A".getBytes(StandardCharsets.UTF_8));
        insertSourceFile(packageId, 1, "%PDF-file-B".getBytes(StandardCharsets.UTF_8));
        enqueueFullPipeline();

        ProcessingJob job = jobService.createJob(packageId, "parsing-nodecl-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        // A retry against a build that never sends the key, for any page.
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_A_NO_DECLARATION));
        WORKER.enqueue(WorkerFixtures.json(LAYOUT_B_NO_DECLARATION));
        ParserPort.StageOutcome retry =
                parserPort.run(
                        new ParserPort.StageRequest(
                                job.getId(),
                                packageId,
                                ProcessingStatus.PARSING,
                                2,
                                "parsing-nodecl-retry"));
        assertThat(retry.success()).isTrue();

        Integer pagesWithDeclaration =
                jdbc.queryForObject(
                        "SELECT count(*) FROM page WHERE package_id = ? AND"
                                + " layout_not_implemented IS NOT NULL",
                        Integer.class,
                        packageId);
        assertThat(pagesWithDeclaration)
                .as("no page may carry a coverage declaration the worker never made")
                .isZero();
    }

    private Integer declarationLength(UUID pageId) {
        return jdbc.queryForObject(
                "SELECT jsonb_array_length(layout_not_implemented) FROM page WHERE id = ?",
                Integer.class,
                pageId);
    }

    private int countElements(UUID packageId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM layout_element
                WHERE page_id IN (SELECT id FROM page WHERE package_id = ?)
                """,
                Integer.class,
                packageId);
    }

    private int countLinks(UUID packageId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM layout_element_span
                WHERE layout_element_id IN (
                    SELECT id FROM layout_element
                    WHERE page_id IN (SELECT id FROM page WHERE package_id = ?))
                """,
                Integer.class,
                packageId);
    }
}
