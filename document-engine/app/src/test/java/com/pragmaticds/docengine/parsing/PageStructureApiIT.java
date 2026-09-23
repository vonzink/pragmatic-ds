package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P2.1/P2.2 — {@code GET /v1/pages/{id}/structure}, the L2 wire (full-capture design §6.2).
 *
 * <p>L2 is the INDEX into the record: what the geometry supports without any schema — tables with
 * {@code (row, col)} addressing, visual blocks, pixel marks — each holding the span ids that lead
 * back down to L1. It is a read model over {@code layout_element} exactly as persisted (design D7:
 * tables need NO new detection), so every expected number below is derived from the committed
 * fixture's own truth file, never from what the code emits.
 *
 * <p>Confidence at L2 is TWO separate numbers and never their product (design D10):
 * {@code structureConfidence} is the element's own stored confidence, {@code textConfidence} the
 * MINIMUM over member spans — the same rule as L3's {@code spanConfidence}, so the one axis the
 * layers share stays commensurable. {@code anchorStrength} and {@code normalizerCertainty} are L3
 * vocabulary and must never appear here; acceptance 11 asserts that as a negative.
 */
class PageStructureApiIT extends AbstractExtractionIT {

    private static final String STRUCTURE_CONTRACT = "DOCENGINE-L2-1/1.0.0";

    // ── acceptance 8: the fixture table, addressed exactly ──────────────────

