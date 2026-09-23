package com.pragmaticds.docengine.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * POST /v1/packages end to end: validation, persistence, blob storage, the orchestration seam,
 * and — critically — that every rejection carries only stable codes and non-sensitive params,
 * never the filename or a byte of document content.
 */
class PackageUploadIT extends AbstractIngestionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void a_native_pdf_upload_is_accepted_with_full_file_metadata() throws Exception {
        byte[] pdf = pdfWithPages(2);
        String expectedSha = sha256Hex(pdf);

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("loan-package.pdf", "application/pdf", pdf))
                                        .param("name", "March paystubs")
                                        .header("Idempotency-Key", "client-key-123"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.packageId").isNotEmpty())
                        .andExpect(jsonPath("$.jobId").isNotEmpty())
                        .andExpect(jsonPath("$.files.length()").value(1))
                        .andExpect(jsonPath("$.files[0].id").isNotEmpty())
                        .andExpect(jsonPath("$.files[0].originalFilename").value("loan-package.pdf"))
                        .andExpect(jsonPath("$.files[0].contentType").value("application/pdf"))
                        .andExpect(jsonPath("$.files[0].sizeBytes").value(pdf.length))
                        .andExpect(jsonPath("$.files[0].sha256").value(expectedSha))
                        .andExpect(jsonPath("$.files[0].pageCount").value(2))
                        .andExpect(jsonPath("$.warnings.length()").value(0))
                        .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        UUID packageId = UUID.fromString(body.get("packageId").asText());
        UUID fileId = UUID.fromString(body.get("files").get(0).get("id").asText());

        // The seam was crossed with the client's idempotency key, and its job id came back.
        RecordingProcessingStarter.Call call = startedJobs.onlyCall();
        assertThat(call.packageId()).isEqualTo(packageId);
        assertThat(call.idempotencyKey()).isEqualTo("client-key-123");
        assertThat(body.get("jobId").asText()).isEqualTo(call.jobId().toString());

        // Package row: page_count is the sum of known page counts.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT page_count FROM document_package WHERE id = ?",
                                Integer.class,
                                packageId))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT name FROM document_package WHERE id = ?", String.class, packageId))
                .isEqualTo("March paystubs");

        // Source file row: sniffed type, claim kept for audit, ordinal 0, and the
        // persisted storage key matches the documented {org}/{package}/{file}/original shape.
        String key = ORG_DEV + "/" + packageId + "/" + fileId + "/original";
        assertThat(
                        jdbc.queryForMap(
                                "SELECT ordinal, content_type, declared_content_type, sha256,"
                                        + " malware_scan_status, storage_key_original"
                                        + " FROM source_file WHERE id = ?",
                                fileId))
                .containsEntry("ordinal", 0)
                .containsEntry("content_type", "application/pdf")
                .containsEntry("declared_content_type", "application/pdf")
                .containsEntry("sha256", expectedSha)
                .containsEntry("malware_scan_status", "SKIPPED")
                .containsEntry("storage_key_original", key);

        // The original bytes are in blob storage under that key.
        assertThat(blobStorage.exists(key)).isTrue();
        assertThat(blobStorage.get(key)).isEqualTo(pdf);
    }

    @Test
    void without_a_client_key_the_package_id_is_the_idempotency_key() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("a.pdf", "application/pdf", pdfWithPages(1))))
                        .andExpect(status().isAccepted())
                        .andReturn();

        String packageId =
                JSON.readTree(result.getResponse().getContentAsString()).get("packageId").asText();
        assertThat(startedJobs.onlyCall().idempotencyKey()).isEqualTo(packageId);
    }

    @Test
    void png_bytes_declared_as_pdf_are_accepted_as_the_sniffed_png() throws Exception {
        byte[] png = pngBytes(64);

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("scan.pdf", "application/pdf", png)))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.files[0].contentType").value("image/png"))
                        .andExpect(jsonPath("$.files[0].pageCount").value(1))
                        .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        UUID fileId = UUID.fromString(body.get("files").get(0).get("id").asText());
        UUID packageId = UUID.fromString(body.get("packageId").asText());

        assertThat(
                        jdbc.queryForMap(
                                "SELECT content_type, declared_content_type, page_count FROM source_file WHERE id = ?",
                                fileId))
                .containsEntry("content_type", "image/png")
                .containsEntry("declared_content_type", "application/pdf")
                .containsEntry("page_count", 1);
        // An image IS a page. This column read NULL for every image before ImageProbe existed,
        // and a package of four photographed paystubs therefore reported a page count of zero.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT page_count FROM document_package WHERE id = ?",
                                Integer.class,
                                packageId))
                .isEqualTo(1);
    }

    @Test
    void a_jpeg_photo_of_a_paystub_is_accepted_as_a_one_page_document() throws Exception {
        // The owner's case: a phone photo, declared image/jpeg, nothing about it PDF-shaped.
        byte[] jpeg = jpegBytes();

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("IMG_4417.jpg", "image/jpeg", jpeg)))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.files[0].contentType").value("image/jpeg"))
                        .andExpect(jsonPath("$.files[0].pageCount").value(1))
                        .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        UUID fileId = UUID.fromString(body.get("files").get(0).get("id").asText());

        // The stored bytes are the UPLOAD, byte for byte — no conversion to PDF at ingest, so the
        // sha256 that duplicate detection joins on is the identity of what the borrower sent and
        // SignedDownloadController serves back exactly that (see worker source.py for the full
        // reasoning behind carrying images rather than converting them).
        assertThat(blobStorage.get(ORG_DEV + "/" + body.get("packageId").asText() + "/" + fileId
                        + "/original"))
                .isEqualTo(jpeg);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sha256 FROM source_file WHERE id = ?", String.class, fileId))
                .isEqualTo(sha256Hex(jpeg));
    }

    @Test
    void a_heic_photo_is_accepted_as_a_one_page_document() throws Exception {
        // An iPhone writes HEIC unless the owner has changed a camera setting, so this is the
        // same borrower as the JPEG case above with nothing changed but the phone's default.
        // It was rejected UNSUPPORTED_MIME at the door until the sniffer learned the ftyp box.
        byte[] heic = heicBytes();

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("IMG_5162.HEIC", "image/heic", heic)))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.files[0].contentType").value("image/heic"))
                        .andExpect(jsonPath("$.files[0].pageCount").value(1))
                        .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        UUID fileId = UUID.fromString(body.get("files").get(0).get("id").asText());

        // Stored pristine, like every other image: the sha256 duplicate detection joins on is the
        // identity of what the borrower sent, and SignedDownloadController serves those bytes back.
        assertThat(
                        jdbc.queryForMap(
                                "SELECT content_type, page_count FROM source_file WHERE id = ?", fileId))
                .containsEntry("content_type", "image/heic")
                .containsEntry("page_count", 1);
        assertThat(blobStorage.get(ORG_DEV + "/" + body.get("packageId").asText() + "/" + fileId
                        + "/original"))
                .isEqualTo(heic);
    }

    @Test
    void an_undecodable_image_is_rejected_as_corrupt_image_and_leaks_nothing() throws Exception {
        // JPEG magic with no image behind it. Before ImageProbe this was ACCEPTED, and the
        // failure surfaced three RENDERING attempts later as CORRUPT_PDF — a code naming a format
        // the file never claimed to be.
        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(
                                                filePart(
                                                        "borrower-paystub.jpg",
                                                        "image/jpeg",
                                                        undecodableJpegBytes())))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("CORRUPT_IMAGE"))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("borrower-paystub");
        assertThat(startedJobs.calls()).isEmpty();
    }

    @Test
    void a_multi_page_tiff_scan_counts_every_frame() throws Exception {
        mockMvc.perform(multipart("/v1/packages").file(filePart("fax.tif", "image/tiff", tiffBytes(3))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.files[0].contentType").value("image/tiff"))
                .andExpect(jsonPath("$.files[0].pageCount").value(3));
    }

    @Test
    void text_bytes_declared_as_pdf_are_unsupported_and_leak_nothing() throws Exception {
        byte[] text = textBytes("Account 9988776655, SSN 123-45-6789");

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("borrower-secret.pdf", "application/pdf", text)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MIME"))
                        .andExpect(jsonPath("$.params.sniffed").value("unknown"))
                        .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("borrower-secret");
        assertThat(body).doesNotContain("123-45-6789");
        assertThat(body).doesNotContain("9988776655");
        assertThat(startedJobs.calls()).isEmpty();
    }

    @Test
    void an_encrypted_pdf_is_rejected_as_password_protected() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(
                                                filePart(
                                                        "protected-bank-statement.pdf",
                                                        "application/pdf",
                                                        encryptedPdf())))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("PASSWORD_PROTECTED"))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("protected-bank-statement");
    }

    @Test
    void an_owner_password_only_pdf_is_accepted_and_flagged_encrypted() throws Exception {
        // The defect this pins: a real lender PDF that opens with NO password prompt was rejected
        // 400 PASSWORD_PROTECTED. It is owner-password-only — encryption that restricts printing
        // and editing while leaving the content freely readable — so PDFBox opens it with the
        // empty user password and every downstream stage reads plaintext. The sibling test
        // an_encrypted_pdf_is_rejected_as_password_protected keeps the true-password boundary.
        byte[] ownerLocked = ownerPasswordOnlyPdf(3);

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(
                                                filePart(
                                                        "closing-disclosure.pdf",
                                                        "application/pdf",
                                                        ownerLocked)))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.files[0].pageCount").value(3))
                        .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        UUID fileId = UUID.fromString(body.get("files").get(0).get("id").asText());

        // Accepted, but the provenance is not lost: a reviewer can see it arrived encrypted.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_encrypted FROM source_file WHERE id = ?", Boolean.class, fileId))
                .isTrue();
        // And the pipeline was actually handed the file — acceptance without a job is no fix.
        assertThat(startedJobs.onlyCall().packageId())
                .isEqualTo(UUID.fromString(body.get("packageId").asText()));
    }

    @Test
    void an_ordinary_pdf_is_not_flagged_encrypted() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("plain.pdf", "application/pdf", pdfWithPages(1))))
                        .andExpect(status().isAccepted())
                        .andReturn();

        UUID fileId =
                UUID.fromString(
                        JSON.readTree(result.getResponse().getContentAsString())
                                .get("files")
                                .get(0)
                                .get("id")
                                .asText());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_encrypted FROM source_file WHERE id = ?", Boolean.class, fileId))
                .isFalse();
    }

    @Test
    void a_corrupt_pdf_is_rejected_without_echoing_its_bytes() throws Exception {
        byte[] corrupt = textBytes("%PDF-1.7 not really a pdf SSN 123-45-6789");

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/packages")
                                        .file(filePart("damaged-w2.pdf", "application/pdf", corrupt)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("CORRUPT_PDF"))
                        .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("damaged-w2");
        assertThat(body).doesNotContain("123-45-6789");
    }

    @Test
    void the_same_bytes_twice_in_one_upload_conflict_and_persist_nothing() throws Exception {
        byte[] pdf = pdfWithPages(1);
        String shaPrefix = sha256Hex(pdf).substring(0, 12);
        long packagesBefore = countPackages();
        long filesBefore = countFiles();

        mockMvc.perform(
                        multipart("/v1/packages")
                                .file(filePart("first.pdf", "application/pdf", pdf))
                                .file(filePart("second-copy.pdf", "application/pdf", pdf)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FILE"))
                .andExpect(jsonPath("$.params.shaPrefix").value(shaPrefix));

        assertThat(countPackages()).isEqualTo(packagesBefore);
        assertThat(countFiles()).isEqualTo(filesBefore);
        assertThat(startedJobs.calls()).isEmpty();
    }

    @Test
    void the_same_bytes_in_another_package_warn_but_do_not_reject() throws Exception {
        // Distinct content so parallel-run leftovers of other tests cannot collide.
        byte[] pdf = pdfWithPages(4);
        mockMvc.perform(
                        multipart("/v1/packages")
                                .file(filePart("paystub.pdf", "application/pdf", pdf)))
                .andExpect(status().isAccepted());

        // The warning names the hash prefix ONLY — deliberately not the other package's
        // source-file id (review finding: an upload response is not a catalogue of where
        // else a document lives).
        mockMvc.perform(
                        multipart("/v1/packages")
                                .file(filePart("same-paystub-again.pdf", "application/pdf", pdf)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].existingSourceFileId").doesNotExist())
                .andExpect(jsonPath("$.warnings[0].shaPrefix").value(sha256Hex(pdf).substring(0, 12)));
    }

    private long countPackages() {
        return jdbc.queryForObject("SELECT count(*) FROM document_package", Long.class);
    }

    private long countFiles() {
        return jdbc.queryForObject("SELECT count(*) FROM source_file", Long.class);
    }
}
