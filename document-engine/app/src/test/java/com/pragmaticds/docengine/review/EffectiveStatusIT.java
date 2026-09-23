package com.pragmaticds.docengine.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.EngineResultFinalizerPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * D11 (2026-08-17 suggestions-and-human-override design, Phase R task R1): every field row on the
 * two consumer read models carries {@code effectiveStatus} — {@code "MACHINE"} |
 * {@code "CORRECTED"} | {@code "REJECTED"} — present and never omitted, derived server-side from
 * the row's transactionally-materialized {@code review_status} plus the presence of a read-time
 * correction.
 *
 * <p>It exists because §11.1 named the defect: a value a human REJECTED kept flowing through
 * {@code /fields} and {@code /export} as current, with nothing machine-readable saying "a named
 * human refused this". The VALUE channel stays byte-for-byte unchanged — the review surface must
 * still show what was rejected — and the new key is what lets a consumer stop USING it.
 *
 * <p>The derivation is deliberately from {@code (review_status, correction-present)}, not from a
 * separate latest-decision query: {@code review_status} is appended-and-flipped in the SAME
 * transaction as the decision row ({@code FieldCorrectionService.apply}), so it IS the latest
 * field decision, with zero extra queries and no window for a concurrent PATCH to make
 * {@code effectiveStatus} contradict {@code reviewStatus} within one response. The
 * correction-present term is what keeps CORRECT-then-CONFIRM reading CORRECTED: the served value
 * is still the human's, and this key's whole purpose is to say whose value is being served.
 */
class EffectiveStatusIT extends AbstractExtractionIT {

    @Autowired private EngineResultFinalizerPort engineResults;

    private record Seed(
            UUID packageId,
            UUID documentId,
            UUID fieldId,
            String machineDisplayed,
            String machineRaw) {}

    private record Occurrence(UUID packageId, UUID documentId, UUID fieldId) {}

    /** Full-pipeline paystub seed, netPay's row — the {@code FieldCorrectionIT.seedNetPay} shape. */
    private Seed seedPaystub(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        var row = currentFieldsByName(documentId).get("netPay");
        return new Seed(
                packageId,
                documentId,
                (UUID) row.get("id"),
                (String) row.get("displayed_text"),
                (String) row.get("raw_value"));
    }

