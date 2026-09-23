package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * THE EQUIVALENCE TEST (Spec 5a, plan root CONTRACTS): an ungrouped field's persistence and API
 * output are byte-identical before and after V13. Every existing schema is single-valued, so if
 * this moves, the "additive migration" claim is false and the spec has broken production data.
 *
 * <p>Both goldens are RECORDED FROM A PRE-V13 RUN, never typed by hand: the test writes what it
 * saw to {@code build/spec5a/} on every run, and the recording step copies those two files into
 * {@code src/test/resources/extraction/golden/}. Recording them after the migration would prove
 * nothing at all.
 *
 * <p>The persistence projection names its columns EXPLICITLY rather than using {@code SELECT *}.
 * That is the point: V13 adds a column, so {@code SELECT *} would change by construction. What
 * must not change is the VALUE of every column that existed before — including that the row set,
 * its order, and every evidence box are the same.
 *
 * <p>Volatile ids (row ids, page ids, layout element ids, text span ids) are rewritten to
 * placeholders in first-appearance order, exactly as {@code PackageExportApiIT} does; the helper
 * is repeated here rather than shared so this test reads top to bottom.
 */
class UngroupedFieldEquivalenceIT extends AbstractExtractionIT {

    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    /** {@code text_span.id} is a bigserial, so it arrives on the wire as a bare number. */
    private static final Pattern TEXT_SPAN_ID_PATTERN = Pattern.compile("\"textSpanId\":(\\d+)");

    /** Every extracted_field column that existed BEFORE V13, in V7 declaration order. */
    private static final List<String> V7_COLUMNS =
            List.of(
                    "data_type",
                    "displayed_text",
                    "raw_value",
                    "normalized_text",
                    "normalized_number",
                    "normalized_date",
                    "normalized_json",
                    "extraction_method",
                    "extractor_version",
                    "confidence",
                    "confidence_components",
                    "validation_status",
                    "review_status",
                    "is_sensitive",
                    "is_current");

