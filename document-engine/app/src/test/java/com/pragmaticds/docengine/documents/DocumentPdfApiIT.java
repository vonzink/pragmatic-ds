package com.pragmaticds.docengine.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.AbstractClassificationIT;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase A — {@code GET /v1/documents/{id}/pdf} + its signed-url pair: a logical document burst to
 * its own PDF, assembled by the worker from the ORIGINAL upload bytes.
 *
 * <p>The worker is a MockWebServer speaking the {@code /v1/burst} contract, so the assertions here
 * are about what the ENGINE does: which source bytes it ships, which (part, index) sequence it
 * asks for (the resolution from {@code logical_document_page} ordinals through {@code
 * page.source_file_id}/{@code page.page_index} — the whole point of the endpoint), which headers
 * it serves, and that every access rule of {@code /v1/files/{id}/content} holds here too
 * (cross-tenant 404, tombstone 404, org-bound signed tokens). Byte-level burst fidelity is the
 * worker's own suite ({@code worker/tests/burst}); the two halves meet at the pinned contract.
 */
class DocumentPdfApiIT extends AbstractClassificationIT {

    private static final MockWebServer WORKER = new MockWebServer();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the mock worker returns as the assembled PDF — content is opaque to the engine. */
    private static final byte[] BURST_PDF =
            "%PDF-1.7 fake-burst-result".getBytes(StandardCharsets.UTF_8);

    @Autowired private BlobStoragePort storage;

    @DynamicPropertySource
    static void workerUrl(DynamicPropertyRegistry registry) {
        registry.add("docengine.worker.base-url", () -> WORKER.url("/").toString());
    }

    @AfterAll
    static void drainWorker() throws Exception {
        // Leave no queued responses or unread requests for another IT class.
        while (WORKER.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
            // drain
        }
    }

    @BeforeEach
    void primeWorker() {
        // Each test enqueues exactly what it needs; nothing is primed globally.
    }

    // ── seeding ─────────────────────────────────────────────────────────────

