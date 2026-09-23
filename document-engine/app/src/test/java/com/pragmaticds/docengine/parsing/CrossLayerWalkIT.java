package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.results.canonical.EngineResultEnvelopeAssembler;
import com.pragmaticds.docengine.results.canonical.EnvelopeAssemblyRequest;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshotLoader;
import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Acceptance 18 — the L3 → L2 → L1 walk, both directions, from BOTH read surfaces.
 *
 * <p>Design D3's claim is that navigation costs zero contract change: {@code evidence[].textSpanId}
 * and {@code evidence[].layoutElementId} were designed onto {@code /fields} and into the frozen
 * canonical envelope, and the two resolvers ({@code ?containsSpan=} on structure, {@code ?element=}
 * on spans) complete the walk. This IT is the measurement of that claim on the fixture document:
 * every evidence id must resolve to a live row through the public read surface — an id that
 * resolves to nothing is a dangling pointer wearing the costume of provenance.
 *
 * <p>The envelope leg matters as much as {@code /fields}: a consumer in another repository pins the
 * envelope and navigates FROM it, so its ids must land on the same live rows. The envelope is
 * assembled here exactly the way {@code EngineResultSnapshotIT} assembles it — through the real
 * loader and assembler beans — so this test walks the same bytes a consumer would hold.
 */
class CrossLayerWalkIT extends AbstractExtractionIT {

    @Autowired private MachineResultSnapshotLoader snapshotLoader;
    @Autowired private EngineResultEnvelopeAssembler envelopeAssembler;

