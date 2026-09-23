package com.pragmaticds.docengine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.ai.PaystubReconciler.Check;
import com.pragmaticds.docengine.ai.PaystubReconciler.Result;
import com.pragmaticds.docengine.ai.PaystubReconciler.Status;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Confidence;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;
import com.pragmaticds.docengine.platform.ai.PaystubExtraction;
import com.pragmaticds.docengine.platform.ai.PaystubExtraction.DeductionLine;
import com.pragmaticds.docengine.platform.ai.PaystubExtraction.EarningLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The trust gate for AI-read paystubs. Every case here exists to pin one of two contracts: a value
 * only earns trust from arithmetic that CLOSED, and a stub that could not be reconciled is
 * UNPROVEN — never quietly treated as either proven or wrong.
 */
class PaystubReconcilerTest {

    private final PaystubReconciler reconciler = new PaystubReconciler();

    @Test
    void aStubThatBalancesReconciles() {
        Result result = reconciler.reconcile(cleanStub());

        assertThat(result.status()).isEqualTo(Status.RECONCILED);
        assertThat(result.currentNetIdentity()).isEqualTo(Check.PASS);
        assertThat(result.ytdNetIdentity()).isEqualTo(Check.PASS);
        assertThat(result.ytdCoversCurrent()).isEqualTo(Check.PASS);
        assertThat(result.earningsPartition()).isEqualTo(Check.PASS);
        assertThat(result.deductionsPartition()).isEqualTo(Check.PASS);
        assertThat(result.earningsLineProducts()).isEqualTo(Check.PASS);
        assertThat(result.periodCoherence()).isEqualTo(Check.PASS);
    }

    @Test
    void aPennyOfPayrollRoundingStillReconciles() {
        Result result = reconciler.reconcile(withNetPay(cleanStub(), "1850.26"));

        assertThat(result.status()).isEqualTo(Status.RECONCILED);
        assertThat(result.currentNetIdentity()).isEqualTo(Check.PASS);
    }

    @Test
    void aRealDiscrepancyContradictsAndProvesNothing() {
        Result result = reconciler.reconcile(withNetPay(cleanStub(), "1750.25"));

        assertThat(result.status()).isEqualTo(Status.CONTRADICTED);
        assertThat(result.currentNetIdentity()).isEqualTo(Check.CONTRADICTED);
        assertThat(result.reconciled()).isFalse();
    }

    @Test
    void aYearToDateBelowTheCurrentPeriodIsImpossibleAndContradicts() {
        PaystubExtraction stub = cleanStub();
        Result result =
                reconciler.reconcile(
                        rebuild(stub, money("100.00"), stub.ytdTotalDeductions(), stub.ytdNetPay()));

        assertThat(result.status()).isEqualTo(Status.CONTRADICTED);
        assertThat(result.ytdCoversCurrent()).isEqualTo(Check.CONTRADICTED);
    }

    @Test
    void aStubWithOnlySomeFieldsExtractedIsUnprovenNotContradicted() {
        PaystubExtraction sparse =
                new PaystubExtraction(
                        text("Alex Q. Sample"),
                        text("Example Widgets LLC"),
                        null,
                        null,
                        null,
                        null,
                        money("2400.00"),
                        null,
                        money("1850.25"),
                        null);

        Result result = reconciler.reconcile(sparse);

        assertThat(result.status()).isEqualTo(Status.UNPROVEN);
        assertThat(result.reconciled()).isFalse();
        assertThat(result.currentNetIdentity()).isEqualTo(Check.UNPROVEN);
        assertThat(result.earningsPartition()).isEqualTo(Check.NOT_CLAIMED);
        assertThat(result.deductionsPartition()).isEqualTo(Check.NOT_CLAIMED);
        assertThat(result.earningsLineProducts()).isEqualTo(Check.NOT_CLAIMED);
        assertThat(result.periodCoherence()).isEqualTo(Check.NOT_CLAIMED);
        assertThat(result.ytdCoversCurrent()).isEqualTo(Check.NOT_CLAIMED);
    }

