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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TYPOGRAPHIC PUNCTUATION. Real IRS and lender forms print {@code Employee’s} and
 * {@code Employer’s} with U+2019 RIGHT SINGLE QUOTATION MARK. Every rule-pack anchor and every
 * extraction-schema label in this repo is authored with the ASCII apostrophe U+0027, so those
 * captions never matched and the three apostrophe-captioned W-2 fields extracted nothing — a real
 * filled W-2 came back 8 of 10 while the synthetic fixture came back 10 of 10, because the fixture
 * was drawn from the same wrong belief as the schema. That is the box-grid defect's exact shape
 * repeated, and this class is the fixture that would have caught it.
 *
 * <p>The page is drawn TWICE from one builder — once with the ASCII apostrophe the old fixture
 * used, once with U+2019 the way a real form prints — and every test asserts the SAME outcome from
 * both. Widening what matches, never moving it: the ASCII spelling is not allowed to regress in
 * order to make the typographic one work.
 *
 * <p>Label and value patterns below are the shipped w2@1.1.0 strings from
 * {@code V12__box_grid_extraction.sql}, character for character. Geometry is Spec 4's, measured on
 * a real W-2: caption rows 24 pt apart, each value's top 12.5 pt below its caption's top, cells
 * beginning at x 36 / 330 / 453.
 */
class TypographicPunctuationExtractionTest {

    /** U+2019 RIGHT SINGLE QUOTATION MARK — what a real form prints. */
    private static final String CURLY = "’";

    /** U+0027 APOSTROPHE — what every schema label and pack anchor is authored with. */
    private static final String ASCII = "'";

    // The shipped w2@1.1.0 rungs, verbatim.
    private static final String NAME_LABEL = "Employee's first name and initial";
    private static final String NAME_VALUE =
            "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])";
    private static final String SSN_LABEL = "Employee's social security number";
    private static final String SSN_VALUE = "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)";
    private static final String EMPLOYER_LABEL = "Employer's name, address, and ZIP code";
    private static final String EMPLOYER_VALUE =
            "(?<![A-Za-z])[A-Z][A-Za-z&'-]*(?: [A-Z&][A-Za-z&'-]*){0,4}(?![A-Za-z])";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    // ── the page, drawn in either apostrophe ────────────────────────────────

    /**
     * A W-2 box grid, column 0 only: box a (SSN), box c (employer) and box e (employee name),
     * each caption over its own value. {@code apostrophe} is the character the captions print.
     */
    private static PageContent w2Column0(String apostrophe) {
        List<SpanRef> spans = new ArrayList<>();
        long id = 1;
        // Row 0 — box a. Caption top 100.0 (height 6.5, bottom 106.5); value top 112.5.
        id = caption(spans, id, 100.0, "a", "Employee" + apostrophe + "s", "social", "security",
                "number");
        spans.add(span(id++, "123-45-6789", "45.0", "112.5", "50.0", "7.4"));
        // Row 2 — box c. Two full rows below, so box a's rung cannot reach it.
        id = caption(spans, id, 148.0, "c", "Employer" + apostrophe + "s", "name,", "address,",
                "and", "ZIP", "code");
        spans.add(span(id++, "ACME", "45.0", "160.5", "24.0", "7.4"));
        spans.add(span(id++, "WIDGETS", "72.0", "160.5", "40.0", "7.4"));
        spans.add(span(id++, "LLC", "115.0", "160.5", "18.0", "7.4"));
        // Row 5 — box e.
        id = caption(spans, id, 220.0, "e", "Employee" + apostrophe + "s", "first", "name", "and",
                "initial");
        spans.add(span(id++, "Jordan", "45.0", "232.5", "30.0", "7.4"));
        spans.add(span(id++, "Q.", "78.0", "232.5", "10.0", "7.4"));
        spans.add(span(id, "Fixture", "91.0", "232.5", "32.0", "7.4"));
        return new PageContent(UUID.randomUUID(), 0, List.copyOf(spans), List.of());
    }

