package com.pragmaticds.rag.lab.analyze;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Document Engine's bank-statement vocabulary, as configuration.
 *
 * <p><b>Why deployment properties and not a pack file.</b> An analyzer's rules live in its pack
 * because they are domain policy that versions with the brain. This is not that: it is the field
 * names one Document Engine happens to emit, and a tool executor is resolved from a global
 * registry by name and schema digests with no brain in scope — it never sees which pack asked for
 * it. One engine serves every brain here, so one vocabulary is the honest shape.
 *
 * <p><b>Nothing is defaulted to a guess.</b> With {@code documentTypes} empty or the amount field
 * unset the tool still registers — so its identity and digests stay stable and a release author
 * can see it exists — but it REFUSES at execution and warns at boot. It does not fall back to an
 * empty ledger: an empty ledger reconciles trivially and would report a clean, complete review of
 * no statements at all.
 *
 * <p>The values below are the engine's own field names, so they are edited when the engine's
 * extraction schema changes, not when a guideline does.
 */
@ConfigurationProperties(prefix = "ragbrain.instances.assets-ledger")
public record AssetsLedgerProperties(
        List<String> documentTypes,
        Account account,
        Transaction transaction) {

    public AssetsLedgerProperties {
        // Blanks are filtered, not preserved: an unset environment variable binds as a
        // single empty string, and a "" document type would make usable() true while
        // matching nothing — the tool would then build an empty ledger, which reconciles
        // trivially and reads as a clean, complete review of no statements at all.
        documentTypes = documentTypes == null ? List.of()
                : documentTypes.stream()
                        .filter(code -> code != null && !code.isBlank())
                        .map(String::strip)
                        .toList();
        account = account == null ? Account.empty() : account;
        transaction = transaction == null ? Transaction.empty() : transaction;
    }

    /**
     * Account header field names — the occurrences whose {@code groupKey} is null.
     *
     * <p>Every one is optional. A statement that does not print a beginning balance yields an
     * account without one, and {@code AssetsCalcService} reports the reconciliation as
     * unverifiable rather than inventing the figure that would make it pass.
     */
    public record Account(
            String institution,
            String maskedNumber,
            String type,
            String statementStart,
            String statementEnd,
            String beginningBalance,
            String endingBalance) {

        static Account empty() {
            return new Account(null, null, null, null, null, null, null);
        }
    }

    /**
     * Transaction row field names — the occurrences sharing one non-null {@code groupKey}.
     *
     * @param amount         required; without it there is no ledger to build
     * @param direction      optional. Set it only when the engine reports magnitude plus a
     *                       separate credit/debit indicator. Left unset, the amount is taken as
     *                       already signed. Getting this wrong does not corrupt the report
     *                       silently — the reconciliation check fails and the report leads with
     *                       "ledger does not reconcile" — but it is worth getting right.
     * @param creditValues   the {@code direction} values that mean money in; anything else is
     *                       treated as money out
     */
    public record Transaction(
            String date,
            String description,
            String amount,
            String runningBalance,
            String direction,
            List<String> creditValues) {

        public Transaction {
            creditValues = creditValues == null ? List.of() : List.copyOf(creditValues);
        }

        static Transaction empty() {
            return new Transaction(null, null, null, null, null, List.of());
        }

        /** Whether a direction value means money in. Case-insensitive; the engine's casing varies. */
        public boolean isCredit(String value) {
            return value != null && creditValues.stream().anyMatch(value::equalsIgnoreCase);
        }
    }

    /** The statement document types, upper-cased once so matching is not a per-field cost. */
    public Set<String> documentTypeCodes() {
        return documentTypes.stream()
                .map(code -> code.strip().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Whether this deployment has been told enough to build a ledger at all.
     *
     * <p>A statement type and an amount field are the irreducible minimum: without the first
     * nothing is read, and without the second every row is unusable.
     */
    public boolean usable() {
        return !documentTypes.isEmpty()
                && transaction.amount() != null && !transaction.amount().isBlank();
    }
}