    /**
     * The case {@code PaystubTypeProfile}'s javadoc has always named: gross minus ONE extracted
     * deduction proves nothing, because nothing says that deduction is the whole of them. Federal
     * withholding must never be silently promoted into the role of total deductions.
     */
    @Test
    void aSingleNamedDeductionCannotProveNet() {
        PaystubExtraction stub =
                new PaystubExtraction(
                        text("Alex Q. Sample"),
                        text("Example Widgets LLC"),
                        date("2026-04-01"),
                        date("2026-04-14"),
                        date("2026-04-17"),
                        text("BIWEEKLY"),
                        money("2400.00"),
                        money("19200.00"),
                        money("1850.25"),
                        money("321.09"));

        Result result = reconciler.reconcile(stub);

        assertThat(result.status()).isEqualTo(Status.UNPROVEN);
        assertThat(result.currentNetIdentity()).isEqualTo(Check.UNPROVEN);
        assertThat(result.periodCoherence()).isEqualTo(Check.PASS);
    }

    @Test
    void completeDeductionLinesProveNetWithoutAStatedTotal() {
        PaystubExtraction stub = cleanStub();
        PaystubExtraction noStatedTotal =
                new PaystubExtraction(
                        stub.borrowerName(),
                        stub.employerName(),
                        stub.payPeriodStart(),
                        stub.payPeriodEnd(),
                        stub.payDate(),
                        stub.payFrequency(),
                        stub.currentGrossPay(),
                        stub.ytdGrossPay(),
                        stub.netPay(),
                        stub.federalWithholding(),
                        null,
                        stub.ytdTotalDeductions(),
                        stub.ytdNetPay(),
                        stub.earnings(),
                        stub.deductions());

        Result result = reconciler.reconcile(noStatedTotal);

        assertThat(result.status()).isEqualTo(Status.RECONCILED);
        assertThat(result.currentNetIdentity()).isEqualTo(Check.PASS);
        // Nothing was STATED to partition the rows against — that is unfinished, not disproved.
        assertThat(result.deductionsPartition()).isEqualTo(Check.UNPROVEN);
    }

    @Test
    void earningsLinesThatDoNotSumToGrossContradict() {
        PaystubExtraction stub = cleanStub();
        PaystubExtraction changed =
                withEarnings(
                        stub,
                        List.of(
                                new EarningLine(
                                        text("Regular"),
                                        money("80.00"),
                                        money("25.00"),
                                        money("2000.00"),
                                        money("16000.00")),
                                new EarningLine(
                                        text("Overtime"),
                                        money("10.00"),
                                        money("37.50"),
                                        money("375.00"),
                                        money("3000.00"))));

        Result result = reconciler.reconcile(changed);

        assertThat(result.status()).isEqualTo(Status.CONTRADICTED);
        assertThat(result.earningsPartition()).isEqualTo(Check.CONTRADICTED);
    }

    @Test
    void deductionLinesThatDoNotSumToTheStatedTotalContradict() {
        PaystubExtraction changed =
                withDeductions(
                        cleanStub(),
                        List.of(
                                new DeductionLine(
                                        text("Federal Income Tax"),
                                        money("321.09"),
                                        money("2568.72"))));

        Result result = reconciler.reconcile(changed);

        assertThat(result.status()).isEqualTo(Status.CONTRADICTED);
        assertThat(result.deductionsPartition()).isEqualTo(Check.CONTRADICTED);
    }

    @Test
    void anEarningsLineWhoseHoursTimesRateDisagreesContradicts() {
        PaystubExtraction changed =
                withEarnings(
                        cleanStub(),
                        List.of(
                                new EarningLine(
                                        text("Regular"),
                                        money("80.00"),
                                        money("25.00"),
                                        money("2400.00"),
                                        money("19200.00"))));

        Result result = reconciler.reconcile(changed);

        assertThat(result.status()).isEqualTo(Status.CONTRADICTED);
        assertThat(result.earningsLineProducts()).isEqualTo(Check.CONTRADICTED);
    }

