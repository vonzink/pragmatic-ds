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
 * tax_return@1.0.0's primarySsn ships {@code "sensitive": true} IN THE V11 SEED — no jdbc flip,
 * unlike {@link ResponseMaskingIT}. Full-body string assertions on every read surface.
 *
 * <p>Mask branch: {@code 987-65-4321} is exactly nine digits ignoring punctuation, so
 * {@code MaskingService.mask} takes the SSN branch — {@code •••-••-NNNN}, never the
 * {@code ••••}+last4 account mask. No checkbox elements are seeded here: filingStatus lands as
 * the missing contract, which is irrelevant to masking — the SSN extracts from spans alone.
 */
class PrimarySsnMaskingIT extends AbstractExtractionIT {

    private static final String SSN_RAW = "987-65-4321";
    private static final String SSN_PREFIX = "987-65"; // anything more than the last four
    private static final String SSN_MASKED = "•••-••-4321";

    private static final String CORRECTED_RAW = "321-54-9876";
    private static final String CORRECTED_PREFIX = "321-54";
    private static final String CORRECTED_MASKED = "•••-••-9876";

    /** primaryTaxpayerName is NOT sensitive — it must flow through untouched (canary). */
    private static final String NON_SENSITIVE_VALUE = "Jordan Q. Fixture";

    private UUID seededTaxReturnPackage(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "tax_return");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void the_fields_endpoint_masks_primarySsn_from_the_seed_alone() throws Exception {
        UUID documentId = onlyDocumentOf(seededTaxReturnPackage("tax-fields-masking-it"));

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
    void the_export_endpoint_masks_primarySsn_from_the_seed_alone() throws Exception {
        UUID packageId = seededTaxReturnPackage("tax-export-masking-it");

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
    void the_history_endpoint_masks_the_previous_and_corrected_primary_ssn() throws Exception {
        UUID documentId = onlyDocumentOf(seededTaxReturnPackage("tax-history-masking-it"));
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("primarySsn").get("id");

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
