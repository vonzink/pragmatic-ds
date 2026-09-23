package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.pack.AssetsRules;
import com.pragmaticds.rag.service.analyze.calc.AssetsCalcService;
import com.pragmaticds.rag.service.analyze.calc.LoanBasis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layout only. Every figure asserted here comes from the derived block, so a change
 * to the report's shape can never move a number.
 */
class AssetsReportRendererTest {

    private final AssetsCalcService calc = new AssetsCalcService();
    private final AssetsReportRenderer renderer = new AssetsReportRenderer();
    private final ObjectMapper om = new ObjectMapper();

    static final AssetsRules RULES = new AssetsRules(List.of(
            new AssetsRules.LargeDepositRule("FANNIE_MAE", "QUALIFYING_MONTHLY_INCOME",
                    new BigDecimal("50"), List.of("PURCHASE"), false),
            new AssetsRules.LargeDepositRule("FHA", "ADJUSTED_VALUE",
                    new BigDecimal("1"), List.of("PURCHASE", "REFINANCE"), true)
    ), List.of(
            new AssetsRules.FlagRule("irs", "IRS", List.of("IRS")),
            new AssetsRules.FlagRule("overdraft", "Overdraft", List.of("OVERDRAFT"))
    ), 2);

    /** $9,216.66 qualifying monthly income on a conventional purchase — 50% is $4,608.33. */
    static final LoanBasis CONVENTIONAL_PURCHASE = new LoanBasis(
            LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE,
            new BigDecimal("9216.66"), null);

    private String render(String domainJson) {
        return render(domainJson, CONVENTIONAL_PURCHASE);
    }

