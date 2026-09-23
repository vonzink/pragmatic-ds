package com.pragmaticds.docengine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P1.3 — the ADMIN boundary around RAW content, and the audit trail that records crossing it.
 *
 * <p>Two resources on this service serve unmasked borrower text: the immutable engine-result bytes,
 * and now the L1 span layer. They are asserted TOGETHER here on purpose. Both are ADMIN-only for the
 * same reason and both depend on the same fragile property — that their matchers sit AHEAD of the
 * broad {@code GET /v1/**} rule that grants READONLY. Behind it, both would be world-readable inside
 * the org and every test below would still pass if it only checked that ADMIN can read.
 *
 * <p>So the ordering is what is actually asserted, and the assertion works because 403 and 404 are
 * different answers: a NONEXISTENT id under READONLY must be FORBIDDEN. If the broad rule matched
 * first, READONLY would be authorized and would fall through to the controller's opaque 404 — a
 * green-looking response that means the boundary is gone.
 */
class RawContentAdminBoundaryIT extends AbstractExtractionIT {

    /** Every path on this service whose 200 body is unmasked raw content. */
    private static final List<String> RAW_CONTENT_PATHS =
            List.of(
                    "/v1/pages/{id}/spans",
                    "/v1/pages/{id}/structure",
                    "/v1/packages/{id}/engine-result",
                    "/v1/packages/{id}/engine-results/{revision}",
                    // The document body is the same captured text rendered as Markdown, and it is
                    // NOT masked. It served READONLY through the broad read rule from the day it
                    // shipped (#55) — the exact hole this list exists to catch.
                    "/v1/documents/{id}/body.md",
                    // A reviewed document's decisions, unmasked: the gold-set label source.
                    "/v1/documents/{id}/gold");

    @Test
    void raw_content_is_admin_only_and_its_matchers_precede_the_broad_read_rule() throws Exception {
        UUID absent = UUID.randomUUID();

        for (String path : RAW_CONTENT_PATHS) {
            for (String role : List.of("READONLY", "PROCESSOR", "REVIEWER")) {
                mockMvc.perform(get(path, absent, 1).header("X-Dev-Role", role))
                        .andExpect(status().isForbidden());
            }
            // ADMIN passes the role gate and reaches the controller, which reports absence — the
            // 404 here is what proves the 403s above were authorization and not a missing route.
            mockMvc.perform(get(path, absent, 1).header("X-Dev-Role", "ADMIN"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void a_readonly_role_cannot_read_spans_from_a_page_that_really_exists() throws Exception {
        UUID pageId = fixturePage();

        mockMvc.perform(get("/v1/pages/{id}/spans", pageId).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/pages/{id}/spans", pageId).header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    /**
     * L2 is behind the same gate as L1, for the same reason: a structure response carries the
     * page's text — cell contents, block text — merely re-grouped. A boundary that gated the flat
     * record and left its index open would be no boundary at all.
     */
    @Test
    void a_readonly_role_cannot_read_structure_from_a_page_that_really_exists() throws Exception {
        UUID pageId = fixturePage();

        mockMvc.perform(get("/v1/pages/{id}/structure", pageId).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/pages/{id}/structure", pageId).header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    /**
     * The exact reach of this boundary, so that nothing downstream over-claims it.
     *
     * <p>The gate is ONE REPRESENTATION DEEP. It covers the two resources that serve borrower text as
     * TEXT. It does not cover the page RASTER: {@code GET /v1/pages/{id}/render} is a PNG of the whole
     * page — every word L1 would return, plus the ones it cannot represent — and it sits under the
     * broad {@code GET /v1/**} rule, reachable by READONLY, as does the {@code /signed-url} that
     * issues a session-free link to the same bytes. That is deliberate (the review UI shows
     * thumbnails to reviewers) and PRE-EXISTING, but it means two things must not be said:
     *
     * <ul>
     *   <li>that L1 is strictly more revealing than everything a READONLY principal can already
     *       reach — it is more revealing than {@code /fields}, and less than {@code /render};
     *   <li>that {@code PAGE_SPANS_ACCESSED} plus {@code ENGINE_RESULT_ACCESSED} is a complete record
     *       of who saw unmasked borrower content. {@code render} writes no audit event at all, so an
     *       access review over those two actions is incomplete by exactly the raster reads.
     * </ul>
     *
     * <p>The assertion below is the measurement behind those two sentences. 403 versus 404 is what
     * separates "the role gate refused" from "the role gate let this through and the controller
     * reported absence", so a nonexistent id is the right probe: if {@code /render} were ever moved
     * behind the ADMIN matcher, this test fails and the paragraph above has to be rewritten.
     */
    @Test
    void the_raster_of_the_same_page_is_NOT_behind_this_gate_and_writes_no_audit_event()
            throws Exception {
        UUID absent = UUID.randomUUID();

        mockMvc.perform(get("/v1/pages/{id}/spans", absent).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isForbidden());
        for (String rasterPath : List.of("/v1/pages/{id}/render", "/v1/pages/{id}/signed-url")) {
            mockMvc.perform(get(rasterPath, absent).header("X-Dev-Role", "READONLY"))
                    .andExpect(status().isNotFound());
        }

        UUID pageId = fixturePage();
        mockMvc.perform(get("/v1/pages/{id}/render", pageId).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isNotFound());
        assertThat(auditEvents(pageId))
                .as("no PAGE_SPANS_ACCESSED — and the raster path writes nothing of its own either")
                .isEmpty();
    }

    /** Page GEOMETRY stays under the generic read rule — only the CONTENT is restricted. */
    @Test
    void the_page_listing_a_readonly_role_already_had_is_not_narrowed_by_this() throws Exception {
        UUID pageId = fixturePage();
        UUID packageId =
                jdbc.queryForObject("SELECT package_id FROM page WHERE id = ?", UUID.class, pageId);

        mockMvc.perform(
                        get("/v1/packages/{id}/pages", packageId).header("X-Dev-Role", "READONLY"))
                .andExpect(status().isOk());
    }

    // ── acceptance 7: one value-free audit event per successful read ─────────

    @Test
    void every_successful_read_writes_exactly_one_audit_event_of_ids_and_counts() throws Exception {
        UUID pageId = fixturePage();
        assertThat(auditEvents(pageId)).isEmpty();

        mockMvc.perform(get("/v1/pages/{id}/spans", pageId)).andExpect(status().isOk());

        List<Map<String, Object>> events = auditEvents(pageId);
        assertThat(events).hasSize(1);
        JsonNode metadata = JSON.readTree((String) events.get(0).get("metadata"));
        assertThat(metadata.path("spanCount").asInt()).isEqualTo(50);
        assertThat(metadata.path("limit").asInt()).isEqualTo(1000);
        assertThat(metadata.path("truncated").asBoolean()).isFalse();
        assertThat(metadata.path("mode").asText()).isEqualTo("PAGE");
        assertThat(metadata.path("packagePageIndex").asInt()).isZero();
        assertThat(metadata.path("packageId").asText()).isNotBlank();

        // The point of the event is that it records the crossing WITHOUT re-committing the offence:
        // not one captured word may appear in it. Checked against the page's real span texts, so a
        // future metadata key that echoed content would fail here rather than in an incident.
        //
        // One- and two-character spans are excluded because they are not evidence either way: the
        // fixture prints a bare "-" placeholder, and a hyphen occurs inside every UUID this event
        // legitimately carries. Asserting on them would fail on a correct implementation, which
        // makes the check worse than useless.
        String serialised = metadata.toString();
        for (String word : spanTexts(pageId)) {
            if (word.length() < 3) {
                continue;
            }
            assertThat(serialised)
                    .as("no captured word reaches audit metadata")
                    .doesNotContain(word);
        }
    }

    @Test
    void a_paginated_read_audits_each_window_it_actually_served() throws Exception {
        UUID pageId = fixturePage();

        String cursor =
                JSON.readTree(
                                mockMvc.perform(
                                                get("/v1/pages/{id}/spans", pageId)
                                                        .param("limit", "20"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("nextCursor")
                        .asText();
        mockMvc.perform(get("/v1/pages/{id}/spans", pageId).param("limit", "20").param("after", cursor))
                .andExpect(status().isOk());

        List<Map<String, Object>> events = auditEvents(pageId);
        assertThat(events).hasSize(2);
        assertThat(JSON.readTree((String) events.get(0).get("metadata")).path("truncated").asBoolean())
                .isTrue();
        assertThat(JSON.readTree((String) events.get(0).get("metadata")).path("spanCount").asInt())
                .isEqualTo(20);
    }

    /** The L2 read writes its own value-free event, exactly like L1's. */
    @Test
    void every_successful_structure_read_writes_one_audit_event_of_ids_and_counts()
            throws Exception {
        UUID pageId = fixturePage();
        assertThat(structureAuditEvents(pageId)).isEmpty();

        mockMvc.perform(get("/v1/pages/{id}/structure", pageId)).andExpect(status().isOk());

        List<Map<String, Object>> events = structureAuditEvents(pageId);
        assertThat(events).hasSize(1);
        JsonNode metadata = JSON.readTree((String) events.get(0).get("metadata"));
        assertThat(metadata.path("packageId").asText()).isNotBlank();
        assertThat(metadata.path("packagePageIndex").asInt()).isZero();
        assertThat(metadata.has("blocks")).isTrue();
        assertThat(metadata.has("tables")).isTrue();
        assertThat(metadata.has("marks")).isTrue();

        // Value-free means value-free: no captured word may ride along as metadata.
        String serialised = metadata.toString();
        for (String word : spanTexts(pageId)) {
            if (word.length() < 3) {
                continue;
            }
            assertThat(serialised)
                    .as("no captured word reaches audit metadata")
                    .doesNotContain(word);
        }
    }

    /** A 304 delivers no spans, so there is no read to record. */
    @Test
    void a_revalidation_that_returns_no_spans_writes_no_read_event() throws Exception {
        UUID pageId = fixturePage();
        jdbc.update("UPDATE page SET content_hash = ? WHERE id = ?", "e".repeat(64), pageId);

        // The tag the server issued, read off the wire — the only one a real client can hold.
        String etag =
                mockMvc.perform(get("/v1/pages/{id}/spans", pageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader("ETag");
        assertThat(etag).isNotNull();
        int readsBefore = auditEvents(pageId).size();

        mockMvc.perform(get("/v1/pages/{id}/spans", pageId).header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        assertThat(auditEvents(pageId)).hasSize(readsBefore);
    }

    // ── fixture plumbing ────────────────────────────────────────────────────

    private UUID fixturePage() {
        UUID packageId = insertPackage("boundary-" + UUID.randomUUID());
        return insertFixturePages(packageId, "paystub_complete").get(0);
    }

    private List<Map<String, Object>> auditEvents(UUID pageId) {
        return jdbc.queryForList(
                "SELECT metadata::text AS metadata FROM audit_event WHERE action ="
                        + " 'PAGE_SPANS_ACCESSED' AND subject_type = 'PAGE' AND subject_id = ?"
                        + " ORDER BY id",
                pageId);
    }

    private List<Map<String, Object>> structureAuditEvents(UUID pageId) {
        return jdbc.queryForList(
                "SELECT metadata::text AS metadata FROM audit_event WHERE action ="
                        + " 'PAGE_STRUCTURE_ACCESSED' AND subject_type = 'PAGE' AND subject_id = ?"
                        + " ORDER BY id",
                pageId);
    }

    private List<String> spanTexts(UUID pageId) {
        List<String> texts = new ArrayList<>();
        for (String text :
                jdbc.queryForList(
                        "SELECT text FROM text_span WHERE page_id = ?", String.class, pageId)) {
            texts.add(text);
        }
        return texts;
    }
}
