package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.GroupRegionSpec;
import com.pragmaticds.docengine.extraction.schema.GroupSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * LABELED row groups: the row key is the letter the form PREPRINTS at the row's own left edge,
 * never a counted ordinal.
 *
 * <p>The geometry here is the one a real Schedule E Part II prints and the shared-origin scheme
 * cannot read: the table appears TWICE — an upper sub-table under the entity captions (name,
 * EIN) and a lower sub-table under the money captions — with the row letters A-D preprinted in
 * the left-margin gutter of BOTH. One counted origin cannot serve both bands: resolved per group
 * (the mutation-verified Spec 5a fix), the name fields read their rows from the MONEY sub-table
 * — the caption continuation line and the money rows became phantom "name" occurrences on a real
 * document, at 0.63–0.90 confidence with real evidence boxes. The letter is the join key the
 * form itself provides (design D2): name row A and money row A are the same entity BY THE FORM'S
 * OWN LABELING, so each FIELD may read its own sub-table and the letter joins them.
 *
 * <p>What anchors a label so a stray capital inside a name can never become a row key:
 *
 * <ul>
 *   <li>it is the LEFTMOST span of its visual line — the gutter is the table's first column and
 *       nothing prints left of it;
 *   <li>its trimmed text is exactly one of the group's DECLARED single-letter labels; and
 *   <li>every label the walk accepts must occupy ONE x-column (pairwise-overlapping x-extents) —
 *       the letters are a printed column, and a candidate off that column means the gutter
 *       cannot be trusted, so the field fails CLOSED rather than adjudicating which letter is
 *       real.
 * </ul>
 *
 * <p>The first line carrying a letter wins that letter for the field: the field's own sub-table
 * is the one its caption line opens, and the later sub-table merely repeats the letters. A row
 * whose label cannot be found is MISSING, never guessed — there is no positional fallback,
 * because a fallback would silently revert to the misalignment this scheme removes.
 */
class LabeledRowGroupExtractionTest {

    /** The CONTRACT's money pattern, verbatim. */
    private static final String MONEY =
            "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)";

    /** {@code schedule_e@1.0.0}'s widened entity-name pattern, verbatim — it matches "Totals". */
    private static final String ENTITY_NAME =
            "(?<!\\S)[\\p{L}\\p{N}]*[\\p{L}][\\p{L}\\p{N}&.,'-]*"
                    + "(?: [\\p{L}\\p{N}&][\\p{L}\\p{N}&.,'-]*){0,11}(?!\\S)";