    @Test
    void every_fields_evidence_id_resolves_to_a_live_row_and_the_walk_closes_both_ways()
            throws Exception {
        UUID packageId = fixturePackage();
        UUID documentId = onlyDocumentOf(packageId);

        JsonNode fields =
                JSON.readTree(
                        mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        List<JsonNode> evidence = allEvidence(fields.path("fields"));
        assertThat(evidence).as("the fixture document extracts evidenced fields").isNotEmpty();
        assertEveryIdResolves(evidence);

        // The worked walk of design §11, closed in both directions on currentGrossPay.
        JsonNode grossValue = valueEvidenceOf(fields.path("fields"), "currentGrossPay");
        UUID pageId = UUID.fromString(grossValue.path("pageId").asText());
        long spanId = grossValue.path("textSpanId").asLong();
        String cellId = grossValue.path("layoutElementId").asText();

        // DOWN: the evidence's element resolves to its member spans — which include the span.
        JsonNode memberSpans =
                JSON.readTree(
                        mockMvc.perform(
                                        get("/v1/pages/{id}/spans", pageId)
                                                .param("element", cellId))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        List<Long> memberIds = new ArrayList<>();
        memberSpans.path("spans").forEach(span -> memberIds.add(span.path("id").asLong()));
        assertThat(memberIds).contains(spanId);

        // UP: the span resolves to the structure that owns it — the same cell, inside its table.
        JsonNode owning =
                JSON.readTree(
                        mockMvc.perform(
                                        get("/v1/pages/{id}/structure", pageId)
                                                .param("containsSpan", String.valueOf(spanId)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(owning.path("tables")).hasSize(1);
        List<String> owningCellIds = new ArrayList<>();
        for (JsonNode cell : owning.path("tables").get(0).path("cells")) {
            for (JsonNode id : cell.path("spanIds")) {
                if (id.asLong() == spanId) {
                    owningCellIds.add(cell.path("id").asText());
                }
            }
        }
        assertThat(owningCellIds).containsExactly(cellId);
    }

    @Test
    void every_envelope_evidence_id_resolves_to_the_same_live_rows() throws Exception {
        UUID packageId = fixturePackage();
        UUID jobId = seedJobRow(packageId);

        EnvelopeAssemblyRequest request =
                new EnvelopeAssemblyRequest(
                        packageId,
                        jobId,
                        1,
                        1,
                        "1.0.0",
                        "DOCENGINE-C14N-1",
                        ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);
        byte[] envelope =
                envelopeAssembler.assemble(request, snapshotLoader.load(request)).bytes();
        JsonNode root = JSON.readTree(envelope);

        List<JsonNode> evidence = new ArrayList<>();
        for (JsonNode document : root.path("documents")) {
            evidence.addAll(allEvidence(document.path("fields")));
        }
        assertThat(evidence).as("the envelope carries evidenced fields").isNotEmpty();
        assertEveryIdResolves(evidence);
    }

    // ── the resolution measurement ──────────────────────────────────────────

    /**
     * Resolves every {@code textSpanId} against L1 and every {@code layoutElementId} against L2,
     * through the endpoints — not through the database — because the claim under test is that the
     * PUBLIC walk closes, not that foreign keys hold.
     */
    private void assertEveryIdResolves(List<JsonNode> evidence) throws Exception {
        Map<UUID, Set<Long>> liveSpans = new HashMap<>();
        Map<UUID, Set<String>> liveStructures = new HashMap<>();

        for (JsonNode item : evidence) {
            UUID pageId = UUID.fromString(item.path("pageId").asText());
            Set<Long> spans = liveSpans.computeIfAbsent(pageId, this::spanIdsOf);
            Set<String> structures =
                    liveStructures.computeIfAbsent(pageId, this::structureIdsOf);

            if (!item.path("textSpanId").isNull()) {
                assertThat(spans)
                        .as("evidence textSpanId %s is a live L1 span", item.path("textSpanId"))
                        .contains(item.path("textSpanId").asLong());
            }
            if (!item.path("layoutElementId").isNull()) {
                assertThat(structures)
                        .as(
                                "evidence layoutElementId %s is a live L2 structure",
                                item.path("layoutElementId"))
                        .contains(item.path("layoutElementId").asText());
            }
        }
    }

    private Set<Long> spanIdsOf(UUID pageId) {
        try {
            JsonNode body =
                    JSON.readTree(
                            mockMvc.perform(get("/v1/pages/{id}/spans", pageId))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            Set<Long> ids = new HashSet<>();
            body.path("spans").forEach(span -> ids.add(span.path("id").asLong()));
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every structure id on the page: blocks, tables, their cells, marks. */
    private Set<String> structureIdsOf(UUID pageId) {
        try {
            JsonNode body =
                    JSON.readTree(
                            mockMvc.perform(get("/v1/pages/{id}/structure", pageId))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            Set<String> ids = new HashSet<>();
            body.path("blocks").forEach(block -> ids.add(block.path("id").asText()));
            for (JsonNode table : body.path("tables")) {
                ids.add(table.path("id").asText());
                table.path("cells").forEach(cell -> ids.add(cell.path("id").asText()));
            }
            body.path("marks").forEach(mark -> ids.add(mark.path("id").asText()));
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── fixture plumbing ────────────────────────────────────────────────────

    private UUID fixturePackage() {
        UUID packageId = insertPackage("walk-" + UUID.randomUUID());
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    private List<JsonNode> allEvidence(JsonNode fields) {
        List<JsonNode> evidence = new ArrayList<>();
        for (JsonNode field : fields) {
            field.path("evidence").forEach(evidence::add);
        }
        return evidence;
    }

    private JsonNode valueEvidenceOf(JsonNode fields, String fieldName) {
        for (JsonNode field : fields) {
            if (fieldName.equals(field.path("fieldName").asText())) {
                for (JsonNode item : field.path("evidence")) {
                    if ("VALUE".equals(item.path("role").asText())) {
                        return item;
                    }
                }
            }
        }
        throw new AssertionError("no VALUE evidence for " + fieldName);
    }

    /** The job row the envelope loader joins provenance through, seeded the snapshot-IT way. */
    private UUID seedJobRow(UUID packageId) {
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO processing_job
                    (id, org_id, package_id, idempotency_key, status, attempt, parse_generation)
                VALUES (?, ?, ?, ?, 'EXTRACTING', 1, 1)
                """,
                jobId,
                ORG_DEV,
                packageId,
                "walk-it-" + jobId);
        jdbc.update(
                """
                INSERT INTO processing_stage
                    (id, org_id, job_id, stage, status, attempt, output_digest, worker_version,
                     parser_versions)
                VALUES (?, ?, ?, 'EXTRACTING', 'SUCCEEDED', 1, 'walk-digest', NULL, NULL)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                jobId);
        return jobId;
    }
}
