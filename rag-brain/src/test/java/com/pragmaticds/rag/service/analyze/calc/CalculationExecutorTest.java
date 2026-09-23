package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.service.analyze.EnvelopeValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CalculationExecutorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final CalculationExecutor executor =
            new CalculationExecutor(new IncomeCalcService(), om);
    private final EnvelopeValidator validator = new EnvelopeValidator();

    private JsonNode envelope(String calcs, String report) throws Exception {
        return om.readTree("""
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"%s",
                 "facts":[],"assumptions":[],"warnings":[],"recommendations":[],
                 "calculations":[%s],"missingItems":[],"citations":[],"confidence":0.9}
                """.formatted(report, calcs));
    }

    @Test
    void computesResultIntoEnvelope() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":90000}}
                """, "r");
        CalculationExecutor.Execution ex = executor.execute(env);
        JsonNode result = ex.enriched().get("calculations").get(0).get("result");
        assertEquals("COMPUTED", result.get("status").asText());
        assertEquals(0, result.get("value").decimalValue().compareTo(new java.math.BigDecimal("7500.00")));
        assertEquals("HALF_UP,2dp", result.get("rounding").asText());
        assertFalse(result.has("error"), "COMPUTED must not carry an error field");
    }

    @Test
    void overwritesModelSuppliedResult() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1",
                 "inputs":{"annual":90000},
                 "result":{"status":"COMPUTED","value":9999999}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(0).get("result");
        assertEquals(0, result.get("value").decimalValue().compareTo(new java.math.BigDecimal("7500.00")));
    }

    @Test
    void unsupportedMethodBecomesErrorResult() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"X","method":"assets.reserves.v1","inputs":{}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(0).get("result");
        assertEquals("ERROR", result.get("status").asText());
        assertTrue(result.get("value").isNull(), "ERROR must carry an explicit null value");
        assertTrue(result.get("error").asText().contains("unsupported method"));
        assertFalse(result.has("intermediates"), "ERROR must not carry intermediates");
        assertFalse(result.has("rounding"), "ERROR must not carry rounding");
    }

    @Test
    void substitutesPlaceholdersInReport() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":90000}},
                {"id":"c2","name":"Bad","method":"income.monthly_from_annual.v1","inputs":{}}
                """, "Base pay is ${{calc:c1}}/mo; bad is {{calc:c2}}; unknown {{calc:zz}} stays.");
        String report = executor.execute(env).enriched().get("reportMarkdown").asText();
        assertEquals("Base pay is $7500.00/mo; bad is [calculation failed: c2]; unknown {{calc:zz}} stays.", report);
    }

    @Test
    void auditListsEveryRequest() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":90000}},
                {"id":"c2","name":"Bad","method":"nope.v1","inputs":{}}
                """, "r");
        CalculationExecutor.Execution ex = executor.execute(env);
        assertEquals(2, ex.audit().size());
        assertEquals("c1", ex.audit().get(0).get("id"));
        assertEquals("COMPUTED", ex.audit().get(0).get("status"));
        assertEquals("ERROR", ex.audit().get(1).get("status"));
        assertNotNull(ex.audit().get(1).get("error"));
    }

    @Test
    void envelopeWithoutCalculationsPassesThrough() throws Exception {
        JsonNode env = envelope("", "r");
        CalculationExecutor.Execution ex = executor.execute(env);
        assertEquals(0, ex.audit().size());
        assertEquals("r", ex.enriched().get("reportMarkdown").asText());
    }

    @Test
    void originalEnvelopeIsNotMutated() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":90000}}
                """, "r");
        executor.execute(env);
        assertFalse(env.get("calculations").get(0).has("result"));
    }

    /**
     * The enriched envelope is what the suite receives — it must still satisfy the v2
     * schema. Covers all five IncomeCalcService methods (each has a distinct result
     * shape/intermediates) plus one ERROR, so a schema drift in any branch is caught here.
     */
    @Test
    void enrichedEnvelopeStillValidatesAgainstSchema() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":90000}},
                {"id":"c2","name":"Bad","method":"nope.v1","inputs":{}},
                {"id":"c3","name":"Rate","method":"income.monthly_from_rate.v1",
                 "inputs":{"rate":25.5,"frequency":"HOURLY","hoursPerWeek":40}},
                {"id":"c4","name":"Ytd","method":"income.ytd_monthly_average.v1",
                 "inputs":{"ytdAmount":12526.50,"periodStart":"2026-01-01","periodEnd":"2026-06-30"}},
                {"id":"c5","name":"Total","method":"income.total_monthly.v1",
                 "inputs":{"amounts":[4420.00,2106.50,500]}},
                {"id":"c6","name":"Variance","method":"income.variance.v1",
                 "inputs":{"computed":11000,"stated":10000}}
                """, "Total {{calc:c1}}");
        JsonNode enriched = executor.execute(env).enriched();
        assertEquals(List.of(), validator.validate(enriched));
    }

    @Test
    void duplicateIdsUseTheLastResult() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"First","method":"income.monthly_from_annual.v1","inputs":{"annual":12000}},
                {"id":"c1","name":"Second","method":"income.monthly_from_annual.v1","inputs":{"annual":24000}}
                """, "Value is {{calc:c1}}");
        CalculationExecutor.Execution ex = executor.execute(env);
        // 12000/12 = 1000.00, 24000/12 = 2000.00 -> the second (last) request wins in the report.
        assertEquals("Value is 2000.00", ex.enriched().get("reportMarkdown").asText());
        // Both requests still ran and both are still audited, even though the id collided.
        assertEquals(2, ex.audit().size());
        assertEquals("c1", ex.audit().get(0).get("id"));
        assertEquals("c1", ex.audit().get(1).get("id"));
        assertEquals(0, ((java.math.BigDecimal) ex.audit().get(1).get("value"))
                .compareTo(new java.math.BigDecimal("2000.00")));
    }

    @Test
    void intermediatesRenderAsNativeNumbersWithoutExponentNotation() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":90000}},
                {"id":"c2","name":"Variance","method":"income.variance.v1","inputs":{"computed":11000,"stated":10000}}
                """, "r");
        JsonNode enriched = executor.execute(env).enriched();
        JsonNode annualIntermediates = enriched.get("calculations").get(0).get("result").get("intermediates");
        JsonNode varianceIntermediates = enriched.get("calculations").get(1).get("result").get("intermediates");
        assertTrue(annualIntermediates.get("annual").isNumber());
        assertTrue(varianceIntermediates.get("thresholdPct").isNumber());

        String json = om.writeValueAsString(enriched);
        assertTrue(json.contains("\"annual\":90000"), json);
        assertTrue(json.contains("\"thresholdPct\":10"), json);
        assertFalse(json.contains("E+"), "intermediates must not render in scientific notation: " + json);
    }

    @Test
    void placeholderSubstitutionIsLiteralNotRegex() throws Exception {
        // A computed value or an id must never be interpreted as a regex replacement
        // (e.g. a "$" in the surrounding text or value would break naive appendReplacement).
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":90000}}
                """, "Cost: $100 and {{calc:c1}} \\\\ done");
        String report = executor.execute(env).enriched().get("reportMarkdown").asText();
        assertTrue(report.contains("$100"));
        assertTrue(report.contains("7500.00"));
    }

    // --- Task 8b: calculation chaining by reference ---------------------------------

    @Test
    void totalWithOnlyAmountRefsResolvesTwoEarlierComputedCalcs() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":12000}},
                {"id":"c2","name":"Bonus","method":"income.monthly_from_annual.v1","inputs":{"annual":24000}},
                {"id":"c3","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["c1","c2"]}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(2).get("result");
        assertEquals("COMPUTED", result.get("status").asText());
        assertEquals(0, result.get("value").decimalValue().compareTo(new java.math.BigDecimal("3000.00")));
    }

    @Test
    void totalWithBothLiteralAmountsAndAmountRefsSumsAll() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":12000}},
                {"id":"c2","name":"Total","method":"income.total_monthly.v1",
                 "inputs":{"amounts":[200.50],"amountRefs":["c1"]}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(1).get("result");
        assertEquals("COMPUTED", result.get("status").asText());
        assertEquals(0, result.get("value").decimalValue().compareTo(new java.math.BigDecimal("1200.50")));
    }

    @Test
    void varianceWithComputedRefResolvesAndProducesVarianceAndMateriality() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":120000}},
                {"id":"c2","name":"Variance","method":"income.variance.v1","inputs":{"computedRef":"c1","stated":8000}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(1).get("result");
        assertEquals("COMPUTED", result.get("status").asText());
        // 120000/12 = 10000.00 computed vs 8000 stated -> variance 2000.00, pct 25.00%, material.
        assertEquals(0, result.get("value").decimalValue().compareTo(new java.math.BigDecimal("2000.00")));
        JsonNode mid = result.get("intermediates");
        assertEquals(0, mid.get("variancePct").decimalValue().compareTo(new java.math.BigDecimal("25.00")));
        assertTrue(mid.get("material").asBoolean());
    }

    @Test
    void refToFailedCalculationFailsTheDependentCalculationNamingTheRef() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Bad","method":"nope.v1","inputs":{}},
                {"id":"c2","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["c1"]}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(1).get("result");
        assertEquals("ERROR", result.get("status").asText());
        assertTrue(result.get("value").isNull());
        String error = result.get("error").asText();
        assertTrue(error.contains("\"c1\""), error);
        assertTrue(error.contains("did not compute"), error);
    }

    @Test
    void refToUnknownIdFailsNamingTheId() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["zzz"]}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(0).get("result");
        assertEquals("ERROR", result.get("status").asText());
        String error = result.get("error").asText();
        assertTrue(error.contains("\"zzz\""), error);
        assertTrue(error.contains("unknown"), error);
    }

    @Test
    void forwardReferenceFails() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["c2"]}},
                {"id":"c2","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":12000}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(0).get("result");
        assertEquals("ERROR", result.get("status").asText());
        assertTrue(result.get("error").asText().contains("\"c2\""), result.get("error").asText());
    }

    @Test
    void selfReferenceFails() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["c1"]}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(0).get("result");
        assertEquals("ERROR", result.get("status").asText());
        assertTrue(result.get("error").asText().contains("\"c1\""), result.get("error").asText());
    }

    @Test
    void bothComputedAndComputedRefPresentFails() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":12000}},
                {"id":"c2","name":"Variance","method":"income.variance.v1",
                 "inputs":{"computed":500,"computedRef":"c1","stated":1000}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(1).get("result");
        assertEquals("ERROR", result.get("status").asText());
        String error = result.get("error").asText();
        assertTrue(error.contains("computed"), error);
        assertTrue(error.contains("computedRef"), error);
    }

    @Test
    void refToDuplicatedIdResolvesToTheEarlierOccurrence() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"First","method":"income.monthly_from_annual.v1","inputs":{"annual":12000}},
                {"id":"c1","name":"Second","method":"income.monthly_from_annual.v1","inputs":{"annual":24000}},
                {"id":"c2","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["c1"]}}
                """, "r");
        JsonNode result = executor.execute(env).enriched().get("calculations").get(2).get("result");
        assertEquals("COMPUTED", result.get("status").asText());
        // First "c1" (12000/12=1000.00) wins the ref, not the second (24000/12=2000.00) — refs
        // are backward-only, and the earlier occurrence is the only choice consistent with that.
        assertEquals(0, result.get("value").decimalValue().compareTo(new java.math.BigDecimal("1000.00")));
    }

    @Test
    void enrichedEnvelopeWithChainedCalcsStillValidatesAgainstSchema() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Hourly","method":"income.monthly_from_rate.v1",
                 "inputs":{"rate":25,"frequency":"HOURLY","hoursPerWeek":40}},
                {"id":"c2","name":"Ytd","method":"income.ytd_monthly_average.v1",
                 "inputs":{"ytdAmount":12526.50,"periodStart":"2026-01-01","periodEnd":"2026-06-30"}},
                {"id":"c3","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["c1","c2"]}},
                {"id":"c4","name":"Variance","method":"income.variance.v1","inputs":{"computedRef":"c3","stated":5000}}
                """, "Total {{calc:c3}}, variance {{calc:c4}}");
        JsonNode enriched = executor.execute(env).enriched();
        assertEquals(List.of(), validator.validate(enriched));
    }

    @Test
    void auditRowForChainedCalcRecordsTheResolvedInputs() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Base","method":"income.monthly_from_annual.v1","inputs":{"annual":12000}},
                {"id":"c2","name":"Total","method":"income.total_monthly.v1","inputs":{"amountRefs":["c1"]}}
                """, "r");
        CalculationExecutor.Execution ex = executor.execute(env);
        JsonNode auditInputs = (JsonNode) ex.audit().get(1).get("inputs");
        // Both the followed ref and the value it resolved to are visible in the audit row.
        assertEquals("c1", auditInputs.get("amountRefs").get(0).asText());
        assertEquals(0, auditInputs.get("amounts").get(0).decimalValue()
                .compareTo(new java.math.BigDecimal("1000.00")));
    }

    /**
     * This is the scenario that motivated Task 8b: a paystub's hourly rate and a
     * separate YTD bonus figure are each computed independently, chained into a
     * grand monthly total, and that total is reconciled against the borrower's
     * stated URLA income — none of these numbers were literal to begin with.
     */
    @Test
    void chainedHourlyAndYtdSourcesIntoTotalAndVarianceMatchesRealScenario() throws Exception {
        JsonNode env = envelope("""
                {"id":"c1","name":"Hourly wages","method":"income.monthly_from_rate.v1",
                 "inputs":{"rate":25,"frequency":"HOURLY","hoursPerWeek":40}},
                {"id":"c2","name":"YTD bonus average","method":"income.ytd_monthly_average.v1",
                 "inputs":{"ytdAmount":12526.50,"periodStart":"2026-01-01","periodEnd":"2026-06-30"}},
                {"id":"c3","name":"Total monthly income","method":"income.total_monthly.v1",
                 "inputs":{"amountRefs":["c1","c2"]}},
                {"id":"c4","name":"URLA variance","method":"income.variance.v1",
                 "inputs":{"computedRef":"c3","stated":5000}}
                """, "r");
        CalculationExecutor.Execution ex = executor.execute(env);
        JsonNode calcs = ex.enriched().get("calculations");

        // 25/hr * 40hrs/wk * 52wk = 52000.00/yr -> 4333.33/mo.
        assertEquals(0, calcs.get(0).get("result").get("value").decimalValue()
                .compareTo(new java.math.BigDecimal("4333.33")));
        // 12526.50 over exactly 6.0000 months -> 2087.75/mo.
        assertEquals(0, calcs.get(1).get("result").get("value").decimalValue()
                .compareTo(new java.math.BigDecimal("2087.75")));

        JsonNode totalResult = calcs.get(2).get("result");
        assertEquals("COMPUTED", totalResult.get("status").asText());
        assertEquals(0, totalResult.get("value").decimalValue().compareTo(new java.math.BigDecimal("6421.08")));

        JsonNode varianceResult = calcs.get(3).get("result");
        assertEquals("COMPUTED", varianceResult.get("status").asText());
        // 6421.08 computed vs 5000 stated -> variance 1421.08, pct 28.42%, material (> 10%).
        assertEquals(0, varianceResult.get("value").decimalValue().compareTo(new java.math.BigDecimal("1421.08")));
        JsonNode mid = varianceResult.get("intermediates");
        assertEquals(0, mid.get("variancePct").decimalValue().compareTo(new java.math.BigDecimal("28.42")));
        assertTrue(mid.get("material").asBoolean());
    }
}
