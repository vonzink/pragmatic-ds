package com.pragmaticds.docengine.platform.ai;

import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;
import java.util.List;

/**
 * Strict typed representation of a validated paystub extraction. The first ten components mirror
 * the seeded PAYSTUB extraction schema (V7) exactly — an AI value lands on the deterministic field
 * row with the same name, so the two vocabularies must never drift.
 *
 * <p>The cell records are shared with {@link BankStatementExtraction} deliberately: one cell
 * vocabulary, one normalization discipline, one handwriting tag. They move together to a neutral
 * home when audit C1 relocates the type-specific extraction classes out of platform.
 *
 * <p>{@code payFrequency} is a {@link TextCell} whose {@code value} is the CANONICAL frequency
 * ({@code WEEKLY}, {@code BIWEEKLY}, {@code SEMIMONTHLY}, {@code MONTHLY}) or null when the
 * printed wording matched none — the parser canonicalizes with the same key-mangling rule the
 * deterministic {@code payFrequency} normalizer uses, so agreement/conflict against a
 * deterministic row compares like with like.
 *
 * <p><b>Why this record carries more than the ten schema fields.</b> Paystub layouts are
 * vendor-controlled and unbounded — ADP, Paychex, Gusto, Workday, Paylocity and every in-house
 * payroll print different labels — so no set of label rules can be trusted to READ a stub. What
 * can be trusted is that a stub BALANCES. The totals and the earnings/deduction lines below were
 * first extracted for exactly one purpose: to let {@code PaystubReconciler} prove the ten schema
 * values arithmetically, independently of who read them.
 *
 * <p>Since {@code paystub@1.5.0} (V53) the three totals and the EARNINGS lines are also persisted
 * as extracted fields — {@code currentTotalDeductions} / {@code ytdTotalDeductions} / {@code
 * ytdNetPay} as scalars, and each earnings row as one {@code earning*} occurrence per line under a
 * row-ordinal group key — because a consumer proving YTD consistency needs the BASE line, not the
 * total YTD gross, and the stage was already reading and proving the lines without ever writing
 * them down. The deductions table is still evidence only.
 *
 * @param currentTotalDeductions the current period's TOTAL deductions as the stub states it — the
 *     one number that closes {@code gross - deductions = net}. A single named deduction (federal
 *     withholding, say) is emphatically not this, and conflating them would fabricate a proof.
 * @param earnings the earnings table rows, in printed order; empty when none were read
 * @param deductions the deductions table rows, in printed order; empty when none were read
 */
public record PaystubExtraction(
        TextCell borrowerName,
        TextCell employerName,
        DateCell payPeriodStart,
        DateCell payPeriodEnd,
        DateCell payDate,
        TextCell payFrequency,
        MoneyCell currentGrossPay,
        MoneyCell ytdGrossPay,
        MoneyCell netPay,
        MoneyCell federalWithholding,
        MoneyCell currentTotalDeductions,
        MoneyCell ytdTotalDeductions,
        MoneyCell ytdNetPay,
        List<EarningLine> earnings,
        List<DeductionLine> deductions)
        implements AiStructuredExtraction {

    public PaystubExtraction {
        earnings = earnings == null ? List.of() : List.copyOf(earnings);
        deductions = deductions == null ? List.of() : List.copyOf(deductions);
    }

    /**
     * The pre-reconciliation shape: the ten schema fields alone, no totals and no lines. Kept so
     * callers that only ever cared about the persisted fields — fixtures, and any caller predating
     * the reconciler — construct a record that is honestly EMPTY of proof rather than being forced
     * to invent placeholder totals.
     */
    public PaystubExtraction(
            TextCell borrowerName,
            TextCell employerName,
            DateCell payPeriodStart,
            DateCell payPeriodEnd,
            DateCell payDate,
            TextCell payFrequency,
            MoneyCell currentGrossPay,
            MoneyCell ytdGrossPay,
            MoneyCell netPay,
            MoneyCell federalWithholding) {
        this(
                borrowerName,
                employerName,
                payPeriodStart,
                payPeriodEnd,
                payDate,
                payFrequency,
                currentGrossPay,
                ytdGrossPay,
                netPay,
                federalWithholding,
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    @Override
    public AiDocumentType documentType() {
        return AiDocumentType.PAYSTUB;
    }

    /**
     * One row of the earnings table.
     *
     * <p>{@code hours} and {@code rate} are {@link MoneyCell}s because that record is this
     * package's decimal cell, not because either is currency — reusing it keeps one normalization
     * discipline and one handwriting tag rather than growing a near-identical fourth cell type.
     */
    public record EarningLine(
            TextCell description,
            MoneyCell hours,
            MoneyCell rate,
            MoneyCell currentAmount,
            MoneyCell ytdAmount) {}

    /** One row of the deductions table. Deduction rows carry no hours or rate. */
    public record DeductionLine(
            TextCell description, MoneyCell currentAmount, MoneyCell ytdAmount) {}
}
