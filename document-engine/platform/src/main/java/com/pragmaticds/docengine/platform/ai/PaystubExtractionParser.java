package com.pragmaticds.docengine.platform.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pragmaticds.docengine.platform.ai.BankStatementExtractionParser.RawCell;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strictly validates and normalizes raw paystub structured JSON without leaking content. Cell
 * normalization delegates to {@link BankStatementExtractionParser}'s helpers so every document
 * type reads dates and money identically.
 */
public final class PaystubExtractionParser {

    private static final String VALIDATION_ERROR = "validation_error";

    /**
     * The SAME canonical map and key-mangling rule as the deterministic {@code payFrequency}
     * normalizer ({@code Normalizers.PAY_FREQUENCIES}): lowercase, strip every non-letter, look
     * up. A printed wording that matches no canonical frequency yields a null value — missing
     * over wrong — while the printed text is kept as evidence.
     */
    private static final Map<String, String> PAY_FREQUENCIES =
            Map.of(
                    "weekly", "WEEKLY",
                    "biweekly", "BIWEEKLY",
                    "semimonthly", "SEMIMONTHLY",
                    "monthly", "MONTHLY");

    private final PaystubExtractionSchema schema;
    private final ObjectMapper mapper;

    public PaystubExtractionParser() {
        this(new PaystubExtractionSchema());
    }

    PaystubExtractionParser(PaystubExtractionSchema schema) {
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
            RawPaystub raw = mapper.readValue(rawResult.structuredJson(), RawPaystub.class);
            PaystubExtraction extraction = normalize(raw);
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

    private static PaystubExtraction normalize(RawPaystub raw) {
        return new PaystubExtraction(
                BankStatementExtractionParser.text(raw.borrowerName()),
                BankStatementExtractionParser.text(raw.employerName()),
                BankStatementExtractionParser.date(raw.payPeriodStart()),
                BankStatementExtractionParser.date(raw.payPeriodEnd()),
                BankStatementExtractionParser.date(raw.payDate()),
                payFrequency(raw.payFrequency()),
                BankStatementExtractionParser.money(raw.currentGrossPay()),
                BankStatementExtractionParser.money(raw.ytdGrossPay()),
                BankStatementExtractionParser.money(raw.netPay()),
                BankStatementExtractionParser.money(raw.federalWithholding()),
                BankStatementExtractionParser.money(raw.currentTotalDeductions()),
                BankStatementExtractionParser.money(raw.ytdTotalDeductions()),
                BankStatementExtractionParser.money(raw.ytdNetPay()),
                raw.earnings() == null
                        ? List.<PaystubExtraction.EarningLine>of()
                        : raw.earnings().stream().map(PaystubExtractionParser::earning).toList(),
                raw.deductions() == null
                        ? List.<PaystubExtraction.DeductionLine>of()
                        : raw.deductions().stream()
                                .map(PaystubExtractionParser::deduction)
                                .toList());
    }

    /**
     * Hours and rate go through the SAME money normalizer as every amount, which is what preserves
     * the decimal places the stub printed. That precision is not cosmetic: the reconciler derives
     * its {@code hours x rate} tolerance from it, so a rate silently re-scaled here would either
     * loosen or tighten a real arithmetic check.
     */
    private static PaystubExtraction.EarningLine earning(RawEarningLine raw) {
        return new PaystubExtraction.EarningLine(
                BankStatementExtractionParser.text(raw.description()),
                BankStatementExtractionParser.money(raw.hours()),
                BankStatementExtractionParser.money(raw.rate()),
                BankStatementExtractionParser.money(raw.currentAmount()),
                BankStatementExtractionParser.money(raw.ytdAmount()));
    }

    private static PaystubExtraction.DeductionLine deduction(RawDeductionLine raw) {
        return new PaystubExtraction.DeductionLine(
                BankStatementExtractionParser.text(raw.description()),
                BankStatementExtractionParser.money(raw.currentAmount()),
                BankStatementExtractionParser.money(raw.ytdAmount()));
    }

    private static BankStatementExtraction.TextCell payFrequency(RawCell raw) {
        String canonical =
                raw.value() == null
                        ? null
                        : PAY_FREQUENCIES.get(
                                raw.value()
                                        .toLowerCase(Locale.ROOT)
                                        .replaceAll("[^a-z]", ""));
        return new BankStatementExtraction.TextCell(
                canonical,
                raw.text(),
                raw.page(),
                BankStatementExtractionParser.confidence(raw.confidence()),
                raw.handwritten());
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

    private record RawPaystub(
            RawCell borrowerName,
            RawCell employerName,
            RawCell payPeriodStart,
            RawCell payPeriodEnd,
            RawCell payDate,
            RawCell payFrequency,
            RawCell currentGrossPay,
            RawCell ytdGrossPay,
            RawCell netPay,
            RawCell federalWithholding,
            RawCell currentTotalDeductions,
            RawCell ytdTotalDeductions,
            RawCell ytdNetPay,
            List<RawEarningLine> earnings,
            List<RawDeductionLine> deductions) {}

    private record RawEarningLine(
            RawCell description,
            RawCell hours,
            RawCell rate,
            RawCell currentAmount,
            RawCell ytdAmount) {}

    private record RawDeductionLine(
            RawCell description, RawCell currentAmount, RawCell ytdAmount) {}
}
