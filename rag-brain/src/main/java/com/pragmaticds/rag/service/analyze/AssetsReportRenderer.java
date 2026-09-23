package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the assets report from the derived block produced by
 * {@link com.pragmaticds.rag.service.analyze.calc.AssetsCalcService}. Pure and layout-only:
 * every number printed here is read from {@code derived}, never recomputed, so a layout
 * change can never alter a figure — and a rule change can never alter the layout.
 *
 * <p>Rendering in Java rather than letting the model write the report is what makes the
 * shape identical on every run.
 */
@Service
public class AssetsReportRenderer {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    public String render(JsonNode envelope) {
        JsonNode d = envelope.path("domain").path("derived");
        if (d.isMissingNode()) return envelope.path("reportMarkdown").asText("");

        StringBuilder sb = new StringBuilder();
        JsonNode rec = d.path("reconciliation");
        String status = rec.path("status").asText("UNVERIFIABLE");

        if ("MISMATCH".equals(status)) {
            sb.append("> **Ledger does not reconcile — ")
              .append(usd(rec.path("deltaTotal")))
              .append(" unaccounted.** The totals below are not trustworthy; ")
              .append("re-read the statement before relying on them.\n\n");
        } else if ("INCOMPLETE".equals(status)) {
            sb.append("> **Ledger is incomplete — at least one page could not be read.** ")
              .append("The totals below are not trustworthy.\n\n");
        }

        sb.append("**Assets** — ").append(d.path("balances").size()).append(" statement(s)");
        if ("OK".equals(status)) {
            sb.append(" · Ledger reconciles ✓");
        } else if ("UNVERIFIABLE".equals(status)) {
            sb.append(" · Reconciliation not verifiable (no printed balances)");
        }
        sb.append("\n\n");

        appendLargeDeposits(sb, d.path("largeDeposits"));
        appendLargeWithdrawals(sb, d.path("largeWithdrawals"));
        appendKeywordSection(sb, "Cash deposits", d.path("cashDeposits"));
        appendKeywordSection(sb, "Earnest money", d.path("earnestMoney"));
        appendOverdrafts(sb, d.path("overdrafts"));
        appendBalances(sb, d.path("balances"));
        appendFlags(sb, d.path("flags"), quietChecks(d));
        appendRecurring(sb, d.path("recurring"));
        appendTotals(sb, d.path("totals"));
        appendCoverage(sb, d.path("coverage"));
        appendClientNotice(sb, d, status);
        return sb.toString().stripTrailing();
    }

