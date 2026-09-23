package com.pragmaticds.docengine.ai;

import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Direction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Summary;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Txn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic arithmetic trust gate for one AI-extracted bank-statement ledger.
 *
 * <p>Two genres reach this class and they carry different proofs. A STATEMENT is struck for a
 * closed period and states an opening balance, a closing balance and category totals, so its
 * ledger can be proved the strong way: opening plus the signed net equals closing, and the stated
 * totals partition the rows. An ONLINE ACTIVITY PRINT-OUT states none of those — it is a view of
 * an account as of the moment it was printed — and asking it for a period-closing equation is
 * asking for arithmetic the document never claimed.
 *
 * <p>The print-out is not therefore unprovable. It prints a RUNNING BALANCE beside every row, and
 * that column is a real check: each row's balance must differ from the row before it by exactly
 * that row's signed amount. It is a per-row corroboration rather than an aggregate one, and it
 * detects an omitted or misread row between the first and last row shown — the failure that
 * matters — while the stated present balance anchors its closing end.
 *
 * <p>So the checks a genre cannot carry report {@link Check#NOT_AVAILABLE} (the document makes no
 * such claim) rather than {@link Check#INCOMPLETE} (it does, and we could not finish it). That
 * distinction already existed for the running-balance column and is now applied to the other two.
 * A statement that merely LOST a total is unchanged and still reports INCOMPLETE: the discriminator
 * is that a print-out states no period, no opening balance and no totals AT ALL.
 */
final class BankStatementReconciler {

    Result reconcile(BankStatementExtraction extraction) {
        if (extraction == null || extraction.summary() == null) {
            return Result.unable();
        }

        List<Txn> transactions = extraction.transactions();
        BigDecimal beginning = amount(extraction.summary().beginningBalance());
        BigDecimal ending = amount(extraction.summary().endingBalance());
        BigDecimal statedDeposits = amount(extraction.summary().totalDeposits());
        BigDecimal statedWithdrawals = amount(extraction.summary().totalWithdrawals());

        boolean completeTransactions =
                transactions != null
                        && transactions.stream()
                                .allMatch(
                                        txn ->
                                                txn != null
                                                        && amount(txn.amount()) != null
                                                        && txn.direction() != null);
        BigDecimal signedTotal = BigDecimal.ZERO;
        BigDecimal deposits = BigDecimal.ZERO;
        BigDecimal withdrawals = BigDecimal.ZERO;
        if (completeTransactions) {
            for (Txn transaction : transactions) {
                BigDecimal absolute = amount(transaction.amount()).abs();
                if (transaction.direction() == Direction.DEPOSIT) {
                    deposits = deposits.add(absolute);
                    signedTotal = signedTotal.add(absolute);
                } else {
                    withdrawals = withdrawals.add(absolute);
                    signedTotal = signedTotal.subtract(absolute);
                }
            }
        }

        // The row order the running-balance walk needs. A statement prints oldest first; a
        // print-out commonly prints newest first, and the prompt deliberately does NOT ask the
        // model to re-sort — the document's own order is the faithful record, so the orientation
        // is established here, from the dates, where it can be reasoned about.
        List<Txn> chronological = inChronologicalOrder(transactions);

        Check ledgerEquation;
        Check partition;
        if (statesAPeriodLedger(extraction.summary())) {
            ledgerEquation =
                    beginning == null || ending == null || !completeTransactions
                            ? Check.INCOMPLETE
                            : check(signedTotal, ending.subtract(beginning));
            partition =
                    statedDeposits == null || statedWithdrawals == null || !completeTransactions
                            ? Check.INCOMPLETE
                            : both(
                                    check(deposits, statedDeposits.abs()),
                                    check(withdrawals, statedWithdrawals.abs()));
        } else {
            // No period, no opening balance, no totals: there is no partition to check against
            // and no opening end to close. What CAN be checked is that the balance the document
            // states as of now agrees with the ledger's own last running balance.
            partition = Check.NOT_AVAILABLE;
            ledgerEquation = closingBalance(ending, chronological, completeTransactions);
        }
        Check runningBalances = runningBalances(beginning, chronological, completeTransactions);

        // At least one check must have actually PASSED. Before the NOT_AVAILABLE arms above, the
        // two core checks could only ever be PASS/FAIL/INCOMPLETE, so a RECONCILED verdict always
        // rested on something; now that all three can report "this document makes no such claim",
        // an unguarded verdict would call a print-out with no balance column reconciled on no
        // evidence whatsoever. This can only ever downgrade a verdict, never grant one.
        boolean anyProof =
                ledgerEquation == Check.PASS
                        || partition == Check.PASS
                        || runningBalances == Check.PASS;

        Status status;
        if (ledgerEquation == Check.FAIL
                || partition == Check.FAIL
                || runningBalances == Check.FAIL) {
            status = Status.NON_RECONCILING;
        } else if (ledgerEquation == Check.INCOMPLETE
                || partition == Check.INCOMPLETE
                || runningBalances == Check.INCOMPLETE
                || !anyProof) {
            status = Status.UNABLE_TO_VALIDATE;
        } else {
            status = Status.RECONCILED;
        }
        return new Result(status, ledgerEquation, partition, runningBalances);
    }

    /**
     * Does this document claim to close a period? True unless it states NO period, NO opening
     * balance and NO totals — the shape of an activity print-out. Deliberately an ALL test: a
     * statement that merely lost one total to a bad scan still claims a period ledger, and must
     * keep reporting INCOMPLETE rather than being quietly re-read as a genre that owes nothing.
     * {@code endingBalance} is excluded because a print-out does state one.
     *
     * <p><b>Every test here is on the cell's VALUE, never the cell reference.</b> The output schema
     * makes all ten summary keys REQUIRED, so a model reporting nothing for a field still emits the
     * cell — {@code {"value": null, "text": null, "page": null, "confidence": "LOW"}} — and {@code
     * BankStatementExtractionParser.money} builds a MoneyCell for it either way, nulling only the
     * amount inside. A reference test is therefore true for every real extraction ever produced,
     * which would make this discriminator permanently answer "period ledger" and leave the
     * print-out arm dead code.
     */
    private static boolean statesAPeriodLedger(Summary summary) {
        return date(summary.statementPeriodStart()) != null
                || date(summary.statementPeriodEnd()) != null
                || amount(summary.beginningBalance()) != null
                || amount(summary.totalDeposits()) != null
                || amount(summary.totalWithdrawals()) != null;
    }

    private static LocalDate date(DateCell cell) {
        return cell == null ? null : cell.value();
    }

    /**
     * The closing check a print-out can carry: the balance it states as of now against the last
     * running balance in its own ledger. NOT_AVAILABLE when no row prints a balance — the document
     * offers nothing to check against — and INCOMPLETE when only some rows do.
     */
    private static Check closingBalance(
            BigDecimal ending, List<Txn> chronological, boolean completeTransactions) {
        if (chronological.isEmpty()) {
            return Check.NOT_AVAILABLE;
        }
        long present =
                chronological.stream()
                        .filter(txn -> txn != null && amount(txn.balance()) != null)
                        .count();
        if (present == 0) {
            return Check.NOT_AVAILABLE;
        }
        if (ending == null || present != chronological.size() || !completeTransactions) {
            return Check.INCOMPLETE;
        }
        return check(ending, amount(chronological.get(chronological.size() - 1).balance()));
    }

    /**
     * The rows in the order they were POSTED. Reverses only a list that is wholly non-increasing
     * by date with at least one strict decrease — an unambiguous newest-first print-out. Anything
     * else, including a list with any missing date or any date that increases, is left exactly as
     * the document printed it: guessing an order would silently re-associate every balance.
     */
    private static List<Txn> inChronologicalOrder(List<Txn> transactions) {
        if (transactions == null || transactions.size() < 2) {
            return transactions == null ? List.of() : transactions;
        }
        LocalDate previous = null;
        boolean strictlyDecreases = false;
        for (Txn transaction : transactions) {
            LocalDate date =
                    transaction == null || transaction.date() == null
                            ? null
                            : transaction.date().value();
            if (date == null) {
                return transactions;
            }
            if (previous != null) {
                int comparison = previous.compareTo(date);
                if (comparison < 0) {
                    return transactions;
                }
                if (comparison > 0) {
                    strictlyDecreases = true;
                }
            }
            previous = date;
        }
        if (!strictlyDecreases) {
            return transactions;
        }
        List<Txn> reversed = new ArrayList<>(transactions);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /**
     * The running-balance column, walked in posting order.
     *
     * <p>With an opening balance the walk is ANCHORED: it starts at that balance and every row
     * must land on its printed one. Without an opening balance — the print-out genre, which states
     * none — the column still proves itself CHAINED: consecutive balances must differ by exactly
     * the later row's signed amount. That is the same arithmetic minus its anchor, and it is what
     * catches a row dropped or misread between the first and last row shown, because losing a row
     * makes the neighbouring balances differ by two amounts instead of one.
     *
     * <p>A chain needs two rows to compare. A single row with no opening balance states a balance
     * nothing corroborates, so it reports INCOMPLETE rather than passing on its own say-so.
     */
    private static Check runningBalances(
            BigDecimal beginning, List<Txn> chronological, boolean completeTransactions) {
        if (chronological.isEmpty()) {
            return Check.NOT_AVAILABLE;
        }
        long present =
                chronological.stream()
                        .filter(txn -> txn != null && amount(txn.balance()) != null)
                        .count();
        if (present == 0) {
            return Check.NOT_AVAILABLE;
        }
        if (present != chronological.size() || !completeTransactions) {
            return Check.INCOMPLETE;
        }

        if (beginning != null) {
            BigDecimal expected = beginning;
            for (Txn transaction : chronological) {
                expected = expected.add(signedAmount(transaction));
                if (expected.compareTo(amount(transaction.balance())) != 0) {
                    return Check.FAIL;
                }
            }
            return Check.PASS;
        }

        if (chronological.size() < 2) {
            return Check.INCOMPLETE;
        }
        for (int index = 1; index < chronological.size(); index++) {
            BigDecimal delta =
                    amount(chronological.get(index).balance())
                            .subtract(amount(chronological.get(index - 1).balance()));
            if (delta.compareTo(signedAmount(chronological.get(index))) != 0) {
                return Check.FAIL;
            }
        }
        return Check.PASS;
    }

    /**
     * The row's amount with the sign its DIRECTION dictates. Direction owns the sign: a statement
     * that prints a withdrawal as {@code (75.00)} or {@code -75.00} must not be double-negated.
     */
    private static BigDecimal signedAmount(Txn transaction) {
        BigDecimal absolute = amount(transaction.amount()).abs();
        return transaction.direction() == Direction.DEPOSIT ? absolute : absolute.negate();
    }

    private static BigDecimal amount(MoneyCell cell) {
        return cell == null ? null : cell.value();
    }

    private static Check check(BigDecimal actual, BigDecimal expected) {
        return actual.compareTo(expected) == 0 ? Check.PASS : Check.FAIL;
    }

    private static Check both(Check first, Check second) {
        return first == Check.PASS && second == Check.PASS ? Check.PASS : Check.FAIL;
    }

    enum Status {
        RECONCILED,
        NON_RECONCILING,
        UNABLE_TO_VALIDATE
    }

    enum Check {
        PASS,
        FAIL,
        INCOMPLETE,
        NOT_AVAILABLE
    }

    record Result(Status status, Check ledgerEquation, Check partition, Check runningBalances) {
        static Result unable() {
            return new Result(
                    Status.UNABLE_TO_VALIDATE,
                    Check.INCOMPLETE,
                    Check.INCOMPLETE,
                    Check.INCOMPLETE);
        }
    }
}
