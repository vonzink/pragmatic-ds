package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRow;
import com.pragmaticds.docengine.parsing.repo.TextSpanWindow;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * P1.1 — the L1 cursor query, at the repository seam.
 *
 * <p>The property this class exists to prove is TOTALITY. {@code (page_id, ordinal)} is
 * deliberately non-unique — NATIVE and OCR spans both restart at ordinal 0 on a MIXED page
 * ({@code TextSpanRepository} javadoc) — so an ordinal-only cursor would either re-serve a row or
 * skip one at every source boundary. {@code (source, ordinal, id)} is total, and a cursor is only
 * as correct as the order it walks. Every fixture below is deliberately MIXED for that reason.
 */
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2"})
class TextSpanCursorIT extends AbstractPostgresIT {

    @Autowired private TextSpanRepository spans;

    private JdbcTemplate jdbc;
    private UUID pageId;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        pageId = seedPage();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── totality: the precondition for cursoring at all ─────────────────────

    @Test
    void ordinal_alone_is_not_a_key_but_source_ordinal_id_is() {
        insertSpan(SpanSource.NATIVE, 0, "native-zero", 10, 10, 1.0000);
        insertSpan(SpanSource.NATIVE, 1, "native-one", 10, 30, 1.0000);
        insertSpan(SpanSource.OCR, 0, "ocr-zero", 10, 50, 0.9100);
        insertSpan(SpanSource.OCR, 1, "ocr-one", 10, 70, 0.9200);

        List<TextSpanRow> all = window().page(pageId).limit(100).fetch(spans);

        assertThat(all).extracting(row -> row.span().getText())
                .containsExactly("native-zero", "native-one", "ocr-zero", "ocr-one");

        Set<Integer> ordinals = new HashSet<>();
        Set<String> totalKeys = new HashSet<>();
        int duplicateOrdinals = 0;
        for (TextSpanRow row : all) {
            if (!ordinals.add(row.span().getOrdinal())) {
                duplicateOrdinals++;
            }
            assertThat(
                            totalKeys.add(
                                    row.span().getSource()
                                            + "|"
                                            + row.span().getOrdinal()
                                            + "|"
                                            + row.span().getId()))
                    .as("(source, ordinal, id) is unique — the cursor key")
                    .isTrue();
        }
        assertThat(duplicateOrdinals)
                .as("ordinal alone repeats across the source boundary, which is why it cannot cursor")
                .isEqualTo(2);
    }

    /**
     * The {@code id} half of the total order, exercised rather than merely declared.
     *
     * <p>{@code text_span} carries NO unique constraint on {@code (page_id, source, ordinal)}, and
     * ordinals come from the worker's per-source enumeration after a package-wide
     * {@code deleteBySource} followed by a per-page insert loop — two overlapping PARSING runs on one
     * package can interleave delete and insert and leave two spans at the same
     * {@code (page, source, ordinal)}. Every other fixture in the repository assigns distinct
     * ordinals within a source, which leaves the {@code id} tiebreak arm dead: verified by mutation,
     * changing it from {@code s.id > :cursorId} to {@code s.id < :cursorId} left the whole suite
     * green before this test existed. With the arm broken, the walk below silently loses the second
     * span of the colliding pair — a clean {@code nextCursor} walk that has quietly dropped a word.
     */
    @Test
    void two_spans_at_the_same_source_and_ordinal_are_both_walked_exactly_once() {
        long lower = insertSpan(SpanSource.NATIVE, 4, "collision-lower-id", 10, 10, 1.0000);
        long upper = insertSpan(SpanSource.NATIVE, 4, "collision-upper-id", 10, 20, 1.0000);
        insertSpan(SpanSource.NATIVE, 5, "after-the-collision", 10, 30, 1.0000);
        assertThat(upper).as("the fixture really does depend on id to break the tie").isGreaterThan(lower);

        // One row at a time, so the cursor is parked ON the first of the colliding pair.
        List<Long> walked = new ArrayList<>();
        TextSpanWindow cursor = window().page(pageId).limit(1);
        while (true) {
            List<TextSpanRow> batch = cursor.fetch(spans);
            if (batch.isEmpty()) {
                break;
            }
            walked.add(batch.get(0).span().getId());
            cursor = cursor.after(batch.get(0));
        }

        assertThat(walked)
                .as("(source, ordinal) is not a key — id is what makes the triple total")
                .containsExactly(lower, upper, walked.get(2));
        assertThat(walked).hasSize(3);
        assertThat(new HashSet<>(walked)).hasSize(3);
    }