    /**
     * The block the loan officer crops into an email to the borrower. Renders in BOTH
     * states — unlike the loan-officer section above, silence here would leave a borrower
     * unable to tell "you are clear" from "we did not check".
     *
     * <p>Suppressed entirely when the ledger does not reconcile: a clean bill of health
     * drawn from untrustworthy totals is worse than none at all.
     */
    private void appendClientNotice(StringBuilder sb, JsonNode d, String reconciliationStatus) {
        sb.append("---\n\n### For the client\n\n");

        if (!"OK".equals(reconciliationStatus)) {
            sb.append("*This ledger did not reconcile, so the client notice is ")
              .append("**not ready to send**. Re-read the statements first.*\n");
            return;
        }

        JsonNode ld = d.path("largeDeposits");
        JsonNode hits = ld.path("hits");
        // A cash deposit needs the same paper trail as a large one, whatever its size.
        JsonNode cash = d.path("cashDeposits").path("hits");

        // An empty hits array means three different things, and only one of them is a clean
        // bill of health. Saying "nothing needed from you" for a check that never ran is the
        // one outcome this block exists to prevent: from the borrower's chair, "you are clear"
        // and "we did not check" look identical, and only one is safe to act on.
        if (UNAVAILABLE.equals(status(ld))) {
            sb.append("*The large-deposit review could not run: ")
              .append(unavailableReason(ld))
              .append(". No sourcing threshold was applied to this loan, so the client notice ")
              .append("is **not ready to send** — supply the missing loan detail and re-run.*\n");
            return;
        }

        if (NOT_APPLICABLE.equals(status(ld))) {
            sb.append("**No deposits need documentation**\n\n")
              .append("Nothing needed from you on assets. On a ")
              .append(purposeWords(ld)).append(", ").append(programWords(ld))
              .append(" does not require large deposits to be sourced, so the statements are ")
              .append("used as they are.\n");
            return;
        }

        if (hits.size() + cash.size() == 0) {
            JsonNode c = d.path("coverage");
            int statements = c.path("statementCount").asInt();
            int accounts = c.path("accountCount").asInt();
            sb.append("**No deposits need documentation**\n\n")
              .append("Nothing needed from you on assets. We reviewed ")
              .append(statements).append(statements == 1 ? " statement" : " statements")
              .append(" across ").append(accounts)
              .append(accounts == 1 ? " account" : " accounts")
              .append(" covering ").append(c.path("periodStart").asText())
              .append(" – ").append(c.path("periodEnd").asText())
              .append(". No single deposit reached the ").append(usd(ld.path("threshold")))
              .append(" threshold.\n");
            return;
        }

        int need = hits.size() + cash.size();
        sb.append("**").append(need)
          .append(need == 1 ? " deposit needs" : " deposits need")
          .append(" documentation**\n\n")
          .append("Before we can use these funds we need a short paper trail showing where ")
          .append("they came from — a copy of the check, a transfer receipt, or a brief ")
          .append("letter explaining the source.\n\n")
          .append("| Date | Account | Description on statement | Amount |\n|---|---|---|---|\n");
        for (JsonNode list : List.of(hits, cash)) {
            for (JsonNode h : list) {
                // Backticked so the borrower sees the bank's exact wording and can find the
                // line on their own statement — the same reason the ledger never normalizes it.
                sb.append("| ").append(h.path("date").asText())
                  .append(" | ").append(h.path("account").asText())
                  .append(" | `").append(h.path("description").asText())
                  .append("` | ").append(usd(h.path("amount"))).append(" |\n");
            }
        }
        sb.append("\nOn this loan, any single deposit of ").append(usd(ld.path("threshold")))
          .append(" or more needs documentation, and any cash deposit does regardless of size. ")
          .append("Everything else is fine as-is.\n");
    }

    /**
     * Prints nothing when no deposit qualifies — the requested "if no, list nothing".
     *
     * <p>An absent threshold is not that case and is never silent: no deposit can qualify when
     * there is nothing to qualify against, so staying quiet would read as "checked, all clear".
     */
    private void appendLargeDeposits(StringBuilder sb, JsonNode ld) {
        String status = status(ld);
        if (UNAVAILABLE.equals(status)) {
            sb.append("### Large deposits — not checked\n\n")
              .append("No sourcing threshold could be applied (").append(unavailableReason(ld))
              .append("). No deposit in this ledger has been screened for sourcing.\n\n");
            return;
        }
        if (NOT_APPLICABLE.equals(status)) {
            sb.append("### Large deposits — not required\n\n")
              .append(programWords(ld)).append(" does not require large-deposit sourcing on a ")
              .append(purposeWords(ld)).append(".\n\n");
            return;
        }
        JsonNode hits = ld.path("hits");
        if (hits.size() == 0) return;
        sb.append("### Large deposits — ").append(hits.size())
          .append(hits.size() == 1 ? " needs" : " need").append(" sourcing\n\n")
          .append("Threshold ").append(usd(ld.path("threshold")))
          .append(" (").append(ld.path("thresholdPct").asText("")).append("% of ")
          .append(basisWords(ld)).append(", ").append(programWords(ld)).append(")\n\n")
          .append("| Date | Account | Description | Amount |\n|---|---|---|---|\n");
        for (JsonNode h : hits) {
            sb.append("| ").append(h.path("date").asText())
              .append(" | ").append(h.path("account").asText())
              .append(" | ").append(h.path("description").asText())
              .append(" | **").append(usd(h.path("amount"))).append("** |\n");
        }
        if (ld.path("requiresPatternReview").asBoolean(false)) {
            sb.append("\n*").append(programWords(ld))
              .append(" requires more than the arithmetic: a deposit over the threshold is")
              .append(" sourced only if it is also inconsistent with the borrower's established")
              .append(" deposit pattern. That judgement is the reviewer's, not the engine's.*\n");
        }
        sb.append('\n');
    }

