package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The LABEL_ABOVE rung: a caption sits BENEATH its value — the tile layout a bank's online
 * activity print-out states its balances in. Geometry here is measured from the real five-page
 * checking print-out V38/V39 were written for (corpus, never copied into this repo; amounts
 * invented, boxes real): three tiles across one row, each amount 12.6–13.5 pt tall with its
 * bottom edge 3.7 pt above its caption's top edge, tiles starting at x 45.4 / 177.2 / 308.6.
 * On that document the V39 ANCHOR_LABEL rung looked RIGHT along the caption row, found the next
 * tile's caption, and left the one field the genre states MISSING.
 *
 * <p>The decoy tests are MANDATORY (design D5): the next tile's amount begins 0.4 pt inside this
 * tile's window, and a rung that took it would report a confident wrong balance with a plausible
 * evidence box — strictly worse than the missing field. Never delete or loosen them.
 */
class LabelAboveExtractionTest {

    /** The CONTRACT's money pattern, verbatim. */
    private static final String MONEY =
            "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                BigDecimal.ONE);
    }

    private static PageContent page(SpanRef... spans) {
        return new PageContent(UUID.randomUUID(), 0, List.of(spans), List.of());
    }

    private static FieldSpec field(String name, ExtractorSpec... rungs) {
        return new FieldSpec(name, DataType.MONEY, true, "money", false, List.of(rungs));
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("BANK_STATEMENT", "1.5.0", List.of(fields));
    }

    /** A LABEL_ABOVE rung with the CONTRACT's defaults (maxRisePt 24.0, cellOverlap 0.5). */
    private static ExtractorSpec above(double strength, String label, String pattern) {
        return above(strength, label, pattern, 24.0, 0.5);
    }

    private static ExtractorSpec above(
            double strength, String label, String pattern, double maxRisePt, double cellOverlap) {
        return new ExtractorSpec(
                ExtractionMethod.LABEL_ABOVE,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE),
                null,
                null,
                null,
                null,
                cellOverlap,
                null,
                maxRisePt);
    }

    /** V39's rung, verbatim: the amount to the RIGHT of the caption on its own line. */
    private static ExtractorSpec lineRight(double strength, String label, String pattern) {
        return new ExtractorSpec(
                ExtractionMethod.ANCHOR_LABEL,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE_RIGHT));
    }

    private static FieldOutcome only(List<FieldOutcome> outcomes) {
        assertThat(outcomes).hasSize(1);
        return outcomes.get(0);
    }

    // The measured geometry every test below uses (canonical space, y downward).
    //   caption row, top 202.1:  "Present balance" x 45.4-127.7 · the next tile's caption
    //                            from x 177.6 · the third tile's from x 307.8
    //   amount row,  top 185.4-185.8: this tile's amount x 45.4-103.3 (bottom 198.4, so its
    //                            RISE above the caption is 3.7 pt) · the next tile's amount
    //                            x 177.2-242.7 · the third's x 308.6-378.5
    // The cell window for "Present balance" therefore runs x 45.4-177.6, and the next tile's
    // amount overlaps it by 0.4 pt of its own 65.5 — the decoy the ownership test refuses.

    private static SpanRef presentCaption1() {
        return span(16, "Present", "45.4", "202.1", "38.4", "12.2");
    }

    private static SpanRef presentCaption2() {
        return span(17, "balance", "89.3", "202.1", "38.4", "12.2");
    }

    private static SpanRef[] neighbouringTiles() {
        return new SpanRef[] {
            span(18, "Available", "177.6", "202.1", "43.5", "13.5"),
            span(19, "balance", "226.5", "202.1", "21.7", "13.5"),
            span(21, "Pending", "307.8", "200.9", "59.9", "14.3"),
            span(22, "debits", "373.2", "200.9", "21.8", "14.3"),
            span(13, "+$2,180.40", "177.2", "185.4", "65.5", "13.5"),
            span(14, "-$12,450.00", "308.6", "185.4", "69.9", "13.5"),
        };
    }

    private static SpanRef thisTilesAmount() {
        return span(12, "$4,812.33", "45.4", "185.8", "57.9", "12.6");
    }

    private static PageContent tilesPage(SpanRef... extra) {
        SpanRef[] neighbours = neighbouringTiles();
        SpanRef[] spans = new SpanRef[3 + neighbours.length + extra.length];
        spans[0] = presentCaption1();
        spans[1] = presentCaption2();
        spans[2] = thisTilesAmount();
        System.arraycopy(neighbours, 0, spans, 3, neighbours.length);
        System.arraycopy(extra, 0, spans, 3 + neighbours.length, extra.length);
        return page(spans);
    }

    // ── the rung finds the amount above its caption ──────────────────────────

    @Test
    void the_amount_directly_above_the_caption_is_found_in_its_tile() {
        PageContent page = tilesPage();
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.LABEL_ABOVE);
        assertThat(outcome.anchorStrength()).isEqualTo(0.8);
        assertThat(outcome.pageId()).isEqualTo(page.pageId());
        assertThat(outcome.displayedText()).isEqualTo("$4,812.33");
        assertThat(outcome.normalized().number()).isEqualByComparingTo(new BigDecimal("4812.33"));
        assertThat(outcome.valueEvidence())
                .as("the amount is VALUE evidence; neither neighbour's amount is")
                .extracting(EvidenceRef::spanId)
                .containsExactly(12L);
        assertThat(outcome.labelEvidence())
                .as("both caption words are LABEL evidence")
                .extracting(EvidenceRef::spanId)
                .containsExactly(16L, 17L);
    }

    // ── the horizontal bound: the neighbouring tile ──────────────────────────

    @Test
    void the_neighbouring_tiles_amount_is_never_taken_when_this_tile_is_empty() {
        // The decoy. This tile has no amount; the next tile's "+$2,180.40" begins 0.4 pt
        // inside this tile's window and 65 pt outside it. Ownership refuses it and the field
        // goes MISSING — never a confident wrong balance from the tile next door.
        SpanRef[] neighbours = neighbouringTiles();
        SpanRef[] spans = new SpanRef[2 + neighbours.length];
        spans[0] = presentCaption1();
        spans[1] = presentCaption2();
        System.arraycopy(neighbours, 0, spans, 2, neighbours.length);
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page(spans))));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void an_amount_that_overlaps_the_tile_by_less_than_cellOverlap_is_not_taken() {
        // Shifted right so 27.6 of its 57.9 pt lie inside the window (0.48 < 0.5): a run
        // mostly in the neighbouring tile belongs to that tile.
        PageContent page =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(18, "Available", "177.6", "202.1", "43.5", "13.5"),
                        span(12, "$4,812.33", "150.0", "185.8", "57.9", "12.6"));
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    // ── the direction: this rung reads UP, never right and never down ────────

    @Test
    void a_caption_then_amount_on_one_line_is_not_read_by_this_rung() {
        // V39's layout guess, and ANCHOR_LABEL LINE_RIGHT's job. The amount shares the
        // caption's row, so its bottom edge is below the caption's top: not this tile.
        PageContent page =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(12, "$4,812.33", "160.4", "202.1", "48.9", "12.2"));
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void an_amount_below_the_caption_is_LABEL_BELOWs_and_not_read_here() {
        PageContent page =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(12, "$4,812.33", "45.4", "218.0", "57.9", "12.6"));
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    // ── the vertical bound ───────────────────────────────────────────────────

    @Test
    void an_amount_beyond_maxRisePt_is_not_taken() {
        // Bottom edge 30.0 pt above the caption's top, past the 24.0 default.
        PageContent page =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(12, "$4,812.33", "45.4", "159.5", "57.9", "12.6"));
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_wider_maxRisePt_authored_in_the_schema_reaches_it() {
        PageContent page =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(12, "$4,812.33", "45.4", "159.5", "57.9", "12.6"));
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY, 40.0, 0.5));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("$4,812.33");
    }

    // ── which line owns the tile ─────────────────────────────────────────────

    @Test
    void the_line_nearest_the_caption_owns_the_tile() {
        // Two admitted rows: an account caption 19.9 pt above the tile's caption and the
        // amount 3.7 pt above it. The NEAREST line owns the tile and the amount is read.
        PageContent page =
                tilesPage(span(9, "CHECKING", "45.4", "170.0", "60.0", "12.2"));
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("$4,812.33");
        assertThat(outcome.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(12L);
    }

    @Test
    void a_shape_mismatch_on_the_owning_line_fails_the_rung_never_a_higher_lines_amount() {
        // The nearest line carries words, not an amount; an amount sits one row higher,
        // still inside maxRisePt. Skipping up to it is how a shape mismatch becomes a
        // confident wrong value from a different printed fact: the rung fails instead.
        PageContent page =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(10, "as", "45.4", "185.8", "10.0", "12.6"),
                        span(11, "of", "59.0", "185.8", "10.0", "12.6"),
                        span(12, "today", "72.6", "185.8", "28.0", "12.6"),
                        span(9, "$9,999.99", "45.4", "170.0", "57.9", "12.2"));
        FieldSpec ending = field("endingBalance", above(0.8, "Present balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    // ── the ladder ───────────────────────────────────────────────────────────

    @Test
    void a_caption_that_is_not_on_the_page_fails_the_rung_and_the_field_goes_missing() {
        FieldSpec ending = field("endingBalance", above(0.8, "Current balance", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(tilesPage())));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.labelEvidence()).isEmpty();
    }

    @Test
    void the_shipped_ladder_order_reads_both_layouts() {
        // bank_statement@1.5.0 keeps V39's LINE_RIGHT rung FIRST and appends LABEL_ABOVE:
        // a print-out that writes the amount beside its caption still answers from the
        // earlier rung, and the tile layout falls through to the new one.
        FieldSpec ending =
                field(
                        "endingBalance",
                        lineRight(0.8, "Present balance", MONEY),
                        above(0.8, "Present balance", MONEY));
        PageContent beside =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(12, "$4,812.33", "160.4", "202.1", "48.9", "12.2"));

        FieldOutcome fromBeside = only(engine.extract(schema(ending), List.of(beside)));
        FieldOutcome fromTiles = only(engine.extract(schema(ending), List.of(tilesPage())));

        assertThat(fromBeside.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(fromBeside.displayedText()).isEqualTo("$4,812.33");
        assertThat(fromTiles.method()).isEqualTo(ExtractionMethod.LABEL_ABOVE);
        assertThat(fromTiles.displayedText()).isEqualTo("$4,812.33");
    }

    @Test
    void a_failed_label_above_rung_lets_a_later_anchor_label_rung_win() {
        FieldSpec ending =
                field(
                        "endingBalance",
                        above(0.8, "Present balance", MONEY),
                        lineRight(0.7, "Present balance", MONEY));
        PageContent beside =
                page(
                        presentCaption1(),
                        presentCaption2(),
                        span(12, "$4,812.33", "160.4", "202.1", "48.9", "12.2"));

        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(beside)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcome.anchorStrength()).isEqualTo(0.7);
    }
}
