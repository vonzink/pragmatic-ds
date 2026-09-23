package com.pragmaticds.docengine.seam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Idempotency through the REAL wiring (no recording stub): a client whose response was lost
 * retries the identical POST with the same Idempotency-Key and must get the ORIGINAL result back —
 * same job, same package, nothing new created.
 *
 * <p>Born as the reproduction of a review finding: the original wiring 500'd here, because
 * JobService's insert-and-catch recovery ran inside UploadService's still-open transaction, which
 * Postgres aborts on the unique violation (25P02) — the recovery path was dead code in production.
 */
class IdempotentReplaySeamIT extends AbstractPostgresIT {

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

    private ResponseEntity<String> upload(byte[] bytes, String idempotencyKey) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(
                "files",
                new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return "replay.pdf";
                    }
                });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "replay-key-fixed");
        return rest.postForEntity("/v1/packages", new HttpEntity<>(form, headers), String.class);
    }

    @Test
    void replaying_the_same_idempotency_key_returns_the_existing_job() throws Exception {
        byte[] bytes = pdf(3);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        ResponseEntity<String> first = upload(bytes, "replay-key-fixed");
        assertThat(first.getStatusCode().value())
                .withFailMessage("first upload failed: %s %s", first.getStatusCode(), first.getBody())
                .isEqualTo(202);
        JsonNode firstBody = objectMapper.readTree(first.getBody());
        String firstJobId = firstBody.get("jobId").asText();

        long packagesAfterFirst =
                jdbc.queryForObject("SELECT count(*) FROM document_package", Long.class);

        // The retry: same key, same bytes — the whole point of the header.
        ResponseEntity<String> replay = upload(bytes, "replay-key-fixed");

        long packagesAfterReplay =
                jdbc.queryForObject("SELECT count(*) FROM document_package", Long.class);
        Long jobRows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM processing_job WHERE idempotency_key = 'replay-key-fixed'",
                        Long.class);

        String observed =
                "REPLAY OBSERVED -> status="
                        + replay.getStatusCode().value()
                        + " body="
                        + replay.getBody()
                        + " | packagesAfterFirst="
                        + packagesAfterFirst
                        + " packagesAfterReplay="
                        + packagesAfterReplay
                        + " jobRowsForKey="
                        + jobRows
                        + " firstJobId="
                        + firstJobId;

        assertThat(replay.getStatusCode().value()).withFailMessage(observed).isEqualTo(202);
        JsonNode replayBody = objectMapper.readTree(replay.getBody());
        assertThat(replayBody.get("jobId").asText()).withFailMessage(observed).isEqualTo(firstJobId);
        assertThat(replayBody.get("packageId").asText())
                .withFailMessage(observed)
                .isEqualTo(firstBody.get("packageId").asText());
        // The replay must not have created a second package or job.
        assertThat(packagesAfterReplay).withFailMessage(observed).isEqualTo(packagesAfterFirst);
        assertThat(jobRows).withFailMessage(observed).isEqualTo(1L);
    }
}
