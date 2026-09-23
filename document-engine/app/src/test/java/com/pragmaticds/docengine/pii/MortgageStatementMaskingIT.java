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
 * loanNumber ships {@code "sensitive": true} in the V11 seed. The fixture's loan number
 * {@code 0087-445-921} is 10 digits (not nine, so no SSN branch) and HYPHENATED — the masked
 * form is {@code ••••-921}, because {@code MaskingService} reveals the last four CHARACTERS of
 * the raw string, hyphen included. Pinning that exact string is deliberate: it locks the
 * character-based (not digit-based) reveal contract on every read surface. No jdbc flip.
 */
class MortgageStatementMaskingIT extends AbstractExtractionIT {

    private static final String LOAN_RAW = "0087-445-921";
    private static final String LOAN_PREFIX = "0087-445"; // anything more than the last four
    private static final String LOAN_MASKED = "••••-921"; // last four CHARACTERS incl. hyphen

    private static final String CORRECTED_RAW = "5531-660-842";
    private static final String CORRECTED_PREFIX = "5531-660";
    private static final String CORRECTED_MASKED = "••••-842";

    /** lenderName is NOT sensitive — regression canary. */
    private static final String NON_SENSITIVE_VALUE = "SYNTHETIC HOME LENDING, LLC";

    private UUID seededStatementPackage(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "mortgage_statement");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void the_fields_endpoint_masks_loanNumber_with_the_last_four_characters_mask()
            throws Exception {
        UUID documentId = onlyDocumentOf(seededStatementPackage("ms-fields-masking-it"));

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(LOAN_RAW);
        assertThat(body).doesNotContain(LOAN_PREFIX);
        assertThat(body).contains(LOAN_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_export_endpoint_masks_loanNumber_with_the_last_four_characters_mask()
            throws Exception {
        UUID packageId = seededStatementPackage("ms-export-masking-it");

        String body =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(LOAN_RAW);
        assertThat(body).doesNotContain(LOAN_PREFIX);
        assertThat(body).contains(LOAN_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_history_endpoint_masks_the_previous_and_corrected_loan_number() throws Exception {
        UUID documentId = onlyDocumentOf(seededStatementPackage("ms-history-masking-it"));
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("loanNumber").get("id");

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

        assertThat(body).doesNotContain(LOAN_RAW);
        assertThat(body).doesNotContain(LOAN_PREFIX);
        assertThat(body).doesNotContain(CORRECTED_RAW);
        assertThat(body).doesNotContain(CORRECTED_PREFIX);
        assertThat(body).contains(LOAN_MASKED);
        assertThat(body).contains(CORRECTED_MASKED);
    }
}
