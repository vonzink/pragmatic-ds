package com.pragmaticds.docengine.reuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The stub parser adapter is the DEFAULT ({@code @ConditionalOnProperty(matchIfMissing = true)})
 * and {@code application.yml} names no adapter — only docker-compose exports
 * {@code DOCENGINE_PROCESSING_ADAPTER=worker}. A boot that misses that variable therefore parses
 * every upload with a deterministic in-process stub, which still finalizes real
 * {@code engine_result} rows and still reaches HUMAN_REVIEW_REQUIRED. Meanwhile the
 * {@code /version} probe answers from whatever worker is listening, so nothing in the fingerprint
 * inputs notices that no worker ran.
 *
 * <p>That combination made a stub parse a fully valid reuse candidate: fix the configuration
 * later, and re-uploads of those bytes would be served the STUB's output. So the adapter's own
 * identity is a fingerprint input, and the stub declines to have one at all — under the stub there
 * is no fingerprint, which means no stamp and no reuse, in both directions.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            // Deliberately NOT set: docengine.processing.adapter. This IS the misconfiguration.
            "docengine.processing.retry-backoff-ms=0",
            "docengine.reuse.enabled=true",
            "docengine.reuse.engine-release=stub-it-engine-release",
            "docengine.reuse.worker-probe-cache-seconds=0",
        })
class StubAdapterNeverReusesIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A healthy worker answering on the configured port — exactly the trap: it is never used. */
    private static final MockWebServer WORKER = new MockWebServer();

    static {
        WORKER.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        String path =
                                request.getPath() == null ? "" : request.getPath().split("\\?")[0];
                        if ("/version".equals(path)) {
                            return new MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody(
                                            "{\"worker\":\"0.9.9-stub-it\",\"stateless\":true,"
                                                    + "\"libraries\":{\"pdfplumber\":\"0.11.10\"}}");
                        }
                        return new MockResponse().setResponseCode(500).setBody("{}");
                    }
                });
        try {
            WORKER.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void workerUrl(DynamicPropertyRegistry registry) {
        registry.add("docengine.worker.base-url", () -> WORKER.url("/").toString());
    }

    @Autowired private TestRestTemplate rest;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.clear();
    }

    private static byte[] pdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle("stub-reuse-it-" + UUID.randomUUID());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode upload(byte[] bytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        form.add(
                "files",
                new HttpEntity<>(
                        new ByteArrayResource(bytes) {
                            @Override
                            public String getFilename() {
                                return "stub.pdf";
                            }
                        },
                        part));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response =
                rest.exchange(
                        "/v1/packages",
                        HttpMethod.POST,
                        new HttpEntity<>(form, headers),
                        String.class);
        assertThat(response.getStatusCode().value())
                .as("upload accepted: %s", response.getBody())
                .isEqualTo(202);
        try {
            return JSON.readTree(response.getBody());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aStubParseIsNeverStampedAndNeverServesAReuseHit() {
        byte[] bytes = pdf();

        JsonNode first = upload(bytes);
        UUID packageId = UUID.fromString(first.get("packageId").asText());
        UUID jobId = UUID.fromString(first.get("jobId").asText());

        // The stub really does complete the pipeline — which is what made it dangerous.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_job WHERE id = ?",
                                String.class,
                                jobId))
                .isEqualTo("HUMAN_REVIEW_REQUIRED");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT behavior_fingerprint FROM processing_job WHERE id = ?",
                                String.class,
                                jobId))
                .as("a parse no worker performed is not a describable behavior — never stamp it")
                .isNull();

        JsonNode second = upload(bytes);
        assertThat(second.get("reused").isNull())
                .as("a stub parse must never be served as a real one")
                .isTrue();
        assertThat(UUID.fromString(second.get("packageId").asText()))
                .isNotEqualTo(packageId);
    }
}
