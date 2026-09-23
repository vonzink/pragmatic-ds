package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /v1/documents/{id}/fields.md} — DOCENGINE-MD-1 over the committed Schedule E fixture.
 *
 * <p>The golden is the determinism proof AND the regression guard: it pins every byte of a
 * two-page, 67-occurrence document, so any change to a table, an ordering rule, a mask, a cell
 * format or the front matter shows up as a diff rather than as a quiet drift. It is RECORDED, never
 * typed — the test writes what it saw to {@code build/md/} on every run, and recording copies that
 * file over the committed one.
 *
 * <p>Volatile ids (package, document, page) are rewritten to placeholders in first-appearance
 * order, the same technique {@code PackageExportApiIT} and {@code UngroupedFieldEquivalenceIT} use.
 * Nothing else in the rendering varies — there is no timestamp, no hostname and no duration in it,
 * which is exactly what makes a byte-exact golden possible.
 */
class DocumentMarkdownApiIT extends AbstractExtractionIT {

    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    /** The fixture's raw SSN. It must not appear in the rendered bytes, in any arm. */
    private static final String RAW_SSN = "987-65-4321";

    /** 8 ungrouped + 15 Part I + 3 addresses + 28 Part II + 10 Part III + 3 Part IV. */
    private static final int SCHEDULE_E_OCCURRENCES = 67;

    private UUID scheduleEDocument() {
        UUID packageId = insertPackage("markdown-api-it");
        insertFixturePages(packageId, "schedule_e");
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
    }

