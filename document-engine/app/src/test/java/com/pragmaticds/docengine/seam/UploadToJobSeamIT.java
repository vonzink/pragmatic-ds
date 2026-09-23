package com.pragmaticds.docengine.seam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The ingestion→orchestration seam, end to end through the REAL wiring: a multipart upload must
 * produce a real processing job that runs the full Phase 1 pipeline — no recording stub, and the
 * real async executor, so tenant propagation across the dispatch boundary is exercised too.
 *
 * <p>This is the test that makes deleting {@code SeamPlaceholderConfig} safe: with the placeholder
 * in place the returned jobId resolves to nothing and this fails.
 */
class UploadToJobSeamIT extends AbstractPostgresIT {

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;

    private static byte[] pdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Owner password set, user password EMPTY — opens with no prompt, must run the pipeline. */
    private static byte[] ownerPasswordOnlyPdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.protect(new StandardProtectionPolicy("owner-secret", "", new AccessPermission()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void upload_produces_a_real_job_that_runs_the_pipeline() throws Exception {
        runPipelineOver(pdf(2), "seam.pdf");
    }

    /**
     * The owner-password-only defect, through the real seam: accepting such a file at the probe is
     * worthless if the job it starts then dies. Same wiring, same assertions, encrypted bytes.
     */
    @Test
    void an_owner_password_only_upload_runs_the_whole_pipeline_too() throws Exception {
        runPipelineOver(ownerPasswordOnlyPdf(2), "owner-locked-seam.pdf");
    }

    private void runPipelineOver(byte[] content, String filename) throws Exception {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(
                "files",
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> upload =
                rest.postForEntity("/v1/packages", new HttpEntity<>(form, headers), String.class);
        assertThat(upload.getStatusCode().value()).isEqualTo(202);
        JsonNode uploadBody = objectMapper.readTree(upload.getBody());
        String jobId = uploadBody.get("jobId").asText();
        String packageId = uploadBody.get("packageId").asText();

        // Real async executor: poll until the pipeline lands, bounded.
        JsonNode job = null;
        for (int i = 0; i < 60; i++) {
            ResponseEntity<String> response = rest.getForEntity("/v1/jobs/" + jobId, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                job = objectMapper.readTree(response.getBody());
                if ("HUMAN_REVIEW_REQUIRED".equals(job.get("status").asText())
                        || "FAILED".equals(job.get("status").asText())) {
                    break;
                }
            }
            Thread.sleep(500);
        }

        assertThat(job).withFailMessage("job %s never became visible", jobId).isNotNull();
        assertThat(job.get("status").asText()).isEqualTo("HUMAN_REVIEW_REQUIRED");
        assertThat(job.get("packageId").asText()).isEqualTo(packageId);

        var stages = job.get("stages");
        assertThat(stages).isNotNull();
        assertThat(stages.size()).isGreaterThanOrEqualTo(14);
        assertThat(stages.findValuesAsText("stage"))
                .contains(
                        "VALIDATING",
                        "RENDERING",
                        "BOUNDARY_EXTRACTION",
                        "EXTRACTING",
                        "AI_EXTRACTION",
                        "FINALIZING",
                        "VALIDATING_DATA",
                        "AI_REVIEW");
    }
}
