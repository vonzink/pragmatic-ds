package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.service.analyze.calc.CalculationExecutor;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import com.pragmaticds.rag.service.analyze.calc.IncomeDomainEnricher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layout only: every figure in the worksheet comes from calculations[] inputs, results or
 * intermediates, so a layout change can never move a number.
 */
class IncomeWorksheetRendererTest {

    private final ObjectMapper om = new ObjectMapper();
    private final CalculationExecutor executor = new CalculationExecutor(new IncomeCalcService(), om);
    private final IncomeDomainEnricher enricher = new IncomeDomainEnricher();
    private final IncomeWorksheetRenderer renderer = new IncomeWorksheetRenderer();

    private String render(String calcs, String sources, String totalCalcId, String reconciliationCalcId)
            throws Exception {
        JsonNode model = om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"r",
                 "facts":[],"assumptions":[],"warnings":[],"recommendations":[],
                 "calculations":[%s],"missingItems":[],"citations":[],"confidence":0.9,
                 "domain":{"schemaVersion":"income-domain-v2",
                   "borrowers":[{"name":"Borrower 1","sources":[%s]}],
                   "totalCalcId":%s,"reconciliationCalcId":%s,"opportunities":[],"gaps":[]}}
                """.formatted(calcs, sources, quoted(totalCalcId), quoted(reconciliationCalcId)));
        ObjectNode enriched = executor.execute(model).enriched();
        enricher.enrich(enriched);
        return renderer.render(enriched);
    }

    private static String quoted(String id) {
        return id == null ? "null" : "\"" + id + "\"";
    }

    private static final String GOLDEN_CALCS = """
            {"id":"src1-monthly-rate","name":"Current pay","method":"income.monthly_from_rate.v1",
             "inputs":{"rate":4670.69,"frequency":"BIWEEKLY"}},
            {"id":"src1-ytd-avg","name":"YTD average","method":"income.ytd_monthly_average.v1",
             "inputs":{"ytdAmount":4670.69,"periodStart":"2026-01-01","periodEnd":"2026-01-15"}},
            {"id":"src1-py1-monthly","name":"2025 Form 1040 total income","method":"income.monthly_from_annual.v1",
             "inputs":{"annual":104982.00}},
            {"id":"src1-variance","name":"Variance","method":"income.variance.v1",
             "inputs":{"computedRef":"src1-monthly-rate","stated":9000}},
            {"id":"total","name":"Total","method":"income.total_monthly.v1",
             "inputs":{"amountRefs":["src1-monthly-rate"]}},
            {"id":"reconciliation","name":"Reconciliation","method":"income.variance.v1",
             "inputs":{"computedRef":"total","stated":9000}}
            """;

    private static final String GOLDEN_SOURCE = """
            {"type":"W-2 base","employer":"ACME WIDGETS LLC","factIds":[],
             "calcIds":["src1-monthly-rate","src1-ytd-avg","src1-py1-monthly"],
             "qualifyingCalcId":"src1-monthly-rate","monthly":null,"urlaStatedMonthly":9000,
             "varianceCalcId":"src1-variance","concerns":[],"confidence":"HIGH"}
            """;

    @Test
    void goldenWorksheetFromTheEngineGoldenPaystub() throws Exception {
        String expected = """
                ## Calculation worksheet

