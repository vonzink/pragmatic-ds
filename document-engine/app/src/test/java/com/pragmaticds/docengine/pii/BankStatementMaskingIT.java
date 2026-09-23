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
 * accountNumber ships {@code "sensitive": true} in the V11 seed. The fixture draws a 12-digit
 * number ON PURPOSE: exactly nine digits would take {@code MaskingService}'s SSN branch; twelve
 * proves the account/other branch ({@code ••••}+last4) on every read surface. No jdbc flip.
 */
class BankStatementMaskingIT extends AbstractExtractionIT {

    private static final String ACCOUNT_RAW = "482199021177";
    private static final String ACCOUNT_PREFIX = "48219902"; // anything more than the last four
    private static final String ACCOUNT_MASKED = "••••1177";

    private static final String CORRECTED_RAW = "771200449583";
    private static final String CORRECTED_PREFIX = "77120044";
    private static final String CORRECTED_MASKED = "••••9583";

    /** bankName is NOT sensitive — regression canary. */
    private static final String NON_SENSITIVE_VALUE = "FIRST SYNTHETIC BANK";

    private UUID seededBankPackage(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void the_fields_endpoint_masks_accountNumber_with_the_last_four_mask() throws Exception {
        UUID documentId = onlyDocumentOf(seededBankPackage("bank-fields-masking-it"));

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(ACCOUNT_RAW);
        assertThat(body).doesNotContain(ACCOUNT_PREFIX);
        assertThat(body).contains(ACCOUNT_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_export_endpoint_masks_accountNumber_with_the_last_four_mask() throws Exception {
        UUID packageId = seededBankPackage("bank-export-masking-it");

        String body =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(ACCOUNT_RAW);
        assertThat(body).doesNotContain(ACCOUNT_PREFIX);
        assertThat(body).contains(ACCOUNT_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_history_endpoint_masks_the_previous_and_corrected_account_number() throws Exception {
        UUID documentId = onlyDocumentOf(seededBankPackage("bank-history-masking-it"));
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("accountNumber").get("id");

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

        assertThat(body).doesNotContain(ACCOUNT_RAW);
        assertThat(body).doesNotContain(ACCOUNT_PREFIX);
        assertThat(body).doesNotContain(CORRECTED_RAW);
        assertThat(body).doesNotContain(CORRECTED_PREFIX);
        assertThat(body).contains(ACCOUNT_MASKED);
        assertThat(body).contains(CORRECTED_MASKED);
    }
}
