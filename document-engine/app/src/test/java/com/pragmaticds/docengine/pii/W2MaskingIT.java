package com.pragmaticds.docengine.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * employeeSsn ships {@code "sensitive": true} IN THE V11 SEED — the first production use of the
 * masking boundary. Unlike {@link ResponseMaskingIT} there is NO jdbc sensitivity flip: the
 * pipeline runs on the w2_form fixture and the mask must hold on every read surface from seed
 * data alone. Full-body string assertions so a leak into ANY attribute is caught.
 */
class W2MaskingIT extends AbstractExtractionIT {

    /** Drawn XXX-XX-NNNN-shaped by fixtures/generate.py so the SSN mask branch triggers. */
    private static final String SSN_RAW = "123-45-6789";
    private static final String SSN_PREFIX = "123-45"; // anything more than the last four
    private static final String SSN_MASKED = "•••-••-6789";

    private static final String CORRECTED_RAW = "987-65-4321";
    private static final String CORRECTED_PREFIX = "987-65";
    private static final String CORRECTED_MASKED = "•••-••-4321";

    /** employerName is NOT sensitive — it must flow through untouched (regression canary). */
    private static final String NON_SENSITIVE_VALUE = "ACME WIDGETS LLC";

    private UUID seededW2Package(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "w2_form");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void the_fields_endpoint_masks_employeeSsn_from_the_seed_alone() throws Exception {
        UUID documentId = onlyDocumentOf(seededW2Package("w2-fields-masking-it"));

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
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
    void the_export_endpoint_masks_employeeSsn_from_the_seed_alone() throws Exception {
        UUID packageId = seededW2Package("w2-export-masking-it");

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
    void the_history_endpoint_masks_the_previous_and_corrected_ssn() throws Exception {
        UUID documentId = onlyDocumentOf(seededW2Package("w2-history-masking-it"));
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("employeeSsn").get("id");

        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action\":\"CORRECT\",\"value\":\""
                                                + CORRECTED_RAW
                                                + "\"}"))
                .andExpect(status().isOk());

        String body =
                mockMvc.perform(get("/v1/documents/{id}/history", documentId))
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
}
