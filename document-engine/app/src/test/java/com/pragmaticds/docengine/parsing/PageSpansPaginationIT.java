package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.AbstractClassificationIT;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P1.5 — size and pagination.
 *
 * <p>Two properties, and both are about a consumer that cannot check the answer itself.
 *
 * <p><b>Size.</b> A typical page is a few hundred spans and must arrive in ONE response. If it
 * silently grew to several round trips the endpoint would still look correct while costing a
 * consumer an order of magnitude more requests than the design budgeted for.
 *
 * <p><b>Exhaustion.</b> A cursor walk must visit every span exactly once. A duplicate inflates a
 * count; a gap loses a word — and a consumer paging blindly has no way to notice either, because
 * both come back as a perfectly well-formed 200. The synthetic page below is deliberately MIXED, so
 * the walk crosses the NATIVE-to-OCR boundary where an ordinal-only cursor breaks.
 */
class PageSpansPaginationIT extends AbstractClassificationIT {

    /**
     * The design budgets ~165 B per span with font metadata, so a 50-span page is ~8 KB plus the
     * page envelope. 20 KB is generous headroom for JSON formatting while still failing loudly if
     * the per-span payload roughly doubles — which is the regression worth catching, since it is
     * multiplied by 161k spans across a real corpus.
     */
    private static final int ONE_PAGE_BYTE_BUDGET = 20_000;

    private static final int SYNTHETIC_SPANS = 1_200;