    private void appendBalances(StringBuilder sb, JsonNode balances) {
        if (balances.size() == 0) return;
        sb.append("### Balances\n\n| Account | Period | Beginning | Ending |\n|---|---|---|---|\n");
        for (JsonNode b : balances) {
            sb.append("| ").append(b.path("account").asText())
              .append(" | ").append(b.path("statementStart").asText())
              .append(" – ").append(b.path("statementEnd").asText())
              .append(" | ").append(usdOrDash(b.path("beginningBalance")))
              .append(" | ").append(usdOrDash(b.path("endingBalance"))).append(" |\n");
        }
        sb.append('\n');
    }

    /** The 2026-09-16 checks that ran and found nothing, for the "Checked, nothing found" line. */
    private static List<String> quietChecks(JsonNode d) {
        List<String> quiet = new ArrayList<>();
        JsonNode cash = d.path("cashDeposits");
        if (cash.path("configured").asBoolean(false) && cash.path("hits").size() == 0) quiet.add("Cash deposits");
        JsonNode emd = d.path("earnestMoney");
        if (emd.path("configured").asBoolean(false) && emd.path("hits").size() == 0) quiet.add("Earnest money");
        JsonNode o = d.path("overdrafts");
        if (!o.isMissingNode() && o.path("negativeBalances").size() == 0
                && o.path("negativeEndingBalances").size() == 0 && o.path("feeHits").size() == 0) {
            quiet.add("Overdrafts");
        }
        return quiet;
    }

    private void appendFlags(StringBuilder sb, JsonNode flags, List<String> quietChecks) {
        List<String> empty = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        for (JsonNode f : flags) {
            if (f.path("hits").size() == 0) {
                empty.add(f.path("label").asText());
                continue;
            }
            for (JsonNode h : f.path("hits")) {
                // usdSigned renders the sign once from the value itself rather than
                // hard-coding a minus in the layout.
                rows.append("| ").append(f.path("label").asText())
                    .append(" | ").append(h.path("date").asText())
                    .append(" | ").append(h.path("description").asText())
                    .append(" | ").append(usdSigned(h.path("amount"))).append(" |\n");
            }
        }
        if (rows.length() > 0) {
            sb.append("### Flagged descriptions\n\n| Rule | Date | Description | Amount |\n|---|---|---|---|\n")
              .append(rows).append('\n');
        }
        empty.addAll(quietChecks);
        if (!empty.isEmpty()) {
            sb.append("*Checked, nothing found: ").append(String.join(" · ", empty)).append("*\n\n");
        }
    }

    /**
     * Same three outcomes as large deposits (it shares the threshold), applied to debits. A
     * hit whose description matches the earnest-money keywords says so in its own column.
     */
    private void appendLargeWithdrawals(StringBuilder sb, JsonNode lw) {
        String status = status(lw);
        if (UNAVAILABLE.equals(status)) {
            sb.append("### Large withdrawals — not checked\n\nNo threshold could be applied (")
              .append(unavailableReason(lw)).append(").\n\n");
            return;
        }
        if (NOT_APPLICABLE.equals(status)) {
            sb.append("### Large withdrawals — not required\n\n").append(programWords(lw))
              .append(" requires no sourcing on a ").append(purposeWords(lw))
              .append(", so withdrawals were not screened against a threshold.\n\n");
            return;
        }
        JsonNode hits = lw.path("hits");
        if (hits.size() == 0) return;
        sb.append("### Large withdrawals — ").append(hits.size())
          .append("\n\nSame threshold as deposits: ").append(usd(lw.path("threshold")))
          .append("\n\n| Date | Account | Description | Amount | Note |\n|---|---|---|---|---|\n");
        for (JsonNode h : hits) {
            sb.append("| ").append(h.path("date").asText())
              .append(" | ").append(h.path("account").asText())
              .append(" | ").append(h.path("description").asText())
              .append(" | **−").append(usd(h.path("amount"))).append("** | ")
              .append(h.path("possibleEarnestMoney").asBoolean(false) ? "possible earnest money" : "")
              .append(" |\n");
        }
        sb.append('\n');
    }

    /** Cash deposits and earnest money share one layout; amounts are magnitudes. */
    private void appendKeywordSection(StringBuilder sb, String title, JsonNode block) {
        if (!block.path("configured").asBoolean(false)) {
            sb.append("### ").append(title).append(" — not configured\n\n");
            return;
        }
        JsonNode hits = block.path("hits");
        if (hits.size() == 0) return;
        sb.append("### ").append(title).append(" — ").append(hits.size())
          .append("\n\n| Date | Account | Description | Amount |\n|---|---|---|---|\n");
        for (JsonNode h : hits) {
            sb.append("| ").append(h.path("date").asText())
              .append(" | ").append(h.path("account").asText())
              .append(" | ").append(h.path("description").asText())
              .append(" | ").append(usd(h.path("amount"))).append(" |\n");
        }
        sb.append('\n');
    }

