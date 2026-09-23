package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ledger, built from parsed facts rather than transcribed by a model.
 *
 * <p>Field names here are the ones a deployment configures, so these tests exercise the mapping
 * mechanism and not one particular engine's vocabulary — pointing the configuration at different
 * names must be all that a different engine schema requires.
 */
class AssetsLedgerToolExecutorTest {

    private static final AssetsLedgerProperties MAPPING = new AssetsLedgerProperties(
            List.of("BANK_STATEMENT"),
            new AssetsLedgerProperties.Account(
                    "institution_name", "account_number_last4", "account_type",
                    "statement_period_start", "statement_period_end",
                    "beginning_balance", "ending_balance"),
            new AssetsLedgerProperties.Transaction(
                    "txn_date", "txn_description", "txn_amount", "txn_running_balance",
                    null, List.of()));

    private final AssetsLedgerToolExecutor executor = new AssetsLedgerToolExecutor(MAPPING);

    @Test
    void aStatementBecomesOneAccountWithItsRowsInEnvelopeOrder() {
        JsonNode ledger = executor.execute(envelope(statement()));

        assertEquals(1, ledger.path("accounts").size());
        JsonNode account = ledger.path("accounts").get(0);
        assertEquals("Wells Fargo", account.path("institution").asText());
        assertEquals("7418", account.path("maskedNumber").asText());
        assertEquals("checking", account.path("type").asText());
        assertEquals("2026-06-01", account.path("statementPeriod").path("start").asText());
        assertEquals("2026-06-30", account.path("statementPeriod").path("end").asText());
        assertEquals("100.00", account.path("beginningBalance").asText());
        assertEquals("5265.00", account.path("endingBalance").asText());
        assertTrue(account.path("transcriptionComplete").asBoolean());

        JsonNode rows = account.path("transactions");
        assertEquals(2, rows.size());
        assertEquals("MOBILE DEPOSIT", rows.get(0).path("description").asText());
        assertEquals("2026-06-09", rows.get(0).path("date").asText());
        assertEquals("5200.00", rows.get(0).path("amount").asText());
        assertEquals("OVERDRAFT FEE", rows.get(1).path("description").asText());
        assertEquals("-35.00", rows.get(1).path("amount").asText());
    }

    /**
     * The ledger this produces has to satisfy the same reconciliation the model's did:
     * beginning + sum(transactions) == ending. If it does not, every downstream total is
     * reported as untrustworthy, so this is the property that makes the swap safe.
     */
    @Test
    void theBuiltLedgerReconciles() {
        JsonNode account = executor.execute(envelope(statement())).path("accounts").get(0);

        BigDecimal sum = new BigDecimal(account.path("beginningBalance").asText());
        for (JsonNode row : account.path("transactions")) {
            sum = sum.add(new BigDecimal(row.path("amount").asText()));
        }
        assertEquals(0, sum.compareTo(new BigDecimal(account.path("endingBalance").asText())));
    }

    /** Descriptions travel exactly as the engine normalized them; the keyword rules match on them. */
    @Test
    void descriptionsAreNotTidied() {
        JsonNode rows = executor.execute(envelope(statement()))
                .path("accounts").get(0).path("transactions");

        assertEquals("MOBILE DEPOSIT", rows.get(0).path("description").asText());
        assertFalse(rows.get(0).path("description").asText().toLowerCase().contains("deposit of"));
    }

    /**
     * A row with no usable amount cannot enter a total, and is reported rather than dropped.
     *
     * <p>Dropping it silently is the exact failure the ledger contract exists to prevent: the
     * totals would still look confident and would be wrong.
     */
    @Test
    void aRowWithNoAmountFlagsTheAccountIncompleteInsteadOfVanishing() {
        List<EngineResultEnvelope.FieldOccurrence> fields = new ArrayList<>(statement());
        fields.add(missing("txn_amount", "row-3"));
        fields.add(text("txn_description", "row-3", "UNREADABLE LINE"));

        JsonNode account = executor.execute(envelope(fields)).path("accounts").get(0);

        assertFalse(account.path("transcriptionComplete").asBoolean(),
                "an unusable row must be reported, not silently omitted");
        assertEquals(2, account.path("transactions").size());
    }

