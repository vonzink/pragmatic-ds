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
 * hoi_declaration@1.0.0's policyNumber ships {@code "sensitive": true} IN THE V11 SEED — no
 * jdbc flip, unlike {@link ResponseMaskingIT}. Full-body string assertions on every read surface.
 *
 * <p>Mask branch: {@code HO-8842716} is eight digits ignoring punctuation, so
 * {@code MaskingService.mask} takes the generic branch — {@code ••••} plus the last four
 * CHARACTERS, never the {@code •••-••-NNNN} SSN format.
 */
class PolicyNumberMaskingIT extends AbstractExtractionIT {

    private static final String POLICY_RAW = "HO-8842716";
    private static final String POLICY_PREFIX = "HO-884"; // anything more than the last four
    private static final String POLICY_MASKED = "••••2716";

    private static final String CORRECTED_RAW = "HO-9911223";
    private static final String CORRECTED_PREFIX = "HO-991";
    private static final String CORRECTED_MASKED = "••••1223";

    /** insuredName is NOT sensitive — it must flow through untouched (regression canary). */
    private static final String NON_SENSITIVE_VALUE = "Jordan Q. Fixture";

    private UUID seededHoiPackage(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "hoi_declaration");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void the_fields_endpoint_masks_policyNumber_from_the_seed_alone() throws Exception {
        UUID documentId = onlyDocumentOf(seededHoiPackage("hoi-fields-masking-it"));

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(POLICY_RAW);
        assertThat(body).doesNotContain(POLICY_PREFIX);
        assertThat(body).contains(POLICY_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_export_endpoint_masks_policyNumber_from_the_seed_alone() throws Exception {
        UUID packageId = seededHoiPackage("hoi-export-masking-it");

        String body =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(POLICY_RAW);
        assertThat(body).doesNotContain(POLICY_PREFIX);
        assertThat(body).contains(POLICY_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_history_endpoint_masks_the_previous_and_corrected_policy_number() throws Exception {
        UUID documentId = onlyDocumentOf(seededHoiPackage("hoi-history-masking-it"));
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("policyNumber").get("id");

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

        assertThat(body).doesNotContain(POLICY_RAW);
        assertThat(body).doesNotContain(POLICY_PREFIX);
        assertThat(body).doesNotContain(CORRECTED_RAW);
        assertThat(body).doesNotContain(CORRECTED_PREFIX);
        assertThat(body).contains(POLICY_MASKED);
        assertThat(body).contains(CORRECTED_MASKED);
    }
}
