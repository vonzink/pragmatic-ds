package com.pragmaticds.docengine.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.security.ActorType;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.review.FieldCorrectionService.Action;
import com.pragmaticds.docengine.security.DevAuthFilter;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The append-only correction path (docs/ARCHITECTURE.md 5-6): a CORRECT is a Layer-4
 * {@code review_decision} that overlays the effective value at read time while the machine's
 * Layer-2 {@code extracted_field} value stays permanently readable — the difference between an
 * audit trail and decoration.
 */
class FieldCorrectionIT extends AbstractExtractionIT {

    private static final UUID DEV_USER = DevAuthFilter.DEV_USER;

    @Autowired private FieldCorrectionService correctionService;

    private record Field(UUID documentId, UUID fieldId, String machineValue) {}

    /** Runs the pipeline and returns netPay's id + its untouched machine displayed value. */
    private Field seedNetPay(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        var row = currentFieldsByName(documentId).get("netPay");
        return new Field(documentId, (UUID) row.get("id"), (String) row.get("displayed_text"));
    }

    private static String body(String action, String value, String reason) {
        return "{\"action\":\"" + action + "\""
                + (value == null ? "" : ",\"value\":\"" + value + "\"")
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + "}";
    }

    @Test
    void a_correction_overlays_the_effective_value_while_the_machine_value_stays_readable()
            throws Exception {
        Field field = seedNetPay("correct-it");

        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "9,999.99", "reviewer fix")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CORRECTED"))
                .andExpect(jsonPath("$.effectiveValue").value("9,999.99"))
                .andExpect(jsonPath("$.machineValue").value(field.machineValue()))
                .andExpect(jsonPath("$.decision.action").value("CORRECT"))
                .andExpect(jsonPath("$.decision.previousValue").value(field.machineValue()))
                .andExpect(jsonPath("$.decision.newValue").value("9,999.99"))
                .andExpect(jsonPath("$.decision.decidedBy").value(DEV_USER.toString()));