    /**
     * Full-pipeline Schedule E seed, returning the {@code rentsReceived#C} occurrence — the
     * fixture's Part I column C is empty, so this row is machine-MISSING (method {@code NONE}).
     */
    private Occurrence seedMissingScheduleEOccurrence(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "schedule_e");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        var row = currentOccurrences(documentId).get("rentsReceived#C");
        assertThat(row).as("schedule_e leaves Part I column C empty").isNotNull();
        assertThat(row.get("extraction_method"))
                .as("the occurrence under review is machine-missing")
                .isEqualTo("NONE");
        return new Occurrence(packageId, documentId, (UUID) row.get("id"));
    }

    private static String body(String action, String value, String reason) {
        return "{\"action\":\"" + action + "\""
                + (value == null ? "" : ",\"value\":\"" + value + "\"")
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + "}";
    }

    private void decide(UUID fieldId, String action, String value) throws Exception {
        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(action, value, null)))
                .andExpect(status().isOk());
    }

    private String fieldsBody(UUID documentId) throws Exception {
        return mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String exportBody(UUID packageId) throws Exception {
        return mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Drops the two decision-bearing members from every row, so two payloads can be compared
     * byte-for-byte OUTSIDE them. The members are adjacent by construction — {@code
     * effectiveStatus} is declared immediately after {@code reviewStatus} — which is what makes
     * this exact-member pattern unambiguous.
     */
    private static String withoutDecisionMembers(String json) {
        return json.replaceAll(",\"reviewStatus\":\"[A-Z_]+\",\"effectiveStatus\":\"[A-Z]+\"", "");
    }

    private JsonNode occurrenceNode(String fieldsJson, String fieldName, String groupKey)
            throws Exception {
        for (JsonNode field : JSON.readTree(fieldsJson).get("fields")) {
            if (fieldName.equals(field.path("fieldName").asText())
                    && groupKey.equals(field.path("groupKey").asText())) {
                return field;
            }
        }
        throw new AssertionError("no occurrence " + fieldName + "#" + groupKey);
    }

    private static int countOf(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    // ── the no-decision floor ───────────────────────────────────────────────

    @Test
    void a_never_reviewed_field_reads_machine_on_fields_and_export() throws Exception {
        Seed seed = seedPaystub("effective-machine-it");

        String fields = fieldsBody(seed.documentId());
        assertThat(fields)
                .as("the key sits immediately after reviewStatus, present with its floor value")
                .contains("\"reviewStatus\":\"NOT_REVIEWED\",\"effectiveStatus\":\"MACHINE\"");
        assertThat(countOf(fields, "\"effectiveStatus\":\"MACHINE\""))
                .as("MACHINE on every no-decision field row, never omitted")
                .isEqualTo(countOf(fields, "\"groupKey\":"));

        String export = exportBody(seed.packageId());
        assertThat(export)
                .contains("\"reviewStatus\":\"NOT_REVIEWED\",\"effectiveStatus\":\"MACHINE\"");
        assertThat(countOf(export, "\"effectiveStatus\":\"MACHINE\""))
                .as("...and on every export field row too")
                .isEqualTo(countOf(export, "\"fieldName\":"));
    }

    // ── REJECT: served, and machine-readably refused ────────────────────────

    @Test
    void a_rejected_field_reads_rejected_and_still_carries_the_machine_value() throws Exception {
        Seed seed = seedPaystub("effective-reject-fields-it");
        String before = fieldsBody(seed.documentId());

        decide(seed.fieldId(), "REJECT", null);

        MvcResult result =
                mockMvc.perform(get("/v1/documents/{id}/fields", seed.documentId()))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.fields[?(@.fieldName=='netPay')].reviewStatus")
                                        .value(hasItem("REJECTED")))
                        .andExpect(
                                jsonPath("$.fields[?(@.fieldName=='netPay')].effectiveStatus")
                                        .value(hasItem("REJECTED")))
                        .andExpect(
                                jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                        .value(hasItem(seed.machineDisplayed())))
                        .andReturn();

        assertThat(withoutDecisionMembers(result.getResponse().getContentAsString()))
                .as("the REJECT moved the two status members and NOT ONE other byte — value, raw,"
                        + " normalized, method, confidence, components, validation and evidence"
                        + " still serve the machine parse the reviewer refused")
                .isEqualTo(withoutDecisionMembers(before));
    }

    @Test
    void a_rejected_field_exports_rejected() throws Exception {
        Seed seed = seedPaystub("effective-reject-export-it");

        decide(seed.fieldId(), "REJECT", null);

        mockMvc.perform(get("/v1/packages/{id}/export", seed.packageId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].reviewStatus")
                                .value(hasItem("REJECTED")))
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("REJECTED")))
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem(seed.machineDisplayed())))
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].value")
                                .value(hasItem(seed.machineRaw())));
    }

    // ── CONFIRM is not a fourth wire value ──────────────────────────────────

    @Test
    void a_confirmed_field_stays_machine() throws Exception {
        Seed seed = seedPaystub("effective-confirm-it");

        decide(seed.fieldId(), "CONFIRM", null);

        mockMvc.perform(get("/v1/documents/{id}/fields", seed.documentId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].reviewStatus")
                                .value(hasItem("CONFIRMED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("MACHINE")));
        mockMvc.perform(get("/v1/packages/{id}/export", seed.packageId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("MACHINE")));
    }

    // ── CORRECT, and the decision sequences ─────────────────────────────────

    @Test
    void a_corrected_field_reads_corrected() throws Exception {
        Seed seed = seedPaystub("effective-correct-it");

        decide(seed.fieldId(), "CORRECT", "9,999.99");

        mockMvc.perform(get("/v1/documents/{id}/fields", seed.documentId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("CORRECTED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("9,999.99")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].rawValue")
                                .value(hasItem(seed.machineRaw())));
    }

    @Test
    void reject_then_correct_reads_corrected() throws Exception {
        Seed seed = seedPaystub("effective-reject-correct-it");

        decide(seed.fieldId(), "REJECT", null);
        decide(seed.fieldId(), "CORRECT", "7,777.77");

        mockMvc.perform(get("/v1/documents/{id}/fields", seed.documentId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].reviewStatus")
                                .value(hasItem("CORRECTED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("CORRECTED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("7,777.77")));
    }

    @Test
    void correct_then_reject_reads_rejected() throws Exception {
        Seed seed = seedPaystub("effective-correct-reject-it");

        decide(seed.fieldId(), "CORRECT", "5,555.55");
        decide(seed.fieldId(), "REJECT", null);

        // The served value is still the latest correction — refused, and now SAYING so.
        mockMvc.perform(get("/v1/documents/{id}/fields", seed.documentId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].reviewStatus")
                                .value(hasItem("REJECTED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("REJECTED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("5,555.55")));
        mockMvc.perform(get("/v1/packages/{id}/export", seed.packageId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("REJECTED")))
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("5,555.55")));
    }

    /**
     * The one sequence that separates this derivation from a pure review_status (or pure
     * latest-action) mapping: after CORRECT then CONFIRM the SERVED value is still the human's
     * correction, so labeling the row MACHINE would misstate exactly the fact this key exists to
     * state. D11's purpose — "whether the value it is serving is still machine-produced" — over
     * R1's literal "latest decision" wording, flagged as such in the change description.
     */
    @Test
    void correct_then_confirm_still_reads_corrected() throws Exception {
        Seed seed = seedPaystub("effective-correct-confirm-it");

        decide(seed.fieldId(), "CORRECT", "4,444.44");
        decide(seed.fieldId(), "CONFIRM", null);

        mockMvc.perform(get("/v1/documents/{id}/fields", seed.documentId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].reviewStatus")
                                .value(hasItem("CONFIRMED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].effectiveStatus")
                                .value(hasItem("CORRECTED")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("4,444.44")));
    }

    // ── the missing-occurrence pair (§11.2 wire half, acceptance 15) ────────

    @Test
    void a_corrected_missing_occurrence_reads_corrected_not_missing() throws Exception {
        Occurrence occurrence = seedMissingScheduleEOccurrence("effective-missing-correct-it");
        String filter = "$.fields[?(@.fieldName=='rentsReceived' && @.groupKey=='C')]";

        decide(occurrence.fieldId(), "CORRECT", "1,234");

        mockMvc.perform(get("/v1/documents/{id}/fields", occurrence.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(filter + ".effectiveStatus").value(hasItem("CORRECTED")))
                .andExpect(jsonPath(filter + ".displayedText").value(hasItem("1,234")))
                .andExpect(jsonPath(filter + ".normalized.number").value(hasItem(1234)))
                // The machine facts stay the machine's: it looked and found nothing.
                .andExpect(jsonPath(filter + ".extractionMethod").value(hasItem("NONE")))
                .andExpect(jsonPath(filter + ".confidence").value(hasItem(0.0)));
    }

    @Test
    void a_rejected_missing_occurrence_is_representable() throws Exception {
        Occurrence occurrence = seedMissingScheduleEOccurrence("effective-missing-reject-it");

        decide(occurrence.fieldId(), "REJECT", null);

        JsonNode row =
                occurrenceNode(fieldsBody(occurrence.documentId()), "rentsReceived", "C");
        assertThat(row.path("effectiveStatus").asText()).isEqualTo("REJECTED");
        assertThat(row.path("displayedText").isNull())
                .as("nothing was ever captured and nothing is invented")
                .isTrue();
        // All three read surfaces still answer — no special case anywhere.
        mockMvc.perform(get("/v1/packages/{id}/export", occurrence.packageId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/documents/{id}/fields.md", occurrence.documentId()))
                .andExpect(status().isOk());
    }

    // ── the parse record is post-parse-untouchable ──────────────────────────

    @Test
    void a_reject_decision_does_not_change_engine_result_bytes() throws Exception {
        Seed seed = seedPaystub("effective-envelope-it");
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status,"
                        + " attempt, parse_generation) VALUES (?, ?, ?, ?, 'EXTRACTING', 1, 1)",
                jobId,
                ORG_DEV,
                seed.packageId(),
                "effective-status-" + jobId);
        EngineResultFinalizerPort.FinalizationResult finalized =
                engineResults.finalizeResult(jobId, seed.packageId(), 1, 1);
        // The stage runner's own commit, replayed: the current-read requires a SUCCEEDED
        // FINALIZING stage for the generation (EngineResultQueryService), which the pipeline
        // records after the finalizer returns.
        jdbc.update(
                "INSERT INTO processing_stage (id, org_id, job_id, stage, status, attempt,"
                        + " output_digest) VALUES (?, ?, ?, 'FINALIZING', 'SUCCEEDED', 1, ?)",
                UUID.randomUUID(),
                ORG_DEV,
                jobId,
                finalized.envelopeSha256());
        byte[] before = engineResultBytes(seed.packageId());

        decide(seed.fieldId(), "REJECT", null);

        assertThat(engineResultBytes(seed.packageId()))
                .as("a review decision is post-parse — the canonical envelope bytes cannot move")
                .isEqualTo(before);
        mockMvc.perform(
                        get("/v1/packages/{id}/engine-results", seed.packageId())
                                .header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    private byte[] engineResultBytes(UUID packageId) throws Exception {
        return mockMvc.perform(
                        get("/v1/packages/{id}/engine-result", packageId)
                                .header("X-Dev-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }
}
