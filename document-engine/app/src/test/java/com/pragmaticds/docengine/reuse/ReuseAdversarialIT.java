package com.pragmaticds.docengine.reuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.platform.behavior.BehaviorViewScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
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

/** Adversarial re-attack on parse-once reuse. Same rig as UploadReuseIT, different questions. */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "docengine.processing.adapter=worker",
            "docengine.processing.retry-backoff-ms=0",
            "docengine.worker.shared-secret=it-worker-secret",
            "docengine.reuse.enabled=true",
            "docengine.reuse.engine-release=adversarial-engine-release",
            "docengine.reuse.worker-probe-cache-seconds=0",
        })
class ReuseAdversarialIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MockWebServer WORKER = new MockWebServer();

    private static final String VERSION_BODY =
            """
            {"worker":"0.9.9-adv","stateless":true,
             "libraries":{"pypdfium2":"5.12.1","pdfplumber":"0.11.10","pypdf":"6.0.0",
                          "rapidocr-onnxruntime":"1.4.4","pytesseract":null,
                          "opencv-python-headless":null,"pillow":"11.3.0","numpy":"2.3.2",
                          "fastapi":"0.116.1"}}
            """;

    private static final String TEXT_ONE_NATIVE_PAGE =
            """
            {
              "worker": { "version": "0.9.9-adv", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                {
                  "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
                  "verdict": "NATIVE",
                  "spans": [
                    { "ordinal": 0, "text": "Adversarial Fixture Document",
                      "x": 72.0, "y": 84.0, "width": 160.0, "height": 10.5,
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
              "worker": { "version": "0.9.9-adv", "libraries": {"pdfplumber": "0.11.10"} },
              "pages": [
                { "pageIndex": 0, "elements": [], "notImplemented": ["CHECKBOX", "SIGNATURE"] }
              ]
            }
            """;

    private static final List<String> STAGE_CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean FAIL_RENDER = new AtomicBoolean(false);

    static {
        WORKER.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        String path =
                                request.getPath() == null ? "" : request.getPath().split("\\?")[0];
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

    private static MockResponse renderOnePage() {
        String boundary = "docengine-adv-render";
        String metadata =
                """
                { "worker": { "version": "0.9.9-adv", "libraries": {"pypdfium2": "5.12.1"} },
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
        body.write("ADV-PNG-0".getBytes(StandardCharsets.UTF_8));
        body.writeUtf8("\r\n--" + boundary + "--\r\n");
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "multipart/mixed; boundary=" + boundary)
                .setBody(body);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ReuseFingerprintService fingerprints;
    @Autowired private com.pragmaticds.docengine.orchestration.JobService jobService;

    // Spy beans (not plain autowires): the TOCTOU attack stubs ONE held-view read to fire a cache
    // replacement INSIDE the guard→compose window. Unstubbed, a spy delegates to the real loader, so
    // every other test in this class sees the real behavior. The autowired fingerprints service is
    // injected with these same spies, so a stub on the loader reaches the service under test.
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.pragmaticds.docengine.classification.rules.RulePackLoader packLoader;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader schemaLoader;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        FAIL_RENDER.set(false);
    }

    @AfterEach
    void tearDown() {
        FAIL_RENDER.set(false);
        com.pragmaticds.docengine.platform.tenancy.TenantContext.clear();
    }

    private static byte[] pdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle("adv-it-" + UUID.randomUUID());
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
                                return "adv.pdf";
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

    private String stampOf(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT behavior_fingerprint FROM processing_job WHERE id = ?",
                String.class,
                jobId);
    }

    private String jobStatus(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM processing_job WHERE id = ?", String.class, jobId);
    }

    private static UUID uuidOf(JsonNode node, String field) {
        return UUID.fromString(node.get(field).asText());
    }

    // ── ATTACK 1: the stamp's FIRST stated proof obligation ──────────────────

    /**
     * {@code ReuseFingerprintService.fingerprintForCompletedRun} documents three proof obligations,
     * the first being "the run RECORDED a view for each loader — a loader never consulted cannot be
     * vouched for". This asserts exactly that, at the seam: a scope in which the run recorded
     * NOTHING must yield no stamp.
     */
    @Test
    void aRunThatRecordedNoViewAtAllIsNotStamped() {
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            assertThat(BehaviorViewScope.recorded(BehaviorViewScope.ViewKind.CLASSIFICATION_PACKS))
                    .as("precondition: this run consulted no loader")
                    .isEmpty();
            assertThat(BehaviorViewScope.recorded(BehaviorViewScope.ViewKind.EXTRACTION_SCHEMAS))
                    .isEmpty();

            assertThat(fingerprints.fingerprintForCompletedRun(Set.of()))
                    .as("a loader never consulted cannot be vouched for — no stamp")
                    .isEmpty();
        }
    }

    /**
     * The narrower, live version: the run pinned classification but never extraction. This is the
     * exact case {@code ExtractionSchemaLoader.pinViewForCurrentRun} was added to work around, so
     * the guard must be the thing that fires here.
     */
    @Test
    void aRunThatPinnedOnlyClassificationIsNotStamped() {
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.fingerprintViewForCurrentOrg(); // the run consults classification only
            assertThat(BehaviorViewScope.recorded(BehaviorViewScope.ViewKind.EXTRACTION_SCHEMAS))
                    .as("precondition: extraction was never consulted")
                    .isEmpty();

            assertThat(fingerprints.fingerprintForCompletedRun(Set.of()))
                    .as("extraction behavior is unaccounted for — no stamp")
                    .isEmpty();
        }
    }

    /** Control: the mismatch half of the guard genuinely works. */
    @Test
    void aRunThatRecordedAViewThatIsNoLongerCurrentIsNotStamped() {
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.fingerprintViewForCurrentOrg();
            schemaLoader.pinViewForCurrentRun();
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();

            assertThat(fingerprints.fingerprintForCompletedRun(Set.of()))
                    .as("the view the run used is not the view in hand — no stamp")
                    .isEmpty();
        }
    }

    /** Control: a run that pinned both views, unchanged, IS stamped (the guard is not a brick). */
    @Test
    void aRunThatPinnedBothViewsIsStamped() {
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.fingerprintViewForCurrentOrg();
            schemaLoader.pinViewForCurrentRun();

            assertThat(fingerprints.fingerprintForCompletedRun(Set.of()))
                    .as("both views accounted for and still current")
                    .isNotEmpty();
        }
    }

    /**
     * The fix, pinned at its seam. The two RED tests above fail because {@code
     * fingerprintForCompletedRun} read the current view through a MINTING, RECORDING accessor — so
     * evaluating the check's argument fabricated the very recording the check then read back. This
     * asserts the property that makes those tests pass: reading a loader's held view (token AND
     * view together) is a PURE read that records nothing. Only the pipeline SERVING a snapshot may
     * record. Revert the accessor to route through {@code snapshotForCurrentOrg()} and this fails —
     * recorded is no longer empty.
     */
    @Test
    void readingTheHeldViewTokenIsPureAndRecordsNothing() {
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.heldFingerprintView();
            schemaLoader.heldFingerprintView();

            assertThat(BehaviorViewScope.recorded(BehaviorViewScope.ViewKind.CLASSIFICATION_PACKS))
                    .as("reading the held classification view must not fabricate a recording")
                    .isEmpty();
            assertThat(BehaviorViewScope.recorded(BehaviorViewScope.ViewKind.EXTRACTION_SCHEMAS))
                    .as("reading the held extraction view must not fabricate a recording")
                    .isEmpty();
        }
    }

    // ── ATTACK 2: resume, end to end ────────────────────────────────────────

    /**
     * Fail a job, change the EXECUTING view, resume it to success, then re-upload the same bytes.
     * The resumed generation blends the pre-failure stages' behavior with the replayed stages', so
     * it must never serve a reuse hit.
     */
    @Test
    void aResumedGenerationNeverServesAReuseHit() {
        byte[] bytes = pdf();

        FAIL_RENDER.set(true);
        JsonNode failed = upload(bytes);
        UUID failedPackage = uuidOf(failed, "packageId");
        UUID failedJob = uuidOf(failed, "jobId");
        assertThat(jobStatus(failedJob)).isEqualTo("FAILED");

        // Behavior moves on between the failure and the resume: an org pack the loader now sees.
        UUID packId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (id, org_id, document_type_code, version, definition, min_confidence, is_active)
                VALUES (?, ?, 'W2', '99.0.0', ?::jsonb, 0.50, true)
                """,
                packId,
                ORG_DEV,
                "{\"targetScore\":1,\"anchors\":[{\"id\":\"adv-fixture\",\"kind\":\"literal\","
                        + "\"pattern\":\"Adversarial Fixture Document\",\"weight\":1}]}");
        packLoader.invalidateAll();
        schemaLoader.invalidateAll();

        try {
            FAIL_RENDER.set(false);
            jobService.resume(failedJob);
            assertThat(jobStatus(failedJob))
                    .as("the resume completes the pipeline")
                    .isEqualTo("HUMAN_REVIEW_REQUIRED");
            assertThat(stampOf(failedJob))
                    .as("a resumed generation is a mixed-behavior artifact — never stamped")
                    .isNull();

            JsonNode second = upload(bytes);
            assertThat(second.get("reused").isNull())
                    .as("a resumed generation must never serve a reuse hit")
                    .isTrue();
            assertThat(uuidOf(second, "packageId")).isNotEqualTo(failedPackage);
        } finally {
            jdbc.update("DELETE FROM classification_rule_pack WHERE id = ?", packId);
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();
        }
    }

    // ── ATTACK 3: the guard→compose TOCTOU, and the two guards it hid ────────

    /**
     * Finding 1 — the guard→compose TOCTOU, closed. The run pins both views; the finalizer reads
     * each held snapshot (token AND view) ONCE and composes the stamp from exactly those. This
     * fires a pack-cache replacement as the finalizer performs its LAST held read — the moment a
     * re-read composer would use to reload a different view — and asserts the stamp STILL describes
     * the view the run executed under.
     *
     * <p>The mutation guard: revert the finalizer to re-read the view through {@code
     * composeDocument()}/{@code fingerprintViewForCurrentOrg()} and this fails — the stamp then
     * describes the reloaded view ({@code d6ac2190…}) instead of the run's own ({@code 5794a39c…}).
     * That reverted shape is exactly what this test caught RED before the fix.
     */
    @Test
    void theStampDescribesTheGuardValidatedViewNeverAReRead() {
        packLoader.invalidateAll();
        schemaLoader.invalidateAll();
        UUID injectedPack = null;
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.fingerprintViewForCurrentOrg(); // the run records its pack view (token Ta)
            schemaLoader.pinViewForCurrentRun(); // and its schema view

            // The fingerprint of the view the run ACTUALLY executed under — before any replacement.
            String runActual = fingerprints.fingerprint().orElseThrow();

            // A pack the loader would newly see: committed now, invisible until the cache reloads.
            final UUID packId = UUID.randomUUID();
            insertWinningW2Pack(packId);
            injectedPack = packId;

            // The window injection: as the finalizer performs its LAST held read (schemas, which
            // happens AFTER it has already captured the pack snapshot), invalidate the pack cache.
            // A re-reading composer would reload the pack view WITH the new pack and stamp THAT; a
            // single-read finalizer already holds the pack view and is untouched.
            org.mockito.Mockito.doAnswer(
                            inv -> {
                                Object real = inv.callRealMethod();
                                packLoader.invalidateAll();
                                return real;
                            })
                    .when(schemaLoader)
                    .heldFingerprintView();

            assertThat(fingerprints.fingerprintForCompletedRun(Set.of()))
                    .as("the stamp must describe the guard-validated view, never a re-read")
                    .contains(runActual);
        } finally {
            if (injectedPack != null) {
                jdbc.update("DELETE FROM classification_rule_pack WHERE id = ?", injectedPack);
            }
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();
        }
    }

    /**
     * Finding 2 — the equals-branch, isolated. The run records pack view X; that view is then
     * REPLACED (not emptied) by a different held snapshot Y≠X, with Y minted on another thread so it
     * is never recorded into this run. Recorded is present (one token, no conflict) and a snapshot
     * IS held, so neither the recorded-empty nor the held-empty branch can fire — only {@code
     * recorded.equals(held)} distinguishes this from a valid run. It must withhold.
     */
    @Test
    void aRunWhosePackViewWasReplacedByADifferentHeldViewIsNotStamped() {
        packLoader.invalidateAll();
        schemaLoader.invalidateAll();
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.fingerprintViewForCurrentOrg(); // records pack token Ta
            schemaLoader.pinViewForCurrentRun();

            replacePackCacheOnAnotherThread(); // held becomes Tb≠Ta, WITHOUT recording Tb here

            assertThat(BehaviorViewScope.recorded(BehaviorViewScope.ViewKind.CLASSIFICATION_PACKS))
                    .as("precondition: exactly one pack token recorded — no conflict, not empty")
                    .isPresent();

            assertThat(fingerprints.fingerprintForCompletedRun(Set.of()))
                    .as("the run's pack view was replaced by a different held view — no stamp")
                    .isEmpty();
        } finally {
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();
        }
    }

    /**
     * Finding 3 — the worker cross-check, isolated. Both behavior views are pinned and unchanged, so
     * the whole view guard passes; the ONLY thing that may withhold is the comparison of the worker
     * version the SUCCEEDED stage rows recorded against the version the {@code /version} probe now
     * reports. A run whose stages recorded an older worker than the probe reports straddled a worker
     * upgrade, and no single fingerprint describes it.
     */
    @Test
    void aRunWhoseStagesRecordedAnOlderWorkerThanTheProbeReportsIsNotStamped() {
        packLoader.invalidateAll();
        schemaLoader.invalidateAll();
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.fingerprintViewForCurrentOrg();
            schemaLoader.pinViewForCurrentRun();

            // The probe reports 0.9.9-adv (the mock worker); the stages say something older.
            assertThat(
                            fingerprints.fingerprintForCompletedRun(
                                    Set.of("0.0.0-worker-that-has-since-upgraded")))
                    .as("recorded worker version != probed worker version — no stamp")
                    .isEmpty();
        } finally {
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();
        }
    }

    /**
     * Finding 4 (minor) — the conflicted-kind guard, isolated. One run records TWO different tokens
     * for the pack view (a mid-run cache replacement the run straddled), while the snapshot HELD at
     * finalization equals the FIRST recorded token — so the held-vs-recorded equals-branch cannot
     * fire. Only {@code BehaviorViewScope.record}'s conflict marking (and {@code recorded}'s reading
     * of it) can turn this into a withheld stamp; neuter either and this run is stamped.
     */
    @Test
    void aRunThatRecordedTwoConflictingPackViewsIsNotStamped() {
        packLoader.invalidateAll();
        schemaLoader.invalidateAll();
        try (BehaviorViewScope.Scope scope = BehaviorViewScope.open()) {
            packLoader.fingerprintViewForCurrentOrg(); // records the real pack token Ta (cache held)
            // A SECOND, different token for the same kind — the run straddled a cache replacement.
            // The held snapshot is still Ta, so only the conflict marking distinguishes this.
            BehaviorViewScope.record(
                    BehaviorViewScope.ViewKind.CLASSIFICATION_PACKS, "a-divergent-pack-token");
            schemaLoader.pinViewForCurrentRun();

            assertThat(BehaviorViewScope.recorded(BehaviorViewScope.ViewKind.CLASSIFICATION_PACKS))
                    .as("two divergent tokens make the pack view unaccountable")
                    .isEmpty();

            assertThat(fingerprints.fingerprintForCompletedRun(Set.of()))
                    .as("the run saw two pack views — no single fingerprint describes it")
                    .isEmpty();
        } finally {
            packLoader.invalidateAll();
            schemaLoader.invalidateAll();
        }
    }

    /** A committed org W2 pack (v99) that shadows the shipped one, so a reload's view differs. */
    private void insertWinningW2Pack(UUID packId) {
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (id, org_id, document_type_code, version, definition, min_confidence, is_active)
                VALUES (?, ?, 'W2', '99.0.0', ?::jsonb, 0.50, true)
                """,
                packId,
                ORG_DEV,
                "{\"targetScore\":1,\"anchors\":[{\"id\":\"toctou\",\"kind\":\"literal\","
                        + "\"pattern\":\"Adversarial Fixture Document\",\"weight\":1}]}");
    }

    /**
     * Reloads the pack cache to a fresh snapshot (a new identity token) on a DIFFERENT thread, so
     * the replacement is NOT recorded into the test thread's open scope. The stamping DataSource
     * reads the org from the calling thread's {@link com.pragmaticds.docengine.platform.tenancy.TenantContext},
     * so the new thread binds it explicitly for RLS to engage.
     */
    private void replacePackCacheOnAnotherThread() {
        Thread other =
                new Thread(
                        () -> {
                            com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
                            try {
                                packLoader.invalidateAll();
                                packLoader.activePacksForCurrentOrg();
                            } finally {
                                com.pragmaticds.docengine.platform.tenancy.TenantContext.clear();
                            }
                        });
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
