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
 * licenseNumber AND dateOfBirth ship {@code "sensitive": true} IN THE V11 SEED — two masked
 * fields on one document, two mask shapes, no jdbc flip. licenseNumber is drawn 10 digits ON
 * PURPOSE (exactly nine would take {@code MaskingService}'s SSN branch): the generic
 * {@code ••••}+last4 mask. dateOfBirth masks via the generic length rule: the displayed
 * {@code 01/15/1988} becomes {@code ••••1988}. Its normalized ISO arm ({@code 1988-01-15})
 * masks to {@code ••••1-15} (last four CHARACTERS) — asserted here by raw-absence only, so the
 * test does not couple to the date arm's serialized form. Full-body string assertions so a leak
 * into ANY attribute is caught.
 */
class DriversLicenseMaskingIT extends AbstractExtractionIT {

    /** Drawn 10 digits by fixtures/generate.py so the generic last-4 branch triggers. */
    private static final String LICENSE_RAW = "941-234-5678";
    private static final String LICENSE_PREFIX = "941-234"; // anything more than the last four
    private static final String LICENSE_MASKED = "••••5678";

    private static final String DOB_RAW = "01/15/1988";
    private static final String DOB_ISO = "1988-01-15"; // the normalized date arm
    private static final String DOB_MASKED = "••••1988";

    private static final String CORRECTED_RAW = "662-441-9887";
    private static final String CORRECTED_PREFIX = "662-441";
    private static final String CORRECTED_MASKED = "••••9887";

    /** fullName is NOT sensitive — it must flow through untouched (regression canary). */
    private static final String NON_SENSITIVE_VALUE = "FIXTURE, JORDAN QUINN";

    private UUID seededLicensePackage(String packageName) {
        UUID packageId = insertPackage(packageName);
        insertFixturePages(packageId, "drivers_license");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void the_fields_endpoint_masks_licenseNumber_and_dateOfBirth_from_the_seed_alone()
            throws Exception {
        UUID documentId = onlyDocumentOf(seededLicensePackage("dl-fields-masking-it"));

        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(LICENSE_RAW);
        assertThat(body).doesNotContain(LICENSE_PREFIX);
        assertThat(body).contains(LICENSE_MASKED);
        assertThat(body).doesNotContain(DOB_RAW);
        assertThat(body).doesNotContain(DOB_ISO);
        assertThat(body).contains(DOB_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_export_endpoint_masks_licenseNumber_and_dateOfBirth_from_the_seed_alone()
            throws Exception {
        UUID packageId = seededLicensePackage("dl-export-masking-it");

        String body =
                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain(LICENSE_RAW);
        assertThat(body).doesNotContain(LICENSE_PREFIX);
        assertThat(body).contains(LICENSE_MASKED);
        assertThat(body).doesNotContain(DOB_RAW);
        assertThat(body).doesNotContain(DOB_ISO);
        assertThat(body).contains(DOB_MASKED);
        assertThat(body).contains(NON_SENSITIVE_VALUE);
    }

    @Test
    void the_history_endpoint_masks_the_previous_and_corrected_license_number() throws Exception {
        UUID documentId = onlyDocumentOf(seededLicensePackage("dl-history-masking-it"));
        UUID fieldId = (UUID) currentFieldsByName(documentId).get("licenseNumber").get("id");

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

        assertThat(body).doesNotContain(LICENSE_RAW);
        assertThat(body).doesNotContain(LICENSE_PREFIX);
        assertThat(body).doesNotContain(CORRECTED_RAW);
        assertThat(body).doesNotContain(CORRECTED_PREFIX);
        assertThat(body).contains(LICENSE_MASKED);
        assertThat(body).contains(CORRECTED_MASKED);
        // The un-corrected sensitive field must not surface raw here either.
        assertThat(body).doesNotContain(DOB_RAW);
        assertThat(body).doesNotContain(DOB_ISO);
    }
}
