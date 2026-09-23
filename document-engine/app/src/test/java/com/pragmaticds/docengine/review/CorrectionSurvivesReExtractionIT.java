package com.pragmaticds.docengine.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * What becomes of a reviewer's correction when the document is extracted again.
 *
 * <p>A regroup re-kicks extraction, and that path is an idempotent delete-then-recreate: the
 * package's {@code extracted_field} rows are replaced, so every field comes back under a NEW id. A
 * {@code review_decision} records the OLD one — {@code subject_id} is a bare uuid with no foreign
 * key, and the review overlay resolves decisions strictly by it.
 *
 * <p>So this settles whether a correction an LO made before a regroup is still the value shown
 * after it. Nothing about the decision is lost either way: the table is append-only, the audit trail
 * is intact, and the reviewer's value is still on record. The question is only whether it is still
 * the EFFECTIVE one. If it is not, the machine's reading quietly comes back in its place — a figure
 * a human looked at and rejected, restored without anyone being told. That is the one outcome the
 * whole machine-value/human-value split exists to prevent, and it would not show up as an error
 * anywhere.
 *
 * <p>This asserts the behaviour that has to hold, written before the mechanism is known to support
 * it. If it fails, the failure is the finding, and its message names both field ids so the reason is
 * visible without a second investigation.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class CorrectionSurvivesReExtractionIT extends AbstractExtractionIT {

    private static final String CORRECTED_VALUE = "9,999.99";

    @Autowired JobService jobService;

    @Test
    void a_correction_still_applies_after_the_document_is_extracted_again() throws Exception {
        UUID packageId = insertPackage("correction-survives-re-extract");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        // reExtract replays a persisted pipeline, so it needs the job + stage rows a completed run
        // would have left behind. Same seeding ReExtractIT uses.
        seedCompletedJob(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        var before = currentFieldsByName(documentId).get("netPay");
        UUID fieldIdBefore = (UUID) before.get("id");
        String machineValue = (String) before.get("displayed_text");
        // Otherwise the test could pass on the machine's own reading and prove nothing.
        assertThat(machineValue).isNotEqualTo(CORRECTED_VALUE);

        // The LO corrects what the machine read.
        mockMvc.perform(
                        patch("/v1/fields/{id}", fieldIdBefore)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action\":\"CORRECT\",\"value\":\""
                                                + CORRECTED_VALUE
                                                + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveValue").value(CORRECTED_VALUE));

        mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].displayedText")
                                .value(hasItem(CORRECTED_VALUE)))
                .andExpect(
                        jsonPath("$.fields[?(@.fieldName=='netPay')].reviewStatus")
                                .value(hasItem("CORRECTED")));

        // Re-bind the tenant before leaving HTTP behind. The mockMvc calls above ran the real
        // filter chain on THIS thread, and DevTenantFilter clears TenantContext in a finally — so
        // the binding AbstractClassificationIT's @BeforeEach installed is already gone. reExtract
        // reads TenantContext.require() directly rather than through a request, and without this
        // it fails with "no tenant bound to this thread" long before reaching what is under test.
        TenantContext.set(ORG_DEV);

        // A regroup re-kicks extraction. Synchronous under the sync executor, so the whole re-run
        // has finished by the time this returns.
        jobService.reExtract(packageId);

        UUID fieldIdAfter = (UUID) currentFieldsByName(documentId).get("netPay").get("id");
        String body =
                mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String because =
                "netPay was field %s when the LO corrected it, and the CORRECT decision still names"
                        + " that id. After the re-run netPay is field %s%s.";
        Object[] ids = {
            fieldIdBefore,
            fieldIdAfter,
            fieldIdBefore.equals(fieldIdAfter) ? "" : " — a different row, which nothing re-attaches"
        };

        List<String> displayed = JsonPath.read(body, "$.fields[?(@.fieldName=='netPay')].displayedText");
        assertThat(displayed).as(because, ids).containsExactly(CORRECTED_VALUE);

        List<String> reviewStatus = JsonPath.read(body, "$.fields[?(@.fieldName=='netPay')].reviewStatus");
        assertThat(reviewStatus).as(because, ids).containsExactly("CORRECTED");
    }
}
