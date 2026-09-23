package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.pack.AssetsRules;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the assets analyzer's deterministic rules to a model-transcribed transaction
 * ledger, appending {@code domain.derived}. Pure: no I/O, no model calls, no clock — the
 * same ledger always yields the same derived block, which is what makes every rule
 * testable without a provider.
 *
 * <p>The model transcribes; this class decides. A transaction that reached the ledger can
 * never be missed by a rule here. A transaction that never reached the ledger is caught by
 * the reconciliation check rather than silently ignored — that check is the reason the
 * numbers below can be trusted at all.
 *
 * <p>Reads {@code domain} directly rather than model-requested {@code calculations[]}:
 * these rollups need no method selection, so making the model request them would only add
 * a way for a section to silently vanish. See the design spec §6.3.
 */
@Service
public class AssetsCalcService {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /**
     * @param envelope the validated v2 envelope; mutated in place and returned
     * @param rules    the analyzer's pack rules; null ⇒ no rules applied
     * @param loan     the loan-level facts the large-deposit threshold is computed from; null,
     *                 or missing the program and purpose that select a rule, ⇒ the threshold is
     *                 not computed and the deposits block reports exactly which fact was absent
     */
    public JsonNode enrich(ObjectNode envelope, AssetsRules rules, LoanBasis loan) {
        JsonNode domainNode = envelope.path("domain");
        if (!domainNode.isObject() || !domainNode.path("accounts").isArray()) {
            return envelope;
        }
        ObjectNode domain = (ObjectNode) domainNode;
        ArrayNode accounts = (ArrayNode) domain.path("accounts");

        ObjectNode derived = domain.putObject("derived");
        derived.set("reconciliation", reconcile(accounts));
        derived.set("largeDeposits", largeDeposits(accounts, rules, loan));
        derived.set("largeWithdrawals", largeWithdrawals(accounts, rules, loan));
        derived.set("cashDeposits", keywordHits(accounts, rules == null ? null : rules.cashDepositMatch(), true));
        derived.set("earnestMoney", keywordHits(accounts, rules == null ? null : rules.earnestMoneyMatch(), false));
        derived.set("overdrafts", overdrafts(accounts, rules));
        derived.set("flags", flags(accounts, rules));
        derived.set("recurring", recurring(accounts, rules));
        derived.set("balances", balances(accounts));
        derived.set("totals", totals(accounts));
        derived.set("coverage", coverage(accounts, rules));
        return envelope;
    }

    private ArrayNode balances(ArrayNode accounts) {
        ArrayNode out = NF.arrayNode();
        for (JsonNode acct : accounts) {
            ObjectNode row = out.addObject();
            row.put("account", label(acct));
            row.put("type", acct.path("type").asText(""));
            putMoneyOrNull(row, "beginningBalance", acct.path("beginningBalance"));
            putMoneyOrNull(row, "endingBalance", acct.path("endingBalance"));
            row.put("statementStart", acct.path("statementPeriod").path("start").asText(""));
            row.put("statementEnd", acct.path("statementPeriod").path("end").asText(""));
        }
        return out;
    }

    /** An absent balance stays null rather than becoming 0.00, which would read as a fact. */
    private static void putMoneyOrNull(ObjectNode row, String field, JsonNode value) {
        if (value.isNumber()) row.put(field, money(value).toPlainString());
        else row.putNull(field);
    }

    private ObjectNode totals(ArrayNode accounts) {
        BigDecimal deposits = ZERO;
        BigDecimal withdrawals = ZERO;
        for (JsonNode acct : accounts) {
            for (JsonNode t : acct.path("transactions")) {
                BigDecimal amt = money(t.path("amount"));
                if (amt.signum() > 0) deposits = deposits.add(amt);
                else withdrawals = withdrawals.add(amt.abs());
            }
        }
        ObjectNode out = NF.objectNode();
        out.put("deposits", deposits.toPlainString());
        out.put("withdrawals", withdrawals.toPlainString());
        out.put("net", deposits.subtract(withdrawals).toPlainString());
        return out;
    }

