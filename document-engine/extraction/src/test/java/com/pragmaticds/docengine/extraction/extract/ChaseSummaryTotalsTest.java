package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import com.pragmaticds.docengine.extraction.repo.ExtractionSchemaRepository;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ShippedBankStatementSeed;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The SHIPPED bank_statement seed against the summary block a real Chase checking statement
 * prints — five rows, not four.
 *
 * <p>The measured block reads Beginning Balance · Deposits and Additions · Checks Paid ·
 * Electronic Withdrawals · Ending Balance, and its own arithmetic settles what those captions
 * mean: {@code begin + deposits + checksPaid + electronic == ending} holds while {@code begin +
 * deposits + electronic == ending} does not. "Electronic Withdrawals" is therefore a CATEGORY
 * SUBTOTAL, and a totalWithdrawals rung anchored on it serves a number 1.6x–2.6x short of the
 * money that actually left the account (measured on two consecutive real statements) — at 0.9
 * confidence, with an evidence box pointing at a row that is genuinely printed, so nothing
 * downstream can tell it is wrong.
 *
 * <p>Chase prints no withdrawals TOTAL at all. The correct outcome is therefore MISSING: summing
 * the categories would be arithmetic invented by the extractor, and a category subtotal wearing
 * the total's name is the confident-wrong-value shape D5 forbids. Other products in the same
 * dialect widen the error by adding "ATM &amp; Debit Card Withdrawals" and "Fees" rows.
 */
