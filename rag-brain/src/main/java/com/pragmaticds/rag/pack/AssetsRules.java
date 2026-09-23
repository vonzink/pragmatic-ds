package com.pragmaticds.rag.pack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic rules the assets analyzer applies to a transcribed transaction ledger.
 * Declared per-analyzer in a pack's analyzers.yaml under {@code assets-rules}; kebab-case
 * keys map to these components via the loader's naming strategy. Immutable; travels and
 * versions with the pack.
 *
 * <p>These live in config rather than Java so adding a state's child-support payee — or a
 * new fee keyword, or a program whose large-deposit test differs — is a pack edit, not a
 * code change and redeploy.
 *
 * <p>Deliberately free of any {@code service} type: a rule names its program and basis as
 * plain strings and {@code AssetsCalcService} does the matching, so pack configuration never
 * has to depend on the calculator it configures.
 *
 * @param largeDepositRules one rule per agency ruleset; an empty list means no program's
 *                          threshold can be computed and the screen reports itself unavailable
 * @param flagRules         description keyword rules, applied case-insensitively
 * @param recurringMinHits  minimum occurrences before a payee counts as recurring
 * @param requiredDays      statement days each account should cover; null ⇒ {@link #DEFAULT_REQUIRED_DAYS}
 * @param cashDepositMatch  substrings that mark a credit as a cash deposit; null ⇒ default, [] ⇒ off
 * @param earnestMoneyMatch substrings that mark a debit as earnest money; null ⇒ default, [] ⇒ off
 */
public record AssetsRules(
        List<LargeDepositRule> largeDepositRules,
        List<FlagRule> flagRules,
        int recurringMinHits,
        Integer requiredDays,
        List<String> cashDepositMatch,
        List<String> earnestMoneyMatch
) {
    /** The two-months-of-statements norm, used when the pack declares no {@code required-days}. */
    public static final int DEFAULT_REQUIRED_DAYS = 60;
    public static final List<String> DEFAULT_CASH_DEPOSIT_MATCH =
            List.of("CASH", "ATM DEPOSIT", "BRANCH DEPOSIT", "CASH DEP", "CURRENCY");
    public static final List<String> DEFAULT_EARNEST_MONEY_MATCH =
            List.of("EARNEST", "EMD", "TITLE", "ESCROW", "REALTY", "CLOSING");
    /** The flag rule whose hits double as overdraft fee evidence in the overdrafts block. */
    public static final String OVERDRAFT_RULE_ID = "overdraft";

    public AssetsRules {
        largeDepositRules = largeDepositRules == null ? List.of() : List.copyOf(largeDepositRules);
        flagRules = flagRules == null ? List.of() : List.copyOf(flagRules);
        // null = "not declared" → the engine default; an explicit [] = "switched off", kept as-is
        // so the report can say "not configured" rather than "nothing found".
        cashDepositMatch = cashDepositMatch == null ? DEFAULT_CASH_DEPOSIT_MATCH : List.copyOf(cashDepositMatch);
        earnestMoneyMatch = earnestMoneyMatch == null ? DEFAULT_EARNEST_MONEY_MATCH : List.copyOf(earnestMoneyMatch);
    }

    /** Three-argument form for callers that want the coverage and keyword defaults. */
    public AssetsRules(List<LargeDepositRule> largeDepositRules, List<FlagRule> flagRules, int recurringMinHits) {
        this(largeDepositRules, flagRules, recurringMinHits, null, null, null);
    }

    /** The coverage target per account; a missing or non-positive value is the default. */
    public int requiredDaysOrDefault() {
        return requiredDays == null || requiredDays <= 0 ? DEFAULT_REQUIRED_DAYS : requiredDays;
    }

    /** The {@code overdraft} flag rule, or null when the pack declares none. */
    public FlagRule overdraftRule() {
        return flagRules.stream().filter(r -> OVERDRAFT_RULE_ID.equalsIgnoreCase(r.id())).findFirst().orElse(null);
    }

    /**
     * One agency's large-deposit test.
     *
     * <p>There is no single rule to hard-code. Fannie Mae measures a deposit against 50% of
     * qualifying monthly income and requires sourcing only on a purchase; FHA measures against
     * 1% of the Adjusted Value on any transaction and adds a judgement the engine cannot make;
     * VA publishes no threshold at all. Modelling program, basis, percentage, and applicable
     * purposes separately is what lets all three be true at once.
     *
     * @param program              agency ruleset name, matched case-insensitively against
     *                             {@code LoanBasis.Program} (FANNIE_MAE, FHA, VA, …)
     * @param basis                what the percentage applies to: {@code
     *                             QUALIFYING_MONTHLY_INCOME}, {@code ADJUSTED_VALUE}, or
     *                             {@code UNDERWRITER_JUDGEMENT} when the agency publishes no
     *                             computable threshold
     * @param pct                  the percentage of the basis; null exactly when the basis is
     *                             {@code UNDERWRITER_JUDGEMENT}
     * @param purposes             the loan purposes this rule applies to. A purpose absent here
     *                             means the agency requires no sourcing for that transaction —
     *                             Fannie on a refinance — which is a clean answer, not a gap
     * @param requiresPatternReview true when clearing the numeric threshold is necessary but not
     *                             sufficient, so the report must say a human still judges whether
     *                             the deposit fits the borrower's established pattern (FHA)
     */
    public record LargeDepositRule(
            String program,
            String basis,
            BigDecimal pct,
            List<String> purposes,
            boolean requiresPatternReview) {

        /** Basis meaning "this agency publishes no computable threshold". */
        public static final String UNDERWRITER_JUDGEMENT = "UNDERWRITER_JUDGEMENT";

        public LargeDepositRule {
            purposes = purposes == null ? List.of() : List.copyOf(purposes);
        }

        public boolean isFor(String programName) {
            return program != null && program.equalsIgnoreCase(programName);
        }

        public boolean appliesTo(String purposeName) {
            return purposes.stream().anyMatch(p -> p.equalsIgnoreCase(purposeName));
        }

        /** True when this agency publishes a threshold the engine can actually compute. */
        public boolean isComputable() {
            return pct != null && basis != null
                    && !UNDERWRITER_JUDGEMENT.equalsIgnoreCase(basis.strip());
        }

        public String normalizedBasis() {
            return basis == null ? null : basis.strip().toUpperCase(Locale.ROOT);
        }
    }

    /**
     * @param id    stable rule id, used in the derived payload; [a-z0-9-]+
     * @param label human label rendered in the report's Rule column
     * @param match substrings matched case-insensitively against the verbatim description;
     *              any one match flags the transaction
     */
    public record FlagRule(String id, String label, List<String> match) {
        public FlagRule {
            match = match == null ? List.of() : List.copyOf(match);
        }
    }

    /** The rule governing this program, or null when the pack declares none for it. */
    public LargeDepositRule ruleFor(String programName) {
        if (programName == null) {
            return null;
        }
        return largeDepositRules.stream().filter(r -> r.isFor(programName)).findFirst().orElse(null);
    }
}
