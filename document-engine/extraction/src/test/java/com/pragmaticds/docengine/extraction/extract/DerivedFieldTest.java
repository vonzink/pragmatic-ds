package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.DerivationSpec;
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
 * The derivation pass: a field whose own rungs left it MISSING is filled from other captured
 * outcomes with {@code sum(plus) - sum(minus)} (spec 2026-09-23 §4). A captured rung is never
 * overridden, and any input lacking a normalized number leaves the field MISSING.
 */
class DerivedFieldTest {

    private static final String MONEY = "\\$?\\d{1,3}(?:,\\d{3})*\\.\\d{2}";
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
        return new SchemaDefinition("BANK_STATEMENT", "1.0.0", List.of(fields));
    }

    private static FieldSpec money(String name, DerivationSpec derivation, ExtractorSpec... rungs) {
        return new FieldSpec(name, DataType.MONEY, true, "money", false, List.of(rungs), null, derivation);
    }

    private static final DerivationSpec WITHDRAWALS =
            new DerivationSpec(List.of("beginningBalance", "totalDeposits"), List.of("endingBalance"));

    private static PageContent usBankSummary() {
        return page(
                span(1, "Beginning", "40", "100", "45", "9"), span(2, "Balance", "88", "100", "36", "9"),
                span(3, "4,233.17", "200", "100", "40", "9"),
                span(4, "Deposits", "40", "120", "40", "9"), span(5, "/", "84", "120", "4", "9"),
                span(6, "Credits", "92", "120", "34", "9"), span(7, "27,569.92", "200", "120", "48", "9"),
                span(8, "Ending", "40", "140", "32", "9"), span(9, "Balance", "76", "140", "36", "9"),
                span(10, "3,896.77", "200", "140", "40", "9"));
    }

    private static SchemaDefinition bank(FieldSpec withdrawals) {
        return schema(
                money("beginningBalance", null, anchor(0.9, "Beginning Balance", MONEY, 0, ValueScope.LINE_RIGHT)),
                money("totalDeposits", null, anchor(0.9, "Deposits / Credits", MONEY, 0, ValueScope.LINE_RIGHT)),
                money("endingBalance", null, anchor(0.9, "Ending Balance", MONEY, 0, ValueScope.LINE_RIGHT)),
                withdrawals);
    }

    private static FieldOutcome named(List<FieldOutcome> outcomes, String name) {
        return outcomes.stream().filter(o -> o.field().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void withdrawals_are_derived_from_the_balance_identity() {
        FieldSpec withdrawals = money("totalWithdrawals", WITHDRAWALS,
                anchor(0.9, "Total Withdrawals", MONEY, 0, ValueScope.LINE_RIGHT));
        FieldOutcome outcome = named(engine.extract(bank(withdrawals), List.of(usBankSummary())), "totalWithdrawals");
        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.DERIVED);
        assertThat(outcome.displayedText()).isEqualTo("27,906.32");
        assertThat(outcome.normalized().number()).isEqualByComparingTo("27906.32");
        assertThat(outcome.valueEvidence()).isEmpty();
        assertThat(outcome.labelEvidence()).isEmpty();
        assertThat(outcome.rawValue())
                .isEqualTo("beginningBalance=4233.17 + totalDeposits=27569.92 - endingBalance=3896.77");
        // Derived confidence is 0.9 x the weakest input: each input captures at overall 0.9000
        // (span confidence 1 x strength 0.9 x certainty 1), so 0.9 x 0.9 = 0.8100.
        assertThat(outcome.confidence().overall()).isEqualByComparingTo("0.8100");
    }

    @Test
    void a_captured_rung_is_never_overridden() {
        PageContent page = page(
                span(1, "Beginning", "40", "100", "45", "9"), span(2, "Balance", "88", "100", "36", "9"),
                span(3, "4,233.17", "200", "100", "40", "9"),
                span(4, "Deposits", "40", "120", "40", "9"), span(5, "/", "84", "120", "4", "9"),
                span(6, "Credits", "92", "120", "34", "9"), span(7, "27,569.92", "200", "120", "48", "9"),
                span(8, "Ending", "40", "140", "32", "9"), span(9, "Balance", "76", "140", "36", "9"),
                span(10, "3,896.77", "200", "140", "40", "9"),
                span(11, "Total", "40", "160", "28", "9"), span(12, "Withdrawals", "72", "160", "56", "9"),
                span(13, "$56,595.03", "200", "160", "52", "9"));
        FieldSpec withdrawals = money("totalWithdrawals", WITHDRAWALS,
                anchor(0.9, "Total Withdrawals", MONEY, 0, ValueScope.LINE_RIGHT));
        FieldOutcome outcome = named(engine.extract(bank(withdrawals), List.of(page)), "totalWithdrawals");
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.ANCHOR_LABEL);
        assertThat(outcome.displayedText()).isEqualTo("$56,595.03");
    }

    @Test
    void derivation_stays_missing_when_an_input_is_missing() {
        PageContent page = page(
                span(1, "Beginning", "40", "100", "45", "9"), span(2, "Balance", "88", "100", "36", "9"),
                span(3, "4,233.17", "200", "100", "40", "9"),
                span(8, "Ending", "40", "140", "32", "9"), span(9, "Balance", "76", "140", "36", "9"),
                span(10, "3,896.77", "200", "140", "40", "9"));
        FieldSpec withdrawals = money("totalWithdrawals", WITHDRAWALS,
                anchor(0.9, "Total Withdrawals", MONEY, 0, ValueScope.LINE_RIGHT));
        FieldOutcome outcome = named(engine.extract(bank(withdrawals), List.of(page)), "totalWithdrawals");
        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void a_negative_identity_leaves_the_field_missing() {
        // ending > beginning + deposits cannot happen on a real ledger: an input was misread, so
        // the field stays MISSING rather than stating a number a reviewer never looked at.
        PageContent page = page(
                span(1, "Beginning", "40", "100", "45", "9"), span(2, "Balance", "88", "100", "36", "9"),
                span(3, "1.00", "200", "100", "20", "9"),
                span(4, "Deposits", "40", "120", "40", "9"), span(5, "/", "84", "120", "4", "9"),
                span(6, "Credits", "92", "120", "34", "9"), span(7, "1.00", "200", "120", "20", "9"),
                span(8, "Ending", "40", "140", "32", "9"), span(9, "Balance", "76", "140", "36", "9"),
                span(10, "5.00", "200", "140", "20", "9"));
        FieldSpec withdrawals = money("totalWithdrawals", WITHDRAWALS,
                anchor(0.9, "Total Withdrawals", MONEY, 0, ValueScope.LINE_RIGHT));
        FieldOutcome outcome = named(engine.extract(bank(withdrawals), List.of(page)), "totalWithdrawals");
        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }
}
