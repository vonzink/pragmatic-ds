package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an executed income envelope's domain into something a reader can trust at a glance
 * (spec 2026-09-14 A4/A5): every source states WHERE its monthly figure came from.
 *
 * <p>Best effort by owner decision. Nothing here fails a run. A qualifying calculation that
 * computed makes the source {@code CALCULATOR}; otherwise a model-typed {@code monthly} is carried
 * through as {@code MODEL_STATED} and the source's confidence is capped at MEDIUM; otherwise the
 * source is {@code NONE}, capped at LOW. A referenced calculation that errored or was never
 * requested — referenced from a source's {@code qualifyingCalcId}/{@code calcIds}/{@code
 * varianceCalcId}, the domain's {@code totalCalcId}/{@code reconciliationCalcId}, an opportunity's
 * {@code estimatedMonthlyCalcId}, or an executor-substituted {@code [calculation failed: <id>]}
 * citation left in {@code reportMarkdown} — and a {@code {{calc:}}} placeholder the executor left
 * literal, each add a warning and lower the envelope's confidence one step — once per run, however
 * many there are.
 *
 * <p>Confidence is only ever lowered here, never raised. Warning statements name JSON locations,
 * source types and calculation ids — never a borrower value.
 *
 * <p>Applies only to a domain declaring {@link #SCHEMA_VERSION}; any other envelope is returned
 * untouched, which is what keeps runs of an earlier promoted release byte-identical.
 */
@Service
public class IncomeDomainEnricher {

    public static final String SCHEMA_VERSION = "income-domain-v2";

    static final BigDecimal CONFIDENCE_STEP = new BigDecimal("0.2");

    private static final Pattern LEFTOVER_PLACEHOLDER = Pattern.compile("\\{\\{calc:([A-Za-z0-9_-]{1,64})}}");
    private static final Pattern REPORT_FAILURE_CITATION =
            Pattern.compile("\\[calculation failed: ([A-Za-z0-9_-]{1,64})]");
    private static final List<String> LEVELS = List.of("LOW", "MEDIUM", "HIGH");

    /** Counts only — safe to log. */
    public record Summary(int calculator, int modelStated, int none, int failedReferences,
                          int unmatchedPlaceholders) {
        public static final Summary NOT_APPLICABLE = new Summary(0, 0, 0, 0, 0);
    }

    public static boolean appliesTo(JsonNode envelope) {
        return envelope != null
                && SCHEMA_VERSION.equals(envelope.path("domain").path("schemaVersion").asText(""));
    }

    public Summary enrich(ObjectNode envelope) {
        if (!appliesTo(envelope)) {
            return Summary.NOT_APPLICABLE;
        }
        ObjectNode domain = (ObjectNode) envelope.get("domain");
        // First-wins, matching CalculationExecutor's ref resolution for duplicate ids.
        Map<String, JsonNode> calcById = new LinkedHashMap<>();
        for (JsonNode calc : envelope.path("calculations")) {
            calcById.putIfAbsent(calc.path("id").asText(), calc);
        }
        Warnings warnings = new Warnings(envelope);
        Set<String> failed = new LinkedHashSet<>();
        int calculator = 0;
        int modelStated = 0;
        int none = 0;

        JsonNode borrowers = domain.path("borrowers");
        for (int b = 0; b < borrowers.size(); b++) {
            JsonNode sources = borrowers.get(b).path("sources");
            for (int s = 0; s < sources.size(); s++) {
                ObjectNode source = (ObjectNode) sources.get(s);
                String where = "borrowers[" + b + "].sources[" + s + "] ("
                        + source.path("type").asText("source") + ")";
                collectFailure(calcById, failed, source.get("qualifyingCalcId"));
                for (JsonNode id : source.path("calcIds")) {
                    collectFailure(calcById, failed, id);
                }
                collectFailure(calcById, failed, source.get("varianceCalcId"));

                ObjectNode computed = source.putObject("computed");
                JsonNode qualifying = computedCalc(calcById, source.get("qualifyingCalcId"));
                if (qualifying != null) {
                    calculator++;
                    fillFromCalculation(computed, qualifying);
                    if (computed.path("frequencyInferred").asBoolean()) {
                        warnings.add("LOW", where + ": the pay frequency was inferred from the pay"
                                + " period length, not read from the document.");
                    }
                } else if (source.path("monthly").isNumber()) {
                    modelStated++;
                    computed.set("monthly", source.get("monthly"));
                    computed.putNull("method");
                    computed.putNull("frequency");
                    computed.put("frequencyInferred", false);
                    computed.put("basis", "MODEL_STATED");
                    capConfidence(source, "MEDIUM");
                    warnings.add("MEDIUM", where + ": the monthly figure is model-stated and"
                            + " unverified; no calculation backs it.");
                } else {
                    none++;
                    computed.putNull("monthly");
                    computed.putNull("method");
                    computed.putNull("frequency");
                    computed.put("frequencyInferred", false);
                    computed.put("basis", "NONE");
                    capConfidence(source, "LOW");
                    warnings.add("HIGH", where + ": no monthly figure could be established;"
                            + " neither a calculation nor a stated figure is available.");
                }
            }
        }

        boolean anyDuplicate = markDuplicateEmployment(envelope, borrowers, calcById, warnings);

        collectFailure(calcById, failed, domain.get("totalCalcId"));
        collectFailure(calcById, failed, domain.get("reconciliationCalcId"));
        for (JsonNode opportunity : domain.path("opportunities")) {
            collectFailure(calcById, failed, opportunity.get("estimatedMonthlyCalcId"));
        }
        Matcher failureCitation = REPORT_FAILURE_CITATION.matcher(envelope.path("reportMarkdown").asText(""));
        while (failureCitation.find()) {
            collectFailure(calcById, failed, failureCitation.group(1));
        }
        JsonNode total = computedCalc(calcById, domain.get("totalCalcId"));
        if (total != null) {
            domain.set("computedTotalMonthly", total.path("result").get("value"));
        } else {
            domain.putNull("computedTotalMonthly");
        }
        // Engine-written, like computedTotalMonthly: a model-supplied value never survives.
        domain.remove("totalAdjustedForDuplicates");
        domain.remove("adjustedReconciliation");
        Set<String> duplicateOnly = anyDuplicate ? duplicateOnlyCalcIds(borrowers) : Set.of();
        if (total != null && countsADuplicate(total, duplicateOnly)) {
            BigDecimal adjusted = totalWithout(total, duplicateOnly, calcById)
                    .add(keptInPlaceOfDuplicates(borrowers, total, duplicateOnly, calcById, warnings))
                    .setScale(2, RoundingMode.HALF_UP);
            domain.put("computedTotalMonthly", adjusted);
            domain.put("totalAdjustedForDuplicates", true);
            String reconciliationId = domain.path("reconciliationCalcId").asText("");
            JsonNode reconciliation = computedCalc(calcById, reconciliationId);
            if (reconciliation != null && reconciliation.path("inputs").path("stated").isNumber()) {
                domain.set("adjustedReconciliation", adjustedVariance(reconciliation, adjusted));
            }
            warnings.add("HIGH", "The requested total \"" + total.path("id").asText() + "\" counts duplicate"
                    + " employment; computedTotalMonthly is replaced with the same total without the duplicate's"
                    + " calculations."
                    + (reconciliationId.isBlank() ? "" : " The application-total variance \"" + reconciliationId
                            + "\" is re-run against the replaced total as domain.adjustedReconciliation."));
        }

        for (String id : failed) {
            JsonNode calc = calcById.get(id);
            warnings.add("HIGH", calc == null
                    ? "Calculation \"" + id + "\" is referenced in the income domain but was never"
                            + " requested, so it was not computed."
                    : "Calculation \"" + id + "\" did not compute: "
                            + calc.path("result").path("error").asText("calculation failed"));
        }

        Set<String> unmatched = new LinkedHashSet<>();
        Matcher m = LEFTOVER_PLACEHOLDER.matcher(envelope.path("reportMarkdown").asText(""));
        while (m.find()) {
            unmatched.add(m.group(1));
        }
        for (String id : unmatched) {
            warnings.add("MEDIUM", "The report references calculation \"" + id + "\", which was"
                    + " never requested; the placeholder was left as written.");
        }

        if (!failed.isEmpty() || !unmatched.isEmpty()) {
            reduceConfidence(envelope);
        }
        return new Summary(calculator, modelStated, none, failed.size(), unmatched.size());
    }

    // --- duplicate-employment guard (loan Gough, 2026-09) ---------------------------------------

    /**
     * Payroll processors and PEOs, matched as whole tokens. "CHC" alone is NOT here: it is as often
     * a Community Health Center as CHC Payroll Agent, so only the phrase "chc payroll" counts.
     */
    private static final Set<String> PAYROLL_PROCESSORS = Set.of("adp", "paychex", "paylocity",
            "ukg", "ceridian", "gusto", "trinet", "insperity", "justworks", "peo");
    /** Dropped when comparing employer names: legal-form suffixes and filler words. */
    private static final Set<String> NAME_NOISE = Set.of("inc", "llc", "corp", "co", "ltd", "of", "the",
            "incorporated", "corporation", "company");
    private static final List<String> BASIS_RANK = List.of("NONE", "MODEL_STATED", "CALCULATOR");
    /** Pure and stateless; used only to re-run the reconciliation variance after an adjustment. */
    private static final IncomeCalcService VARIANCE = new IncomeCalcService();
    private static final Pattern AGENT_FOR = Pattern.compile("(?i)\\bagent\\s+for\\s+([^)\\];]+)");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern EIN_IN_TEXT = Pattern.compile("\\b\\d{2}-\\d{7}\\b");

    /**
     * Best-effort, per borrower. Two W-2 base sources are probably one employment only on strong
     * evidence: the principal a payroll agent names ("Agent for X") is contained in the other
     * employer's normalized name (or vice versa); the normalized names are equal or one contains
     * the other; or the facts share an EIN and neither employer is a payroll agent or PEO (those
     * print the agent's EIN for every client). A single shared word is never enough: city, state
     * and industry words link genuinely concurrent jobs.
     *
     * <p>The kept source ranks CALCULATOR &gt; MODEL_STATED &gt; NONE, then the more recent dated
     * evidence, then the earlier index. Every dropped source's {@code computed.duplicateOf} names the
     * FINAL kept source (chains are resolved), with one HIGH warning each. Basis is left unchanged.
     */
    private static boolean markDuplicateEmployment(ObjectNode envelope, JsonNode borrowers,
                                                   Map<String, JsonNode> calcById, Warnings warnings) {
        Map<String, JsonNode> factById = new LinkedHashMap<>();
        for (JsonNode fact : envelope.path("facts")) {
            factById.putIfAbsent(fact.path("id").asText(), fact);
        }
        boolean any = false;
        for (int b = 0; b < borrowers.size(); b++) {
            JsonNode sources = borrowers.get(b).path("sources");
            Map<Integer, Integer> droppedFor = new LinkedHashMap<>();
            Map<Integer, String> reasons = new LinkedHashMap<>();
            for (int i = 0; i < sources.size(); i++) {
                if (!isW2Base(sources.get(i))) {
                    continue;
                }
                for (int j = i + 1; j < sources.size() && !droppedFor.containsKey(i); j++) {
                    if (droppedFor.containsKey(j) || !isW2Base(sources.get(j))) {
                        continue;
                    }
                    String reason = duplicateReason(sources.get(i), sources.get(j), factById);
                    if (reason == null) {
                        continue;
                    }
                    int keep = keeper(sources, i, j, calcById, factById);
                    int drop = keep == i ? j : i;
                    droppedFor.put(drop, keep);
                    reasons.put(drop, reason);
                }
            }
            for (Map.Entry<Integer, Integer> entry : droppedFor.entrySet()) {
                int drop = entry.getKey();
                int keep = entry.getValue();
                while (droppedFor.containsKey(keep)) {   // a kept source was itself dropped later
                    keep = droppedFor.get(keep);
                }
                any = true;
                JsonNode kept = sources.get(keep);
                ObjectNode droppedSource = (ObjectNode) sources.get(drop);
                ((ObjectNode) droppedSource.path("computed")).put("duplicateOf", keep);
                warnings.add("HIGH", "borrowers[" + b + "].sources[" + drop + "] ("
                        + droppedSource.path("type").asText("source") + "): probably the same employment as"
                        + " sources[" + keep + "] (" + kept.path("employer").asText("unnamed") + ") — "
                        + reasons.get(drop) + "; counted once.");
            }
        }
        return any;
    }

    private static boolean isW2Base(JsonNode source) {
        String type = source.path("type").asText("").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return type.startsWith("w2") && !type.matches(".*(overtime|bonus|commission|tips).*");
    }

    private static String duplicateReason(JsonNode a, JsonNode b, Map<String, JsonNode> factById) {
        String ea = a.path("employer").isTextual() ? a.path("employer").asText() : "";
        String eb = b.path("employer").isTextual() ? b.path("employer").asText() : "";
        String agent = agentReason(ea, eb);
        if (agent == null) {
            agent = agentReason(eb, ea);
        }
        if (agent != null) {
            return agent;
        }
        if (namesContain(ea, eb)) {
            return "the employer names match";
        }
        if (isPayrollAgentOrPeo(ea) || isPayrollAgentOrPeo(eb)) {
            return null;   // an agent/PEO W-2 prints the agent's EIN for every client
        }
        Set<String> einsA = eins(a, factById);
        einsA.retainAll(eins(b, factById));
        return einsA.isEmpty() ? null : "the facts behind them share an EIN";
    }

    /** Non-null when {@code agentEmployer} names a principal ("Agent for X") contained in {@code other}, or vice versa. */
    private static String agentReason(String agentEmployer, String other) {
        Matcher agentFor = AGENT_FOR.matcher(agentEmployer);
        if (!agentFor.find()) {
            return null;
        }
        String principal = agentFor.group(1).trim();
        // The explicit "Agent for X" line is itself strong evidence, so X needs only one
        // non-generic token — but its FULL normalized name must appear inside the other employer's.
        String np = String.join(" ", nameTokens(principal));
        String no = String.join(" ", nameTokens(other));
        boolean contained = !np.isEmpty() && (" " + no + " ").contains(" " + np + " ");
        return contained && significantTokens(principal) >= 1
                ? "one employer is a payroll agent for \"" + principal + "\", whose name is contained in the"
                        + " other employer's"
                : null;
    }

    /**
     * Normalized names are equal, or one is a whole-token run inside the other AND that contained
     * run carries at least two significant tokens. "Target" in "Target Hospitality Corp" and
     * "University of Colorado" in "University of Colorado Health" are separate employers.
     */
    private static boolean namesContain(String a, String b) {
        String na = String.join(" ", nameTokens(a));
        String nb = String.join(" ", nameTokens(b));
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.equals(nb)) {
            return true;
        }
        if ((" " + nb + " ").contains(" " + na + " ")) {
            return significantTokens(a) >= 2;
        }
        return (" " + na + " ").contains(" " + nb + " ") && significantTokens(b) >= 2;
    }

    /** Tokens that identify an employer: not legal-form noise, geography, or an industry word. */
    private static long significantTokens(String name) {
        return nameTokens(name).stream().filter(t -> !GENERIC_NAME_TOKENS.contains(t)).count();
    }

    /** Geography and industry words that many unrelated employers share. */
    private static final Set<String> GENERIC_NAME_TOKENS = Set.of(
            "denver", "colorado", "aurora", "boulder", "mountain", "mountains", "front", "range", "rocky",
            "county", "state", "states", "city", "regional", "region", "community", "valley", "north", "south",
            "east", "west", "central", "national", "american", "united", "international", "university",
            "health", "healthcare", "medical", "hospital", "hospitals", "center", "centers", "ctr", "clinic",
            "care", "partners", "systems", "system", "business", "services", "service", "solutions", "group",
            "holdings", "enterprises", "associates", "payroll", "agent");

    private static boolean isPayrollAgentOrPeo(String employer) {
        String flat = String.join(" ", nameTokens(employer));
        return AGENT_FOR.matcher(employer).find()
                || (" " + flat + " ").contains(" payroll ")
                || (" " + flat + " ").contains(" chc payroll ")
                || nameTokens(employer).stream().anyMatch(PAYROLL_PROCESSORS::contains);
    }

    private static List<String> nameTokens(String name) {
        List<String> out = new java.util.ArrayList<>();
        for (String token : name.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty() && !NAME_NOISE.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    /** Nine-digit EINs from the source's facts whose key or statement names an EIN. */
    private static Set<String> eins(JsonNode source, Map<String, JsonNode> factById) {
        Set<String> out = new HashSet<>();
        for (JsonNode id : source.path("factIds")) {
            JsonNode fact = factById.get(id.asText());
            if (fact == null) {
                continue;
            }
            String key = fact.path("key").asText("");
            String statement = fact.path("statement").asText("");
            boolean einKey = key.equalsIgnoreCase("ein") || key.equalsIgnoreCase("fein")
                    || key.endsWith("Ein") || key.endsWith("EIN") || key.toLowerCase(java.util.Locale.ROOT).endsWith("_ein");
            boolean einStatement = statement.matches("(?s).*\\b(EIN|FEIN)\\b.*")
                    || statement.toLowerCase(java.util.Locale.ROOT).contains("employer identification number");
            if (!einKey && !einStatement) {
                continue;
            }
            String digits = fact.path("value").asText("").replaceAll("\\D", "");
            if (digits.length() == 9) {
                out.add(digits);
            }
            Matcher inText = EIN_IN_TEXT.matcher(statement);
            while (inText.find()) {
                out.add(inText.group().replace("-", ""));
            }
        }
        return out;
    }

    private static int keeper(JsonNode sources, int i, int j, Map<String, JsonNode> calcById,
                              Map<String, JsonNode> factById) {
        int rankI = BASIS_RANK.indexOf(sources.get(i).path("computed").path("basis").asText("NONE"));
        int rankJ = BASIS_RANK.indexOf(sources.get(j).path("computed").path("basis").asText("NONE"));
        if (rankI != rankJ) {
            return rankI > rankJ ? i : j;
        }
        String dateI = latestEvidenceDate(sources.get(i), calcById, factById);
        String dateJ = latestEvidenceDate(sources.get(j), calcById, factById);
        return dateJ.compareTo(dateI) > 0 ? j : i;   // ISO dates compare lexically; "" is oldest
    }

    /** The latest ISO date in the source's calculation inputs and fact values, or "" when none. */
    private static String latestEvidenceDate(JsonNode source, Map<String, JsonNode> calcById,
                                             Map<String, JsonNode> factById) {
        List<String> texts = new java.util.ArrayList<>();
        List<JsonNode> ids = new java.util.ArrayList<>();
        ids.add(source.path("qualifyingCalcId"));
        source.path("calcIds").forEach(ids::add);
        for (JsonNode id : ids) {
            JsonNode calc = calcById.get(id.asText());
            if (calc != null) {
                calc.path("inputs").forEach(v -> texts.add(v.asText("")));
            }
        }
        for (JsonNode id : source.path("factIds")) {
            JsonNode fact = factById.get(id.asText());
            if (fact != null) {
                texts.add(fact.path("value").asText(""));
            }
        }
        String latest = "";
        for (String text : texts) {
            Matcher m = ISO_DATE.matcher(text);
            while (m.find()) {
                if (m.group().compareTo(latest) > 0) {
                    latest = m.group();
                }
            }
        }
        return latest;
    }

    /** Calculation ids referenced by a duplicate source and by no counted source. */
    private static Set<String> duplicateOnlyCalcIds(JsonNode borrowers) {
        Set<String> counted = new HashSet<>();
        Set<String> duplicate = new HashSet<>();
        for (JsonNode borrower : borrowers) {
            for (JsonNode source : borrower.path("sources")) {
                Set<String> target = source.path("computed").has("duplicateOf") ? duplicate : counted;
                target.add(source.path("qualifyingCalcId").asText(""));
                source.path("calcIds").forEach(id -> target.add(id.asText("")));
            }
        }
        duplicate.removeAll(counted);
        duplicate.remove("");
        return duplicate;
    }

    private static boolean countsADuplicate(JsonNode total, Set<String> duplicateOnly) {
        for (JsonNode ref : total.path("inputs").path("amountRefs")) {
            if (duplicateOnly.contains(ref.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The model's own total minus the duplicate's calculations: its literal amounts plus every
     * amountRef that is not duplicate-only, each ref's COMPUTED value. The executor's resolved
     * {@code amounts} holds the literals first, then one entry per ref, so the literals are the
     * leading {@code amounts.size() - amountRefs.size()} entries.
     */
    private static BigDecimal totalWithout(JsonNode total, Set<String> duplicateOnly, Map<String, JsonNode> calcById) {
        JsonNode inputs = total.path("inputs");
        JsonNode refs = inputs.path("amountRefs");
        JsonNode amounts = inputs.path("amounts");
        BigDecimal sum = BigDecimal.ZERO;
        int literals = Math.max(0, amounts.size() - refs.size());
        for (int k = 0; k < literals; k++) {
            sum = sum.add(amounts.get(k).decimalValue());
        }
        for (JsonNode ref : refs) {
            if (!duplicateOnly.contains(ref.asText())) {
                sum = sum.add(calcById.get(ref.asText()).path("result").path("value").decimalValue());
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * When the total counted a dropped duplicate but none of its final kept source's calculations,
     * removing the duplicate would drop the employment entirely. Adds each such kept source's
     * COMPUTED qualifying figure once; a kept source with no computed figure adds nothing and says so.
     */
    private static BigDecimal keptInPlaceOfDuplicates(JsonNode borrowers, JsonNode total, Set<String> duplicateOnly,
                                                      Map<String, JsonNode> calcById, Warnings warnings) {
        Set<String> refs = new HashSet<>();
        total.path("inputs").path("amountRefs").forEach(ref -> refs.add(ref.asText()));
        Set<String> added = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int b = 0; b < borrowers.size(); b++) {
            JsonNode sources = borrowers.get(b).path("sources");
            for (JsonNode source : sources) {
                JsonNode duplicateOf = source.path("computed").get("duplicateOf");
                if (duplicateOf == null || calcIdsOf(source).stream().noneMatch(id -> refs.contains(id) && duplicateOnly.contains(id))) {
                    continue;
                }
                int k = duplicateOf.asInt();
                JsonNode kept = sources.path(k);
                if (calcIdsOf(kept).stream().anyMatch(refs::contains) || !added.add(b + ":" + k)) {
                    continue;
                }
                JsonNode qualifying = computedCalc(calcById, kept.get("qualifyingCalcId"));
                if (qualifying != null) {
                    sum = sum.add(qualifying.path("result").path("value").decimalValue());
                } else {
                    warnings.add("HIGH", "borrowers[" + b + "].sources[" + k + "] ("
                            + kept.path("type").asText("source") + "): kept in place of a duplicate the requested"
                            + " total counted, but it has no computed qualifying figure; the adjusted total omits"
                            + " this employment.");
                }
            }
        }
        return sum;
    }

    private static Set<String> calcIdsOf(JsonNode source) {
        Set<String> ids = new HashSet<>();
        String qualifying = source.path("qualifyingCalcId").asText("");
        if (!qualifying.isBlank()) {
            ids.add(qualifying);
        }
        source.path("calcIds").forEach(id -> ids.add(id.asText("")));
        ids.remove("");
        return ids;
    }

    /** income.variance.v1 re-run deterministically: computed = the adjusted total, stated = the reconciliation's. */
    private static ObjectNode adjustedVariance(JsonNode reconciliation, BigDecimal adjustedTotal) {
        ObjectNode out = JsonNodeFactory.withExactBigDecimals(true).objectNode();
        out.put("id", reconciliation.path("id").asText());
        out.put("method", "income.variance.v1");
        ObjectNode inputs = out.putObject("inputs");
        inputs.put("computed", adjustedTotal);
        inputs.put("stated", reconciliation.path("inputs").path("stated").decimalValue());
        CalcResult r = VARIANCE.compute("income.variance.v1", inputs);
        ObjectNode result = out.putObject("result");
        if (r.isComputed()) {
            result.put("status", "COMPUTED");
            result.put("value", r.value());
            ObjectNode mid = result.putObject("intermediates");
            r.intermediates().forEach((key, value) -> {
                if (value instanceof BigDecimal d) {
                    mid.put(key, d);
                } else if (value instanceof Boolean flag) {
                    mid.put(key, flag);
                } else {
                    mid.put(key, String.valueOf(value));
                }
            });
        } else {
            result.put("status", "ERROR");
            result.putNull("value");
            result.put("error", r.error() == null || r.error().isBlank() ? "calculation failed" : r.error());
        }
        return out;
    }

    private static void fillFromCalculation(ObjectNode computed, JsonNode calc) {
        JsonNode result = calc.path("result");
        JsonNode mid = result.path("intermediates");
        String method = calc.path("method").asText();
        computed.set("monthly", result.get("value"));
        computed.put("method", method);
        switch (method) {
            case "income.monthly_from_rate.v1" -> {
                computed.put("frequency", mid.path("frequency").asText());
                computed.put("frequencyInferred", false);
            }
            case "income.monthly_from_period_gross.v1" -> {
                computed.put("frequency", mid.path("inferredFrequency").asText());
                computed.put("frequencyInferred", true);
            }
            case "income.period_gross_from_ytd_delta.v1" -> {
                computed.put("frequency", mid.path("frequency").asText());
                computed.put("frequencyInferred", mid.path("frequencyInferred").asBoolean());
            }
            case "income.monthly_from_annual.v1" -> {
                computed.put("frequency", "ANNUAL");
                computed.put("frequencyInferred", false);
            }
            default -> {
                computed.putNull("frequency");
                computed.put("frequencyInferred", false);
            }
        }
        computed.put("basis", "CALCULATOR");
    }

    /** Records an id that is referenced but did not compute (errored, or was never requested). */
    private static void collectFailure(Map<String, JsonNode> calcById, Set<String> failed, JsonNode idNode) {
        if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
            return;
        }
        collectFailure(calcById, failed, idNode.asText());
    }

    private static void collectFailure(Map<String, JsonNode> calcById, Set<String> failed, String id) {
        if (computedCalc(calcById, id) == null) {
            failed.add(id);
        }
    }

    private static JsonNode computedCalc(Map<String, JsonNode> calcById, JsonNode idNode) {
        if (idNode == null || !idNode.isTextual()) {
            return null;
        }
        return computedCalc(calcById, idNode.asText());
    }

    private static JsonNode computedCalc(Map<String, JsonNode> calcById, String id) {
        JsonNode calc = calcById.get(id);
        return calc != null && "COMPUTED".equals(calc.path("result").path("status").asText()) ? calc : null;
    }

    private static void capConfidence(ObjectNode source, String cap) {
        int current = LEVELS.indexOf(source.path("confidence").asText(""));
        if (current > LEVELS.indexOf(cap)) {
            source.put("confidence", cap);
        }
    }

    private static void reduceConfidence(ObjectNode envelope) {
        JsonNode confidence = envelope.get("confidence");
        if (confidence == null || !confidence.isNumber()) {
            return;
        }
        envelope.put("confidence", confidence.decimalValue().subtract(CONFIDENCE_STEP)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
    }

    /** Appends schema-valid warnings with ids that never collide with the model's. */
    private static final class Warnings {
        private final ArrayNode array;
        private final Set<String> taken = new HashSet<>();
        private int next = 1;

        Warnings(ObjectNode envelope) {
            JsonNode existing = envelope.get("warnings");
            this.array = existing instanceof ArrayNode a ? a : envelope.putArray("warnings");
            for (JsonNode w : array) {
                taken.add(w.path("id").asText());
            }
        }

        void add(String severity, String statement) {
            String id;
            do {
                id = "engine-income-" + next++;
            } while (!taken.add(id));
            ObjectNode warning = array.addObject();
            warning.put("id", id);
            warning.put("severity", severity);
            warning.put("statement", statement);
            warning.put("confidence", 1);
        }
    }
}
