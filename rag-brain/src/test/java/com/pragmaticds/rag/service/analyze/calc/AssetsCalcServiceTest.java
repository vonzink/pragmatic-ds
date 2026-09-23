package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.pack.AssetsRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rules over a transcribed ledger. Every test here runs without a model, which is the
 * whole point of moving the rules out of the prompt: a deposit that reached the ledger
 * can no longer be missed by chance.
 */
class AssetsCalcServiceTest {

    private final AssetsCalcService svc = new AssetsCalcService();
    private final ObjectMapper om = new ObjectMapper();

    /**
     * The four programs the pack declares, so a test can pick the wrong one on purpose.
     * Fannie is purchase-only and income-based; FHA applies to both purposes and is measured
     * against the Adjusted Value; VA publishes no computable threshold at all.
     */
    static final AssetsRules RULES = new AssetsRules(List.of(
            new AssetsRules.LargeDepositRule("FANNIE_MAE", "QUALIFYING_MONTHLY_INCOME",
                    new BigDecimal("50"), List.of("PURCHASE"), false),
            new AssetsRules.LargeDepositRule("FHA", "ADJUSTED_VALUE",
                    new BigDecimal("1"), List.of("PURCHASE", "REFINANCE"), true),
            new AssetsRules.LargeDepositRule("VA", AssetsRules.LargeDepositRule.UNDERWRITER_JUDGEMENT,
                    null, List.of("PURCHASE", "REFINANCE"), true)
    ), List.of(
            new AssetsRules.FlagRule("irs", "IRS", List.of("IRS")),
            new AssetsRules.FlagRule("sba", "SBA", List.of("SBA")),
            new AssetsRules.FlagRule("child-support", "Child support", List.of("ND DHS-CSD")),
            new AssetsRules.FlagRule("overdraft", "Overdraft", List.of("OVERDRAFT", "OD FEE", "NSF"))
    ), 2);

    /** $9,216.66 qualifying monthly income on a conventional purchase — 50% is $4,608.33. */
    static final LoanBasis CONVENTIONAL_PURCHASE = new LoanBasis(
            LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE,
            new BigDecimal("9216.66"), null);