    @Test
    void the_rendered_markdown_is_byte_for_byte_the_committed_golden() throws Exception {
        UUID documentId = scheduleEDocument();

        MvcResult result =
                mockMvc.perform(get("/v1/documents/{id}/fields.md", documentId))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                        .andExpect(
                                header().string(
                                                HttpHeaders.CACHE_CONTROL,
                                                "private, max-age=0, must-revalidate"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andReturn();

        String rendered = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // DETERMINISM, half one: the same document renders the same bytes on a second request,
        // through a second controller invocation, against the same rows.
        String again =
                mockMvc.perform(get("/v1/documents/{id}/fields.md", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(again).as("same document, same bytes — twice").isEqualTo(rendered);

        // DETERMINISM, half two: those bytes are the ones committed.
        recordAndCompare("schedule_e.md", normalizeUuids(rendered));
    }

    @Test
    void every_occurrence_reaches_the_rendering_and_every_missing_one_says_so() throws Exception {
        String rendered = markdownOf(scheduleEDocument());

        // The appendix is one row per occurrence, all 67 — a renderer that dropped missing ones
        // would recreate confident-partial output on the surface humans actually read.
        assertThat(countOf(rendered, "| NOT_VALIDATED |") + countOf(rendered, "| MANUAL_REVIEW_REQUIRED |"))
                .as("one appendix row per occurrence")
                .isEqualTo(SCHEDULE_E_OCCURRENCES);
        // Every MISSING occurrence is visible somewhere above the appendix: as a missing CELL in
        // its table, or — for the one whose region was never located, which belongs to no table —
        // as a region-not-read callout. Nothing is silently dropped and nothing renders blank.
        assertThat(countOf(rendered, "— missing (review)") + countOf(rendered, "## Region not read\n\n> **`"))
                .as("22 missing cells + 1 region-not-read callout = 23 missing occurrences")
                .isEqualTo(countOf(rendered, "| MANUAL_REVIEW_REQUIRED |"));
        assertThat(countOf(rendered, "— missing (review)")).isEqualTo(22);

        // Part I column C is empty on the fixture: five money lines, five missing cells.
        assertThat(rendered)
                .contains("| Field | A | B | C |")
                // Every money cell carries its normalized arm beside the glyphs the form printed,
                // and its own scale-4 confidence (0.81 = 1 · 0.9 · 0.9 — the money normalizer is
                // not certain, and the cell says so rather than rounding the doubt away).
                .contains("| `rentsReceived` | 44,400 = 44400 (0.8100, p1, NATIVE) |"
                        + " 29,700 = 29700 (0.8100, p1, NATIVE) | — missing (review) |")
                // The loss carries its sign, beside the glyphs the form printed.
                .contains("( 18,470 ) = -18470 (0.8100, p1, NATIVE)")
                // Part II reads DOWN, one row per printed letter, C and D blank but present.
                .contains("| Key | `partnershipEin` |")
                .contains("| A | 27-1234567 (0.9000, p2, NATIVE) |");
    }

    /**
     * T9's warning, as a byte assertion. {@code remicExcessInclusion}'s region was never located,
     * so its one occurrence has no key — and it must appear as a region-not-read callout, never as
     * a document-level field row and never as a row of any Part IV table.
     */
    @Test
    void the_null_keyed_grouped_field_is_isolated_from_every_table() throws Exception {
        String rendered = markdownOf(scheduleEDocument());

        String documentFields =
                rendered.substring(
                        rendered.indexOf("## Document fields"),
                        rendered.indexOf("## Estate Or Trust"));
        assertThat(documentFields)
                .as("it is NOT a document-level field")
                .doesNotContain("remicExcessInclusion");
        String appendixStart = "## Occurrence detail";
        assertThat(rendered.substring(0, rendered.indexOf(appendixStart)))
                .as("...and appears above the appendix exactly once — as the callout")
                .containsOnlyOnce("remicExcessInclusion");
        assertThat(rendered)
                .contains("## Region not read")
                .contains("> **`remicExcessInclusion`** — the engine located no readable ROW group")
                .contains("| remicExcessInclusion | ∅ | ROW | MISSING — region not read |");
    }

    /**
     * The provenance column, on the surface a human actually reads. The fixture is wholly native,
     * so every found occurrence says NATIVE and every missing one says UNKNOWN — and the count of
     * the two together is the occurrence count, which is what proves the column is on EVERY row
     * rather than on the ones that happened to be convenient.
     */
    @Test
    void every_occurrence_states_where_its_characters_came_from() throws Exception {
        String rendered = markdownOf(scheduleEDocument());
        // Counted in the APPENDIX ONLY. The ungrouped table carries a Text column too, so a
        // whole-document count is 8 higher than the occurrence count and would silently pass a
        // renderer that had stopped emitting the column on half the appendix rows.
        String appendix = rendered.substring(rendered.indexOf("## Occurrence detail"));

        assertThat(countOf(appendix, "| NATIVE |") + countOf(appendix, "| UNKNOWN |"))
                .as("one Text cell per appendix row, all 67")
                .isEqualTo(SCHEDULE_E_OCCURRENCES);
        assertThat(countOf(appendix, "| UNKNOWN |"))
                .as("a missing occurrence cites no span, so it cannot claim a text layer")
                .isEqualTo(countOf(appendix, "| MANUAL_REVIEW_REQUIRED |"));
        assertThat(rendered)
                .as("the reader is told what the column means, in the same words the API uses")
                .contains("`Text` is where the value's characters came from")
                .contains("It is NOT a confidence component.")
                .as("...and the ungrouped table carries it too, not just the appendix")
                .contains("| Field | Value | Text | Confidence | Page |");
    }

    @Test
    void the_sensitive_value_is_masked_in_every_arm_of_the_rendering() throws Exception {
        String rendered = markdownOf(scheduleEDocument());

        assertThat(rendered)
                .as("the raw SSN appears nowhere in the bytes — asserted byte-wise, not visually")
                .doesNotContain(RAW_SSN);
        assertThat(rendered)
                .contains("| `taxpayerSsn` (sensitive, masked) | •••-••-4321 |")
                .contains("| taxpayerSsn | ∅ | NONE | FOUND | •••-••-4321 | •••-••-4321 |");
    }

    /**
     * D11's Markdown half, end to end: this surface is a copy-paste/LLM artifact with no
     * machine-readable contract, so the only binding form of "a human refused this value" is the
     * value's ABSENCE — a rejected occurrence renders a rejected cell, never its text.
     */
    @Test
    void a_rejected_field_never_prints_its_text_end_to_end() throws Exception {
        UUID documentId = scheduleEDocument();
        Map<String, Object> row = currentOccurrences(documentId).get("rentsReceived#A");
        String rejectedText = (String) row.get("displayed_text");
        assertThat(rejectedText).as("property A's rents are captured on the fixture").isNotNull();

        mockMvc.perform(
                        patch("/v1/fields/{id}", row.get("id"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"REJECT\",\"reason\":\"wrong property\"}"))
                .andExpect(status().isOk());

        String rendered = markdownOf(documentId);
        assertThat(rendered)
                .contains("— rejected (review)")
                .contains("Status `REJECTED` means a reviewer refused the served value")
                .as("the refused text is byte-absent — cells, appendix, everywhere")
                .doesNotContain(rejectedText);
    }

    @Test
    void the_etag_answers_304_on_revalidation() throws Exception {
        UUID documentId = scheduleEDocument();

        String etag =
                mockMvc.perform(get("/v1/documents/{id}/fields.md", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotNull();

        mockMvc.perform(
                        get("/v1/documents/{id}/fields.md", documentId)
                                .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void an_unknown_or_foreign_document_is_an_opaque_404() throws Exception {
        mockMvc.perform(get("/v1/documents/{id}/fields.md", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        UUID foreignPackage = UUID.randomUUID();
        UUID foreignDocument = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package for markdown");
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)"
                        + " VALUES (?, ?, ?, 0, 'SCHEDULE_E')",
                foreignDocument,
                ORG_OTHER,
                foreignPackage);

        mockMvc.perform(get("/v1/documents/{id}/fields.md", foreignDocument))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String markdownOf(UUID documentId) throws Exception {
        return mockMvc.perform(get("/v1/documents/{id}/fields.md", documentId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Writes what this run saw to {@code build/md/<name>} and compares it with the committed
     * golden, byte for byte — no strip, no trim: a trailing-newline change IS a contract change
     * for a format whose whole promise is byte determinism.
     */
    private void recordAndCompare(String name, String actual) throws Exception {
        Path recorded = Path.of(System.getProperty("user.dir"), "build", "md");
        Files.createDirectories(recorded);
        Files.writeString(recorded.resolve(name), actual);
        String golden;
        try (InputStream stream = getClass().getResourceAsStream("/extraction/markdown/" + name)) {
            assertThat(stream)
                    .as(
                            "golden %s is not recorded yet — copy build/md/%s over"
                                    + " app/src/test/resources/extraction/markdown/%s",
                            name, name, name)
                    .isNotNull();
            golden = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(actual).as("%s must be byte-identical to the committed golden", name).isEqualTo(golden);
    }

    /** Volatile row ids → sequential placeholders in first-appearance order. */
    private static String normalizeUuids(String markdown) {
        Map<String, String> replacements = new LinkedHashMap<>();
        Matcher matcher = UUID_PATTERN.matcher(markdown);
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

    private static int countOf(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
