package com.pragmaticds.docengine.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The size and page caps, driven to tiny values so the tests stay fast. Both rejections must name
 * the number that tripped and the limit — the caller can fix the upload without a support ticket —
 * and nothing else.
 */
@TestPropertySource(
        properties = {
            "docengine.ingest.max-file-bytes=4096",
            "docengine.ingest.max-pages=2"
        })
class PackageUploadLimitsIT extends AbstractIngestionIT {

    @Test
    void an_oversized_file_reports_its_size_and_the_cap() throws Exception {
        byte[] oversized = pngBytes(5000);

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("huge-appraisal.png", "image/png", oversized)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                        .andExpect(jsonPath("$.params.sizeBytes").value(5000))
                        .andExpect(jsonPath("$.params.maxBytes").value(4096))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("huge-appraisal");
        assertThat(startedJobs.calls()).isEmpty();
    }

    @Test
    void a_pdf_over_the_page_cap_reports_both_numbers() throws Exception {
        byte[] threePages = pdfWithPages(3);
        // Precondition: must trip the page cap, not the byte cap.
        assertThat(threePages.length).isLessThan(4096);

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("long-appraisal.pdf", "application/pdf", threePages)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("PAGE_LIMIT_EXCEEDED"))
                        .andExpect(jsonPath("$.params.pageCount").value(3))
                        .andExpect(jsonPath("$.params.maxPages").value(2))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("long-appraisal");
        assertThat(startedJobs.calls()).isEmpty();
    }
}
