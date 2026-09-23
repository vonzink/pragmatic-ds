package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The W-2 parser follows the paystub parser's discipline: a schema violation refuses the WHOLE
 * response, cells share the bank-statement normalizers, and the two W-2-specific cells (EIN,
 * tax year) canonicalize to exactly the form the rules rows store — or to null. Missing over
 * wrong.
 */
class W2ExtractionParserTest {

    private final W2ExtractionParser parser = new W2ExtractionParser();

    @Test
    void a_valid_w2_parses_every_cell() {
        AiExtractionResult parsed = parser.parse(ok(json("98-7654321", "98-7654321", "2025", "2025")));

        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.OK);
        W2Extraction w2 = (W2Extraction) parsed.extraction();
        assertThat(w2.documentType()).isEqualTo(AiDocumentType.W2);
        assertThat(w2.employeeName().value()).isEqualTo("Jordan Q. Fixture");
        assertThat(w2.employerName().value()).isEqualTo("ACME WIDGETS LLC");
        assertThat(w2.employerEin().value()).isEqualTo("98-7654321");
        assertThat(w2.taxYear().value()).isEqualTo("2025");
        assertThat(w2.wagesTipsOtherComp().value()).isEqualByComparingTo(new BigDecimal("61538.72"));
        assertThat(w2.federalIncomeTaxWithheld().value()).isEqualByComparingTo(new BigDecimal("9730.44"));
        assertThat(w2.socialSecurityWages().value()).isEqualByComparingTo(new BigDecimal("62538.72"));
        assertThat(w2.medicareWages().value()).isEqualByComparingTo(new BigDecimal("62110.09"));
        assertThat(w2.stateWages().value()).isEqualByComparingTo(new BigDecimal("60004.10"));
    }

    @Test
    void an_ein_printed_without_its_hyphen_canonicalizes_to_the_rules_form() {
        W2Extraction w2 = parse(json("987654321", "987654321", "2025", "2025"));
        assertThat(w2.employerEin().value()).isEqualTo("98-7654321");
        assertThat(w2.employerEin().text()).isEqualTo("987654321");

        W2Extraction spaced = parse(json("98 7654321", "98 7654321", "2025", "2025"));
        assertThat(spaced.employerEin().value()).isEqualTo("98-7654321");
    }

    @Test
    void an_ein_whose_digits_are_not_in_the_printed_text_is_dropped() {
        // The model "read" 98-7654321 but the page shows a different number: missing over wrong.
        W2Extraction w2 = parse(json("98-7654321", "12-3456789", "2025", "2025"));
        assertThat(w2.employerEin().value()).isNull();
        assertThat(w2.employerEin().text()).isEqualTo("12-3456789");
    }

    @Test
    void an_ein_that_is_not_nine_digits_is_dropped() {
        assertThat(parse(json("98-765432", "98-765432", "2025", "2025")).employerEin().value())
                .isNull();
    }

    @Test
    void only_a_bare_four_digit_year_survives_as_tax_year() {
        assertThat(parse(json("98-7654321", "98-7654321", "2025", "W-2 2025")).taxYear().value())
                .isEqualTo("2025");
        assertThat(parse(json("98-7654321", "98-7654321", "25", "25")).taxYear().value()).isNull();
        assertThat(parse(json("98-7654321", "98-7654321", "2025-12-31", "2025-12-31")).taxYear().value())
                .isNull();
        assertThat(parse(json("98-7654321", "98-7654321", "2025", "2024")).taxYear().value())
                .as("value not in printed text")
                .isNull();
    }

    @Test
    void an_ssn_shape_read_into_the_ein_cell_scrubs_the_whole_cell() {
        // The model misread Box a (the SSN) into employerEin: nine digits, three model-shaped
        // fields, same corner of the form. W3 says an SSN never persists anywhere — neither its
        // digits nor its printed text.
        W2Extraction w2 = parse(json("123-45-6789", "123-45-6789", "2025", "2025"));
        assertThat(w2.employerEin().value()).isNull();
        assertThat(w2.employerEin().text()).isNull();
        // The rest of the response is unaffected — one scrubbed cell costs only itself.
        assertThat(w2.taxYear().value()).isEqualTo("2025");
        assertThat(w2.wagesTipsOtherComp().value()).isEqualByComparingTo(new BigDecimal("61538.72"));
    }

    @Test
    void an_ssn_shape_anywhere_in_a_cells_printed_text_scrubs_that_cell() {
        String withSsnInName =
                json("98-7654321", "98-7654321", "2025", "2025")
                        .replace(
                                "\"text\": \"JORDAN Q FIXTURE\"",
                                "\"text\": \"JORDAN Q FIXTURE 123-45-6789\"");
        W2Extraction w2 = parse(withSsnInName);
        assertThat(w2.employeeName().value()).isNull();
        assertThat(w2.employeeName().text()).isNull();
        // A sibling cell that never carried the shape is untouched.
        assertThat(w2.employerEin().value()).isEqualTo("98-7654321");
    }

    @Test
    void an_ein_shape_is_not_mistaken_for_an_ssn_shape() {
        // NN-NNNNNNN (2-7) is not NNN-NN-NNNN (3-2-4) — the EIN path stays green.
        assertThat(parse(json("98-7654321", "98-7654321", "2025", "2025")).employerEin().value())
                .isEqualTo("98-7654321");
    }

    @Test
    void a_19xx_year_is_no_longer_accepted_as_a_tax_year() {
        assertThat(parse(json("98-7654321", "98-7654321", "1998", "1998")).taxYear().value())
                .isNull();
    }

    @Test
    void a_money_value_not_in_its_printed_text_is_dropped() {
        String wrongBox1 =
                json("98-7654321", "98-7654321", "2025", "2025")
                        .replace("\"value\": \"61538.72\"", "\"value\": \"62538.72\"");
        assertThat(parse(wrongBox1).wagesTipsOtherComp().value()).isNull();
    }

    @Test
    void a_response_that_includes_the_ssn_is_refused_whole() {
        String withSsn =
                json("98-7654321", "98-7654321", "2025", "2025")
                        .replaceFirst(
                                "\\{",
                                "{ \"employeeSsn\": { \"value\": \"123-45-6789\", \"text\":"
                                        + " \"123-45-6789\", \"page\": 1, \"confidence\": \"HIGH\" },");

        AiExtractionResult parsed = parser.parse(ok(withSsn));

        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(parsed.reason()).isEqualTo("validation_error");
        assertThat(parsed.extraction()).isNull();
        assertThat(parsed.structuredJson()).isNull();
    }

    @Test
    void a_missing_required_key_is_refused_whole() {
        String missing = json("98-7654321", "98-7654321", "2025", "2025")
                .replaceAll("(?s)\"stateWages\": \\{[^}]*\\},?", "")
                .replaceAll(",\\s*}\\s*$", "}");
        assertThat(parser.parse(ok(missing)).status()).isEqualTo(AiExtractionStatus.ERROR);
    }

    @Test
    void a_non_ok_provider_result_passes_through_untouched() {
        AiExtractionResult failed =
                new AiExtractionResult(null, "p", "m", AiExtractionStatus.ERROR, AiTokenCounts.ZERO, "provider_transient");
        assertThat(parser.parse(failed)).isSameAs(failed);
    }

    private W2Extraction parse(String json) {
        AiExtractionResult parsed = parser.parse(ok(json));
        assertThat(parsed.status()).isEqualTo(AiExtractionStatus.OK);
        return (W2Extraction) parsed.extraction();
    }

    private static AiExtractionResult ok(String structuredJson) {
        return new AiExtractionResult(
                structuredJson, "p", "m", AiExtractionStatus.OK, AiTokenCounts.ZERO, null);
    }

    private static String json(String einValue, String einText, String yearValue, String yearText) {
        return """
                {
                  "employeeName": { "value": "Jordan Q. Fixture", "text": "JORDAN Q FIXTURE", "page": 1, "confidence": "HIGH" },
                  "employerName": { "value": "ACME WIDGETS LLC", "text": "ACME WIDGETS LLC", "page": 1, "confidence": "HIGH" },
                  "employerEin": { "value": "%s", "text": "%s", "page": 1, "confidence": "HIGH" },
                  "taxYear": { "value": "%s", "text": "%s", "page": 1, "confidence": "HIGH" },
                  "wagesTipsOtherComp": { "value": "61538.72", "text": "61,538.72", "page": 1, "confidence": "HIGH" },
                  "federalIncomeTaxWithheld": { "value": "9730.44", "text": "9,730.44", "page": 1, "confidence": "HIGH" },
                  "socialSecurityWages": { "value": "62538.72", "text": "62,538.72", "page": 1, "confidence": "HIGH" },
                  "medicareWages": { "value": "62110.09", "text": "62,110.09", "page": 1, "confidence": "MEDIUM" },
                  "stateWages": { "value": "60004.10", "text": "60,004.10", "page": 1, "confidence": "MEDIUM" }
                }
                """
                .formatted(einValue, einText, yearValue, yearText);
    }
}