    @Test
    void the_fixture_page_returns_one_table_whose_cells_match_the_truth_grid_exactly()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);

        JsonNode body = json(ok(structure(pageId)));

        assertThat(body.path("pageId").asText()).isEqualTo(pageId.toString());
        assertThat(body.path("widthPt").asDouble()).isEqualTo(612.0);
        assertThat(body.path("heightPt").asDouble()).isEqualTo(792.0);
        assertThat(body.path("rotation").asInt()).isZero();
        assertThat(body.path("structureContract").asText()).isEqualTo(STRUCTURE_CONTRACT);

        // The truth file's own census: every word carrying a "cell" marker, keyed by (row, col).
        Map<String, JsonNode> truthCells = truthCells("paystub_complete");
        int truthRows = 1 + truthCells.keySet().stream().mapToInt(k -> row(k)).max().orElseThrow();
        int truthCols = 1 + truthCells.keySet().stream().mapToInt(k -> col(k)).max().orElseThrow();
        assertThat(truthRows).isEqualTo(5);
        assertThat(truthCols).isEqualTo(5);
        assertThat(truthCells).hasSize(25);

        assertThat(body.path("tables")).hasSize(1);
        JsonNode table = body.path("tables").get(0);
        assertThat(table.path("rows").asInt()).isEqualTo(truthRows);
        assertThat(table.path("cols").asInt()).isEqualTo(truthCols);
        assertThat(table.path("ruled").asBoolean()).isFalse();
        assertThat(table.path("structureConfidence").decimalValue())
                .isEqualByComparingTo("0.7500");
        assertThat(table.path("id").asText()).isNotBlank();
        for (String member : List.of("x", "y", "width", "height")) {
            assertThat(table.path("box").has(member)).as("table box carries %s", member).isTrue();
        }

        assertThat(table.path("cells")).hasSize(truthCells.size());
        for (JsonNode cell : table.path("cells")) {
            String key = cell.path("row").asInt() + "," + cell.path("col").asInt();
            JsonNode word = truthCells.remove(key);
            assertThat(word).as("cell (%s) exists in the fixture's declared grid", key).isNotNull();
            assertThat(cell.path("text").asText()).isEqualTo(word.get("text").asText());
            for (String member : List.of("x", "y", "width", "height")) {
                assertThat(cell.path("box").path(member).decimalValue())
                        .as("cell (%s) box %s", key, member)
                        .isEqualByComparingTo(word.get(member).decimalValue());
            }
            assertThat(cell.path("spanIds")).hasSize(1);
            assertThat(cell.path("spanIds").get(0).asLong())
                    .isEqualTo(spanIdAt(pageId, word));
            assertThat(cell.path("textConfidence").decimalValue())
                    .isEqualByComparingTo("1.0000");
        }
        assertThat(truthCells).as("every declared cell was served exactly once").isEmpty();
    }

    // ── acceptance 9: the containsSpan resolver, the walk's upward leg ──────

    @Test
    void containsSpan_of_the_rate_span_returns_the_table_and_identifies_cell_1_1()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        long rateSpan = spanIdOfText(pageId, "48.0771");

        JsonNode body = json(ok(structure(pageId).param("containsSpan", String.valueOf(rateSpan))));

        assertThat(body.path("tables")).hasSize(1);
        assertThat(body.path("blocks")).isEmpty();
        assertThat(body.path("marks")).isEmpty();

        List<JsonNode> owning = new ArrayList<>();
        for (JsonNode cell : body.path("tables").get(0).path("cells")) {
            for (JsonNode id : cell.path("spanIds")) {
                if (id.asLong() == rateSpan) {
                    owning.add(cell);
                }
            }
        }
        assertThat(owning).as("exactly one cell owns the span").hasSize(1);
        assertThat(owning.get(0).path("row").asInt()).isEqualTo(1);
        assertThat(owning.get(0).path("col").asInt()).isEqualTo(1);

        // Anchored to the truth file, not to the bridge: the fixture itself declares 48.0771 at
        // (1, 1), so the assertion above measures the fixture's grid and not this test's plumbing.
        JsonNode truthWord = truthCells("paystub_complete").get("1,1");
        assertThat(truthWord.get("text").asText()).isEqualTo("48.0771");
    }

    @Test
    void containsSpan_of_an_unknown_span_is_an_empty_structure_list_not_an_error()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);

        JsonNode body = json(ok(structure(pageId).param("containsSpan", "999999999")));

        assertThat(body.path("tables")).isEmpty();
        assertThat(body.path("blocks")).isEmpty();
        assertThat(body.path("marks")).isEmpty();
    }

    // ── acceptance 11: two confidences, never their product ─────────────────

    /**
     * The negative test the design demands. A block whose {@code structureConfidence} is 0.9000 and
     * whose weakest member span is 0.6200 must serve BOTH numbers and never 0.5580 — the product —
     * anywhere in the body. And the L3 component names must not appear at all: L2 has no anchor and
     * no normalizer, so an {@code anchorStrength} here would be a lie with four decimal places.
     */
    @Test
    void no_l2_response_carries_a_confidence_product_or_l3_component_names() throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        List<Long> spanIds = spanIdsInOrder(pageId);
        jdbc.update("UPDATE text_span SET confidence = 0.6200 WHERE id = ?", spanIds.get(1));
        insertBlock(pageId, "PARAGRAPH", "0.9000", spanIds.subList(0, 2));

        String raw =
                ok(structure(pageId)).getResponse().getContentAsString();
        JsonNode block = json(raw).path("blocks").get(0);

        assertThat(block.path("structureConfidence").decimalValue())
                .isEqualByComparingTo("0.9000");
        assertThat(block.path("textConfidence").decimalValue()).isEqualByComparingTo("0.6200");
        assertThat(raw)
                .doesNotContain("anchorStrength")
                .doesNotContain("normalizerCertainty")
                .doesNotContain("confidenceComponents")
                // 0.9000 × 0.6200 — the one number this response could only contain by multiplying.
                .doesNotContain("0.5580")
                .doesNotContain("0.558");
    }

    @Test
    void text_confidence_is_the_minimum_over_member_spans_matching_l3s_span_rule()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        List<Long> spanIds = spanIdsInOrder(pageId);
        jdbc.update("UPDATE text_span SET confidence = 0.9100 WHERE id = ?", spanIds.get(0));
        jdbc.update("UPDATE text_span SET confidence = 0.4300 WHERE id = ?", spanIds.get(1));
        jdbc.update("UPDATE text_span SET confidence = 0.8800 WHERE id = ?", spanIds.get(2));
        insertBlock(pageId, "PARAGRAPH", "0.9000", spanIds.subList(0, 3));

        JsonNode block = json(ok(structure(pageId))).path("blocks").get(0);

        // One shaky word taints the whole value — L3's rule, applied verbatim at L2.
        assertThat(block.path("textConfidence").decimalValue()).isEqualByComparingTo("0.4300");
    }

    // ── acceptance 12: ruled, unruled, rotated ──────────────────────────────

    @Test
    void a_ruled_table_serves_the_workers_ruling_confirmed_confidence() throws Exception {
        UUID pageId = fixturePage("ruled_table", true);

        JsonNode table = json(ok(structure(pageId))).path("tables").get(0);

        assertThat(table.path("ruled").asBoolean()).isTrue();
        assertThat(table.path("structureConfidence").decimalValue())
                .isEqualByComparingTo("0.9500");
    }

    @Test
    void an_unruled_table_serves_the_honest_unconfirmed_confidence() throws Exception {
        UUID pageId = fixturePage("unruled_table", false);

        JsonNode table = json(ok(structure(pageId))).path("tables").get(0);

        assertThat(table.path("ruled").asBoolean()).isFalse();
        assertThat(table.path("structureConfidence").decimalValue())
                .isEqualByComparingTo("0.7500");
    }

    /**
     * A rotated page can never claim ruled confirmation. The guarantee itself lives in the worker —
     * {@code rulings.py} skips any page with a nonzero {@code /Rotate} entirely, so ruling
     * confirmation is structurally unreachable there — and the seeded tree mirrors what that worker
     * persists for {@code paystub_complete_rot90}: unruled, 0.75. What THIS test pins is the read
     * model's half of the promise: the stored 0.75 arrives on the wire as 0.7500 with
     * {@code ruled: false} and the page's rotation beside it, un-averaged and un-improved.
     */
    @Test
    void a_rotated_page_serves_unruled_confidence_and_its_own_rotation() throws Exception {
        UUID pageId = fixturePage("paystub_complete_rot90", false);

        JsonNode body = json(ok(structure(pageId)));

        assertThat(body.path("rotation").asInt()).isEqualTo(90);
        JsonNode table = body.path("tables").get(0);
        assertThat(table.path("ruled").asBoolean()).isFalse();
        assertThat(table.path("structureConfidence").decimalValue())
                .isEqualByComparingTo("0.7500");
    }

    // ── blocks, marks, and the D11 empty cell ───────────────────────────────

    @Test
    void blocks_carry_kind_text_box_and_member_spans_in_link_order() throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        List<Long> spanIds = spanIdsInOrder(pageId);
        // Linked deliberately out of page order: the element's own link order is the contract.
        UUID blockId =
                insertBlock(pageId, "HEADER", "0.9000", List.of(spanIds.get(2), spanIds.get(0)));

        JsonNode body = json(ok(structure(pageId)));

        assertThat(body.path("blocks")).hasSize(1);
        JsonNode block = body.path("blocks").get(0);
        assertThat(block.path("id").asText()).isEqualTo(blockId.toString());
        assertThat(block.path("kind").asText()).isEqualTo("HEADER");
        assertThat(block.path("text").asText()).isNotBlank();
        assertThat(block.path("spanIds").get(0).asLong()).isEqualTo(spanIds.get(2));
        assertThat(block.path("spanIds").get(1).asLong()).isEqualTo(spanIds.get(0));
        assertThat(block.path("structureConfidence").decimalValue())
                .isEqualByComparingTo("0.9000");
    }

    @Test
    void a_form_field_is_served_as_a_block_and_owns_its_spans() throws Exception {
        // The worker's pair detector (#63) emits a label and its figure as one FORM_FIELD.
        // It is a block here: a paired line must not vanish from the structure or from the
        // containsSpan owner walk.
        UUID pageId = fixturePage("paystub_complete", false);
        List<Long> spanIds = spanIdsInOrder(pageId);
        UUID blockId =
                insertBlock(pageId, "FORM_FIELD", "0.9000", List.of(spanIds.get(0), spanIds.get(1)));

        JsonNode body = json(ok(structure(pageId)));

        assertThat(body.path("blocks")).hasSize(1);
        JsonNode block = body.path("blocks").get(0);
        assertThat(block.path("id").asText()).isEqualTo(blockId.toString());
        assertThat(block.path("kind").asText()).isEqualTo("FORM_FIELD");
        assertThat(block.path("spanIds").get(0).asLong()).isEqualTo(spanIds.get(0));

        JsonNode owner =
                json(ok(structure(pageId).param("containsSpan", String.valueOf(spanIds.get(1)))));
        assertThat(owner.path("blocks")).hasSize(1);
        assertThat(owner.path("blocks").get(0).path("id").asText()).isEqualTo(blockId.toString());
    }

    @Test
    void marks_carry_the_pixel_detectors_own_confidence_and_the_checkbox_state()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        insertMark(pageId, "CHECKBOX", "0.9300", "{\"checked\": true, \"fillRatio\": 0.31}");
        insertMark(pageId, "SIGNATURE", "0.9000", "{\"inkFraction\": 0.11}");

        JsonNode marks = json(ok(structure(pageId))).path("marks");

        assertThat(marks).hasSize(2);
        JsonNode checkbox = marks.get(0);
        assertThat(checkbox.path("kind").asText()).isEqualTo("CHECKBOX");
        assertThat(checkbox.path("structureConfidence").decimalValue())
                .isEqualByComparingTo("0.9300");
        assertThat(checkbox.path("checked").asBoolean()).isTrue();
        assertThat(checkbox.path("spanIds")).isEmpty();
        assertThat(checkbox.path("textConfidence").isNull())
                .as("no member spans, no text confidence — null, not 1.0")
                .isTrue();
        JsonNode signature = marks.get(1);
        assertThat(signature.path("kind").asText()).isEqualTo("SIGNATURE");
        assertThat(signature.path("checked").isNull())
                .as("checked is a checkbox fact; a signature does not fake one")
                .isTrue();
    }

    /**
     * Design D11: "missing" is an L3 concept. An empty cell is {@code text: ""} with no member
     * spans — the cell is blank ON THE PAGE, which is a statement about the page, not a
     * failure to find something.
     */
    @Test
    void an_empty_cell_is_blank_text_with_no_spans_not_an_absence() throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        UUID rowId =
                jdbc.queryForObject(
                        "SELECT id FROM layout_element WHERE page_id = ? AND element_type ="
                                + " 'TABLE_ROW' ORDER BY ordinal LIMIT 1",
                        UUID.class,
                        pageId);
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, parent_element_id, element_type,
                    ordinal, x, y, width, height, confidence, detector, detector_version, attributes)
                VALUES (?, ?, ?, ?, 'TABLE_CELL', 999, 590, 168, 10, 12, 0.75, 'clustering', 'it',
                        '{"row": 0, "col": 5}'::jsonb)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                pageId,
                rowId);

        JsonNode table = json(ok(structure(pageId))).path("tables").get(0);

        JsonNode empty = null;
        for (JsonNode cell : table.path("cells")) {
            if (cell.path("row").asInt() == 0 && cell.path("col").asInt() == 5) {
                empty = cell;
            }
        }
        assertThat(empty).isNotNull();
        assertThat(empty.path("text").asText()).isEmpty();
        assertThat(empty.path("spanIds")).isEmpty();
        assertThat(empty.path("textConfidence").isNull()).isTrue();
    }

    // ── detectorCoverage: the honesty marker ────────────────────────────────

    /**
     * Acceptance 10 — BOTH states pinned in one test, so the {@code UNKNOWN} → truth flip is
     * deliberate rather than incidental.
     *
     * <p>BEFORE (a page whose parse predates the persisted declaration — {@code
     * layout_not_implemented} NULL, element tree present): nobody can say whether the pixel
     * detectors looked, because {@code notImplemented} was parsed off the worker wire and
     * dropped. {@code UNKNOWN} is the only honest value — materially different from {@code RAN}
     * with an empty result. The span-geometry detectors read {@code RAN} here ONLY because the
     * seeded element tree proves a layout response was persisted for this page, and no worker
     * version that ever shipped can produce one without running clustering and table detection.
     * A page with neither declaration nor elements proves nothing — see
     * {@link #a_rendered_but_never_parsed_page_reads_unknown_for_every_detector_family}.
     *
     * <p>AFTER (P2.4 persists the declaration): the truth, verbatim. {@code []} = the worker
     * looked for everything, so {@code RAN}; a type IN the list = the worker declared it did not
     * look, so {@code NOT_IMPLEMENTED} — which is a statement about that parse, not a guess.
     */
    @Test
    void detector_coverage_reads_unknown_before_the_declaration_and_the_truth_after()
            throws Exception {
        // BEFORE: no declaration persisted.
        UUID undeclared = fixturePage("paystub_complete", false);
        JsonNode before = json(ok(structure(undeclared))).path("detectorCoverage");
        List<String> keys = new ArrayList<>();
        before.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("table", "block", "checkbox", "signature");
        assertThat(before.path("table").asText()).isEqualTo("RAN");
        assertThat(before.path("block").asText()).isEqualTo("RAN");
        assertThat(before.path("checkbox").asText()).isEqualTo("UNKNOWN");
        assertThat(before.path("signature").asText()).isEqualTo("UNKNOWN");

        // AFTER: the worker declared full coverage — the way today's pixel path leaves a page.
        UUID declaredAll = fixturePage("paystub_complete", false);
        jdbc.update(
                "UPDATE page SET layout_not_implemented = '[]'::jsonb WHERE id = ?", declaredAll);
        JsonNode ran = json(ok(structure(declaredAll))).path("detectorCoverage");
        assertThat(ran.path("table").asText()).isEqualTo("RAN");
        assertThat(ran.path("block").asText()).isEqualTo("RAN");
        assertThat(ran.path("checkbox").asText()).isEqualTo("RAN");
        assertThat(ran.path("signature").asText()).isEqualTo("RAN");

        // AFTER: the worker declared the pixel detectors did not run (e.g. no raster for the
        // page's index) — the truth is "did not look", not "looked and found none".
        UUID declaredSkipped = fixturePage("paystub_complete", false);
        jdbc.update(
                "UPDATE page SET layout_not_implemented = '[\"CHECKBOX\", \"SIGNATURE\"]'::jsonb"
                        + " WHERE id = ?",
                declaredSkipped);
        JsonNode skipped = json(ok(structure(declaredSkipped))).path("detectorCoverage");
        assertThat(skipped.path("table").asText()).isEqualTo("RAN");
        assertThat(skipped.path("block").asText()).isEqualTo("RAN");
        assertThat(skipped.path("checkbox").asText()).isEqualTo("NOT_IMPLEMENTED");
        assertThat(skipped.path("signature").asText()).isEqualTo("NOT_IMPLEMENTED");
    }

    /**
     * The state every package passes through — and a package whose PARSING failed never leaves:
     * page rows commit at RENDERING, stages commit in separate transactions, and this endpoint
     * has no stage guard. With no declaration AND no element tree, nothing proves any layout call
     * ever happened for this page, so {@code RAN} for table/block would be a fabricated coverage
     * claim — a parse-once consumer would cache "clustering ran, no tables here" about a parse
     * that never ran. A wrong value is worse than a missing one: every family reads
     * {@code UNKNOWN}.
     */
    @Test
    void a_rendered_but_never_parsed_page_reads_unknown_for_every_detector_family()
            throws Exception {
        UUID packageId = insertPackage("l2-unparsed-" + UUID.randomUUID());
        // Pages only — deliberately NO insertFixtureLayout: the between-stages window.
        UUID pageId = insertFixturePages(packageId, "paystub_complete").get(0);

        JsonNode body = json(ok(structure(pageId)));

        assertThat(body.path("blocks")).isEmpty();
        assertThat(body.path("tables")).isEmpty();
        assertThat(body.path("marks")).isEmpty();
        JsonNode coverage = body.path("detectorCoverage");
        assertThat(coverage.path("table").asText()).isEqualTo("UNKNOWN");
        assertThat(coverage.path("block").asText()).isEqualTo("UNKNOWN");
        assertThat(coverage.path("checkbox").asText()).isEqualTo("UNKNOWN");
        assertThat(coverage.path("signature").asText()).isEqualTo("UNKNOWN");
    }

    /**
     * The legacy-tree fallbacks, pinned. A TABLE row persisted by a worker build predating the
     * {@code rows}/{@code cols}/{@code ruled} attributes must serve the census over its own cells
     * — {@code 1 + max(addressed index)}, the same count a consumer would derive — and NEVER
     * {@code ruled: true}, which would be a ruling-confirmation claim no vector ink ever made.
     * Both absent shapes are exercised: attributes NULL entirely, and attributes present without
     * these keys.
     */
    @Test
    void a_legacy_table_without_declared_attributes_serves_the_cell_census_and_stays_unruled()
            throws Exception {
        UUID nullAttributes = fixturePage("paystub_complete", false);
        jdbc.update(
                "UPDATE layout_element SET attributes = NULL WHERE page_id = ? AND element_type"
                        + " = 'TABLE'",
                nullAttributes);
        UUID emptyAttributes = fixturePage("paystub_complete", false);
        jdbc.update(
                "UPDATE layout_element SET attributes = '{}'::jsonb WHERE page_id = ? AND"
                        + " element_type = 'TABLE'",
                emptyAttributes);

        for (UUID pageId : List.of(nullAttributes, emptyAttributes)) {
            JsonNode table = json(ok(structure(pageId))).path("tables").get(0);
            assertThat(table.path("rows").asInt()).as("census rows for %s", pageId).isEqualTo(5);
            assertThat(table.path("cols").asInt()).as("census cols for %s", pageId).isEqualTo(5);
            assertThat(table.path("ruled").asBoolean())
                    .as("absent means unconfirmed, never a fabricated ruling claim")
                    .isFalse();
            assertThat(table.path("cells")).hasSize(25);
        }
    }

    // ── the kind filter ─────────────────────────────────────────────────────

    @Test
    void kind_narrows_to_one_structure_family_and_refuses_names_this_contract_does_not_serve()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        insertBlock(pageId, "PARAGRAPH", "0.9000", spanIdsInOrder(pageId).subList(0, 1));
        insertMark(pageId, "CHECKBOX", "0.9300", "{\"checked\": false, \"fillRatio\": 0.02}");

        JsonNode tablesOnly = json(ok(structure(pageId).param("kind", "TABLE")));
        assertThat(tablesOnly.path("tables")).hasSize(1);
        assertThat(tablesOnly.path("blocks")).isEmpty();
        assertThat(tablesOnly.path("marks")).isEmpty();

        JsonNode blocksOnly = json(ok(structure(pageId).param("kind", "BLOCK")));
        assertThat(blocksOnly.path("tables")).isEmpty();
        assertThat(blocksOnly.path("blocks")).hasSize(1);

        JsonNode marksOnly = json(ok(structure(pageId).param("kind", "MARK")));
        assertThat(marksOnly.path("marks")).hasSize(1);
        assertThat(marksOnly.path("tables")).isEmpty();

        // PAIR is real vocabulary in the design but not in DOCENGINE-L2-1/1.0.0 — the pair
        // detector does not exist yet, and accepting the name would promise it does. P3 widens
        // this with the structureContract bump that announces pairs.
        for (String bad : List.of("PAIR", "CELL", "banana", "")) {
            mockMvc.perform(structure(pageId).param("kind", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void malformed_containsSpan_is_refused_rather_than_guessed() throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);

        for (String bad : List.of("not-a-number", "1.5", "")) {
            mockMvc.perform(structure(pageId).param("containsSpan", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    // ── the pin ─────────────────────────────────────────────────────────────

    @Test
    void the_etag_names_page_hash_and_structure_contract_and_if_none_match_answers_304()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        String hash = "c".repeat(64);
        jdbc.update("UPDATE page SET content_hash = ? WHERE id = ?", hash, pageId);
        String etag = validator(pageId, hash);

        MvcResult first =
                mockMvc.perform(structure(pageId))
                        .andExpect(status().isOk())
                        .andExpect(header().string("ETag", etag))
                        .andExpect(header().string("Cache-Control", "private, no-store"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andReturn();
        assertThat(json(first).path("contentHash").asText()).isEqualTo(hash);

        mockMvc.perform(structure(pageId).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
        mockMvc.perform(structure(pageId).header("If-None-Match", validator(pageId, "d".repeat(64))))
                .andExpect(status().isOk());
    }

    @Test
    void a_page_with_no_content_hash_omits_the_etag_rather_than_faking_one() throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);

        mockMvc.perform(structure(pageId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    /** D4 as corrected in P1: a validator describes ONE representation — the whole-page window. */
    @Test
    void a_filtered_window_carries_no_validator_and_is_never_answered_as_unchanged()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        jdbc.update("UPDATE page SET content_hash = ? WHERE id = ?", "a".repeat(64), pageId);
        String etag = issuedEtag(pageId);

        for (MockHttpServletRequestBuilder narrowed :
                List.of(
                        structure(pageId).param("containsSpan", "1"),
                        structure(pageId).param("kind", "TABLE"))) {
            mockMvc.perform(narrowed.header("If-None-Match", etag))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("ETag"));
        }
    }

    @Test
    void a_conditional_request_still_refuses_a_bad_parameter_rather_than_answering_304()
            throws Exception {
        UUID pageId = fixturePage("paystub_complete", false);
        jdbc.update("UPDATE page SET content_hash = ? WHERE id = ?", "a".repeat(64), pageId);
        String etag = issuedEtag(pageId);

        mockMvc.perform(
                        structure(pageId)
                                .param("kind", "banana")
                                .header("If-None-Match", etag))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ── opaque absence ──────────────────────────────────────────────────────

    @Test
    void foreign_tombstoned_and_nonexistent_pages_are_indistinguishable() throws Exception {
        UUID foreign = foreignPage();
        UUID tombstoned = fixturePage("paystub_complete", false);
        jdbc.update(
                "UPDATE document_package SET deleted_at = now() WHERE id ="
                        + " (SELECT package_id FROM page WHERE id = ?)",
                tombstoned);
        UUID nonexistent = UUID.randomUUID();

        List<String> bodies = new ArrayList<>();
        for (UUID id : List.of(foreign, tombstoned, nonexistent)) {
            bodies.add(
                    mockMvc.perform(structure(id))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString()
                            .replace(id.toString(), "{the-id-the-caller-asked-for}"));
        }
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
    }

    // ── the published contract ──────────────────────────────────────────────

    @Test
    void the_generated_openapi_document_declares_the_l2_read_and_its_resolvers() throws Exception {
        JsonNode api =
                JSON.readTree(
                        mockMvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());

        JsonNode operation = api.at("/paths/~1v1~1pages~1{id}~1structure/get");
        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("security").toString()).contains("bearerAuth");

        List<String> parameters = new ArrayList<>();
        operation.path("parameters").forEach(p -> parameters.add(p.path("name").asText()));
        assertThat(parameters).contains("id", "containsSpan", "kind");

        JsonNode headers = operation.at("/responses/200/headers");
        assertThat(headers.has("ETag")).isTrue();
        assertThat(headers.has("Cache-Control")).isTrue();
        assertThat(headers.has("X-Content-Type-Options")).isTrue();

        // The sibling operations survive springdoc method-name collision — same pin as P1.
        assertThat(api.at("/paths/~1v1~1packages~1{id}~1pages/get/operationId").asText())
                .isEqualTo("list");
        assertThat(api.at("/paths/~1v1~1pages~1{id}~1spans/get").isMissingNode()).isFalse();
    }

    // ── fixture plumbing ────────────────────────────────────────────────────

    private static MockHttpServletRequestBuilder structure(UUID pageId) {
        return get("/v1/pages/{id}/structure", pageId);
    }

    /** The validator this endpoint issues: page id, content hash, AND the structure contract. */
    private static String validator(UUID pageId, String contentHash) {
        return "\"" + pageId + "." + contentHash + "-l2/" + STRUCTURE_CONTRACT + "\"";
    }

    private String issuedEtag(UUID pageId) throws Exception {
        String etag = ok(structure(pageId)).getResponse().getHeader("ETag");
        assertThat(etag).as("the whole-page window carries a validator").isNotNull();
        return etag;
    }

    private MvcResult ok(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return json(result.getResponse().getContentAsString());
    }

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }

    /** Page 0 of a fixture, with its grid tree seeded the way the live worker leaves one. */
    private UUID fixturePage(String fixtureName, boolean ruled) {
        UUID packageId = insertPackage("l2-" + UUID.randomUUID());
        List<UUID> pageIds = insertFixturePages(packageId, fixtureName);
        insertFixtureLayout(packageId, fixtureName, ruled);
        return pageIds.get(0);
    }

    private UUID foreignPage() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'foreign')",
                packageId,
                ORG_OTHER);
        return insertFixturePages(packageId, ORG_OTHER, truth("paystub_complete").get("pages"))
                .get(0);
    }

    /** Truth cell-marked words keyed by {@code "row,col"}. */
    private static Map<String, JsonNode> truthCells(String fixtureName) {
        Map<String, JsonNode> cells = new LinkedHashMap<>();
        for (JsonNode word : truth(fixtureName).get("pages").get(0).get("words")) {
            if (word.has("cell")) {
                cells.put(
                        word.get("cell").get("row").asInt() + "," + word.get("cell").get("col").asInt(),
                        word);
            }
        }
        return cells;
    }

    private static int row(String key) {
        return Integer.parseInt(key.split(",")[0]);
    }

    private static int col(String key) {
        return Integer.parseInt(key.split(",")[1]);
    }

    private List<Long> spanIdsInOrder(UUID pageId) {
        return jdbc.queryForList(
                "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id",
                Long.class,
                pageId);
    }

    private long spanIdOfText(UUID pageId, String text) {
        return jdbc.queryForObject(
                "SELECT id FROM text_span WHERE page_id = ? AND text = ?", Long.class, pageId, text);
    }

    private long spanIdAt(UUID pageId, JsonNode word) {
        return jdbc.queryForObject(
                "SELECT id FROM text_span WHERE page_id = ? AND x = ? AND y = ?",
                Long.class,
                pageId,
                word.get("x").decimalValue().setScale(2, java.math.RoundingMode.HALF_UP),
                word.get("y").decimalValue().setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /** A top-level block element with linked spans, text denormalized the way the persister does. */
    private UUID insertBlock(UUID pageId, String kind, String confidence, List<Long> spanIds) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, element_type, ordinal,
                    x, y, width, height, confidence, detector, detector_version, attributes, text)
                VALUES (?, ?, ?, ?, 500, 72, 100, 200, 12, ?::numeric, 'clustering', 'it', NULL, ?)
                """,
                id,
                ORG_DEV,
                pageId,
                kind,
                confidence,
                "block-text");
        int ordinal = 0;
        for (Long spanId : spanIds) {
            jdbc.update(
                    "INSERT INTO layout_element_span (layout_element_id, text_span_id, org_id,"
                            + " ordinal) VALUES (?, ?, ?, ?)",
                    id,
                    spanId,
                    ORG_DEV,
                    ordinal++);
        }
        return id;
    }

    private UUID insertMark(UUID pageId, String kind, String confidence, String attributes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, element_type, ordinal,
                    x, y, width, height, confidence, detector, detector_version, attributes)
                VALUES (?, ?, ?, ?, ?, 71.3, 305, 10.1, 10.1, ?::numeric, ?, '0.1.0', ?::jsonb)
                """,
                id,
                ORG_DEV,
                pageId,
                kind,
                kind.equals("CHECKBOX") ? 600 : 601,
                confidence,
                kind.equals("CHECKBOX") ? "checkbox-cv" : "signature-cv",
                attributes);
        return id;
    }
}
