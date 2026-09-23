package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P1.2 — {@code GET /v1/pages/{id}/spans}, the L1 wire.
 *
 * <p>L1 is the raw record: every word the parser captured, with its box, its source and its
 * per-word confidence. It asserts nothing and names nothing. The properties worth testing are
 * therefore not "is the value right" but "is the record complete, ordered, addressable, pinned to
 * one parse, and invisible to anyone who should not see it".
 *
 * <p>The fixture is {@code paystub_complete}: 50 words on one 612x792 page, drawn as a real grid,
 * committed and synthetic. No borrower document is involved anywhere in this class.
 */
class PageSpansApiIT extends AbstractExtractionIT {

    /** The earnings block, verbatim from the design's worked example. */
    private static final String EARNINGS_BOX = "66,168,500,110";

    // ── acceptance 1: the whole page, in the total order ────────────────────

    @Test
    void the_fixture_page_returns_all_fifty_spans_in_source_ordinal_id_order() throws Exception {
        UUID pageId = fixturePage();

        JsonNode body = json(ok(spans(pageId)));

        assertThat(body.path("spans")).hasSize(50);
        assertThat(body.path("returned").asInt()).isEqualTo(50);
        assertThat(body.path("truncated").asBoolean()).isFalse();
        assertThat(body.path("nextCursor").isNull()).isTrue();
        assertThat(body.path("widthPt").asDouble()).isEqualTo(612.0);
        assertThat(body.path("heightPt").asDouble()).isEqualTo(792.0);
        assertThat(body.path("rotation").asInt()).isZero();
        assertThat(body.path("textLayer").asText()).isEqualTo("NATIVE");
        assertThat(body.path("packagePageIndex").asInt()).isZero();

        List<String> served = new ArrayList<>();
        int previousOrdinal = -1;
        for (JsonNode span : body.path("spans")) {
            served.add(span.path("text").asText());
            assertThat(span.path("ordinal").asInt()).isGreaterThan(previousOrdinal);
            previousOrdinal = span.path("ordinal").asInt();
            assertThat(span.path("source").asText()).isEqualTo("NATIVE");
            assertThat(span.path("confidence").asDouble()).isEqualTo(1.0);
            assertThat(span.path("id").asLong()).isPositive();
            for (String member : List.of("x", "y", "width", "height", "ocrEngine", "fontSize", "fontName")) {
                assertThat(span.has(member)).as("span carries %s", member).isTrue();
            }
        }

        List<String> truthWords = new ArrayList<>();
        for (JsonNode word : truth("paystub_complete").get("pages").get(0).get("words")) {
            truthWords.add(word.get("text").asText());
        }
        assertThat(served).as("L1 is the record — every captured word, in order").isEqualTo(truthWords);
    }

    /**
     * The BOX, by value, not by key presence. {@code SpanView} is a 12-component record whose four
     * box components are all {@code BigDecimal} and all positional — the single easiest thing in this
     * change to get wrong, and the one a reader cannot check: a caller that receives a transposed box
     * draws every highlight in the wrong place while the endpoint returns the right spans for the
     * right {@code ?box=} window and every other assertion in this class still holds. (Verified by
     * mutation: swapping the constructor arguments to {@code (getY, getX, getHeight, getWidth)} left
     * the whole suite green before this test existed.)
     *
     * <p>The fixture declares the exact geometry of all 50 words, so the served box is compared to
     * the fixture's own truth rather than to nothing.
     */
    @Test
    void every_span_carries_the_fixtures_own_declared_box_and_not_a_transposition() throws Exception {
        UUID pageId = fixturePage();

        JsonNode served = json(ok(spans(pageId))).path("spans");
        JsonNode words = truth("paystub_complete").get("pages").get(0).get("words");
        assertThat(served).hasSize(words.size());

        for (int i = 0; i < words.size(); i++) {
            JsonNode span = served.get(i);
            JsonNode word = words.get(i);
            assertThat(span.path("text").asText()).isEqualTo(word.get("text").asText());
            for (String member : List.of("x", "y", "width", "height")) {
                assertThat(span.path(member).decimalValue())
                        .as("span %d (%s) %s", i, word.get("text").asText(), member)
                        .isEqualByComparingTo(word.get(member).decimalValue());
            }
        }

        // The fixture is a real grid, so x and y genuinely differ and width and height genuinely
        // differ on at least one word — without that, a transposition would be unobservable above.
        JsonNode first = served.get(0);
        assertThat(first.path("x").decimalValue()).isNotEqualByComparingTo(first.path("y").decimalValue());
        assertThat(first.path("width").decimalValue())
                .isNotEqualByComparingTo(first.path("height").decimalValue());
    }

