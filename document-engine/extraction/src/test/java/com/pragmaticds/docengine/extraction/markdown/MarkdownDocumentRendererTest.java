package com.pragmaticds.docengine.extraction.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.web.DocumentFieldsView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.ConfidenceComponentsView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.EvidenceView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.FieldView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.NormalizedView;
import com.pragmaticds.docengine.extraction.web.EffectiveStatus;
import com.pragmaticds.docengine.extraction.web.TextProvenanceView;
import com.pragmaticds.docengine.platform.pii.MaskableValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The renderer's rules, exercised as a pure function — no database, no Spring, no fixture pipeline.
 * The committed golden ({@code DocumentMarkdownApiIT}) proves the whole document; this class proves
 * each rule in isolation so a failure names the rule that broke.
 */
class MarkdownDocumentRendererTest {

    private static final UUID PACKAGE_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID PAGE_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");

    /** What a found occurrence reads as unless a test says otherwise — the ordinary case. */
    private static final TextProvenanceView NATIVE_TEXT = new TextProvenanceView("NATIVE", null);

    @Test
    void a_column_group_reads_ACROSS_the_way_the_form_prints_it() {
        String rendered =
                render(
                        found("rentsReceived", "A", "COLUMN", "MONEY", "44,400", new BigDecimal("44400")),
                        found("rentsReceived", "B", "COLUMN", "MONEY", "29,700", new BigDecimal("29700")),
                        missing("rentsReceived", "C", "COLUMN"));

        assertThat(rendered)
                .as("the keys are the COLUMNS and the field is one row")
                .contains("| Field | A | B | C |")
                .contains(
                        "| `rentsReceived` | 44,400 = 44400 (0.9000, p1, NATIVE) |"
                                + " 29,700 = 29700 (0.9000, p1, NATIVE) | — missing (review) |");
    }

    @Test
    void a_row_group_reads_DOWN_one_row_per_occurrence_key() {
        String rendered =
                render(
                        found("partnershipName", "A", "ROW", "STRING", "SUMMIT RIDGE LP", null),
                        missing("partnershipName", "B", "ROW"));

        assertThat(rendered)
                .contains("| Key | `partnershipName` |")
                .contains("| A | SUMMIT RIDGE LP (0.9000, p1, NATIVE) |")
                .contains("| B | — missing (review) |");
    }

    /**
     * paystub@1.5.0's earnings lines (V53): five {@code earning*} fields under one ROW group,
     * two-digit ordinal keys, rendered as ONE table titled from the words the members share — the
     * shape the suite's income analyzer reads a stub's base line from. A line that prints no
     * hours carries an explicit MISSING occurrence for that cell (the AI stage writes one, as
     * the deterministic engine does for a located row's blank cell), and the cell says missing
     * rather than inventing a zero. Clusters partition by EXACT key sequence, so that occurrence
     * is what keeps the group rectangular and the table whole.
     */
    @Test
    void paystub_earnings_lines_render_as_one_earnings_table_keyed_by_row_ordinal() {
        String rendered =
                render(
                        found("earningDescription", "01", "ROW", "STRING", "Regular", null),
                        found("earningHours", "01", "ROW", "MONEY", "80.00", new BigDecimal("80.00")),
                        found(
                                "earningYtdAmount",
                                "01",
                                "ROW",
                                "MONEY",
                                "46,000.00",
                                new BigDecimal("46000.00")),
                        found("earningDescription", "02", "ROW", "STRING", "Overtime", null),
                        missing("earningHours", "02", "ROW"),
                        found(
                                "earningYtdAmount",
                                "02",
                                "ROW",
                                "MONEY",
                                "1,000.00",
                                new BigDecimal("1000.00")),
                        found("ytdGrossPay", null, "NONE", "MONEY", "52,000.00", new BigDecimal("52000.00")));

        assertThat(rendered)
                .contains("## Earning 01–02 (2) — ROW group")
                .contains("| Key | `earningDescription` | `earningHours` | `earningYtdAmount` |")
                .contains("| 01 | Regular (0.9000, p1, NATIVE) | 80.00 = 80 (0.9000, p1, NATIVE) |")
                .contains("| 02 | Overtime (0.9000, p1, NATIVE) | — missing (review) |")
                // One table, not an "Earning" table beside an "Earning Hours" one.
                .doesNotContain("Earning Hours")
                // The total stays a document-level field: the table is the breakdown, not a
                // replacement for it.
                .contains("| `ytdGrossPay` |")
                .doesNotContain("Region not read");
        // The rows read DOWN in printed order, 01 before 02.
        assertThat(rendered.indexOf("| 01 | Regular")).isLessThan(rendered.indexOf("| 02 | Overtime"));
    }