    /** One caption line at x 42, 7 pt words with a 3 pt gap — a real form's box caption. */
    private static long caption(List<SpanRef> spans, long id, double top, String... words) {
        double x = 42.0;
        for (String word : words) {
            double width = 4.0 * word.length();
            spans.add(
                    span(
                            id++,
                            word,
                            String.valueOf(x),
                            String.valueOf(top),
                            String.valueOf(width),
                            "6.5"));
            x += width + 3.0;
        }
        return id;
    }

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                BigDecimal.ONE);
    }

    private static ExtractorSpec below(String label, String value) {
        return new ExtractorSpec(
                ExtractionMethod.LABEL_BELOW,
                0.9,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(value, 0, ValueScope.LINE),
                null,
                null,
                null,
                24.0,
                0.5);
    }

    private static FieldSpec field(String name, String normalizer, ExtractorSpec rung) {
        return new FieldSpec(name, DataType.STRING, true, normalizer, false, List.of(rung));
    }

    private static SchemaDefinition w2Schema() {
        return new SchemaDefinition(
                "W2",
                "1.1.0",
                List.of(
                        field("employeeSsn", null, below(SSN_LABEL, SSN_VALUE)),
                        field("employerName", null, below(EMPLOYER_LABEL, EMPLOYER_VALUE)),
                        field("employeeName", "personName", below(NAME_LABEL, NAME_VALUE))));
    }

    private static List<String> capturedText(List<FieldOutcome> outcomes) {
        return outcomes.stream().map(FieldOutcome::displayedText).toList();
    }

    // ── THE REGRESSION ──────────────────────────────────────────────────────

    @Test
    void the_three_apostrophe_captioned_w2_fields_extract_from_a_form_printed_with_U2019() {
        // Before the punctuation fold this was the whole defect: three MISSING fields on a page
        // whose only difference from the passing fixture is one character per caption. The real
        // form scored 1.00 at classification and extracted 8 of 10.
        List<FieldOutcome> outcomes = engine.extract(w2Schema(), List.of(w2Column0(CURLY)));

        assertThat(capturedText(outcomes))
                .as("employeeSsn, employerName, employeeName from a U+2019 form")
                .containsExactly("123-45-6789", "ACME WIDGETS LLC", "Jordan Q. Fixture");
        assertThat(outcomes)
                .allSatisfy(
                        outcome -> {
                            assertThat(outcome.found()).isTrue();
                            assertThat(outcome.method()).isEqualTo(ExtractionMethod.LABEL_BELOW);
                            assertThat(outcome.labelEvidence()).isNotEmpty();
                            assertThat(outcome.valueEvidence()).isNotEmpty();
                        });
    }

    @Test
    void the_ascii_apostrophe_form_still_extracts_all_three() {
        // The fold WIDENS what matches; it must not move it. The spelling the pre-existing
        // fixture uses keeps working, unchanged.
        List<FieldOutcome> outcomes = engine.extract(w2Schema(), List.of(w2Column0(ASCII)));

        assertThat(capturedText(outcomes))
                .containsExactly("123-45-6789", "ACME WIDGETS LLC", "Jordan Q. Fixture");
    }

    @Test
    void the_two_spellings_produce_identical_evidence_and_confidence() {
        // Same spans, same boxes, same three confidence components — the apostrophe changes
        // WHETHER the label is found, never WHAT is reported once it is.
        List<FieldOutcome> ascii = engine.extract(w2Schema(), List.of(w2Column0(ASCII)));
        List<FieldOutcome> curly = engine.extract(w2Schema(), List.of(w2Column0(CURLY)));

        assertThat(curly).hasSameSizeAs(ascii);
        for (int i = 0; i < ascii.size(); i++) {
            FieldOutcome a = ascii.get(i);
            FieldOutcome c = curly.get(i);
            assertThat(c.displayedText()).isEqualTo(a.displayedText());
            assertThat(c.rawValue()).isEqualTo(a.rawValue());
            assertThat(c.valueEvidence().stream().map(EvidenceRef::spanId).toList())
                    .as("%s VALUE evidence spans", a.field().name())
                    .isEqualTo(a.valueEvidence().stream().map(EvidenceRef::spanId).toList());
            assertThat(c.labelEvidence().stream().map(EvidenceRef::spanId).toList())
                    .as("%s LABEL evidence spans", a.field().name())
                    .isEqualTo(a.labelEvidence().stream().map(EvidenceRef::spanId).toList());
            assertThat(c.confidence()).isEqualTo(a.confidence());
        }
    }

    @Test
    void a_curly_apostrophe_in_the_captured_value_is_reported_as_the_form_printed_it() {
        // The fold decides what MATCHES; it never rewrites what is REPORTED. A company name
        // printed with U+2019 must reach the reviewer, the export and the DB byte-for-byte as
        // the document has it — the evidence box and the text must agree.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        List.of(
                                span(1, "c", "42.0", "100.0", "4.0", "6.5"),
                                span(2, "Employer" + CURLY + "s", "49.0", "100.0", "40.0", "6.5"),
                                span(3, "name,", "92.0", "100.0", "20.0", "6.5"),
                                span(4, "address,", "115.0", "100.0", "32.0", "6.5"),
                                span(5, "and", "150.0", "100.0", "12.0", "6.5"),
                                span(6, "ZIP", "165.0", "100.0", "12.0", "6.5"),
                                span(7, "code", "180.0", "100.0", "16.0", "6.5"),
                                span(8, "O" + CURLY + "HARA", "55.0", "112.5", "40.0", "7.4"),
                                span(9, "MILLWORK", "98.0", "112.5", "45.0", "7.4")),
                        List.of());
        FieldSpec employer = field("employerName", null, below(EMPLOYER_LABEL, EMPLOYER_VALUE));

        List<FieldOutcome> outcomes =
                engine.extract(new SchemaDefinition("W2", "1.1.0", List.of(employer)), List.of(page));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).displayedText())
                .as("the reported value keeps the document's own apostrophe")
                .isEqualTo("O" + CURLY + "HARA MILLWORK");
    }

    // ── the other punctuation real forms print ──────────────────────────────

    @Test
    void a_non_breaking_space_inside_a_caption_still_matches_the_authored_space() {
        // PDF producers emit U+00A0 to keep a caption from breaking across lines. Java's \s does
        // not match it either, so a value pattern spelled with \s fails on the same text.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        List.of(
                                span(1, "Employer identification", "42.0", "100.0", "70.0", "6.5"),
                                span(2, "number", "115.0", "100.0", "24.0", "6.5"),
                                span(3, "98-7654321", "45.0", "112.5", "45.0", "7.4")),
                        List.of());
        FieldSpec ein =
                field(
                        "employerEin",
                        null,
                        below("Employer identification number", "(?<!\\d)\\d{2}-\\d{7}(?!\\d)"));

        List<FieldOutcome> outcomes =
                engine.extract(new SchemaDefinition("W2", "1.1.0", List.of(ein)), List.of(page));

        assertThat(outcomes.get(0).displayedText()).isEqualTo("98-7654321");
    }

    @Test
    void a_non_breaking_hyphen_in_a_form_number_still_matches_the_authored_hyphen() {
        // IRS forms set "W-2" with U+2011 NON-BREAKING HYPHEN precisely so the form number never
        // breaks. Every dash in the fold table is a horizontal stroke a reader takes as a hyphen.
        // taxYear's shipped rung is the FLAT one — on a real W-2 the year is printed beside the
        // title, not in a box — so this reads LINE_RIGHT, as the seed does.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        List.of(
                                span(1, "Form", "42.0", "100.0", "20.0", "6.5"),
                                span(2, "W‑2", "65.0", "100.0", "16.0", "6.5"),
                                span(3, "2025", "90.0", "100.0", "20.0", "6.5")),
                        List.of());
        ExtractorSpec flat =
                new ExtractorSpec(
                        ExtractionMethod.ANCHOR_LABEL,
                        0.9,
                        new LabelSpec(AnchorKind.LITERAL, "Form W-2"),
                        null,
                        new ValueSpec("(?<!\\d)20\\d{2}(?!\\d)", 0, ValueScope.LINE_RIGHT));
        FieldSpec year = field("taxYear", null, flat);

        List<FieldOutcome> outcomes =
                engine.extract(new SchemaDefinition("W2", "1.1.0", List.of(year)), List.of(page));

        assertThat(outcomes.get(0).displayedText()).isEqualTo("2025");
    }

    // ── the fold must not manufacture a match ───────────────────────────────

    @Test
    void a_caption_that_genuinely_differs_still_goes_missing() {
        // The guard on the whole change: widening punctuation must not widen WORDS. A label that
        // is not on the page stays not on the page, and the field stays MISSING rather than
        // acquiring a confident wrong value with a plausible evidence box (design D5).
        PageContent page = w2Column0(CURLY);
        FieldSpec spouse =
                field("spouseName", "personName", below("Spouse's first name and initial",
                        NAME_VALUE));

        List<FieldOutcome> outcomes =
                engine.extract(new SchemaDefinition("W2", "1.1.0", List.of(spouse)), List.of(page));

        assertThat(outcomes.get(0).found()).isFalse();
        assertThat(outcomes.get(0).method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcomes.get(0).valueEvidence()).isEmpty();
    }
}
