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
 * A rung tries every label occurrence on the page, in reading order, until one's scope captures.
 * A caption printed first as a table header with nothing beside it no longer starves the real
 * line below it on the same page (ANB Bank, 2026-09-22).
 */
class LabelOccurrencesTest {

    private static final String MONEY = "\\$?\\d{1,3}(?:,\\d{3})*\\.\\d{2}";

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

    private static FieldSpec field(String name, DataType type, String normalizer, ExtractorSpec... rungs) {
        return new FieldSpec(name, type, true, normalizer, false, List.of(rungs));
    }

    private static ExtractorSpec anchor(
            double strength, String label, String pattern, int occurrence, ValueScope scope) {
        return new ExtractorSpec(
                ExtractionMethod.ANCHOR_LABEL,
                strength,
                new LabelSpec(AnchorKind.LITERAL, label),
                null,
                new ValueSpec(pattern, occurrence, scope));
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("PAYSTUB", "1.0.0", List.of(fields));
    }

    private static FieldOutcome only(List<FieldOutcome> outcomes) {
        assertThat(outcomes).hasSize(1);
        return outcomes.get(0);
    }

    @Test
    void a_header_row_occurrence_no_longer_starves_the_real_line() {
        // ANB Bank page 1: "Account Type  Account Number  Ending Balance" is a table header
        // with nothing to its right; the real line is "07/31/2020 Ending Balance $128,491.21".
        PageContent page = page(
                span(1, "Account", "40", "300", "38", "9"),
                span(2, "Type", "82", "300", "22", "9"),
                span(3, "Ending", "200", "300", "32", "9"),
                span(4, "Balance", "236", "300", "38", "9"),
                span(5, "07/31/2020", "40", "340", "50", "9"),
                span(6, "Ending", "100", "340", "32", "9"),
                span(7, "Balance", "136", "340", "38", "9"),
                span(8, "$128,491.21", "260", "340", "60", "9"));
        FieldSpec ending = field("endingBalance", DataType.MONEY, "money",
                anchor(0.9, "Ending Balance", MONEY, 0, ValueScope.LINE_RIGHT));
        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));
        assertThat(outcome.displayedText()).isEqualTo("$128,491.21");
        assertThat(outcome.labelEvidence()).extracting(EvidenceRef::spanId)
                .containsExactly(6L, 7L);
    }

    @Test
    void the_third_label_occurrence_captures() {
        PageContent page = page(
                span(1, "Total", "40", "100", "28", "9"), span(2, "Due", "72", "100", "20", "9"),
                span(3, "Total", "40", "120", "28", "9"), span(4, "Due", "72", "120", "20", "9"),
                span(5, "Total", "40", "140", "28", "9"), span(6, "Due", "72", "140", "20", "9"),
                span(7, "$9.99", "120", "140", "28", "9"));
        FieldSpec due = field("totalAmountDue", DataType.MONEY, "money",
                anchor(0.9, "Total Due", MONEY, 0, ValueScope.LINE_RIGHT));
        assertThat(only(engine.extract(schema(due), List.of(page))).displayedText()).isEqualTo("$9.99");
    }

    @Test
    void the_first_occurrence_still_wins_when_it_captures() {
        PageContent page = page(
                span(1, "Ending", "40", "100", "32", "9"), span(2, "Balance", "76", "100", "38", "9"),
                span(3, "$1.00", "140", "100", "28", "9"),
                span(4, "Ending", "40", "120", "32", "9"), span(5, "Balance", "76", "120", "38", "9"),
                span(6, "$2.00", "140", "120", "28", "9"));
        FieldSpec ending = field("endingBalance", DataType.MONEY, "money",
                anchor(0.9, "Ending Balance", MONEY, 0, ValueScope.LINE_RIGHT));
        assertThat(only(engine.extract(schema(ending), List.of(page))).displayedText()).isEqualTo("$1.00");
    }

    @Test
    void no_capturing_occurrence_is_still_missing() {
        PageContent page = page(
                span(1, "Ending", "40", "100", "32", "9"), span(2, "Balance", "76", "100", "38", "9"),
                span(3, "Ending", "40", "120", "32", "9"), span(4, "Balance", "76", "120", "38", "9"));
        FieldSpec ending = field("endingBalance", DataType.MONEY, "money",
                anchor(0.9, "Ending Balance", MONEY, 0, ValueScope.LINE_RIGHT));
        assertThat(only(engine.extract(schema(ending), List.of(page))).found()).isFalse();
    }

    @Test
    void page_scope_tries_the_first_occurrence_only() {
        // PAGE scope reads the whole page whatever the occurrence, so the first label occurrence
        // is the only attempt: its label evidence stands, and the page's first match is the value.
        PageContent page = page(
                span(1, "Ending", "40", "100", "32", "9"), span(2, "Balance", "76", "100", "38", "9"),
                span(3, "Ending", "40", "120", "32", "9"), span(4, "Balance", "76", "120", "38", "9"),
                span(5, "$3.00", "140", "140", "28", "9"), span(6, "$4.00", "140", "160", "28", "9"));
        FieldSpec ending = field("endingBalance", DataType.MONEY, "money",
                anchor(0.9, "Ending Balance", MONEY, 0, ValueScope.PAGE));
        FieldOutcome outcome = only(engine.extract(schema(ending), List.of(page)));
        assertThat(outcome.displayedText()).isEqualTo("$3.00");
        assertThat(outcome.labelEvidence()).extracting(EvidenceRef::spanId).containsExactly(1L, 2L);
    }

    @Test
    void at_most_32_occurrences_are_tried() {
        // 40 captions with nothing beside them, then a capturing 41st: past the cap, so MISSING.
        List<SpanRef> spans = new ArrayList<>();
        long id = 1;
        for (int line = 0; line < 41; line++) {
            String y = String.valueOf(100 + 20 * line);
            spans.add(span(id++, "Total", "40", y, "28", "9"));
            spans.add(span(id++, "Due", "72", y, "20", "9"));
        }
        spans.add(span(id, "$9.99", "120", String.valueOf(100 + 20 * 40), "28", "9"));
        PageContent page = new PageContent(UUID.randomUUID(), 0, spans, List.of());
        FieldSpec due = field("totalAmountDue", DataType.MONEY, "money",
                anchor(0.9, "Total Due", MONEY, 0, ValueScope.LINE_RIGHT));
        assertThat(DefaultFieldExtractionEngine.MAX_LABEL_OCCURRENCES).isEqualTo(32);
        assertThat(only(engine.extract(schema(due), List.of(page))).found()).isFalse();
    }
}
