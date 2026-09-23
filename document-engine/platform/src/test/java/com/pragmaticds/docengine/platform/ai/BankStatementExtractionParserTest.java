package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BankStatementExtractionParserTest {

    private static final AiTokenCounts TOKENS = new AiTokenCounts(101, 37, 80, 21);
    private final BankStatementExtractionParser parser = new BankStatementExtractionParser();

    @Test
    void parses_clean_multipage_fixture_into_typed_cells_and_preserves_evidence()
            throws IOException {
        AiExtractionResult result = parser.parse(rawFixture("good-clean-multipage.json"));

        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(result.reason()).isNull();
        assertThat(result.provider()).isEqualTo("synthetic-provider");
        assertThat(result.tokenCounts()).isEqualTo(TOKENS);
        assertThat(result.extraction()).isInstanceOf(BankStatementExtraction.class);

        BankStatementExtraction statement = (BankStatementExtraction) result.extraction();
        assertThat(statement.documentType()).isEqualTo(AiDocumentType.BANK_STATEMENT);
        assertThat(statement.summary().statementPeriodStart().value())
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(statement.summary().beginningBalance().value())
                .isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(statement.summary().endingBalance().text()).isEqualTo("$1,500.00");
        assertThat(statement.summary().endingBalance().page()).isEqualTo(2);
        assertThat(statement.transactions()).hasSize(3);
        assertThat(statement.transactions().get(0).direction())
                .isEqualTo(BankStatementExtraction.Direction.DEPOSIT);
        assertThat(statement.transactions().get(2).page()).isEqualTo(2);
        assertThat(statement.checks()).singleElement().satisfies(check -> {
            assertThat(check.checkNumber().value()).isEqualTo("1001");
            assertThat(check.datePaid().value()).isEqualTo(LocalDate.of(2026, 7, 20));
            assertThat(check.amount().value()).isEqualByComparingTo("1000.00");
            assertThat(check.page()).isEqualTo(2);
        });
    }

    @Test
    void the_handwritten_tag_survives_parsing_and_absence_stays_null() throws IOException {
        // Phase H: the model's handwriting tag is one leg of the trust ceiling's OR-rule, so it
        // must round-trip the parse — and every cell authored before the tag existed must keep
        // reading as null, never as false-with-invented-certainty.
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(fixture("good-clean-multipage.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        root.path("summary").path("beginningBalance"))
                .put("handwritten", true);

        AiExtractionResult result = parser.parse(rawJson(mapper.writeValueAsString(root)));

        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        BankStatementExtraction statement = (BankStatementExtraction) result.extraction();
        assertThat(statement.summary().beginningBalance().handwritten()).isTrue();
        assertThat(statement.summary().endingBalance().handwritten()).isNull();
    }

    @Test
    void preserves_explicit_null_cells_instead_of_inventing_values() throws IOException {
        AiExtractionResult result = parser.parse(rawFixture("good-missing-summary-field.json"));

        BankStatementExtraction statement = (BankStatementExtraction) result.extraction();
        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(statement.summary().bankName().value()).isNull();
        assertThat(statement.summary().bankName().text()).isNull();
        assertThat(statement.summary().bankName().page()).isNull();
        assertThat(statement.summary().bankName().confidence())
                .isEqualTo(BankStatementExtraction.Confidence.LOW);
    }

    @Test
    void normalizes_parenthesized_money_and_common_us_date_values() throws IOException {
        String fixture =
                fixture("good-negative-and-parens-money.json")
                        .replace("\"-50.00\"", "\"($50.00)\"")
                        .replace("\"2026-05-01\"", "\"5/1/26\"")
                        .replace("\"2026-05-31\"", "\"May 31, 2026\"")
                        .replace(
                                "\"75.00\", \"text\": \"$75.00\"",
                                "\"$1,234.56\", \"text\": \"$1,234.56\"");

        AiExtractionResult result = parser.parse(rawJson(fixture));

        BankStatementExtraction statement = (BankStatementExtraction) result.extraction();
        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(statement.summary().beginningBalance().value())
                .isEqualByComparingTo(new BigDecimal("-50.00"));
        assertThat(statement.summary().beginningBalance().text()).isEqualTo("($50.00)");
        assertThat(statement.summary().endingBalance().value())
                .isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(statement.summary().statementPeriodStart().value())
                .isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(statement.summary().statementPeriodStart().text()).isEqualTo("5/1/26");
        assertThat(statement.summary().statementPeriodEnd().value())
                .isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    void impossible_calendar_date_returns_error_instead_of_being_adjusted() throws IOException {
        String fixture =
                fixture("good-negative-and-parens-money.json")
                        .replace("\"2026-05-01\"", "\"2/30/26\"");

        AiExtractionResult result = parser.parse(rawJson(fixture));

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("validation_error");
        assertThat(result.extraction()).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "malformed-extra-property.json",
                "malformed-missing-required-property.json",
                "malformed-wrong-type.json",
                "malformed-unparseable-money.json"
            })
    void malformed_fixture_returns_error_without_partial_object_or_content_logs(
            String fixture, CapturedOutput output) throws IOException {
        String rawFixture = fixture(fixture);

        AiExtractionResult result = parser.parse(rawJson(rawFixture));

        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("validation_error");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.extraction()).isNull();
        assertThat(result.provider()).isEqualTo("synthetic-provider");
        assertThat(result.tokenCounts()).isEqualTo(TOKENS);
        assertThat(output.getAll()).doesNotContain("X Bank").doesNotContain("Z Bank");
    }

    @Test
    void null_input_never_throws_and_returns_content_free_error() {
        assertThatCode(() -> parser.parse(null)).doesNotThrowAnyException();

        AiExtractionResult result = parser.parse(null);
        assertThat(result.status()).isEqualTo(AiExtractionStatus.ERROR);
        assertThat(result.reason()).isEqualTo("validation_error");
        assertThat(result.structuredJson()).isNull();
        assertThat(result.extraction()).isNull();
    }

    // ── A money value must be derivable from the text the model says it read ────────────

    /**
     * The defect, reproduced from the statement that produced it. A real Chase statement prints
     * {@code Checks Paid -390.00} and {@code Electronic Withdrawals -240.36} as two separate lines
     * and no combined total. The schema has one {@code totalWithdrawals} slot, so the model summed
     * them: it quoted BOTH printed lines as its text and reported their sum as its value. The engine
     * persisted, as a printed figure, a number that was never printed.
     *
     * <p>The sum was arithmetically correct, which is what made it dangerous — it satisfied the
     * reconciler's deposit/withdrawal partition, and a ledger that should have been
     * UNABLE_TO_VALIDATE was stamped RECONCILED.
     */
    @Test
    void a_total_the_statement_never_printed_is_dropped_rather_than_persisted() throws IOException {
        String fixture =
                fixture("good-clean-multipage.json")
                        .replace(
                                "\"totalWithdrawals\": { \"value\": \"1500.00\", \"text\": \"$1,500.00\"",
                                "\"totalWithdrawals\": { \"value\": \"-630.36\","
                                        + " \"text\": \"-390.00\\n-240.36\"");

        AiExtractionResult result = parser.parse(rawJson(fixture));

        BankStatementExtraction statement = (BankStatementExtraction) result.extraction();
        assertThat(result.status()).isEqualTo(AiExtractionStatus.OK);
        assertThat(statement.summary().totalWithdrawals().value())
                .as("a value no printed token supports is not a printed value")
                .isNull();
        assertThat(statement.summary().totalWithdrawals().text())
                .as("what the model claims to have read is still shown to the reviewer")
                .isEqualTo("-390.00\n-240.36");
    }

    /** One bad total costs that total, never the rows that anchored perfectly beside it. */
    @Test
    void dropping_one_uncorroborated_total_leaves_the_rest_of_the_statement_intact()
            throws IOException {
        String fixture =
                fixture("good-clean-multipage.json")
                        .replace(
                                "\"totalWithdrawals\": { \"value\": \"1500.00\", \"text\": \"$1,500.00\"",
                                "\"totalWithdrawals\": { \"value\": \"-630.36\","
                                        + " \"text\": \"-390.00\\n-240.36\"");

        BankStatementExtraction statement =
                (BankStatementExtraction) parser.parse(rawJson(fixture)).extraction();

        assertThat(statement.summary().beginningBalance().value()).isEqualByComparingTo("1000.00");
        assertThat(statement.summary().endingBalance().value()).isEqualByComparingTo("1500.00");
        assertThat(statement.summary().totalDeposits().value()).isEqualByComparingTo("2000.00");
        assertThat(statement.transactions()).hasSize(3);
        assertThat(statement.transactions())
                .allSatisfy(txn -> assertThat(txn.amount().value()).isNotNull());
        assertThat(statement.checks()).singleElement().satisfies(
                check -> assertThat(check.amount().value()).isEqualByComparingTo("1000.00"));
    }

    /**
     * Sign is deliberately not part of the check. A checks-paid table prints {@code 130.00} for a
     * withdrawal the ledger carries as {@code -130.00}; demanding the sign match would reject every
     * such cell on every bank statement in the corpus.
     */
    @Test
    void a_withdrawal_printed_without_its_minus_sign_still_corroborates() throws IOException {
        // The second transaction is the only "500.00" cell in the fixture, so this rewrites exactly
        // one amount — the 1,000.00 pair appears on both a transaction and a check.
        String fixture =
                fixture("good-clean-multipage.json")
                        .replace(
                                "\"amount\": { \"value\": \"500.00\", \"text\": \"500.00\"",
                                "\"amount\": { \"value\": \"-500.00\", \"text\": \"500.00\"");

        BankStatementExtraction statement =
                (BankStatementExtraction) parser.parse(rawJson(fixture)).extraction();

        assertThat(statement.transactions().get(1).amount().value())
                .isEqualByComparingTo("-500.00");
    }

    /** Nor is scale: {@code $1,000} on the page corroborates {@code 1000.00} in the value. */
    @Test
    void a_scale_difference_between_value_and_printed_text_still_corroborates()
            throws IOException {
        String fixture =
                fixture("good-clean-multipage.json")
                        .replace("\"text\": \"$1,000.00\"", "\"text\": \"$1,000\"");

        BankStatementExtraction statement =
                (BankStatementExtraction) parser.parse(rawJson(fixture)).extraction();

        assertThat(statement.summary().beginningBalance().value())
                .isEqualByComparingTo("1000.00");
    }

    /** A number with nothing quoted behind it is exactly the unverifiable case. */
    @Test
    void a_money_value_with_blank_printed_text_is_dropped() throws IOException {
        String fixture =
                fixture("good-clean-multipage.json")
                        .replace("\"text\": \"$1,000.00\"", "\"text\": \"   \"");

        BankStatementExtraction statement =
                (BankStatementExtraction) parser.parse(rawJson(fixture)).extraction();

        assertThat(statement.summary().beginningBalance().value()).isNull();
    }

    /** A single printed token that simply disagrees with the value is a misread, and drops too. */
    @Test
    void a_value_contradicted_by_its_own_printed_text_is_dropped() throws IOException {
        String fixture =
                fixture("good-clean-multipage.json")
                        .replace(
                                "\"beginningBalance\": { \"value\": \"1000.00\"",
                                "\"beginningBalance\": { \"value\": \"1900.00\"");

        BankStatementExtraction statement =
                (BankStatementExtraction) parser.parse(rawJson(fixture)).extraction();

        assertThat(statement.summary().beginningBalance().value()).isNull();
        assertThat(statement.summary().beginningBalance().text()).isEqualTo("$1,000.00");
    }

    private static AiExtractionResult rawFixture(String fixtureName) throws IOException {
        return rawJson(fixture(fixtureName));
    }

    private static AiExtractionResult rawJson(String structuredJson) {
        return new AiExtractionResult(
                structuredJson,
                null,
                "synthetic-provider",
                "synthetic-model",
                AiExtractionStatus.OK,
                TOKENS,
                null);
    }

    private static String fixture(String name) throws IOException {
        String path = "/ai/golden/" + name;
        try (InputStream input =
                BankStatementExtractionParserTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing synthetic fixture: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