    /**
     * The failure mode the explicit MISSING cell exists to prevent, pinned so it cannot creep
     * back: a ROW group whose members do not all carry every key is TWO clusters. A writer that
     * skipped a line's empty cell would split the earnings table exactly like this.
     */
    @Test
    void a_ragged_row_group_splits_into_one_table_per_key_sequence() {
        String rendered =
                render(
                        found("earningDescription", "01", "ROW", "STRING", "Regular", null),
                        found("earningHours", "01", "ROW", "MONEY", "80.00", new BigDecimal("80.00")),
                        found("earningDescription", "02", "ROW", "STRING", "Overtime", null));

        assertThat(rendered)
                .contains("## Earning Description 01–02 (2) — ROW group")
                .contains("## Earning Hours 01 (1) — ROW group")
                .doesNotContain("## Earning 01–02");
    }

    /**
     * The T9 assertion. A grouped field whose region was never located carries no key, and that is
     * a statement about the TABLE. It must not appear as a document-level field — a reviewer
     * reading it there would conclude "this field is empty" where the truth is "this table was
     * never read".
     */
    @Test
    void a_null_keyed_grouped_field_is_a_callout_and_NEVER_a_document_field_row() {
        String rendered =
                render(
                        found("taxYear", null, "NONE", "STRING", "2025", null),
                        missing("remicExcessInclusion", null, "ROW"));

        assertThat(rendered)
                .as("the callout section exists at all — without it the field has nowhere to go"
                        + " but the document-field table, which is the lie this rule forbids")
                .contains("## Region not read")
                .contains("> **`remicExcessInclusion`** — the engine located no readable ROW group")
                .contains("| remicExcessInclusion | ∅ | ROW | MISSING — region not read |");

        String documentFields =
                rendered.substring(
                        rendered.indexOf("## Document fields"), rendered.indexOf("## Region not read"));
        assertThat(documentFields)
                .as("the ungrouped table carries taxYear and nothing else")
                .contains("| `taxYear` |")
                .doesNotContain("remicExcessInclusion");
    }

    @Test
    void a_sensitive_value_renders_MASKED_and_says_so_on_its_label() {
        String rendered =
                render(sensitive("taxpayerSsn", "987-65-4321"));

        assertThat(rendered)
                .as("the raw value never reaches the bytes, in any arm")
                .doesNotContain("987-65-4321");
        assertThat(rendered)
                .contains("| `taxpayerSsn` (sensitive, masked) | •••-••-4321 |")
                .as("the normalized arm is masked too, not just the displayed text")
                .contains("| taxpayerSsn | ∅ | NONE | FOUND | •••-••-4321 | •••-••-4321 |");
    }

    @Test
    void the_appendix_carries_all_three_confidence_components_and_the_page() {
        String rendered = render(found("taxYear", null, "NONE", "STRING", "2025", null));

        assertThat(rendered)
                .contains("| taxYear | ∅ | NONE | FOUND | 2025 | 2025 | ANCHOR_LABEL | NATIVE |"
                        + " 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |");
    }

    @Test
    void a_missing_occurrence_reports_zero_confidence_with_no_components_and_no_page() {
        String rendered = render(missing("rentsReceived", "C", "COLUMN"));

        assertThat(rendered)
                .contains("| rentsReceived | C | COLUMN | MISSING | — | — | NONE | UNKNOWN |"
                        + " 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |");
    }