    @Test
    void a_cursor_walk_visits_every_span_exactly_once_with_no_duplicate_and_no_gap() {
        for (int i = 0; i < 7; i++) {
            insertSpan(SpanSource.NATIVE, i, "n" + i, 10, 10 + i, 1.0000);
        }
        for (int i = 0; i < 6; i++) {
            insertSpan(SpanSource.OCR, i, "o" + i, 10, 40 + i, 0.9000);
        }

        List<Long> walked = new ArrayList<>();
        TextSpanWindow cursor = window().page(pageId).limit(3);
        while (true) {
            List<TextSpanRow> batch = cursor.fetch(spans);
            if (batch.isEmpty()) {
                break;
            }
            assertThat(batch.size()).isLessThanOrEqualTo(3);
            batch.forEach(row -> walked.add(row.span().getId()));
            cursor = cursor.after(batch.get(batch.size() - 1));
        }

        List<Long> straight = window().page(pageId).limit(1000).fetch(spans).stream()
                .map(row -> row.span().getId())
                .toList();
        assertThat(walked).as("no duplicate, no gap, same order").isEqualTo(straight);
        assertThat(walked).hasSize(13);
        assertThat(new HashSet<>(walked)).hasSize(13);
    }

    /**
     * The boundary the whole design turns on: a cursor parked on the LAST NATIVE span must still
     * yield the entire OCR block, and a cursor parked on the last OCR span must yield nothing.
     */
    @Test
    void a_cursor_in_the_native_block_still_reaches_the_whole_ocr_block() {
        insertSpan(SpanSource.NATIVE, 0, "n0", 10, 10, 1.0000);
        insertSpan(SpanSource.NATIVE, 1, "n1", 10, 20, 1.0000);
        insertSpan(SpanSource.OCR, 0, "o0", 10, 30, 0.9000);
        insertSpan(SpanSource.OCR, 1, "o1", 10, 40, 0.9000);

        List<TextSpanRow> all = window().page(pageId).limit(100).fetch(spans);
        TextSpanRow lastNative = all.get(1);
        TextSpanRow lastOcr = all.get(3);

        assertThat(window().page(pageId).limit(100).after(lastNative).fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("o0", "o1");
        assertThat(window().page(pageId).limit(100).after(lastOcr).fetch(spans))
                .as("the end of the OCR block is the end of the page")
                .isEmpty();
    }

    // ── filters ─────────────────────────────────────────────────────────────

    @Test
    void the_box_predicate_selects_intersecting_spans_only() {
        insertSpan(SpanSource.NATIVE, 0, "inside", 100, 100, 1.0000);
        insertSpan(SpanSource.NATIVE, 1, "straddles-left-edge", 45, 100, 1.0000);
        insertSpan(SpanSource.NATIVE, 2, "far-below", 100, 400, 1.0000);
        insertSpan(SpanSource.NATIVE, 3, "far-right", 500, 100, 1.0000);

        assertThat(
                        window().page(pageId).limit(100)
                                .box(
                                        new BigDecimal("50"),
                                        new BigDecimal("90"),
                                        new BigDecimal("200"),
                                        new BigDecimal("60"))
                                .fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("inside", "straddles-left-edge");
    }

    @Test
    void the_source_and_confidence_filters_and_together() {
        insertSpan(SpanSource.NATIVE, 0, "n", 10, 10, 1.0000);
        insertSpan(SpanSource.OCR, 0, "low", 10, 20, 0.4000);
        insertSpan(SpanSource.OCR, 1, "high", 10, 30, 0.9500);

        assertThat(window().page(pageId).limit(100).source(SpanSource.OCR).fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("low", "high");
        assertThat(
                        window().page(pageId).limit(100)
                                .source(SpanSource.OCR)
                                .minConfidence(new BigDecimal("0.9000"))
                                .fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("high");
        assertThat(window().page(pageId).limit(100).source(SpanSource.NATIVE).fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("n");
    }

    /**
     * The published contract is "spans AT or above" the threshold, and the boundary is where that
     * sentence can quietly become "above". No other fixture puts a span ON the threshold, so
     * {@code >=} could regress to {@code >} with the suite green (verified by mutation). The
     * consequence is silent: a scanned page whose OCR spans all scored exactly the requested value
     * answers {@code spans: [], returned: 0, truncated: false} — byte-indistinguishable from "this
     * page has no OCR layer", with several hundred words lost and no signal.
     */
    @Test
    void a_span_sitting_exactly_on_the_confidence_threshold_is_included() {
        insertSpan(SpanSource.OCR, 0, "just-below", 10, 10, 0.9099);
        insertSpan(SpanSource.OCR, 1, "exactly-on", 10, 20, 0.9100);
        insertSpan(SpanSource.OCR, 2, "just-above", 10, 30, 0.9101);

        assertThat(
                        window().page(pageId).limit(100)
                                .minConfidence(new BigDecimal("0.9100"))
                                .fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("exactly-on", "just-above");
    }

    /**
     * The unfiltered default is {@code BigDecimal.ZERO}, and it must be inclusive too: a recogniser
     * that scored a word {@code 0.0000} still captured that word, and an unfiltered window that drops
     * it is a silently incomplete record.
     */
    @Test
    void a_zero_confidence_span_is_still_in_the_unfiltered_window() {
        insertSpan(SpanSource.OCR, 0, "scored-nothing", 10, 10, 0.0000);

        assertThat(window().page(pageId).limit(100).fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("scored-nothing");
    }

    // ── the element resolver ────────────────────────────────────────────────

    /**
     * An element's member spans come back in {@code layout_element_span.ordinal} order, which is
     * the element's OWN order and need not agree with page reading order. The link ordinals here
     * are deliberately the reverse of the span ordinals: if the query silently fell back to the
     * page order this assertion inverts.
     */
    @Test
    void element_member_spans_come_back_in_link_ordinal_order_not_page_order() {
        long first = insertSpan(SpanSource.NATIVE, 0, "printed-first", 10, 10, 1.0000);
        long second = insertSpan(SpanSource.NATIVE, 1, "printed-second", 60, 10, 1.0000);
        long third = insertSpan(SpanSource.NATIVE, 2, "not-a-member", 10, 40, 1.0000);
        UUID element = insertElement();
        linkSpan(element, second, 0);
        linkSpan(element, first, 1);

        List<TextSpanRow> members = window().page(pageId).limit(100).element(element).fetch(spans);

        assertThat(members).extracting(row -> row.span().getText())
                .containsExactly("printed-second", "printed-first");
        assertThat(members).extracting(row -> row.span().getId()).doesNotContain(third);
    }

    @Test
    void an_element_window_cursors_over_its_own_order() {
        long a = insertSpan(SpanSource.NATIVE, 0, "a", 10, 10, 1.0000);
        long b = insertSpan(SpanSource.NATIVE, 1, "b", 60, 10, 1.0000);
        long c = insertSpan(SpanSource.NATIVE, 2, "c", 110, 10, 1.0000);
        UUID element = insertElement();
        linkSpan(element, c, 0);
        linkSpan(element, b, 1);
        linkSpan(element, a, 2);

        TextSpanWindow window = window().page(pageId).limit(2).element(element);
        List<TextSpanRow> firstBatch = window.fetch(spans);
        assertThat(firstBatch).extracting(row -> row.span().getText()).containsExactly("c", "b");
        assertThat(window.after(firstBatch.get(1)).fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("a");
    }

    /**
     * The element resolver is scoped to the page in the PATH, not merely to the element id.
     *
     * <p>Every other element test builds its element on the page it queries, which leaves
     * {@code and s.pageId = :pageId} dead: verified by mutation, replacing it with a tautology left
     * the whole suite green before this test existed. The consequence of that predicate regressing is
     * a wrong record rather than an error — {@code GET /v1/pages/{A}/spans?element=<element on B>}
     * would answer 200 with page B's spans inside page A's envelope: A's {@code pageId}, A's
     * {@code widthPt}/{@code heightPt}/{@code rotation}, A's {@code contentHash} and A's ETag. A
     * caller drawing highlights would render B's boxes in A's coordinate frame, and this is the D3
     * walk's only downward leg, so it is exactly the call a consumer following
     * {@code evidence[].layoutElementId} makes.
     */
    @Test
    void an_element_on_another_page_resolves_to_nothing_inside_this_pages_window() {
        long ours = insertSpan(SpanSource.NATIVE, 0, "on-the-page-we-asked-for", 10, 10, 1.0000);
        UUID otherPage = seedPage();
        long theirs = insertSpanOn(otherPage, SpanSource.NATIVE, 0, "on-a-different-page", 10, 10);
        UUID elementOnOtherPage = insertElementOn(otherPage);
        linkSpan(elementOnOtherPage, theirs, 0);

        // Sanity: the element really does resolve — on ITS OWN page.
        assertThat(window().page(otherPage).limit(100).element(elementOnOtherPage).fetch(spans))
                .extracting(row -> row.span().getId())
                .containsExactly(theirs);

        assertThat(window().page(pageId).limit(100).element(elementOnOtherPage).fetch(spans))
                .as("an element id is not a licence to read another page's spans")
                .isEmpty();
        assertThat(window().page(pageId).limit(100).fetch(spans))
                .extracting(row -> row.span().getId())
                .containsExactly(ours);
    }

    @Test
    void a_foreign_orgs_spans_are_never_in_the_window() {
        insertSpan(SpanSource.NATIVE, 0, "ours", 10, 10, 1.0000);
        jdbc.update(
                "INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width, height,"
                        + " source, confidence) VALUES (?, ?, 1, 'theirs', 10, 20, 20, 10,"
                        + " 'NATIVE', 1.0)",
                ORG_OTHER,
                pageId);

        assertThat(window().page(pageId).limit(100).fetch(spans))
                .extracting(row -> row.span().getText())
                .containsExactly("ours");
    }

    // ── fixture plumbing ────────────────────────────────────────────────────

    private TextSpanWindow window() {
        return TextSpanWindow.forOrg(ORG_DEV);
    }

    private UUID seedPage() {
        UUID packageId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID page = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'cursor-it')",
                packageId,
                ORG_DEV);
        jdbc.update(
                "INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,"
                        + " content_type, size_bytes, sha256, storage_key_original) VALUES"
                        + " (?, ?, ?, 0, 'f.pdf', 'application/pdf', 10, ?, 'k')",
                fileId,
                ORG_DEV,
                packageId,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        jdbc.update(
                "INSERT INTO page (id, org_id, source_file_id, package_id, page_index,"
                        + " package_page_index, width_pt, height_pt) VALUES (?, ?, ?, ?, 0, 0,"
                        + " 612.00, 792.00)",
                page,
                ORG_DEV,
                fileId,
                packageId);
        return page;
    }

    private long insertSpan(SpanSource source, int ordinal, String text, double x, double y, double confidence) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width,"
                                + " height, source, confidence) VALUES (?, ?, ?, ?, ?, ?, 20, 10,"
                                + " ?, ?) RETURNING id",
                        Long.class,
                        ORG_DEV,
                        pageId,
                        ordinal,
                        text,
                        BigDecimal.valueOf(x),
                        BigDecimal.valueOf(y),
                        source.name(),
                        BigDecimal.valueOf(confidence));
        return id;
    }

    private long insertSpanOn(UUID page, SpanSource source, int ordinal, String text, double x, double y) {
        Long id =
                jdbc.queryForObject(
                        "INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width,"
                                + " height, source, confidence) VALUES (?, ?, ?, ?, ?, ?, 20, 10,"
                                + " ?, 1.0) RETURNING id",
                        Long.class,
                        ORG_DEV,
                        page,
                        ordinal,
                        text,
                        BigDecimal.valueOf(x),
                        BigDecimal.valueOf(y),
                        source.name());
        return id;
    }

    private UUID insertElement() {
        return insertElementOn(pageId);
    }

    private UUID insertElementOn(UUID page) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO layout_element (id, org_id, page_id, element_type, ordinal, x, y,"
                        + " width, height, confidence, detector, detector_version) VALUES"
                        + " (?, ?, ?, 'TABLE_CELL', 0, 10, 10, 100, 20, 0.75, 'it', 'it')",
                id,
                ORG_DEV,
                page);
        return id;
    }

    private void linkSpan(UUID element, long spanId, int ordinal) {
        jdbc.update(
                "INSERT INTO layout_element_span (layout_element_id, text_span_id, org_id, ordinal)"
                        + " VALUES (?, ?, ?, ?)",
                element,
                spanId,
                ORG_DEV,
                ordinal);
    }
}