    /** {@code schedule_e@1.0.0}'s EIN pattern, verbatim. */
    private static final String EIN = "(?<![\\d-])\\d{2}-\\d{7}(?![\\d-])";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    // ── span and page builders ───────────────────────────────────────────────

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                BigDecimal.ONE);
    }

    /** {@code Income or Loss From Partnerships and S Corporations} — the region start. */
    private static List<SpanRef> startAnchor() {
        return List.of(
                span(1, "Income", "40.0", "400.0", "30.0", "7.0"),
                span(2, "or", "73.0", "400.0", "10.0", "7.0"),
                span(3, "Loss", "86.0", "400.0", "20.0", "7.0"),
                span(4, "From", "109.0", "400.0", "22.0", "7.0"),
                span(5, "Partnerships", "134.0", "400.0", "55.0", "7.0"),
                span(6, "and", "192.0", "400.0", "16.0", "7.0"),
                span(7, "S", "211.0", "400.0", "6.0", "7.0"),
                span(8, "Corporations", "220.0", "400.0", "55.0", "7.0"));
    }

    /** The ENTITY caption band: {@code (a) Name} and {@code (d) Employer}. */
    private static List<SpanRef> entityCaptions() {
        return List.of(
                span(10, "(a)", "48.0", "418.0", "12.0", "6.5"),
                span(11, "Name", "62.0", "418.0", "24.0", "6.5"),
                span(12, "(d)", "160.0", "418.0", "12.0", "6.5"),
                span(13, "Employer", "174.0", "418.0", "45.0", "6.5"));
    }

    /**
     * The UPPER sub-table: entity rows opened by their preprinted letter in the gutter at x 40.
     * Rows C and D are letter-only — the form preprints all four letters and this filer has two
     * partnerships. The single capital {@code P}/{@code S} mid-row is the (b) code column: a
     * letter that is NOT leftmost and must never become a row key.
     */
    private static List<SpanRef> upperRowA() {
        return List.of(
                span(20, "A", "40.0", "440.0", "5.0", "7.4"),
                span(21, "SUMMIT RIDGE PARTNERS", "55.0", "440.0", "100.0", "7.4"),
                span(22, "P", "240.0", "440.0", "6.0", "7.4"),
                span(23, "27-1234567", "170.0", "440.0", "50.0", "7.4"));
    }

    private static List<SpanRef> upperRowB() {
        return List.of(
                span(30, "B", "40.0", "455.0", "5.0", "7.4"),
                span(31, "BOREAL HOLDINGS INC", "55.0", "455.0", "95.0", "7.4"),
                span(32, "S", "240.0", "455.0", "6.0", "7.4"),
                span(33, "84-7654321", "170.0", "455.0", "50.0", "7.4"));
    }

    private static List<SpanRef> upperRowC() {
        return List.of(span(40, "C", "40.0", "470.0", "5.0", "7.4"));
    }

    private static List<SpanRef> upperRowD() {
        return List.of(span(45, "D", "40.0", "485.0", "5.0", "7.4"));
    }

    /** The MONEY caption band, printed BELOW the whole upper sub-table. */
    private static List<SpanRef> moneyCaptions() {
        return List.of(
                span(50, "(h)", "340.0", "500.0", "12.0", "6.5"),
                span(51, "Nonpassive", "355.0", "500.0", "45.0", "6.5"),
                span(52, "loss", "403.0", "500.0", "16.0", "6.5"),
                span(53, "(k)", "440.0", "500.0", "10.0", "6.5"),
                span(54, "Nonpassive", "453.0", "500.0", "45.0", "6.5"),
                span(55, "income", "501.0", "500.0", "30.0", "6.5"));
    }

    /**
     * The caption CONTINUATION line — "(attach Form 8582 … from Schedule K-1" — an UNLETTERED
     * line inside the region. Under counted ordinals it was row 01 and shifted every entity by
     * one; it has no letter in the gutter, so under printed labels it is no row at all.
     */
    private static List<SpanRef> captionContinuation() {
        return List.of(
                span(60, "(attach", "340.0", "508.0", "30.0", "5.0"),
                span(61, "Form", "373.0", "508.0", "20.0", "5.0"),
                span(62, "8582", "396.0", "508.0", "20.0", "5.0"),
                span(63, "from", "440.0", "508.0", "18.0", "5.0"),
                span(64, "Schedule", "461.0", "508.0", "36.0", "5.0"),
                span(65, "K-1", "500.0", "508.0", "14.0", "5.0"));
    }

    /** The LOWER sub-table: the SAME four preprinted letters, this time beside the money. */
    private static List<SpanRef> lowerRowA() {
        return List.of(
                span(70, "A", "40.0", "520.0", "5.0", "7.4"),
                span(71, "1,200.00", "360.0", "520.0", "34.0", "7.4"),
                span(72, "42,150.00", "468.0", "520.0", "38.0", "7.4"));
    }

    private static List<SpanRef> lowerRowB() {
        return List.of(
                span(80, "B", "40.0", "535.0", "5.0", "7.4"),
                span(81, "2,100.00", "360.0", "535.0", "34.0", "7.4"),
                span(82, "5,250.00", "470.0", "535.0", "34.0", "7.4"));
    }

    private static List<SpanRef> lowerRowC() {
        return List.of(span(90, "C", "40.0", "550.0", "5.0", "7.4"));
    }

    private static List<SpanRef> lowerRowD() {
        return List.of(span(95, "D", "40.0", "565.0", "5.0", "7.4"));
    }

    /**
     * The 29a Totals row: UNLETTERED, inside the region, carrying column sums squarely inside
     * the money columns AND a word inside the name column. A counted walk keys it; a labeled
     * walk finds no letter in the gutter and skips it.
     */
    private static List<SpanRef> totalsRow() {
        return List.of(
                span(100, "29a", "36.0", "580.0", "14.0", "7.4"),
                span(101, "Totals", "55.0", "580.0", "28.0", "7.4"),
                span(102, "3,300.00", "360.0", "580.0", "34.0", "7.4"),
                span(103, "47,400.00", "468.0", "580.0", "38.0", "7.4"));
    }

    /** The region end, carrying the COLUMN TOTAL inside the income column. */
    private static List<SpanRef> endAnchor() {
        return List.of(
                span(110, "Total", "40.0", "595.0", "22.0", "7.0"),
                span(111, "partnership", "65.0", "595.0", "50.0", "7.0"),
                span(112, "and", "118.0", "595.0", "16.0", "7.0"),
                span(113, "S", "137.0", "595.0", "6.0", "7.0"),
                span(114, "corporation", "146.0", "595.0", "50.0", "7.0"),
                span(115, "55,200.00", "468.0", "595.0", "38.0", "7.0"));
    }

    @SafeVarargs
    private static PageContent page(List<SpanRef>... lines) {
        List<SpanRef> spans = new ArrayList<>();
        for (List<SpanRef> line : lines) {
            spans.addAll(line);
        }
        return new PageContent(UUID.randomUUID(), 0, List.copyOf(spans), List.of());
    }

    /** Part II as the real form prints it: TWO lettered sub-tables and their decoys. */
    private static PageContent dualSubTablePage() {
        return page(
                startAnchor(),
                entityCaptions(),
                upperRowA(),
                upperRowB(),
                upperRowC(),
                upperRowD(),
                moneyCaptions(),
                captionContinuation(),
                lowerRowA(),
                lowerRowB(),
                lowerRowC(),
                lowerRowD(),
                totalsRow(),
                endAnchor());
    }

    // ── schema builders ──────────────────────────────────────────────────────

    private static GroupSpec letteredRows() {
        return GroupSpec.labeledRow(
                new GroupRegionSpec(
                        new LabelSpec(
                                AnchorKind.LITERAL,
                                "Income or Loss From Partnerships and S Corporations"),
                        new LabelSpec(AnchorKind.LITERAL, "Total partnership and S corporation")),
                20,
                List.of("A", "B", "C", "D"));
    }

    private static ExtractorSpec rowCell(String columnHeader, String pattern) {
        return new ExtractorSpec(
                ExtractionMethod.ROW_CELL,
                0.9,
                null,
                null,
                new ValueSpec(pattern, 0, ValueScope.LINE),
                null,
                null,
                null,
                null,
                0.5,
                new LabelSpec(AnchorKind.LITERAL, columnHeader));
    }

    private static FieldSpec grouped(
            String name, DataType type, String normalizer, ExtractorSpec rung) {
        return new FieldSpec(name, type, false, normalizer, false, List.of(rung), letteredRows());
    }

    /** The four Part II fields, two per sub-table, one labeled group. */
    private static SchemaDefinition labeledSchema() {
        return new SchemaDefinition(
                "SCHEDULE_E",
                "1.0.0",
                List.of(
                        grouped(
                                "partnershipName",
                                DataType.STRING,
                                null,
                                rowCell("(a) Name", ENTITY_NAME)),
                        grouped(
                                "partnershipEin",
                                DataType.STRING,
                                null,
                                rowCell("(d) Employer", EIN)),
                        grouped(
                                "partnershipNonpassiveLoss",
                                DataType.MONEY,
                                "money",
                                rowCell("(h) Nonpassive loss", MONEY)),
                        grouped(
                                "partnershipNonpassiveIncome",
                                DataType.MONEY,
                                "money",
                                rowCell("(k) Nonpassive income", MONEY))));
    }

    // ── reading the outcomes the way a consumer does ─────────────────────────

    private static List<FieldOutcome> of(List<FieldOutcome> outcomes, String field) {
        return outcomes.stream()
                .filter(outcome -> outcome.field().name().equals(field))
                .toList();
    }

    private static List<String> keysOf(List<FieldOutcome> outcomes, String field) {
        return of(outcomes, field).stream().map(FieldOutcome::groupKey).toList();
    }

    /** The consumer contract's own instruction: group by groupKey, read across field names. */
    private static Map<String, String> entityAt(List<FieldOutcome> outcomes, String groupKey) {
        Map<String, String> entity = new LinkedHashMap<>();
        for (FieldOutcome outcome : outcomes) {
            if (groupKey.equals(outcome.groupKey()) && outcome.found()) {
                entity.put(outcome.field().name(), outcome.displayedText());
            }
        }
        return entity;
    }

    // ── the printed letter is the key ────────────────────────────────────────

    @Test
    void every_field_of_a_labeled_group_keys_by_the_printed_letter() {
        // Name and EIN read the UPPER sub-table, the money fields the LOWER one — and all four
        // key sets are the form's own letters. Counted ordinals cannot do this: one origin
        // serves one band, and the other band's fields drift onto caption text and money rows.
        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(dualSubTablePage()));

        for (String field :
                List.of(
                        "partnershipName",
                        "partnershipEin",
                        "partnershipNonpassiveLoss",
                        "partnershipNonpassiveIncome")) {
            assertThat(keysOf(outcomes, field))
                    .as("%s keys are the PRINTED letters, in the form's own order", field)
                    .containsExactly("A", "B", "C", "D");
        }
    }

    @Test
    void a_name_and_its_money_join_on_the_same_printed_letter() {
        // THE point of the letter: the two sub-tables are one table, and the form's own row
        // letter is what says so. Every value of entity A comes back under the one key "A".
        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(dualSubTablePage()));

        assertThat(entityAt(outcomes, "A"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "partnershipName", "SUMMIT RIDGE PARTNERS",
                                "partnershipEin", "27-1234567",
                                "partnershipNonpassiveLoss", "1,200.00",
                                "partnershipNonpassiveIncome", "42,150.00"));
        assertThat(entityAt(outcomes, "B"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "partnershipName", "BOREAL HOLDINGS INC",
                                "partnershipEin", "84-7654321",
                                "partnershipNonpassiveLoss", "2,100.00",
                                "partnershipNonpassiveIncome", "5,250.00"));
    }

    @Test
    void each_fields_evidence_for_one_letter_sits_in_its_own_sub_table() {
        // The y-coordinates are the proof that the join spans the two bands: entity A's NAME is
        // evidenced on the upper sub-table's row A and its MONEY on the lower sub-table's row A
        // — different printed lines, one key. A key from the wrong sub-table's letter would put
        // both on one band.
        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(dualSubTablePage()));

        Map<String, String> upper = Map.of("A", "440.0", "B", "455.0");
        Map<String, String> lower = Map.of("A", "520.0", "B", "535.0");
        for (String key : List.of("A", "B")) {
            for (String field : List.of("partnershipName", "partnershipEin")) {
                assertThat(evidenceYs(outcomes, field, key))
                        .as("%s#%s is evidenced on the UPPER sub-table's row %s", field, key, key)
                        .containsExactly(upper.get(key));
            }
            for (String field :
                    List.of("partnershipNonpassiveLoss", "partnershipNonpassiveIncome")) {
                assertThat(evidenceYs(outcomes, field, key))
                        .as("%s#%s is evidenced on the LOWER sub-table's row %s", field, key, key)
                        .containsExactly(lower.get(key));
            }
        }
    }

    private static List<String> evidenceYs(
            List<FieldOutcome> outcomes, String field, String key) {
        return of(outcomes, field).stream()
                .filter(outcome -> key.equals(outcome.groupKey()))
                .flatMap(outcome -> outcome.valueEvidence().stream())
                .map(evidence -> evidence.box().y().toPlainString())
                .distinct()
                .toList();
    }

    @Test
    void a_preprinted_letter_with_a_blank_row_is_MISSING_under_its_own_letter() {
        // Rows C and D are letter-only: the form preprints the letter and the filer left the
        // row blank. Design D5 — the occurrence exists, keyed by the printed letter, and is
        // MISSING: never absent, and never a value borrowed from a neighbouring line.
        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(dualSubTablePage()));

        for (String field :
                List.of(
                        "partnershipName",
                        "partnershipEin",
                        "partnershipNonpassiveLoss",
                        "partnershipNonpassiveIncome")) {
            for (String key : List.of("C", "D")) {
                FieldOutcome outcome =
                        of(outcomes, field).stream()
                                .filter(candidate -> key.equals(candidate.groupKey()))
                                .findFirst()
                                .orElseThrow();
                assertThat(outcome.found()).as("%s#%s is missing", field, key).isFalse();
                assertThat(outcome.displayedText()).isNull();
                assertThat(outcome.valueEvidence()).isEmpty();
                assertThat(outcome.confidence()).isEqualTo(ConfidenceBreakdown.ZERO);
            }
        }
    }

    @Test
    void the_caption_continuation_line_and_the_totals_row_are_never_rows() {
        // The two decoys that manufactured the real document's phantom occurrences. The
        // continuation line was row 01 under counted ordinals — every entity shifted one —
        // and the Totals row put a column SUM under an entity key. Neither carries a letter
        // in the gutter, so neither is a row.
        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(dualSubTablePage()));

        assertThat(outcomes)
                .flatMap(FieldOutcome::valueEvidence)
                .extracting(evidence -> evidence.box().y().toPlainString())
                .as("no occurrence is evidenced on the continuation line or the Totals row")
                .doesNotContain("508.0", "580.0");
        assertThat(outcomes)
                .extracting(FieldOutcome::displayedText)
                .doesNotContain("Totals", "3,300.00", "47,400.00", "55,200.00");
    }

    @Test
    void a_letter_that_is_not_leftmost_on_its_line_is_never_a_row_key() {
        // The (b) code column prints a single capital P or S mid-row — a span that IS a letter
        // but is not the gutter. If it could key a row, any capital inside the table would
        // scramble the key space. (P and S are not declared labels here, but the guard is the
        // GEOMETRY: leftmost-on-the-line — so declare a group whose labels include P and S and
        // prove the mid-row capitals still cannot key anything.)
        GroupSpec wideLabels =
                GroupSpec.labeledRow(
                        new GroupRegionSpec(
                                new LabelSpec(
                                        AnchorKind.LITERAL,
                                        "Income or Loss From Partnerships and S Corporations"),
                                new LabelSpec(
                                        AnchorKind.LITERAL,
                                        "Total partnership and S corporation")),
                        20,
                        List.of("A", "B", "C", "D", "P", "S"));
        FieldSpec name =
                new FieldSpec(
                        "partnershipName",
                        DataType.STRING,
                        false,
                        null,
                        false,
                        List.of(rowCell("(a) Name", ENTITY_NAME)),
                        wideLabels);

        List<FieldOutcome> outcomes =
                engine.extract(
                        new SchemaDefinition("SCHEDULE_E", "1.0.0", List.of(name)),
                        List.of(dualSubTablePage()));

        assertThat(keysOf(outcomes, "partnershipName"))
                .as("P and S are declared but never PRINTED in the gutter, so they are MISSING")
                .containsExactly("A", "B", "C", "D", "P", "S");
        assertThat(of(outcomes, "partnershipName").stream()
                        .filter(outcome -> List.of("P", "S").contains(outcome.groupKey()))
                        .toList())
                .allSatisfy(outcome -> assertThat(outcome.found()).isFalse());
        assertThat(entityAt(outcomes, "A"))
                .containsEntry("partnershipName", "SUMMIT RIDGE PARTNERS");
    }

    @Test
    void a_stray_declared_letter_mid_line_cannot_poison_the_letter_gutter() {
        // The line between the caption band and row A here is the kind the form actually
        // prints — instruction text — and it carries a STANDALONE span whose text is a declared
        // letter, mid-line, off the gutter column. The leftmost-span rule must make this line
        // invisible: it opens with ordinary text, so it is not a row, full stop. An
        // implementation that accepted a declared letter ANYWHERE on the line would seed the
        // gutter at the stray letter's x, every real letter would then fail the one-column
        // check, and the whole field would collapse to MISSING — this test pins the rows
        // surviving, so that regression cannot land silently. (Mutation-verified: with the
        // leftmost rule relaxed to anywhere-on-line, this test fails.)
        List<SpanRef> strayLetterNote =
                List.of(
                        span(300, "See", "40.0", "432.0", "16.0", "7.0"),
                        span(301, "instructions", "59.0", "432.0", "52.0", "7.0"),
                        span(302, "A", "200.0", "432.0", "5.0", "7.0"));
        PageContent poisonedPage =
                page(
                        startAnchor(),
                        entityCaptions(),
                        strayLetterNote,
                        upperRowA(),
                        upperRowB(),
                        upperRowC(),
                        upperRowD(),
                        moneyCaptions(),
                        captionContinuation(),
                        lowerRowA(),
                        lowerRowB(),
                        lowerRowC(),
                        lowerRowD(),
                        totalsRow(),
                        endAnchor());

        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(poisonedPage));

        assertThat(entityAt(outcomes, "A"))
                .as("row A survives a stray mid-line letter above it")
                .containsEntry("partnershipName", "SUMMIT RIDGE PARTNERS");
        assertThat(of(outcomes, "partnershipName").stream()
                        .filter(outcome -> List.of("A", "B").contains(outcome.groupKey()))
                        .toList())
                .as("the populated rows stay FOUND — the stray letter neither keys a row nor"
                        + " collapses the field")
                .allSatisfy(outcome -> assertThat(outcome.found()).isTrue());
    }

    @Test
    void a_label_candidate_off_the_letter_gutter_fails_the_field_CLOSED() {
        // Row B's gutter letter is lost and its typed-in name arrives as loose single-character
        // spans — the leftmost is a capital "A" at the NAME column's x. Keying it would join
        // row B's data to entity A; skipping just that line would silently drop an entity. The
        // letters are a printed COLUMN, so a candidate off the column means the gutter cannot
        // be trusted at all: the field fails CLOSED, every declared letter MISSING.
        List<SpanRef> rowBLetterLost =
                List.of(
                        span(30, "A", "55.0", "455.0", "6.0", "7.4"),
                        span(31, "B", "63.0", "455.0", "6.0", "7.4"),
                        span(32, "C", "71.0", "455.0", "6.0", "7.4"),
                        span(33, "COMPANY", "79.0", "455.0", "40.0", "7.4"));
        PageContent gutterBreached =
                page(
                        startAnchor(),
                        entityCaptions(),
                        upperRowA(),
                        rowBLetterLost,
                        moneyCaptions(),
                        captionContinuation(),
                        lowerRowA(),
                        lowerRowB(),
                        endAnchor());

        List<FieldOutcome> outcomes =
                engine.extract(labeledSchema(), List.of(gutterBreached));

        assertThat(keysOf(outcomes, "partnershipName"))
                .as("the key set survives — every letter, all MISSING")
                .containsExactly("A", "B", "C", "D");
        assertThat(of(outcomes, "partnershipName"))
                .allSatisfy(outcome -> assertThat(outcome.found()).isFalse());
        assertThat(of(outcomes, "partnershipName"))
                .extracting(FieldOutcome::displayedText)
                .as("no value from the breached table is ever reported")
                .containsOnlyNulls();
        // The money sub-table's gutter is intact, so the money fields still read their rows.
        assertThat(entityAt(outcomes, "A"))
                .containsEntry("partnershipNonpassiveIncome", "42,150.00");
    }

    @Test
    void a_table_whose_letters_are_all_lost_is_MISSING_per_letter_never_keyed_by_position() {
        // No positional fallback, ever: a skewed scan that loses the gutter must not silently
        // revert to the counted misalignment this scheme removes. Every declared letter comes
        // back MISSING — a review case per row, not a confident wrong join.
        List<SpanRef> upperRowANoLetter =
                List.of(
                        span(21, "SUMMIT RIDGE PARTNERS", "55.0", "440.0", "100.0", "7.4"),
                        span(23, "27-1234567", "170.0", "440.0", "50.0", "7.4"));
        List<SpanRef> upperRowBNoLetter =
                List.of(
                        span(31, "BOREAL HOLDINGS INC", "55.0", "455.0", "95.0", "7.4"),
                        span(33, "84-7654321", "170.0", "455.0", "50.0", "7.4"));
        PageContent lettersLost =
                page(
                        startAnchor(),
                        entityCaptions(),
                        upperRowANoLetter,
                        upperRowBNoLetter,
                        moneyCaptions(),
                        captionContinuation(),
                        lowerRowA(),
                        lowerRowB(),
                        endAnchor());

        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(lettersLost));

        assertThat(keysOf(outcomes, "partnershipName")).containsExactly("A", "B", "C", "D");
        assertThat(of(outcomes, "partnershipName"))
                .as("no letters, no rows — never 'the first line is probably A'")
                .allSatisfy(outcome -> assertThat(outcome.found()).isFalse());
        // The lower sub-table still prints its letters, so the money fields are unaffected.
        assertThat(entityAt(outcomes, "A"))
                .containsEntry("partnershipNonpassiveLoss", "1,200.00");
    }

    @Test
    void a_labeled_occurrence_confidence_is_exactly_the_three_components() {
        // The V7 contract holds for letter-keyed occurrences too: three components, never a
        // fourth — the label is a KEY on the outcome, not a smuggled confidence input.
        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(dualSubTablePage()));

        FieldOutcome nameA =
                of(outcomes, "partnershipName").stream()
                        .filter(outcome -> "A".equals(outcome.groupKey()))
                        .findFirst()
                        .orElseThrow();
        assertThat(nameA.confidence().spanConfidence()).isEqualByComparingTo("1");
        assertThat(nameA.confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(nameA.confidence().normalizerCertainty()).isEqualByComparingTo("1");
        assertThat(nameA.method()).isEqualTo(ExtractionMethod.ROW_CELL);
    }

    @Test
    void the_gutter_letter_itself_is_never_captured_as_a_value() {
        // The letter is the row's KEY, not its data: even if a caption box reached the gutter,
        // the label span is excluded from every cell, so "A" can never come back as (part of)
        // a name. Here the name pattern would happily match a bare "A" — and must never see it.
        List<FieldOutcome> outcomes = engine.extract(labeledSchema(), List.of(dualSubTablePage()));

        assertThat(outcomes)
                .extracting(FieldOutcome::displayedText)
                .doesNotContain("A", "B", "C", "D");
        assertThat(outcomes)
                .flatMap(FieldOutcome::valueEvidence)
                .extracting(EvidenceRef::spanId)
                .as("no gutter letter span is cited as VALUE evidence")
                .doesNotContain(20L, 30L, 40L, 45L, 70L, 80L, 90L, 95L);
    }
}