    /** Negative-balance lines, negative ending balances, then fees — each only when present. */
    private void appendOverdrafts(StringBuilder sb, JsonNode o) {
        JsonNode neg = o.path("negativeBalances");
        JsonNode negEnd = o.path("negativeEndingBalances");
        JsonNode fees = o.path("feeHits");
        if (neg.size() == 0 && negEnd.size() == 0 && fees.size() == 0) return;
        sb.append("### Overdrafts\n\n");
        if (!o.path("runningBalancesPrinted").asBoolean(false)) {
            sb.append("*The statements print no running balance, so negative-balance days could not be")
              .append(" checked; fees and ending balances only.*\n\n");
        }
        if (neg.size() > 0) {
            sb.append("| Date | Account | Description | Balance |\n|---|---|---|---|\n");
            for (JsonNode n : neg) {
                sb.append("| ").append(n.path("date").asText())
                  .append(" | ").append(n.path("account").asText())
                  .append(" | ").append(n.path("description").asText())
                  .append(" | ").append(usdSigned(n.path("balance"))).append(" |\n");
            }
            sb.append('\n');
        }
        for (JsonNode n : negEnd) {
            sb.append("- **").append(n.path("account").asText()).append("** — Ending balance ")
              .append(usdSigned(n.path("endingBalance"))).append(" on ")
              .append(n.path("statementEnd").asText()).append('\n');
        }
        if (negEnd.size() > 0) sb.append('\n');
        if (fees.size() > 0) {
            sb.append("Fees\n\n| Date | Account | Description | Amount |\n|---|---|---|---|\n");
            for (JsonNode h : fees) {
                sb.append("| ").append(h.path("date").asText())
                  .append(" | ").append(h.path("account").asText())
                  .append(" | ").append(h.path("description").asText())
                  .append(" | −").append(usd(h.path("amount"))).append(" |\n");
            }
            sb.append('\n');
        }
    }

    private void appendRecurring(StringBuilder sb, JsonNode recurring) {
        if (recurring.size() == 0) return;
        sb.append("### Recurring withdrawals — ").append(recurring.size())
          .append(recurring.size() == 1 ? " payee\n\n" : " payees\n\n")
          .append("| Payee | Hits | Average | Total | Latest |\n|---|---|---|---|---|\n");
        for (JsonNode r : recurring) {
            sb.append("| ").append(r.path("payee").asText())
              .append(" | ").append(r.path("hits").asInt())
              .append(" | ").append(usd(r.path("average")))
              .append(" | ").append(usd(r.path("total")))
              .append(" | ").append(r.path("latest").asText()).append(" |\n");
        }
        sb.append('\n');
    }

    private void appendTotals(StringBuilder sb, JsonNode t) {
        if (t.isMissingNode()) return;
        // withdrawals is stored as a positive magnitude, so the minus belongs to the
        // layout here; net carries its own sign and must not be flattened by abs().
        sb.append("### Totals\n\nDeposits ").append(usd(t.path("deposits")))
          .append(" · Withdrawals −").append(usd(t.path("withdrawals")))
          .append(" · Net ").append(usdSigned(t.path("net"))).append("\n\n");
    }