    private String render(String domainJson, LoanBasis loan) {
        try {
            ObjectNode root = om.createObjectNode();
            root.set("domain", om.readTree(domainJson));
            calc.enrich(root, RULES, loan);
            return renderer.render(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A reconciling ledger with one deposit that would clear a 50%-of-$9,216.66 threshold. */
    private static final String ONE_BIG_DEPOSIT = """
        {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
          "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
          "beginningBalance":100.00,"endingBalance":5300.00,"transcriptionComplete":true,
          "transactions":[
            {"date":"2026-06-09","description":"MOBILE DEPOSIT","amount":5200.00}]}]}
        """;

    /**
     * A parsed run carries no loan context, so the threshold basis is absent on every one of
     * them. Printing "No deposits need documentation" there would hand a borrower a clean bill
     * of health for a check that never ran — and they cannot tell the two apart.
     */
    @Test
    void anAbsentThresholdIsReportedAsUncheckedRatherThanClear() {
        String md = render(ONE_BIG_DEPOSIT, null);

        assertTrue(md.contains("Large deposits — not checked"), md);
        assertTrue(md.contains("loan program and purpose were not supplied"), md);
        assertTrue(md.contains("not ready to send"), md);
        assertFalse(md.contains("No deposits need documentation"), md);
        assertFalse(md.contains("Nothing needed from you on assets"), md);
    }

    /**
     * A conventional refinance is genuinely clear, and the borrower is told so in those words.
     * This is the state that must NOT be collapsed into "not checked" — the borrower has
     * nothing to send, and saying "we could not check" would ask them for documents that
     * Fannie Mae never wanted.
     */
    @Test
    void aConventionalRefinanceTellsTheBorrowerNothingIsNeededAndWhy() {
        String md = render(ONE_BIG_DEPOSIT, new LoanBasis(
                LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.REFINANCE,
                new BigDecimal("9216.66"), null));

        assertTrue(md.contains("Large deposits — not required"), md);
        assertTrue(md.contains("Fannie Mae does not require large-deposit sourcing"), md);
        assertTrue(md.contains("No deposits need documentation"), md);
        assertFalse(md.contains("not checked"), md);
        assertFalse(md.contains("not ready to send"), md);
    }

    /** FHA clears the same deposit on value, and says the pattern call is still a human's. */
    @Test
    void anFhaThresholdNamesItsBasisAndKeepsThePatternJudgementWithTheReviewer() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":8100.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-09","description":"MOBILE DEPOSIT","amount":8000.00}]}]}
            """, new LoanBasis(LoanBasis.Program.FHA, LoanBasis.Purpose.PURCHASE,
                    new BigDecimal("9216.66"), new BigDecimal("600000.00")));

        assertTrue(md.contains("Adjusted Value"), md);
        assertTrue(md.contains("FHA"), md);
        assertTrue(md.contains("deposit pattern"), md);
        assertFalse(md.contains("qualifying monthly income"), md);
    }

    /**
     * The same ledger WITH a basis still reports the deposit, so the guard above is doing its
     * job by discriminating on the threshold and not by suppressing the section outright.
     */
    @Test
    void thatSameLedgerStillFlagsTheDepositOnceTheBasisExists() {
        String md = render(ONE_BIG_DEPOSIT);

        assertTrue(md.contains("Large deposits — 1 needs sourcing"), md);
        assertTrue(md.contains("MOBILE DEPOSIT"), md);
        assertFalse(md.contains("not checked"), md);
    }

    @Test
    void rendersFlaggedDepositAndFlaggedDescriptions() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":5265.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-09","description":"MOBILE DEPOSIT","amount":5200.00},
                {"date":"2026-06-27","description":"OVERDRAFT FEE","amount":-35.00}]}]}
            """);

        assertTrue(md.contains("Large deposits"), md);
        assertTrue(md.contains("MOBILE DEPOSIT"), md);
        assertTrue(md.contains("$5,200.00"), md);
        assertTrue(md.contains("$4,608.33"), md);
        assertTrue(md.contains("OVERDRAFT FEE"), md);
        // A debit renders its sign once, from the value — never a hard-coded minus
        // in front of an already-negative number.
        assertTrue(md.contains("−$35.00"), md);
        assertFalse(md.contains("$-35.00"), md);
    }

    /**
     * The deposits block vanishes when nothing qualifies, but the rule roll-up proves
     * the check ran — a vanished section is indistinguishable from a skipped one.
     */
    @Test
    void emptyChecksCollapseToOneLine() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":96.00,"transcriptionComplete":true,
              "transactions":[{"date":"2026-06-02","description":"COFFEE","amount":-4.00}]}]}
            """);

        assertFalse(md.contains("Large deposits"), md);
        assertTrue(md.contains("Checked, nothing found"), md);
        assertTrue(md.contains("IRS"), md);
        assertTrue(md.contains("Overdraft"), md);
    }

    @Test
    void leadsWithMismatchBannerWhenLedgerDoesNotReconcile() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":4130.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-02","description":"DEPOSIT","amount":50.00},
                {"date":"2026-06-03","description":"COFFEE","amount":-20.00}]}]}
            """);

        assertTrue(md.startsWith("> **Ledger does not reconcile"), md);
        assertTrue(md.contains("$4,000.00"), md);
        assertTrue(md.contains("not trustworthy"), md);
    }

    @Test
    void reconcilingLedgerShowsCheckmarkNotBanner() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":130.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-02","description":"DEPOSIT","amount":50.00},
                {"date":"2026-06-03","description":"COFFEE","amount":-20.00}]}]}
            """);

        assertFalse(md.contains("does not reconcile"), md);
        assertTrue(md.contains("Ledger reconciles"), md);
    }

    /** Counts agree with their nouns — this report goes in front of a client. */
    @Test
    void countsArePluralised() {
        String one = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":5240.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-09","description":"MOBILE DEPOSIT","amount":5200.00},
                {"date":"2026-05-14","description":"GYM","amount":-30.00},
                {"date":"2026-06-14","description":"GYM","amount":-30.00}]}]}
            """);
        assertTrue(one.contains("1 needs sourcing"), one);
        assertTrue(one.contains("1 payee\n"), one);

        String many = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":10340.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-09","description":"DEP ONE","amount":5200.00},
                {"date":"2026-06-10","description":"DEP TWO","amount":5200.00},
                {"date":"2026-05-14","description":"GYM","amount":-30.00},
                {"date":"2026-06-14","description":"GYM","amount":-30.00},
                {"date":"2026-05-02","description":"NETFLIX","amount":-50.00},
                {"date":"2026-06-02","description":"NETFLIX","amount":-50.00}]}]}
            """);
        assertTrue(many.contains("2 need sourcing"), many);
        assertTrue(many.contains("2 payees"), many);
    }

    // --- client-facing crop ---

    @Test
    void clientBlockListsFlaggedDeposit() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":5300.00,"transcriptionComplete":true,
              "transactions":[{"date":"2026-06-09","description":"MOBILE DEPOSIT","amount":5200.00}]}]}
            """);

        assertTrue(md.contains("For the client"), md);
        assertTrue(md.contains("1 deposit needs documentation"), md);
        assertTrue(md.contains("$4,608.33 or more needs documentation"), md);
        // Quoted verbatim so the borrower can find the line on their own statement.
        assertTrue(md.contains("`MOBILE DEPOSIT`"), md);
    }

    /** The one section that speaks when nothing is flagged. */
    @Test
    void clientBlockConfirmsWhenNothingFlagged() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":96.00,"transcriptionComplete":true,
              "transactions":[{"date":"2026-06-02","description":"COFFEE","amount":-4.00}]}]}
            """);

        assertTrue(md.contains("No deposits need documentation"), md);
        assertTrue(md.contains("Nothing needed from you on assets"), md);
        assertTrue(md.contains("1 statement"), md);
        // The loan-officer section still stays silent — only the client block speaks.
        assertFalse(md.contains("### Large deposits"), md);
    }

    /** A ledger we cannot trust must never produce a clean bill of health for a borrower. */
    @Test
    void clientBlockSuppressedWhenLedgerDoesNotReconcile() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":100.00,"endingBalance":4130.00,"transcriptionComplete":true,
              "transactions":[
                {"date":"2026-06-02","description":"DEPOSIT","amount":50.00},
                {"date":"2026-06-03","description":"COFFEE","amount":-20.00}]}]}
            """);

        assertFalse(md.contains("Nothing needed from you on assets"), md);
        assertTrue(md.contains("not ready to send"), md);
    }

    /** A net loss keeps its sign; abs() must not flatten it to a positive. */
    @Test
    void negativeNetKeepsItsSign() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-06-01","end":"2026-06-30"},
              "beginningBalance":500.00,"endingBalance":400.00,"transcriptionComplete":true,
              "transactions":[{"date":"2026-06-02","description":"BILL","amount":-100.00}]}]}
            """);

        assertTrue(md.contains("Net −$100.00"), md);
    }

    // --- statement checks (2026-09-16) ---

    private static final String TWO_STATEMENTS_WITH_GAP = """
        {"accounts":[
          {"institution":"WF","maskedNumber":"7418","type":"checking",
           "statementPeriod":{"start":"2026-04-01","end":"2026-04-30"},
           "beginningBalance":100.00,"endingBalance":100.00,"transcriptionComplete":true,"transactions":[]},
          {"institution":"WF","maskedNumber":"7418","type":"checking",
           "statementPeriod":{"start":"2026-05-13","end":"2026-06-12"},
           "beginningBalance":100.00,"endingBalance":-20.00,"transcriptionComplete":true,
           "transactions":[
             {"date":"2026-05-14","description":"ATM DEPOSIT","amount":300.00,"runningBalance":400.00},
             {"date":"2026-05-20","description":"CHECK 1042 FIRST AMERICAN TITLE","amount":-5000.00,"runningBalance":-4600.00},
             {"date":"2026-05-21","description":"OD FEE","amount":-35.00,"runningBalance":-4635.00},
             {"date":"2026-06-01","description":"PAYROLL","amount":4615.00,"runningBalance":-20.00}]}]}
        """;

    @Test
    void coverageTableListsDaysGapsAndShortfall() {
        String md = render(TWO_STATEMENTS_WITH_GAP);
        assertTrue(md.contains("### Coverage"), md);
        assertTrue(md.contains("Target 60 days per account"), md);
        assertTrue(md.contains("| WF ••7418 | 2 | 2026-04-01 – 2026-06-12 | 61 | 12 | — |"), md);
        assertTrue(md.contains("missing 12 days between 2026-04-30 and 2026-05-13"), md);
    }

    @Test
    void newSectionsRenderTheirHits() {
        String md = render(TWO_STATEMENTS_WITH_GAP);
        assertTrue(md.contains("### Large withdrawals — 1"), md);
        assertTrue(md.contains("possible earnest money"), md);
        assertTrue(md.contains("### Cash deposits — 1"), md);
        assertTrue(md.contains("### Earnest money — 1"), md);
        assertTrue(md.contains("### Overdrafts"), md);
        assertTrue(md.contains("| 2026-05-20 | WF ••7418 | CHECK 1042 FIRST AMERICAN TITLE | −$4,600.00 |"), md);
        assertTrue(md.contains("Ending balance −$20.00"), md);
    }

    @Test
    void quietLedgerCollapsesTheNewChecksIntoOneLine() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-05-01","end":"2026-06-29"},
              "beginningBalance":100.00,"endingBalance":120.00,"transcriptionComplete":true,
              "transactions":[{"date":"2026-05-02","description":"PAYROLL","amount":20.00,"runningBalance":120.00}]}]}
            """);
        assertFalse(md.contains("### Large withdrawals"), md);
        assertFalse(md.contains("### Cash deposits"), md);
        assertFalse(md.contains("### Overdrafts"), md);
        assertTrue(md.contains("Cash deposits · Earnest money · Overdrafts"), md);
    }

    @Test
    void clientNoticeAsksForPaperworkOnACashDepositEvenWithNoLargeDeposit() {
        String md = render("""
            {"accounts":[{"institution":"WF","maskedNumber":"7418","type":"checking",
              "statementPeriod":{"start":"2026-05-01","end":"2026-06-29"},
              "beginningBalance":100.00,"endingBalance":600.00,"transcriptionComplete":true,
              "transactions":[{"date":"2026-05-02","description":"CASH DEPOSIT","amount":500.00}]}]}
            """);
        assertTrue(md.contains("1 deposit needs documentation"), md);
        assertTrue(md.contains("`CASH DEPOSIT`"), md);
    }
}
