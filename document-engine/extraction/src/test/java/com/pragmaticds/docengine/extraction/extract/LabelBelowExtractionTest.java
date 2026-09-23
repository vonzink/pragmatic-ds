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
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The LABEL_BELOW rung: a label captions a cell and the value sits on the next line INSIDE that
 * cell. Geometry here is measured from a real filled IRS W-2 (corpus, never copied into this
 * repo): captions 24 pt apart, each value's top 12.5 pt below its caption's top, three columns
 * whose cells start at x 36 / 330 / 453.
 *
 * <p>The two decoy tests are MANDATORY (design D5). On a W-2 box 3 sits directly beneath box 1,
 * so a rung that grabs the wrong cell reports a <b>confident wrong value with a plausible
 * evidence box</b> — strictly worse than the missing fields the spec exists to fix, and the
 * same failure class as the unanchored-regex trap from Phase 5. Never delete or loosen them.
 */
class LabelBelowExtractionTest {

    /** The CONTRACT's money pattern, verbatim. */
    private static final String MONEY =
            "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return span(id, text, x, y, w, h, "1");
    }

    private static SpanRef span(
            long id, String text, String x, String y, String w, String h, String confidence) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                new BigDecimal(confidence));
    }

    private static PageContent page(SpanRef... spans) {
        return new PageContent(UUID.randomUUID(), 0, List.of(spans), List.of());
    }

    private static FieldSpec field(String name, String normalizer, ExtractorSpec... rungs) {
        return new FieldSpec(name, DataType.MONEY, true, normalizer, false, List.of(rungs));
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("W2", "1.1.0", List.of(fields));
    }

    /** A LABEL_BELOW rung with the CONTRACT's defaults (maxDropPt 24.0, cellOverlap 0.5). */
    private static ExtractorSpec below(double strength, String label, String pattern) {
        return below(strength, label, pattern, 24.0, 0.5);
    }

    private static ExtractorSpec below(
            double strength, String label, String pattern, double maxDropPt, double cellOverlap) {
        return new ExtractorSpec(
                ExtractionMethod.LABEL_BELOW,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE),
                null,
                null,
                null,
                maxDropPt,
                cellOverlap);
    }

    /** The V45 person-name shape: two name words at least, initials between, ALL CAPS admitted. */
    private static final String NAME =
            "(?<![A-Za-z'\\-])(?:[A-Z][A-Za-z'\\-]+|[A-Z]{2,})"
                    + "(?:(?: [A-Z]\\.?)|(?: (?:[A-Z][A-Za-z'\\-]+|[A-Z]{2,})))*"
                    + " (?:[A-Z][A-Za-z'\\-]+|[A-Z]{2,})(?![A-Za-z'\\-])";

    private static ExtractorSpec belowJoining(
            double strength,
            String label,
            String pattern,
            double maxDropPt,
            double cellOverlap,
            String... joinLabels) {
        return new ExtractorSpec(
                ExtractionMethod.LABEL_BELOW,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE),
                null,
                null,
                null,
                maxDropPt,
                cellOverlap,
                null,
                null,
                java.util.Arrays.stream(joinLabels)
                        .map(text -> new LabelSpec(AnchorKind.LITERAL, text))
                        .toList());
    }

    private static FieldSpec nameField(String name, ExtractorSpec... rungs) {
        return new FieldSpec(name, DataType.STRING, true, "personName", false, List.of(rungs));
    }

    /**
     * The W-2's box e as the IRS prints it (2026-09-14, measured on a filled official form and
     * redrawn with invented values): the box letter, the caption, the "Last name" and "Suff."
     * captions on ONE row; the first name and initial under the first caption, the surname
     * under "Last name", nothing under "Suff.". The first-name value starts 3 pt LEFT of the box
     * letter, exactly as printed, so the cell's left edge is the box letter's — not the
     * caption text's — and the join caption is the next box on the same row.
     */
    private static List<SpanRef> boxERow(SpanRef... values) {
        List<SpanRef> spans = new java.util.ArrayList<>();
        spans.add(span(1, "e", "42.0", "182.5", "4.0", "7.0"));
        spans.add(span(2, "Employee's", "50.0", "182.5", "36.0", "7.0"));
        spans.add(span(3, "first", "88.0", "182.5", "12.0", "7.0"));
        spans.add(span(4, "name", "102.0", "182.5", "17.0", "7.0"));
        spans.add(span(5, "and", "121.0", "182.5", "12.0", "7.0"));
        spans.add(span(6, "initial", "135.0", "182.5", "16.0", "7.0"));
        spans.add(span(7, "Last", "177.0", "182.5", "13.0", "7.0"));
        spans.add(span(8, "name", "192.0", "182.5", "17.0", "7.0"));
        spans.add(span(9, "Suff.", "313.0", "182.5", "14.0", "7.0"));
        spans.addAll(List.of(values));
        return spans;
    }

    @Test
    void a_name_split_across_the_first_name_and_last_name_cells_is_read_as_one_value() {
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        boxERow(
                                span(21, "Jordan", "39.0", "194.0", "24.0", "8.0"),
                                span(22, "Q.", "66.0", "194.0", "8.0", "8.0"),
                                span(23, "Fixture", "175.0", "194.0", "28.0", "8.0")),
                        List.of());
        FieldSpec employeeName =
                nameField(
                        "employeeName",
                        belowJoining(
                                0.9,
                                "e Employee's first name and initial",
                                NAME,
                                24.0,
                                0.5,
                                "Last name"));

        FieldOutcome outcome = only(engine.extract(schema(employeeName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.LABEL_BELOW);
        assertThat(outcome.displayedText()).isEqualTo("Jordan Q. Fixture");
        assertThat(outcome.normalized().text()).isEqualTo("Jordan Q. Fixture");
        assertThat(outcome.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .as("both cells' words are the value's evidence")
                .containsExactly(21L, 22L, 23L);
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .as("the joined caption is cited beside the anchoring one")
                .contains(1L, 2L, 6L, 7L, 8L);
    }

    @Test
    void a_first_name_cell_whose_joined_last_name_cell_is_empty_stays_missing() {
        // The composed text is "Jordan Q." — one name word — and the two-word pattern refuses
        // it. Missing over a confident partial (design D5), exactly as the un-joined rung.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        boxERow(
                                span(21, "Jordan", "39.0", "194.0", "24.0", "8.0"),
                                span(22, "Q.", "66.0", "194.0", "8.0", "8.0")),
                        List.of());
        FieldSpec employeeName =
                nameField(
                        "employeeName",
                        belowJoining(
                                0.9,
                                "e Employee's first name and initial",
                                NAME,
                                24.0,
                                0.5,
                                "Last name"));

        FieldOutcome outcome = only(engine.extract(schema(employeeName), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void a_joined_cell_holding_a_non_name_token_fails_the_composed_pattern_and_stays_missing() {
        // The join composes text; it does not vouch for it. A form whose "Last name" cell was
        // filled with an EIN-shaped string composes "Jordan Q. 12-3456789", the value pattern
        // matches the COMPOSED text whole, and no two-word name is in it — MISSING, never
        // "Jordan Q." as a confident partial and never the digits as a surname.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        boxERow(
                                span(21, "Jordan", "39.0", "194.0", "24.0", "8.0"),
                                span(22, "Q.", "66.0", "194.0", "8.0", "8.0"),
                                span(23, "12-3456789", "177.0", "194.0", "48.0", "8.0")),
                        List.of());
        FieldSpec employeeName =
                nameField(
                        "employeeName",
                        belowJoining(
                                0.9,
                                "e Employee's first name and initial",
                                NAME,
                                24.0,
                                0.5,
                                "Last name"));

        FieldOutcome outcome = only(engine.extract(schema(employeeName), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_whole_name_left_packed_in_the_first_cell_still_reads_when_the_joined_cell_is_empty() {
        // The V45 preparer-software layout: the surname does NOT sit under "Last name". The join
        // contributes nothing and the rung reads exactly what it read before.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        boxERow(
                                span(21, "MORGAN", "39.0", "194.0", "40.0", "8.0"),
                                span(22, "T", "83.0", "194.0", "6.0", "8.0"),
                                span(23, "FIXTURE", "93.0", "194.0", "44.0", "8.0")),
                        List.of());
        FieldSpec employeeName =
                nameField(
                        "employeeName",
                        belowJoining(
                                0.9,
                                "e Employee's first name and initial",
                                NAME,
                                24.0,
                                0.5,
                                "Last name"));

        FieldOutcome outcome = only(engine.extract(schema(employeeName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("MORGAN T FIXTURE");
    }

    @Test
    void a_join_caption_is_taken_from_the_labels_own_row_never_from_the_row_above() {
        // A Form 1040's two identity rows, both captioned "... first name ..." | "Last name".
        // The spouse rung's join must read the SPOUSE row's "Last name" cell: the taxpayer row's
        // identical caption comes first in reading order, and a page-global lookup would bind to
        // it, find the taxpayer's surname in reach of nothing on the spouse row, and compose
        // "Casey R." — which the pattern then refuses, losing the spouse for no reason.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        List.of(
                                span(1, "Your", "40.0", "84.0", "15.0", "8.0"),
                                span(2, "first", "57.0", "84.0", "12.0", "8.0"),
                                span(3, "name", "70.0", "84.0", "17.0", "8.0"),
                                span(4, "and", "90.0", "84.0", "12.0", "8.0"),
                                span(5, "middle", "103.0", "84.0", "21.0", "8.0"),
                                span(6, "initial", "126.0", "84.0", "16.0", "8.0"),
                                span(7, "Last", "242.0", "84.0", "13.0", "8.0"),
                                span(8, "name", "257.0", "84.0", "17.0", "8.0"),
                                span(9, "Your", "472.0", "84.0", "16.0", "8.0"),
                                span(10, "social", "490.0", "84.0", "20.0", "8.0"),
                                span(11, "Jordan", "38.0", "96.0", "19.0", "8.0"),
                                span(12, "Q.", "59.0", "96.0", "8.0", "8.0"),
                                span(13, "Smith", "241.0", "96.0", "22.0", "8.0"),
                                span(14, "If", "40.0", "108.0", "4.0", "8.0"),
                                span(15, "joint", "46.0", "108.0", "13.0", "8.0"),
                                span(16, "return,", "61.0", "108.0", "20.0", "8.0"),
                                span(17, "spouse's", "83.0", "108.0", "28.0", "8.0"),
                                span(18, "first", "114.0", "108.0", "12.0", "8.0"),
                                span(19, "name", "127.0", "108.0", "17.0", "8.0"),
                                span(20, "and", "146.0", "108.0", "12.0", "8.0"),
                                span(21, "middle", "160.0", "108.0", "21.0", "8.0"),
                                span(22, "initial", "183.0", "108.0", "16.0", "8.0"),
                                span(23, "Last", "242.0", "108.0", "13.0", "8.0"),
                                span(24, "name", "257.0", "108.0", "17.0", "8.0"),
                                span(25, "Spouse's", "472.0", "108.0", "30.0", "8.0"),
                                span(26, "social", "504.0", "108.0", "19.0", "8.0"),
                                span(27, "Casey", "38.0", "120.0", "18.0", "8.0"),
                                span(28, "R.", "58.0", "120.0", "9.0", "8.0"),
                                span(29, "Fixture", "241.0", "120.0", "26.0", "8.0")),
                        List.of());
        FieldSpec spouseName =
                nameField(
                        "spouseName",
                        belowJoining(
                                0.9,
                                "If joint return, spouse's first name and middle initial",
                                NAME,
                                12.0,
                                0.5,
                                "Last name"));

        FieldOutcome outcome = only(engine.extract(schema(spouseName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("Casey R. Fixture");
        assertThat(outcome.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(27L, 28L, 29L);
    }

    private static FieldOutcome only(List<FieldOutcome> outcomes) {
        assertThat(outcomes).hasSize(1);
        return outcomes.get(0);
    }

    // The geometry every test below uses, measured from the real form.
    //   box 1's caption "1 Wages, tips, other compensation": top 111.0, height 6.5,
    //     so its BOTTOM edge is 117.5; the anchor phrase itself spans x 342.9-448.0.
    //   its own value row:            top 123.5  → drop  6.0  (admitted)
    //   box 3's caption, one row down: top 135.0  → drop 17.5  (admitted, no amount in it)
    //   box 3's value:                top 147.5  → drop 30.0  (EXCLUDED by maxDropPt 24)
    // Every test spells its spans out in full deliberately: the geometry is the
    // subject under test, and it should be readable without scrolling to a helper.

    // ── the rung finds the value on the line below ───────────────────────────

    @Test
    void the_value_one_line_below_the_label_is_found_in_its_cell() {
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(22, "61,538.72", "333.0", "123.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.LABEL_BELOW);
        assertThat(outcome.anchorStrength()).isEqualTo(0.9);
        assertThat(outcome.pageId()).isEqualTo(page.pageId());
        assertThat(outcome.displayedText()).isEqualTo("61,538.72");
        assertThat(outcome.normalized().number()).isEqualByComparingTo(new BigDecimal("61538.72"));
    }

    @Test
    void a_flat_line_right_layout_is_not_read_by_this_rung() {
        // The rung reads DOWN, never right: a value on the label's OWN line is not its cell.
        // (That layout is ANCHOR_LABEL's job, and the ladder keeps it as a fallback.)
        PageContent page =
                page(
                        span(11, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(12, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(13, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(14, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(15, "61,538.72", "455.0", "111.0", "35.6", "6.5"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    // ── the vertical bound ───────────────────────────────────────────────────

    @Test
    void a_value_beyond_maxDropPt_is_not_taken() {
        // 147.5 is where the NEXT row's value sits: 30.0 pt below the label's bottom edge,
        // past the 24.0 default. Nothing in this cell matches, so the rung must fail.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(43, "62,538.72", "333.0", "147.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    // ── the horizontal bound (the "same cell" test) ──────────────────────────

    @Test
    void a_value_below_but_in_another_column_is_not_taken() {
        // The left column's cell (x 39-81) shares no x with the middle column's label
        // (x 342.9-448.0): a value directly below in a DIFFERENT cell is not this label's.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(21, "98-7654321", "39.0", "123.5", "42.7", "7.4"),
                        span(23, "9,730.44", "456.0", "123.5", "31.1", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.found())
                .as("box 2's amount is on the same row but in the next cell")
                .isFalse();
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_value_that_overlaps_the_cell_by_less_than_cellOverlap_is_not_taken() {
        // The sharper half of the same bound: 8 pt of shared x out of a 35.6 pt value box is
        // 0.22 of the narrower box — under the 0.5 default, so it is a neighbouring cell's
        // value bleeding leftward, not this cell's.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(22, "61,538.72", "315.3", "123.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    // ── D2: the rung never consults layout structure ─────────────────────────

    @Test
    void the_outcome_is_identical_with_and_without_layout_structure() {
        // Design D2: LABEL_BELOW deliberately does NOT depend on ruling detection. Many lender
        // forms draw their boxes with no lines at all, and the worker skips rulings on rotated
        // pages, so a rung that leaned on them would fail exactly where scans are worst. A
        // genuinely ruled grid reaches extraction as PageContent.tables — TABLE → TABLE_ROW →
        // TABLE_CELL — and this rung must produce the SAME outcome whether that tree is there
        // or empty. D2 is implemented by simply never touching page.tables(); untested, the
        // invariant would be lost the first time someone "optimised" the rung by reading the
        // cell straight out of the table tree, and the failure would only show up on the
        // unruled lender forms this rung exists for.
        UUID pageId = UUID.randomUUID();
        List<SpanRef> spans =
                List.of(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(22, "61,538.72", "333.0", "123.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        PageContent unruled = new PageContent(pageId, 0, spans, List.of());
        PageContent ruled = new PageContent(pageId, 0, spans, List.of(tableOver(spans)));

        FieldOutcome withoutStructure = only(engine.extract(schema(wages), List.of(unruled)));
        FieldOutcome withStructure = only(engine.extract(schema(wages), List.of(ruled)));

        assertThat(withoutStructure.found())
                .as("the rung reads the cell from geometry alone, with no grid to help it")
                .isTrue();
        assertThat(withStructure)
                .as("a detected grid changes NOTHING — the rung never consults it (D2)")
                .isEqualTo(withoutStructure);
    }

    /**
     * A TABLE → TABLE_ROW → TABLE_CELL tree over the same spans: what a genuinely ruled page
     * projects into {@code PageContent.tables}. {@code TABLE_CLUSTER} navigates it;
     * {@code LABEL_BELOW} must ignore it entirely.
     */
    private static LayoutNode tableOver(List<SpanRef> spans) {
        Box cellBox =
                new Box(
                        new BigDecimal("330.0"),
                        new BigDecimal("108.0"),
                        new BigDecimal("123.0"),
                        new BigDecimal("24.0"));
        LayoutNode cell =
                new LayoutNode(
                        UUID.nameUUIDFromBytes("box-1-cell".getBytes()),
                        LayoutElementType.TABLE_CELL,
                        cellBox,
                        0,
                        0,
                        spans,
                        List.of());
        LayoutNode row =
                new LayoutNode(
                        UUID.nameUUIDFromBytes("box-1-row".getBytes()),
                        LayoutElementType.TABLE_ROW,
                        cellBox,
                        0,
                        null,
                        List.of(),
                        List.of(cell));
        return new LayoutNode(
                UUID.nameUUIDFromBytes("box-grid-table".getBytes()),
                LayoutElementType.TABLE,
                cellBox,
                null,
                null,
                List.of(),
                List.of(row));
    }

    // ── THE DECOY TESTS (design D5 — mandatory) ──────────────────────────────

    @Test
    void the_decoy_amount_one_row_further_down_the_same_column_is_not_taken() {
        // The real W-2 arrangement: box 3 sits directly beneath box 1, same column, and its
        // amount is just as plausible a "wages" figure. The rung must return box 1's.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(22, "61,538.72", "333.0", "123.5", "35.6", "7.4"),
                        span(31, "3", "336.0", "135.0", "3.9", "6.5"),
                        span(32, "Social", "342.9", "135.0", "19.1", "6.5"),
                        span(33, "security", "365.0", "135.0", "24.1", "6.5"),
                        span(34, "wages", "392.1", "135.0", "20.2", "6.5"),
                        span(43, "62,538.72", "333.0", "147.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("61,538.72");
        assertThat(outcome.valueEvidence())
                .as("the evidence must point at box 1's amount, never box 3's")
                .extracting(EvidenceRef::spanId)
                .containsExactly(22L);
    }

    @Test
    void an_empty_cell_is_a_missing_field_and_never_the_next_rows_amount() {
        // The strict half, and the whole point of the bound: box 1 was left blank. A rung that
        // reaches past its own cell would report box 3's 62,538.72 as WAGES — confident, wrong,
        // and with an evidence box a reviewer would nod at. The field must go MISSING instead.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(31, "3", "336.0", "135.0", "3.9", "6.5"),
                        span(32, "Social", "342.9", "135.0", "19.1", "6.5"),
                        span(33, "security", "365.0", "135.0", "24.1", "6.5"),
                        span(34, "wages", "392.1", "135.0", "20.2", "6.5"),
                        span(43, "62,538.72", "333.0", "147.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome).isEqualTo(FieldOutcome.missing(wages));
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
        assertThat(outcome.confidence()).isEqualTo(ConfidenceBreakdown.ZERO);
    }

    // ── no label ⇒ the rung fails and the ladder decides ─────────────────────

    @Test
    void a_label_that_is_not_on_the_page_fails_the_rung_and_the_field_goes_missing() {
        PageContent page =
                page(
                        span(31, "3", "336.0", "135.0", "3.9", "6.5"),
                        span(32, "Social", "342.9", "135.0", "19.1", "6.5"),
                        span(33, "security", "365.0", "135.0", "24.1", "6.5"),
                        span(34, "wages", "392.1", "135.0", "20.2", "6.5"),
                        span(43, "62,538.72", "333.0", "147.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome).isEqualTo(FieldOutcome.missing(wages));
        assertThat(outcome.labelEvidence()).isEmpty();
    }

    @Test
    void a_failed_label_below_rung_lets_a_later_anchor_label_rung_win() {
        // The ladder the W-2 schema will carry: box grid first, flat layout as fallback.
        PageContent page =
                page(
                        span(1, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(2, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(3, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(4, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(5, "61,538.72", "455.0", "111.0", "35.6", "6.5"));
        ExtractorSpec fallback =
                new ExtractorSpec(
                        ExtractionMethod.ANCHOR_LABEL,
                        0.7,
                        new LabelSpec(AnchorKind.LITERAL, "Wages, tips, other compensation"),
                        null,
                        new ValueSpec(MONEY, 0, ValueScope.LINE_RIGHT));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY),
                        fallback);

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcome.anchorStrength()).isEqualTo(0.7);
        assertThat(outcome.displayedText()).isEqualTo("61,538.72");
    }

    // ── confidence components and evidence ───────────────────────────────────

    @Test
    void confidence_is_exactly_spanConfidence_anchorStrength_and_normalizerCertainty() {
        // The V7 contract: three components, never a fourth. 0.8 × 0.9 × 1 = 0.7200.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(22, "61,538.72", "333.0", "123.5", "35.6", "7.4", "0.8"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("0.8");
        assertThat(outcome.confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(outcome.confidence().normalizerCertainty()).isEqualByComparingTo("1");
        assertThat(outcome.confidence().overall()).isEqualTo(new BigDecimal("0.7200"));
    }

    @Test
    void the_value_spans_are_VALUE_evidence_and_the_label_spans_are_LABEL_evidence() {
        // Identical to ANCHOR_LABEL's evidence shape, so click-to-highlight needs no UI change.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(22, "61,538.72", "333.0", "123.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(22L);
        assertThat(outcome.valueEvidence().get(0).box().x()).isEqualByComparingTo("333.0");
        assertThat(outcome.valueEvidence().get(0).box().y()).isEqualByComparingTo("123.5");
        assertThat(outcome.valueEvidence())
                .allSatisfy(evidence -> assertThat(evidence.layoutElementId()).isNull());
        // The label anchor is the phrase, not the box number: spans 12-15, never span 11.
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(12L, 13L, 14L, 15L);
    }

    @Test
    void a_wider_maxDropPt_authored_in_the_schema_reaches_the_next_row() {
        // maxDropPt is DATA, not a constant: a schema author who measures a taller form can
        // widen it. Proving it changes behaviour is what keeps the default honest.
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(43, "62,538.72", "333.0", "147.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY, 36.0, 0.5));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("62,538.72");
    }

    // ── a value match must never straddle visual lines ───────────────────────

    /**
     * The shipped {@code tax_return}/{@code schedule_e} {@code @PERSON@} macro, verbatim from
     * {@code V13__repeating_groups.sql}: one person — first name, up to two middle tokens
     * (initial or spelled), optional surname — optionally joined to a second by "and"/"&".
     */
    private static final String PERSON =
            "(?<![A-Za-z])[A-Z][a-z]+(?: (?:[A-Z]\\.?|[A-Z][a-z]+)){0,2}(?: [A-Z][a-z]+)?"
                    + "(?: (?:and|&) [A-Z][a-z]+(?: (?:[A-Z]\\.?|[A-Z][a-z]+)){0,2}"
                    + "(?: [A-Z][a-z]+)?)?(?![A-Za-z])(?! (?!and\\b)[a-z])";

    private static FieldSpec stringField(String name, String normalizer, ExtractorSpec... rungs) {
        return new FieldSpec(name, DataType.STRING, true, normalizer, false, List.of(rungs));
    }

    @Test
    void a_value_never_glues_words_from_the_line_below_its_own_visual_line() {
        // The confident-wrong-value defect from a real filled Schedule E: the identity cell
        // under "Name(s) shown on return" admits TWO visual lines (maxDropPt 24 is two text
        // rows), and the scope used to join them with a space. The joint name is printed WHOLE
        // on one line, but the @PERSON@ pattern's middle slots legally consumed the capitalized
        // words the form prints on the NEXT line ("Part" "I" "Income" — a 4-letter word, a bare
        // capital, a 6-letter word), producing an 8-token value whose tail exists as contiguous
        // text NOWHERE on the page — at 0.9, with an evidence box a reviewer would nod at.
        // A LABEL_BELOW value match must resolve inside ONE visual line of the cell.
        PageContent page =
                page(
                        span(11, "Name(s)", "36.0", "111.0", "30.7", "6.5"),
                        span(12, "shown", "72.7", "111.0", "23.1", "6.5"),
                        span(13, "on", "101.8", "111.0", "8.9", "6.5"),
                        span(14, "return", "116.7", "111.0", "20.9", "6.5"),
                        span(21, "Jordan Q. Fixture and Casey Reese Fix", "36.0", "123.5", "170.0", "9.3"),
                        span(31, "Part", "36.0", "135.0", "20.0", "9.3"),
                        span(32, "I", "60.0", "135.0", "3.7", "9.3"),
                        span(33, "Income", "80.0", "135.0", "33.0", "9.3"));
        FieldSpec taxpayerName =
                stringField(
                        "taxpayerName",
                        "personName",
                        below(0.9, "Name(s) shown on return", PERSON));

        FieldOutcome outcome = only(engine.extract(schema(taxpayerName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText())
                .as("the capture is the name line's text — never the next line's words")
                .isEqualTo("Jordan Q. Fixture and Casey Reese Fix");
        assertThat(outcome.valueEvidence())
                .as("the evidence is the ONE span the name line prints")
                .extracting(EvidenceRef::spanId)
                .containsExactly(21L);
    }

    @Test
    void a_value_below_a_matchless_continuation_line_is_MISSING_never_guessed() {
        // DELIBERATE SEMANTIC REVERSAL. This geometry — a matchless caption continuation
        // between the label and a money line — used to assert the money below the
        // continuation was FOUND: the per-line rule confined the match, and lines without a
        // match were passed over. That skip-down is exactly how a real W-2 reported an
        // ADDRESS line's person-shaped street words as employeeName at 0.9 (see the
        // owning-line tests below): whatever sits under the first line of a cell is a
        // DIFFERENT printed fact, and a pattern match inside it is wrong by construction.
        // The first line below the caption that contains any admitted span now OWNS the
        // cell; a cell whose owning line carries no value goes MISSING, which is the
        // review case it always was (missing over wrong, design D5).
        PageContent page =
                page(
                        span(11, "1", "336.0", "111.0", "3.9", "6.5"),
                        span(12, "Wages,", "342.9", "111.0", "23.7", "6.5"),
                        span(13, "tips,", "369.6", "111.0", "12.8", "6.5"),
                        span(14, "other", "385.5", "111.0", "16.0", "6.5"),
                        span(15, "compensation", "404.4", "111.0", "43.6", "6.5"),
                        span(21, "(see", "342.9", "123.5", "14.0", "6.5"),
                        span(22, "instructions)", "360.0", "123.5", "42.0", "6.5"),
                        span(23, "61,538.72", "333.0", "133.5", "35.6", "7.4"));
        FieldSpec wages =
                field(
                        "wagesTipsOtherComp",
                        "money",
                        below(0.9, "Wages, tips, other compensation", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(wages), List.of(page)));

        assertThat(outcome)
                .as("the continuation line owns the cell; the money below it is a lower "
                        + "line this rung must never consult")
                .isEqualTo(FieldOutcome.missing(wages));
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    // ── the cell's x-window: printed captions partition the row ──────────────

    /** The shipped {@code w2@1.1.x} employeeName pattern, verbatim from V12. */
    private static final String PERSON_ONE =
            "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])";

    /** The shipped {@code w2@1.1.x} employerName pattern, verbatim from V12. */
    private static final String COMPANY =
            "(?<![A-Za-z])[A-Z][A-Za-z&'-]*(?: [A-Z&][A-Za-z&'-]*){0,4}(?![A-Za-z])";

    @Test
    void a_value_wider_than_its_narrow_caption_is_captured_whole_up_to_the_next_caption() {
        // The confirmed truncation defect from a real filled Schedule E. The form's own
        // geometry (blank f1040se): "Name(s) shown on return" spans x 36-114.4 and "Your
        // social security number" spans x 457.6-551.2 ON THE SAME caption line; the name
        // box is the whole space between the two captions, and a filed joint name (seven
        // word runs, one visual line) extends far past its caption's own right edge. An
        // admission measured against the CAPTION's x-extent kept only the tokens over the
        // caption and captured "Jordan Q. Fixture" — five-of-seven-tokens shaped, at 0.9,
        // with a plausible evidence box. The cell must run from its caption's left edge to
        // the NEXT printed caption's left edge, and the neighbour's value (the SSN, typed
        // starting 3 pt LEFT of its own caption, the real forms' habit) must stay out.
        PageContent page =
                page(
                        span(11, "Name(s)", "36.0", "86.5", "25.7", "7.0"),
                        span(12, "shown", "63.6", "86.5", "20.6", "7.0"),
                        span(13, "on", "86.2", "86.5", "7.9", "7.0"),
                        span(14, "return", "96.0", "86.5", "18.4", "7.0"),
                        span(15, "Your", "457.6", "86.5", "15.8", "7.0"),
                        span(16, "social", "475.4", "86.5", "19.7", "7.0"),
                        span(17, "security", "497.0", "86.5", "26.6", "7.0"),
                        span(18, "number", "525.5", "86.5", "25.7", "7.0"),
                        span(21, "Jordan", "36.0", "96.5", "30.0", "10.0"),
                        span(22, "Q.", "70.0", "96.5", "9.0", "10.0"),
                        span(23, "Fixture", "83.0", "96.5", "32.0", "10.0"),
                        span(24, "and", "119.0", "96.5", "15.0", "10.0"),
                        span(25, "Casey", "138.0", "96.5", "26.0", "10.0"),
                        span(26, "Reese", "168.0", "96.5", "26.0", "10.0"),
                        span(27, "Fix", "198.0", "96.5", "14.0", "10.0"),
                        span(31, "987-65-4321", "454.6", "96.5", "46.0", "10.0"));
        FieldSpec taxpayerName =
                stringField(
                        "taxpayerName",
                        "personName",
                        below(0.9, "Name(s) shown on return", PERSON));

        FieldOutcome outcome = only(engine.extract(schema(taxpayerName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText())
                .as("the joint name is captured WHOLE — the caption's own width is not the cell")
                .isEqualTo("Jordan Q. Fixture and Casey Reese Fix");
        assertThat(outcome.valueEvidence())
                .as("all seven name runs are evidence; the neighbour's SSN is NOT")
                .extracting(EvidenceRef::spanId)
                .containsExactly(21L, 22L, 23L, 24L, 25L, 26L, 27L);
    }

    @Test
    void a_wide_line_from_the_neighbouring_box_never_enters_the_cell_by_crossing_it() {
        // The confirmed wrong-box defect from a real filled W-2, and the pull OPPOSITE to
        // the widening above: the employer-name line is ONE wide printed run that starts in
        // box c's cell and merely CROSSES box e's x-territory. An admission measured
        // against the narrower of the two boxes (the caption) let the crossing in, and
        // employeeName captured "Sunrise Homes" — the employer's first two tokens,
        // person-shaped, at 0.9. Ownership must be measured against the SPAN's own width:
        // a run mostly inside another caption's zone belongs to that zone, full stop —
        // employeeName goes MISSING (missing over wrong, D5) while employerName, whose
        // widened cell holds the run's majority, KEEPS it. Same span, both directions of
        // the tension, one geometry.
        PageContent page =
                page(
                        span(11, "Employer's", "42.0", "86.5", "36.0", "7.0"),
                        span(12, "name,", "80.0", "86.5", "20.0", "7.0"),
                        span(13, "address,", "102.0", "86.5", "28.0", "7.0"),
                        span(14, "and", "132.0", "86.5", "12.0", "7.0"),
                        span(15, "ZIP", "146.0", "86.5", "12.0", "7.0"),
                        span(16, "code", "160.0", "86.5", "16.0", "7.0"),
                        span(21, "Employee's", "210.0", "86.5", "37.0", "7.0"),
                        span(22, "first", "249.0", "86.5", "13.0", "7.0"),
                        span(23, "name", "264.0", "86.5", "17.0", "7.0"),
                        span(24, "and", "283.0", "86.5", "12.0", "7.0"),
                        span(25, "initial", "297.0", "86.5", "20.0", "7.0"),
                        span(31, "Sunrise Homes LLC 4300 Sample Pkwy", "40.0", "96.5", "292.0", "8.0"));
        FieldSpec employerName =
                stringField(
                        "employerName",
                        null,
                        below(0.9, "Employer's name, address, and ZIP code", COMPANY));
        FieldSpec employeeName =
                stringField(
                        "employeeName",
                        "personName",
                        below(0.9, "Employee's first name and initial", PERSON_ONE));

        List<FieldOutcome> outcomes =
                engine.extract(schema(employerName, employeeName), List.of(page));
        assertThat(outcomes).hasSize(2);
        FieldOutcome employer = outcomes.get(0);
        FieldOutcome employee = outcomes.get(1);

        assertThat(employee)
                .as("the crossing run is box c's, not box e's: MISSING over a wrong value")
                .isEqualTo(FieldOutcome.missing(employeeName));
        assertThat(employee.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(employee.valueEvidence()).isEmpty();

        assertThat(employer.found())
                .as("the run's OWNER keeps it — refusing both sides would trade one defect "
                        + "for another")
                .isTrue();
        assertThat(employer.displayedText()).isEqualTo("Sunrise Homes LLC");
        assertThat(employer.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(31L);
    }

    @Test
    void a_captions_own_tail_is_not_the_next_caption_so_a_flush_right_value_is_admitted() {
        // The confirmed miss from a real ADP W-2 (2026-09-05): the shipped label "Medicare
        // wages" is a PREFIX of the caption the form prints, "5 Medicare wages and tips", and
        // ADP sets every amount flush RIGHT in its box. Reading the first word past the
        // matched run — "and", 3.2 pt on — as the next caption collapsed the cell to the two
        // matched words (x 342.9-397.9); the amount, right-aligned to end at 447.6, lay
        // wholly outside it and boxes 5 and 16 went missing while box 3, whose literal
        // happens to run to its caption's end, survived. The caption's tail sits a word gap
        // away; the next box's caption ("6 Medicare tax withheld") begins several ems on.
        // Same grid as the IRS drawing the tests above measure.
        PageContent page =
                page(
                        span(11, "5", "336.0", "159.0", "3.9", "6.5"),
                        span(12, "Medicare", "342.9", "159.0", "28.8", "6.5"),
                        span(13, "wages", "374.7", "159.0", "20.2", "6.5"),
                        span(14, "and", "397.9", "159.0", "11.7", "6.5"),
                        span(15, "tips", "412.6", "159.0", "10.9", "6.5"),
                        span(16, "6", "459.0", "159.0", "3.9", "6.5"),
                        span(17, "Medicare", "465.9", "159.0", "28.8", "6.5"),
                        span(18, "tax", "497.7", "159.0", "9.3", "6.5"),
                        span(19, "withheld", "510.0", "159.0", "25.7", "6.5"),
                        span(22, "62,110.09", "412.0", "171.5", "35.6", "7.4"),
                        span(23, "900.60", "511.0", "171.5", "24.7", "7.4"));
        FieldSpec medicareWages =
                field("medicareWages", "money", below(0.9, "Medicare wages", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(medicareWages), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText())
                .as("the cell runs to the NEXT caption, not to the end of the matched words")
                .isEqualTo("62,110.09");
        assertThat(outcome.valueEvidence())
                .as("box 6's flush-right amount stays in box 6")
                .extracting(EvidenceRef::spanId)
                .containsExactly(22L);
    }

    // ── the first line below the caption OWNS the cell ───────────────────────
    //
    // The three-line geometry measured from a real filled W-2 (corpus, never copied here):
    //   box e caption row, top 182.5, height 7.0 (bottom 189.5) — the anchor phrase
    //     "Employee's first name and initial" spans x 49.9-151.0, and the NEXT caption on the
    //     same row, "Last name", starts at x 157.0, so the cell window runs 49.9-157.0;
    //   the REAL employee name, top 195.0 (drop 5.5): First, BARE middle initial, Last,
    //     starting x 60 — in the drop window, inside the x-window, mixed-case;
    //   the employee ADDRESS line, top 209.7 (drop 20.2 — inside maxDropPt 24): its leading
    //     house number sits at x 40, left of the window, and its street words at x 60.0/89.4
    //     are inside it and PERSON-SHAPED. On the real document the engine skipped the name
    //     and reported the two street words as employeeName at 0.90.

    @Test
    void a_bare_middle_initial_on_the_first_line_below_the_caption_is_captured() {
        // The clean three-line geometry, no interference: the name line must win, which
        // proves span admission, the top-to-bottom offering order, and the seeded pattern's
        // bare-initial middle slot ([A-Z]\.?) all hold. This test pins those three candidate
        // causes OUT of the wrong-line defect the next two tests reproduce.
        PageContent page =
                page(
                        span(111, "e", "42.0", "182.5", "4.0", "7.0"),
                        span(112, "Employee's", "49.9", "182.5", "36.1", "7.0"),
                        span(113, "first", "88.5", "182.5", "12.5", "7.0"),
                        span(114, "name", "103.5", "182.5", "17.0", "7.0"),
                        span(115, "and", "123.0", "182.5", "12.0", "7.0"),
                        span(116, "initial", "137.5", "182.5", "13.5", "7.0"),
                        span(117, "Last", "157.0", "182.5", "14.0", "7.0"),
                        span(118, "name", "173.0", "182.5", "17.0", "7.0"),
                        span(119, "Suff.", "300.0", "182.5", "15.0", "7.0"),
                        span(120, "12a", "340.0", "182.5", "12.0", "7.0"),
                        span(121, "See", "354.0", "182.5", "12.0", "7.0"),
                        span(122, "instructions", "368.0", "182.5", "38.0", "7.0"),
                        span(131, "Jordan", "60.0", "195.0", "28.0", "9.0"),
                        span(132, "A", "92.0", "195.0", "6.0", "9.0"),
                        span(133, "Fix", "102.0", "195.0", "14.0", "9.0"),
                        span(141, "9999", "40.0", "209.7", "17.0", "8.0"),
                        span(142, "Sunrise", "60.0", "209.7", "26.0", "8.0"),
                        span(143, "Grove", "89.4", "209.7", "21.0", "8.0"),
                        span(144, "Pkwy,", "113.0", "209.7", "22.0", "8.0"),
                        span(145, "Apt", "138.0", "209.7", "13.0", "8.0"),
                        span(146, "9B", "154.0", "209.7", "9.0", "8.0"));
        FieldSpec employeeName =
                stringField(
                        "employeeName",
                        "personName",
                        below(0.9, "Employee's first name and initial", PERSON_ONE));

        FieldOutcome outcome = only(engine.extract(schema(employeeName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText())
                .as("the name line is the first line below the caption, and it owns the cell")
                .isEqualTo("Jordan A Fix");
        assertThat(outcome.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(131L, 132L, 133L);
    }

    @Test
    void a_name_row_merged_into_the_caption_line_by_a_tall_foreign_span_still_owns_the_cell() {
        // The REAL W-2 wrong-value mechanism (part 1 of the diagnosis). The page carries one
        // extra printed fact the clean geometry above omits: box 12a's vertical "Code"
        // caption — a single rotated run, 6 pt wide and 25 pt TALL, at x 341. Visual-line
        // grouping chains on center proximity, and that one tall box BRIDGES the caption row
        // (centers 186.0) to the name row (centers 199.5): gap 11.5 and 2.0 against a
        // half-max-height threshold of 12.5, so caption, "Code" and the REAL NAME congeal
        // into ONE page-global visual line whose top is the caption's own 182.5. A drop gate
        // that reads that line's top discards the name as "the label's own line", and the
        // address row below — person-shaped street words inside the window — answered
        // instead, at 0.90. Admission must therefore be measured per SPAN against the cell's
        // own geometry, never against the page-global line an unrelated tall run merged.
        PageContent page =
                page(
                        span(111, "e", "42.0", "182.5", "4.0", "7.0"),
                        span(112, "Employee's", "49.9", "182.5", "36.1", "7.0"),
                        span(113, "first", "88.5", "182.5", "12.5", "7.0"),
                        span(114, "name", "103.5", "182.5", "17.0", "7.0"),
                        span(115, "and", "123.0", "182.5", "12.0", "7.0"),
                        span(116, "initial", "137.5", "182.5", "13.5", "7.0"),
                        span(117, "Last", "157.0", "182.5", "14.0", "7.0"),
                        span(118, "name", "173.0", "182.5", "17.0", "7.0"),
                        span(119, "Suff.", "300.0", "182.5", "15.0", "7.0"),
                        span(120, "12a", "340.0", "182.5", "12.0", "7.0"),
                        span(121, "See", "354.0", "182.5", "12.0", "7.0"),
                        span(122, "instructions", "368.0", "182.5", "38.0", "7.0"),
                        span(130, "Code", "341.0", "185.0", "6.0", "25.0"),
                        span(131, "Jordan", "60.0", "195.0", "28.0", "9.0"),
                        span(132, "A", "92.0", "195.0", "6.0", "9.0"),
                        span(133, "Fix", "102.0", "195.0", "14.0", "9.0"),
                        span(141, "9999", "40.0", "209.7", "17.0", "8.0"),
                        span(142, "Sunrise", "60.0", "209.7", "26.0", "8.0"),
                        span(143, "Grove", "89.4", "209.7", "21.0", "8.0"),
                        span(144, "Pkwy,", "113.0", "209.7", "22.0", "8.0"),
                        span(145, "Apt", "138.0", "209.7", "13.0", "8.0"),
                        span(146, "9B", "154.0", "209.7", "9.0", "8.0"));
        FieldSpec employeeName =
                stringField(
                        "employeeName",
                        "personName",
                        below(0.9, "Employee's first name and initial", PERSON_ONE));

        FieldOutcome outcome = only(engine.extract(schema(employeeName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText())
                .as("the real name wins even when a tall foreign run merges its page-global "
                        + "visual line into the caption's — never the address line's street "
                        + "words")
                .isEqualTo("Jordan A Fix");
        assertThat(outcome.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(131L, 132L, 133L);
    }

    @Test
    void a_shape_mismatch_on_the_owning_line_fails_the_rung_never_a_lower_lines_value() {
        // The rule that makes the whole class impossible (part 2): the first line below the
        // caption that contains ANY admitted span owns the cell. Here the filer typed the
        // name in ALL CAPS — the seeded person pattern does not match it — and the address
        // line 14.7 pt further down still holds person-shaped street words inside the
        // window. Skip-down is how a shape mismatch on the TRUE value line becomes a
        // confident wrong value from a lower line; missing-over-wrong is the governing rule,
        // so the rung must FAIL and never consult the address line.
        PageContent page =
                page(
                        span(111, "e", "42.0", "182.5", "4.0", "7.0"),
                        span(112, "Employee's", "49.9", "182.5", "36.1", "7.0"),
                        span(113, "first", "88.5", "182.5", "12.5", "7.0"),
                        span(114, "name", "103.5", "182.5", "17.0", "7.0"),
                        span(115, "and", "123.0", "182.5", "12.0", "7.0"),
                        span(116, "initial", "137.5", "182.5", "13.5", "7.0"),
                        span(117, "Last", "157.0", "182.5", "14.0", "7.0"),
                        span(118, "name", "173.0", "182.5", "17.0", "7.0"),
                        span(131, "JORDAN", "60.0", "195.0", "30.0", "9.0"),
                        span(132, "A", "94.0", "195.0", "6.0", "9.0"),
                        span(133, "FIX", "104.0", "195.0", "15.0", "9.0"),
                        span(141, "9999", "40.0", "209.7", "17.0", "8.0"),
                        span(142, "Sunrise", "60.0", "209.7", "26.0", "8.0"),
                        span(143, "Grove", "89.4", "209.7", "21.0", "8.0"),
                        span(144, "Pkwy,", "113.0", "209.7", "22.0", "8.0"),
                        span(145, "Apt", "138.0", "209.7", "13.0", "8.0"),
                        span(146, "9B", "154.0", "209.7", "9.0", "8.0"));
        FieldSpec employeeName =
                stringField(
                        "employeeName",
                        "personName",
                        below(0.9, "Employee's first name and initial", PERSON_ONE));

        FieldOutcome outcome = only(engine.extract(schema(employeeName), List.of(page)));

        assertThat(outcome)
                .as("the owning line's shape mismatch is a review case, not a licence to "
                        + "guess from the address line")
                .isEqualTo(FieldOutcome.missing(employeeName));
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_caption_printed_with_a_right_single_quotation_mark_matches_an_ascii_label() {
        // The Spec 4 defect at rung level: the schema's authored label carries the ASCII
        // apostrophe, the form's text layer carries U+2019. Geometry is this file's standard
        // caption/value pair; only the caption's punctuation is under test.
        PageContent page =
                page(
                        span(
                                1,
                                "Employer’s name, address, and ZIP code",
                                "36",
                                "111.0",
                                "180",
                                "6.5"),
                        span(2, "1,234.56", "36", "123.5", "60", "8.0"));
        FieldSpec employerName =
                field(
                        "employerName",
                        "money",
                        below(0.90, "Employer's name, address, and ZIP code", MONEY));

        FieldOutcome outcome = only(engine.extract(schema(employerName), List.of(page)));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.LABEL_BELOW);
        assertThat(outcome.displayedText()).isEqualTo("1,234.56");
        assertThat(outcome.labelEvidence()).extracting(EvidenceRef::spanId).containsExactly(1L);
    }
}