    @Test
    void rendering_twice_yields_identical_bytes_and_ends_in_exactly_one_newline() {
        MarkdownDocumentSource source =
                source(
                        found("rentsReceived", "A", "COLUMN", "MONEY", "44,400", new BigDecimal("44400")),
                        found("partnershipName", "A", "ROW", "STRING", "SUMMIT RIDGE LP", null),
                        found("taxYear", null, "NONE", "STRING", "2025", null));

        assertThat(MarkdownDocumentRenderer.render(source))
                .isEqualTo(MarkdownDocumentRenderer.render(source));
        assertThat(MarkdownDocumentRenderer.render(source))
                .doesNotContain("\r")
                .endsWith("0-based.\n");
        assertThat(MarkdownDocumentRenderer.render(source)).doesNotEndWith("\n\n");
    }

    @Test
    void a_pipe_in_a_value_is_escaped_so_it_cannot_end_the_cell() {
        String rendered = render(found("taxpayerName", null, "NONE", "STRING", "A | B", null));

        assertThat(rendered).contains("| `taxpayerName` | A \\| B |");
    }

    /**
     * Clusters partition by KIND as well as key sequence, so a COLUMN group and a ROW group that
     * happen to share an alphabet do not merge into one nonsensical table.
     */
    @Test
    void two_groups_sharing_a_key_alphabet_stay_two_tables_when_their_kinds_differ() {
        String rendered =
                render(
                        found("rentsReceived", "A", "COLUMN", "MONEY", "44,400", new BigDecimal("44400")),
                        found("partnershipName", "A", "ROW", "STRING", "SUMMIT RIDGE LP", null));

        assertThat(rendered)
                // Two headings, two tables — and the section order is by first member field name,
                // which is total. A single-member cluster labels itself with its own humanised
                // name; "Group" is only the fallback for members that share no leading word.
                .contains("## Partnership Name A (1) — ROW group")
                .contains("## Rents Received A (1) — COLUMN group");
        assertThat(rendered.indexOf("## Partnership Name"))
                .as("clusters order by first member field name, code-point ascending")
                .isLessThan(rendered.indexOf("## Rents Received"));
    }

    /** Members that share a leading camel word title the section with it; otherwise "Group". */
    @Test
    void a_cluster_titles_itself_from_the_leading_words_its_members_share() {
        String sharedPrefix =
                render(
                        found("estateOrTrustName", "A", "ROW", "STRING", "O'BRIEN TRUST", null),
                        found("estateOrTrustOtherIncome", "A", "ROW", "MONEY", "9,850",
                                new BigDecimal("9850")));
        assertThat(sharedPrefix).contains("## Estate Or Trust A (1) — ROW group");

        String noSharedPrefix =
                render(
                        found("incomeOrLoss", "A", "COLUMN", "MONEY", "12,860", new BigDecimal("12860")),
                        found("rentsReceived", "A", "COLUMN", "MONEY", "44,400", new BigDecimal("44400")));
        assertThat(noSharedPrefix).contains("## Group A (1) — COLUMN group");
    }

    /**
     * An OCR'd value NAMES ITS ENGINE, everywhere it appears. A reviewer who can see that a number
     * was recognised from pixels rather than read from a text layer knows to look at it twice; one
     * who cannot see it treats a guess and a fact identically, which is the whole complaint this
     * column answers.
     */
    @Test
    void an_ocr_value_names_its_engine_in_the_table_and_in_the_appendix() {
        String rendered =
                render(
                        withProvenance(
                                found("taxYear", null, "NONE", "STRING", "2025", null),
                                new TextProvenanceView("OCR", "RAPIDOCR")),
                        withProvenance(
                                found("rentsReceived", "A", "COLUMN", "MONEY", "44,400",
                                        new BigDecimal("44400")),
                                new TextProvenanceView("OCR", "TESSERACT")));

        assertThat(rendered)
                .as("the ungrouped table's Text column names the engine")
                .contains("| `taxYear` | 2025 | OCR RAPIDOCR | 0.9000 | 1 |")
                .as("...and so does the cluster cell's parenthetical")
                .contains("| `rentsReceived` | 44,400 = 44400 (0.9000, p1, OCR TESSERACT) |")
                .as("...and the appendix, which is the row a consumer diffs against the JSON")
                .contains("| taxYear | ∅ | NONE | FOUND | 2025 | 2025 | ANCHOR_LABEL |"
                        + " OCR RAPIDOCR | 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |");
    }