    private void appendCoverage(StringBuilder sb, JsonNode c) {
        sb.append("### Coverage\n\nTarget ").append(c.path("requiredDays").asInt())
          .append(" days per account\n\n");
        JsonNode accounts = c.path("accounts");
        if (accounts.size() > 0) {
            sb.append("| Account | Statements | Period | Days | Gaps | Shortfall |\n|---|---|---|---|---|---|\n");
            for (JsonNode a : accounts) {
                int shortfall = a.path("shortfallDays").asInt();
                sb.append("| ").append(a.path("account").asText())
                  .append(" | ").append(a.path("statements").asInt())
                  .append(" | ").append(a.path("periodStart").asText()).append(" – ").append(a.path("periodEnd").asText())
                  .append(" | ").append(a.path("daysCovered").asInt())
                  .append(" | ").append(a.path("gapDays").asInt())
                  .append(" | ").append(shortfall == 0 ? "—" : shortfall + " days short").append(" |\n");
            }
            sb.append('\n');
        }
        JsonNode gaps = c.path("gaps");
        JsonNode incomplete = c.path("incompleteAccounts");
        if (gaps.size() == 0 && incomplete.size() == 0) {
            sb.append("No gaps in statement history.\n\n");
            return;
        }
        for (JsonNode g : gaps) {
            sb.append("- **").append(g.path("account").asText())
              .append("** — missing ").append(g.path("missingDays").asInt())
              .append(" days between ").append(g.path("after").asText())
              .append(" and ").append(g.path("before").asText()).append("\n");
        }
        for (JsonNode a : incomplete) {
            sb.append("- **").append(a.asText())
              .append("** — at least one page could not be read\n");
        }
        sb.append('\n');
    }

    /** Magnitude only — callers that need a sign use usdSigned. */
    private static String usd(JsonNode n) {
        String raw = n.asText("");
        if (raw.isBlank()) return "—";
        return "$" + MONEY.format(new BigDecimal(raw).abs());
    }

    /** Renders the value's own sign once, using a true minus (U+2212), not a hyphen. */
    private static String usdSigned(JsonNode n) {
        String raw = n.asText("");
        if (raw.isBlank()) return "—";
        BigDecimal v = new BigDecimal(raw);
        return (v.signum() < 0 ? "−$" : "$") + MONEY.format(v.abs());
    }

    private static String usdOrDash(JsonNode n) {
        return n.isNull() || n.isMissingNode() ? "—" : usd(n);
    }

    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    /**
     * Which of the three large-deposit outcomes this run reached.
     *
     * <p>An absent status is read as UNAVAILABLE rather than APPLIED. The two failure modes are
     * not symmetric: treating a missing screen as a completed one tells a borrower they are clear
     * when nobody looked, while the reverse merely asks for a detail that is already on file.
     */
    private static String status(JsonNode largeDeposits) {
        return largeDeposits.path("status").asText(UNAVAILABLE);
    }

    /** The calc service's value-free reason, in the reader's words. */
    private static String unavailableReason(JsonNode largeDeposits) {
        return switch (largeDeposits.path("unavailableReason").asText("")) {
            case "LOAN_BASIS_NOT_SUPPLIED" -> "the loan program and purpose were not supplied";
            case "PROGRAM_NOT_SUPPLIED" -> "the loan program was not supplied";
            case "PURPOSE_NOT_SUPPLIED" -> "the loan purpose was not supplied";
            case "NO_RULE_FOR_PROGRAM" ->
                    "this analyzer declares no large-deposit rule for " + programWords(largeDeposits);
            case "NO_PUBLISHED_THRESHOLD" ->
                    programWords(largeDeposits) + " publishes no numeric large-deposit threshold,"
                            + " so this screen is an underwriter judgement";
            case "BASIS_AMOUNT_NOT_SUPPLIED" ->
                    "the loan did not carry the " + basisWords(largeDeposits)
                            + " this program's threshold is measured against";
            case "NO_RULES" -> "this analyzer declares no ledger rules";
            default -> "a loan detail the threshold depends on was unavailable";
        };
    }

    /** The agency name as a reader writes it, or a neutral phrase when none was recorded. */
    private static String programWords(JsonNode largeDeposits) {
        return switch (largeDeposits.path("program").asText("")) {
            case "FANNIE_MAE" -> "Fannie Mae";
            case "FREDDIE_MAC" -> "Freddie Mac";
            case "FHA" -> "FHA";
            case "VA" -> "VA";
            case "USDA" -> "USDA";
            default -> "this loan's program";
        };
    }

    private static String purposeWords(JsonNode largeDeposits) {
        return switch (largeDeposits.path("purpose").asText("")) {
            case "PURCHASE" -> "purchase";
            case "REFINANCE" -> "refinance";
            default -> "transaction of this type";
        };
    }

    private static String basisWords(JsonNode largeDeposits) {
        return switch (largeDeposits.path("basis").asText("")) {
            case "QUALIFYING_MONTHLY_INCOME" -> "qualifying monthly income";
            case "ADJUSTED_VALUE" -> "Adjusted Value";
            default -> "threshold basis";
        };
    }
}