    /**
     * A rate printed to two decimals is a ROUNDED rate: the payroll system multiplied the full-
     * precision one. The line-product tolerance must absorb exactly that much and no more, or
     * every hourly stub in the country reads as contradicted.
     */
    @Test
    void aRatePrintedToFewerDecimalsThanItWasPaidStillPasses() {
        PaystubExtraction changed =
                withEarnings(
                        cleanStub(),
                        List.of(
                                new EarningLine(
                                        text("Regular"),
                                        money("37.25"),
                                        money("25.13"),
                                        money("936.26"),
                                        money("7490.08"))));

        Result result = reconciler.reconcile(changed);

        assertThat(result.earningsLineProducts()).isEqualTo(Check.PASS);
        // Rows that no longer sum to gross are a different failure; only the product is asserted.
        assertThat(result.earningsPartition()).isEqualTo(Check.CONTRADICTED);
    }

    @Test
    void anEarningsLineMissingItsAmountLeavesThePartitionUnprovenWithoutBlockingTheNetProof() {
        PaystubExtraction changed =
                withEarnings(
                        cleanStub(),
                        List.of(
                                new EarningLine(
                                        text("Regular"), money("80.00"), money("25.00"), null, null),
                                new EarningLine(
                                        text("Overtime"),
                                        money("10.00"),
                                        money("40.00"),
                                        money("400.00"),
                                        money("3200.00"))));

        Result result = reconciler.reconcile(changed);

        assertThat(result.earningsPartition()).isEqualTo(Check.UNPROVEN);
        // The net identity closed on its own inputs, so the stub is still reconciled: an
        // unfinished check withholds proof, it never withdraws proof another check earned.
        assertThat(result.status()).isEqualTo(Status.RECONCILED);
    }

    @Test
    void aSalariedLineWithNoHoursOrRateClaimsNoProduct() {
        PaystubExtraction changed =
                withEarnings(
                        cleanStub(),
                        List.of(
                                new EarningLine(
                                        text("Salary"),
                                        null,
                                        null,
                                        money("2400.00"),
                                        money("19200.00"))));

        Result result = reconciler.reconcile(changed);

        assertThat(result.earningsLineProducts()).isEqualTo(Check.NOT_CLAIMED);
        assertThat(result.status()).isEqualTo(Status.RECONCILED);
    }

    @Test
    void aPeriodThatEndsBeforeItBeganContradicts() {
        PaystubExtraction stub = cleanStub();
        PaystubExtraction changed =
                withDates(stub, date("2026-04-20"), date("2026-04-14"), date("2026-04-17"));

        Result result = reconciler.reconcile(changed);

        assertThat(result.status()).isEqualTo(Status.CONTRADICTED);
        assertThat(result.periodCoherence()).isEqualTo(Check.CONTRADICTED);
    }

    @Test
    void aPayDateBeforeThePeriodEndsContradicts() {
        PaystubExtraction changed =
                withDates(
                        cleanStub(), date("2026-04-01"), date("2026-04-14"), date("2026-04-10"));

        Result result = reconciler.reconcile(changed);

        assertThat(result.periodCoherence()).isEqualTo(Check.CONTRADICTED);
    }

    @Test
    void onlyOnePeriodDateLeavesCoherenceUnproven() {
        PaystubExtraction changed = withDates(cleanStub(), date("2026-04-01"), null, null);

        Result result = reconciler.reconcile(changed);

        assertThat(result.periodCoherence()).isEqualTo(Check.UNPROVEN);
        assertThat(result.status()).isEqualTo(Status.RECONCILED);
    }

    @Test
    void aYearToDateNetIdentityThatDoesNotCloseContradicts() {
        PaystubExtraction stub = cleanStub();
        Result result =
                reconciler.reconcile(
                        rebuild(
                                stub,
                                stub.ytdGrossPay(),
                                stub.ytdTotalDeductions(),
                                money("13000.00")));

        assertThat(result.status()).isEqualTo(Status.CONTRADICTED);
        assertThat(result.ytdNetIdentity()).isEqualTo(Check.CONTRADICTED);
        assertThat(result.currentNetIdentity()).isEqualTo(Check.PASS);
    }

