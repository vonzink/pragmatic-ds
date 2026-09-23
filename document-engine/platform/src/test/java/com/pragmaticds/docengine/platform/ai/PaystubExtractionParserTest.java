package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The paystub parser mirrors the bank-statement parser's discipline exactly: schema-shape
 * violations refuse the WHOLE response, cell normalization is shared, and payFrequency
 * canonicalizes with the deterministic normalizer's own rule — unknown wording degrades to a
 * null value (missing over wrong), never an invented canonical.
 */
class PaystubExtractionParserTest {

    private final PaystubExtractionParser parser = new PaystubExtractionParser();

    @Test
    void a_valid_paystub_parses_and_normalizes_every_cell() {
        AiExtractionResult parsed = parser.parse(ok(fullJson("Bi-Weekly")));

        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.OK);
        PaystubExtraction paystub = (PaystubExtraction) parsed.extraction();
        assertThat(paystub.documentType()).isEqualTo(AiDocumentType.PAYSTUB);
        assertThat(paystub.borrowerName().value()).isEqualTo("Alex Q. Sample");
        assertThat(paystub.employerName().value()).isEqualTo("Example Widgets LLC");
        assertThat(paystub.payPeriodStart().value()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(paystub.payPeriodEnd().value()).isEqualTo(LocalDate.of(2026, 4, 14));
        assertThat(paystub.payDate().value()).isEqualTo(LocalDate.of(2026, 4, 17));
        // Canonicalized with the deterministic normalizer's key-mangling rule; printed wording
        // survives verbatim in text — it is the evidence key.
        assertThat(paystub.payFrequency().value()).isEqualTo("BIWEEKLY");
        assertThat(paystub.payFrequency().text()).isEqualTo("Bi-Weekly");
        assertThat(paystub.currentGrossPay().value())
                .isEqualByComparingTo(new BigDecimal("2400.00"));
        assertThat(paystub.ytdGrossPay().value())
                .isEqualByComparingTo(new BigDecimal("19200.00"));
        assertThat(paystub.netPay().value()).isEqualByComparingTo(new BigDecimal("1850.25"));
        assertThat(paystub.federalWithholding().value())
                .isEqualByComparingTo(new BigDecimal("321.09"));
        assertThat(paystub.netPay().handwritten()).isTrue();
        assertThat(paystub.borrowerName().handwritten()).isNull();
        assertThat(paystub.currentTotalDeductions().value())
                .isEqualByComparingTo(new BigDecimal("549.75"));
        assertThat(paystub.ytdTotalDeductions().value())
                .isEqualByComparingTo(new BigDecimal("4398.00"));
        assertThat(paystub.ytdNetPay().value()).isEqualByComparingTo(new BigDecimal("14802.00"));
        assertThat(paystub.deductions()).hasSize(1);
        assertThat(paystub.deductions().get(0).description().value())
                .isEqualTo("Federal Income Tax");
        assertThat(paystub.earnings()).hasSize(2);
        // The PRINTED precision survives, scale and all: PaystubReconciler derives its
        // hours-times-rate tolerance from exactly how many decimals the stub showed, so
        // re-scaling a rate here would silently move a real arithmetic check.
        assertThat(paystub.earnings().get(0).rate().value().scale()).isEqualTo(4);
        assertThat(paystub.earnings().get(0).hours().value().scale()).isEqualTo(2);
        // A salaried row prints no hours and no rate; nulls stay nulls rather than becoming zeros,
        // which would fabricate an hours-times-rate claim of 0 x 0 = 400.
        assertThat(paystub.earnings().get(1).hours()).isNotNull();
        assertThat(paystub.earnings().get(1).hours().value()).isNull();
        assertThat(paystub.earnings().get(1).rate().value()).isNull();
    }