        // The fields endpoint now shows the corrected value...
        mockMvc.perform(get("/v1/documents/{id}/fields", field.documentId()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("9,999.99")))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].reviewStatus")
                                .value(hasItem("CORRECTED")));

        // ...but the Layer-2 machine value is untouched in extracted_field.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT displayed_text FROM extracted_field WHERE id = ?",
                                String.class,
                                field.fieldId()))
                .isEqualTo(field.machineValue());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM extracted_field WHERE id = ?",
                                String.class,
                                field.fieldId()))
                .isEqualTo("CORRECTED");

        // One review_decision row: previous -> new, decided by the human.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT new_value->>'value' FROM review_decision WHERE subject_id = ?"
                                        + " AND action = 'CORRECT'",
                                String.class,
                                field.fieldId()))
                .isEqualTo("9,999.99");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT decided_by FROM review_decision WHERE subject_id = ? AND"
                                        + " action = 'CORRECT'",
                                UUID.class,
                                field.fieldId()))
                .isEqualTo(DEV_USER);

        // An audit_event FIELD_CORRECTED with actor_id = the principal.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actor_id FROM audit_event WHERE action = 'FIELD_CORRECTED'"
                                        + " AND subject_id = ?",
                                UUID.class,
                                field.fieldId()))
                .isEqualTo(DEV_USER);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actor_type FROM audit_event WHERE action = 'FIELD_CORRECTED'"
                                        + " AND subject_id = ?",
                                String.class,
                                field.fieldId()))
                .isEqualTo("USER");

        // The history strip: original -> corrected -> user -> timestamp, newest first.
        mockMvc.perform(get("/v1/documents/{id}/history", field.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].action").value("CORRECT"))
                .andExpect(jsonPath("$.entries[0].fieldName").value("netPay"))
                .andExpect(jsonPath("$.entries[0].previousValue").value(field.machineValue()))
                .andExpect(jsonPath("$.entries[0].newValue").value("9,999.99"))
                .andExpect(jsonPath("$.entries[0].decidedBy").value(DEV_USER.toString()))
                .andExpect(jsonPath("$.entries[0].decidedAt").isNotEmpty());
    }

    @Test
    void confirm_sets_confirmed_and_leaves_the_value() throws Exception {
        Field field = seedNetPay("confirm-it");

        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CONFIRM", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.effectiveValue").value(field.machineValue()));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM extracted_field WHERE id = ?",
                                String.class,
                                field.fieldId()))
                .isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT displayed_text FROM extracted_field WHERE id = ?",
                                String.class,
                                field.fieldId()))
                .isEqualTo(field.machineValue());
    }

    @Test
    void reject_flags_rejected_and_leaves_the_value() throws Exception {
        Field field = seedNetPay("reject-it");

        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("REJECT", null, "not a paystub value")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.effectiveValue").value(field.machineValue()));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM extracted_field WHERE id = ?",
                                String.class,
                                field.fieldId()))
                .isEqualTo("REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT displayed_text FROM extracted_field WHERE id = ?",
                                String.class,
                                field.fieldId()))
                .isEqualTo(field.machineValue());
    }

    @Test
    void two_corrections_append_two_rows_and_the_latest_wins() throws Exception {
        Field field = seedNetPay("append-only-it");

        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "100.00", null)))
                .andExpect(status().isOk());
        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "200.00", null)))
                .andExpect(status().isOk())
                // The second correction's previous is the FIRST correction, not the machine value.
                .andExpect(jsonPath("$.decision.previousValue").value("100.00"))
                .andExpect(jsonPath("$.effectiveValue").value("200.00"));

        // Two rows, not an overwrite.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM review_decision WHERE subject_id = ? AND"
                                        + " action = 'CORRECT'",
                                Integer.class,
                                field.fieldId()))
                .isEqualTo(2);

        // Latest wins for the effective value in the export/fields overlay.
        mockMvc.perform(get("/v1/documents/{id}/history", field.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].newValue").value("200.00"))
                .andExpect(jsonPath("$.entries[1].newValue").value("100.00"));
        mockMvc.perform(get("/v1/documents/{id}/fields", field.documentId()))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("200.00")));
    }

    @Test
    void the_export_overlays_the_correction_while_the_machine_value_stays_in_extracted_field()
            throws Exception {
        UUID packageId = insertPackage("export-overlay-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        var row = currentFieldsByName(documentId).get("netPay");
        UUID fieldId = (UUID) row.get("id");
        String machineRaw = (String) row.get("raw_value");

        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "8,888.88", null)))
                .andExpect(status().isOk());

        // The export shows the corrected value; the raw machine capture stays in the payload too.
        mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem("8,888.88")))
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].value")
                                .value(hasItem(machineRaw)))
                // The TYPED arm a downstream LOS ingests must reflect the human correction, not the
                // stale machine number — otherwise the consumer books the pre-correction figure
                // while displayedText shows the correction (Phase 7b review finding). netPay is a
                // MONEY field: "8,888.88" re-normalizes to 8888.88, never the machine's 3565.87.
                .andExpect(
                        jsonPath("$.documents[0].fields[?(@.fieldName=='netPay')].normalizedValue")
                                .value(hasItem(8888.88)));

        // The machine's Layer-2 value is untouched.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT raw_value FROM extracted_field WHERE id = ?",
                                String.class,
                                fieldId))
                .isEqualTo(machineRaw);
    }

    @Test
    void correct_requires_a_value() throws Exception {
        Field field = seedNetPay("correct-needs-value-it");
        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void another_orgs_field_is_indistinguishable_from_a_nonexistent_one() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        UUID foreignDocument = UUID.randomUUID();
        UUID foreignField = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package for correction");
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)"
                        + " VALUES (?, ?, ?, 0, 'PAYSTUB')",
                foreignDocument,
                ORG_OTHER,
                foreignPackage);
        jdbc.update(
                "INSERT INTO extracted_field (id, org_id, logical_document_id, schema_id,"
                        + " field_name, data_type, displayed_text, extraction_method,"
                        + " extractor_version, confidence) VALUES (?, ?, ?, (SELECT id FROM"
                        + " extraction_schema WHERE org_id IS NULL AND document_type_code = 'PAYSTUB'"
                        + " AND version = '1.0.0'), 'netPay', 'MONEY', '$1.00', 'ANCHOR_LABEL',"
                        + " 'engine/1.0.0', 0.9)",
                foreignField,
                ORG_OTHER,
                foreignDocument);

        mockMvc.perform(
                        patch("/v1/fields/{id}", foreignField)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "5.00", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void a_readonly_principal_cannot_correct_a_field() throws Exception {
        mockMvc.perform(
                        patch("/v1/fields/{id}", UUID.randomUUID())
                                .header("X-Dev-Role", "READONLY")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CONFIRM", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_reviewer_principal_can_correct_a_field() throws Exception {
        Field field = seedNetPay("reviewer-can-it");
        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .header("X-Dev-Role", "REVIEWER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "1,234.56", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveValue").value("1,234.56"));
    }

    @Test
    void a_system_principal_with_no_user_id_cannot_correct() {
        Field field = seedNetPay("system-cannot-it");
        AuthContext.set(
                new AuthPrincipal(
                        ORG_DEV, null, "system:sweep", null, ActorType.SYSTEM, Set.of()));
        try {
            assertThatThrownBy(
                            () ->
                                    correctionService.apply(
                                            field.fieldId(), Action.CORRECT, "1.00", null))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            e ->
                                    assertThat(((DomainException) e).httpStatus())
                                            .as("a human is always accountable — decided_by NOT NULL")
                                            .isEqualTo(403));
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void the_audit_metadata_carries_no_field_value() throws Exception {
        Field field = seedNetPay("no-pii-it");
        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "77777.77", "secret reviewer note")))
                .andExpect(status().isOk());

        // The corrected value never lands in audit metadata — it lives only in review_decision.
        String metadata =
                jdbc.queryForObject(
                        "SELECT metadata::text FROM audit_event WHERE action = 'FIELD_CORRECTED'"
                                + " AND subject_id = ?",
                        String.class,
                        field.fieldId());
        assertThat(metadata).doesNotContain("77777.77").doesNotContain("secret reviewer note");
        assertThat(metadata).contains("netPay").contains("CORRECT");

        // ip_hash, when present, is a hash — never the raw address.
        String ipHash =
                jdbc.queryForObject(
                        "SELECT ip_hash FROM audit_event WHERE action = 'FIELD_CORRECTED' AND"
                                + " subject_id = ?",
                        String.class,
                        field.fieldId());
        if (ipHash != null) {
            assertThat(ipHash).hasSize(64).matches("[0-9a-f]{64}");
        }
    }
    private static String bodyWithPage(String action, String value, String reason, int pageIndex) {
        return "{\"action\":\"" + action + "\",\"value\":\"" + value + "\",\"reason\":\"" + reason
                + "\",\"pageIndex\":" + pageIndex + "}";
    }

    @Test
    void a_correction_may_name_the_page_and_the_decision_keeps_it_beside_the_value()
            throws Exception {
        Field field = seedNetPay("correct-with-page-it");

        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyWithPage("CORRECT", "9,999.99", "gold label", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveValue").value("9,999.99"));

        String newValue =
                jdbc.queryForObject(
                        "SELECT new_value::text FROM review_decision"
                                + " WHERE subject_id = ? AND action = 'CORRECT'",
                        String.class,
                        field.fieldId());
        assertThat(ReviewJson.read(newValue, "value")).isEqualTo("9,999.99");
        assertThat(ReviewJson.readInt(newValue, "pageIndex")).isEqualTo(1);
    }

    @Test
    void a_correction_without_a_page_stores_no_page_key() throws Exception {
        Field field = seedNetPay("correct-no-page-it");

        mockMvc.perform(
                        patch("/v1/fields/{id}", field.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("CORRECT", "9,999.99", "reviewer fix")))
                .andExpect(status().isOk());

        String newValue =
                jdbc.queryForObject(
                        "SELECT new_value::text FROM review_decision"
                                + " WHERE subject_id = ? AND action = 'CORRECT'",
                        String.class,
                        field.fieldId());
        assertThat(ReviewJson.readInt(newValue, "pageIndex")).isNull();
    }
}
