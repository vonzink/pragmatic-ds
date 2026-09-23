package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /v1/packages/{id}/export — the brief's export shape. The golden test is ENGINE-DEPENDENT:
 * the committed golden JSON is the expected POST-MERGE content derived from truth, so under the
 * placeholder engine it fails on extractionMethod NONE / null values, and turns green when the
 * real engine lands. The 404 tests are green now.
 */
class PackageExportApiIT extends AbstractExtractionIT {

    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    /** Members added AFTER the golden was recorded. Each is asserted, then normalized out. */
    private static final java.util.Set<String> ADDITIVE_KEYS =
            java.util.Set.of(
                    "groupKind",
                    "textProvenance",
                    "effectiveStatus",
                    "boundaryProvenance",
                    "absorbedUntypedPages");

    @Test
    void the_complete_paystub_export_matches_the_committed_golden() throws Exception {
        UUID packageId = insertPackage("export-golden-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);

        MvcResult result =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode parsed =
                JSON.readTree(normalizeUuids(result.getResponse().getContentAsString()));

        // Phase B's ADDITIVE key, document-level, asserted before it is normalized away: a
        // single-document upload's one document opens the package — its boundary was decided by
        // nothing and must say so, not claim an inference that never happened.
        assertThat(parsed.get("documents").get(0).path("boundaryProvenance").asText(null))
                .isEqualTo("PACKAGE_START");
        // Issue #60's ADDITIVE key, likewise: the fixture's one page classified as its type, so
        // the splitter absorbed nothing — 0, and a 0 that is WRITTEN (not null, which would mean
        // "never counted").
        assertThat(parsed.get("documents").get(0).path("absorbedUntypedPages").asInt(-1))
                .isEqualTo(0);

        // 5b D10's ADDITIVE key, asserted before it is normalized away: a paystub's scalars say
        // NONE, which is what lets a consumer tell "does not repeat" from "repeats, and this
        // occurrence's key could not be read" — and paystub@1.5.0's five earning* fields say ROW
        // on their null-keyed placeholder, which is exactly that second reading.
        for (JsonNode field : parsed.get("documents").get(0).get("fields")) {
            String name = field.path("fieldName").asText();
            boolean earningsLine = name.startsWith("earning");
            assertThat(field.path("groupKind").asText(null))
                    .as("groupKind on %s", name)
                    .isEqualTo(earningsLine ? "ROW" : "NONE");
            // The same treatment for text provenance: asserted before it is normalized away. The
            // fixture's spans are all NATIVE and every rule-read paystub field is found, so this
            // is the whole-fixture control — an export that started reporting OCR here would mean
            // the read path had begun inventing a provenance rather than reading one. The eight
            // AI-only fields (V53) are MISSING on a rules-only run: no span backs them, UNKNOWN.
            boolean missing = "NONE".equals(field.path("extractionMethod").asText(null));
            assertThat(field.path("textProvenance").path("source").asText(null))
                    .as("textProvenance.source on %s", name)
                    .isEqualTo(missing ? "UNKNOWN" : "NATIVE");
            assertThat(field.path("textProvenance").path("ocrEngine").isNull())
                    .as("no engine on a native value (%s)", name)
                    .isTrue();
            // D11's effectiveStatus, same treatment: this run makes no review decision, so every
            // row must read MACHINE — the floor value a consumer may treat as "the machine's own
            // parse, untouched". REJECTED/CORRECTED are pinned by EffectiveStatusIT.
            assertThat(field.path("effectiveStatus").asText(null))
                    .as("effectiveStatus on %s", name)
                    .isEqualTo("MACHINE");
        }

        JsonNode actual = canonicalize(withoutAdditiveKeys(parsed));
        // Recorded on every run, the way UngroupedFieldEquivalenceIT records its goldens: a
        // COORDINATED re-record (a schema bump that adds fields, say) copies this file over
        // src/test/resources/extraction/golden/export_paystub_complete.json rather than hand-editing
        // a golden nobody ran.
        java.nio.file.Path recorded =
                java.nio.file.Path.of(System.getProperty("user.dir"), "build", "spec5a");
        java.nio.file.Files.createDirectories(recorded);
        java.nio.file.Files.writeString(
                recorded.resolve("export_paystub_complete.json"), actual.toPrettyString() + "\n");
        JsonNode golden;
        try (InputStream stream =
                getClass().getResourceAsStream("/extraction/golden/export_paystub_complete.json")) {
            golden = canonicalize(JSON.readTree(stream));
        }
        assertThat(actual.toPrettyString()).isEqualTo(golden.toPrettyString());
    }

    @Test
    void an_unknown_package_id_is_not_found() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}/export", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void another_orgs_package_is_indistinguishable_from_a_nonexistent_one() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package for export");

        MvcResult result =
                mockMvc.perform(get("/v1/packages/{id}/export", foreignPackage))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("foreign package for export");
    }

    // ── golden normalization ────────────────────────────────────────────────

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

    /**
     * Drops the ADDITIVE members — 5b's {@code groupKind} and text provenance's
     * {@code textProvenance} — so the committed golden stays byte-identical. Each member's value is
     * asserted above, per field, before this runs; nothing about either goes unobserved. Fold them
     * into the golden at the next COORDINATED re-record (the envelope bump that adds them there
     * too), not as a side effect of adding them to the read models.
     */
    private static JsonNode withoutAdditiveKeys(JsonNode root) {
        if (root.isObject()) {
            var object = JSON.createObjectNode();
            root.properties()
                    .forEach(
                            entry -> {
                                if (!ADDITIVE_KEYS.contains(entry.getKey())) {
                                    object.set(
                                            entry.getKey(), withoutAdditiveKeys(entry.getValue()));
                                }
                            });
            return object;
        }
        if (root.isArray()) {
            var array = JSON.createArrayNode();
            root.forEach(child -> array.add(withoutAdditiveKeys(child)));
            return array;
        }
        return root;
    }

    /**
     * Rewrites every numeric node to its trailing-zero-stripped decimal so 0.9 and 0.9000 (and
     * 4670.69 vs 4670.6900) compare equal — DB scale is an artifact, not export content.
     */
    private static JsonNode canonicalize(JsonNode node) {
        if (node.isNumber()) {
            BigDecimal stripped = node.decimalValue().stripTrailingZeros();
            return JSON.getNodeFactory().numberNode(new BigDecimal(stripped.toPlainString()));
        }
        if (node.isObject()) {
            var object = JSON.createObjectNode();
            node.properties().forEach(entry -> object.set(entry.getKey(), canonicalize(entry.getValue())));
            return object;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return node;
    }
}