    @Test
    void an_absent_earnings_table_yields_an_empty_list_not_a_null() {
        String noTables =
                fullJson("Weekly")
                        .replaceAll("(?s)\"earnings\": \\[.*?\\],", "\"earnings\": [],")
                        .replaceAll("(?s)\"deductions\": \\[.*?\\]", "\"deductions\": []");

        AiExtractionResult parsed = parser.parse(ok(noTables));

        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.OK);
        PaystubExtraction paystub = (PaystubExtraction) parsed.extraction();
        assertThat(paystub.earnings()).isEmpty();
        assertThat(paystub.deductions()).isEmpty();
    }

    @Test
    void an_unrecognized_pay_frequency_keeps_the_text_but_yields_no_value() {
        AiExtractionResult parsed = parser.parse(ok(fullJson("every two weeks")));

        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.OK);
        PaystubExtraction paystub = (PaystubExtraction) parsed.extraction();
        assertThat(paystub.payFrequency().value()).isNull();
        assertThat(paystub.payFrequency().text()).isEqualTo("every two weeks");
    }

    @Test
    void a_missing_required_property_refuses_the_whole_response() {
        String missingNetPay = fullJson("Weekly").replace("\"netPay\"", "\"notNetPay\"");

        AiExtractionResult parsed = parser.parse(ok(missingNetPay));

        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(parsed.reason()).isEqualTo("validation_error");
        assertThat(parsed.extraction()).isNull();
        assertThat(parsed.structuredJson()).isNull();
    }

    @Test
    void an_unparseable_money_value_refuses_the_whole_response() {
        String badMoney = fullJson("Weekly").replace("\"2400.00\"", "\"about 2400\"");

        AiExtractionResult parsed = parser.parse(ok(badMoney));

        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(parsed.reason()).isEqualTo("validation_error");
    }

    @Test
    void a_non_ok_raw_result_passes_through_untouched() {
        AiExtractionResult error =
                new AiExtractionResult(
                        null, "p", "m", AiExtractionStatus.ERROR, AiTokenCounts.ZERO, "boom");

        assertThat(parser.parse(error)).isSameAs(error);
    }

    private static AiExtractionResult ok(String structuredJson) {
        return new AiExtractionResult(
                structuredJson, "p", "m", AiExtractionStatus.OK, AiTokenCounts.ZERO, null);
    }

    private static String fullJson(String frequency) {
        return """
                {
                  "borrowerName": { "value": "Alex Q. Sample", "text": "ALEX Q SAMPLE", "page": 1, "confidence": "HIGH" },
                  "employerName": { "value": "Example Widgets LLC", "text": "EXAMPLE WIDGETS LLC", "page": 1, "confidence": "HIGH" },
                  "payPeriodStart": { "value": "2026-04-01", "text": "04/01/2026", "page": 1, "confidence": "HIGH" },
                  "payPeriodEnd": { "value": "2026-04-14", "text": "04/14/2026", "page": 1, "confidence": "HIGH" },
                  "payDate": { "value": "2026-04-17", "text": "04/17/2026", "page": 1, "confidence": "HIGH" },
                  "payFrequency": { "value": "%s", "text": "%s", "page": 1, "confidence": "HIGH" },
                  "currentGrossPay": { "value": "2400.00", "text": "$2,400.00", "page": 1, "confidence": "HIGH" },
                  "ytdGrossPay": { "value": "19200.00", "text": "$19,200.00", "page": 1, "confidence": "HIGH" },
                  "netPay": { "value": "1850.25", "text": "$1,850.25", "page": 1, "confidence": "HIGH", "handwritten": true },
                  "federalWithholding": { "value": "321.09", "text": "$321.09", "page": 1, "confidence": "MEDIUM" },
                  "currentTotalDeductions": { "value": "549.75", "text": "$549.75", "page": 1, "confidence": "HIGH" },
                  "ytdTotalDeductions": { "value": "4398.00", "text": "$4,398.00", "page": 1, "confidence": "HIGH" },
                  "ytdNetPay": { "value": "14802.00", "text": "$14,802.00", "page": 1, "confidence": "HIGH" },
                  "earnings": [
                    {
                      "description": { "value": "Regular", "text": "Regular", "page": 1, "confidence": "HIGH" },
                      "hours": { "value": "80.00", "text": "80.00", "page": 1, "confidence": "HIGH" },
                      "rate": { "value": "25.0000", "text": "25.0000", "page": 1, "confidence": "HIGH" },
                      "currentAmount": { "value": "2000.00", "text": "$2,000.00", "page": 1, "confidence": "HIGH" },
                      "ytdAmount": { "value": "16000.00", "text": "$16,000.00", "page": 1, "confidence": "HIGH" }
                    },
                    {
                      "description": { "value": "Salary", "text": "Salary", "page": 1, "confidence": "HIGH" },
                      "hours": { "value": null, "text": null, "page": null, "confidence": "LOW" },
                      "rate": { "value": null, "text": null, "page": null, "confidence": "LOW" },
                      "currentAmount": { "value": "400.00", "text": "$400.00", "page": 1, "confidence": "HIGH" },
                      "ytdAmount": { "value": "3200.00", "text": "$3,200.00", "page": 1, "confidence": "HIGH" }
                    }
                  ],
                  "deductions": [
                    {
                      "description": { "value": "Federal Income Tax", "text": "Fed Income Tax", "page": 1, "confidence": "HIGH" },
                      "currentAmount": { "value": "321.09", "text": "$321.09", "page": 1, "confidence": "HIGH" },
                      "ytdAmount": { "value": "2568.72", "text": "$2,568.72", "page": 1, "confidence": "HIGH" }
                    }
                  ]
                }
                """
                .formatted(frequency, frequency);
    }
}
