package com.pragmaticds.docengine.reuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
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
 * Parse-once reuse, end to end through the real upload path and the real pipeline against a
 * path-routing mock worker: a re-upload of already-parsed bytes under an unchanged behavior
 * fingerprint serves the PRIOR package — zero worker calls, zero new rows — and every doubt
 * (fingerprint drift, force flag, other org, tombstone, failure, regroup, different source set,
 * corrupt prior) parses fresh. The governing principle: serving a stale parse as current is the
 * wrong-value failure mode; when in doubt, parse again.
 */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "docengine.processing.adapter=worker",
            "docengine.processing.retry-backoff-ms=0",
            "docengine.worker.shared-secret=it-worker-secret",
            "docengine.reuse.enabled=true",
            "docengine.reuse.engine-release=it-engine-release",
            // The probe cache must not leak a /version answer across tests that reconfigure it.
            "docengine.reuse.worker-probe-cache-seconds=0",
        })
class UploadReuseIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MockWebServer WORKER = new MockWebServer();

    private static final String VERSION_BODY =
            """
            {"worker":"0.9.9-it","stateless":true,
             "libraries":{"pypdfium2":"5.12.1","pdfplumber":"0.11.10","pypdf":"6.0.0",
                          "rapidocr-onnxruntime":"1.4.4","pytesseract":null,
                          "opencv-python-headless":null,"pillow":"11.3.0","numpy":"2.3.2",
                          "fastapi":"0.116.1"}}
            """;

    private static final String TEXT_ONE_NATIVE_PAGE =
            """
            {
              "worker": { "version": "0.9.9-it", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "NATIVE",
                  "spans": [
                    { "ordinal": 0, "text": "Reuse Fixture Document",
                      "x": 72.0, "y": 84.0, "width": 140.0, "height": 10.5,
                      "fontSize": 10.5, "fontName": "Helvetica-Bold" },
                    { "ordinal": 1, "text": "Total 12.00",
                      "x": 72.0, "y": 104.0, "width": 61.0, "height": 10.5,
                      "fontSize": 10.5, "fontName": "Helvetica" }
                  ]
                }
              ]
            }
            """;

    private static final String LAYOUT_ONE_PAGE =
            """
            {
              "worker": { "version": "0.9.9-it", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "elements": [], "notImplemented": ["CHECKBOX", "SIGNATURE"] }
              ]
            }
            """;

    /** Stage calls only — /version probes are deliberately NOT stage work and are not counted. */
    private static final List<String> STAGE_CALLS = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean FAIL_RENDER = new AtomicBoolean(false);

    static {
        WORKER.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        String path =
                                request.getPath() == null
                                        ? ""
                                        : request.getPath().split("\\?")[0];
                        if ("/version".equals(path)) {
                            return json(VERSION_BODY);
                        }
                        STAGE_CALLS.add(path);
                        if ("/v1/render".equals(path)) {
                            if (FAIL_RENDER.get()) {
                                return new MockResponse()
                                        .setResponseCode(500)
                                        .setHeader("Content-Type", "application/json")
                                        .setBody("{\"error\":\"INTERNAL\",\"detail\":{}}");
                            }
                            return renderOnePage();
                        }
                        if ("/v1/text".equals(path)) {
                            return json(TEXT_ONE_NATIVE_PAGE);
                        }
                        if ("/v1/layout".equals(path)) {
                            return json(LAYOUT_ONE_PAGE);
                        }
                        // /v1/ocr must never run for NATIVE fixtures — loud, not silent.
                        return new MockResponse()
                                .setResponseCode(500)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"error\":\"UNEXPECTED_CALL\",\"detail\":{}}");
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

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    /** The /v1/render multipart/mixed shape the Java client parses (one 612x792 page at 200dpi). */
    private static MockResponse renderOnePage() {
        String boundary = "docengine-reuse-render";
        String metadata =
                """
                { "worker": { "version": "0.9.9-it", "libraries": {"pypdfium2": "5.12.1"} },
                  "pages": [ { "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0,
                               "rotation": 0, "dpi": 200, "widthPx": 1700, "heightPx": 2200,
                               "pngPart": "page-0" } ] }
                """;
        Buffer body = new Buffer();
        body.writeUtf8("--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n");
        body.writeUtf8("Content-Type: application/json\r\n\r\n");
        body.writeUtf8(metadata);
        body.writeUtf8("\r\n--" + boundary + "\r\n");
        body.writeUtf8("Content-Disposition: form-data; name=\"page-0\"\r\n");
        body.writeUtf8("Content-Type: image/png\r\n\r\n");
        body.write("REUSE-PNG-0".getBytes(StandardCharsets.UTF_8));
        body.writeUtf8("\r\n--" + boundary + "--\r\n");
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "multipart/mixed; boundary=" + boundary)
                .setBody(body);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private BlobStoragePort storage;
    @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired private com.pragmaticds.docengine.orchestration.JobService jobService;
    @Autowired private com.pragmaticds.docengine.retention.PackagePurger purger;
    @Autowired private com.pragmaticds.docengine.classification.rules.RulePackLoader packLoader;

    @Autowired
    private com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader schemaLoader;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // The test thread's own jdbc/service calls need a tenant for RLS stamping; HTTP calls
        // bind their own via DevAuthFilter and are unaffected.
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        FAIL_RENDER.set(false);
    }

    @AfterEach
    void resetWorker() {
        FAIL_RENDER.set(false);
        com.pragmaticds.docengine.platform.tenancy.TenantContext.clear();
    }

    // ── fixtures and helpers ────────────────────────────────────────────────

    /**
     * PDFBox output is fully deterministic, so a bare one-page PDF is the SAME BYTES on every
     * call — and identical bytes across tests would make reuse leak between tests. Every fixture
     * therefore carries a unique title, making each test's bytes its own.
     */
    private static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.getDocumentInformation().setTitle("reuse-it-" + UUID.randomUUID());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode upload(byte[][] files, String query, HttpHeaders extra) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < files.length; i++) {
            final String name = "file-" + i + ".pdf";
            final byte[] bytes = files[i];
            form.add(
                    "files",
                    new HttpEntity<>(
                            new ByteArrayResource(bytes) {
                                @Override
                                public String getFilename() {
                                    return name;
                                }
                            },
                            pdfPartHeaders()));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (extra != null) {
            headers.addAll(extra);
        }
        ResponseEntity<String> response =
                rest.exchange(
                        "/v1/packages" + (query == null ? "" : "?" + query),
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

    private static HttpHeaders pdfPartHeaders() {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_PDF);
        return part;
    }

    private JsonNode upload(byte[] file) {
        return upload(new byte[][] {file}, null, null);
    }

    private String jobStatus(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM processing_job WHERE id = ?", String.class, jobId);
    }

    private int stageCallCount() {
        return STAGE_CALLS.size();
    }

    private long countPackages() {
        return jdbc.queryForObject("SELECT count(*) FROM document_package", Long.class);
    }

    private long countJobs() {
        return jdbc.queryForObject("SELECT count(*) FROM processing_job", Long.class);
    }

    private long countStageRows() {
        return jdbc.queryForObject("SELECT count(*) FROM processing_stage", Long.class);
    }

    private long reuseAuditCount(UUID packageId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'PACKAGE_REUSE_SERVED'"
                        + " AND subject_id = ?",
                Long.class,
                packageId);
    }

    private static UUID uuidOf(JsonNode node, String field) {
        return UUID.fromString(node.get(field).asText());
    }

    private String documentTypeOf(UUID packageId) {
        return jdbc.queryForObject(
                "SELECT document_type_code FROM logical_document WHERE package_id = ?"
                        + " ORDER BY ordinal LIMIT 1",
                String.class,
                packageId);
    }

    private String stampOf(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT behavior_fingerprint FROM processing_job WHERE id = ?",
                String.class,
                jobId);
    }

    /**
     * An ORG-scoped W2 pack whose only anchor is the reuse fixture's own title, at a version that
     * shadows every global W2 pack. Present in the DATABASE it makes the fixture classify W2;
     * absent from a warm {@code RulePackLoader} cache it changes nothing — which is exactly the
     * admission-vs-execution split this test is about.
     */
    private UUID insertOrgPackMatchingTheFixture() {
        UUID packId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (id, org_id, document_type_code, version, definition, min_confidence,
                     is_active)
                VALUES (?, ?, 'W2', '99.0.0', ?::jsonb, 0.50, true)
                """,
                packId,
                ORG_DEV,
                "{\"targetScore\":1,\"anchors\":[{\"id\":\"reuse-fixture\",\"kind\":\"literal\","
                        + "\"pattern\":\"Reuse Fixture Document\",\"weight\":1}]}");
        return packId;
    }

    // ── the parse-once contract ─────────────────────────────────────────────

    @Test
    void sameBytesSameFingerprintReuses() {
        byte[] bytes = pdf(1);

        JsonNode first = upload(bytes);
        UUID packageId = uuidOf(first, "packageId");
        UUID jobId = uuidOf(first, "jobId");
        assertThat(jobStatus(jobId)).isEqualTo("HUMAN_REVIEW_REQUIRED");
        assertThat(first.get("reused").isNull()).as("a fresh parse carries no reuse marker").isTrue();

        long packages = countPackages();
        long jobs = countJobs();
        long stageRows = countStageRows();
        int workerCalls = stageCallCount();

        JsonNode second = upload(bytes);

        // The prior package IS the answer: same ids, explicit marker, prior file metadata.
        assertThat(uuidOf(second, "packageId")).isEqualTo(packageId);
        assertThat(uuidOf(second, "jobId")).isEqualTo(jobId);
        JsonNode reused = second.get("reused");
        assertThat(reused.isNull()).isFalse();
        assertThat(uuidOf(reused, "packageId")).isEqualTo(packageId);
        assertThat(uuidOf(reused, "jobId")).isEqualTo(jobId);
        assertThat(reused.get("engineResultRevision").asInt()).isEqualTo(1);
        assertThat(second.get("files")).hasSize(1);

        // No new package, no new job, no new stage rows — and, the whole point, ZERO new worker
        // calls: nothing was rendered, re-read, re-OCRed, or re-laid-out to serve this answer.
        assertThat(countPackages()).isEqualTo(packages);
        assertThat(countJobs()).isEqualTo(jobs);
        assertThat(countStageRows()).isEqualTo(stageRows);
        assertThat(stageCallCount())
                .as("a reuse hit performs zero worker stage calls")
                .isEqualTo(workerCalls);

        // The durable record is an audit event, not a fabricated stage row.
        assertThat(reuseAuditCount(packageId)).isEqualTo(1);
    }

    /**
     * THE flagship regression: a stamp must describe the view the parse EXECUTED under, never the
     * view that merely ADMITTED it.
     *
     * <p>Classification runs from {@code RulePackLoader}'s per-org CACHE. Nothing in main code
     * calls {@code invalidate}/{@code invalidateAll}, so a pack row inserted underneath a warm
     * cache is invisible to the classifier until the process restarts — while a fingerprint that
     * read the DATABASE would already describe it. That gap lets ONE fingerprint describe TWO
     * different outputs, and a reuse hit under it serves a package the current behavior would not
     * produce.
     *
     * <p>The proof is behavioral and needs no knowledge of where the stamp is written: after the
     * restart, the probe's answer and {@code forceReparse=true}'s answer — taken under the
     * IDENTICAL fingerprint — must be the same document type. Before the fix the probe served the
     * UNKNOWN package the stale cache produced while the forced parse said W2.
     */
    @Test
    void aStampNeverDescribesAViewTheParseDidNotExecuteUnder() {
        // (1) Warm both loader caches with view A by running one ordinary parse.
        upload(pdf(1));

        // (2) View B lands in the database. The caches still hold A, so the ENGINE still behaves
        //     as A — only a database-reading fingerprint would claim otherwise.
        UUID packId = insertOrgPackMatchingTheFixture();
        byte[] bytes = pdf(1);
        try {
            // (3) This parse EXECUTES under A.
            JsonNode admitted = upload(bytes);
            UUID admittedPackage = uuidOf(admitted, "packageId");
            assertThat(admitted.get("reused").isNull()).isTrue();
            assertThat(documentTypeOf(admittedPackage))
                    .as("the warm cache classifies this fixture UNKNOWN")
                    .isEqualTo("UNKNOWN");

            // (4) A restart (or any future invalidation caller) makes B the EXECUTING view too.
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();

            // (5) Same bytes, same org, one prospective fingerprint — two ways of answering.
            JsonNode probed = upload(bytes);
            JsonNode forced = upload(new byte[][] {bytes}, "forceReparse=true", null);

            assertThat(documentTypeOf(uuidOf(forced, "packageId")))
                    .as("under view B this fixture is a W2 — that is what the engine now does")
                    .isEqualTo("W2");
            assertThat(documentTypeOf(uuidOf(probed, "packageId")))
                    .as("a reuse hit must answer exactly what a fresh parse answers")
                    .isEqualTo(documentTypeOf(uuidOf(forced, "packageId")));
            assertThat(uuidOf(probed, "packageId"))
                    .as("the A-parse describes behavior that no longer executes; it must not serve")
                    .isNotEqualTo(admittedPackage);
        } finally {
            jdbc.update("DELETE FROM classification_rule_pack WHERE id = ?", packId);
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();
        }
    }

    @Test
    void idempotencyKeyReplayStillWinsOverReuseProbe() {
        byte[] bytes = pdf(1);
        String key = "reuse-idem-" + UUID.randomUUID();
        HttpHeaders idempotency = new HttpHeaders();
        idempotency.add("Idempotency-Key", key);

        JsonNode first = upload(new byte[][] {bytes}, null, idempotency);
        UUID packageId = uuidOf(first, "packageId");
        long auditsBefore = reuseAuditCount(packageId);

        JsonNode replay = upload(new byte[][] {bytes}, null, idempotency);

        // The replay contract answers first: original ids, NO reuse marker, NO reuse audit.
        assertThat(uuidOf(replay, "packageId")).isEqualTo(packageId);
        assertThat(uuidOf(replay, "jobId")).isEqualTo(uuidOf(first, "jobId"));
        assertThat(replay.get("reused").isNull()).isTrue();
        assertThat(reuseAuditCount(packageId)).isEqualTo(auditsBefore);
    }

    /**
     * A change to the view the engine EXECUTES under parses fresh — and a database row that has
     * not reached the engine does not, because it has not changed a single thing about what a
     * parse would produce.
     *
     * <p>The second half used to be the first half of this test: inserting the pack row alone was
     * expected to reparse, because the fingerprint read the database while classification read a
     * cache nothing invalidates. That made the fingerprint track a view the engine was not using,
     * which is the same defect from the other side — and it is what
     * {@code aStampNeverDescribesAViewTheParseDidNotExecuteUnder} turns into a served wrong
     * answer. Reuse under an unreached row is not a compromise: a fresh parse right now would
     * classify from the same warm cache and produce the same package.
     */
    @Test
    void aChangeToTheEXECUTINGViewReparses() {
        byte[] bytes = pdf(1);
        JsonNode first = upload(bytes);
        UUID firstPackage = uuidOf(first, "packageId");

        // An org-scoped pack shadows the global one for its type: once the loader sees it, the
        // org's effective classification view — and therefore the behavior fingerprint — changes.
        UUID packId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (id, org_id, document_type_code, version, definition, min_confidence, is_active)
                SELECT ?, ?, document_type_code, '99.0.0', definition, min_confidence, true
                  FROM classification_rule_pack
                 WHERE org_id IS NULL AND document_type_code = 'W2' AND is_active
                 ORDER BY version DESC
                 LIMIT 1
                """,
                packId,
                ORG_DEV);
        try {
            JsonNode stillTheOldView = upload(bytes);
            assertThat(uuidOf(stillTheOldView, "packageId"))
                    .as("a row the classifier has not loaded changes no behavior — reuse is sound")
                    .isEqualTo(firstPackage);
            assertThat(stillTheOldView.get("reused").isNull()).isFalse();

            packLoader.invalidateAll();

            JsonNode second = upload(bytes);
            assertThat(uuidOf(second, "packageId"))
                    .as("a changed EXECUTING view must parse fresh")
                    .isNotEqualTo(firstPackage);
            assertThat(second.get("reused").isNull()).isTrue();
            assertThat(jobStatus(uuidOf(second, "jobId"))).isEqualTo("HUMAN_REVIEW_REQUIRED");
        } finally {
            jdbc.update("DELETE FROM classification_rule_pack WHERE id = ?", packId);
            packLoader.invalidateAll();
        }
    }

    @Test
    void forceReparseFlagReparses() {
        byte[] bytes = pdf(1);
        JsonNode first = upload(bytes);
        UUID firstPackage = uuidOf(first, "packageId");

        int workerCallsBefore = stageCallCount();
        JsonNode forced = upload(new byte[][] {bytes}, "forceReparse=true", null);
        UUID forcedPackage = uuidOf(forced, "packageId");

        // The button event: a NEW package, a FULL parse, no marker.
        assertThat(forcedPackage).isNotEqualTo(firstPackage);
        assertThat(forced.get("reused").isNull()).isTrue();
        assertThat(stageCallCount()).isGreaterThan(workerCallsBefore);
        assertThat(jobStatus(uuidOf(forced, "jobId"))).isEqualTo("HUMAN_REVIEW_REQUIRED");

        // The prior package is untouched and independently readable.
        ResponseEntity<String> prior =
                rest.getForEntity("/v1/packages/" + firstPackage, String.class);
        assertThat(prior.getStatusCode().value()).isEqualTo(200);

        // The forced parse was admitted under the same view, so it IS a future reuse source —
        // and the newest candidate wins the next probe.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT behavior_fingerprint FROM processing_job WHERE id = ?",
                                String.class,
                                uuidOf(forced, "jobId")))
                .isNotNull();
        JsonNode third = upload(bytes);
        assertThat(uuidOf(third, "packageId")).isEqualTo(forcedPackage);
        assertThat(third.get("reused").isNull()).isFalse();
    }

    @Test
    void crossOrgNeverReuses() {
        byte[] bytes = pdf(1);
        JsonNode first = upload(bytes);
        UUID orgDevPackage = uuidOf(first, "packageId");

        HttpHeaders otherOrg = new HttpHeaders();
        otherOrg.add("X-Dev-Org", ORG_OTHER.toString());
        int workerCallsBefore = stageCallCount();
        JsonNode second = upload(new byte[][] {bytes}, null, otherOrg);

        // Same bytes, different org: an independent package and a full parse, under real RLS.
        assertThat(uuidOf(second, "packageId")).isNotEqualTo(orgDevPackage);
        assertThat(second.get("reused").isNull()).isTrue();
        assertThat(stageCallCount()).isGreaterThan(workerCallsBefore);
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_OTHER);
        try {
            assertThat(jobStatus(uuidOf(second, "jobId"))).isEqualTo("HUMAN_REVIEW_REQUIRED");
        } finally {
            com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
        }
    }

    @Test
    void tombstonedPriorNeverReuses() {
        byte[] bytes = pdf(1);
        JsonNode first = upload(bytes);
        UUID firstPackage = uuidOf(first, "packageId");

        assertThat(
                        rest.exchange(
                                        "/v1/packages/" + firstPackage,
                                        HttpMethod.DELETE,
                                        HttpEntity.EMPTY,
                                        Void.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(204);

        JsonNode second = upload(bytes);
        UUID secondPackage = uuidOf(second, "packageId");
        assertThat(secondPackage).isNotEqualTo(firstPackage);
        assertThat(second.get("reused").isNull()).isTrue();

        // Purged variant: after the rows are physically gone, a candidate cannot even exist.
        // The purge sweep is disabled in ITs; purge synchronously through the real purger.
        purge(firstPackage);
        JsonNode third = upload(bytes);
        assertThat(uuidOf(third, "packageId"))
                .isNotEqualTo(firstPackage)
                .as("a purged prior has no candidate row; the live same-bytes package serves")
                .isEqualTo(secondPackage);
        assertThat(third.get("reused").isNull()).isFalse();
    }

    private void purge(UUID packageId) {
        com.pragmaticds.docengine.platform.security.AuthContext.set(
                com.pragmaticds.docengine.platform.security.AuthPrincipal.user(
                        ORG_DEV,
                        com.pragmaticds.docengine.security.DevAuthFilter.DEV_USER,
                        "it-purge",
                        com.pragmaticds.docengine.platform.security.Role.ADMIN));
        try {
            purger.purge(packageId, ORG_DEV);
        } finally {
            com.pragmaticds.docengine.platform.security.AuthContext.clear();
        }
    }

    @Test
    void unfinalizedOrFailedPriorNeverReuses() {
        byte[] bytes = pdf(1);

        FAIL_RENDER.set(true);
        JsonNode failed = upload(bytes);
        UUID failedPackage = uuidOf(failed, "packageId");
        assertThat(jobStatus(uuidOf(failed, "jobId"))).isEqualTo("FAILED");

        FAIL_RENDER.set(false);
        JsonNode second = upload(bytes);
        assertThat(uuidOf(second, "packageId"))
                .as("a FAILED prior (no successful FINALIZING, no engine result) never serves")
                .isNotEqualTo(failedPackage);
        assertThat(second.get("reused").isNull()).isTrue();
        assertThat(jobStatus(uuidOf(second, "jobId"))).isEqualTo("HUMAN_REVIEW_REQUIRED");
    }

    @Test
    void regroupedPriorNeverReuses() {
        byte[] bytes = pdf(1);
        JsonNode first = upload(bytes);
        UUID firstPackage = uuidOf(first, "packageId");
        UUID firstJob = uuidOf(first, "jobId");

        // The regroup re-kick, through the REAL service: deletes EXTRACTING/FINALIZING stage
        // rows, claims (which nulls the fingerprint — V19), and re-runs to generation 2 inline
        // under the sync executor. No worker calls: those stages are Java-side.
        jobService.reExtract(firstPackage);
        assertThat(jobStatus(firstJob)).isEqualTo("HUMAN_REVIEW_REQUIRED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT behavior_fingerprint FROM processing_job WHERE id = ?",
                                String.class,
                                firstJob))
                .as("a re-extract claim nulls the stamp")
                .isNull();

        JsonNode second = upload(bytes);
        assertThat(uuidOf(second, "packageId"))
                .as("a regenerated (mixed-behavior) prior never serves")
                .isNotEqualTo(firstPackage);
        assertThat(second.get("reused").isNull()).isTrue();
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void sourceSetMustMatchExactly() {
        byte[] f1 = pdf(1);
        byte[] f2 = pdf(1);

        JsonNode pair = upload(new byte[][] {f1, f2}, null, null);
        UUID pairPackage = uuidOf(pair, "packageId");
        assertThat(jobStatus(uuidOf(pair, "jobId"))).isEqualTo("HUMAN_REVIEW_REQUIRED");

        // Subset, subset, and reorder: the ordinal is part of the identity — all parse fresh.
        JsonNode justF1 = upload(new byte[][] {f1}, null, null);
        assertThat(uuidOf(justF1, "packageId")).isNotEqualTo(pairPackage);
        assertThat(justF1.get("reused").isNull()).isTrue();

        JsonNode justF2 = upload(new byte[][] {f2}, null, null);
        assertThat(uuidOf(justF2, "packageId")).isNotEqualTo(pairPackage);
        assertThat(justF2.get("reused").isNull()).isTrue();

        JsonNode reordered = upload(new byte[][] {f2, f1}, null, null);
        assertThat(uuidOf(reordered, "packageId")).isNotEqualTo(pairPackage);
        assertThat(reordered.get("reused").isNull()).isTrue();

        // The exact set in the exact order still reuses.
        JsonNode exact = upload(new byte[][] {f1, f2}, null, null);
        assertThat(uuidOf(exact, "packageId")).isEqualTo(pairPackage);
        assertThat(exact.get("reused").isNull()).isFalse();
    }

    @Test
    void corruptPriorEnvelopeSkipsCandidate() {
        byte[] bytes = pdf(1);
        JsonNode first = upload(bytes);
        UUID firstPackage = uuidOf(first, "packageId");

        String storageKey =
                jdbc.queryForObject(
                        "SELECT envelope_storage_key FROM engine_result WHERE package_id = ?",
                        String.class,
                        firstPackage);
        storage.put(storageKey, "tampered-bytes".getBytes(StandardCharsets.UTF_8));

        JsonNode second = upload(bytes);
        assertThat(uuidOf(second, "packageId"))
                .as("an unverifiable prior is never served")
                .isNotEqualTo(firstPackage);
        assertThat(second.get("reused").isNull()).isTrue();
        assertThat(jobStatus(uuidOf(second, "jobId"))).isEqualTo("HUMAN_REVIEW_REQUIRED");
    }

    @Test
    void reviewDecisionsRemainReachableOnReuse() {
        byte[] bytes = pdf(1);
        JsonNode first = upload(bytes);
        UUID packageId = uuidOf(first, "packageId");
        UUID documentId =
                jdbc.queryForObject(
                        "SELECT id FROM logical_document WHERE package_id = ? ORDER BY ordinal"
                                + " LIMIT 1",
                        UUID.class,
                        packageId);

        // Two current machine rows on the PRIOR package's document (an inactive schema keeps the
        // loader views — and therefore the fingerprint — untouched). Decisions key these UUIDs.
        UUID schemaId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO extraction_schema (id, org_id, document_type_code, version,"
                        + " definition, is_active) VALUES (?, ?, 'UNKNOWN', 'reuse-it-1',"
                        + " '{}'::jsonb, false)",
                schemaId,
                ORG_DEV);
        UUID correctedField = insertField(documentId, schemaId, "reuseCorrected", "1100.00");
        UUID rejectedField = insertField(documentId, schemaId, "reuseRejected", "900.00");
        decide(correctedField, "{\"action\":\"CORRECT\",\"value\":\"1234.56\"}");
        decide(rejectedField, "{\"action\":\"REJECT\",\"reason\":\"wrong month\"}");

        JsonNode second = upload(bytes);
        assertThat(uuidOf(second, "packageId")).isEqualTo(packageId);
        assertThat(second.get("reused").isNull()).isFalse();

        // The reused package serves the reviewed CURRENT view through the one effectiveStatus
        // derivation: the correction is visible, and the refused value says REJECTED.
        ResponseEntity<String> fields =
                rest.getForEntity("/v1/documents/" + documentId + "/fields", String.class);
        assertThat(fields.getStatusCode().value()).isEqualTo(200);
        JsonNode fieldsBody = readTree(fields.getBody());
        JsonNode corrected = fieldNamed(fieldsBody, "reuseCorrected");
        JsonNode rejected = fieldNamed(fieldsBody, "reuseRejected");
        assertThat(corrected.get("effectiveStatus").asText()).isEqualTo("CORRECTED");
        assertThat(rejected.get("effectiveStatus").asText()).isEqualTo("REJECTED");
    }

    private UUID insertField(UUID documentId, UUID schemaId, String name, String rawValue) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO extracted_field
                    (id, org_id, logical_document_id, schema_id, field_name, data_type,
                     displayed_text, raw_value, extraction_method, extractor_version,
                     confidence, validation_status, review_status, is_sensitive, is_current)
                VALUES (?, ?, ?, ?, ?, 'MONEY', ?, ?, 'ANCHOR_LABEL', 'reuse-it',
                        0.9, 'VALID', 'NOT_REVIEWED', false, true)
                """,
                id,
                ORG_DEV,
                documentId,
                schemaId,
                name,
                rawValue,
                rawValue);
        return id;
    }

    /** MockMvc, not TestRestTemplate: the JDK request factory cannot send PATCH. */
    private void decide(UUID fieldId, String body) {
        try {
            mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .patch("/v1/fields/{id}", fieldId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isOk());
        } catch (Exception e) {
            throw new AssertionError("decision failed for " + fieldId, e);
        }
    }

    private static JsonNode readTree(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode fieldNamed(JsonNode fieldsBody, String name) {
        for (JsonNode field : fieldsBody.get("fields")) {
            if (name.equals(field.path("fieldName").asText())) {
                return field;
            }
        }
        throw new AssertionError("field " + name + " not present in " + fieldsBody);
    }
}
