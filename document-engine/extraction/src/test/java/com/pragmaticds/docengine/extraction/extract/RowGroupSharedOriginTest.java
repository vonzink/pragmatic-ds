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
 * ONE row origin per ROW group — the cross-field invariant {@code groupKey} exists to carry.
 *
 * <p>{@code docs/consumer/income-extraction-contract.md} tells consumers to group the entries by
 * {@code groupKey} and read across field names, so the key is a JOIN key: {@code
 * partnershipName#02} and {@code partnershipNonpassiveIncome#02} must name the SAME printed
 * entity. Every field of the group therefore has to count its rows from the same y.
 *
 * <p>The geometry here is the one a real IRS Schedule E Part II prints and the fixture generator
 * cannot ({@code fixtures/generate.py} truncates the captions precisely because the real ones do
 * not fit on one baseline): the entity captions {@code (a)}–{@code (e)} sit on one line and a
 * SECOND caption band {@code (f)}–{@code (j)} sits below it over the dollar columns. Resolving
 * each field's row origin from its OWN caption line puts the name fields one row out of step with
 * the money fields — every occurrence fully confident, every evidence box on a real printed
 * number, and every entity's name paired with the NEXT entity's money.
 *
 * <p>Nothing here is about a skewed scan: {@link VisualLines} chains on consecutive spans, so a
 * rotation is a gradient and both bands stay one line each. The second caption band is the
 * mechanism.
 */
class RowGroupSharedOriginTest {

    /** The CONTRACT's money pattern, verbatim. */
    private static final String MONEY =
            "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)";

    /** {@code schedule_e@1.0.0}'s entity-name pattern, verbatim. */
    private static final String ENTITY_NAME =
            "(?<![A-Za-z])[A-Z][A-Z&.-]*(?: [A-Z&][A-Z&.-]*){0,7}(?![A-Za-z])";

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

    /**
     * The FIRST caption band, y 418: the entity captions {@code (a)} and {@code (d)}. On the real
     * form these are {@code (a)}–{@code (e)} and they wrap; nothing about the money columns is
     * printed on this line.
     */
    private static List<SpanRef> captionBandOne() {
        return List.of(
                span(10, "(a)", "40.0", "418.0", "12.0", "6.5"),
                span(11, "Name", "55.0", "418.0", "24.0", "6.5"),
                span(12, "(d)", "250.0", "418.0", "12.0", "6.5"),
                span(13, "Employer", "265.0", "418.0", "45.0", "6.5"),
                span(14, "ID", "313.0", "418.0", "12.0", "6.5"));
    }

    /**
     * The SECOND caption band, y 430: the dollar-column captions {@code (h)} and {@code (j)},
     * printed BELOW the entity captions because the real ones do not fit beside them.
     */
    private static List<SpanRef> captionBandTwo() {
        return List.of(
                span(20, "(h)", "340.0", "430.0", "12.0", "6.5"),
                span(21, "Nonpassive", "355.0", "430.0", "45.0", "6.5"),
                span(22, "loss", "403.0", "430.0", "16.0", "6.5"),
                span(23, "(j)", "440.0", "430.0", "10.0", "6.5"),
                span(24, "Nonpassive", "453.0", "430.0", "45.0", "6.5"),
                span(25, "income", "501.0", "430.0", "30.0", "6.5"));
    }

    private static List<SpanRef> entityRowOne() {
        return List.of(
                span(30, "SUMMIT RIDGE PARTNERS", "55.0", "444.0", "110.0", "7.4"),
                span(31, "84-1234567", "255.0", "444.0", "50.0", "7.4"),
                span(32, "1,200.00", "360.0", "444.0", "34.0", "7.4"),
                span(33, "42,150.00", "468.0", "444.0", "38.0", "7.4"));
    }

    private static List<SpanRef> entityRowTwo() {
        return List.of(
                span(40, "BOREAL HOLDINGS INC", "55.0", "464.0", "104.0", "7.4"),
                span(41, "12-7654321", "255.0", "464.0", "50.0", "7.4"),
                span(42, "2,100.00", "360.0", "464.0", "34.0", "7.4"),
                span(43, "5,250.00", "470.0", "464.0", "34.0", "7.4"));
    }

    private static List<SpanRef> entityRowThree() {
        return List.of(
                span(50, "CEDAR TRUSTS LLC", "55.0", "484.0", "92.0", "7.4"),
                span(51, "47-9876543", "255.0", "484.0", "50.0", "7.4"),
                span(52, "3,300.00", "360.0", "484.0", "34.0", "7.4"),
                span(53, "7,800.00", "470.0", "484.0", "34.0", "7.4"));
    }

    /** The region end, carrying the COLUMN TOTAL inside the income column. */
    private static List<SpanRef> endAnchor() {
        return List.of(
                span(60, "Total", "40.0", "505.0", "22.0", "7.0"),
                span(61, "partnership", "65.0", "505.0", "50.0", "7.0"),
                span(62, "and", "118.0", "505.0", "16.0", "7.0"),
                span(63, "S", "137.0", "505.0", "6.0", "7.0"),
                span(64, "corporation", "146.0", "505.0", "50.0", "7.0"),
                span(65, "55,200.00", "468.0", "505.0", "38.0", "7.0"));
    }

    @SafeVarargs
    private static PageContent page(int packagePageIndex, List<SpanRef>... lines) {
        List<SpanRef> spans = new ArrayList<>();
        for (List<SpanRef> line : lines) {
            spans.addAll(line);
        }
        return new PageContent(UUID.randomUUID(), packagePageIndex, List.copyOf(spans), List.of());
    }

    /** Part II as the real form prints it: TWO caption bands over three entity rows. */
    private static PageContent twoCaptionBands() {
        return page(
                0,
                startAnchor(),
                captionBandOne(),
                captionBandTwo(),
                entityRowOne(),
                entityRowTwo(),
                entityRowThree(),
                endAnchor());
    }

    /** The same line printed on a LATER page: identical geometry, span ids shifted by 1000. */
    private static List<SpanRef> reprint(List<SpanRef> line) {
        return line.stream()
                .map(span -> span(span.id() + 1000, span.text(), span.box()))
                .toList();
    }

    private static SpanRef span(long id, String text, Box box) {
        return new SpanRef(id, text, box, BigDecimal.ONE);
    }

    /** A later page whose entity rows are DIFFERENT partnerships under the same captions. */
    private static PageContent otherEntitiesPage(int index) {
        return page(
                index,
                reprint(startAnchor()),
                reprint(captionBandOne()),
                reprint(captionBandTwo()),
                List.of(
                        span(2030, "DELTA FARMS LP", "55.0", "444.0", "80.0", "7.4"),
                        span(2031, "22-2222222", "255.0", "444.0", "50.0", "7.4"),
                        span(2032, "9,100.00", "360.0", "444.0", "34.0", "7.4"),
                        span(2033, "6,600.00", "470.0", "444.0", "34.0", "7.4")),
                reprint(endAnchor()));
    }

    // ── schema builders ──────────────────────────────────────────────────────

    private static GroupSpec partTwoRows() {
        return GroupSpec.row(
                new GroupRegionSpec(
                        new LabelSpec(
                                AnchorKind.LITERAL,
                                "Income or Loss From Partnerships and S Corporations"),
                        new LabelSpec(AnchorKind.LITERAL, "Total partnership and S corporation")),
                20);
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
        return new FieldSpec(name, type, false, normalizer, false, List.of(rung), partTwoRows());
    }

    /** The four Part II fields of {@code schedule_e@1.0.0}, in schema order. */
    private static SchemaDefinition partTwoSchema() {
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
                                rowCell("(d) Employer ID", EIN)),
                        grouped(
                                "partnershipNonpassiveLoss",
                                DataType.MONEY,
                                "money",
                                rowCell("(h) Nonpassive loss", MONEY)),
                        grouped(
                                "partnershipNonpassiveIncome",
                                DataType.MONEY,
                                "money",
                                rowCell("(j) Nonpassive income", MONEY))));
    }

    // ── reading the outcomes the way a consumer does ─────────────────────────

    private static List<String> keysOf(List<FieldOutcome> outcomes, String field) {
        return outcomes.stream()
                .filter(outcome -> outcome.field().name().equals(field))
                .map(FieldOutcome::groupKey)
                .toList();
    }

    /** The consumer contract's own instruction: group by groupKey, read across field names. */
    private static Map<String, String> entityAt(List<FieldOutcome> outcomes, String groupKey) {
        Map<String, String> entity = new LinkedHashMap<>();
        for (FieldOutcome outcome : outcomes) {
            if (groupKey.equals(outcome.groupKey())) {
                entity.put(outcome.field().name(), outcome.displayedText());
            }
        }
        return entity;
    }

    private static Map<String, String> entity(String name, String ein, String loss, String income) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("partnershipName", name);
        expected.put("partnershipEin", ein);
        expected.put("partnershipNonpassiveLoss", loss);
        expected.put("partnershipNonpassiveIncome", income);
        return expected;
    }

    // ── the shared origin ────────────────────────────────────────────────────

    @Test
    void every_field_of_a_row_group_yields_the_same_key_set() {
        // The join key only joins if the key SETS agree. A name field counting from the first
        // caption band and a money field counting from the second differ by one whole row: the
        // name field opens with an occurrence for the caption band itself and runs one further.
        List<FieldOutcome> outcomes =
                engine.extract(partTwoSchema(), List.of(twoCaptionBands()));

        assertThat(keysOf(outcomes, "partnershipName"))
                .as("the name column counts its rows from the group's origin, not its own caption")
                .containsExactly("01", "02", "03");
        assertThat(keysOf(outcomes, "partnershipEin")).containsExactly("01", "02", "03");
        assertThat(keysOf(outcomes, "partnershipNonpassiveLoss"))
                .containsExactly("01", "02", "03");
        assertThat(keysOf(outcomes, "partnershipNonpassiveIncome"))
                .containsExactly("01", "02", "03");
    }

    @Test
    void each_group_key_pairs_one_entitys_name_with_that_same_entitys_money() {
        // THE defect this class exists for. Drifted origins report SUMMIT RIDGE's name beside
        // BOREAL's income — ROW_CELL, anchorStrength 0.9, spanConfidence 1.0, an evidence box on
        // a real printed number, and whose money it is is wrong. A wrong value is worse than a
        // missing one, and this one is wrong about whose money it is.
        List<FieldOutcome> outcomes =
                engine.extract(partTwoSchema(), List.of(twoCaptionBands()));

        assertThat(entityAt(outcomes, "02"))
                .containsExactlyInAnyOrderEntriesOf(
                        entity("BOREAL HOLDINGS INC", "12-7654321", "2,100.00", "5,250.00"));
        assertThat(entityAt(outcomes, "01"))
                .containsExactlyInAnyOrderEntriesOf(
                        entity("SUMMIT RIDGE PARTNERS", "84-1234567", "1,200.00", "42,150.00"));
        assertThat(entityAt(outcomes, "03"))
                .containsExactlyInAnyOrderEntriesOf(
                        entity("CEDAR TRUSTS LLC", "47-9876543", "3,300.00", "7,800.00"));
    }

    @Test
    void every_occurrence_sharing_a_key_is_evidenced_on_ONE_printed_row() {
        // Values can agree by coincidence; boxes cannot. Every field's occurrence "02" must point
        // at the same printed line of the form.
        List<FieldOutcome> outcomes =
                engine.extract(partTwoSchema(), List.of(twoCaptionBands()));

        for (Map.Entry<String, String> row :
                Map.of("01", "444.0", "02", "464.0", "03", "484.0").entrySet()) {
            assertThat(
                            outcomes.stream()
                                    .filter(outcome -> row.getKey().equals(outcome.groupKey()))
                                    .flatMap(outcome -> outcome.valueEvidence().stream())
                                    .map(evidence -> evidence.box().y().toPlainString())
                                    .distinct()
                                    .toList())
                    .as("occurrence %s is one printed row", row.getKey())
                    .containsExactly(row.getValue());
        }
    }

    @Test
    void the_second_caption_band_is_never_itself_a_row() {
        // The caption band lies BELOW the first band's bottom edge, so a field whose origin is
        // its own caption line reads the captions as entity 01 — an occurrence for a row nobody
        // printed, which then shifts every real entity down one.
        List<FieldOutcome> outcomes =
                engine.extract(partTwoSchema(), List.of(twoCaptionBands()));

        assertThat(outcomes).hasSize(12);
        assertThat(outcomes)
                .as("three entities, four fields each — no caption row, no total row")
                .allSatisfy(outcome -> assertThat(outcome.found()).isTrue());
    }

    // ── page agreement ───────────────────────────────────────────────────────

    @Test
    void a_field_that_cannot_name_its_column_on_the_groups_page_goes_missing_not_to_another_page()
    {
        // The same corruption by another route. Page 1's entity captions failed to OCR, so the
        // name column cannot be located there; page 2 prints a DIFFERENT partnership under
        // complete captions. A per-field page choice would key page 2's DELTA FARMS as "01"
        // beside page 1's 42,150.00 — two documents' entities joined under one key. The group
        // agrees on ONE page, and the field that cannot read that page fails SAFE.
        PageContent captionsLost =
                page(
                        0,
                        startAnchor(),
                        captionBandTwo(),
                        entityRowOne(),
                        entityRowTwo(),
                        entityRowThree(),
                        endAnchor());
        PageContent later = otherEntitiesPage(1);

        List<FieldOutcome> outcomes =
                engine.extract(partTwoSchema(), List.of(captionsLost, later));

        assertThat(keysOf(outcomes, "partnershipName"))
                .as("no column on the group's page is a review-me, never another page's rows")
                .containsExactly((String) null);
        assertThat(outcomes.stream()
                        .filter(outcome -> outcome.field().name().equals("partnershipName"))
                        .toList())
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.found()).isFalse());
        assertThat(keysOf(outcomes, "partnershipNonpassiveIncome"))
                .containsExactly("01", "02", "03");
        assertThat(entityAt(outcomes, "01"))
                .as("nothing from page 2 is joined to page 1's first row")
                .doesNotContainValue("DELTA FARMS LP");
        assertThat(
                        outcomes.stream()
                                .filter(FieldOutcome::found)
                                .map(FieldOutcome::pageId)
                                .distinct()
                                .toList())
                .as("every occurrence of the group came from the one page the group agreed on")
                .containsExactly(captionsLost.pageId());
    }
}