    /**
     * A value whose characters came from BOTH a text layer and OCR says MIXED rather than picking
     * a side. Picking one would be a coin-flip dressed as a fact: call it NATIVE and the OCR'd half
     * stops being reviewed, call it OCR and the reviewer distrusts characters the document itself
     * declared. The engine is still named, because the OCR'd half is the half that needs it.
     */
    @Test
    void a_value_spanning_both_sources_reports_MIXED_and_still_names_the_engine() {
        String rendered =
                render(
                        withProvenance(
                                found("taxYear", null, "NONE", "STRING", "2025", null),
                                new TextProvenanceView("MIXED", "RAPIDOCR")));

        assertThat(rendered)
                .contains("| `taxYear` | 2025 | MIXED RAPIDOCR | 0.9000 | 1 |")
                .doesNotContain("| `taxYear` | 2025 | NATIVE |");
    }

    /** Two engines reconciled into one value name both, joined and sorted — never just the first. */
    @Test
    void a_value_reconciled_from_two_engines_names_both() {
        String rendered =
                render(
                        withProvenance(
                                found("taxYear", null, "NONE", "STRING", "2025", null),
                                new TextProvenanceView("OCR", "RAPIDOCR+TESSERACT")));

        assertThat(rendered).contains("| `taxYear` | 2025 | OCR RAPIDOCR+TESSERACT | 0.9000 | 1 |");
    }

    /**
     * Provenance is NOT a fourth confidence component, and the rendering must not let anyone read
     * it as one: the components parenthetical stays exactly three factors whose product is the
     * score, and the Text column sits in its own column outside it.
     */
    @Test
    void provenance_never_leaks_into_the_confidence_components_parenthetical() {
        String rendered =
                render(
                        withProvenance(
                                found("taxYear", null, "NONE", "STRING", "2025", null),
                                new TextProvenanceView("OCR", "RAPIDOCR")));

        assertThat(rendered)
                .as("still three factors, still multiplying back to the score")
                .contains("0.9000 (1 · 0.9 · 1)")
                .doesNotContain("· OCR")
                .doesNotContain("OCR ·");
    }

    /**
     * §8.3 / D11, the Markdown half: a REJECTED occurrence's text is WITHHELD. This surface is a
     * copy-paste/LLM artifact with no machine-readable contract, so the only binding form of "do
     * not use this value" is absence — strike-through would still hand the refused characters to
     * every text consumer. The machine facts (method, confidence, page, provenance) stay printed:
     * they are true statements about the parse, and the reviewer deciding whether the rejection
     * stands needs them.
     */
    @Test
    void a_rejected_occurrence_renders_a_rejected_cell_and_never_its_text() {
        String rendered =
                render(
                        withEffectiveStatus(
                                found("taxYear", null, "NONE", "STRING", "2025", null),
                                EffectiveStatus.REJECTED),
                        withEffectiveStatus(
                                found("rentsReceived", "A", "COLUMN", "MONEY", "44,400",
                                        new BigDecimal("44400")),
                                EffectiveStatus.REJECTED));

        assertThat(rendered)
                .as("the ungrouped value cell states the refusal, not the value")
                .contains("| `taxYear` | — rejected (review) | NATIVE | 0.9000 | 1 |")
                .as("...and so does the cluster cell, with no parenthetical to leak into")
                .contains("| `rentsReceived` | — rejected (review) |")
                .as("the reader is told what the verdict words mean")
                .contains("Status `REJECTED` means a reviewer refused the served value");
        assertThat(rendered)
                .as("the refused strings are byte-absent from the WHOLE rendering, appendix included")
                .doesNotContain("2025")
                .doesNotContain("44,400")
                .doesNotContain("44400");
    }

