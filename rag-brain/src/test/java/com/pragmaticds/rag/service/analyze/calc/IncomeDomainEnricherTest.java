package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncomeDomainEnricherTest {

    private final ObjectMapper om = new ObjectMapper();
    private final CalculationExecutor executor = new CalculationExecutor(new IncomeCalcService(), om);
    private final IncomeDomainEnricher enricher = new IncomeDomainEnricher();

    /** Model envelope → real executor → enricher, exactly the production order. */
    private ObjectNode run(String calcs, String sourceJson, String totalCalcId, String report) throws Exception {
        return run(calcs, sourceJson, totalCalcId, report, "[]");
    }

    /** Overload for tests that need to populate {@code domain.opportunities}. */
    private ObjectNode run(String calcs, String sourceJson, String totalCalcId, String report,
            String opportunities) throws Exception {
        JsonNode model = om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"%s",
                 "facts":[],"assumptions":[],
                 "warnings":[{"id":"engine-income-1","severity":"LOW","statement":"model's own","confidence":0.5}],
                 "recommendations":[],"calculations":[%s],"missingItems":[],"citations":[],
                 "confidence":0.9,
                 "domain":{"schemaVersion":"income-domain-v2",
                   "borrowers":[{"name":"Borrower 1","sources":[%s]}],
                   "totalCalcId":%s,"reconciliationCalcId":null,"opportunities":%s,"gaps":[]}}
                """.formatted(report, calcs, sourceJson, totalCalcId == null ? "null" : "\"" + totalCalcId + "\"",
                opportunities));
        ObjectNode enriched = executor.execute(model).enriched();
        enricher.enrich(enriched);
        return enriched;
    }

    private static String source(String qualifyingCalcId, String monthly, String calcIds, String confidence) {
        return """
                {"type":"W-2 base","employer":"ACME WIDGETS LLC","factIds":[],"calcIds":%s,
                 "qualifyingCalcId":%s,"monthly":%s,"urlaStatedMonthly":null,"varianceCalcId":null,
                 "concerns":[],"confidence":"%s"}
                """.formatted(calcIds, qualifyingCalcId == null ? "null" : "\"" + qualifyingCalcId + "\"",
                monthly, confidence);
    }

    private static final String RATE = """
            {"id":"rate","name":"Current pay","method":"income.monthly_from_rate.v1",
             "inputs":{"rate":4670.69,"frequency":"BIWEEKLY"}}
            """;
    private static final String BROKEN_RATE = """
            {"id":"rate","name":"Current pay","method":"income.monthly_from_rate.v1",
             "inputs":{"rate":4670.69,"frequency":"FORTNIGHTLY"}}
            """;
    private static final String PERIOD = """
            {"id":"period","name":"Current pay","method":"income.monthly_from_period_gross.v1",
             "inputs":{"gross":4030.77,"periodStart":"2026-02-02","periodEnd":"2026-02-15"}}
            """;
    private static final String TOTAL = """
            {"id":"total","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["rate"]}}
            """;
    private static final String BROKEN_ORPHAN = """
            {"id":"orphan","name":"Orphan","method":"income.monthly_from_rate.v1",
             "inputs":{"rate":4670.69,"frequency":"FORTNIGHTLY"}}
            """;

    private static List<String> statements(JsonNode env) {
        List<String> out = new ArrayList<>();
        env.path("warnings").forEach(w -> out.add(w.path("severity").asText() + " " + w.path("statement").asText()));
        return out;
    }

    @Test
    void aComputedQualifyingCalculationIsTheCalculatorBasis() throws Exception {
        ObjectNode env = run(RATE + "," + TOTAL, source("rate", "null", "[\"rate\"]", "HIGH"), "total", "r");
        JsonNode computed = env.at("/domain/borrowers/0/sources/0/computed");
        assertEquals(0, new BigDecimal("10119.83").compareTo(computed.path("monthly").decimalValue()));
        assertEquals("income.monthly_from_rate.v1", computed.path("method").asText());
        assertEquals("BIWEEKLY", computed.path("frequency").asText());
        assertFalse(computed.path("frequencyInferred").asBoolean());
        assertEquals("CALCULATOR", computed.path("basis").asText());
        assertEquals(List.of("monthly", "method", "frequency", "frequencyInferred", "basis"),
                iterableToList(computed.fieldNames()));
        assertEquals("HIGH", env.at("/domain/borrowers/0/sources/0/confidence").asText());
        assertEquals(0, new BigDecimal("10119.83").compareTo(env.at("/domain/computedTotalMonthly").decimalValue()));
        assertEquals(1, env.path("warnings").size(), "a clean run adds no engine warnings: " + statements(env));
        assertEquals(0, new BigDecimal("0.9").compareTo(env.path("confidence").decimalValue()));
    }

    @Test
    void aFailedQualifyingCalculationFallsBackToTheModelsFigureAndSaysSo() throws Exception {
        ObjectNode env = run(BROKEN_RATE, source("rate", "8733.33", "[\"rate\"]", "HIGH"), null, "r");
        JsonNode computed = env.at("/domain/borrowers/0/sources/0/computed");
        assertEquals("MODEL_STATED", computed.path("basis").asText());
        assertEquals(0, new BigDecimal("8733.33").compareTo(computed.path("monthly").decimalValue()));
        assertTrue(computed.path("method").isNull());
        assertTrue(computed.path("frequency").isNull());
        assertEquals("MEDIUM", env.at("/domain/borrowers/0/sources/0/confidence").asText(),
                "a model-stated source is capped at MEDIUM");
        List<String> warnings = statements(env);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("HIGH") && w.contains("\"rate\" did not compute")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("MEDIUM") && w.contains("model-stated")), warnings.toString());
        assertEquals(0, new BigDecimal("0.70").compareTo(env.path("confidence").decimalValue()),
                "a failed referenced calculation lowers run confidence one step");
        assertTrue(env.at("/domain/computedTotalMonthly").isNull());
    }

    @Test
    void noCalculationAndNoFigureIsNoneCappedAtLow() throws Exception {
        ObjectNode env = run("", source(null, "null", "[]", "MEDIUM"), null, "r");
        assertEquals("NONE", env.at("/domain/borrowers/0/sources/0/computed/basis").asText());
        assertTrue(env.at("/domain/borrowers/0/sources/0/computed/monthly").isNull());
        assertEquals("LOW", env.at("/domain/borrowers/0/sources/0/confidence").asText());
        assertTrue(statements(env).stream().anyMatch(w -> w.startsWith("HIGH") && w.contains("no monthly figure")));
        assertEquals(0, new BigDecimal("0.9").compareTo(env.path("confidence").decimalValue()),
                "NONE alone is not a failed calculation; run confidence is untouched");
    }

    @Test
    void aDanglingIdDegradesInsteadOfFailing() throws Exception {
        ObjectNode env = run(RATE, source("never-requested", "null", "[\"rate\"]", "HIGH"), null, "r");
        assertEquals("NONE", env.at("/domain/borrowers/0/sources/0/computed/basis").asText());
        assertTrue(statements(env).stream().anyMatch(w -> w.contains("\"never-requested\"") && w.contains("never requested")));
        assertEquals(0, new BigDecimal("0.70").compareTo(env.path("confidence").decimalValue()));
    }

    @Test
    void anInferredFrequencyIsSurfaced() throws Exception {
        ObjectNode env = run(PERIOD, source("period", "null", "[\"period\"]", "HIGH"), null, "r");
        JsonNode computed = env.at("/domain/borrowers/0/sources/0/computed");
        assertEquals("BIWEEKLY", computed.path("frequency").asText());
        assertTrue(computed.path("frequencyInferred").asBoolean());
        assertEquals(0, new BigDecimal("8733.34").compareTo(computed.path("monthly").decimalValue()));
        assertTrue(statements(env).stream().anyMatch(w -> w.startsWith("LOW") && w.contains("inferred")));
    }

    @Test
    void anUnmatchedReportPlaceholderIsWarnedAndLowersConfidence() throws Exception {
        ObjectNode env = run(RATE, source("rate", "null", "[\"rate\"]", "HIGH"), null, "Income {{calc:ghost}}");
        assertTrue(statements(env).stream().anyMatch(w -> w.startsWith("MEDIUM") && w.contains("\"ghost\"")));
        assertEquals(0, new BigDecimal("0.70").compareTo(env.path("confidence").decimalValue()));
    }

    @Test
    void anErrorCalcCitedOnlyByAReportPlaceholderDegrades() throws Exception {
        ObjectNode env = run(BROKEN_ORPHAN, source(null, "null", "[]", "MEDIUM"), null,
                "Pay {{calc:orphan}}");
        List<String> warnings = statements(env);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("HIGH") && w.contains("\"orphan\" did not compute")),
                warnings.toString());
        assertEquals(0, new BigDecimal("0.70").compareTo(env.path("confidence").decimalValue()),
                "a calc failure cited only via a [calculation failed: ] report substitution still degrades");
    }

    @Test
    void anErrorCalcReferencedOnlyByAnOpportunityDegrades() throws Exception {
        ObjectNode env = run(BROKEN_ORPHAN, source(null, "null", "[]", "MEDIUM"), null, "r",
                "[{\"description\":\"Bonus\",\"estimatedMonthlyCalcId\":\"orphan\","
                        + "\"docsRequired\":[],\"basis\":null}]");
        List<String> warnings = statements(env);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("HIGH") && w.contains("\"orphan\" did not compute")),
                warnings.toString());
        assertEquals(0, new BigDecimal("0.70").compareTo(env.path("confidence").decimalValue()),
                "a calc failure referenced only from an opportunity still degrades");
    }

    @Test
    void engineWarningIdsNeverCollideWithTheModels() throws Exception {
        ObjectNode env = run(BROKEN_RATE, source("rate", "8733.33", "[\"rate\"]", "HIGH"), null, "r");
        List<String> ids = new ArrayList<>();
        env.path("warnings").forEach(w -> ids.add(w.path("id").asText()));
        assertEquals(ids.size(), ids.stream().distinct().count(), ids.toString());
        assertEquals("engine-income-1", ids.get(0), "the model's own warning keeps its id");
    }

    @Test
    void aModelSuppliedComputedBlockIsOverwritten() throws Exception {
        String lying = source("rate", "null", "[\"rate\"]", "HIGH").replace("\"confidence\":\"HIGH\"",
                "\"confidence\":\"HIGH\",\"computed\":{\"monthly\":99999,\"method\":null,\"frequency\":null,"
                        + "\"frequencyInferred\":false,\"basis\":\"CALCULATOR\"}");
        ObjectNode env = run(RATE, lying, null, "r");
        assertEquals(0, new BigDecimal("10119.83").compareTo(
                env.at("/domain/borrowers/0/sources/0/computed/monthly").decimalValue()));
    }

    @Test
    void aLegacyDomainIsLeftExactlyAsItWas() throws Exception {
        ObjectNode model = (ObjectNode) om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"r {{calc:ghost}}",
                 "facts":[],"assumptions":[],"warnings":[],"recommendations":[],"calculations":[],
                 "missingItems":[],"citations":[],"confidence":0.9,
                 "domain":{"borrowers":[{"name":"B","sources":[{"type":"W-2 base","calcIds":["x"]}]}]}}
                """);
        ObjectNode before = model.deepCopy();
        assertEquals(IncomeDomainEnricher.Summary.NOT_APPLICABLE, enricher.enrich(model));
        assertEquals(before, model);
    }

    @Test
    void summaryCountsBasesAndDegradations() throws Exception {
        JsonNode model = om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"{{calc:ghost}}",
                 "facts":[],"assumptions":[],"warnings":[],"recommendations":[],
                 "calculations":[%s,%s],"missingItems":[],"citations":[],"confidence":0.9,
                 "domain":{"schemaVersion":"income-domain-v2","borrowers":[{"name":"B","sources":[%s,%s]}],
                   "totalCalcId":null,"reconciliationCalcId":null,"opportunities":[],"gaps":[]}}
                """.formatted(PERIOD, BROKEN_RATE,
                source("period", "null", "[\"period\"]", "HIGH"),
                source("rate", "5000", "[\"rate\"]", "HIGH")));
        ObjectNode enriched = executor.execute(model).enriched();
        assertEquals(new IncomeDomainEnricher.Summary(1, 1, 0, 1, 1), enricher.enrich(enriched));
        assertEquals(0, new BigDecimal("0.70").compareTo(enriched.path("confidence").decimalValue()),
                "a failed ref (rate) AND an unmatched placeholder (ghost) lower confidence only once");
    }

    // --- duplicate-employment guard (loan Gough, 2026-09) ---

    /** Multi-borrower envelope: {@code borrowers} is the raw borrowers array body. */
    private ObjectNode runBorrowers(String facts, String calcs, String borrowers, String totalCalcId,
                                    String reconciliationCalcId) throws Exception {
        JsonNode model = om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"r",
                 "facts":[%s],"assumptions":[],"warnings":[],"recommendations":[],"calculations":[%s],
                 "missingItems":[],"citations":[],"confidence":0.9,
                 "domain":{"schemaVersion":"income-domain-v2","borrowers":[%s],
                   "totalCalcId":%s,"reconciliationCalcId":%s,"opportunities":[],"gaps":[]}}
                """.formatted(facts, calcs, borrowers, totalCalcId == null ? "null" : "\"" + totalCalcId + "\"",
                reconciliationCalcId == null ? "null" : "\"" + reconciliationCalcId + "\""));
        ObjectNode enriched = executor.execute(model).enriched();
        enricher.enrich(enriched);
        return enriched;
    }

    private static String w2(String employer, String qualifyingCalcId, String calcIds, String monthly, String factIds) {
        return """
                {"type":"W-2 base","employer":"%s","factIds":%s,"calcIds":%s,"qualifyingCalcId":%s,
                 "monthly":%s,"urlaStatedMonthly":null,"varianceCalcId":null,"concerns":[],"confidence":"HIGH"}
                """.formatted(employer, factIds, calcIds,
                qualifyingCalcId == null ? "null" : "\"" + qualifyingCalcId + "\"", monthly);
    }

    static final String GOUGH_CALCS = """
            {"id":"b1s1-py1-monthly","name":"2025 W-2 Box 1 wages","method":"income.monthly_from_annual.v1",
             "inputs":{"annual":130712.62}},
            {"id":"b1s2-monthly-delta","name":"Current pay","method":"income.period_gross_from_ytd_delta.v1",
             "inputs":{"ytdPrior":104050.46,"ytdCurrent":109767.76,"periodStart":"2026-08-16","periodEnd":"2026-08-29"}},
            {"id":"b2s1-monthly-rate","name":"Current pay","method":"income.monthly_from_rate.v1",
             "inputs":{"rate":4670.69,"frequency":"BIWEEKLY"}},
            {"id":"total","name":"Total","method":"income.total_monthly.v1",
             "inputs":{"amountRefs":["b1s1-py1-monthly","b1s2-monthly-delta","b2s1-monthly-rate"]}},
            {"id":"reconciliation","name":"Reconciliation","method":"income.variance.v1",
             "inputs":{"computedRef":"total","stated":23452.50}}
            """;

    static final String GOUGH_BORROWERS = """
            {"name":"Borrower 1","sources":[%s,%s]},
            {"name":"Borrower 2","sources":[%s]}
            """.formatted(
            w2("CHC Payroll Agent, Inc. (Agent for HealthOne of Denver, Inc.)", "b1s1-py1-monthly",
                    "[\"b1s1-py1-monthly\"]", "null", "[]"),
            w2("HealthOne of Denver, Inc. (S Denver Endoscopy Ctr)", "b1s2-monthly-delta",
                    "[\"b1s2-monthly-delta\"]", "null", "[]"),
            w2("OptionCare Enterprises, Inc.", "b2s1-monthly-rate", "[\"b2s1-monthly-rate\"]", "null", "[]"));

    private static JsonNode src(JsonNode env, int b, int s) {
        return env.path("domain").path("borrowers").get(b).path("sources").get(s);
    }

    @Test
    void goughPayrollAgentW2AndEmployerStubsAreOneEmployment() throws Exception {
        ObjectNode env = runBorrowers("", GOUGH_CALCS, GOUGH_BORROWERS, "total", "reconciliation");

        // Both are CALCULATOR; the stubs (dated 2026-08-29) are the more recent evidence.
        assertEquals(1, src(env, 0, 0).path("computed").path("duplicateOf").asInt(-1));
        assertEquals("CALCULATOR", src(env, 0, 0).path("computed").path("basis").asText());
        assertFalse(src(env, 0, 1).path("computed").has("duplicateOf"));
        assertFalse(src(env, 1, 0).path("computed").has("duplicateOf"), "the other borrower is untouched");

        // The model's total (33,400.03) counted the W-2 twice; replaced by 12,387.48 + 10,119.83.
        assertEquals(new BigDecimal("33400.03"), env.path("calculations").get(3).path("result").path("value").decimalValue());
        assertEquals(0, new BigDecimal("22507.31").compareTo(env.path("domain").path("computedTotalMonthly").decimalValue()));
        assertTrue(env.path("domain").path("totalAdjustedForDuplicates").asBoolean());

        List<String> warnings = statements(env);
        assertTrue(warnings.contains("HIGH borrowers[0].sources[0] (W-2 base): probably the same employment as"
                + " sources[1] (HealthOne of Denver, Inc. (S Denver Endoscopy Ctr)) — one employer is a payroll"
                + " agent for \"HealthOne of Denver, Inc.\", whose name is contained in the other employer's;"
                + " counted once."), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("HIGH The requested total \"total\" counts duplicate"
                + " employment") && w.contains("reconciliation")), warnings.toString());

        // Review 3: the application variance is re-run against the adjusted total, not left at +42%.
        JsonNode adjusted = env.path("domain").path("adjustedReconciliation");
        assertEquals("income.variance.v1", adjusted.path("method").asText());
        assertEquals(0, new BigDecimal("22507.31").compareTo(adjusted.path("inputs").path("computed").decimalValue()));
        assertEquals(0, new BigDecimal("23452.50").compareTo(adjusted.path("inputs").path("stated").decimalValue()));
        assertEquals("COMPUTED", adjusted.path("result").path("status").asText());
        assertEquals(0, new BigDecimal("-945.19").compareTo(adjusted.path("result").path("value").decimalValue()));
        assertEquals(0, new BigDecimal("-4.03").compareTo(adjusted.path("result").path("intermediates").path("variancePct").decimalValue()));
        assertFalse(adjusted.path("result").path("intermediates").path("material").asBoolean());
    }

    // --- review findings: false positives must keep both jobs counted ---

    private void assertBothCounted(String agentEmployer, String otherEmployer, String facts,
                                   String agentFacts, String otherFacts) throws Exception {
        String calcs = RATE + "," + PERIOD + """
                ,{"id":"total","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["rate","period"]}}
                """;
        String borrowers = "{\"name\":\"B\",\"sources\":[%s,%s]}".formatted(
                w2(agentEmployer, "rate", "[\"rate\"]", "null", agentFacts),
                w2(otherEmployer, "period", "[\"period\"]", "null", otherFacts));
        ObjectNode env = runBorrowers(facts, calcs, borrowers, "total", null);
        assertFalse(src(env, 0, 0).path("computed").has("duplicateOf"), statements(env).toString());
        assertFalse(src(env, 0, 1).path("computed").has("duplicateOf"), statements(env).toString());
        assertEquals(0, new BigDecimal("18853.17").compareTo(env.path("domain").path("computedTotalMonthly").decimalValue()));
        assertFalse(env.path("domain").has("totalAdjustedForDuplicates"));
    }

    @Test
    void anAgentsPrincipalSharingOnlyACityIsNotADuplicate() throws Exception {
        assertBothCounted("CHC Payroll Agent, Inc. (Agent for HealthOne of Denver, Inc.)",
                "Denver Health Medical Center", "", "[]", "[]");
    }

    @Test
    void anAgentsPrincipalSharingOnlyAStateIsNotADuplicate() throws Exception {
        assertBothCounted("ADP (Agent for Children's Hospital Colorado)", "University of Colorado Health", "", "[]", "[]");
        assertBothCounted("Paychex (Agent for Mountain View Medical Group)", "Mountain States Orthopedics", "", "[]", "[]");
        assertBothCounted("Paychex Business Solutions", "Business Systems Partners", "", "[]", "[]");
        assertBothCounted("CHC Community Health Center", "Community Health Partners", "", "[]", "[]");
    }

    @Test
    void aPeoOrAgentEinIsNotADuplicateSignal() throws Exception {
        String facts = """
                {"id":"f1","key":"employerEin","statement":"Employer EIN","value":"84-1234567","citationIds":["c1"],"confidence":0.9},
                {"id":"f2","key":"ein","statement":"EIN","value":"841234567","citationIds":["c1"],"confidence":0.9}
                """;
        assertBothCounted("Columbia Payroll Services", "Mountain Clinic", facts, "[\"f1\"]", "[\"f2\"]");
        assertBothCounted("Justworks Employment Group LLC", "Summit Dental", facts, "[\"f1\"]", "[\"f2\"]");
    }

    @Test
    void aTotalReplacementSumsOnlyTheModelsOwnRefs() throws Exception {
        // Borrower 2's source is deliberately left out of the model's total, and borrower 1's kept
        // source is counted through its YTD average, not its qualifying delta.
        String calcs = GOUGH_CALCS.replace("[\"b1s1-py1-monthly\",\"b1s2-monthly-delta\",\"b2s1-monthly-rate\"]",
                "[\"b1s1-py1-monthly\",\"b1s2-ytd-avg\"]").replace("{\"id\":\"total\"", """
                {"id":"b1s2-ytd-avg","name":"YTD average","method":"income.ytd_monthly_average.v1",
                  "inputs":{"ytdAmount":109767.76,"periodStart":"2026-01-01","periodEnd":"2026-08-29"}},
                {"id":"total\"""");
        String borrowers = GOUGH_BORROWERS.replace("\"calcIds\":[\"b1s2-monthly-delta\"]",
                "\"calcIds\":[\"b1s2-monthly-delta\",\"b1s2-ytd-avg\"]");
        ObjectNode env = runBorrowers("", calcs, borrowers, "total", null);
        JsonNode ytd = env.path("calculations").get(3);
        assertEquals("b1s2-ytd-avg", ytd.path("id").asText());
        assertEquals(0, ytd.path("result").path("value").decimalValue()
                .compareTo(env.path("domain").path("computedTotalMonthly").decimalValue()),
                env.path("domain").toString());
        assertTrue(env.path("domain").path("totalAdjustedForDuplicates").asBoolean());
        assertFalse(env.path("domain").has("adjustedReconciliation"), "no reconciliation was requested");
    }

    @Test
    void aTotalThatCountedOnlyTheDuplicateCountsTheKeptSourceInstead() throws Exception {
        // The model's total used the agent W-2 (dropped) but not the stubs source (kept): the
        // employment must stay in the total through the kept source's qualifying figure.
        String calcs = GOUGH_CALCS.replace("[\"b1s1-py1-monthly\",\"b1s2-monthly-delta\",\"b2s1-monthly-rate\"]",
                "[\"b1s1-py1-monthly\",\"b2s1-monthly-rate\"]");
        ObjectNode env = runBorrowers("", calcs, GOUGH_BORROWERS, "total", "reconciliation");
        assertEquals(1, src(env, 0, 0).path("computed").path("duplicateOf").asInt(-1));
        assertTrue(env.path("domain").path("totalAdjustedForDuplicates").asBoolean());
        assertEquals(0, new BigDecimal("22507.31").compareTo(env.path("domain").path("computedTotalMonthly").decimalValue()),
                env.path("domain").toString());
        assertEquals(0, new BigDecimal("22507.31").compareTo(
                env.path("domain").path("adjustedReconciliation").path("inputs").path("computed").decimalValue()));
    }

    @Test
    void aSingleTokenOrGeographicNameRunIsNotADuplicate() throws Exception {
        assertBothCounted("University of Colorado", "University of Colorado Health", "", "[]", "[]");
        assertBothCounted("Target", "Target Hospitality Corp", "", "[]", "[]");
        assertBothCounted("ADP (Agent for Denver)", "Denver Health Medical Center", "", "[]", "[]");
    }

    @Test
    void aTotalReplacementKeepsLiteralAmounts() throws Exception {
        String calcs = GOUGH_CALCS.replace("\"inputs\":{\"amountRefs\":[\"b1s1-py1-monthly\"",
                "\"inputs\":{\"amounts\":[100.10],\"amountRefs\":[\"b1s1-py1-monthly\"");
        ObjectNode env = runBorrowers("", calcs, GOUGH_BORROWERS, "total", null);
        assertEquals(0, new BigDecimal("22607.41").compareTo(env.path("domain").path("computedTotalMonthly").decimalValue()));
    }

    @Test
    void aDuplicateChainPointsAtTheFinalKeptSource() throws Exception {
        // 0 (model-stated) loses to 1 (calculator); 1 (undated) then loses to 2 (dated).
        String calcs = RATE + "," + PERIOD;
        String borrowers = "{\"name\":\"B\",\"sources\":[%s,%s,%s]}".formatted(
                w2("Acme Widgets", null, "[]", "9000", "[]"),
                w2("ACME WIDGETS LLC", "rate", "[\"rate\"]", "null", "[]"),
                w2("Acme Widgets Inc", "period", "[\"period\"]", "null", "[]"));
        ObjectNode env = runBorrowers("", calcs, borrowers, null, null);
        assertEquals(2, src(env, 0, 0).path("computed").path("duplicateOf").asInt(-1));
        assertEquals(2, src(env, 0, 1).path("computed").path("duplicateOf").asInt(-1));
        assertFalse(src(env, 0, 2).path("computed").has("duplicateOf"));
        assertTrue(statements(env).stream().noneMatch(w -> w.contains("same employment as sources[1]")),
                statements(env).toString());
    }

    @Test
    void aModelStatedSourceOutranksNoneBeforeRecency() throws Exception {
        String facts = """
                {"id":"d","key":"payDate","statement":"Pay date","value":"2026-09-04","citationIds":["c1"],"confidence":0.9}
                """;
        String borrowers = "{\"name\":\"B\",\"sources\":[%s,%s]}".formatted(
                w2("Acme Widgets", null, "[]", "null", "[\"d\"]"),
                w2("ACME WIDGETS LLC", null, "[]", "9000", "[]"));
        ObjectNode env = runBorrowers(facts, "", borrowers, null, null);
        assertEquals(1, src(env, 0, 0).path("computed").path("duplicateOf").asInt(-1));
        assertFalse(src(env, 0, 1).path("computed").has("duplicateOf"));
    }

    @Test
    void modelSuppliedEngineFieldsAreStripped() throws Exception {
        JsonNode model = om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"r","facts":[],"assumptions":[],
                 "warnings":[],"recommendations":[],"calculations":[%s],"missingItems":[],"citations":[],"confidence":0.9,
                 "domain":{"schemaVersion":"income-domain-v2","borrowers":[{"name":"B","sources":[%s]}],
                   "totalCalcId":null,"reconciliationCalcId":null,"opportunities":[],"gaps":[],
                   "totalAdjustedForDuplicates":true,"adjustedReconciliation":{"method":"x"}}}
                """.formatted(RATE, source("rate", "null", "[\"rate\"]", "HIGH")));
        ObjectNode enriched = executor.execute(model).enriched();
        enricher.enrich(enriched);
        assertFalse(enriched.path("domain").has("totalAdjustedForDuplicates"));
        assertFalse(enriched.path("domain").has("adjustedReconciliation"));
    }

    @Test
    void aContainedEmployerNameIsADuplicateAndTheCalculatorSourceIsKept() throws Exception {
        String calcs = RATE;
        String borrowers = "{\"name\":\"B\",\"sources\":[%s,%s]}".formatted(
                w2("ACME WIDGETS LLC of Denver", null, "[]", "9000", "[]"),
                w2("Acme Widgets, Inc.", "rate", "[\"rate\"]", "null", "[]"));
        ObjectNode env = runBorrowers("", calcs, borrowers, null, null);
        assertEquals(1, src(env, 0, 0).path("computed").path("duplicateOf").asInt(-1));
        assertEquals("MODEL_STATED", src(env, 0, 0).path("computed").path("basis").asText());
        assertFalse(src(env, 0, 1).path("computed").has("duplicateOf"));
        assertTrue(env.path("domain").path("computedTotalMonthly").isNull());
        assertFalse(env.path("domain").has("totalAdjustedForDuplicates"));
        assertTrue(statements(env).stream().anyMatch(w -> w.startsWith("HIGH borrowers[0].sources[0] (W-2 base):"
                + " probably the same employment as sources[1] (Acme Widgets, Inc.) — the employer names match")));
    }

    @Test
    void differentEmployersAreNotDuplicates() throws Exception {
        String calcs = RATE + "," + PERIOD + """
                ,{"id":"total","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["rate","period"]}}
                """;
        String borrowers = "{\"name\":\"B\",\"sources\":[%s,%s]}".formatted(
                w2("ACME WIDGETS LLC", "rate", "[\"rate\"]", "null", "[]"),
                w2("Globex Corporation", "period", "[\"period\"]", "null", "[]"));
        ObjectNode env = runBorrowers("", calcs, borrowers, "total", null);
        assertFalse(src(env, 0, 0).path("computed").has("duplicateOf"));
        assertFalse(src(env, 0, 1).path("computed").has("duplicateOf"));
        assertEquals(0, new BigDecimal("18853.17").compareTo(env.path("domain").path("computedTotalMonthly").decimalValue()));
        assertFalse(env.path("domain").has("totalAdjustedForDuplicates"));
        assertTrue(statements(env).stream().noneMatch(w -> w.contains("same employment")));
    }

    @Test
    void whenBothAreCalculatorTheMoreRecentEvidenceIsKept() throws Exception {
        // PERIOD is dated 2026-02-15; RATE carries no date. The dated source is kept wherever it sits.
        String calcs = PERIOD + "," + RATE;
        String borrowers = "{\"name\":\"B\",\"sources\":[%s,%s]}".formatted(
                w2("Acme Widgets", "period", "[\"period\"]", "null", "[]"),
                w2("ACME WIDGETS LLC", "rate", "[\"rate\"]", "null", "[]"));
        ObjectNode env = runBorrowers("", calcs, borrowers, null, null);
        assertFalse(src(env, 0, 0).path("computed").has("duplicateOf"));
        assertEquals(0, src(env, 0, 1).path("computed").path("duplicateOf").asInt(-1));
    }

    @Test
    void aSharedEinBetweenNonAgentEmployersIsADuplicate() throws Exception {
        // One employer's legal name and its trade name, both printing the employer's own EIN.
        String facts = """
                {"id":"f1","key":"employerEin","statement":"Employer EIN","value":"84-1234567","citationIds":["c1"],"confidence":0.9},
                {"id":"f2","key":"ein","statement":"EIN","value":"841234567","citationIds":["c1"],"confidence":0.9}
                """;
        String calcs = RATE + "," + PERIOD;
        String borrowers = "{\"name\":\"B\",\"sources\":[%s,%s]}".formatted(
                w2("Front Range Dermatology PC", "rate", "[\"rate\"]", "null", "[\"f1\"]"),
                w2("Skin Wellness Institute", "period", "[\"period\"]", "null", "[\"f2\"]"));
        ObjectNode env = runBorrowers(facts, calcs, borrowers, null, null);
        assertEquals(1, src(env, 0, 0).path("computed").path("duplicateOf").asInt(-1));
        assertTrue(statements(env).stream().anyMatch(w -> w.contains("share an EIN")), statements(env).toString());
    }

    @Test
    void aTotalThatAlreadyExcludesTheDuplicateIsKept() throws Exception {
        String calcs = GOUGH_CALCS.replace("[\"b1s1-py1-monthly\",\"b1s2-monthly-delta\",\"b2s1-monthly-rate\"]",
                "[\"b1s2-monthly-delta\",\"b2s1-monthly-rate\"]");
        ObjectNode env = runBorrowers("", calcs, GOUGH_BORROWERS, "total", null);
        assertEquals(1, src(env, 0, 0).path("computed").path("duplicateOf").asInt(-1));
        assertEquals(0, new BigDecimal("22507.31").compareTo(env.path("domain").path("computedTotalMonthly").decimalValue()));
        assertFalse(env.path("domain").has("totalAdjustedForDuplicates"));
    }

    private static List<String> iterableToList(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