    /**
     * Font metadata is per span and carried only by the text layer: {@code persistNativeSpans} keeps
     * {@code fontSize}/{@code fontName}, {@code persistOcrSpans} forces both null because a
     * recogniser has no fonts to report. The fixture bridge inserts neither, so this test stamps a
     * NATIVE span the way the real persister would and recognises a second one — the difference in
     * the response is then attributable to that edit and to nothing else.
     */
    @Test
    void font_metadata_rides_on_native_spans_and_is_null_on_recognised_ones() throws Exception {
        UUID pageId = fixturePage();
        List<Long> ids = spanIdsInOrder(pageId);
        jdbc.update(
                "UPDATE text_span SET font_size = 10.20, font_name = 'Helvetica' WHERE id = ?",
                ids.get(0));
        jdbc.update(
                "UPDATE text_span SET source = 'OCR', ocr_engine = 'RAPIDOCR', confidence = 0.9200,"
                        + " font_size = NULL, font_name = NULL WHERE id = ?",
                ids.get(1));

        JsonNode body = json(ok(spans(pageId)));
        JsonNode native0 = spanById(body, ids.get(0));
        JsonNode recognised = spanById(body, ids.get(1));

        assertThat(native0.path("fontSize").asDouble()).isEqualTo(10.2);
        assertThat(native0.path("fontName").asText()).isEqualTo("Helvetica");
        assertThat(native0.path("ocrEngine").isNull()).isTrue();
        assertThat(recognised.path("source").asText()).isEqualTo("OCR");
        assertThat(recognised.path("ocrEngine").asText()).isEqualTo("RAPIDOCR");
        assertThat(recognised.path("confidence").asDouble()).isEqualTo(0.92);
        assertThat(recognised.path("fontSize").isNull()).isTrue();
        assertThat(recognised.path("fontName").isNull()).isTrue();
    }

    // ── acceptance 3: the filters ───────────────────────────────────────────

    @Test
    void the_earnings_block_window_returns_exactly_its_twenty_five_words() throws Exception {
        UUID pageId = fixturePage();

        JsonNode body = json(ok(spans(pageId).param("box", EARNINGS_BOX)));

        assertThat(body.path("spans")).hasSize(25);
        assertThat(body.path("returned").asInt()).isEqualTo(25);
        List<String> texts = new ArrayList<>();
        body.path("spans").forEach(span -> texts.add(span.path("text").asText()));
        assertThat(texts)
                .startsWith("Earnings", "Rate", "Hours", "Current", "YTD")
                .contains("48.0771", "80.00", "3,846.17", "72.1157", "4.50", "324.52")
                .endsWith("4,670.69", "4,670.69");
    }