    @Test
    void an_ungrouped_paystub_persists_exactly_what_it_persisted_before_v13() throws Exception {
        UUID packageId = insertPackage("equivalence-persistence-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);

        recordAndCompare(
                "extracted_fields_paystub_complete.txt",
                persistedProjection(onlyDocumentOf(packageId)));
    }

    @Test
    void the_fields_api_returns_exactly_what_it_returned_before_v13() throws Exception {
        UUID packageId = insertPackage("equivalence-api-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        MvcResult result =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andReturn();

        // The RAW response body, not a re-serialized parse tree: a parse round-trip would
        // silently normalize numeric scale (0.8100 → 0.81), which is precisely the kind of
        // wire change this test exists to catch.
        String body = normalizeIds(result.getResponse().getContentAsString());

        // 5b D10's ADDITIVE key, asserted here rather than folded into the golden. Every field of
        // an ungrouped schema reports NONE, and it sits immediately after the key it qualifies.
        assertThat(body)
                .as("groupKind is present on every ungrouped field, beside its null groupKey")
                .contains("\"groupKey\":null,\"groupKind\":\"NONE\"");
        assertThat(countOf(body, "\"groupKind\":"))
                .as("...on every field, not just the first")
                .isEqualTo(countOf(body, "\"groupKey\":"));

        // The SECOND additive key, on the same terms. The fixture's spans are all NATIVE, and every
        // paystub field is found, so every occurrence reports NATIVE with no engine.
        assertThat(body)
                .as("textProvenance is present on every field, beside its groupKind")
                .contains("\"groupKind\":\"NONE\",\"textProvenance\":{\"source\":\"NATIVE\","
                        + "\"ocrEngine\":null}");
        assertThat(countOf(body, "\"textProvenance\":"))
                .as("...on every field, not just the first")
                .isEqualTo(countOf(body, "\"groupKey\":"));

        // The THIRD additive key (D11): with no review decision anywhere in this run, every field
        // reads MACHINE, immediately after the reviewStatus it qualifies.
        assertThat(body)
                .as("effectiveStatus is present on every field, beside its reviewStatus")
                .contains("\"reviewStatus\":\"NOT_REVIEWED\",\"effectiveStatus\":\"MACHINE\"");
        assertThat(countOf(body, "\"effectiveStatus\":\"MACHINE\""))
                .as("...on every field, not just the first")
                .isEqualTo(countOf(body, "\"groupKey\":"));

        recordAndCompare(
                "fields_paystub_complete.json",
                withoutEffectiveStatus(withoutTextProvenance(withoutGroupKind(body))));
    }

    // ── the projection ──────────────────────────────────────────────────────

    /** Every current row of a document and its evidence, rendered line by line. */
    private String persistedProjection(UUID documentId) {
        StringBuilder rendered = new StringBuilder();
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT id, field_name, data_type, displayed_text, raw_value,
                               normalized_text, normalized_number, normalized_date,
                               normalized_json, extraction_method, extractor_version,
                               confidence, confidence_components, validation_status,
                               review_status, is_sensitive, is_current
                        FROM extracted_field
                        WHERE logical_document_id = ? AND is_current
                        ORDER BY field_name
                        """,
                        documentId);
        for (Map<String, Object> row : rows) {
            rendered.append("field ").append(row.get("field_name")).append('\n');
            for (String column : V7_COLUMNS) {
                rendered.append("  ")
                        .append(column)
                        .append('=')
                        .append(String.valueOf(row.get(column)))
                        .append('\n');
            }
            for (Map<String, Object> box :
                    jdbc.queryForList(
                            """
                            SELECT e.role, e.ordinal, e.x, e.y, e.width, e.height,
                                   p.package_page_index,
                                   (e.text_span_id IS NOT NULL) AS has_span,
                                   (e.layout_element_id IS NOT NULL) AS has_element
                            FROM field_evidence e JOIN page p ON p.id = e.page_id
                            WHERE e.extracted_field_id = ?
                            ORDER BY e.role, e.ordinal
                            """,
                            row.get("id"))) {
                rendered.append("  evidence ")
                        .append(box.get("role"))
                        .append('#')
                        .append(box.get("ordinal"))
                        .append(" page=")
                        .append(box.get("package_page_index"))
                        .append(" box=")
                        .append(box.get("x"))
                        .append(',')
                        .append(box.get("y"))
                        .append(',')
                        .append(box.get("width"))
                        .append(',')
                        .append(box.get("height"))
                        .append(" span=")
                        .append(box.get("has_span"))
                        .append(" element=")
                        .append(box.get("has_element"))
                        .append('\n');
            }
        }
        return rendered.toString();
    }

    // ── golden recording and comparison ─────────────────────────────────────

    /**
     * Writes what this run saw to {@code build/spec5a/<name>} and compares it with the committed
     * golden. Trailing whitespace is stripped from both so the committed file may end with the
     * newline git wants.
     */
    private void recordAndCompare(String name, String actual) throws Exception {
        Path recorded = Path.of(System.getProperty("user.dir"), "build", "spec5a");
        Files.createDirectories(recorded);
        Files.writeString(recorded.resolve(name), actual.strip() + "\n");
        String golden;
        try (InputStream stream =
                getClass().getResourceAsStream("/extraction/golden/" + name)) {
            assertThat(stream)
                    .as(
                            "golden %s is not recorded yet — copy build/spec5a/%s over"
                                    + " src/test/resources/extraction/golden/%s",
                            name, name, name)
                    .isNotNull();
            golden = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(actual.strip())
                .as("%s must be byte-identical before and after V13", name)
                .isEqualTo(golden.strip());
    }

    /**
     * Every volatile id in the response → a placeholder in first-appearance order: the UUID row,
     * page and layout-element ids, AND {@code textSpanId}.
     *
     * <p>The span id is the one that is not a UUID. {@code text_span.id} is a bigserial drawn from
     * a sequence in the ONE Testcontainers database the whole JVM shares, so its literal value
     * counts how many spans every test class that happened to run earlier inserted. Left raw, this
     * golden would stop being a record of the fields API and quietly become a detector of test-suite
     * composition — and since T3 through T9 each add span-inserting ITs, it would break on tasks
     * that changed nothing about this response, training the next executor to re-record the one
     * file whose whole value is that it is never re-recorded. What the equivalence claim needs is
     * that the same evidence row points at the same span, in the same order, which the placeholder
     * preserves exactly.
     */
    private static String normalizeIds(String json) {
        return normalizeTextSpanIds(normalizeUuids(json));
    }

    /**
     * Drops 5b's ADDITIVE {@code groupKind} member so the recorded golden stays byte-identical.
     *
     * <p>This is the one sanctioned normalization on this file's comparison and it is narrow on
     * purpose: the pattern matches the exact member and nothing else, and the member's presence and
     * value are asserted separately above — so nothing about {@code groupKind} is unobserved.
     *
     * <p>Why not simply re-record. The golden is the pre-V13 recording, and its whole value is that
     * it was never re-typed: it proves an ungrouped field's VALUES did not move. Re-recording it for
     * each additive key erodes that one property a byte at a time, and trains the next reader to
     * re-record the file whose point is that it is not re-recorded. An added key is not a changed
     * value, so it is normalized out and pinned by its own assertion instead. Fold it in at the next
     * COORDINATED re-record (the same event that adds {@code groupKind} to the canonical envelope),
     * not as a side effect of adding it here.
     */
    private static String withoutGroupKind(String json) {
        return json.replaceAll(",\"groupKind\":\"[A-Z]+\"", "");
    }

    /**
     * Drops the ADDITIVE {@code textProvenance} member, on exactly the terms {@link
     * #withoutGroupKind} states and for exactly the same reason.
     *
     * <p>This golden is the pre-V13 recording of an ungrouped field's VALUES. Text provenance adds
     * no value and moves none: it reports where characters the golden already pins came FROM. Were
     * it folded in, the file would stop being "what the API returned before V13" and become "what
     * the API returned as of the last additive key", which is a record of nothing. The member's
     * presence, its per-field count and its exact value are asserted above, before this runs — so
     * normalizing it out hides nothing. Fold both keys in at the next COORDINATED re-record.
     */
    private static String withoutTextProvenance(String json) {
        return json.replaceAll(
                ",\"textProvenance\":\\{\"source\":\"[A-Z]+\",\"ocrEngine\":(null|\"[A-Z+]+\")\\}",
                "");
    }

    /**
     * Drops the ADDITIVE {@code effectiveStatus} member (design D11), on exactly the terms {@link
     * #withoutGroupKind} states and for exactly the same reason.
     *
     * <p>This golden is the pre-V13 recording of an ungrouped field's VALUES, and this run has no
     * review decisions, so the member's only possible value here is {@code MACHINE} — asserted
     * per-field above, before this runs. The member exists so a REJECTED value stops being
     * consumable as current (§11.1); it adds no value and moves none. Exact-member-anchored like
     * its two predecessors so it can never eat a future member. Fold all three keys in at the next
     * COORDINATED re-record.
     */
    private static String withoutEffectiveStatus(String json) {
        return json.replaceAll(",\"effectiveStatus\":\"[A-Z]+\"", "");
    }

    private static int countOf(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /** Volatile row ids → sequential placeholders in first-appearance order. */
    private static String normalizeUuids(String json) {
        Map<String, String> replacements = new LinkedHashMap<>();
        Matcher matcher = UUID_PATTERN.matcher(json);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            String placeholder =
                    replacements.computeIfAbsent(
                            matcher.group(), id -> "uuid-" + (replacements.size() + 1));
            matcher.appendReplacement(normalized, placeholder);
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    /** Bigserial span ids → {@code "span-N"} placeholders, same first-appearance rule. */
    private static String normalizeTextSpanIds(String json) {
        Map<String, String> replacements = new LinkedHashMap<>();
        Matcher matcher = TEXT_SPAN_ID_PATTERN.matcher(json);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            String placeholder =
                    replacements.computeIfAbsent(
                            matcher.group(1), id -> "span-" + (replacements.size() + 1));
            matcher.appendReplacement(normalized, "\"textSpanId\":\"" + placeholder + "\"");
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }
}
