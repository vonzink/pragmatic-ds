package com.pragmaticds.docengine.platform.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strictly validates and normalizes raw bank-statement structured JSON without leaking content. */
public final class BankStatementExtractionParser {

    private static final String VALIDATION_ERROR = "validation_error";
    private static final List<DateTimeFormatter> DATE_FORMATS =
            List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US)
                            .withResolverStyle(ResolverStyle.STRICT),
                    new DateTimeFormatterBuilder()
                            .appendPattern("M/d/")
                            .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                            .toFormatter(Locale.US)
                            .withResolverStyle(ResolverStyle.STRICT),
                    DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)
                            .withResolverStyle(ResolverStyle.STRICT),
                    DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)
                            .withResolverStyle(ResolverStyle.STRICT));

    private final BankStatementExtractionSchema schema;
    private final ObjectMapper mapper;

    public BankStatementExtractionParser() {
        this(new BankStatementExtractionSchema());
    }

    BankStatementExtractionParser(BankStatementExtractionSchema schema) {
        this.schema = schema;
        this.mapper =
                JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .build();
    }

    public AiExtractionResult parse(AiExtractionResult rawResult) {
        if (rawResult == null) {
            return error(null);
        }
        if (rawResult.status() != AiExtractionStatus.OK) {
            return rawResult;
        }
        try {
            if (!schema.validationErrors(rawResult.structuredJson()).isEmpty()) {
                return error(rawResult);
            }
            RawStatement raw = mapper.readValue(rawResult.structuredJson(), RawStatement.class);
            BankStatementExtraction extraction = normalize(raw);
            return new AiExtractionResult(
                    rawResult.structuredJson(),
                    extraction,
                    rawResult.provider(),
                    rawResult.model(),
                    AiExtractionStatus.OK,
                    rawResult.tokenCounts(),
                    null);
        } catch (JsonProcessingException | RuntimeException invalidResponse) {
            return error(rawResult);
        }
    }

    private static BankStatementExtraction normalize(RawStatement raw) {
        RawSummary summary = raw.summary();
        BankStatementExtraction.Summary normalizedSummary =
                new BankStatementExtraction.Summary(
                        text(summary.bankName()),
                        text(summary.accountHolderName()),
                        text(summary.accountHolderAddress()),
                        text(summary.accountNumber()),
                        date(summary.statementPeriodStart()),
                        date(summary.statementPeriodEnd()),
                        money(summary.beginningBalance()),
                        money(summary.endingBalance()),
                        money(summary.totalDeposits()),
                        money(summary.totalWithdrawals()));
        List<BankStatementExtraction.Txn> transactions =
                raw.transactions().stream()
                        .map(
                                txn ->
                                        new BankStatementExtraction.Txn(
                                                date(txn.date()),
                                                text(txn.description()),
                                                money(txn.amount()),
                                                txn.balance() == null ? null : money(txn.balance()),
                                                BankStatementExtraction.Direction.valueOf(
                                                        txn.direction()),
                                                txn.page()))
                        .toList();
        List<BankStatementExtraction.Check> checks =
                raw.checks().stream()
                        .map(
                                check ->
                                        new BankStatementExtraction.Check(
                                                text(check.checkNumber()),
                                                date(check.datePaid()),
                                                money(check.amount()),
                                                check.page()))
                        .toList();
        return new BankStatementExtraction(normalizedSummary, transactions, checks);
    }

    // The cell helpers and RawCell below are package-visible on purpose: PaystubExtractionParser
    // reuses them so every document type shares ONE date/money/text normalization discipline —
    // two parsers that disagree on what "$1,234.56" means would be a correctness bug.
    static BankStatementExtraction.TextCell text(RawCell raw) {
        return new BankStatementExtraction.TextCell(
                raw.value(), raw.text(), raw.page(), confidence(raw.confidence()), raw.handwritten());
    }

    static BankStatementExtraction.DateCell date(RawCell raw) {
        return new BankStatementExtraction.DateCell(
                parseDate(raw.value()),
                raw.text(),
                raw.page(),
                confidence(raw.confidence()),
                raw.handwritten());
    }

    /**
     * A money cell, with its number DROPPED unless the cell's own printed text supports it.
     *
     * <p>Every cell carries two things: {@code value}, the number the model reports, and
     * {@code text}, the characters it says it read. Nothing forced those two to agree, and on a real
     * Chase statement they did not. The statement prints two separate withdrawal lines —
     * {@code Checks Paid -390.00} and {@code Electronic Withdrawals -240.36} — and no combined
     * total; the schema has one {@code totalWithdrawals} slot; the model filled the slot by SUMMING
     * them, quoting both lines as its text and reporting {@code -630.36} as its value. The engine
     * persisted a printed value that was never printed.
     *
     * <p>That is worse than a wrong number. {@code -630.36} is arithmetically right, so it satisfied
     * {@code BankStatementReconciler}'s deposit/withdrawal partition, and a ledger that should have
     * been {@code UNABLE_TO_VALIDATE} was stamped {@code RECONCILED} — a fabricated figure defeated
     * the gate built to catch fabrication.
     *
     * <p>So the value must be derivable from the cell's own quoted text: some monetary token in
     * {@code text} must equal it in magnitude. Sign is deliberately ignored — a withdrawal prints
     * {@code 130.00} in a checks table and means {@code -130.00} — and so is scale, so
     * {@code 130} corroborates {@code 130.00}. When nothing corroborates, the number is dropped and
     * the cell keeps only its printed text: missing over wrong, and the reviewer still sees what the
     * model claims to have read.
     *
     * <p>Deliberately NOT a rejection of the whole response. One uncorroborated summary total should
     * cost that total, not the ninety transaction cells that anchored perfectly beside it.
     */
    static BankStatementExtraction.MoneyCell money(RawCell raw) {
        BigDecimal amount = parseMoney(raw.value());
        return new BankStatementExtraction.MoneyCell(
                corroborated(amount, raw.text()) ? amount : null,
                raw.text(),
                raw.page(),
                confidence(raw.confidence()),
                raw.handwritten());
    }

    /** Digits with optional thousands separators and decimals — one printed monetary token. */
    private static final Pattern MONEY_TOKEN = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    /**
     * Whether {@code printedText} contains a monetary token equal in magnitude to {@code amount}.
     *
     * <p>A null amount corroborates trivially — there is no claim to check. Blank text does NOT:
     * a number with nothing quoted behind it is exactly the unverifiable case this guard exists for.
     */
    private static boolean corroborated(BigDecimal amount, String printedText) {
        if (amount == null) {
            return true;
        }
        if (printedText == null || printedText.isBlank()) {
            return false;
        }
        BigDecimal magnitude = amount.abs();
        Matcher tokens = MONEY_TOKEN.matcher(printedText);
        while (tokens.find()) {
            try {
                // compareTo, not equals: 130 and 130.00 differ in scale and not in value.
                if (new BigDecimal(tokens.group().replace(",", "")).compareTo(magnitude) == 0) {
                    return true;
                }
            } catch (NumberFormatException notANumber) {
                // A token the printed text happened to look like but cannot be read as a number.
            }
        }
        return false;
    }

    static BankStatementExtraction.Confidence confidence(String value) {
        return BankStatementExtraction.Confidence.valueOf(value);
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value.trim(), format);
            } catch (DateTimeParseException ignored) {
                // Try the next deliberately-supported printed form.
            }
        }
        throw new IllegalArgumentException("unparseable date");
    }

    private static BigDecimal parseMoney(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        boolean parenthesized = normalized.startsWith("(") && normalized.endsWith(")");
        if (parenthesized) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.replace("$", "").replace(",", "").trim();
        BigDecimal amount = new BigDecimal(normalized);
        return parenthesized ? amount.abs().negate() : amount;
    }

    private static AiExtractionResult error(AiExtractionResult rawResult) {
        return new AiExtractionResult(
                null,
                null,
                rawResult == null ? "unknown" : rawResult.provider(),
                rawResult == null ? "unknown" : rawResult.model(),
                AiExtractionStatus.ERROR,
                rawResult == null ? AiTokenCounts.ZERO : rawResult.tokenCounts(),
                VALIDATION_ERROR);
    }

    private record RawStatement(
            RawSummary summary, List<RawTransaction> transactions, List<RawCheck> checks) {}

    private record RawSummary(
            RawCell bankName,
            RawCell accountHolderName,
            RawCell accountHolderAddress,
            RawCell accountNumber,
            RawCell statementPeriodStart,
            RawCell statementPeriodEnd,
            RawCell beginningBalance,
            RawCell endingBalance,
            RawCell totalDeposits,
            RawCell totalWithdrawals) {}

    private record RawTransaction(
            RawCell date,
            RawCell description,
            RawCell amount,
            RawCell balance,
            String direction,
            int page) {}

    private record RawCheck(RawCell checkNumber, RawCell datePaid, RawCell amount, int page) {}

    record RawCell(
            String value, String text, Integer page, String confidence, Boolean handwritten) {}
}
