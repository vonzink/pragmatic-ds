package com.pragmaticds.docengine.ai;

import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.PaystubExtraction;
import com.pragmaticds.docengine.platform.ai.PaystubExtraction.DeductionLine;
import com.pragmaticds.docengine.platform.ai.PaystubExtraction.EarningLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic arithmetic trust gate for one AI-read paystub.
 *
 * <p><b>Why this exists.</b> Paystub layouts are vendor-controlled and unbounded — ADP, Paychex,
 * Gusto, Workday, Paylocity and every in-house payroll print different labels for the same number
 * — so label-hunting cannot be made reliable and enumerating dialects is not a strategy. The AI
 * reader handles the dialects; this class is what makes trusting it defensible. A paystub BALANCES,
 * and a number that balances is verified independently of who read it. That, not the reader's own
 * self-report, is what a field's VALID status is allowed to rest on.
 *
 * <p><b>The contract a reviewer sees, and the one rule that governs it: a wrong value is worse
 * than a missing one.</b> Three outcomes, never two:
 *
 * <ul>
 *   <li>{@link Status#RECONCILED} — the current period's {@code gross - deductions = net} closed
 *       and nothing else disagreed. Only this promotes a field to VALID, and only for the fields
 *       that identity actually contains.
 *   <li>{@link Status#CONTRADICTED} — some identity the document itself asserts does NOT hold. The
 *       discrepancy is named in the per-check breakdown; no value is dropped, corrected or hidden,
 *       and nothing earns VALID.
 *   <li>{@link Status#UNPROVEN} — nothing disagreed, but nothing closed either. A stub where only
 *       some fields were read simply cannot be checked on that identity. That is not a failure and
 *       must never be reported as one; it is also not a pass, so it earns no trust and routes to a
 *       human.
 * </ul>
 *
 * <p>The same three words grade every individual check, with a fourth for the case where the
 * document makes no such claim AT ALL ({@link Check#NOT_CLAIMED} — a stub with no YTD column, a
 * salaried line with no hours or rate). Distinguishing "the model lost a number" ({@link
 * Check#UNPROVEN}) from "there was never such a number to lose" is the difference between a
 * reviewer chasing a bad read and a reviewer chasing nothing.
 *
 * <p><b>An unfinished check withholds proof; it never withdraws proof another check earned.</b>
 * Only a CONTRADICTED check can pull a verdict down, because only a CONTRADICTED check is evidence
 * of anything being wrong.
 */
final class PaystubReconciler {

    /**
     * How far two sides of a money identity may differ and still be believed: two cents.
     *
     * <p>Not a percentage — a percentage would make the tolerance grow with the paycheck, which
     * gets the failure mode exactly backwards, since a transposed digit on a large stub is the
     * error most worth catching. Two cents is chosen because each side of a paystub identity is
     * independently rounded to the cent by the payroll system: a total deduction is rounded once,
     * and the per-line withholdings that produced it were each rounded before being summed, so the
     * two can legitimately sit a cent apart, and two such roundings can compose. Anything larger
     * than that is not rounding, it is a misread.
     */
    static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.02");

    private static final BigDecimal HALF = new BigDecimal("0.5");

    Result reconcile(PaystubExtraction stub) {
        if (stub == null) {
            return Result.unproven();
        }

        BigDecimal currentDeductions =
                effectiveDeductions(stub.currentTotalDeductions(), currentDeductionAmounts(stub));
        BigDecimal ytdDeductions =
                effectiveDeductions(stub.ytdTotalDeductions(), ytdDeductionAmounts(stub));

        Check currentNetIdentity =
                netIdentity(amount(stub.currentGrossPay()), currentDeductions, amount(stub.netPay()));
        Check ytdNetIdentity =
                claimsYearToDate(stub)
                        ? netIdentity(
                                amount(stub.ytdGrossPay()), ytdDeductions, amount(stub.ytdNetPay()))
                        : Check.NOT_CLAIMED;
        Check ytdCoversCurrent = ytdCoversCurrent(stub);
        Check earningsPartition =
                partition(
                        stub.earnings().stream().map(EarningLine::currentAmount).toList(),
                        amount(stub.currentGrossPay()));
        Check deductionsPartition =
                partition(
                        stub.deductions().stream().map(DeductionLine::currentAmount).toList(),
                        amount(stub.currentTotalDeductions()));
        Check earningsLineProducts = earningsLineProducts(stub.earnings());
        Check periodCoherence =
                periodCoherence(
                        date(stub.payPeriodStart()), date(stub.payPeriodEnd()), date(stub.payDate()));

        List<Check> checks =
                List.of(
                        currentNetIdentity,
                        ytdNetIdentity,
                        ytdCoversCurrent,
                        earningsPartition,
                        deductionsPartition,
                        earningsLineProducts,
                        periodCoherence);
        Status status;
        if (checks.contains(Check.CONTRADICTED)) {
            status = Status.CONTRADICTED;
        } else if (currentNetIdentity == Check.PASS) {
            status = Status.RECONCILED;
        } else {
            status = Status.UNPROVEN;
        }
        return new Result(
                status,
                currentNetIdentity,
                ytdNetIdentity,
                ytdCoversCurrent,
                earningsPartition,
                deductionsPartition,
                earningsLineProducts,
                periodCoherence);
    }

    /**
     * {@code gross - deductions = net}, the one identity a paystub always asserts and the only one
     * that vouches for the persisted money fields.
     *
     * <p>Never {@link Check#NOT_CLAIMED}: every earnings statement in existence claims a gross, a
     * withholding and a take-home, so a missing input here is always something the reader lost —
     * UNPROVEN — never something the document declined to say.
     */
    private static Check netIdentity(BigDecimal gross, BigDecimal deductions, BigDecimal net) {
        if (gross == null || deductions == null || net == null) {
            return Check.UNPROVEN;
        }
        return within(gross.subtract(deductions), net) ? Check.PASS : Check.CONTRADICTED;
    }

    /**
     * The deductions figure the net identity is allowed to use: the STATED total, or — when the
     * stub states none — the sum of a COMPLETE set of deduction lines.
     *
     * <p>What must never happen is the shortcut this engine deliberately refused for years: gross
     * minus a single named deduction (federal withholding, say) does not prove net, because nothing
     * says that deduction is the whole of them. Only a stated total or a whole table is the whole
     * of them. A table with one amount missing yields null and the identity reports UNPROVEN; a
     * table that is merely SHORT a row cannot fake a pass either, because a short sum makes the
     * identity miss by the value of the row that was dropped.
     *
     * <p>Absolute value, once, on the finished figure: vendors print deduction columns positive,
     * negative, or bracketed, and the sign convention is presentation, not arithmetic. Taking it on
     * the sum rather than on each row keeps a genuine negative row (a reimbursement inside the
     * deductions block) reducing the total instead of inflating it.
     */
    private static BigDecimal effectiveDeductions(MoneyCell statedTotal, List<BigDecimal> lines) {
        BigDecimal stated = amount(statedTotal);
        if (stated != null) {
            return stated.abs();
        }
        if (lines.isEmpty() || lines.contains(null)) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal line : lines) {
            sum = sum.add(line);
        }
        return sum.abs();
    }

    /**
     * Every year-to-date figure must be at least its current-period counterpart, because the
     * current period is one of the periods the year-to-date column accumulates. A YTD below the
     * current period is not a small discrepancy — it is impossible — and it is the signature of the
     * single most common paystub misread there is: the two columns swapped.
     *
     * <p>Compared on MAGNITUDE, so a vendor that prints both deduction columns as negatives is
     * checked on how much was withheld rather than on which number sorts lower.
     */
    private static Check ytdCoversCurrent(PaystubExtraction stub) {
        if (!claimsYearToDate(stub)) {
            return Check.NOT_CLAIMED;
        }
        List<BigDecimal[]> pairs = new ArrayList<>();
        pairs.add(new BigDecimal[] {amount(stub.ytdGrossPay()), amount(stub.currentGrossPay())});
        pairs.add(new BigDecimal[] {amount(stub.ytdNetPay()), amount(stub.netPay())});
        pairs.add(
                new BigDecimal[] {
                    amount(stub.ytdTotalDeductions()), amount(stub.currentTotalDeductions())
                });
        for (EarningLine line : stub.earnings()) {
            pairs.add(new BigDecimal[] {amount(line.ytdAmount()), amount(line.currentAmount())});
        }
        for (DeductionLine line : stub.deductions()) {
            pairs.add(new BigDecimal[] {amount(line.ytdAmount()), amount(line.currentAmount())});
        }

        boolean anyComparable = false;
        for (BigDecimal[] pair : pairs) {
            if (pair[0] == null || pair[1] == null) {
                continue;
            }
            anyComparable = true;
            if (pair[0].abs().compareTo(pair[1].abs().subtract(MONEY_TOLERANCE)) < 0) {
                return Check.CONTRADICTED;
            }
        }
        return anyComparable ? Check.PASS : Check.UNPROVEN;
    }

    /**
     * A table's rows must sum to the total the stub prints above or below them.
     *
     * <p>Three distinct outcomes and they are not interchangeable: no rows at all means the reader
     * saw no such table and there is nothing to check ({@link Check#NOT_CLAIMED}); rows with a hole
     * in them, or no stated total to check them against, means the check could not be finished
     * ({@link Check#UNPROVEN}); a complete table that does not sum is the document disagreeing with
     * itself ({@link Check#CONTRADICTED}).
     *
     * <p>Current period only. The year-to-date column is guarded by its own net identity and by the
     * YTD-covers-current rule, and a second partition over it would add a second chance to raise a
     * false contradiction on a stub whose YTD table legitimately carries prior-employer or
     * adjustment rows the current column does not.
     */
    private static Check partition(List<MoneyCell> rows, BigDecimal statedTotal) {
        if (rows.isEmpty()) {
            return Check.NOT_CLAIMED;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (MoneyCell row : rows) {
            BigDecimal value = amount(row);
            if (value == null) {
                return Check.UNPROVEN;
            }
            sum = sum.add(value);
        }
        if (statedTotal == null) {
            return Check.UNPROVEN;
        }
        return within(sum.abs(), statedTotal.abs()) ? Check.PASS : Check.CONTRADICTED;
    }

    /**
     * {@code hours x rate = amount} on every earnings row that prints all three.
     *
     * <p>A salaried row prints neither hours nor rate and asserts no product, so a stub made only
     * of those reports {@link Check#NOT_CLAIMED} rather than pretending it was checked.
     */
    private static Check earningsLineProducts(List<EarningLine> earnings) {
        boolean anyChecked = false;
        for (EarningLine line : earnings) {
            BigDecimal hours = amount(line.hours());
            BigDecimal rate = amount(line.rate());
            BigDecimal lineAmount = amount(line.currentAmount());
            if (hours == null || rate == null || lineAmount == null) {
                continue;
            }
            anyChecked = true;
            BigDecimal difference = hours.multiply(rate).subtract(lineAmount).abs();
            if (difference.compareTo(lineProductTolerance(hours, rate)) > 0) {
                return Check.CONTRADICTED;
            }
        }
        return anyChecked ? Check.PASS : Check.NOT_CLAIMED;
    }

    /**
     * How far a printed {@code hours x rate} may sit from its printed amount.
     *
     * <p>The flat money tolerance is wrong here on its own, and dangerously so. A rate printed to
     * two decimals is a ROUNDED rate — payroll multiplied the full-precision one — and that
     * rounding is amplified by every hour worked: a rate half a cent off across eighty hours moves
     * the line by forty cents, with nothing whatsoever wrong. Charging that to the reader would
     * report most hourly stubs in the country as contradicted, which is precisely the "wrong
     * beats missing" failure this class exists to prevent.
     *
     * <p>So the tolerance is derived from the precision the document actually printed: half a unit
     * in the last decimal place of each factor, scaled by the other factor — the standard
     * first-order bound on a product of two rounded numbers — plus the flat cent-level allowance
     * for the rounding of the amount itself. A stub that prints its rate to four decimals is
     * therefore held to a far tighter bound than one that prints two, which is exactly right: it
     * told us more, so we can demand more.
     */
    private static BigDecimal lineProductTolerance(BigDecimal hours, BigDecimal rate) {
        return hours.abs()
                .multiply(halfUnitInLastPlace(rate))
                .add(rate.abs().multiply(halfUnitInLastPlace(hours)))
                .add(MONEY_TOLERANCE);
    }

    /** Half of one unit in the value's last printed decimal place — the rounding it may hide. */
    private static BigDecimal halfUnitInLastPlace(BigDecimal value) {
        return BigDecimal.ONE.movePointLeft(Math.max(value.scale(), 0)).multiply(HALF);
    }

    /**
     * {@code payPeriodStart <= payPeriodEnd <= payDate}, on whichever pairs were read.
     *
     * <p>An out-of-order pair almost always means two dates were swapped or one was read off the
     * wrong label, and a stub whose period is inverted cannot be used to annualize income at all.
     * The rule is deliberately strict about pay date: an employer paying BEFORE the period closes
     * (an advance for a holiday week) is real but rare, and grading it CONTRADICTED costs a human
     * a glance, while relaxing the rule would let a genuinely transposed pay date through — the
     * cheaper mistake wins.
     */
    private static Check periodCoherence(LocalDate start, LocalDate end, LocalDate payDate) {
        if (start == null && end == null && payDate == null) {
            return Check.NOT_CLAIMED;
        }
        boolean anyComparable = false;
        if (start != null && end != null) {
            anyComparable = true;
            if (start.isAfter(end)) {
                return Check.CONTRADICTED;
            }
        }
        if (end != null && payDate != null) {
            anyComparable = true;
            if (end.isAfter(payDate)) {
                return Check.CONTRADICTED;
            }
        }
        if (start != null && payDate != null) {
            anyComparable = true;
            if (start.isAfter(payDate)) {
                return Check.CONTRADICTED;
            }
        }
        return anyComparable ? Check.PASS : Check.UNPROVEN;
    }

    /** Does this stub print a year-to-date column at all? */
    private static boolean claimsYearToDate(PaystubExtraction stub) {
        return amount(stub.ytdGrossPay()) != null
                || amount(stub.ytdNetPay()) != null
                || amount(stub.ytdTotalDeductions()) != null
                || stub.earnings().stream().anyMatch(line -> amount(line.ytdAmount()) != null)
                || stub.deductions().stream().anyMatch(line -> amount(line.ytdAmount()) != null);
    }

    private static List<BigDecimal> currentDeductionAmounts(PaystubExtraction stub) {
        return stub.deductions().stream().map(line -> amount(line.currentAmount())).toList();
    }

    private static List<BigDecimal> ytdDeductionAmounts(PaystubExtraction stub) {
        return stub.deductions().stream().map(line -> amount(line.ytdAmount())).toList();
    }

    private static boolean within(BigDecimal left, BigDecimal right) {
        return left.subtract(right).abs().compareTo(MONEY_TOLERANCE) <= 0;
    }

    private static BigDecimal amount(MoneyCell cell) {
        return cell == null ? null : cell.value();
    }

    private static LocalDate date(DateCell cell) {
        return cell == null ? null : cell.value();
    }

    enum Status {
        RECONCILED,
        CONTRADICTED,
        UNPROVEN
    }

    enum Check {
        PASS,
        CONTRADICTED,
        UNPROVEN,
        NOT_CLAIMED
    }

    /**
     * @param currentNetIdentity gross minus total deductions equals net, this period
     * @param ytdNetIdentity the same identity down the year-to-date column
     * @param ytdCoversCurrent every YTD figure is at least its current-period counterpart
     * @param earningsPartition the earnings rows sum to gross
     * @param deductionsPartition the deduction rows sum to the stated total deductions
     * @param earningsLineProducts hours times rate equals amount, on rows that print all three
     * @param periodCoherence period start, period end and pay date are in order
     */
    record Result(
            Status status,
            Check currentNetIdentity,
            Check ytdNetIdentity,
            Check ytdCoversCurrent,
            Check earningsPartition,
            Check deductionsPartition,
            Check earningsLineProducts,
            Check periodCoherence) {

        /**
         * Whether this outcome is allowed to promote a field to VALID. Only a closed current-period
         * net identity does — the checks around it corroborate the reading but do not, on their
         * own, prove the take-home number a lender underwrites from.
         */
        boolean reconciled() {
            return status == Status.RECONCILED;
        }

        static Result unproven() {
            return new Result(
                    Status.UNPROVEN,
                    Check.UNPROVEN,
                    Check.UNPROVEN,
                    Check.UNPROVEN,
                    Check.UNPROVEN,
                    Check.UNPROVEN,
                    Check.UNPROVEN,
                    Check.UNPROVEN);
        }
    }
}