@ExtendWith(MockitoExtension.class)
class ChaseSummaryTotalsTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Invented values in the real block's shape: the four identities below are the point. */
    private static final String BEGINNING = "9,214.55";

    private static final String DEPOSITS = "2,412.19";
    private static final String CHECKS_PAID = "-745.00";
    private static final String ELECTRONIC = "-1,834.02";
    private static final String ENDING = "9,047.72";

    @Mock private ExtractionSchemaRepository schemas;

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SchemaDefinition shipped() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                new ExtractionSchema(
                                        null,
                                        "BANK_STATEMENT",
                                        ShippedBankStatementSeed.VERSION,
                                        ShippedBankStatementSeed.DEFINITION,
                                        true)));
        return new ExtractionSchemaLoader(schemas)
                .activeSchemaFor("BANK_STATEMENT")
                .orElseThrow();
    }

    private static SpanRef span(long id, String text, double x, double y, double w) {
        return new SpanRef(
                id,
                text,
                new Box(
                        BigDecimal.valueOf(x),
                        BigDecimal.valueOf(y),
                        BigDecimal.valueOf(w),
                        BigDecimal.valueOf(9.0)),
                BigDecimal.ONE);
    }

    /** The five-row CHECKING SUMMARY, captions left, amounts right-aligned in one column. */
    private static PageContent fiveRowSummaryPage() {
        return new PageContent(
                UUID.randomUUID(),
                0,
                List.of(
                        span(1, "CHECKING", 39.6, 300.0, 48.0),
                        span(2, "SUMMARY", 91.6, 300.0, 44.0),
                        span(3, "Beginning", 39.6, 321.7, 40.0),
                        span(4, "Balance", 83.6, 321.7, 32.0),
                        span(5, BEGINNING, 326.1, 321.7, 40.0),
                        span(6, "Deposits", 39.6, 339.5, 34.0),
                        span(7, "and", 77.6, 339.5, 14.0),
                        span(8, "Additions", 95.6, 339.5, 36.0),
                        span(9, DEPOSITS, 326.1, 339.5, 36.0),
                        span(10, "Checks", 39.6, 357.3, 28.0),
                        span(11, "Paid", 71.6, 357.3, 16.0),
                        span(12, CHECKS_PAID, 326.1, 357.3, 32.0),
                        span(13, "Electronic", 39.6, 375.1, 40.0),
                        span(14, "Withdrawals", 83.6, 375.1, 48.0),
                        span(15, ELECTRONIC, 326.1, 375.1, 40.0),
                        span(16, "Ending", 39.6, 392.9, 26.0),
                        span(17, "Balance", 69.6, 392.9, 32.0),
                        span(18, ENDING, 326.1, 392.9, 40.0)),
                List.of());
    }

    private static BigDecimal amount(String printed) {
        return new BigDecimal(printed.replace(",", "").replace("$", ""));
    }

    private static FieldOutcome named(List<FieldOutcome> outcomes, String field) {
        return outcomes.stream()
                .filter(outcome -> outcome.field().name().equals(field))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + field));
    }

    // ── what the printed block actually means ────────────────────────────────

    @Test
    void the_summary_block_proves_electronic_withdrawals_is_not_the_total() {
        BigDecimal begin = amount(BEGINNING);
        BigDecimal deposits = amount(DEPOSITS);
        BigDecimal checks = amount(CHECKS_PAID);
        BigDecimal electronic = amount(ELECTRONIC);
        BigDecimal ending = amount(ENDING);

        assertThat(begin.add(deposits).add(checks).add(electronic))
                .as("the five printed rows balance: every withdrawal category counts")
                .isEqualByComparingTo(ending);
        assertThat(begin.add(deposits).add(electronic))
                .as("drop Checks Paid and the block no longer balances — so Electronic"
                        + " Withdrawals is a CATEGORY, not the total")
                .isNotEqualByComparingTo(ending);
        assertThat(checks.signum()).as("the dropped category is nonzero").isNotZero();
    }

    // ── the shipped seed's answer ────────────────────────────────────────────

    @Test
    void the_shipped_seed_derives_the_withdrawals_total_on_this_dialect() {
        // bank_statement@1.7.0 (V54): Chase prints no withdrawals total, so the schema-declared
        // balance identity fills it — begin + deposits - ending, the WHOLE debit side
        // (checks paid + electronic), never the "Electronic Withdrawals" category subtotal.
        List<FieldOutcome> outcomes = engine.extract(shipped(), List.of(fiveRowSummaryPage()));

        FieldOutcome withdrawals = named(outcomes, "totalWithdrawals");
        assertThat(withdrawals.method()).isEqualTo(ExtractionMethod.DERIVED);
        assertThat(withdrawals.displayedText())
                .as("9,214.55 + 2,412.19 - 9,047.72 = 745.00 + 1,834.02")
                .isEqualTo("2,579.02");
    }

    @Test
    void the_shipped_seed_still_answers_for_the_totals_the_block_does_print() {
        List<FieldOutcome> outcomes = engine.extract(shipped(), List.of(fiveRowSummaryPage()));

        assertThat(named(outcomes, "beginningBalance").displayedText()).isEqualTo(BEGINNING);
        assertThat(named(outcomes, "endingBalance").displayedText()).isEqualTo(ENDING);
        assertThat(named(outcomes, "totalDeposits").displayedText())
                .as("'Deposits and Additions' IS the whole credit side: the identity above"
                        + " balances with it as the only deposit row")
                .isEqualTo(DEPOSITS);
    }

    // ── the retired rung, pinned so it cannot quietly return ─────────────────

    @Test
    void the_retired_electronic_withdrawals_rung_is_what_bound_the_category_subtotal() {
        FieldSpec retired =
                new FieldSpec(
                        "totalWithdrawals",
                        DataType.MONEY,
                        true,
                        "money",
                        false,
                        List.of(
                                new ExtractorSpec(
                                        ExtractionMethod.ANCHOR_LABEL,
                                        0.9,
                                        new LabelSpec(AnchorKind.LITERAL, "Electronic Withdrawals"),
                                        null,
                                        new ValueSpec(
                                                "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)"
                                                        + "\\.\\d{2}(?!\\d)",
                                                0,
                                                ValueScope.LINE_RIGHT),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)),
                        null);
        SchemaDefinition v18 =
                new SchemaDefinition("BANK_STATEMENT", "1.1.0", List.of(retired));

        List<FieldOutcome> outcomes = engine.extract(v18, List.of(fiveRowSummaryPage()));

        assertThat(outcomes.get(0).displayedText())
                .as("the defect: a real, well-evidenced row — that is not the total")
                .isEqualTo(ELECTRONIC);
        assertThat(amount(ELECTRONIC).abs())
                .as("and it understates the money that actually left the account")
                .isLessThan(amount(CHECKS_PAID).add(amount(ELECTRONIC)).abs());
    }
}