    /**
     * Per-account statement coverage: days covered (a union of statement days, so overlaps do
     * not double count), gaps between consecutive statements with the missing-day count, and the
     * shortfall against the pack's required days. Deliberately does NOT flag day-to-day gaps
     * inside a statement — no transactions on a Sunday is normal, and reporting it would bury
     * the statement-level gaps that actually matter.
     */
    private ObjectNode coverage(ArrayNode accounts, AssetsRules rules) {
        int requiredDays = rules == null ? AssetsRules.DEFAULT_REQUIRED_DAYS : rules.requiredDaysOrDefault();
        ObjectNode out = NF.objectNode();
        out.put("requiredDays", requiredDays);
        ArrayNode gaps = out.putArray("gaps");
        ArrayNode incomplete = out.putArray("incompleteAccounts");
        ArrayNode perAccount = out.putArray("accounts");

        Map<String, List<JsonNode>> byAccount = new LinkedHashMap<>();
        for (JsonNode acct : accounts) {
            byAccount.computeIfAbsent(label(acct), k -> new ArrayList<>()).add(acct);
            if (!acct.path("transcriptionComplete").asBoolean(true)) {
                incomplete.add(label(acct));
            }
        }

        for (Map.Entry<String, List<JsonNode>> e : byAccount.entrySet()) {
            List<LocalDate[]> periods = new ArrayList<>();
            for (JsonNode a : e.getValue()) {
                LocalDate s = date(a.path("statementPeriod").path("start").asText(""));
                LocalDate d = date(a.path("statementPeriod").path("end").asText(""));
                if (s != null && d != null && !d.isBefore(s)) periods.add(new LocalDate[]{s, d});
            }
            periods.sort(Comparator.comparing(p -> p[0]));

            java.util.Set<LocalDate> days = new java.util.HashSet<>();
            for (LocalDate[] p : periods) {
                for (LocalDate x = p[0]; !x.isAfter(p[1]); x = x.plusDays(1)) days.add(x);
            }
            for (int i = 1; i < periods.size(); i++) {
                LocalDate prevEnd = periods.get(i - 1)[1];
                LocalDate nextStart = periods.get(i)[0];
                long missing = ChronoUnit.DAYS.between(prevEnd, nextStart) - 1;
                if (missing > 0) {
                    ObjectNode gap = gaps.addObject();
                    gap.put("account", e.getKey());
                    gap.put("after", prevEnd.toString());
                    gap.put("before", nextStart.toString());
                    gap.put("missingDays", missing);
                }
            }

            LocalDate first = periods.isEmpty() ? null : periods.get(0)[0];
            LocalDate last = periods.stream().map(p -> p[1]).max(Comparator.naturalOrder()).orElse(null);
            int covered = days.size();
            long span = first == null ? 0 : ChronoUnit.DAYS.between(first, last) + 1;
            int shortfall = Math.max(0, requiredDays - covered);

            ObjectNode row = perAccount.addObject();
            row.put("account", e.getKey());
            row.put("statements", e.getValue().size());
            row.put("periodStart", first == null ? "" : first.toString());
            row.put("periodEnd", last == null ? "" : last.toString());
            row.put("daysCovered", covered);
            row.put("spanDays", span);
            row.put("gapDays", Math.max(0, span - covered));
            row.put("shortfallDays", shortfall);
            row.put("meetsRequirement", shortfall == 0);
        }

        // Reviewed-period summary, used by the client-facing notice to say exactly what
        // was checked rather than leaving a borrower to infer it from silence.
        out.put("statementCount", accounts.size());
        out.put("accountCount", byAccount.size());
        String earliest = "";
        String latest = "";
        for (JsonNode acct : accounts) {
            String s = acct.path("statementPeriod").path("start").asText("");
            String e2 = acct.path("statementPeriod").path("end").asText("");
            if (!s.isBlank() && (earliest.isBlank() || s.compareTo(earliest) < 0)) earliest = s;
            if (!e2.isBlank() && e2.compareTo(latest) > 0) latest = e2;
        }
        out.put("periodStart", earliest);
        out.put("periodEnd", latest);
        return out;
    }

