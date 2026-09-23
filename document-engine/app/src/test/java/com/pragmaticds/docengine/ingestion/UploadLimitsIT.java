package com.pragmaticds.docengine.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Review finding: an upload exceeding the SERVLET multipart limit never reached the service's
 * FILE_TOO_LARGE check — Tomcat threw first, and the generic handler turned it into 500 INTERNAL.
 * An oversize upload must surface as FILE_TOO_LARGE regardless of WHICH layer catches it.
 */
@TestPropertySource(
        properties = {
            // Multipart cap far below the ingest cap, so Tomcat rejects first.
            "spring.servlet.multipart.max-file-size=10KB",
            "spring.servlet.multipart.max-request-size=20KB",
            "docengine.ingest.max-file-bytes=104857600",
        })
class UploadLimitsIT extends AbstractPostgresIT {

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;

    @Test
    void oversize_multipart_upload_reports_FILE_TOO_LARGE_not_INTERNAL() throws Exception {
        byte[] big = new byte[40 * 1024];
        big[0] = '%'; // irrelevant — the request must die at the servlet layer

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(
                "files",
                new ByteArrayResource(big) {
                    @Override
                    public String getFilename() {
                        return "big.pdf";
                    }
                });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response =
                rest.postForEntity("/v1/packages", new HttpEntity<>(form, headers), String.class);

        assertThat(response.getStatusCode().value())
                .withFailMessage("got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(413);
        assertThat(objectMapper.readTree(response.getBody()).get("code").asText())
                .isEqualTo("FILE_TOO_LARGE");
    }
}