    private ObjectNode env(String domainJson) {
        try {
            ObjectNode root = om.createObjectNode();
            root.set("domain", om.readTree(domainJson));
            return root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- reconciliation ---

    /** beginning 100.00 + (+50.00) + (-20.00) == ending 130.00 */
    @Test
    void reconciles_whenTransactionsSumToEndingBalance() {
        JsonNode d = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":130.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-02","description":"DEPOSIT","amount":50.00},
                {"date":"2026-06-03","description":"COFFEE","amount":-20.00}]}]}
            """), RULES, null).path("domain").path("derived").path("reconciliation");

        assertEquals("OK", d.path("status").asText());
        assertEquals(0, new BigDecimal("0.00").compareTo(new BigDecimal(d.path("deltaTotal").asText())));
    }

    @Test
    void mismatch_whenARowIsMissing() {
        JsonNode d = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":4130.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-02","description":"DEPOSIT","amount":50.00},
                {"date":"2026-06-03","description":"COFFEE","amount":-20.00}]}]}
            """), RULES, null).path("domain").path("derived").path("reconciliation");

        assertEquals("MISMATCH", d.path("status").asText());
        assertEquals(0, new BigDecimal("4000.00")
                .compareTo(new BigDecimal(d.path("deltaTotal").asText())));
    }

    @Test
    void unverifiable_whenBalancesAbsent() {
        JsonNode d = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "transcriptionComplete":true,
              "transactions":[{"date":"2026-06-02","description":"DEPOSIT","amount":50.00}]}]}
            """), RULES, null).path("domain").path("derived").path("reconciliation");

        assertEquals("UNVERIFIABLE", d.path("status").asText());
    }

    /** A known-partial ledger must never read as OK, even if the delta happens to be zero. */
    @Test
    void mismatch_whenTranscriptionIncomplete() {
        JsonNode d = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":130.00,"transcriptionComplete":false,
              "transactions":[
                {"date":"2026-06-02","description":"DEPOSIT","amount":50.00},
                {"date":"2026-06-03","description":"COFFEE","amount":-20.00}]}]}
            """), RULES, null).path("domain").path("derived").path("reconciliation");

        assertEquals("INCOMPLETE", d.path("status").asText());
    }

    @Test
    void noDomain_returnsEnvelopeUnchanged() {
        ObjectNode root = om.createObjectNode();
        root.put("reportMarkdown", "untouched");
        JsonNode out = svc.enrich(root, RULES, null);
        assertEquals("untouched", out.path("reportMarkdown").asText());
        assertTrue(out.path("domain").isMissingNode());
    }

    // --- large deposits: $9,216.66 x 50% = $4,608.33 ---

    private JsonNode deposits(String txns, LoanBasis loan) {
        return svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "transcriptionComplete":true,"transactions":[%s]}]}
            """.formatted(txns)), RULES, loan)
                .path("domain").path("derived").path("largeDeposits");
    }

    @Test
    void flagsDepositAboveThreshold() {
        JsonNode d = deposits("""
            {"date":"2026-06-09","description":"MOBILE DEPOSIT","amount":5200.00}
            """, CONVENTIONAL_PURCHASE);

        assertEquals("4608.33", d.path("threshold").asText());
        assertEquals("50", d.path("thresholdPct").asText());
        assertEquals(1, d.path("hits").size());
        assertEquals("MOBILE DEPOSIT", d.path("hits").get(0).path("description").asText());
        assertEquals("5200.00", d.path("hits").get(0).path("amount").asText());
    }

    /** Exactly at the threshold counts — "50% or more" is the guideline reading. */
    @Test
    void flagsDepositExactlyAtThreshold() {
        JsonNode d = deposits("""
            {"date":"2026-06-09","description":"WIRE IN","amount":4608.33}
            """, CONVENTIONAL_PURCHASE);
        assertEquals(1, d.path("hits").size());
    }

    @Test
    void ignoresDepositOneCentUnder() {
        JsonNode d = deposits("""
            {"date":"2026-06-09","description":"WIRE IN","amount":4608.32}
            """, CONVENTIONAL_PURCHASE);
        assertEquals(0, d.path("hits").size());
    }

    /** Withdrawals are negative and are never deposits, however large. */
    @Test
    void ignoresLargeWithdrawal() {
        JsonNode d = deposits("""
            {"date":"2026-06-09","description":"WIRE OUT","amount":-9000.00}
            """, CONVENTIONAL_PURCHASE);
        assertEquals(0, d.path("hits").size());
    }

    private static final String ONE_BIG_DEPOSIT =
            """
            {"date":"2026-06-09","description":"MOBILE DEPOSIT","amount":5200.00}
            """;

    /** Never silently use zero as the basis — that would flag every deposit in the file. */
    @Test
    void reportsWhyThresholdUncomputable_whenIncomeUnknown() {
        JsonNode d = deposits(ONE_BIG_DEPOSIT, new LoanBasis(
                LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE, null, null));
        assertTrue(d.path("threshold").isNull());
        assertEquals(0, d.path("hits").size());
        assertEquals("UNAVAILABLE", d.path("status").asText());
        assertEquals("BASIS_AMOUNT_NOT_SUPPLIED", d.path("unavailableReason").asText());
    }

    /**
     * No loan facts at all is the parsed path's standing state, and it must not read as a
     * completed screen. This is the case that silently produced "no deposits need
     * documentation" for every parsed run.
     */
    @Test
    void reportsWhyThresholdUncomputable_whenNoLoanFactsAtAll() {
        JsonNode d = deposits(ONE_BIG_DEPOSIT, null);
        assertEquals("UNAVAILABLE", d.path("status").asText());
        assertEquals("LOAN_BASIS_NOT_SUPPLIED", d.path("unavailableReason").asText());
        assertEquals(0, d.path("hits").size());
    }

    /** The program picks the rule; without it there is no test to apply. */
    @Test
    void reportsWhyThresholdUncomputable_whenProgramUnknown() {
        JsonNode d = deposits(ONE_BIG_DEPOSIT, new LoanBasis(
                null, LoanBasis.Purpose.PURCHASE, new BigDecimal("9216.66"), null));
        assertEquals("PROGRAM_NOT_SUPPLIED", d.path("unavailableReason").asText());
    }

    /**
     * A conventional REFINANCE needs no sourcing at all — Fannie's rule is purchase-only
     * because no borrower funds are required to purchase the property. That is a clean
     * answer and must not be reported as a failed or skipped check.
     */
    @Test
    void aConventionalRefinanceReportsSourcingNotRequiredRatherThanUnavailable() {
        JsonNode d = deposits(ONE_BIG_DEPOSIT, new LoanBasis(
                LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.REFINANCE,
                new BigDecimal("9216.66"), null));

        assertEquals("NOT_APPLICABLE", d.path("status").asText());
        assertEquals("SOURCING_NOT_REQUIRED_FOR_PURPOSE", d.path("notApplicableReason").asText());
        assertTrue(d.path("unavailableReason").isMissingNode());
        assertEquals(0, d.path("hits").size());
    }

    /**
     * The whole reason the rule is program-aware: the same $5,200 deposit on a $600,000 FHA
     * purchase is measured against 1% of Adjusted Value ($6,000) and does NOT need sourcing,
     * while Fannie's income test would have flagged it. One threshold cannot serve both.
     */
    @Test
    void fhaMeasuresAgainstAdjustedValueNotIncome() {
        JsonNode d = deposits(ONE_BIG_DEPOSIT, new LoanBasis(
                LoanBasis.Program.FHA, LoanBasis.Purpose.PURCHASE,
                new BigDecimal("9216.66"), new BigDecimal("600000.00")));

        assertEquals("APPLIED", d.path("status").asText());
        assertEquals("ADJUSTED_VALUE", d.path("basis").asText());
        assertEquals("6000.00", d.path("threshold").asText());
        assertEquals(0, d.path("hits").size(), "a $5,200 deposit is under FHA's $6,000 threshold");
        assertTrue(d.path("requiresPatternReview").asBoolean(),
                "FHA's numeric test is necessary but not sufficient");
    }

    /** VA publishes no number, so the screen refuses rather than borrowing Fannie's. */
    @Test
    void vaReportsNoPublishedThreshold() {
        JsonNode d = deposits(ONE_BIG_DEPOSIT, new LoanBasis(
                LoanBasis.Program.VA, LoanBasis.Purpose.PURCHASE,
                new BigDecimal("9216.66"), new BigDecimal("600000.00")));

        assertEquals("UNAVAILABLE", d.path("status").asText());
        assertEquals("NO_PUBLISHED_THRESHOLD", d.path("unavailableReason").asText());
    }

    // --- keyword flag rules ---

    private JsonNode flags(String txns) {
        return svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "transcriptionComplete":true,"transactions":[%s]}]}
            """.formatted(txns)), RULES, CONVENTIONAL_PURCHASE)
                .path("domain").path("derived").path("flags");
    }

    private static JsonNode rule(JsonNode flags, String id) {
        for (JsonNode f : flags) {
            if (f.path("ruleId").asText().equals(id)) return f;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    @Test
    void matchesEachConfiguredRule() {
        JsonNode f = flags("""
            {"date":"2026-06-20","description":"IRS USATAXPYMT 270601","amount":-780.00},
            {"date":"2026-06-03","description":"SBA EIDL LOAN PYMT","amount":-412.00},
            {"date":"2026-05-15","description":"ND DHS-CSD","amount":-644.00},
            {"date":"2026-06-27","description":"OVERDRAFT FEE","amount":-35.00}
            """);
        assertEquals(1, rule(f, "irs").path("hits").size());
        assertEquals(1, rule(f, "sba").path("hits").size());
        assertEquals(1, rule(f, "child-support").path("hits").size());
        assertEquals(1, rule(f, "overdraft").path("hits").size());
        assertEquals("IRS", rule(f, "irs").path("label").asText());
    }

    @Test
    void matchesCaseInsensitively() {
        JsonNode f = flags("""
            {"date":"2026-06-27","description":"Overdraft Fee - Item Returned","amount":-35.00}
            """);
        assertEquals(1, rule(f, "overdraft").path("hits").size());
    }

    /** Any one of a rule's substrings matches; NSF is an overdraft under the same rule. */
    @Test
    void matchesAnyAlternate() {
        JsonNode f = flags("""
            {"date":"2026-06-27","description":"NSF RETURN CHARGE","amount":-35.00}
            """);
        assertEquals(1, rule(f, "overdraft").path("hits").size());
    }

    /**
     * Rules with no hits stay present with an empty list. The report needs to say
     * "checked, nothing found" — a rule that vanishes is indistinguishable from a
     * rule that never ran.
     */
    @Test
    void emptyRuleIsPresentNotAbsent() {
        JsonNode f = flags("""
            {"date":"2026-06-02","description":"COFFEE","amount":-4.00}
            """);
        assertEquals(4, f.size());
        assertEquals(0, rule(f, "irs").path("hits").size());
    }

    /** One transaction can match two rules; each reports it independently. */
    @Test
    void oneTransactionCanMatchTwoRules() {
        JsonNode f = flags("""
            {"date":"2026-06-20","description":"IRS SBA COMBINED DEBIT","amount":-100.00}
            """);
        assertEquals(1, rule(f, "irs").path("hits").size());
        assertEquals(1, rule(f, "sba").path("hits").size());
    }

    // --- recurring withdrawals ---

    private JsonNode recurring(String txns) {
        return svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "transcriptionComplete":true,"transactions":[%s]}]}
            """.formatted(txns)), RULES, CONVENTIONAL_PURCHASE)
                .path("domain").path("derived").path("recurring");
    }

    @Test
    void groupsRepeatedPayeeAndSortsByTotal() {
        JsonNode r = recurring("""
            {"date":"2026-04-14","description":"CAPITAL ONE MOBILE PMT","amount":-412.00},
            {"date":"2026-05-14","description":"CAPITAL ONE MOBILE PMT","amount":-412.00},
            {"date":"2026-06-14","description":"CAPITAL ONE MOBILE PMT","amount":-412.00},
            {"date":"2026-05-01","description":"NETFLIX","amount":-15.99},
            {"date":"2026-06-01","description":"NETFLIX","amount":-15.99}
            """);
        assertEquals(2, r.size());
        assertEquals("CAPITAL ONE MOBILE PMT", r.get(0).path("payee").asText());
        assertEquals(3, r.get(0).path("hits").asInt());
        assertEquals("1236.00", r.get(0).path("total").asText());
        assertEquals("412.00", r.get(0).path("average").asText());
        assertEquals("2026-06-14", r.get(0).path("latest").asText());
        assertEquals("NETFLIX", r.get(1).path("payee").asText());
    }

    /** A single occurrence is not recurring at recurring-min-hits=2. */
    @Test
    void singleOccurrenceExcluded() {
        JsonNode r = recurring("""
            {"date":"2026-06-14","description":"ONE OFF PAYMENT","amount":-99.00}
            """);
        assertEquals(0, r.size());
    }

    /** Deposits are not withdrawals; a twice-monthly paycheck is not a recurring bill. */
    @Test
    void repeatedDepositsExcluded() {
        JsonNode r = recurring("""
            {"date":"2026-06-01","description":"ACME PAYROLL","amount":2000.00},
            {"date":"2026-06-15","description":"ACME PAYROLL","amount":2000.00}
            """);
        assertEquals(0, r.size());
    }

    /** Amounts are positive magnitudes; the section is titled "withdrawals". */
    @Test
    void amountsAreMagnitudes() {
        JsonNode r = recurring("""
            {"date":"2026-05-14","description":"GYM","amount":-30.00},
            {"date":"2026-06-14","description":"GYM","amount":-30.00}
            """);
        assertEquals("60.00", r.get(0).path("total").asText());
    }

    // --- balances, totals, coverage ---

    @Test
    void totalsSeparateDepositsFromWithdrawals() {
        JsonNode d = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":1080.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-02","description":"DEP A","amount":1000.00},
                {"date":"2026-06-03","description":"DEP B","amount":500.00},
                {"date":"2026-06-04","description":"BILL","amount":-520.00}]}]}
            """), RULES, CONVENTIONAL_PURCHASE).path("domain").path("derived");

        assertEquals("1500.00", d.path("totals").path("deposits").asText());
        assertEquals("520.00", d.path("totals").path("withdrawals").asText());
        assertEquals("980.00", d.path("totals").path("net").asText());
        assertEquals("100.00", d.path("balances").get(0).path("beginningBalance").asText());
        assertEquals("1080.00", d.path("balances").get(0).path("endingBalance").asText());
        assertEquals("WF ••7418", d.path("balances").get(0).path("account").asText());
    }

    /** Two statements for one account with April and June but no May. */
    @Test
    void detectsMissingStatementMonth() {
        JsonNode c = svc.enrich(env("""
            {"accounts":[
              {"institution":"Chase","maskedNumber":"2201","type":"checking",
               "statementPeriod":{"start":"2026-04-01","end":"2026-04-30"},
               "transcriptionComplete":true,"transactions":[]},
              {"institution":"Chase","maskedNumber":"2201","type":"checking",
               "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
               "transcriptionComplete":true,"transactions":[]}]}
            """), RULES, CONVENTIONAL_PURCHASE).path("domain").path("derived").path("coverage");

        assertEquals(1, c.path("gaps").size());
        assertEquals("Chase ••2201", c.path("gaps").get(0).path("account").asText());
        assertEquals("2026-04-30", c.path("gaps").get(0).path("after").asText());
        assertEquals("2026-06-01", c.path("gaps").get(0).path("before").asText());
    }

    /** Consecutive months are contiguous — 04-30 to 05-01 is not a gap. */
    @Test
    void consecutiveStatementsAreNotAGap() {
        JsonNode c = svc.enrich(env("""
            {"accounts":[
              {"institution":"Chase","maskedNumber":"2201","type":"checking",
               "statementPeriod":{"start":"2026-04-01","end":"2026-04-30"},
               "transcriptionComplete":true,"transactions":[]},
              {"institution":"Chase","maskedNumber":"2201","type":"checking",
               "statementPeriod":{"start":"2026-05-01","end":"2026-05-31"},
               "transcriptionComplete":true,"transactions":[]}]}
            """), RULES, CONVENTIONAL_PURCHASE).path("domain").path("derived").path("coverage");

        assertEquals(0, c.path("gaps").size());
    }

    @Test
    void listsIncompleteAccounts() {
        JsonNode c = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "transcriptionComplete":false,"transactions":[]}]}
            """), RULES, CONVENTIONAL_PURCHASE).path("domain").path("derived").path("coverage");

        assertEquals(1, c.path("incompleteAccounts").size());
        assertEquals("WF ••7418", c.path("incompleteAccounts").get(0).asText());
    }

    /** The client notice needs a reviewed-period summary; deriving it is this class's job. */
    @Test
    void coverageSummarisesReviewedPeriod() {
        JsonNode c = svc.enrich(env("""
            {"accounts":[
              {"institution":"WF","maskedNumber":"7418","type":"checking",
               "statementPeriod":{"start":"2026-04-01","end":"2026-04-30"},
               "transcriptionComplete":true,"transactions":[]},
              {"institution":"WF","maskedNumber":"7418","type":"checking",
               "statementPeriod":{"start":"2026-05-01","end":"2026-05-31"},
               "transcriptionComplete":true,"transactions":[]},
              {"institution":"Chase","maskedNumber":"2201","type":"checking",
               "statementPeriod":{"start":"2026-05-01","end":"2026-05-31"},
               "transcriptionComplete":true,"transactions":[]}]}
            """), RULES, CONVENTIONAL_PURCHASE).path("domain").path("derived").path("coverage");

        assertEquals(3, c.path("statementCount").asInt());
        assertEquals(2, c.path("accountCount").asInt());
        assertEquals("2026-04-01", c.path("periodStart").asText());
        assertEquals("2026-05-31", c.path("periodEnd").asText());
    }

    // --- coverage: days, gaps, shortfall ---

    private JsonNode coverageOf(String accountsJson) {
        return svc.enrich(env("{\"accounts\":[" + accountsJson + "]}"), RULES, CONVENTIONAL_PURCHASE)
                .path("domain").path("derived").path("coverage");
    }

    private static String stmt(String inst, String mask, String start, String end) {
        return """
            {"institution":"%s","maskedNumber":"%s","type":"checking",
             "statementPeriod":{"start":"%s","end":"%s"},"transcriptionComplete":true,"transactions":[]}
            """.formatted(inst, mask, start, end);
    }

    @Test
    void coverageCountsDaysPerAccountAndFlagsAShortfall() {
        JsonNode c = coverageOf(stmt("WF", "7418", "2026-04-01", "2026-04-30") + "," + stmt("WF", "7418", "2026-05-01", "2026-05-31"));
        assertEquals(60, c.path("requiredDays").asInt());
        JsonNode a = c.path("accounts").get(0);
        assertEquals("WF ••7418", a.path("account").asText());
        assertEquals(2, a.path("statements").asInt());
        assertEquals(61, a.path("daysCovered").asInt());
        assertEquals(61, a.path("spanDays").asInt());
        assertEquals(0, a.path("gapDays").asInt());
        assertEquals(0, a.path("shortfallDays").asInt());
        assertTrue(a.path("meetsRequirement").asBoolean());
        assertEquals(0, c.path("gaps").size());
    }

    @Test
    void coverageReportsAGapWithItsMissingDays() {
        JsonNode c = coverageOf(stmt("WF", "7418", "2026-04-01", "2026-04-30") + "," + stmt("WF", "7418", "2026-05-13", "2026-06-12"));
        JsonNode g = c.path("gaps").get(0);
        assertEquals("2026-04-30", g.path("after").asText());
        assertEquals("2026-05-13", g.path("before").asText());
        assertEquals(12, g.path("missingDays").asInt());
        JsonNode a = c.path("accounts").get(0);
        assertEquals(61, a.path("daysCovered").asInt());
        assertEquals(73, a.path("spanDays").asInt());
        assertEquals(12, a.path("gapDays").asInt());
    }

    @Test
    void coverageShortfallBelowSixtyDays() {
        JsonNode a = coverageOf(stmt("WF", "7418", "2026-05-01", "2026-05-31")).path("accounts").get(0);
        assertEquals(31, a.path("daysCovered").asInt());
        assertEquals(29, a.path("shortfallDays").asInt());
        assertFalse(a.path("meetsRequirement").asBoolean());
    }

    @Test
    void coverageDoesNotDoubleCountAnOverlappingStatement() {
        JsonNode a = coverageOf(stmt("WF", "7418", "2026-05-01", "2026-05-31") + "," + stmt("WF", "7418", "2026-05-15", "2026-06-14")).path("accounts").get(0);
        assertEquals(45, a.path("daysCovered").asInt());
        assertEquals(0, a.path("gapDays").asInt());
    }

    @Test
    void coverageIgnoresBlankDatesButStillCountsTheStatement() {
        JsonNode a = coverageOf(stmt("WF", "7418", "2026-05-01", "2026-05-31") + "," + stmt("WF", "7418", "", "")).path("accounts").get(0);
        assertEquals(2, a.path("statements").asInt());
        assertEquals(31, a.path("daysCovered").asInt());
    }

    // --- large withdrawals, cash, earnest money, overdrafts ---

    private JsonNode derivedFor(String txJson, LoanBasis loan) {
        return svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "transcriptionComplete":true,"transactions":[%s]}]}
            """.formatted(txJson)), RULES, loan).path("domain").path("derived");
    }

    @Test
    void largeWithdrawalUsesTheDepositThresholdOnDebits() {
        JsonNode w = derivedFor("""
            {"date":"2026-06-09","description":"WIRE OUT TITLE CO","amount":-5200.00},
            {"date":"2026-06-10","description":"RENT","amount":-4608.32},
            {"date":"2026-06-11","description":"MOBILE DEPOSIT","amount":5200.00}
            """, CONVENTIONAL_PURCHASE).path("largeWithdrawals");
        assertEquals("APPLIED", w.path("status").asText());
        assertEquals("4608.33", w.path("threshold").asText());
        assertEquals(1, w.path("hits").size());
        JsonNode hit = w.path("hits").get(0);
        assertEquals("5200.00", hit.path("amount").asText(), "reported as a magnitude");
        assertTrue(hit.path("possibleEarnestMoney").asBoolean(), "TITLE CO matches the earnest-money keywords");
    }

    @Test
    void largeWithdrawalSharesTheDepositRefusals() {
        JsonNode w = derivedFor("""
            {"date":"2026-06-09","description":"WIRE OUT","amount":-9000.00}
            """, new LoanBasis(LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.REFINANCE, new BigDecimal("9216.66"), null))
                .path("largeWithdrawals");
        assertEquals("NOT_APPLICABLE", w.path("status").asText());
        assertEquals(0, w.path("hits").size());
        JsonNode unavailable = derivedFor("{\"date\":\"2026-06-09\",\"description\":\"WIRE OUT\",\"amount\":-9000.00}", null).path("largeWithdrawals");
        assertEquals("UNAVAILABLE", unavailable.path("status").asText());
        assertEquals("LOAN_BASIS_NOT_SUPPLIED", unavailable.path("unavailableReason").asText());
    }

    @Test
    void cashDepositsMatchCreditsOnlyAtAnyAmount() {
        JsonNode c = derivedFor("""
            {"date":"2026-06-02","description":"ATM DEPOSIT 123 MAIN","amount":40.00},
            {"date":"2026-06-03","description":"CASH WITHDRAWAL","amount":-200.00},
            {"date":"2026-06-04","description":"Branch Deposit Cash","amount":1500.00}
            """, CONVENTIONAL_PURCHASE).path("cashDeposits");
        assertTrue(c.path("configured").asBoolean());
        assertEquals(2, c.path("hits").size());
        assertEquals("40.00", c.path("hits").get(0).path("amount").asText());
    }

    @Test
    void earnestMoneyMatchesDebitsOnlyAsMagnitudes() {
        JsonNode e = derivedFor("""
            {"date":"2026-06-05","description":"CHECK 1042 FIRST AMERICAN TITLE","amount":-3000.00},
            {"date":"2026-06-06","description":"EMD REFUND","amount":3000.00}
            """, CONVENTIONAL_PURCHASE).path("earnestMoney");
        assertEquals(1, e.path("hits").size());
        assertEquals("3000.00", e.path("hits").get(0).path("amount").asText());
    }

    @Test
    void emptyKeywordListReportsNotConfigured() {
        AssetsRules off = new AssetsRules(RULES.largeDepositRules(), RULES.flagRules(), 2, 60, List.of(), List.of());
        JsonNode d = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},"transcriptionComplete":true,
              "transactions":[{"date":"2026-06-02","description":"CASH DEPOSIT","amount":40.00}]}]}
            """), off, CONVENTIONAL_PURCHASE).path("domain").path("derived");
        assertFalse(d.path("cashDeposits").path("configured").asBoolean());
        assertEquals(0, d.path("cashDeposits").path("hits").size());
        assertFalse(d.path("earnestMoney").path("configured").asBoolean());
    }

    @Test
    void overdraftsFromRunningBalanceEndingBalanceAndFees() {
        JsonNode o = svc.enrich(env("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":-35.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-02","description":"RENT","amount":-150.00,"runningBalance":-50.00},
                {"date":"2026-06-03","description":"OD FEE","amount":-35.00,"runningBalance":-85.00},
                {"date":"2026-06-04","description":"PAYROLL","amount":50.00,"runningBalance":-35.00}]}]}
            """), RULES, CONVENTIONAL_PURCHASE).path("domain").path("derived").path("overdrafts");
        assertTrue(o.path("feeRuleConfigured").asBoolean());
        assertTrue(o.path("runningBalancesPrinted").asBoolean());
        assertEquals(3, o.path("negativeBalances").size());
        assertEquals("-50.00", o.path("negativeBalances").get(0).path("balance").asText());
        assertEquals(1, o.path("negativeEndingBalances").size());
        assertEquals("-35.00", o.path("negativeEndingBalances").get(0).path("endingBalance").asText());
        assertEquals(1, o.path("feeHits").size());
        JsonNode by = o.path("byAccount").get(0);
        assertEquals(3, by.path("negativeBalanceLines").asInt());
        assertEquals(1, by.path("fees").asInt());
    }

    @Test
    void overdraftsWithoutRunningBalancesReportNoneNotClear() {
        JsonNode o = derivedFor("""
            {"date":"2026-06-02","description":"RENT","amount":-150.00}
            """, CONVENTIONAL_PURCHASE).path("overdrafts");
        assertEquals(0, o.path("negativeBalances").size());
        assertFalse(o.path("runningBalancesPrinted").asBoolean());
    }
}