    /** ISO date or null; a blank or unparseable date is "not evidence", never a gap. */
    private static LocalDate date(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    /** One accumulator per verbatim payee string. */
    private static final class Payee {
        int hits;
        BigDecimal total = ZERO;
        String latest = "";
    }

    /**
     * Groups withdrawals by EXACT verbatim description. Deliberately dumb: normalizing
     * payee names is the same inference that loses a line entirely, and this section
     * exists to be over-inclusive so a human can scan it for what the rules missed.
     */
    private ArrayNode recurring(ArrayNode accounts, AssetsRules rules) {
        ArrayNode out = NF.arrayNode();
        int min = rules == null ? 2 : rules.recurringMinHits();

        Map<String, Payee> byPayee = new LinkedHashMap<>();
        for (JsonNode acct : accounts) {
            for (JsonNode t : acct.path("transactions")) {
                BigDecimal amt = money(t.path("amount"));
                if (amt.signum() >= 0) continue;            // withdrawals only
                String desc = t.path("description").asText("");
                if (desc.isBlank()) continue;

                Payee p = byPayee.computeIfAbsent(desc, k -> new Payee());
                p.hits++;
                p.total = p.total.add(amt.abs());
                String date = t.path("date").asText("");
                // ISO-8601 dates compare lexicographically, so no parsing is needed.
                if (date.compareTo(p.latest) > 0) p.latest = date;
            }
        }

        List<Map.Entry<String, Payee>> ranked = byPayee.entrySet().stream()
                .filter(e -> e.getValue().hits >= min)
                .sorted(Comparator.comparing((Map.Entry<String, Payee> e) -> e.getValue().total).reversed())
                .toList();

        for (Map.Entry<String, Payee> e : ranked) {
            Payee p = e.getValue();
            ObjectNode row = out.addObject();
            row.put("payee", e.getKey());
            row.put("hits", p.hits);
            row.put("total", p.total.toPlainString());
            row.put("average", p.total.divide(BigDecimal.valueOf(p.hits), 2, RoundingMode.HALF_UP).toPlainString());
            row.put("latest", p.latest);
        }
        return out;
    }

    /**
     * Keyword matches against the VERBATIM description. Every configured rule is emitted
     * even when it has no hits, so the report can distinguish "checked, nothing found"
     * from "never ran" — the ambiguity that let an unnoticed payment look like an absence.
     */
    private ArrayNode flags(ArrayNode accounts, AssetsRules rules) {
        ArrayNode out = NF.arrayNode();
        if (rules == null) return out;

        for (AssetsRules.FlagRule r : rules.flagRules()) {
            ObjectNode entry = out.addObject();
            entry.put("ruleId", r.id());
            entry.put("label", r.label());
            ArrayNode hits = entry.putArray("hits");

            for (JsonNode acct : accounts) {
                for (JsonNode t : acct.path("transactions")) {
                    String desc = t.path("description").asText("");
                    if (matchesAny(desc, r)) {
                        ObjectNode hit = hits.addObject();
                        hit.put("date", t.path("date").asText(""));
                        hit.put("account", label(acct));
                        hit.put("description", desc);
                        hit.put("amount", money(t.path("amount")).toPlainString());
                    }
                }
            }
        }
        return out;
    }

    private static boolean matchesAny(String description, AssetsRules.FlagRule rule) {
        return matchesAny(description, rule.match());
    }

    private static boolean matchesAny(String description, List<String> needles) {
        String haystack = description.toUpperCase(java.util.Locale.ROOT);
        for (String needle : needles) {
            if (!needle.isBlank() && haystack.contains(needle.toUpperCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Deposits at or above the threshold the LOAN's own agency rule sets.
     *
     * <p>The block always reports one of three states in {@code status}, and they mean different
     * things to a reader:
     *
     * <ul>
     *   <li>{@code APPLIED} — a threshold was computed and every credit was screened against it.
     *       Zero hits here genuinely means no deposit needs sourcing.
     *   <li>{@code NOT_APPLICABLE} — the agency requires no sourcing for this transaction at all,
     *       the standard case being a Fannie Mae refinance, where no borrower funds are needed to
     *       purchase the property. Also a clean answer, reached a different way.
     *   <li>{@code UNAVAILABLE} — a fact needed to pick or compute the rule was not supplied, so
     *       nothing was screened. {@code unavailableReason} names the missing fact.
     * </ul>
     *
     * <p>Collapsing the third into the first is the failure this method is shaped to prevent: an
     * empty {@code hits} array with no status reads as "we checked and you are clear" whichever
     * of the three actually happened.
     */
    private ObjectNode largeDeposits(ArrayNode accounts, AssetsRules rules, LoanBasis loan) {
        ObjectNode out = NF.objectNode();
        ArrayNode hits = out.putArray("hits");
        Threshold t = selectThreshold(out, rules, loan);
        if (!t.applied()) return out;
        for (JsonNode acct : accounts) {
            for (JsonNode tx : acct.path("transactions")) {
                BigDecimal amt = money(tx.path("amount"));
                // Credits only: a large debit is not a deposit, however big.
                if (amt.signum() > 0 && amt.compareTo(t.value()) >= 0) hits.add(hit(acct, tx, amt));
            }
        }
        return out;
    }

    /**
     * Debits at or above the same threshold the deposits are screened against — the reviewer's
     * chosen test (2026-09-16), since no agency publishes a withdrawal figure. Each hit says
     * whether its description also looks like earnest money, so the two questions are
     * answered in one row. Shares {@link #selectThreshold} so the two blocks can never
     * disagree about the threshold or the reason none could be computed.
     */
    private ObjectNode largeWithdrawals(ArrayNode accounts, AssetsRules rules, LoanBasis loan) {
        ObjectNode out = NF.objectNode();
        ArrayNode hits = out.putArray("hits");
        Threshold t = selectThreshold(out, rules, loan);
        if (!t.applied()) return out;
        List<String> emd = rules.earnestMoneyMatch();
        for (JsonNode acct : accounts) {
            for (JsonNode tx : acct.path("transactions")) {
                BigDecimal amt = money(tx.path("amount"));
                if (amt.signum() < 0 && amt.abs().compareTo(t.value()) >= 0) {
                    ObjectNode h = hit(acct, tx, amt.abs());
                    h.put("possibleEarnestMoney", matchesAny(tx.path("description").asText(""), emd));
                    hits.add(h);
                }
            }
        }
        return out;
    }

    /** The outcome of selecting and computing the loan's sourcing threshold, written into a block. */
    private record Threshold(BigDecimal value) {
        boolean applied() {
            return value != null;
        }
    }

    /**
     * Fills {@code out} with program / purpose / status / threshold exactly as the deposits block
     * always has, and returns the threshold when one applies. See {@link #largeDeposits} for what
     * the three statuses mean to a reader.
     */
    private Threshold selectThreshold(ObjectNode out, AssetsRules rules, LoanBasis loan) {
        if (rules == null || rules.largeDepositRules().isEmpty()) {
            unavailable(out, "NO_RULES");
            return new Threshold(null);
        }
        if (loan == null || !loan.selectsARule()) {
            // Which agency's test applies, and whether borrower funds are even needed, are loan
            // facts. Guessing either produces a confident wrong threshold rather than a refusal.
            String missing = loan == null || (loan.program() == null && loan.purpose() == null)
                    ? "LOAN_BASIS_NOT_SUPPLIED"
                    : loan.program() == null ? "PROGRAM_NOT_SUPPLIED" : "PURPOSE_NOT_SUPPLIED";
            unavailable(out, missing);
            return new Threshold(null);
        }

        String program = loan.program().name();
        String purpose = loan.purpose().name();
        out.put("program", program);
        out.put("purpose", purpose);

        AssetsRules.LargeDepositRule rule = rules.ruleFor(program);
        if (rule == null) {
            unavailable(out, "NO_RULE_FOR_PROGRAM");
            return new Threshold(null);
        }
        if (!rule.appliesTo(purpose)) {
            // Not a gap. Fannie Mae asks for no sourcing on a refinance, and saying so is a
            // better answer than reporting a threshold nobody has to clear.
            out.putNull("threshold");
            out.put("status", "NOT_APPLICABLE");
            out.put("notApplicableReason", "SOURCING_NOT_REQUIRED_FOR_PURPOSE");
            return new Threshold(null);
        }
        if (!rule.isComputable()) {
            // VA publishes no number; the screen is a human judgement plus any lender overlay.
            unavailable(out, "NO_PUBLISHED_THRESHOLD");
            return new Threshold(null);
        }

        // Written before the guard below so the refusal can name the figure it wanted —
        // "the loan did not carry the qualifying monthly income" beats "a basis was missing".
        String basis = rule.normalizedBasis();
        out.put("basis", basis);

        BigDecimal basisAmount = basisAmount(basis, loan);
        if (basisAmount == null || basisAmount.signum() <= 0) {
            unavailable(out, "BASIS_AMOUNT_NOT_SUPPLIED");
            return new Threshold(null);
        }

        BigDecimal threshold = basisAmount.multiply(rule.pct())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        out.put("status", "APPLIED");
        out.put("threshold", threshold.toPlainString());
        out.put("thresholdPct", rule.pct().toPlainString());
        out.put("basisAmount", basisAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        // FHA's test is "over 1% of Adjusted Value AND out of pattern". The engine can only do
        // the arithmetic half, so it says so rather than letting a cleared row read as sourced.
        out.put("requiresPatternReview", rule.requiresPatternReview());
        return new Threshold(threshold);
    }

    /**
     * Keyword hits on one side of the ledger (credits for cash deposits, debits for earnest
     * money), any amount, reported as magnitudes. {@code configured:false} when the pack switched
     * the check off, so the report says so instead of "nothing found".
     */
    private ObjectNode keywordHits(ArrayNode accounts, List<String> needles, boolean credits) {
        ObjectNode out = NF.objectNode();
        boolean configured = needles != null && !needles.isEmpty();
        out.put("configured", configured);
        ArrayNode hits = out.putArray("hits");
        if (!configured) return out;
        for (JsonNode acct : accounts) {
            for (JsonNode tx : acct.path("transactions")) {
                BigDecimal amt = money(tx.path("amount"));
                if (credits ? amt.signum() <= 0 : amt.signum() >= 0) continue;
                if (matchesAny(tx.path("description").asText(""), needles)) hits.add(hit(acct, tx, amt.abs()));
            }
        }
        return out;
    }

    /**
     * Two independent overdraft signals: a printed running balance below zero (or a negative
     * ending balance), and the pack's overdraft fee rule. A ledger without running balances
     * reports {@code runningBalancesPrinted:false} so "no negative lines" cannot read as clear.
     */
    private ObjectNode overdrafts(ArrayNode accounts, AssetsRules rules) {
        ObjectNode out = NF.objectNode();
        ArrayNode negatives = out.putArray("negativeBalances");
        ArrayNode negativeEndings = out.putArray("negativeEndingBalances");
        ArrayNode fees = out.putArray("feeHits");
        ArrayNode byAccount = out.putArray("byAccount");
        AssetsRules.FlagRule feeRule = rules == null ? null : rules.overdraftRule();
        out.put("feeRuleConfigured", feeRule != null);
        boolean anyRunning = false;

        for (JsonNode acct : accounts) {
            int lines = 0;
            int feeCount = 0;
            for (JsonNode tx : acct.path("transactions")) {
                JsonNode rb = tx.path("runningBalance");
                if (rb.isNumber()) {
                    anyRunning = true;
                    if (money(rb).signum() < 0) {
                        ObjectNode n = negatives.addObject();
                        n.put("date", tx.path("date").asText(""));
                        n.put("account", label(acct));
                        n.put("description", tx.path("description").asText(""));
                        n.put("balance", money(rb).toPlainString());
                        lines++;
                    }
                }
                if (feeRule != null && matchesAny(tx.path("description").asText(""), feeRule)) {
                    fees.add(hit(acct, tx, money(tx.path("amount")).abs()));
                    feeCount++;
                }
            }
            JsonNode end = acct.path("endingBalance");
            if (end.isNumber() && money(end).signum() < 0) {
                ObjectNode n = negativeEndings.addObject();
                n.put("account", label(acct));
                n.put("statementEnd", acct.path("statementPeriod").path("end").asText(""));
                n.put("endingBalance", money(end).toPlainString());
            }
            ObjectNode row = byAccount.addObject();
            row.put("account", label(acct));
            row.put("negativeBalanceLines", lines);
            row.put("fees", feeCount);
        }
        out.put("runningBalancesPrinted", anyRunning);
        return out;
    }

    /** One hit row: date, account label, verbatim description, and the amount as given. */
    private static ObjectNode hit(JsonNode acct, JsonNode tx, BigDecimal amount) {
        ObjectNode h = NF.objectNode();
        h.put("date", tx.path("date").asText(""));
        h.put("account", label(acct));
        h.put("description", tx.path("description").asText(""));
        h.put("amount", amount.toPlainString());
        return h;
    }

    /** The amount this rule's percentage applies to, or null when the loan did not carry it. */
    private static BigDecimal basisAmount(String basis, LoanBasis loan) {
        return switch (basis == null ? "" : basis) {
            case "QUALIFYING_MONTHLY_INCOME" -> loan.qualifyingMonthlyIncome();
            case "ADJUSTED_VALUE" -> loan.adjustedValue();
            default -> null;
        };
    }

    /** No threshold, and the value-free reason a reader needs to tell it from a clean file. */
    private static ObjectNode unavailable(ObjectNode out, String reason) {
        out.putNull("threshold");
        out.put("status", "UNAVAILABLE");
        out.put("unavailableReason", reason);
        return out;
    }

    /**
     * Asserts beginningBalance + Σ(transactions) == endingBalance per account. A ledger
     * that balances is provably complete; one that does not is reported as untrustworthy
     * rather than quietly producing confident wrong totals.
     */
    private ObjectNode reconcile(ArrayNode accounts) {
        ObjectNode out = NF.objectNode();
        ArrayNode byAccount = out.putArray("byAccount");

        boolean anyIncomplete = false;
        boolean anyUnverifiable = false;
        BigDecimal deltaTotal = ZERO;

        for (JsonNode acct : accounts) {
            ObjectNode row = byAccount.addObject();
            row.put("account", label(acct));

            BigDecimal sum = ZERO;
            for (JsonNode t : acct.path("transactions")) {
                sum = sum.add(money(t.path("amount")));
            }
            row.put("transactionSum", sum.toPlainString());

            if (!acct.path("transcriptionComplete").asBoolean(true)) {
                anyIncomplete = true;
            }

            JsonNode beg = acct.path("beginningBalance");
            JsonNode end = acct.path("endingBalance");
            if (!beg.isNumber() || !end.isNumber()) {
                anyUnverifiable = true;
                row.put("status", "UNVERIFIABLE");
                continue;
            }

            BigDecimal expected = money(beg).add(sum);
            BigDecimal actual = money(end);
            BigDecimal delta = actual.subtract(expected).abs();
            deltaTotal = deltaTotal.add(delta);

            row.put("beginningBalance", money(beg).toPlainString());
            row.put("endingBalance", actual.toPlainString());
            row.put("expectedEndingBalance", expected.toPlainString());
            row.put("delta", delta.toPlainString());
            row.put("status", delta.compareTo(ZERO) == 0 ? "OK" : "MISMATCH");
        }

        out.put("deltaTotal", deltaTotal.toPlainString());
        // Precedence: a known-partial transcription is the strongest signal — the ledger is
        // missing rows by the model's own admission, so a coincidentally-zero delta must
        // not read as OK.
        if (anyIncomplete) {
            out.put("status", "INCOMPLETE");
        } else if (deltaTotal.compareTo(ZERO) != 0) {
            out.put("status", "MISMATCH");
        } else if (anyUnverifiable) {
            out.put("status", "UNVERIFIABLE");
        } else {
            out.put("status", "OK");
        }
        return out;
    }

    /** Display label for an account: institution plus masked digits when present. */
    static String label(JsonNode acct) {
        String inst = acct.path("institution").asText("Account");
        String mask = acct.path("maskedNumber").asText("");
        return mask.isBlank() ? inst : inst + " ••" + mask;
    }

    /** Scale-2 money, so equality comparisons are exact and need no epsilon. */
    static BigDecimal money(JsonNode n) {
        if (!n.isNumber()) return ZERO;
        return n.decimalValue().setScale(2, RoundingMode.HALF_UP);
    }
}
