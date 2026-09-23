package com.pragmaticds.rag.service.analyze.calc;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The loan-level facts a large-deposit threshold is computed from.
 *
 * <p>None of this can be read off a bank statement. Which agency's rule applies, whether the
 * transaction is a purchase or a refinance, the qualifying monthly income, and the Adjusted Value
 * are all facts the loan file holds and the Assets folder does not — so they arrive as an input or
 * the threshold is not computed at all.
 *
 * <p><b>Why a program and a purpose, and not just an income figure.</b> There is no single
 * large-deposit test. Fannie Mae measures a deposit against 50% of qualifying monthly income and
 * only on a purchase; FHA measures it against 1% of the Adjusted Value and adds a pattern
 * judgement; VA publishes no number at all. A basis that carried income alone could only ever
 * express the Fannie rule, and applying it to an FHA file produces a confident wrong answer rather
 * than a refusal.
 *
 * <p>Every field is nullable, and a null is never defaulted. {@link AssetsCalcService} reports
 * precisely which fact was missing instead of substituting one, because a substituted basis is
 * indistinguishable in the report from a real one.
 *
 * @param program                 the agency ruleset this loan is underwritten to
 * @param purpose                 purchase or refinance; Fannie's sourcing requirement is
 *                                purchase-only, so this is load-bearing rather than descriptive
 * @param qualifyingMonthlyIncome total monthly qualifying income, the Fannie/Freddie basis
 * @param adjustedValue           the FHA Adjusted Value — for most purchases the sales price
 */
public record LoanBasis(Program program,
                        Purpose purpose,
                        BigDecimal qualifyingMonthlyIncome,
                        BigDecimal adjustedValue) {

    /** The agency rulesets an assets review can be run under. */
    public enum Program { FANNIE_MAE, FREDDIE_MAC, FHA, VA, USDA }

    /** Whether borrower funds are needed to purchase the property — Fannie's sourcing hinge. */
    public enum Purpose { PURCHASE, REFINANCE }

    /** True only when both facts needed to select a rule are present. */
    public boolean selectsARule() {
        return program != null && purpose != null;
    }

    /**
     * The caller's program name, or null when absent or unrecognized.
     *
     * <p>An unrecognized name is deliberately null rather than an exception or a default: a
     * suite sending {@code "Conventional"} should make the threshold report itself unavailable,
     * not silently underwrite the file to whichever program happened to be first in the enum.
     */
    public static Program programOf(String name) {
        return parse(Program.class, name);
    }

    /** The caller's purpose name, or null when absent or unrecognized. */
    public static Purpose purposeOf(String name) {
        return parse(Purpose.class, name);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unrecognized) {
            return null;
        }
    }
}
