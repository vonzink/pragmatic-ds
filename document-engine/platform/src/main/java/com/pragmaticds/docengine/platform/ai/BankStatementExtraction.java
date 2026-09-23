package com.pragmaticds.docengine.platform.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Strict typed representation of a validated bank-statement extraction. */
public record BankStatementExtraction(Summary summary, List<Txn> transactions, List<Check> checks)
        implements AiStructuredExtraction {

    public BankStatementExtraction {
        Objects.requireNonNull(summary, "summary");
        transactions = List.copyOf(transactions);
        checks = List.copyOf(checks);
    }

    @Override
    public AiDocumentType documentType() {
        return AiDocumentType.BANK_STATEMENT;
    }

    public record Summary(
            TextCell bankName,
            TextCell accountHolderName,
            TextCell accountHolderAddress,
            TextCell accountNumber,
            DateCell statementPeriodStart,
            DateCell statementPeriodEnd,
            MoneyCell beginningBalance,
            MoneyCell endingBalance,
            MoneyCell totalDeposits,
            MoneyCell totalWithdrawals) {}

    public record Txn(
            DateCell date,
            TextCell description,
            MoneyCell amount,
            MoneyCell balance,
            Direction direction,
            int page) {}

    public record Check(TextCell checkNumber, DateCell datePaid, MoneyCell amount, int page) {}

    public record TextCell(
            String value, String text, Integer page, Confidence confidence, Boolean handwritten) {

        /** Pre-Phase-H shape: no handwriting tag. */
        public TextCell(String value, String text, Integer page, Confidence confidence) {
            this(value, text, page, confidence, null);
        }
    }

    public record DateCell(
            LocalDate value, String text, Integer page, Confidence confidence, Boolean handwritten) {

        /** Pre-Phase-H shape: no handwriting tag. */
        public DateCell(LocalDate value, String text, Integer page, Confidence confidence) {
            this(value, text, page, confidence, null);
        }
    }

    public record MoneyCell(
            BigDecimal value, String text, Integer page, Confidence confidence, Boolean handwritten) {

        /** Pre-Phase-H shape: no handwriting tag. */
        public MoneyCell(BigDecimal value, String text, Integer page, Confidence confidence) {
            this(value, text, page, confidence, null);
        }
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum Direction {
        DEPOSIT,
        WITHDRAWAL
    }
}