    @Test
    void a_rejected_occurrence_appendix_row_says_rejected_with_dashed_values() {
        String rendered =
                render(
                        withEffectiveStatus(
                                found("taxYear", null, "NONE", "STRING", "2025", null),
                                EffectiveStatus.REJECTED));

        assertThat(rendered)
                .as("Status REJECTED, Value and Normalized dashed, machine Method/Confidence kept")
                .contains("| taxYear | ∅ | NONE | REJECTED | — | — | ANCHOR_LABEL | NATIVE |"
                        + " 0.9000 (1 · 0.9 · 1) | NOT_VALIDATED | 1 |");
    }

    /**
     * §8.5 / §11.2, the Markdown half: "missing" is a statement about the MACHINE's parse, so a
     * machine-missing occurrence a human then FILLED renders the human's value — the missing cell
     * would misreport a value that exists on the read model this rendering projects.
     */
    @Test
    void a_corrected_missing_occurrence_renders_its_corrected_value() {
        String rendered =
                render(
                        found("rentsReceived", "A", "COLUMN", "MONEY", "44,400",
                                new BigDecimal("44400")),
                        correctedMissing("rentsReceived", "C", "COLUMN", "1,234",
                                new BigDecimal("1234")));

        assertThat(rendered)
                .as("the human-filled cell renders the value with the machine's own zero score")
                .contains("1,234 = 1234 (0.0000, UNKNOWN)")
                .as("...and its appendix row says CORRECTED with the machine facts intact")
                .contains("| rentsReceived | C | COLUMN | CORRECTED | 1,234 | 1234 | NONE |"
                        + " UNKNOWN | 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |");
        assertThat(rendered).doesNotContain("— missing (review)");
    }

