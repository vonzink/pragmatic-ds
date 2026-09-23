package com.pragmaticds.docengine.platform.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pragmaticds.docengine.platform.ai.BankStatementExtractionParser.RawCell;
import java.util.regex.Pattern;

/**
 * Strictly validates and normalizes raw W-2 structured JSON without leaking content. Names and
 * money reuse {@link BankStatementExtractionParser}'s helpers (money is kept only when its value
 * appears in the printed text). EIN and tax year get their own canonicalization, to the exact
 * strings the rules rows store; a value that cannot be canonicalized, or whose digits are not in
 * the printed text, becomes null — missing over wrong — while the printed text stays as evidence.
 */
public final class W2ExtractionParser {
    private static final String VALIDATION_ERROR = "validation_error";
    private static final Pattern TAX_YEAR = Pattern.compile("20\\d{2}");

    /**
     * An SSN's printed shape — three digits, a separator, two digits, a separator, four digits,
     * with no adjoining digit on either side (so it does not fire inside a longer digit run, e.g.
     * an account or routing number). W3 (the spec's promise the AI path never returns or persists
     * an SSN) does not stop at {@code employeeSsn}: nothing in the schema names that field, but a
     * model can still misplace Box a's SSN into whichever cell it is looking at when it reads
     * that box — {@code employerEin} most often, since both are nine digits in the same corner of
     * the form. Every one of the nine raw cells is checked, on both {@code value} and {@code
     * text}, before any other normalization runs.
     */
    private static final Pattern SSN_SHAPE =
            Pattern.compile("(?<!\\d)\\d{3}[- ]\\d{2}[- ]\\d{4}(?!\\d)");

    private final W2ExtractionSchema schema;
    private final ObjectMapper mapper;

    public W2ExtractionParser() {
        this(new W2ExtractionSchema());
    }

    W2ExtractionParser(W2ExtractionSchema schema) {
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
            RawW2 raw = mapper.readValue(rawResult.structuredJson(), RawW2.class);
            return new AiExtractionResult(
                    rawResult.structuredJson(),
                    normalize(raw),
                    rawResult.provider(),
                    rawResult.model(),
                    AiExtractionStatus.OK,
                    rawResult.tokenCounts(),
                    null);
        } catch (JsonProcessingException | RuntimeException invalidResponse) {
            return error(rawResult);
        }
    }

    private static W2Extraction normalize(RawW2 raw) {
        return new W2Extraction(
                BankStatementExtractionParser.text(scrubSsn(raw.employeeName())),
                BankStatementExtractionParser.text(scrubSsn(raw.employerName())),
                ein(scrubSsn(raw.employerEin())),
                taxYear(scrubSsn(raw.taxYear())),
                BankStatementExtractionParser.money(scrubSsn(raw.wagesTipsOtherComp())),
                BankStatementExtractionParser.money(scrubSsn(raw.federalIncomeTaxWithheld())),
                BankStatementExtractionParser.money(scrubSsn(raw.socialSecurityWages())),
                BankStatementExtractionParser.money(scrubSsn(raw.medicareWages())),
                BankStatementExtractionParser.money(scrubSsn(raw.stateWages())));
    }

    /**
     * W3: neither the digits nor the printed text of an SSN survive anywhere in a W-2 reading, no
     * matter which of the nine cells the model put it in. A cell whose {@code value} or {@code
     * text} contains an SSN shape is replaced wholesale — {@code value}, {@code text} and {@code
     * page} all null, confidence dropped to {@code LOW}, handwritten unknown — rather than merely
     * having the offending substring stripped, so a reviewer never sees a redacted fragment that
     * still hints at the number.
     */
    static RawCell scrubSsn(RawCell raw) {
        if (raw == null) {
            return null;
        }
        boolean ssnInValue = raw.value() != null && SSN_SHAPE.matcher(raw.value()).find();
        boolean ssnInText = raw.text() != null && SSN_SHAPE.matcher(raw.text()).find();
        if (!ssnInValue && !ssnInText) {
            return raw;
        }
        return new RawCell(null, null, null, "LOW", null);
    }

    /** Nine digits, present in the printed text, rendered NN-NNNNNNN; anything else is null. */
    static BankStatementExtraction.TextCell ein(RawCell raw) {
        String digits = raw.value() == null ? "" : raw.value().replaceAll("\\D", "");
        String printedDigits = raw.text() == null ? "" : raw.text().replaceAll("\\D", "");
        String canonical =
                digits.length() == 9 && printedDigits.contains(digits)
                        ? digits.substring(0, 2) + "-" + digits.substring(2)
                        : null;
        return withValue(raw, canonical);
    }

    /** A bare 19xx/20xx year that also appears in the printed text; anything else is null. */
    static BankStatementExtraction.TextCell taxYear(RawCell raw) {
        String value = raw.value() == null ? null : raw.value().trim();
        boolean ok =
                value != null
                        && TAX_YEAR.matcher(value).matches()
                        && raw.text() != null
                        && raw.text().contains(value);
        return withValue(raw, ok ? value : null);
    }

    private static BankStatementExtraction.TextCell withValue(RawCell raw, String value) {
        return new BankStatementExtraction.TextCell(
                value,
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

    private record RawW2(
            RawCell employeeName,
            RawCell employerName,
            RawCell employerEin,
            RawCell taxYear,
            RawCell wagesTipsOtherComp,
            RawCell federalIncomeTaxWithheld,
            RawCell socialSecurityWages,
            RawCell medicareWages,
            RawCell stateWages) {}
}
