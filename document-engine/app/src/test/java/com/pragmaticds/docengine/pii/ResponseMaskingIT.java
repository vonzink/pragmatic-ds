package com.pragmaticds.docengine.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The masking boundary end to end: a field marked sensitive has its RAW value NOWHERE in the JSON of
 * the three value-bearing reads, and its masked form present. Asserting on the FULL response body
 * (not a JSON path) is deliberate — a leak into an unexpected attribute would still be caught.
 *
 * <p>The sensitive field is TEST-ONLY: an existing extracted field is flipped to {@code is_sensitive
 * = true} with a known SSN, in the test database only. The production V7 seed marks no field
 * sensitive — masking is a latent-but-correct control, proven here without shipping any PII field.
 */
class ResponseMaskingIT extends AbstractExtractionIT {

    private static final String SSN_RAW = "123-45-6789";
    private static final String SSN_PREFIX = "123-45"; // anything more than the last four
    private static final String SSN_MASKED = "•••-••-6789";

    private static final String CORRECTED_RAW = "987-65-4321";
    private static final String CORRECTED_PREFIX = "987-65";
    private static final String CORRECTED_MASKED = "•••-••-4321";

    /** A non-sensitive field's value must still flow through untouched (regression). */
    private static final String NON_SENSITIVE_VALUE = "ACME WIDGETS LLC";

    private record Sensitive(UUID documentId, UUID fieldId) {}

    /** Runs the pipeline, then flips borrowerName to a sensitive SSN — test DB only. */
    private Sensitive seedSensitiveField(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Object> borrowerName = currentFieldsByName(documentId).get("borrowerName");
        UUID fieldId = (UUID) borrowerName.get("id");
        jdbc.update(
                "UPDATE extracted_field SET is_sensitive = true, displayed_text = ?, raw_value = ?,"
                        + " normalized_text = ?, normalized_number = NULL, normalized_date = NULL"
                        + " WHERE id = ?",
                SSN_RAW,
                SSN_RAW,
                SSN_RAW,
                fieldId);
        return new Sensitive(documentId, fieldId);
    }

    @Test
    void the_fields_endpoint_masks_the_sensitive_value_everywhere_and_leaves_others_alone()
            throws Exception {
        Sensitive sensitive = seedSensitiveField("fields-masking-it");

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", sensitive.documentId()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(SSN_RAW);
        assertThat(body).doesNotContain(SSN_PREFIX);
        assertThat(body).contains(SSN_MASKED);
        // Regression: a non-sensitive field is unmasked.
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_export_endpoint_masks_the_sensitive_value_everywhere_and_leaves_others_alone()
            throws Exception {
        Sensitive sensitive = seedSensitiveField("export-masking-it");
        UUID packageId =
                jdbc.queryForObject(
                        "SELECT package_id FROM logical_document WHERE id = ?",
                        UUID.class,
                        sensitive.documentId());

        String body =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(SSN_RAW);
        assertThat(body).doesNotContain(SSN_PREFIX);
        assertThat(body).contains(SSN_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_history_endpoint_masks_both_the_previous_and_the_corrected_sensitive_value()
            throws Exception {
        Sensitive sensitive = seedSensitiveField("history-masking-it");

        // A correction moves the sensitive value from the machine SSN to a new SSN. Both the
        // previous and the new value land in review_decision and must be masked on read-back.
        mockMvc.perform(
                        patch("/v1/fields/{id}", sensitive.fieldId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action\":\"CORRECT\",\"value\":\""
                                                + CORRECTED_RAW
                                                + "\"}"))
                .andExpect(status().isOk());

        String body =
                mockMvc.perform(get("/v1/documents/{id}/history", sensitive.documentId()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(SSN_RAW);
        assertThat(body).doesNotContain(SSN_PREFIX);
        assertThat(body).doesNotContain(CORRECTED_RAW);
        assertThat(body).doesNotContain(CORRECTED_PREFIX);
        assertThat(body).contains(SSN_MASKED);
        assertThat(body).contains(CORRECTED_MASKED);
    }

    /**
     * FIX 2 (HIGH): the correction WRITE response ({@code PATCH /v1/fields/{id}}) must mask the
     * value too. Its {@code machineValue}/{@code effectiveValue} and {@code decision.previousValue}/
     * {@code decision.newValue} carried raw {@code String}s that reached the wire without passing
     * through {@code MaskingSerializer} — a leak the ArchUnit guard did not cover for the review
     * package. Asserting on the FULL body: no raw value anywhere, masked form present.
     */
    @Test
    void the_patch_correction_response_masks_the_sensitive_previous_and_new_values()
            throws Exception {
        Sensitive sensitive = seedSensitiveField("patch-masking-it");

        String body =
                mockMvc.perform(
                                patch("/v1/fields/{id}", sensitive.fieldId())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"action\":\"CORRECT\",\"value\":\""
                                                        + CORRECTED_RAW
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // machineValue + decision.previousValue = the machine SSN; effectiveValue + newValue = the
        // corrected SSN. Neither raw form may appear; both masked forms must.
        assertThat(body).doesNotContain(SSN_RAW);
        assertThat(body).doesNotContain(SSN_PREFIX);
        assertThat(body).doesNotContain(CORRECTED_RAW);
        assertThat(body).doesNotContain(CORRECTED_PREFIX);
        assertThat(body).contains(SSN_MASKED);
        assertThat(body).contains(CORRECTED_MASKED);
    }

    /**
     * FIX 3 (MEDIUM): a sensitive MONEY field's normalized NUMBER arm bypassed masking — only the
     * text arm was a {@link com.pragmaticds.docengine.platform.pii.MaskableValue}, so the raw typed number
     * reached the wire. A non-sensitive field's number must stay a plain JSON number (the wire shape
     * is unchanged for everything not sensitive).
     */
    @Test
    void the_fields_endpoint_masks_a_sensitive_money_fields_normalized_number() throws Exception {
        UUID packageId = insertPackage("normalized-number-masking-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        UUID documentId = onlyDocumentOf(packageId);

        UUID netPayId = (UUID) currentFieldsByName(documentId).get("netPay").get("id");
        // netPay is MONEY: flip it sensitive with a distinctive normalized number.
        jdbc.update(
                "UPDATE extracted_field SET is_sensitive = true, displayed_text = '424242.42',"
                        + " raw_value = '424242.42', normalized_text = NULL, normalized_number ="
                        + " 424242.42, normalized_date = NULL WHERE id = ?",
                netPayId);

        // A non-sensitive MONEY field with a normalized number is the regression canary.
        Map<String, Object> plain =
                jdbc.queryForMap(
                        "SELECT field_name, normalized_number FROM extracted_field WHERE"
                                + " logical_document_id = ? AND data_type = 'MONEY' AND"
                                + " normalized_number IS NOT NULL AND is_sensitive = false AND"
                                + " is_current = true LIMIT 1",
                        documentId);
        String plainField = (String) plain.get("field_name");
        BigDecimal plainNumber = (BigDecimal) plain.get("normalized_number");

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        // Regression: the non-sensitive field's number is still a plain JSON number.
                        .andExpect(
                                jsonPath(
                                                "$.fields[?(@.fieldName=='"
                                                        + plainField
                                                        + "')].normalized.number")
                                        .value(hasItem(plainNumber.doubleValue())))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // The sensitive field's raw normalized number never reaches the wire; masked marker present.
        assertThat(body).doesNotContain("424242.42");
        assertThat(body).contains("•");
    }
}