    /** The no-decision path is 1.1.0's, byte for byte below the contract line. */
    @Test
    void a_machine_missing_occurrence_still_renders_missing() {
        String rendered = render(missing("rentsReceived", "C", "COLUMN"));

        assertThat(rendered)
                .contains("| — missing (review) |")
                .contains("| rentsReceived | C | COLUMN | MISSING | — | — | NONE | UNKNOWN |"
                        + " 0.0000 (—) | MANUAL_REVIEW_REQUIRED | — |");
        assertThat(rendered)
                .as("a rendering with no review verdicts spends not one byte on them — the legend"
                        + " sentence appears only when a verdict word can appear")
                .doesNotContain("REJECTED")
                .doesNotContain("rejected")
                .doesNotContain("Status `REJECTED`");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static String render(FieldView... fields) {
        return MarkdownDocumentRenderer.render(source(fields));
    }

    /** The same occurrence, re-stamped with a provenance other than the fixtures' NATIVE default. */
    private static FieldView withProvenance(FieldView field, TextProvenanceView provenance) {
        return new FieldView(
                field.id(),
                field.fieldName(),
                field.groupKey(),
                field.groupKind(),
                provenance,
                field.dataType(),
                field.displayedText(),
                field.rawValue(),
                field.normalized(),
                field.extractionMethod(),
                field.extractorVersion(),
                field.confidence(),
                field.confidenceComponents(),
                field.validationStatus(),
                field.reviewStatus(),
                field.effectiveStatus(),
                field.sensitive(),
                field.evidence());
    }

    /** The same occurrence, re-stamped with a review verdict on its served value. */
    private static FieldView withEffectiveStatus(FieldView field, String effectiveStatus) {
        return new FieldView(
                field.id(),
                field.fieldName(),
                field.groupKey(),
                field.groupKind(),
                field.textProvenance(),
                field.dataType(),
                field.displayedText(),
                field.rawValue(),
                field.normalized(),
                field.extractionMethod(),
                field.extractorVersion(),
                field.confidence(),
                field.confidenceComponents(),
                field.validationStatus(),
                field.reviewStatus(),
                effectiveStatus,
                field.sensitive(),
                field.evidence());
    }

    private static MarkdownDocumentSource source(FieldView... fields) {
        return new MarkdownDocumentSource(
                PACKAGE_ID,
                0,
                new DocumentFieldsView(DOCUMENT_ID, "SCHEDULE_E", "1.0.1", List.of(fields)),
                List.of(
                        new MarkdownDocumentSource.PageClassification(
                                1, "SCHEDULE_E", new BigDecimal("0.95"), "1.0.0")));
    }

    private static FieldView found(
            String name,
            String key,
            String kind,
            String dataType,
            String displayed,
            BigDecimal number) {
        return new FieldView(
                UUID.randomUUID(),
                name,
                key,
                kind,
                NATIVE_TEXT,
                dataType,
                MaskableValue.of(displayed, false),
                MaskableValue.of(displayed, false),
                new NormalizedView(
                        MaskableValue.of(number == null ? displayed : null, false),
                        MaskableValue.of(number, false),
                        MaskableValue.of(null, false)),
                "ANCHOR_LABEL",
                "engine/1.0.0",
                new BigDecimal("0.9000"),
                new ConfidenceComponentsView(
                        new BigDecimal("1.0"), new BigDecimal("0.9"), BigDecimal.ONE),
                "NOT_VALIDATED",
                "NOT_REVIEWED",
                EffectiveStatus.MACHINE,
                false,
                List.of(valueBox()));
    }

    private static FieldView sensitive(String name, String raw) {
        return new FieldView(
                UUID.randomUUID(),
                name,
                null,
                "NONE",
                NATIVE_TEXT,
                "STRING",
                MaskableValue.of(raw, true),
                MaskableValue.of(raw, true),
                new NormalizedView(
                        MaskableValue.of(raw, true),
                        MaskableValue.of(null, true),
                        MaskableValue.of(null, true)),
                "LABEL_BELOW",
                "engine/1.0.0",
                new BigDecimal("0.9000"),
                new ConfidenceComponentsView(
                        new BigDecimal("1.0"), new BigDecimal("0.9"), BigDecimal.ONE),
                "NOT_VALIDATED",
                "NOT_REVIEWED",
                EffectiveStatus.MACHINE,
                true,
                List.of(valueBox()));
    }

    private static FieldView missing(String name, String key, String kind) {
        return new FieldView(
                UUID.randomUUID(),
                name,
                key,
                kind,
                // A missing occurrence cites no span, so there is nothing to have a provenance —
                // and UNKNOWN says exactly that rather than defaulting to the comfortable answer.
                TextProvenanceView.UNKNOWN_PROVENANCE,
                "MONEY",
                MaskableValue.of(null, false),
                MaskableValue.of(null, false),
                new NormalizedView(
                        MaskableValue.of(null, false),
                        MaskableValue.of(null, false),
                        MaskableValue.of(null, false)),
                "NONE",
                "engine/1.0.0",
                BigDecimal.ZERO,
                null,
                "MANUAL_REVIEW_REQUIRED",
                "NOT_REVIEWED",
                EffectiveStatus.MACHINE,
                false,
                List.of());
    }

    /**
     * What the read model serves after a human CORRECTs a machine-MISSING occurrence (§11.2): the
     * human's displayed/normalized value beside the machine's own facts — method {@code NONE},
     * confidence 0, no components, no evidence, no provenance.
     */
    private static FieldView correctedMissing(
            String name, String key, String kind, String value, BigDecimal number) {
        return new FieldView(
                UUID.randomUUID(),
                name,
                key,
                kind,
                TextProvenanceView.UNKNOWN_PROVENANCE,
                "MONEY",
                MaskableValue.of(value, false),
                MaskableValue.of(null, false),
                new NormalizedView(
                        MaskableValue.of(null, false),
                        MaskableValue.of(number, false),
                        MaskableValue.of(null, false)),
                "NONE",
                "engine/1.0.0",
                BigDecimal.ZERO,
                null,
                "MANUAL_REVIEW_REQUIRED",
                "CORRECTED",
                EffectiveStatus.CORRECTED,
                false,
                List.of());
    }

    private static EvidenceView valueBox() {
        return new EvidenceView(
                "VALUE",
                0,
                PAGE_ID,
                0,
                new BigDecimal("10.00"),
                new BigDecimal("20.00"),
                new BigDecimal("30.00"),
                new BigDecimal("10.00"),
                1L,
                null);
    }
}