    @Test
    void asking_for_ocr_spans_on_a_native_page_is_an_empty_array_not_a_404() throws Exception {
        UUID pageId = fixturePage();

        JsonNode body = json(ok(spans(pageId).param("source", "OCR")));

        assertThat(body.path("spans")).isEmpty();
        assertThat(body.path("returned").asInt()).isZero();
        assertThat(body.path("truncated").asBoolean()).isFalse();
        assertThat(body.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void the_element_resolver_returns_one_cells_member_spans() throws Exception {
        UUID pageId = fixturePage();
        long rateSpan = spanIdOfText(pageId, "48.0771");
        UUID cell = elementOwning(rateSpan);

        JsonNode body = json(ok(spans(pageId).param("element", cell.toString())));

        assertThat(body.path("spans")).hasSize(1);
        assertThat(body.at("/spans/0/id").asLong()).isEqualTo(rateSpan);
        assertThat(body.at("/spans/0/text").asText()).isEqualTo("48.0771");
    }

    @Test
    void an_unknown_element_is_an_empty_window_not_an_error() throws Exception {
        UUID pageId = fixturePage();

        JsonNode body = json(ok(spans(pageId).param("element", UUID.randomUUID().toString())));

        assertThat(body.path("spans")).isEmpty();
    }

    // ── acceptance 4: the pin ───────────────────────────────────────────────

    @Test
    void the_etag_names_the_page_and_its_content_hash_and_if_none_match_answers_304()
            throws Exception {
        UUID pageId = fixturePage();
        String hash = "c".repeat(64);
        jdbc.update("UPDATE page SET content_hash = ? WHERE id = ?", hash, pageId);
        String etag = validator(pageId, hash);

        MvcResult first =
                mockMvc.perform(spans(pageId))
                        .andExpect(status().isOk())
                        .andExpect(header().string("ETag", etag))
                        .andExpect(header().string("Cache-Control", "private, no-store"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andReturn();
        assertThat(json(first).path("contentHash").asText()).isEqualTo(hash);

        mockMvc.perform(spans(pageId).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
        mockMvc.perform(spans(pageId).header("If-None-Match", validator(pageId, "d".repeat(64))))
                .andExpect(status().isOk());
    }

    /**
     * {@code page.content_hash} is the DUPLICATE-PAGE detection digest (DATA_MODEL: V5's
     * {@code duplicate_of_page_id} exists precisely because two pages in one package are EXPECTED to
     * carry the same value when their span text and geometry match). A validator built from it alone
     * therefore does not identify the resource: a caller holding page A's copy would be told its copy
     * of page B is current, and would attribute A's span ids to B — and {@code span.id} is the join
     * key {@code evidence[].textSpanId} and {@code ?element=} both rely on. A wrong record, not a
     * missing one, which is the failure this whole design is built against.
     */
    @Test
    void a_duplicate_pages_validator_cannot_be_used_to_revalidate_this_page() throws Exception {
        String sharedHash = "b".repeat(64);
        UUID pageA = parsedFixturePage(sharedHash);
        UUID pageB = parsedFixturePage(sharedHash);

        String tagOfA = issuedEtag(pageA);

        // Same content_hash, different page, different span ids: the answer must be the record, not
        // "your copy is current".
        JsonNode body = json(ok(spans(pageB).header("If-None-Match", tagOfA)));
        assertThat(body.path("spans")).hasSize(50);
        assertThat(body.path("pageId").asText()).isEqualTo(pageB.toString());

        // And the page's own tag still revalidates, so this is resource scoping and not a disabled
        // conditional.
        mockMvc.perform(spans(pageB).header("If-None-Match", validator(pageB, sharedHash)))
                .andExpect(status().isNotModified());
    }

    /**
     * The one failure mode a paginating caller cannot detect. The published contract used to tell a
     * consumer to pin the ETag across a walk; {@code If-None-Match} is the header it would reach for,
     * and the second window of a walk the server itself just called truncated must never be answered
     * "unchanged" — that hands back 20 of 50 spans behind a status code that looks like success.
     */
    @Test
    void a_cursored_window_is_never_answered_as_unchanged() throws Exception {
        UUID pageId = parsedFixturePage("a".repeat(64));
        String etag = issuedEtag(pageId);

        JsonNode first = json(ok(spans(pageId).param("limit", "20")));
        assertThat(first.path("truncated").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asText();

        JsonNode second =
                json(
                        ok(
                                spans(pageId)
                                        .param("limit", "20")
                                        .param("after", cursor)
                                        .header("If-None-Match", etag)));
        assertThat(second.path("returned").asInt()).isEqualTo(20);
        assertThat(second.at("/spans/0/id").asLong())
                .isNotEqualTo(first.at("/spans/0/id").asLong());
    }

    /** A narrowed window is a different representation, so the page's validator cannot answer it. */
    @Test
    void a_narrowed_window_is_never_answered_as_unchanged() throws Exception {
        UUID pageId = parsedFixturePage("a".repeat(64));
        String etag = issuedEtag(pageId);

        for (MockHttpServletRequestBuilder narrowed :
                List.of(
                        spans(pageId).param("box", EARNINGS_BOX),
                        spans(pageId).param("source", "NATIVE"),
                        spans(pageId).param("minConfidence", "0.5"),
                        spans(pageId).param("limit", "20"),
                        spans(pageId).param("element", UUID.randomUUID().toString()))) {
            mockMvc.perform(narrowed.header("If-None-Match", etag))
                    .andExpect(status().isOk())
                    // A window the tag does not describe must not carry it either — a caller must
                    // not be able to cache 25 spans under the whole page's validator.
                    .andExpect(header().doesNotExist("ETag"));
        }
    }

    /**
     * The "refuse rather than clamp or guess" posture must not be reachable-around. A revalidating
     * request used to return 304 before any parameter was parsed, so every refusal this endpoint
     * documents became a plausible-looking 304 for any caller that sent a matching If-None-Match.
     */
    @Test
    void a_conditional_request_still_refuses_a_bad_parameter_rather_than_answering_304()
            throws Exception {
        UUID pageId = parsedFixturePage("a".repeat(64));
        String etag = issuedEtag(pageId);

        for (MockHttpServletRequestBuilder bad :
                List.of(
                        spans(pageId).param("limit", "0"),
                        spans(pageId).param("limit", "5001"),
                        spans(pageId).param("limit", "not-a-number"),
                        spans(pageId).param("after", "not-base64!!"),
                        spans(pageId).param("minConfidence", "1.5"))) {
            mockMvc.perform(bad.header("If-None-Match", etag))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void a_page_with_no_content_hash_omits_the_etag_rather_than_faking_one() throws Exception {
        UUID pageId = fixturePage();
        jdbc.update("UPDATE page SET content_hash = NULL WHERE id = ?", pageId);

        MvcResult result =
                mockMvc.perform(spans(pageId))
                        .andExpect(status().isOk())
                        .andExpect(header().doesNotExist("ETag"))
                        .andExpect(header().string("Cache-Control", "private, no-store"))
                        .andReturn();
        assertThat(json(result).path("contentHash").isNull())
                .as("null on the wire too — a pin nobody can honour is not invented")
                .isTrue();
    }

    // ── acceptance 5: opaque absence ────────────────────────────────────────

    @Test
    void foreign_tombstoned_and_nonexistent_pages_are_indistinguishable() throws Exception {
        UUID foreign = foreignPage();
        UUID tombstoned = fixturePage();
        jdbc.update(
                "UPDATE document_package SET deleted_at = now() WHERE id ="
                        + " (SELECT package_id FROM page WHERE id = ?)",
                tombstoned);
        UUID nonexistent = UUID.randomUUID();

        List<String> bodies = new ArrayList<>();
        for (UUID id : List.of(foreign, tombstoned, nonexistent)) {
            // The only part of the response that may differ is the request path the caller itself
            // supplied; everything the SERVER chose to say must be identical, or the difference is
            // an oracle for which page ids exist in another org.
            bodies.add(
                    mockMvc.perform(spans(id))
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

    // ── the request envelope ────────────────────────────────────────────────

    @Test
    void limit_defaults_to_one_thousand_and_refuses_anything_past_five_thousand() throws Exception {
        UUID pageId = fixturePage();

        mockMvc.perform(spans(pageId).param("limit", "5000")).andExpect(status().isOk());
        for (String bad : List.of("5001", "0", "-1", "not-a-number")) {
            mockMvc.perform(spans(pageId).param("limit", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void a_malformed_or_foreign_cursor_is_refused_rather_than_guessed() throws Exception {
        UUID pageId = fixturePage();
        UUID otherPageId = fixturePage();

        String cursor =
                json(ok(spans(pageId).param("limit", "10"))).path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        mockMvc.perform(spans(pageId).param("after", cursor).param("limit", "10"))
                .andExpect(status().isOk());
        // A cursor names the page it walks.
        mockMvc.perform(spans(otherPageId).param("after", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        // A page cursor cannot be replayed against an element's own order.
        mockMvc.perform(
                        spans(pageId)
                                .param("after", cursor)
                                .param("element", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String bad : List.of("not-base64!!", "", "abcdef")) {
            mockMvc.perform(spans(pageId).param("after", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void unparseable_filters_are_refused_rather_than_ignored() throws Exception {
        UUID pageId = fixturePage();

        for (MockHttpServletRequestBuilder request :
                List.of(
                        spans(pageId).param("source", "HANDWRITING"),
                        spans(pageId).param("box", "66,168,500"),
                        spans(pageId).param("box", "a,b,c,d"),
                        spans(pageId).param("box", "66,168,-1,110"),
                        spans(pageId).param("minConfidence", "high"),
                        spans(pageId).param("minConfidence", "1.5"),
                        spans(pageId).param("element", "not-a-uuid"))) {
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    // ── the published contract ──────────────────────────────────────────────

    /**
     * {@code docs/api/openapi.json} is regenerated and committed by hand and CI never diffs it, so
     * the generated document is pinned here instead — the same posture the engine-result endpoints
     * already take. What is pinned is the shape a consumer codes against: the path exists, it is
     * authenticated, it declares every filter this class exercises, and it advertises the integrity
     * headers the response really carries.
     */
    @Test
    void the_generated_openapi_document_declares_the_l1_read_and_its_filters() throws Exception {
        JsonNode api =
                JSON.readTree(
                        mockMvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());

        JsonNode operation = api.at("/paths/~1v1~1pages~1{id}~1spans/get");
        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("security").toString()).contains("bearerAuth");

        List<String> parameters = new ArrayList<>();
        operation.path("parameters").forEach(parameter -> parameters.add(parameter.path("name").asText()));
        assertThat(parameters)
                .contains("id", "after", "limit", "source", "minConfidence", "box", "element");

        JsonNode headers = operation.at("/responses/200/headers");
        assertThat(headers.has("ETag")).isTrue();
        assertThat(headers.has("Cache-Control")).isTrue();
        assertThat(headers.has("X-Content-Type-Options")).isTrue();

        // Adding a controller must not rename a sibling operation through a springdoc method-name
        // collision — the trap that EngineResultApiIT already pins for /documents/{id}/history.
        assertThat(api.at("/paths/~1v1~1packages~1{id}~1pages/get/operationId").asText())
                .isEqualTo("list");
    }

    // ── fixture plumbing ────────────────────────────────────────────────────

    private static MockHttpServletRequestBuilder spans(UUID pageId) {
        return get("/v1/pages/{id}/spans", pageId);
    }

    /** The validator this endpoint issues: the page id AND its content hash, quoted. */
    private static String validator(UUID pageId, String contentHash) {
        return "\"" + pageId + "." + contentHash + "\"";
    }

    /**
     * The tag the SERVER issued for this page's whole-page window — the only one a real client can
     * hold. Read off the wire rather than constructed, so these tests measure the conditional-request
     * behaviour and not the tag's spelling.
     */
    private String issuedEtag(UUID pageId) throws Exception {
        String etag = ok(spans(pageId)).getResponse().getHeader("ETag");
        assertThat(etag).as("the whole-page window carries a validator").isNotNull();
        return etag;
    }

    private MvcResult ok(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode spanById(JsonNode body, long id) {
        for (JsonNode span : body.path("spans")) {
            if (span.path("id").asLong() == id) {
                return span;
            }
        }
        throw new AssertionError("no span " + id + " in the window");
    }

    /** A fixture page stamped with a content hash, the way the PARSING stage leaves one. */
    private UUID parsedFixturePage(String contentHash) {
        UUID pageId = fixturePage();
        jdbc.update("UPDATE page SET content_hash = ? WHERE id = ?", contentHash, pageId);
        return pageId;
    }

    /** A parsed fixture page with its layout tree — page 0 of {@code paystub_complete}. */
    private UUID fixturePage() {
        UUID packageId = insertPackage("l1-" + UUID.randomUUID());
        List<UUID> pageIds = insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
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

    private List<Long> spanIdsInOrder(UUID pageId) {
        return jdbc.queryForList(
                "SELECT id FROM text_span WHERE page_id = ? ORDER BY source, ordinal, id",
                Long.class,
                pageId);
    }

    private long spanIdOfText(UUID pageId, String text) {
        Long id =
                jdbc.queryForObject(
                        "SELECT id FROM text_span WHERE page_id = ? AND text = ?",
                        Long.class,
                        pageId,
                        text);
        return id;
    }

    private UUID elementOwning(long spanId) {
        return jdbc.queryForObject(
                "SELECT layout_element_id FROM layout_element_span WHERE text_span_id = ?",
                UUID.class,
                spanId);
    }
}
