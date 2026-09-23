package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the assets transaction ledger from the parsed envelope, with no model in the loop.
 *
 * <p>The ledger contract was defined so that "the LLM fills it today, the parsing engine fills it
 * later" — this is later. The shape it emits is byte-for-byte the shape the model was asked to
 * transcribe, so {@link com.pragmaticds.rag.service.analyze.calc.AssetsCalcService}, the
 * reconciliation check, and the report renderer are all unchanged by the swap. What changes is
 * that dense financial transcription stops being a model's job, which is where the original
 * production miss came from: a $4,000 deposit that was on the statement and not in the findings.
 *
 * <p><b>How rows are found.</b> A {@code FieldOccurrence} carries a {@code groupKey} that is
 * "present-and-null for a field that does not repeat". That IS the row boundary: null-key fields
 * are the account header, and each distinct non-null key is one transaction. Nothing here parses
 * a raw string — only the typed {@code normalized} arms are read, because re-parsing a value the
 * engine already normalized is how two systems come to disagree about the same number.
 *
 * <p><b>A row that cannot be summed is reported, never dropped.</b> A group with no usable amount
 * sets its account's {@code transcriptionComplete} to false and is left out of the totals. That is
 * the same rule the model was given and for the same reason: a quietly partial ledger produces
 * confident wrong numbers, which is worse than a reported gap. The reconciliation check then has
 * the last word.
 *
 * <p>Deterministic by construction: documents, fields, and groups are all walked in envelope
 * order, and every collection here preserves insertion order.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class AssetsLedgerToolExecutor implements InstanceToolExecutor {

    /** Pinned identity. A change in behavior here is a new version, never a redefinition. */
    public static final String NAME = "assets.ledger.v1";
    public static final String VERSION = "1.0.0";

    private static final String INPUT_SCHEMA = "ai/tools/assets-ledger-v1.input.schema.json";
    private static final String OUTPUT_SCHEMA = "ai/tools/assets-ledger-v1.output.schema.json";

    private static final Logger log = LoggerFactory.getLogger(AssetsLedgerToolExecutor.class);

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private final AssetsLedgerProperties properties;
    private final String inputSchemaSha256;
    private final String outputSchemaSha256;

    public AssetsLedgerToolExecutor(AssetsLedgerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.inputSchemaSha256 = sha256Hex(read(INPUT_SCHEMA));
        this.outputSchemaSha256 = sha256Hex(read(OUTPUT_SCHEMA));
        if (!properties.usable()) {
            // Registered anyway, so the tool's identity and digests stay stable and a release
            // author can see it exists. It refuses at execution rather than building an empty
            // ledger; this line is so that refusal is not the first anyone hears of it.
            log.warn("Tool {} is registered but not runnable: set"
                    + " ragbrain.instances.assets-ledger.document-types and"
                    + " .transaction.amount to the Document Engine's own field names", NAME);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String inputSchemaSha256() {
        return inputSchemaSha256;
    }

    @Override
    public String outputSchemaSha256() {
        return outputSchemaSha256;
    }

    /**
     * This tool's output IS the analyzer envelope's domain, so it replaces whatever the model
     * wrote there rather than sitting beside it in provenance.
     */
    @Override
    public boolean producesDomain() {
        return true;
    }

    @Override
    public JsonNode execute(EngineResultEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (!properties.usable()) {
            // A release pinned a tool this deployment cannot run. Failing is the point: an empty
            // ledger reconciles trivially and would report a clean, complete review of nothing.
            throw new IllegalStateException("assets ledger mapping is not configured");
        }

        Set<String> statementTypes = properties.documentTypeCodes();
        ObjectNode out = NF.objectNode();
        ArrayNode accounts = out.putArray("accounts");

        for (EngineResultEnvelope.LogicalDocument document : envelope.documents()) {
            if (!statementTypes.contains(document.documentTypeCode().toUpperCase(
                    java.util.Locale.ROOT))) {
                continue;
            }
            accounts.add(account(document));
        }
        return out;
    }

    // ---------------------------------------------------------------- mapping

    private ObjectNode account(EngineResultEnvelope.LogicalDocument document) {
        AssetsLedgerProperties.Account names = properties.account();

        // Header fields are the occurrences that do not repeat. Collected first so a row-level
        // failure below can flip transcriptionComplete without a second pass.
        Map<String, EngineResultEnvelope.FieldOccurrence> header = new LinkedHashMap<>();
        Map<String, List<EngineResultEnvelope.FieldOccurrence>> rows = new LinkedHashMap<>();
        for (EngineResultEnvelope.FieldOccurrence field : document.fields()) {
            if (field.groupKey() == null) {
                header.putIfAbsent(field.name(), field);
            } else {
                rows.computeIfAbsent(field.groupKey(), key -> new java.util.ArrayList<>())
                        .add(field);
            }
        }

        ObjectNode account = NF.objectNode();
        putText(account, "institution", header.get(names.institution()));
        putText(account, "maskedNumber", header.get(names.maskedNumber()));
        putText(account, "type", header.get(names.type()));

        ObjectNode period = NF.objectNode();
        putDate(period, "start", header.get(names.statementStart()));
        putDate(period, "end", header.get(names.statementEnd()));
        if (!period.isEmpty()) {
            account.set("statementPeriod", period);
        }

        // Omitted rather than defaulted when the statement prints none: AssetsCalcService proves
        // the ledger complete by reconciling against these, so an invented figure would make the
        // proof circular and always pass.
        putNumber(account, "beginningBalance", header.get(names.beginningBalance()));
        putNumber(account, "endingBalance", header.get(names.endingBalance()));

        ArrayNode transactions = NF.arrayNode();
        boolean complete = true;
        for (List<EngineResultEnvelope.FieldOccurrence> group : rows.values()) {
            ObjectNode transaction = transaction(group, document.ordinal());
            if (transaction == null) {
                complete = false;
                continue;
            }
            transactions.add(transaction);
        }

        account.put("transcriptionComplete", complete);
        account.set("transactions", transactions);
        return account;
    }

    /** One row, or null when it carries no amount and therefore cannot enter a total. */
    private ObjectNode transaction(
            List<EngineResultEnvelope.FieldOccurrence> group, int documentOrdinal) {
        AssetsLedgerProperties.Transaction names = properties.transaction();

        Map<String, EngineResultEnvelope.FieldOccurrence> byName = new LinkedHashMap<>();
        for (EngineResultEnvelope.FieldOccurrence field : group) {
            byName.putIfAbsent(field.name(), field);
        }

        BigDecimal amount = number(byName.get(names.amount()));
        if (amount == null) {
            return null;
        }

        ObjectNode transaction = NF.objectNode();
        putDate(transaction, "date", byName.get(names.date()));
        putText(transaction, "description", byName.get(names.description()));
        transaction.put("amount", signed(amount, byName.get(names.direction()), names));
        putNumber(transaction, "runningBalance", byName.get(names.runningBalance()));
        transaction.put("docId", ParsedDocumentPromptRenderer.handleFor(documentOrdinal));
        return transaction;
    }

    /**
     * The ledger's signed convention, from whichever shape the engine reports.
     *
     * <p>With no {@code direction} field configured the amount is already signed and is passed
     * through untouched — including its sign, so a debit the engine already made negative is not
     * negated a second time.
     */
    private static BigDecimal signed(BigDecimal amount,
                                     EngineResultEnvelope.FieldOccurrence direction,
                                     AssetsLedgerProperties.Transaction names) {
        if (names.direction() == null || names.direction().isBlank()) {
            return amount;
        }
        String value = text(direction);
        if (value == null) {
            // The engine was expected to say and did not. Magnitude alone would be a coin flip on
            // every row, so the sign it already carries is the only non-invented answer left.
            return amount;
        }
        return names.isCredit(value) ? amount.abs() : amount.abs().negate();
    }

    // ---------------------------------------------------------------- typed reads

    private static void putText(
            ObjectNode target, String field, EngineResultEnvelope.FieldOccurrence occurrence) {
        String value = text(occurrence);
        if (value != null) {
            target.put(field, value);
        }
    }

    private static void putNumber(
            ObjectNode target, String field, EngineResultEnvelope.FieldOccurrence occurrence) {
        BigDecimal value = number(occurrence);
        if (value != null) {
            target.put(field, value);
        }
    }

    private static void putDate(
            ObjectNode target, String field, EngineResultEnvelope.FieldOccurrence occurrence) {
        if (usable(occurrence) && occurrence.normalized().date() != null) {
            target.put(field, occurrence.normalized().date().toString());
        }
    }

    private static String text(EngineResultEnvelope.FieldOccurrence occurrence) {
        return usable(occurrence) ? occurrence.normalized().text() : null;
    }

    private static BigDecimal number(EngineResultEnvelope.FieldOccurrence occurrence) {
        return usable(occurrence) ? occurrence.normalized().number() : null;
    }

    /**
     * A field is usable only when the engine both found it, normalized it, and did not flag it
     * sensitive.
     *
     * <p>{@code rawValue} is deliberately not a fallback. A MISSING occurrence means the engine
     * looked and did not find one; reading around that would put a value into a ledger the engine
     * declined to vouch for.
     *
     * <p>A sensitive occurrence is treated as absent for the same reason in the other direction:
     * this ledger is persisted as the run's {@code domain} and returned to the browser, so it is a
     * derived store, and the envelope it reads is unmasked. The engine's own masked surfaces are
     * where a sensitive value may be shown, not here.
     */
    private static boolean usable(EngineResultEnvelope.FieldOccurrence occurrence) {
        return occurrence != null
                && occurrence.status() == EngineResultEnvelope.FieldStatus.FOUND
                && occurrence.normalized() != null
                && !occurrence.sensitive();
    }

    // ---------------------------------------------------------------- schema digests

    private static byte[] read(String resource) {
        try (InputStream in = AssetsLedgerToolExecutor.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                // A missing schema is a broken build, not a tool to quietly register with a
                // digest of nothing — a release could then pin an identity that means nothing.
                throw new IllegalStateException("missing tool schema: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