    @Test
    void the_fixture_page_arrives_whole_in_one_response_inside_the_byte_budget() throws Exception {
        UUID pageId = fixturePage();

        byte[] body =
                mockMvc.perform(spans(pageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        JsonNode parsed = JSON.readTree(body);

        assertThat(parsed.path("spans")).hasSize(50);
        assertThat(parsed.path("truncated").asBoolean()).isFalse();
        assertThat(body.length)
                .as("50 spans in one response, %d bytes", body.length)
                .isLessThan(ONE_PAGE_BYTE_BUDGET);
    }

    @Test
    void a_twelve_hundred_span_page_paginates_to_exhaustion_with_no_duplicate_and_no_gap()
            throws Exception {
        UUID pageId = syntheticPage();
        List<Long> expected = spanIdsInTotalOrder(pageId);
        assertThat(expected).hasSize(SYNTHETIC_SPANS);

        List<Long> walked = walk(pageId, 250, null);

        assertThat(walked).as("every span, once, in the page's own order").isEqualTo(expected);
        assertThat(new HashSet<>(walked)).as("no duplicate").hasSize(SYNTHETIC_SPANS);
    }

    /** A page whose span count is an exact multiple of the limit must not claim a phantom next. */
    @Test
    void an_exact_multiple_final_window_reports_truncated_false_and_no_cursor() throws Exception {
        UUID pageId = syntheticPage();

        JsonNode last = null;
        String cursor = null;
        int windows = 0;
        do {
            last = json(spans(pageId).param("limit", "400").param("after", cursor));
            cursor = last.path("nextCursor").isNull() ? null : last.path("nextCursor").asText();
            windows++;
        } while (cursor != null);

        assertThat(windows).as("1200 spans at 400 a window").isEqualTo(3);
        assertThat(last.path("returned").asInt()).isEqualTo(400);
        assertThat(last.path("truncated").asBoolean())
                .as("the third window IS the last one — no empty fourth request")
                .isFalse();
    }

    /**
     * The walk must be exact while other readers are hitting the engine. It is, and the reason is
     * structural rather than lucky: the cursor is a POSITION rather than a server-side snapshot, and
     * nothing about a read is stateful. This test exists so that a future change which quietly makes
     * a cursor session-scoped, or a window shared between concurrent requests, fails here.
     *
     * <p>The readers hammer a DIFFERENT page and assert that every id they receive belongs to it.
     * That matters: readers pointed at the walker's own page could only ever assert "an array came
     * back", which a leaky implementation satisfies just as well as a correct one. Pointed at another
     * page, a single span of the walker's arriving in a reader's window fails the assertion — and a
     * reader's span arriving in the walk fails the exact-equality check below.
     */
    @Test
    void the_walk_is_exact_while_other_readers_hammer_a_different_page() throws Exception {
        UUID pageId = syntheticPage();
        UUID otherPageId = syntheticPage();
        List<Long> expected = spanIdsInTotalOrder(pageId);
        Set<Long> otherPageIds = new HashSet<>(spanIdsInTotalOrder(otherPageId));
        AtomicBoolean walking = new AtomicBoolean(true);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> readers = new ArrayList<>();
            for (int reader = 0; reader < 3; reader++) {
                final int offset = reader * 137;
                readers.add(
                        pool.submit(
                                (Callable<Integer>)
                                        () -> {
                                            int reads = 0;
                                            while (walking.get()) {
                                                JsonNode body =
                                                        json(
                                                                spans(otherPageId)
                                                                        .param("limit", "100")
                                                                        .param(
                                                                                "box",
                                                                                offset
                                                                                        + ",0,200,800"));
                                                assertThat(body.path("pageId").asText())
                                                        .isEqualTo(otherPageId.toString());
                                                assertThat(ids(body))
                                                        .as("a concurrent read never borrows another page's spans")
                                                        .isSubsetOf(otherPageIds);
                                                reads++;
                                            }
                                            return reads;
                                        }));
            }

            List<Long> walked = walk(pageId, 97, null);
            walking.set(false);

            int concurrentReads = 0;
            for (Future<Integer> reader : readers) {
                concurrentReads += reader.get();
            }
            assertThat(concurrentReads).as("the readers really did run").isPositive();
            assertThat(walked).isEqualTo(expected);
        } finally {
            walking.set(false);
            pool.shutdownNow();
        }
    }

    /**
     * A window narrowed mid-walk continues from the cursor: a filter narrows, it never reorders and
     * it never restarts.
     *
     * <p>The cursor is deliberately parked INSIDE the OCR block, 100 spans in. Narrowing from a
     * cursor parked in the NATIVE block would prove nothing — an implementation that ignored
     * {@code ?after=} entirely returns the OCR block from ordinal 0 either way, which is exactly what
     * a correct one returns from a NATIVE-parked cursor. From an OCR-parked cursor the two answers
     * differ by 100 spans, so the expected id list below can actually fail.
     */
    @Test
    void narrowing_the_filter_mid_walk_continues_from_the_cursor_rather_than_restarting()
            throws Exception {
        UUID pageId = syntheticPage();
        List<Long> ocrBlock = spanIdsInTotalOrder(pageId, "OCR");
        assertThat(ocrBlock).hasSize(500);

        // 700 NATIVE + the first 100 OCR: the cursor lands on OCR ordinal 99.
        JsonNode first = json(spans(pageId).param("limit", "800"));
        assertThat(first.path("returned").asInt()).isEqualTo(800);
        assertThat(first.at("/spans/799/source").asText()).isEqualTo("OCR");
        String cursor = first.path("nextCursor").asText();

        JsonNode narrowed =
                json(
                        spans(pageId)
                                .param("limit", "400")
                                .param("source", "OCR")
                                .param("after", cursor));

        assertThat(ids(narrowed))
                .as("resumes at OCR 100 — it does not restart the OCR block at 0")
                .isEqualTo(ocrBlock.subList(100, 500));
        assertThat(narrowed.path("truncated").asBoolean()).isFalse();
    }

    /**
     * The asymmetry the cursor's contract now states out loud: narrowing mid-walk is coherent,
     * WIDENING is not, and the server cannot tell the caller so.
     *
     * <p>The token pins the order, not the filters, and a cursor parked in the OCR block sorts after
     * every NATIVE row. Dropping {@code ?source=OCR} mid-walk therefore does not go back for the
     * NATIVE block — it walks to exhaustion and ends on {@code truncated: false}, having silently
     * skipped 700 spans. This test exists so the behaviour is measured rather than assumed, and so
     * that anyone who later makes the token pin its filters has to come here and say so.
     */
    @Test
    void widening_the_filter_mid_walk_skips_everything_that_sorts_before_the_cursor()
            throws Exception {
        UUID pageId = syntheticPage();

        JsonNode first = json(spans(pageId).param("limit", "100").param("source", "OCR"));
        assertThat(first.path("returned").asInt()).isEqualTo(100);

        List<Long> rest = walk(pageId, 400, first.path("nextCursor").asText());

        assertThat(rest).as("only the remainder of the OCR block, never the NATIVE block").hasSize(400);
        assertThat(rest).containsExactlyElementsOf(spanIdsInTotalOrder(pageId, "OCR").subList(100, 500));
        assertThat(rest).doesNotContainAnyElementsOf(spanIdsInTotalOrder(pageId, "NATIVE"));
    }

    // ── fixture plumbing ────────────────────────────────────────────────────

    private static MockHttpServletRequestBuilder spans(UUID pageId) {
        return get("/v1/pages/{id}/spans", pageId);
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        return JSON.readTree(
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    /** Follows nextCursor to exhaustion, collecting ids in the order they were served. */
    private List<Long> walk(UUID pageId, int limit, String cursor) throws Exception {
        List<Long> ids = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        while (true) {
            MockHttpServletRequestBuilder request = spans(pageId).param("limit", String.valueOf(limit));
            if (cursor != null) {
                request = request.param("after", cursor);
            }
            JsonNode body = json(request);
            body.path("spans").forEach(span -> ids.add(span.path("id").asLong()));
            if (body.path("nextCursor").isNull()) {
                assertThat(body.path("truncated").asBoolean()).isFalse();
                return ids;
            }
            cursor = body.path("nextCursor").asText();
            assertThat(seenCursors.add(cursor)).as("a cursor is never re-issued — that is a loop").isTrue();
        }
    }

    private UUID fixturePage() {
        return insertFixturePages(insertPackage("size-" + UUID.randomUUID()), "paystub_complete")
                .get(0);
    }

    /**
     * 1,200 spans on one MIXED page: 700 NATIVE then 500 OCR, both restarting at ordinal 0, which is
     * the shape that makes ordinal alone unusable as a cursor.
     */
    private UUID syntheticPage() {
        UUID packageId = insertPackage("pagination-" + UUID.randomUUID());
        UUID sourceFileId = insertSourceFile(packageId, ORG_DEV);
        UUID pageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO page (id, org_id, source_file_id, package_id, page_index,"
                        + " package_page_index, width_pt, height_pt, text_layer) VALUES"
                        + " (?, ?, ?, ?, 0, 0, 612.00, 792.00, 'MIXED')",
                pageId,
                ORG_DEV,
                sourceFileId,
                packageId);
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            batch.add(row(pageId, i, "native-" + i, "NATIVE", null, "1.0000"));
        }
        for (int i = 0; i < 500; i++) {
            batch.add(row(pageId, i, "ocr-" + i, "OCR", "RAPIDOCR", "0.9100"));
        }
        jdbc.batchUpdate(
                "INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width, height,"
                        + " source, ocr_engine, confidence) VALUES (?, ?, ?, ?, ?, ?, 20, 10, ?, ?,"
                        + " CAST(? AS numeric))",
                batch);
        return pageId;
    }

    private Object[] row(UUID pageId, int ordinal, String text, String source, String engine, String confidence) {
        return new Object[] {
            ORG_DEV,
            pageId,
            ordinal,
            text,
            (ordinal % 7) * 60.0,
            (ordinal % 60) * 12.0,
            source,
            engine,
            confidence
        };
    }

    private List<Long> spanIdsInTotalOrder(UUID pageId) {
        return jdbc.queryForList(
                "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id",
                Long.class,
                pageId);
    }

    private List<Long> spanIdsInTotalOrder(UUID pageId, String source) {
        return jdbc.queryForList(
                "SELECT id FROM text_span WHERE page_id = ? AND source = ?"
                        + " ORDER BY ordinal, id",
                Long.class,
                pageId,
                source);
    }

    private static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.path("spans").forEach(span -> ids.add(span.path("id").asLong()));
        return ids;
    }
}