    private UUID insertSource(
            UUID packageId, UUID orgId, int ordinal, String storageKey, byte[] bytes) {
        storage.put(storageKey, bytes);
        UUID sourceFileId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, ?, 'fixture.pdf', 'application/pdf', ?, ?, ?)
                """,
                sourceFileId,
                orgId,
                packageId,
                ordinal,
                bytes.length,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                storageKey);
        return sourceFileId;
    }

    private UUID insertPage(
            UUID sourceFileId, UUID packageId, UUID orgId, int pageIndex, int packagePageIndex) {
        UUID pageId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO page (id, org_id, source_file_id, package_id, page_index,
                    package_page_index, width_pt, height_pt, rotation, text_layer, is_blank)
                VALUES (?, ?, ?, ?, ?, ?, 612, 792, 0, 'NATIVE', false)
                """,
                pageId,
                orgId,
                sourceFileId,
                packageId,
                pageIndex,
                packagePageIndex);
        return pageId;
    }

    private UUID insertDocument(UUID packageId, UUID orgId, List<UUID> pageIdsInOrder) {
        UUID documentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)
                VALUES (?, ?, ?, 0, 'PAYSTUB')
                """,
                documentId,
                orgId,
                packageId);
        for (int ordinal = 0; ordinal < pageIdsInOrder.size(); ordinal++) {
            jdbc.update(
                    """
                    INSERT INTO logical_document_page (org_id, logical_document_id, page_id, ordinal)
                    VALUES (?, ?, ?, ?)
                    """,
                    orgId,
                    documentId,
                    pageIdsInOrder.get(ordinal),
                    ordinal);
        }
        return documentId;
    }

    /** A contract-shaped {@code /v1/burst} success response. */
    private static MockResponse burstResponse(byte[] pdfBytes) {
        String boundary = "docengine-burst-fixture";
        String metadata =
                """
                { "worker": { "version": "0.2.0", "libraries": {"pypdf": "6.14.2", "pillow": "12.3.0"} },
                  "pageCount": 1, "pdfPart": "pdf" }
                """;
        Buffer body = new Buffer();
        body.writeUtf8("--" + boundary + "\r\n")
                .writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n")
                .writeUtf8("Content-Type: application/json\r\n\r\n")
                .writeUtf8(metadata)
                .writeUtf8("\r\n--" + boundary + "\r\n")
                .writeUtf8("Content-Disposition: form-data; name=\"pdf\"\r\n")
                .writeUtf8("Content-Type: application/pdf\r\n\r\n")
                .write(pdfBytes)
                .writeUtf8("\r\n--" + boundary + "--\r\n");
        return new MockResponse()
                .setHeader("Content-Type", "multipart/mixed; boundary=\"" + boundary + "\"")
                .setBody(body);
    }

    /** The mock's view of the engine's request: the JSON sequence + which parts carried bytes. */
    private record BurstCall(JsonNode pages, String bodyText) {}

    private BurstCall takeBurstCall() throws Exception {
        RecordedRequest request = WORKER.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/burst");
        String body = request.getBody().readString(StandardCharsets.ISO_8859_1);
        int start = body.indexOf("name=\"request\"");
        assertThat(start).as("request part present").isPositive();
        int jsonStart = body.indexOf("{", start);
        int jsonEnd = body.indexOf("\r\n--", jsonStart);
        JsonNode json = MAPPER.readTree(body.substring(jsonStart, jsonEnd));
        return new BurstCall(json.get("pages"), body);
    }

    // ── the resolution: links → (source ordinal, page index), in link order ──

    @Test
    void bursts_a_single_source_document_with_source_page_indices_in_link_order() throws Exception {
        UUID packageId = insertPackage("burst-single");
        byte[] sourceBytes = "%PDF-1.7 original-source".getBytes(StandardCharsets.UTF_8);
        UUID sourceId = insertSource(packageId, ORG_DEV, 0, "it-burst/" + packageId, sourceBytes);
        // Three package pages; the document owns pages 2 and 0 of the SOURCE, in that order.
        UUID pageA = insertPage(sourceId, packageId, ORG_DEV, 0, 0);
        insertPage(sourceId, packageId, ORG_DEV, 1, 1);
        UUID pageC = insertPage(sourceId, packageId, ORG_DEV, 2, 2);
        UUID documentId = insertDocument(packageId, ORG_DEV, List.of(pageC, pageA));

        WORKER.enqueue(burstResponse(BURST_PDF));

        MvcResult result =
                mockMvc.perform(get("/v1/documents/{id}/pdf", documentId))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(BURST_PDF);
        assertThat(result.getResponse().getHeader("X-Document-Type")).isEqualTo("PAYSTUB");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"PAYSTUB-" + documentId + ".pdf\"");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        BurstCall call = takeBurstCall();
        // The sequence is SOURCE page indices in LINK ordinal order — not package order.
        assertThat(call.pages()).hasSize(2);
        assertThat(call.pages().get(0).get("part").asText()).isEqualTo("file-0");
        assertThat(call.pages().get(0).get("index").asInt()).isEqualTo(2);
        assertThat(call.pages().get(1).get("index").asInt()).isEqualTo(0);
        // The engine shipped the ORIGINAL source bytes, once.
        assertThat(call.bodyText()).contains("%PDF-1.7 original-source");
    }

    @Test
    void a_document_spanning_two_source_files_interleaves_parts_in_link_order() throws Exception {
        UUID packageId = insertPackage("burst-cross-source");
        UUID sourceA =
                insertSource(
                        packageId,
                        ORG_DEV,
                        0,
                        "it-burst-a/" + packageId,
                        "%PDF-1.7 source-A".getBytes(StandardCharsets.UTF_8));
        UUID sourceB =
                insertSource(
                        packageId,
                        ORG_DEV,
                        1,
                        "it-burst-b/" + packageId,
                        "%PDF-1.7 source-B".getBytes(StandardCharsets.UTF_8));
        UUID pageA0 = insertPage(sourceA, packageId, ORG_DEV, 0, 0);
        UUID pageB0 = insertPage(sourceB, packageId, ORG_DEV, 0, 1);
        UUID pageA1 = insertPage(sourceA, packageId, ORG_DEV, 1, 2);
        // Link order alternates sources: A0, B0, A1.
        UUID documentId = insertDocument(packageId, ORG_DEV, List.of(pageA0, pageB0, pageA1));

        WORKER.enqueue(burstResponse(BURST_PDF));
        mockMvc.perform(get("/v1/documents/{id}/pdf", documentId)).andExpect(status().isOk());

        BurstCall call = takeBurstCall();
        assertThat(call.pages()).hasSize(3);
        // file-0 is source A (first appearance), file-1 is source B; the sequence interleaves.
        assertThat(call.pages().get(0).get("part").asText()).isEqualTo("file-0");
        assertThat(call.pages().get(0).get("index").asInt()).isZero();
        assertThat(call.pages().get(1).get("part").asText()).isEqualTo("file-1");
        assertThat(call.pages().get(1).get("index").asInt()).isZero();
        assertThat(call.pages().get(2).get("part").asText()).isEqualTo("file-0");
        assertThat(call.pages().get(2).get("index").asInt()).isEqualTo(1);
        // BOTH sources' bytes travelled, each once.
        assertThat(call.bodyText()).contains("%PDF-1.7 source-A").contains("%PDF-1.7 source-B");
    }

    // ── authorization parity with /v1/files/{id}/content ────────────────────

    @Test
    void a_cross_tenant_document_is_404_never_403() throws Exception {
        UUID packageId = insertPackage("burst-cross-tenant");
        // The package row belongs to ORG_DEV but the document belongs to ORG_OTHER: the org-scoped
        // document load must already refuse, before any package or byte access.
        UUID sourceId =
                insertSource(packageId, ORG_DEV, 0, "it-burst-ct/" + packageId, BURST_PDF);
        UUID pageId = insertPage(sourceId, packageId, ORG_DEV, 0, 0);
        UUID foreignDocument = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)
                VALUES (?, ?, ?, 0, 'PAYSTUB')
                """,
                foreignDocument,
                ORG_OTHER,
                packageId);
        jdbc.update(
                """
                INSERT INTO logical_document_page (org_id, logical_document_id, page_id, ordinal)
                VALUES (?, ?, ?, 0)
                """,
                ORG_OTHER,
                foreignDocument,
                pageId);

        mockMvc.perform(get("/v1/documents/{id}/pdf", foreignDocument))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/documents/{id}/pdf/signed-url", foreignDocument))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_soft_deleted_packages_document_serves_nothing_and_mints_no_token() throws Exception {
        UUID packageId = insertPackage("burst-tombstone");
        UUID sourceId = insertSource(packageId, ORG_DEV, 0, "it-burst-ts/" + packageId, BURST_PDF);
        UUID pageId = insertPage(sourceId, packageId, ORG_DEV, 0, 0);
        UUID documentId = insertDocument(packageId, ORG_DEV, List.of(pageId));

        jdbc.update("UPDATE document_package SET deleted_at = now() WHERE id = ?", packageId);

        mockMvc.perform(get("/v1/documents/{id}/pdf", documentId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/documents/{id}/pdf/signed-url", documentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_document_with_no_pages_is_absent_not_an_empty_pdf() throws Exception {
        UUID packageId = insertPackage("burst-empty");
        UUID documentId = insertDocument(packageId, ORG_DEV, List.of());

        mockMvc.perform(get("/v1/documents/{id}/pdf", documentId))
                .andExpect(status().isNotFound());
    }

    // ── the signed-url pair ─────────────────────────────────────────────────

    @Test
    void signed_url_round_trips_without_a_session_and_dies_with_the_package() throws Exception {
        UUID packageId = insertPackage("burst-signed");
        UUID sourceId = insertSource(packageId, ORG_DEV, 0, "it-burst-su/" + packageId, BURST_PDF);
        UUID pageId = insertPage(sourceId, packageId, ORG_DEV, 0, 0);
        UUID documentId = insertDocument(packageId, ORG_DEV, List.of(pageId));

        // Issuing costs no worker call — nothing is enqueued yet.
        MvcResult issued =
                mockMvc.perform(get("/v1/documents/{id}/pdf/signed-url", documentId))
                        .andExpect(status().isOk())
                        .andReturn();
        String url =
                MAPPER.readTree(issued.getResponse().getContentAsString()).get("url").asText();
        assertThat(url).startsWith("/v1/download?token=");

        WORKER.enqueue(burstResponse(BURST_PDF));
        MvcResult downloaded =
                mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        assertThat(downloaded.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(downloaded.getResponse().getContentAsByteArray()).isEqualTo(BURST_PDF);
        assertThat(downloaded.getResponse().getHeader("X-Document-Type")).isEqualTo("PAYSTUB");
        takeBurstCall();

        // A token minted before soft delete stops working — same guarantee as the other kinds.
        jdbc.update("UPDATE document_package SET deleted_at = now() WHERE id = ?", packageId);
        mockMvc.perform(get(url)).andExpect(status().isNotFound());
    }

    // ── the security property: parity with /v1/files/{id}/content ───────────

    /**
     * Burst serves UNMASKED original bytes and cannot mask — masking is defined per named
     * sensitive field, and ink on a page has no name. Its only protection is therefore WHO may
     * call it, and the rule is PARITY with {@code /v1/files/{id}/content}: the same roles, no
     * looser and no stricter. Looser and a role that may only read masked fields could recover an
     * unmasked SSN by downloading the PDF, which would make the masking architecture decorative.
     *
     * <p>Asserted against a NONEXISTENT id on purpose, the way {@code RawContentAdminBoundaryIT}
     * does: 403 and 404 are different answers, so an authorized role reaching the controller's
     * opaque 404 proves the role gate let it through rather than that the route is missing. Both
     * paths are driven with the same id in the same loop, so the two endpoints cannot drift onto
     * different rules without this failing.
     */
    @Test
    void burst_is_reachable_by_exactly_the_roles_that_may_download_the_original_file()
            throws Exception {
        UUID absent = UUID.randomUUID();

        for (String role : List.of("READONLY", "PROCESSOR", "REVIEWER", "ADMIN")) {
            int fileStatus =
                    mockMvc.perform(get("/v1/files/{id}/content", absent).header("X-Dev-Role", role))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            int burstStatus =
                    mockMvc.perform(get("/v1/documents/{id}/pdf", absent).header("X-Dev-Role", role))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            int signedUrlStatus =
                    mockMvc.perform(
                                    get("/v1/documents/{id}/pdf/signed-url", absent)
                                            .header("X-Dev-Role", role))
                            .andReturn()
                            .getResponse()
                            .getStatus();

            assertThat(burstStatus)
                    .as("burst and file-content agree on role %s", role)
                    .isEqualTo(fileStatus);
            assertThat(signedUrlStatus)
                    .as("burst signed-url and file-content agree on role %s", role)
                    .isEqualTo(fileStatus);
            // And the shared answer is authorization-passed-then-absent, not a role rejection —
            // otherwise the equality above would hold vacuously at 403 for every role.
            assertThat(fileStatus).as("role %s is authorized for raw bytes", role).isEqualTo(404);
        }
    }

    // ── worker failure mapping ──────────────────────────────────────────────

    @Test
    void a_worker_failure_is_a_502_with_the_stable_code_never_a_404() throws Exception {
        UUID packageId = insertPackage("burst-worker-down");
        UUID sourceId = insertSource(packageId, ORG_DEV, 0, "it-burst-wf/" + packageId, BURST_PDF);
        UUID pageId = insertPage(sourceId, packageId, ORG_DEV, 0, 0);
        UUID documentId = insertDocument(packageId, ORG_DEV, List.of(pageId));

        WORKER.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\": \"BURST_FAILED\", \"detail\": {}}"));

        mockMvc.perform(get("/v1/documents/{id}/pdf", documentId))
                .andExpect(status().isBadGateway());
        takeBurstCall();
    }
}