    /** A MISSING header field is absent, never zero — the reconciliation depends on that. */
    @Test
    void anAbsentBalanceIsOmittedRatherThanDefaulted() {
        List<EngineResultEnvelope.FieldOccurrence> fields = new ArrayList<>();
        for (EngineResultEnvelope.FieldOccurrence field : statement()) {
            if (!field.name().equals("beginning_balance")) {
                fields.add(field);
            }
        }

        JsonNode account = executor.execute(envelope(fields)).path("accounts").get(0);

        assertTrue(account.path("beginningBalance").isMissingNode(),
                "an invented balance would make the completeness proof circular");
        assertEquals("5265.00", account.path("endingBalance").asText());
    }

    /** Documents that are not statements are ignored, not an error. */
    @Test
    void otherDocumentTypesAreSkipped() {
        EngineResultEnvelope envelope = envelopeOf(
                document("BANK_STATEMENT", 1, statement()),
                document("PAYSTUB", 2, List.of(text("employer_name", null, "Acme"))));

        assertEquals(1, executor.execute(envelope).path("accounts").size());
    }

    /** An engine reporting magnitude plus a separate indicator still yields a signed ledger. */
    @Test
    void aSeparateDirectionFieldProducesSignedAmounts() {
        AssetsLedgerProperties directional = new AssetsLedgerProperties(
                List.of("BANK_STATEMENT"), MAPPING.account(),
                new AssetsLedgerProperties.Transaction(
                        "txn_date", "txn_description", "txn_amount", null,
                        "txn_direction", List.of("CREDIT")));

        List<EngineResultEnvelope.FieldOccurrence> fields = List.of(
                number("txn_amount", "row-1", "5200.00"),
                text("txn_direction", "row-1", "CREDIT"),
                number("txn_amount", "row-2", "35.00"),
                text("txn_direction", "row-2", "DEBIT"));

        JsonNode rows = new AssetsLedgerToolExecutor(directional)
                .execute(envelope(fields)).path("accounts").get(0).path("transactions");

        assertEquals("5200.00", rows.get(0).path("amount").asText());
        assertEquals("-35.00", rows.get(1).path("amount").asText(),
                "a debit is negated exactly once");
    }

    /**
     * An unconfigured deployment refuses rather than returning an empty ledger.
     *
     * <p>An empty ledger reconciles trivially, so it would be reported as a clean and complete
     * review of statements nobody read.
     */
    @Test
    void anUnconfiguredMappingRefusesInsteadOfBuildingAnEmptyLedger() {
        AssetsLedgerToolExecutor unconfigured =
                new AssetsLedgerToolExecutor(new AssetsLedgerProperties(List.of(), null, null));

        assertFalse(new AssetsLedgerProperties(List.of(), null, null).usable());
        assertThrows(IllegalStateException.class,
                () -> unconfigured.execute(envelope(statement())));
    }

    /** An unset environment variable binds as one empty string and must not count as configured. */
    @Test
    void aBlankDocumentTypeIsNotAConfiguredDeployment() {
        assertFalse(new AssetsLedgerProperties(List.of(""), null,
                new AssetsLedgerProperties.Transaction(null, null, "txn_amount", null, null, null))
                .usable());
    }

    /** The output is this tool's own domain payload, so it replaces the model's. */
    /**
     * The ledger is persisted and returned to the browser, so it is a derived store: an occurrence
     * the engine flagged sensitive must be treated as absent, never copied through — even though
     * it is FOUND and normalized.
     */
    @Test
    void aSensitiveOccurrenceIsTreatedAsAbsentNotCopiedIntoTheLedger() {
        List<EngineResultEnvelope.FieldOccurrence> fields = new java.util.ArrayList<>(statement());
        fields.removeIf(f -> f.name().equals("account_number_last4"));
        fields.add(sensitive("account_number_last4", null, "TEXT",
                new EngineResultEnvelope.NormalizedValue("123456789012", null, null, null)));
        fields.removeIf(f -> f.name().equals("txn_description") && "row-1".equals(f.groupKey()));
        fields.add(sensitive("txn_description", "row-1", "TEXT",
                new EngineResultEnvelope.NormalizedValue("PAYROLL SSN 123-45-6789", null, null, null)));

        JsonNode ledger = executor.execute(envelope(fields));

        String rendered = ledger.toString();
        assertFalse(rendered.contains("123456789012"), rendered);
        assertFalse(rendered.contains("123-45-6789"), rendered);
        JsonNode account = ledger.path("accounts").get(0);
        assertTrue(account.path("maskedNumber").isMissingNode(), "sensitive field must be absent");
        assertEquals("Wells Fargo", account.path("institution").asText(),
                "non-sensitive siblings are unaffected");
    }

