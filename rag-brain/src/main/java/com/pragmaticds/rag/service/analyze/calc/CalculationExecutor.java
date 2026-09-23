package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a validated v2 envelope's calculation requests through the deterministic
 * calc services, writes each outcome into the request's {@code result}, and
 * substitutes {@code {{calc:<id>}}} placeholders in reportMarkdown so computed
 * numbers in the report always come from the engine, never the model.
 *
 * A model-supplied {@code result} is stripped and overwritten — the model has no
 * authority over computed values. The input envelope is never mutated.
 *
 * The {@code result} node shape is dictated by the {@code calculationRequest.result}
 * {@code oneOf} in {@code ai/analyzer-envelope-v2.schema.json}: a COMPUTED result
 * carries exactly {@code status, value, intermediates, rounding} and MUST NOT
 * carry {@code error}; an ERROR result carries exactly {@code status, value
 * (explicit JSON null), error} and MUST NOT carry {@code intermediates} or
 * {@code rounding}. Emitting a field outside its branch fails schema validation.
 *
 * <h2>Calculation chaining by reference</h2>
 * {@code income.total_monthly.v1} and {@code income.variance.v1} both take, as
 * their most important input, a number that is itself the OUTPUT of an earlier
 * calculation (a per-source monthly amount; a computed total). Since {@link
 * IncomeCalcService} is intentionally kept pure — it only ever sees literal
 * numbers and knows nothing about other calculations — this class resolves
 * references BEFORE dispatch, substituting resolved values into a COPY of the
 * request's {@code inputs} and handing that copy to {@code IncomeCalcService}.
 * Supported ref keys: {@code income.total_monthly.v1} accepts {@code
 * "amountRefs": ["<id>", ...]} alongside (or instead of) the literal {@code
 * "amounts"} array — every literal and every resolved ref is summed together;
 * {@code income.variance.v1} accepts {@code "computedRef": "<id>"} as an
 * alternative to the literal {@code "computed"} ({@code "stated"} is never a
 * ref target — it comes from claimed URLA data, not a calculation).
 * <p>
 * <b>Resolved-inputs shape.</b> The copy handed to {@code IncomeCalcService},
 * and the copy that ends up in the enriched envelope's {@code inputs} and the
 * audit row, is the ORIGINAL inputs object with the literal-valued field
 * ({@code amounts} / {@code computed}) filled in with the resolved number —
 * the ref field ({@code amountRefs} / {@code computedRef}) is left in place,
 * unchanged, alongside it. So a chained total's enriched {@code inputs} shows
 * both {@code "amountRefs":["c1","c2"]} AND the {@code "amounts"} array those
 * refs resolved to: the manifest records what was actually computed without
 * erasing the dependency that produced it. On a resolution FAILURE, {@code
 * inputs} is left completely unresolved (the original, as the model wrote it)
 * — there is no number to show, and showing a partial one would be misleading.
 * <p>
 * <b>Backward-only.</b> A ref may only resolve to a calculation earlier in
 * {@code calculations[]}; calculations already execute in array order, so this
 * is enforced simply by only ever looking a ref up in the map of
 * ALREADY-PROCESSED results — a forward or self reference is never in that map
 * yet and fails closed. No topological sort.
 * <p>
 * <b>Fail-closed.</b> A ref to a calculation that itself errored, or to an
 * unknown id, fails the DEPENDENT calculation with a message naming the ref —
 * never a defaulted zero, so a failed source can never silently vanish from a
 * total.
 * <p>
 * <b>Duplicate ids.</b> The schema does not enforce unique {@code id}s, and a
 * duplicate only warns (see below). A ref to a duplicated id resolves to the
 * EARLIEST occurrence — the first one seen by array order — which is the only
 * choice consistent with backward-only resolution; report-placeholder
 * substitution ({@link #substitute}) is unrelated and keeps its own,
 * already-shipped last-one-wins behavior.
 */
@Service
public class CalculationExecutor {

    private static final Logger log = LoggerFactory.getLogger(CalculationExecutor.class);
    private static final String ROUNDING = "HALF_UP,2dp";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{calc:([A-Za-z0-9_-]{1,64})}}");

    /**
     * BigDecimal factory that preserves the value's own scale (e.g. {@code 90000}
     * stays {@code 90000}, not {@code 9E+4}) rather than the default node factory,
     * which normalizes through {@code BigDecimal.stripTrailingZeros()} and can
     * flip into scientific notation. Used only for the intermediates tree so it
     * renders symmetrically with {@code value}, which is written via
     * {@code ObjectNode.put(String, BigDecimal)} and is already exact.
     */
    // Not JsonNodeFactory.withExactBigDecimals(true) — that static factory is deprecated
    // in this Jackson version. The public single-arg constructor is the current API for
    // the same "don't strip trailing zeros" behavior.
    private static final JsonNodeFactory EXACT_DECIMALS = new JsonNodeFactory(true);

    /** Enriched deep copy of the envelope plus the manifest-bound audit rows. */
    public record Execution(ObjectNode enriched, List<Map<String, Object>> audit) {}

    private final IncomeCalcService incomeCalc;
    private final ObjectMapper objectMapper;
    private final ObjectMapper exactDecimalMapper;

    public CalculationExecutor(IncomeCalcService incomeCalc, ObjectMapper objectMapper) {
        this.incomeCalc = incomeCalc;
        this.objectMapper = objectMapper;
        // A separate copy, not a reconfiguration of the shared injected mapper bean:
        // the exact-BigDecimal node factory is local to how we render intermediates.
        this.exactDecimalMapper = objectMapper.copy().setNodeFactory(EXACT_DECIMALS);
    }

    public Execution execute(JsonNode envelope) {
        ObjectNode out = (ObjectNode) envelope.deepCopy();
        List<Map<String, Object>> audit = new ArrayList<>();
        // Last-wins: drives {{calc:<id>}} report-placeholder substitution only
        // (pre-existing behavior, unchanged by chaining).
        Map<String, CalcResult> byId = new LinkedHashMap<>();
        // First-wins: drives ref resolution. A ref may only point backward, so by
        // the time calc[i] is processed this map holds exactly the results of
        // calc[0..i-1] — a hit IS the backward-reference guarantee. On a duplicate
        // id, putIfAbsent keeps the EARLIEST occurrence (see class javadoc).
        Map<String, CalcResult> firstById = new LinkedHashMap<>();

        JsonNode calcs = out.get("calculations");
        if (calcs != null && calcs.isArray()) {
            // Every id in the array, scanned up front, purely so a failed ref lookup
            // can say "unknown id" versus "forward/self reference" instead of one
            // generic message — this is a message-quality lookup, not a topological
            // sort; execution order below is untouched.
            Set<String> allIds = new HashSet<>();
            for (JsonNode c : calcs) {
                String cid = c.path("id").asText();
                if (!cid.isEmpty()) {
                    allIds.add(cid);
                }
            }
            for (JsonNode c : calcs) {
                ObjectNode calc = (ObjectNode) c;
                if (calc.has("result")) {
                    log.warn("Model supplied a calculation result for id={}; overwriting",
                            calc.path("id").asText());
                }
                String id = calc.path("id").asText();
                String method = calc.path("method").asText();
                CalcResult r;
                try {
                    JsonNode resolvedInputs = resolveRefs(method, calc.get("inputs"), firstById, allIds);
                    r = incomeCalc.compute(method, resolvedInputs);
                    if (resolvedInputs != null) {
                        calc.set("inputs", resolvedInputs);
                    }
                } catch (CalcInputException e) {
                    // Ref resolution itself failed (unknown/forward/self/failed ref, or
                    // both a literal and a ref for the same scalar) — this calculation
                    // never reaches IncomeCalcService, and inputs stays exactly as the
                    // model wrote it (no partial resolution to show).
                    r = CalcResult.error(e.getMessage());
                }
                calc.set("result", toResultNode(r));
                CalcResult previous = byId.put(id, r);
                if (previous != null) {
                    log.warn("Duplicate calculation id={} in envelope; report placeholders will use the last one",
                            id);
                }
                firstById.putIfAbsent(id, r);
                audit.add(auditRow(calc, r));
            }
        }

        String report = out.path("reportMarkdown").asText("");
        out.put("reportMarkdown", substitute(report, byId));
        return new Execution(out, audit);
    }

    /**
     * Resolves calculation-chaining refs into a copy of {@code inputs}, dispatched
     * by method — only {@code income.total_monthly.v1} ({@code amountRefs}) and
     * {@code income.variance.v1} ({@code computedRef}) support chaining; every
     * other method's inputs pass straight through unchanged. Returns {@code null}
     * when {@code inputs} itself is null or not an object, so {@link
     * IncomeCalcService#compute} raises its own "inputs must be a JSON object"
     * error exactly as it did before chaining existed.
     */
    private JsonNode resolveRefs(String method, JsonNode inputs, Map<String, CalcResult> firstById,
            Set<String> allIds) {
        if (inputs == null || !inputs.isObject()) {
            return null;
        }
        ObjectNode in = (ObjectNode) inputs;
        return switch (method) {
            case "income.total_monthly.v1" -> resolveTotalMonthlyRefs(in, firstById, allIds);
            case "income.variance.v1" -> resolveVarianceRefs(in, firstById, allIds);
            default -> in;
        };
    }

    /**
     * {@code amountRefs: ["<id>", ...]} — each id resolves to that calculation's
     * COMPUTED value and is appended to a copy of {@code amounts} (creating the
     * array if the request had none). {@code amountRefs} is left in the returned
     * copy unchanged, so the enriched envelope and audit row show both the refs
     * that were followed and the {@code amounts} they resolved into.
     */
    private ObjectNode resolveTotalMonthlyRefs(ObjectNode in, Map<String, CalcResult> firstById,
            Set<String> allIds) {
        JsonNode amountRefs = in.get("amountRefs");
        if (amountRefs == null) {
            return in;
        }
        if (!amountRefs.isArray()) {
            throw new CalcInputException("input amountRefs must be an array of calculation ids");
        }
        ObjectNode resolved = in.deepCopy();
        JsonNode existingAmounts = resolved.get("amounts");
        ArrayNode amounts = existingAmounts != null && existingAmounts.isArray()
                ? (ArrayNode) existingAmounts
                : resolved.putArray("amounts");
        int i = 0;
        for (JsonNode refNode : amountRefs) {
            String field = "amountRefs[" + i + "]";
            if (!refNode.isTextual() || refNode.asText().isBlank()) {
                throw new CalcInputException("input " + field + " must be a non-blank calculation id string");
            }
            amounts.add(EXACT_DECIMALS.numberNode(resolveRef(field, refNode.asText(), firstById, allIds)));
            i++;
        }
        return resolved;
    }

    /**
     * {@code computedRef: "<id>"} resolves to that calculation's COMPUTED value
     * and is written into a copy's {@code computed} field; {@code computedRef}
     * itself is left in the copy so the reader sees both the ref that was
     * followed and the literal it resolved to. Both {@code computed} and {@code
     * computedRef} present at once is ambiguous and rejected outright — the
     * schema can't express "exactly one of", so this class enforces it.
     * {@code stated} is never a ref target; it stays literal always.
     */
    private ObjectNode resolveVarianceRefs(ObjectNode in, Map<String, CalcResult> firstById, Set<String> allIds) {
        JsonNode computedRef = in.get("computedRef");
        if (computedRef == null) {
            return in;
        }
        if (in.has("computed")) {
            throw new CalcInputException("input has both \"computed\" and \"computedRef\"; supply exactly one");
        }
        if (!computedRef.isTextual() || computedRef.asText().isBlank()) {
            throw new CalcInputException("input computedRef must be a non-blank calculation id string");
        }
        BigDecimal value = resolveRef("computedRef", computedRef.asText(), firstById, allIds);
        ObjectNode resolved = in.deepCopy();
        resolved.set("computed", EXACT_DECIMALS.numberNode(value));
        return resolved;
    }

    /**
     * Looks {@code refId} up in {@code firstById}, which holds results only for
     * calculations already processed — strictly earlier in the array — so a hit
     * there IS the backward-reference guarantee. A miss is either a truly unknown
     * id or a forward/self reference (the id exists somewhere in {@code allIds}
     * but hasn't been processed yet); those get distinct messages. A hit whose
     * result is not COMPUTED (i.e. the referenced calculation itself errored)
     * fails this calculation too — never a defaulted zero.
     */
    private static BigDecimal resolveRef(String field, String refId, Map<String, CalcResult> firstById,
            Set<String> allIds) {
        CalcResult target = firstById.get(refId);
        if (target == null) {
            if (allIds.contains(refId)) {
                throw new CalcInputException("input " + field + " references calculation \"" + refId
                        + "\" which is not earlier in calculations[] (forward or self references are not allowed)");
            }
            throw new CalcInputException("input " + field + " references unknown calculation id \"" + refId + "\"");
        }
        if (!target.isComputed()) {
            throw new CalcInputException(
                    "input " + field + " references calculation \"" + refId + "\" which did not compute");
        }
        return target.value();
    }

    /**
     * Builds the {@code result} node by hand, one branch at a time, so that only
     * the fields the schema's {@code oneOf} allows for that status are ever
     * written — no shared "build everything, null the rest" helper, since a
     * literal JSON {@code null} for {@code error} or {@code intermediates}/
     * {@code rounding} would itself fail validation (those properties must be
     * entirely absent, not null).
     */
    private ObjectNode toResultNode(CalcResult r) {
        ObjectNode result = objectMapper.createObjectNode();
        if (r.isComputed()) {
            result.put("status", "COMPUTED");
            result.put("value", r.value());
            result.set("intermediates", exactDecimalMapper.valueToTree(r.intermediates()));
            result.put("rounding", ROUNDING);
        } else {
            result.put("status", "ERROR");
            result.putNull("value");
            // A schema-invalid null/blank error would fail validation (minLength: 1)
            // and swallow the real defect behind a confusing validator error instead.
            String message = r.error() == null || r.error().isBlank()
                    ? "calculation failed" : r.error();
            result.put("error", message);
        }
        return result;
    }

    private static Map<String, Object> auditRow(ObjectNode calc, CalcResult r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", calc.path("id").asText());
        row.put("name", calc.path("name").asText());
        row.put("method", calc.path("method").asText());
        // Deep-copy: calc.get("inputs") is the same node identity that lives inside
        // the enriched envelope, so aliasing it here would let later mutation of the
        // envelope silently rewrite the audit trail Task 6 persists.
        JsonNode inputs = calc.get("inputs");
        row.put("inputs", inputs == null ? null : inputs.deepCopy());
        row.put("status", r.status());
        row.put("value", r.value());
        row.put("error", r.error());
        return row;
    }

    private static String substitute(String report, Map<String, CalcResult> byId) {
        Matcher m = PLACEHOLDER.matcher(report);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            CalcResult r = byId.get(id);
            String replacement;
            if (r == null) {
                log.warn("Unmatched calculation placeholder for id={} in reportMarkdown; leaving literal", id);
                replacement = m.group();                      // unknown id: leave visible
            } else if (r.isComputed()) {
                replacement = r.value().toPlainString();
            } else {
                replacement = "[calculation failed: " + id + "]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