    @Test
    void aStubWithNoYearToDateColumnClaimsNoYearToDateChecks() {
        PaystubExtraction stub = cleanStub();
        PaystubExtraction changed =
                new PaystubExtraction(
                        stub.borrowerName(),
                        stub.employerName(),
                        stub.payPeriodStart(),
                        stub.payPeriodEnd(),
                        stub.payDate(),
                        stub.payFrequency(),
                        stub.currentGrossPay(),
                        null,
                        stub.netPay(),
                        stub.federalWithholding(),
                        stub.currentTotalDeductions(),
                        null,
                        null,
                        List.of(
                                new EarningLine(
                                        text("Regular"),
                                        money("80.00"),
                                        money("25.00"),
                                        money("2000.00"),
                                        null),
                                new EarningLine(
                                        text("Overtime"),
                                        money("10.00"),
                                        money("40.00"),
                                        money("400.00"),
                                        null)),
                        List.of(
                                new DeductionLine(text("Federal Income Tax"), money("321.09"), null),
                                new DeductionLine(text("Social Security"), money("148.80"), null),
                                new DeductionLine(text("Medicare"), money("34.80"), null),
                                new DeductionLine(text("401k"), money("45.06"), null)));

        Result result = reconciler.reconcile(changed);

        assertThat(result.ytdNetIdentity()).isEqualTo(Check.NOT_CLAIMED);
        assertThat(result.ytdCoversCurrent()).isEqualTo(Check.NOT_CLAIMED);
        assertThat(result.status()).isEqualTo(Status.RECONCILED);
    }

    @Test
    void nothingAtAllIsUnproven() {
        Result result = reconciler.reconcile(null);

        assertThat(result.status()).isEqualTo(Status.UNPROVEN);
        assertThat(result.reconciled()).isFalse();
        assertThat(result.currentNetIdentity()).isEqualTo(Check.UNPROVEN);
    }