    @Test
    void theToolDeclaresItselfTheDomainProducer() {
        assertTrue(executor.producesDomain());
        assertTrue(executor.inputSchemaSha256().matches("[0-9a-f]{64}"));
        assertTrue(executor.outputSchemaSha256().matches("[0-9a-f]{64}"));
        assertNotEquals(executor.inputSchemaSha256(), executor.outputSchemaSha256());
    }

    // ---------------------------------------------------------------- fixtures

    /** One reconciling statement: 100.00 + 5200.00 − 35.00 = 5265.00. */
    private static List<EngineResultEnvelope.FieldOccurrence> statement() {
        return List.of(
                text("institution_name", null, "Wells Fargo"),
                text("account_number_last4", null, "7418"),
                text("account_type", null, "checking"),
                date("statement_period_start", null, "2026-06-01"),
                date("statement_period_end", null, "2026-06-30"),
                number("beginning_balance", null, "100.00"),
                number("ending_balance", null, "5265.00"),
                date("txn_date", "row-1", "2026-06-09"),
                text("txn_description", "row-1", "MOBILE DEPOSIT"),
                number("txn_amount", "row-1", "5200.00"),
                date("txn_date", "row-2", "2026-06-27"),
                text("txn_description", "row-2", "OVERDRAFT FEE"),
                number("txn_amount", "row-2", "-35.00"));
    }

    private static EngineResultEnvelope envelope(
            List<EngineResultEnvelope.FieldOccurrence> fields) {
        return envelopeOf(document("BANK_STATEMENT", 1, fields));
    }

    private static EngineResultEnvelope envelopeOf(
            EngineResultEnvelope.LogicalDocument... documents) {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("ledger".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                new EngineResultEnvelope.Generation(
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        1, 1, "ab".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(),
                List.of(),
                List.of(documents),
                List.of(),
                new EngineResultEnvelope.Provenance(
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        List.of(new EngineResultEnvelope.StageAttempt(
                                "EXTRACTING", 1, "cd".repeat(32), "1.4.0", null))));
    }

    private static EngineResultEnvelope.LogicalDocument document(
            String type, int ordinal, List<EngineResultEnvelope.FieldOccurrence> fields) {
        return new EngineResultEnvelope.LogicalDocument(
                UUID.randomUUID(), type, ordinal, List.of(), fields);
    }

    private static EngineResultEnvelope.FieldOccurrence text(
            String name, String groupKey, String value) {
        return occurrence(name, groupKey, "TEXT",
                new EngineResultEnvelope.NormalizedValue(value, null, null, null));
    }

    private static EngineResultEnvelope.FieldOccurrence number(
            String name, String groupKey, String value) {
        return occurrence(name, groupKey, "MONEY",
                new EngineResultEnvelope.NormalizedValue(null, new BigDecimal(value), null, null));
    }

    private static EngineResultEnvelope.FieldOccurrence date(
            String name, String groupKey, String value) {
        return occurrence(name, groupKey, "DATE",
                new EngineResultEnvelope.NormalizedValue(null, null, LocalDate.parse(value), null));
    }

    private static EngineResultEnvelope.FieldOccurrence missing(String name, String groupKey) {
        return new EngineResultEnvelope.FieldOccurrence(
                name, groupKey, EngineResultEnvelope.FieldStatus.MISSING, "MONEY", null, null,
                null, schema(), "NONE", "5.1.0", BigDecimal.ZERO, null, "NOT_VALIDATED", false,
                List.of());
    }

    private static EngineResultEnvelope.FieldOccurrence occurrence(
            String name, String groupKey, String dataType,
            EngineResultEnvelope.NormalizedValue normalized) {
        return new EngineResultEnvelope.FieldOccurrence(
                name, groupKey, EngineResultEnvelope.FieldStatus.FOUND, dataType, null, null,
                normalized, schema(), "ANCHOR_LABEL", "5.1.0", new BigDecimal("0.99"), null,
                "VALID", false, List.of());
    }

    private static EngineResultEnvelope.FieldOccurrence sensitive(
            String name, String groupKey, String dataType,
            EngineResultEnvelope.NormalizedValue normalized) {
        return new EngineResultEnvelope.FieldOccurrence(
                name, groupKey, EngineResultEnvelope.FieldStatus.FOUND, dataType, null, null,
                normalized, schema(), "ANCHOR_LABEL", "5.1.0", new BigDecimal("0.99"), null,
                "VALID", true, List.of());
    }

    private static EngineResultEnvelope.SchemaRef schema() {
        return new EngineResultEnvelope.SchemaRef(UUID.randomUUID(), "2024.1");
    }
}
