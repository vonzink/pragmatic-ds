package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.pack.AssetsRules;
import com.pragmaticds.rag.service.analyze.calc.AssetsCalcService;
import org.junit.jupiter.api.Test;

import com.pragmaticds.rag.service.analyze.calc.LoanBasis;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the three misses from the reported production run — the ending balance, a
 * large deposit, and a Capital One line the folder chat denied existed — plus the
 * overdraft and IRS detection requested at the same time.
 *
 * <p>Synthetic reconstruction only. No borrower value from a real file appears here.
 */
class AssetsReportedFailureTest {

    private static final AssetsRules RULES = new AssetsRules(List.of(
            new AssetsRules.LargeDepositRule("FANNIE_MAE", "QUALIFYING_MONTHLY_INCOME",
                    new java.math.BigDecimal("50"), List.of("PURCHASE"), false)
    ), List.of(
            new AssetsRules.FlagRule("irs", "IRS", List.of("IRS")),
            new AssetsRules.FlagRule("sba", "SBA", List.of("SBA")),
            new AssetsRules.FlagRule("child-support", "Child support", List.of("ND DHS-CSD")),
            new AssetsRules.FlagRule("overdraft", "Overdraft", List.of("OVERDRAFT", "OD FEE", "NSF"))
    ), 2);

    /** $9,216.66 qualifying monthly income on a conventional purchase — 50% is $4,608.33. */
    private static final LoanBasis CONVENTIONAL_PURCHASE = new LoanBasis(
            LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE,
            new BigDecimal("9216.66"), null);

    @Test
    void reportSurfacesEveryPreviouslyMissedItem() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.set("domain", om.readTree(
                getClass().getResourceAsStream("/fixtures/assets/reported-failure-ledger.json")));

        new AssetsCalcService().enrich(root, RULES, CONVENTIONAL_PURCHASE);
        String md = new AssetsReportRenderer().render(root);

        // 1. The ending balance is present and correct — originally missed entirely.
        assertTrue(md.contains("$1,666.66"), md);
        // 2. The large deposit is flagged rather than missed.
        assertTrue(md.contains("MOBILE DEPOSIT"), md);
        assertTrue(md.contains("$5,200.00"), md);
        // 3. The Capital One line is visible — folder chat previously denied it existed.
        assertTrue(md.contains("CAPITAL ONE MOBILE PMT"), md);
        // 4. Newly requested detections.
        assertTrue(md.contains("OVERDRAFT FEE"), md);
        assertTrue(md.contains("IRS USATAXPYMT 270601"), md);
        // 5. The ledger proves itself complete, so the figures above can be trusted.
        assertTrue(md.contains("Ledger reconciles"), md);
        assertFalse(md.contains("does not reconcile"), md);
        // 6. Contiguous May and June statements are not reported as a gap.
        assertTrue(md.contains("No gaps in statement history"), md);
    }

    /**
     * Dropping a single row must break reconciliation. This is the guard that makes the
     * whole design trustworthy: the tool reports the miss instead of the analyst finding
     * it by hand later.
     */
    @Test
    void droppingOneRowIsCaughtByReconciliation() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        ObjectNode domain = (ObjectNode) om.readTree(
                getClass().getResourceAsStream("/fixtures/assets/reported-failure-ledger.json"));

        // Remove the $5,200.00 deposit, exactly the class of miss originally reported.
        ((com.fasterxml.jackson.databind.node.ArrayNode)
                domain.path("accounts").get(1).path("transactions")).remove(0);
        root.set("domain", domain);

        new AssetsCalcService().enrich(root, RULES, CONVENTIONAL_PURCHASE);
        String md = new AssetsReportRenderer().render(root);

        assertTrue(md.startsWith("> **Ledger does not reconcile"), md);
        assertTrue(md.contains("$5,200.00"), md);
        assertTrue(md.contains("not trustworthy"), md);
    }
}
