package com.pragmaticds.docengine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Confidence;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Direction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Summary;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Txn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankStatementReconcilerTest {

    @Test
    void reconciles_ledger_partition_and_running_balances_as_one_trust_gate() {
        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(statement("200.00", "75.00", true));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.RECONCILED);
        assertThat(result.ledgerEquation()).isEqualTo(BankStatementReconciler.Check.PASS);
        assertThat(result.partition()).isEqualTo(BankStatementReconciler.Check.PASS);
        assertThat(result.runningBalances()).isEqualTo(BankStatementReconciler.Check.PASS);
    }

    @Test
    void a_partition_mismatch_fails_even_when_beginning_and_ending_balances_reconcile() {
        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(statement("199.99", "75.00", true));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.NON_RECONCILING);
        assertThat(result.ledgerEquation()).isEqualTo(BankStatementReconciler.Check.PASS);
        assertThat(result.partition()).isEqualTo(BankStatementReconciler.Check.FAIL);
    }

    @Test
    void an_inconsistent_running_balance_fails_the_ledger() {
        BankStatementExtraction source = statement("200.00", "75.00", true);
        Txn wrongSecondBalance =
                transaction("75.00", Direction.WITHDRAWAL, "1124.99");
        BankStatementExtraction changed =
                new BankStatementExtraction(
                        source.summary(),
                        List.of(source.transactions().get(0), wrongSecondBalance),
                        List.of());

        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(changed);

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.NON_RECONCILING);
        assertThat(result.runningBalances()).isEqualTo(BankStatementReconciler.Check.FAIL);
    }

    @Test
    void statements_without_a_running_balance_column_can_reconcile_on_the_two_core_checks() {
        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(statement("200.00", "75.00", false));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.RECONCILED);
        assertThat(result.runningBalances())
                .isEqualTo(BankStatementReconciler.Check.NOT_AVAILABLE);
    }

    @Test
    void partial_running_balance_coverage_requires_manual_review() {
        BankStatementExtraction source = statement("200.00", "75.00", true);
        Txn missingSecondBalance = transaction("75.00", Direction.WITHDRAWAL, null);
        BankStatementExtraction changed =
                new BankStatementExtraction(
                        source.summary(),
                        List.of(source.transactions().get(0), missingSecondBalance),
                        List.of());

        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(changed);

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.UNABLE_TO_VALIDATE);
        assertThat(result.runningBalances()).isEqualTo(BankStatementReconciler.Check.INCOMPLETE);
    }

    @Test
    void missing_required_totals_cannot_be_called_reconciled() {
        BankStatementExtraction source = statement("200.00", "75.00", true);
        Summary incomplete =
                new Summary(
                        source.summary().bankName(),
                        source.summary().accountHolderName(),
                        source.summary().accountHolderAddress(),
                        source.summary().accountNumber(),
                        source.summary().statementPeriodStart(),
                        source.summary().statementPeriodEnd(),
                        source.summary().beginningBalance(),
                        source.summary().endingBalance(),
                        null,
                        source.summary().totalWithdrawals());

        BankStatementReconciler.Result result =
                new BankStatementReconciler()
                        .reconcile(new BankStatementExtraction(incomplete, source.transactions(), List.of()));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.UNABLE_TO_VALIDATE);
        assertThat(result.partition()).isEqualTo(BankStatementReconciler.Check.INCOMPLETE);
    }

    @Test
    void direction_owns_the_sign_so_parenthesized_withdrawal_amounts_are_not_double_negated() {
        BankStatementExtraction source = statement("200.00", "75.00", true);
        Txn negativeWithdrawal =
                transaction("-75.00", Direction.WITHDRAWAL, "1125.00");
        BankStatementExtraction changed =
                new BankStatementExtraction(
                        source.summary(),
                        List.of(source.transactions().get(0), negativeWithdrawal),
                        List.of());

        assertThat(new BankStatementReconciler().reconcile(changed).status())
                .isEqualTo(BankStatementReconciler.Status.RECONCILED);
    }

    // ── the ONLINE ACTIVITY PRINT-OUT genre ─────────────────────────────────
    // It states no period, no opening balance and no totals, so the two period-closing checks
    // have nothing to close over. What it does print is a running balance beside every row, and
    // that column proves itself: consecutive balances must differ by the later row's signed
    // amount. Rows are listed NEWEST FIRST, which the reconciler establishes from the dates
    // rather than asking the model to re-sort.

    @Test
    void a_print_out_reconciles_on_its_running_balance_column_alone() {
        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(printOut("4812.33", newestFirst()));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.RECONCILED);
        // The closing check this genre supports: the stated present balance against the ledger's
        // own last running balance.
        assertThat(result.ledgerEquation()).isEqualTo(BankStatementReconciler.Check.PASS);
        assertThat(result.runningBalances()).isEqualTo(BankStatementReconciler.Check.PASS);
        // No totals are stated, so there is no partition to check — NOT a failure to complete one.
        assertThat(result.partition()).isEqualTo(BankStatementReconciler.Check.NOT_AVAILABLE);
    }

    @Test
    void the_same_rows_reconcile_whichever_way_the_document_sorts_them() {
        List<Txn> oldestFirst = new java.util.ArrayList<>(newestFirst());
        java.util.Collections.reverse(oldestFirst);

        assertThat(new BankStatementReconciler().reconcile(printOut("4812.33", oldestFirst)).status())
                .isEqualTo(BankStatementReconciler.Status.RECONCILED);
    }

    @Test
    void a_row_dropped_from_a_print_out_breaks_the_chain() {
        // The failure that matters on a genre with no totals to cross-foot: lose a row and the
        // neighbouring balances differ by two amounts instead of one. Nothing else would catch it.
        List<Txn> rows = newestFirst();
        List<Txn> missingMiddle = List.of(rows.get(0), rows.get(2), rows.get(3));

        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(printOut("4812.33", missingMiddle));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.NON_RECONCILING);
        assertThat(result.runningBalances()).isEqualTo(BankStatementReconciler.Check.FAIL);
    }

    @Test
    void a_print_out_whose_stated_balance_disagrees_with_its_ledger_does_not_reconcile() {
        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(printOut("9999.99", newestFirst()));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.NON_RECONCILING);
        assertThat(result.ledgerEquation()).isEqualTo(BankStatementReconciler.Check.FAIL);
    }

    @Test
    void a_print_out_with_no_balance_column_is_never_reconciled_on_no_evidence() {
        // Every check reports NOT_AVAILABLE here. Without the "at least one PASS" guard this is
        // exactly the input that would fall through to RECONCILED having proved nothing at all.
        List<Txn> withoutBalances =
                newestFirst().stream()
                        .map(txn -> new Txn(txn.date(), null, txn.amount(), null, txn.direction(), 1))
                        .toList();

        BankStatementReconciler.Result result =
                new BankStatementReconciler().reconcile(printOut("4812.33", withoutBalances));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.UNABLE_TO_VALIDATE);
        assertThat(result.runningBalances())
                .isEqualTo(BankStatementReconciler.Check.NOT_AVAILABLE);
    }

    @Test
    void a_single_row_print_out_has_nothing_to_chain_against() {
        BankStatementReconciler.Result result =
                new BankStatementReconciler()
                        .reconcile(printOut("4812.33", List.of(newestFirst().get(0))));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.UNABLE_TO_VALIDATE);
        assertThat(result.runningBalances()).isEqualTo(BankStatementReconciler.Check.INCOMPLETE);
    }

    @Test
    void a_statement_that_merely_lost_a_total_is_still_read_as_a_statement() {
        // The discriminator is an ALL test, and this is why: a statement missing ONE total still
        // claims a period ledger, so its partition stays INCOMPLETE — it must not be quietly
        // re-read as a genre that owes no totals and reconciled on the strength of that.
        BankStatementExtraction source = statement("200.00", "75.00", true);
        Summary lostOneTotal =
                new Summary(
                        null, null, null, null,
                        source.summary().statementPeriodStart(),
                        source.summary().statementPeriodEnd(),
                        source.summary().beginningBalance(),
                        source.summary().endingBalance(),
                        source.summary().totalDeposits(),
                        null);

        BankStatementReconciler.Result result =
                new BankStatementReconciler()
                        .reconcile(
                                new BankStatementExtraction(
                                        lostOneTotal, source.transactions(), List.of()));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.UNABLE_TO_VALIDATE);
        assertThat(result.partition()).isEqualTo(BankStatementReconciler.Check.INCOMPLETE);
    }

    @Test
    void a_print_out_reconciles_in_the_shape_a_REAL_extraction_arrives_in() {
        // The shape every other print-out test here missed, and CI caught. The output schema makes
        // all ten summary keys REQUIRED, so a model reporting nothing still emits the cell and
        // BankStatementExtractionParser.money builds a MoneyCell with a null AMOUNT inside rather
        // than returning a null cell. A discriminator testing the cell REFERENCE is therefore true
        // for every real extraction ever produced, which left the print-out arm dead code and made
        // this exact ledger report UNABLE_TO_VALIDATE on the golden eval run.
        Summary asParsed =
                new Summary(
                        emptyText(), emptyText(), emptyText(), emptyText(),
                        emptyDate(), emptyDate(),
                        emptyMoney(),
                        money("4812.33"),
                        emptyMoney(),
                        emptyMoney());

        BankStatementReconciler.Result result =
                new BankStatementReconciler()
                        .reconcile(
                                new BankStatementExtraction(asParsed, newestFirst(), List.of()));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.RECONCILED);
        assertThat(result.partition()).isEqualTo(BankStatementReconciler.Check.NOT_AVAILABLE);
        assertThat(result.runningBalances()).isEqualTo(BankStatementReconciler.Check.PASS);
    }

    @Test
    void a_statement_that_lost_one_total_to_a_scan_is_still_read_as_a_statement_in_that_shape() {
        // The counterweight in the same shape: an EMPTY totals cell must not look like a genre
        // that owes no totals. It still claims a period, so its partition stays INCOMPLETE.
        BankStatementExtraction source = statement("200.00", "75.00", true);
        Summary lostATotal =
                new Summary(
                        emptyText(), emptyText(), emptyText(), emptyText(),
                        emptyDate(), emptyDate(),
                        source.summary().beginningBalance(),
                        source.summary().endingBalance(),
                        source.summary().totalDeposits(),
                        emptyMoney());

        BankStatementReconciler.Result result =
                new BankStatementReconciler()
                        .reconcile(
                                new BankStatementExtraction(
                                        lostATotal, source.transactions(), List.of()));

        assertThat(result.status()).isEqualTo(BankStatementReconciler.Status.UNABLE_TO_VALIDATE);
        assertThat(result.partition()).isEqualTo(BankStatementReconciler.Check.INCOMPLETE);
    }

    /** A cell the model emitted with nothing in it — what the parser builds for a null value. */
    private static MoneyCell emptyMoney() {
        return new MoneyCell(null, null, null, Confidence.LOW);
    }

    private static DateCell emptyDate() {
        return new DateCell(null, null, null, Confidence.LOW);
    }

    private static BankStatementExtraction.TextCell emptyText() {
        return new BankStatementExtraction.TextCell(null, null, null, Confidence.LOW);
    }

    /** The observed print-out's ledger: newest row first, a running balance on every row. */
    private static List<Txn> newestFirst() {
        return List.of(
                dated("54.20", Direction.WITHDRAWAL, "4812.33", "2026-08-24"),
                dated("500.00", Direction.WITHDRAWAL, "4866.53", "2026-08-22"),
                dated("2180.40", Direction.DEPOSIT, "5366.53", "2026-08-21"),
                dated("120.00", Direction.WITHDRAWAL, "3186.13", "2026-08-19"));
    }

    /** A print-out summary: a present balance, and nothing a closed period would state. */
    private static BankStatementExtraction printOut(String presentBalance, List<Txn> rows) {
        Summary summary =
                new Summary(
                        null, null, null, null, null, null,
                        null,
                        money(presentBalance),
                        null,
                        null);
        return new BankStatementExtraction(summary, rows, List.of());
    }

    private static Txn dated(String amount, Direction direction, String balance, String date) {
        return new Txn(
                new DateCell(LocalDate.parse(date), date, 1, Confidence.HIGH),
                null,
                money(amount),
                money(balance),
                direction,
                1);
    }

    private static BankStatementExtraction statement(
            String deposits, String withdrawals, boolean withBalances) {
        Summary summary =
                new Summary(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        money("1000.00"),
                        money("1125.00"),
                        money(deposits),
                        money(withdrawals));
        return new BankStatementExtraction(
                summary,
                List.of(
                        transaction("200.00", Direction.DEPOSIT, withBalances ? "1200.00" : null),
                        transaction(
                                "75.00",
                                Direction.WITHDRAWAL,
                                withBalances ? "1125.00" : null)),
                List.of());
    }

    private static Txn transaction(String amount, Direction direction, String balance) {
        return new Txn(null, null, money(amount), balance == null ? null : money(balance), direction, 1);
    }

    private static MoneyCell money(String value) {
        return new MoneyCell(new BigDecimal(value), "$" + value, 1, Confidence.HIGH);
    }
}