                ```text
                ACME WIDGETS LLC — W-2 base
                  Current pay:   $4,670.69 × 26 (BIWEEKLY) = $121,437.94 ÷ 12 = $10,119.83
                  YTD check:     not computed: period covers 0.4839 months (< 0.5); refusing to average
                  Prior year:    2025 Form 1040 total income $104,982.00 ÷ 12 = $8,748.50
                  Qualifying:    $10,119.83 (current pay)
                  Application:   stated $9,000.00 → variance +$1,119.83 (+12.44%), MATERIAL

                Total qualifying monthly income: $10,119.83
                Application total: stated $9,000.00 → variance +$1,119.83 (+12.44%), MATERIAL
                ```""";
        assertEquals(expected, render(GOLDEN_CALCS, GOLDEN_SOURCE, "total", "reconciliation"));
    }

    @Test
    void anInferredFrequencyIsLabelledAsInferred() throws Exception {
        String calcs = """
                {"id":"p","name":"Current pay","method":"income.monthly_from_period_gross.v1",
                 "inputs":{"gross":4030.77,"periodStart":"2026-02-02","periodEnd":"2026-02-15"}}
                """;
        String source = GOLDEN_SOURCE.replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "\"p\"")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":\"p\"")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null");
        String worksheet = render(calcs, source, null, null);
        assertTrue(worksheet.contains(
                "  Current pay:   $4,030.77 × 26 (BIWEEKLY, inferred from 14-day period) = $104,800.02 ÷ 12 = $8,733.34\n"),
                worksheet);
        assertTrue(worksheet.contains("  Application:   stated $9,000.00 (no variance calculation requested)\n"), worksheet);
        assertTrue(worksheet.contains("Total qualifying monthly income: not computed (no total calculation requested)\n"),
                worksheet);
    }

    @Test
    void hourlyYtdAndMultiplePriorYearsRenderInOrder() throws Exception {
        String calcs = """
                {"id":"py2","name":"2024 W-2 Box 1 wages","method":"income.monthly_from_annual.v1","inputs":{"annual":69183.96}},
                {"id":"h","name":"Current pay","method":"income.monthly_from_rate.v1",
                 "inputs":{"rate":25.50,"frequency":"HOURLY","hoursPerWeek":40}},
                {"id":"ytd","name":"YTD","method":"income.ytd_monthly_average.v1",
                 "inputs":{"ytdAmount":15938.47,"periodStart":"2026-01-01","periodEnd":"2026-02-12"}},
                {"id":"py1","name":"2025 W-2 Box 1 wages","method":"income.monthly_from_annual.v1","inputs":{"annual":82981.00}}
                """;
        String source = GOLDEN_SOURCE.replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "\"py1\",\"py2\",\"ytd\",\"h\"")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":\"h\"")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null");
        String worksheet = render(calcs, source, null, null);
        String block = worksheet.substring(worksheet.indexOf("ACME"), worksheet.indexOf("  Qualifying:"));
        assertEquals("""
                ACME WIDGETS LLC — W-2 base
                  Current pay:   $25.50/hr × 40 hrs/wk × 52 (HOURLY) = $53,040.00 ÷ 12 = $4,420.00
                  YTD check:     $15,938.47 ÷ 1.4286 months (01/01–02/12/2026) = $11,156.71
                  Prior year:    2025 W-2 Box 1 wages $82,981.00 ÷ 12 = $6,915.08
                                 2024 W-2 Box 1 wages $69,183.96 ÷ 12 = $5,765.33
                """, block);
    }

    @Test
    void modelStatedAndNoneAreSaidPlainly() throws Exception {
        String stated = GOLDEN_SOURCE.replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":null")
                .replace("\"monthly\":null", "\"monthly\":8733.33")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null")
                .replace("\"urlaStatedMonthly\":9000", "\"urlaStatedMonthly\":null");
        String none = stated.replace("\"monthly\":8733.33", "\"monthly\":null")
                .replace("ACME WIDGETS LLC", "Side Gig LLC");
        String worksheet = render("", stated + "," + none, null, null);
        assertTrue(worksheet.contains("  Qualifying:    $8,733.33 (model-stated, not verified by a calculation)\n"), worksheet);
        assertTrue(worksheet.contains("Side Gig LLC — W-2 base\n  Qualifying:    not computed — no calculation or stated figure\n"),
                worksheet);
        assertFalse(worksheet.contains("Application:"), "no stated figure and no variance: no Application line");
    }

    @Test
    void aDanglingCalculationIdIsShownNotHidden() throws Exception {
        String source = GOLDEN_SOURCE.replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "\"ghost\"")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":null")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null");
        String worksheet = render("", source, "missing-total", null);
        assertTrue(worksheet.contains("  Calculation:   not computed: no calculation with id \"ghost\" was requested\n"), worksheet);
        assertTrue(worksheet.contains("Total qualifying monthly income: not computed: no calculation with id \"missing-total\" was requested\n"),
                worksheet);
    }

    @Test
    void anErroredTotalListedInASourcesCalcIdsIsShownNotHidden() throws Exception {
        String calcs = """
                {"id":"bad-total","name":"Total","method":"income.total_monthly.v1","inputs":{"amounts":[]}}
                """;
        String source = GOLDEN_SOURCE.replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "\"bad-total\"")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":null")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null");
        String worksheet = render(calcs, source, null, null);
        assertTrue(worksheet.contains("  Calculation:   not computed: amounts must be a non-empty array of numbers\n"),
                worksheet);
    }

    @Test
    void multipleBorrowersAreLabelledByIndexNeverByName() throws Exception {
        JsonNode model = om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"r",
                 "facts":[],"assumptions":[],"warnings":[],"recommendations":[],
                 "calculations":[%s],"missingItems":[],"citations":[],"confidence":0.9,
                 "domain":{"schemaVersion":"income-domain-v2",
                   "borrowers":[{"name":"Alicia Alvarez","sources":[%s]},
                                {"name":"Bo Bergstrom","sources":[%s]}],
                   "totalCalcId":null,"reconciliationCalcId":null,"opportunities":[],"gaps":[]}}
                """.formatted(RATE_CALC,
                source("rate", "null", "[\"rate\"]", "HIGH", "ACME WIDGETS LLC"),
                source(null, "5000", "[]", "MEDIUM", "Side Gig LLC")));
        ObjectNode enriched = executor.execute(model).enriched();
        enricher.enrich(enriched);
        String worksheet = renderer.render(enriched);
        assertTrue(worksheet.contains("Borrower 1 · ACME WIDGETS LLC"), worksheet);
        assertTrue(worksheet.contains("Borrower 2 · Side Gig LLC"), worksheet);
        assertFalse(worksheet.contains("Alicia Alvarez"), worksheet);
        assertFalse(worksheet.contains("Bo Bergstrom"), worksheet);
    }

    private static final String RATE_CALC = """
            {"id":"rate","name":"Current pay","method":"income.monthly_from_rate.v1",
             "inputs":{"rate":4670.69,"frequency":"BIWEEKLY"}}
            """;

    private static String source(String qualifyingCalcId, String monthly, String calcIds, String confidence,
            String employer) {
        return """
                {"type":"W-2 base","employer":"%s","factIds":[],"calcIds":%s,
                 "qualifyingCalcId":%s,"monthly":%s,"urlaStatedMonthly":null,"varianceCalcId":null,
                 "concerns":[],"confidence":"%s"}
                """.formatted(employer, calcIds, qualifyingCalcId == null ? "null" : "\"" + qualifyingCalcId + "\"",
                monthly, confidence);
    }

    @Test
    void aYtdDeltaIsCurrentPayAndShowsTheSubtraction() throws Exception {
        String calcs = """
                {"id":"d","name":"Current pay","method":"income.period_gross_from_ytd_delta.v1",
                 "inputs":{"ytdPrior":104050.46,"ytdCurrent":109767.76,"periodStart":"2026-08-16","periodEnd":"2026-08-29"}},
                {"id":"py","name":"2025 W-2 Box 1 wages","method":"income.monthly_from_annual.v1",
                 "inputs":{"annual":130712.62}}
                """;
        String source = GOLDEN_SOURCE.replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "\"py\",\"d\"")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":\"d\"")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null");
        String worksheet = render(calcs, source, null, null);
        assertTrue(worksheet.contains(
                "  Current pay:   $109,767.76 − $104,050.46 = $5,717.30 × 26 (BIWEEKLY, inferred from 14-day period)"
                        + " = $148,649.80 ÷ 12 = $12,387.48\n"
                        + "  Prior year:    2025 W-2 Box 1 wages $130,712.62 ÷ 12 = $10,892.72\n"
                        + "  Qualifying:    $12,387.48 (current pay)\n"), worksheet);
    }

    @Test
    void aYtdDeltaWithAGivenFrequencyIsNotLabelledInferred() throws Exception {
        String calcs = """
                {"id":"d","name":"Current pay","method":"income.period_gross_from_ytd_delta.v1",
                 "inputs":{"ytdPrior":104050.46,"ytdCurrent":109767.76,"periodStart":"2026-08-16","periodEnd":"2026-08-29",
                           "frequency":"BIWEEKLY"}}
                """;
        String source = GOLDEN_SOURCE.replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "\"d\"")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":\"d\"")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null");
        String worksheet = render(calcs, source, null, null);
        assertTrue(worksheet.contains(
                "  Current pay:   $109,767.76 − $104,050.46 = $5,717.30 × 26 (BIWEEKLY) = $148,649.80 ÷ 12 = $12,387.48\n"),
                worksheet);
    }

    @Test
    void aDuplicateEmploymentIsPrintedAsNotCountedAndTheAdjustedTotalIsUsed() throws Exception {
        String calcs = """
                {"id":"py","name":"2025 W-2 Box 1 wages","method":"income.monthly_from_annual.v1",
                 "inputs":{"annual":130712.62}},
                {"id":"d","name":"Current pay","method":"income.period_gross_from_ytd_delta.v1",
                 "inputs":{"ytdPrior":104050.46,"ytdCurrent":109767.76,"periodStart":"2026-08-16","periodEnd":"2026-08-29"}},
                {"id":"total","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["py","d"]}},
                {"id":"reconciliation","name":"Reconciliation","method":"income.variance.v1",
                 "inputs":{"computedRef":"total","stated":13000}}
                """;
        String agent = GOLDEN_SOURCE.replace("ACME WIDGETS LLC", "CHC Payroll Agent, Inc. (Agent for HealthOne of Denver, Inc.)")
                .replace("\"src1-monthly-rate\",\"src1-ytd-avg\",\"src1-py1-monthly\"", "\"py\"")
                .replace("\"qualifyingCalcId\":\"src1-monthly-rate\"", "\"qualifyingCalcId\":\"py\"")
                .replace("\"varianceCalcId\":\"src1-variance\"", "\"varianceCalcId\":null")
                .replace("\"urlaStatedMonthly\":9000", "\"urlaStatedMonthly\":null");
        String stubs = agent.replace("CHC Payroll Agent, Inc. (Agent for HealthOne of Denver, Inc.)",
                        "HealthOne of Denver, Inc. (S Denver Endoscopy Ctr)")
                .replace("[\"py\"]", "[\"d\"]").replace("\"qualifyingCalcId\":\"py\"", "\"qualifyingCalcId\":\"d\"");
        String worksheet = render(calcs, agent + "," + stubs, "total", "reconciliation");
        assertTrue(worksheet.contains(
                "CHC Payroll Agent, Inc. (Agent for HealthOne of Denver, Inc.) — W-2 base\n"
                        + "  Prior year:    2025 W-2 Box 1 wages $130,712.62 ÷ 12 = $10,892.72\n"
                        + "  Qualifying:    $10,892.72 (annual income)\n"
                        + "  Counted:       no — duplicate of HealthOne of Denver, Inc. (S Denver Endoscopy Ctr)\n"),
                worksheet);
        assertTrue(worksheet.contains("Total qualifying monthly income: $12,387.48 (duplicate employment counted"
                + " once; the requested total was $23,280.20)\n"
                + "Application total: stated $13,000.00 → variance −$612.52 (−4.71%), not material"
                + " (against the adjusted total)\n"), worksheet);
        assertFalse(worksheet.contains("+$10,280.20"), "the stale pre-adjustment variance is not printed");
    }

    @Test
    void aLegacyDomainRendersNothing() throws Exception {
        JsonNode legacy = om.readTree("""
                {"reportMarkdown":"r","calculations":[],"domain":{"borrowers":[]}}
                """);
        assertEquals("", renderer.render(legacy));
    }
}