    /**
     * Deductions printed as negatives are the same claim with the opposite sign convention; the
     * identity must not be read as gross PLUS deductions because a vendor bracketed its column.
     */
    @Test
    void deductionsPrintedAsNegativesReconcileTheSameWay() {
        PaystubExtraction stub = cleanStub();
        PaystubExtraction changed =
                new PaystubExtraction(
                        stub.borrowerName(),
                        stub.employerName(),
                        stub.payPeriodStart(),
                        stub.payPeriodEnd(),
                        stub.payDate(),
                        stub.payFrequency(),
                        stub.currentGrossPay(),
                        stub.ytdGrossPay(),
                        stub.netPay(),
                        stub.federalWithholding(),
                        money("-549.75"),
                        money("-4398.00"),
                        stub.ytdNetPay(),
                        stub.earnings(),
                        List.of(
                                new DeductionLine(
                                        text("Federal Income Tax"),
                                        money("-321.09"),
                                        money("-2568.72")),
                                new DeductionLine(
                                        text("Social Security"),
                                        money("-148.80"),
                                        money("-1190.40")),
                                new DeductionLine(
                                        text("Medicare"), money("-34.80"), money("-278.40")),
                                new DeductionLine(
                                        text("401k"), money("-45.06"), money("-360.48"))));

        Result result = reconciler.reconcile(changed);

        assertThat(result.status()).isEqualTo(Status.RECONCILED);
        assertThat(result.currentNetIdentity()).isEqualTo(Check.PASS);
        assertThat(result.deductionsPartition()).isEqualTo(Check.PASS);
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A synthetic bi-weekly stub that balances exactly: 2400.00 gross, 549.75 deductions, 1850.25
     * net, with a YTD column at eight times the period and earnings/deduction tables that
     * partition both totals.
     */
    private static PaystubExtraction cleanStub() {
        return new PaystubExtraction(
                text("Alex Q. Sample"),
                text("Example Widgets LLC"),
                date("2026-04-01"),
                date("2026-04-14"),
                date("2026-04-17"),
                text("BIWEEKLY"),
                money("2400.00"),
                money("19200.00"),
                money("1850.25"),
                money("321.09"),
                money("549.75"),
                money("4398.00"),
                money("14802.00"),
                List.of(
                        new EarningLine(
                                text("Regular"),
                                money("80.00"),
                                money("25.00"),
                                money("2000.00"),
                                money("16000.00")),
                        new EarningLine(
                                text("Overtime"),
                                money("10.00"),
                                money("40.00"),
                                money("400.00"),
                                money("3200.00"))),
                List.of(
                        new DeductionLine(
                                text("Federal Income Tax"), money("321.09"), money("2568.72")),
                        new DeductionLine(
                                text("Social Security"), money("148.80"), money("1190.40")),
                        new DeductionLine(text("Medicare"), money("34.80"), money("278.40")),
                        new DeductionLine(text("401k"), money("45.06"), money("360.48"))));
    }

    private static PaystubExtraction withNetPay(PaystubExtraction stub, String net) {
        return new PaystubExtraction(
                stub.borrowerName(),
                stub.employerName(),
                stub.payPeriodStart(),
                stub.payPeriodEnd(),
                stub.payDate(),
                stub.payFrequency(),
                stub.currentGrossPay(),
                stub.ytdGrossPay(),
                money(net),
                stub.federalWithholding(),
                stub.currentTotalDeductions(),
                stub.ytdTotalDeductions(),
                stub.ytdNetPay(),
                stub.earnings(),
                stub.deductions());
    }

    private static PaystubExtraction rebuild(
            PaystubExtraction stub,
            MoneyCell ytdGross,
            MoneyCell ytdDeductions,
            MoneyCell ytdNet) {
        return new PaystubExtraction(
                stub.borrowerName(),
                stub.employerName(),
                stub.payPeriodStart(),
                stub.payPeriodEnd(),
                stub.payDate(),
                stub.payFrequency(),
                stub.currentGrossPay(),
                ytdGross,
                stub.netPay(),
                stub.federalWithholding(),
                stub.currentTotalDeductions(),
                ytdDeductions,
                ytdNet,
                stub.earnings(),
                stub.deductions());
    }

    private static PaystubExtraction withEarnings(
            PaystubExtraction stub, List<EarningLine> earnings) {
        return new PaystubExtraction(
                stub.borrowerName(),
                stub.employerName(),
                stub.payPeriodStart(),
                stub.payPeriodEnd(),
                stub.payDate(),
                stub.payFrequency(),
                stub.currentGrossPay(),
                stub.ytdGrossPay(),
                stub.netPay(),
                stub.federalWithholding(),
                stub.currentTotalDeductions(),
                stub.ytdTotalDeductions(),
                stub.ytdNetPay(),
                earnings,
                stub.deductions());
    }

    private static PaystubExtraction withDeductions(
            PaystubExtraction stub, List<DeductionLine> deductions) {
        return new PaystubExtraction(
                stub.borrowerName(),
                stub.employerName(),
                stub.payPeriodStart(),
                stub.payPeriodEnd(),
                stub.payDate(),
                stub.payFrequency(),
                stub.currentGrossPay(),
                stub.ytdGrossPay(),
                stub.netPay(),
                stub.federalWithholding(),
                stub.currentTotalDeductions(),
                stub.ytdTotalDeductions(),
                stub.ytdNetPay(),
                stub.earnings(),
                deductions);
    }

    private static PaystubExtraction withDates(
            PaystubExtraction stub, DateCell start, DateCell end, DateCell payDate) {
        return new PaystubExtraction(
                stub.borrowerName(),
                stub.employerName(),
                start,
                end,
                payDate,
                stub.payFrequency(),
                stub.currentGrossPay(),
                stub.ytdGrossPay(),
                stub.netPay(),
                stub.federalWithholding(),
                stub.currentTotalDeductions(),
                stub.ytdTotalDeductions(),
                stub.ytdNetPay(),
                stub.earnings(),
                stub.deductions());
    }

    private static TextCell text(String value) {
        return new TextCell(value, value, 1, Confidence.HIGH);
    }

    private static DateCell date(String iso) {
        return new DateCell(LocalDate.parse(iso), iso, 1, Confidence.HIGH);
    }

    private static MoneyCell money(String amount) {
        return new MoneyCell(new BigDecimal(amount), amount, 1, Confidence.HIGH);
    }
}
