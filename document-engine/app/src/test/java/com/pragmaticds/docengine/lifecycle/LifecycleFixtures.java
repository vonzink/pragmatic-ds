package com.pragmaticds.docengine.lifecycle;

import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds one document package with at least one row in EVERY package-owned table, plus its real
 * blobs, straight through JDBC (RLS is bypassed by the Testcontainers superuser, so an explicit
 * {@code org_id} on every insert is both necessary and sufficient). Two ITs share it: the
 * soft-delete read-exclusion IT (which only needs the file/page/blobs) and the retention purge IT
 * (which asserts every one of these rows and blobs is gone afterwards, and that a sibling package
 * seeded the same way is untouched).
 *
 * <p>Deliberately NOT the production pipeline: the purge must be proven against a package that has
 * touched every table, and driving the whole worker pipeline in a unit-speed test is neither
 * necessary nor deterministic. The rows are minimal but constraint-valid.
 */
public final class LifecycleFixtures {

    private static final String SHA64 =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String ENGINE_RESULT_MEDIA_TYPE =
            "application/vnd.pragmaticds.document-engine-result+json;version=1";

    private LifecycleFixtures() {}

    /** Every id and blob key the ITs assert on. */
    public record Seed(
            UUID packageId,
            UUID orgId,
            UUID sourceFileId,
            UUID pageId,
            UUID logicalDocumentId,
            UUID extractedFieldId,
            long textSpanId,
            UUID layoutElementId,
            UUID parserOutputId,
            UUID jobId,
            UUID stageId,
            UUID engineResultId,
            String originalBlobKey,
            String renderBlobKey,
            String parserPayloadKey,
            String engineResultBlobKey,
            String engineResultDigest) {}

    /** One additional immutable revision appended to an existing standard seed. */
    public record ResultRevision(UUID id, int revision, String blobKey, String digest) {}

    /** Seeds a full package for {@code orgId} and returns its ids + blob keys. */
    public static Seed seedFullPackage(
            JdbcTemplate jdbc, BlobStoragePort storage, UUID orgId, String name) {

        UUID packageId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        UUID logicalDocumentId = UUID.randomUUID();
        UUID extractedFieldId = UUID.randomUUID();
        UUID layoutElementId = UUID.randomUUID();
        UUID parserOutputId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        UUID engineResultId = UUID.randomUUID();
        UUID fieldEvidenceId = UUID.randomUUID();
        UUID classPageResultId = UUID.randomUUID();
        UUID classDocResultId = UUID.randomUUID();
        long textSpanId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

        String originalBlobKey = orgId + "/" + packageId + "/" + sourceFileId + "/original";
        String renderBlobKey = orgId + "/" + packageId + "/pages/" + pageId + ".png";
        String parserPayloadKey = orgId + "/parser-output/" + parserOutputId + ".json";
        byte[] engineResultBytes = engineResultBytes(packageId, 1);
        String engineResultDigest = sha256(engineResultBytes);
        String engineResultBlobKey = engineResultKey(orgId, engineResultDigest);

        UUID schemaId =
                jdbc.queryForObject(
                        "SELECT id FROM extraction_schema WHERE document_type_code = 'PAYSTUB'"
                                + " AND org_id IS NULL LIMIT 1",
                        UUID.class);

        jdbc.update(
                "INSERT INTO document_package (id, org_id, name, page_count) VALUES (?, ?, ?, 1)",
                packageId, orgId, name);

        jdbc.update(
                "INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,"
                        + " content_type, size_bytes, sha256, storage_key_original)"
                        + " VALUES (?, ?, ?, 0, 'doc.pdf', 'application/pdf', 123, ?, ?)",
                sourceFileId, orgId, packageId, SHA64, originalBlobKey);

        jdbc.update(
                "INSERT INTO page (id, org_id, source_file_id, package_id, page_index,"
                        + " package_page_index, width_pt, height_pt, render_storage_key, render_dpi)"
                        + " VALUES (?, ?, ?, ?, 0, 0, 612.00, 792.00, ?, 150)",
                pageId, orgId, sourceFileId, packageId, renderBlobKey);

        jdbc.update(
                "INSERT INTO text_span (id, org_id, page_id, ordinal, text, x, y, width, height,"
                        + " source, confidence)"
                        + " VALUES (?, ?, ?, 0, 'Net Pay', 10, 20, 50, 12, 'NATIVE', 1.0)",
                textSpanId, orgId, pageId);

        jdbc.update(
                "INSERT INTO layout_element (id, org_id, page_id, element_type, ordinal, x, y,"
                        + " width, height, confidence, detector, detector_version)"
                        + " VALUES (?, ?, ?, 'PARAGRAPH', 0, 10, 20, 100, 30, 1.0, 'test', 'v1')",
                layoutElementId, orgId, pageId);

        jdbc.update(
                "INSERT INTO layout_element_span (layout_element_id, text_span_id, org_id, ordinal)"
                        + " VALUES (?, ?, ?, 0)",
                layoutElementId, textSpanId, orgId);

        jdbc.update(
                "INSERT INTO parser_output (id, org_id, source_file_id, page_id, stage,"
                        + " parser_name, parser_version, payload_storage_key, payload_sha256)"
                        + " VALUES (?, ?, ?, ?, 'RENDERING', 'stub', 'v1', ?, ?)",
                parserOutputId, orgId, sourceFileId, pageId, parserPayloadKey, SHA64);

        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal, document_type_code)"
                        + " VALUES (?, ?, ?, 0, 'PAYSTUB')",
                logicalDocumentId, orgId, packageId);

        jdbc.update(
                "INSERT INTO logical_document_page (org_id, logical_document_id, page_id, ordinal)"
                        + " VALUES (?, ?, ?, 0)",
                orgId, logicalDocumentId, pageId);

        // Both polymorphic subject kinds, so the by-subject delete must clear PAGE and
        // LOGICAL_DOCUMENT rows alike.
        jdbc.update(
                "INSERT INTO classification_result (id, org_id, subject_type, subject_id,"
                        + " document_type_code, confidence, method, evidence)"
                        + " VALUES (?, ?, 'PAGE', ?, 'PAYSTUB', 0.90, 'RULE_ANCHOR', '{}'::jsonb)",
                classPageResultId, orgId, pageId);
        jdbc.update(
                "INSERT INTO classification_result (id, org_id, subject_type, subject_id,"
                        + " document_type_code, confidence, method, evidence)"
                        + " VALUES (?, ?, 'LOGICAL_DOCUMENT', ?, 'PAYSTUB', 0.90, 'RULE_ANCHOR',"
                        + " '{}'::jsonb)",
                classDocResultId, orgId, logicalDocumentId);

        jdbc.update(
                "INSERT INTO extracted_field (id, org_id, logical_document_id, schema_id,"
                        + " field_name, data_type, extraction_method, extractor_version, confidence)"
                        + " VALUES (?, ?, ?, ?, 'netPay', 'MONEY', 'ANCHOR_LABEL', 'v1', 0.90)",
                extractedFieldId, orgId, logicalDocumentId, schemaId);

        jdbc.update(
                "INSERT INTO field_evidence (id, org_id, extracted_field_id, page_id, x, y, width,"
                        + " height, role, ordinal)"
                        + " VALUES (?, ?, ?, ?, 10, 20, 50, 12, 'VALUE', 0)",
                fieldEvidenceId, orgId, extractedFieldId, pageId);

        jdbc.update(
                "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status,"
                        + " current_stage) VALUES (?, ?, ?, ?, 'HUMAN_REVIEW_REQUIRED',"
                        + " 'FINALIZING')",
                jobId, orgId, packageId, "idem-" + jobId);

        jdbc.update(
                "INSERT INTO processing_stage (id, org_id, job_id, stage, status, output_digest)"
                        + " VALUES (?, ?, ?, 'FINALIZING', 'SUCCEEDED', ?)",
                stageId, orgId, jobId, engineResultDigest);

        jdbc.update(
                """
                INSERT INTO engine_result
                    (id, org_id, package_id, processing_job_id, parse_generation,
                     materialized_job_attempt, revision, supersedes_result_id,
                     envelope_schema_version, canonicalization_version, canonical_media_type,
                     source_set_sha256, provenance_sha256, envelope_storage_key, envelope_sha256,
                     envelope_size_bytes, reuse_eligibility)
                VALUES (?, ?, ?, ?, 1, 1, 1, NULL, '1.0.0', 'DOCENGINE-C14N-1', ?, ?, ?, ?, ?, ?,
                        'PARSE_ONCE_CURRENT_PACKAGE')
                """,
                engineResultId,
                orgId,
                packageId,
                jobId,
                ENGINE_RESULT_MEDIA_TYPE,
                SHA64,
                SHA64,
                engineResultBlobKey,
                engineResultDigest,
                engineResultBytes.length);

        storage.put(originalBlobKey, name.getBytes(StandardCharsets.UTF_8));
        storage.put(renderBlobKey, pngBytes());
        storage.put(parserPayloadKey, "{}".getBytes(StandardCharsets.UTF_8));
        storage.putImmutable(engineResultBlobKey, engineResultBytes, engineResultDigest);

        return new Seed(
                packageId,
                orgId,
                sourceFileId,
                pageId,
                logicalDocumentId,
                extractedFieldId,
                textSpanId,
                layoutElementId,
                parserOutputId,
                jobId,
                stageId,
                engineResultId,
                originalBlobKey,
                renderBlobKey,
                parserPayloadKey,
                engineResultBlobKey,
                engineResultDigest);
    }

    /**
     * Appends revision 2 and advances the fixture's one current FINALIZING stage to generation 2.
     * Revision 1 remains historical, matching regroup/re-extraction lifecycle semantics.
     */
    public static ResultRevision appendSecondEngineResult(
            JdbcTemplate jdbc, BlobStoragePort storage, Seed seed) {
        UUID resultId = UUID.randomUUID();
        byte[] bytes = engineResultBytes(seed.packageId(), 2);
        String digest = sha256(bytes);
        String key = engineResultKey(seed.orgId(), digest);

        storage.putImmutable(key, bytes, digest);
        jdbc.update(
                "UPDATE processing_job SET parse_generation = 2, attempt = 2 WHERE id = ? AND"
                        + " org_id = ?",
                seed.jobId(),
                seed.orgId());
        jdbc.update(
                "UPDATE processing_stage SET attempt = 2, output_digest = ? WHERE id = ? AND"
                        + " org_id = ?",
                digest,
                seed.stageId(),
                seed.orgId());
        jdbc.update(
                """
                INSERT INTO engine_result
                    (id, org_id, package_id, processing_job_id, parse_generation,
                     materialized_job_attempt, revision, supersedes_result_id,
                     envelope_schema_version, canonicalization_version, canonical_media_type,
                     source_set_sha256, provenance_sha256, envelope_storage_key, envelope_sha256,
                     envelope_size_bytes, reuse_eligibility)
                VALUES (?, ?, ?, ?, 2, 2, 2, ?, '1.0.0', 'DOCENGINE-C14N-1', ?, ?, ?, ?, ?, ?,
                        'PARSE_ONCE_CURRENT_PACKAGE')
                """,
                resultId,
                seed.orgId(),
                seed.packageId(),
                seed.jobId(),
                seed.engineResultId(),
                ENGINE_RESULT_MEDIA_TYPE,
                SHA64,
                SHA64,
                key,
                digest,
                bytes.length);
        return new ResultRevision(resultId, 2, key, digest);
    }

    /** Total DB rows this seed created that a purge must remove (package + 15 children). */
    public static long rowCount(JdbcTemplate jdbc, Seed seed) {
        return countByPackage(jdbc, seed);
    }

    /** Sum of package-owned rows still present for the seed across every purged table. */
    public static long countByPackage(JdbcTemplate jdbc, Seed seed) {
        UUID p = seed.packageId();
        UUID org = seed.orgId();
        long total = 0;
        total += one(jdbc, "SELECT count(*) FROM document_package WHERE id = ? AND org_id = ?", p, org);
        total += one(jdbc, "SELECT count(*) FROM source_file WHERE package_id = ? AND org_id = ?", p, org);
        total += one(jdbc, "SELECT count(*) FROM page WHERE package_id = ? AND org_id = ?", p, org);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM text_span WHERE org_id = ? AND page_id IN"
                                + " (SELECT id FROM page WHERE package_id = ?)",
                        org,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM layout_element WHERE org_id = ? AND page_id IN"
                                + " (SELECT id FROM page WHERE package_id = ?)",
                        org,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM layout_element_span WHERE org_id = ? AND"
                                + " layout_element_id IN (SELECT le.id FROM layout_element le JOIN"
                                + " page pg ON pg.id = le.page_id WHERE pg.package_id = ?)",
                        org,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM parser_output WHERE org_id = ? AND (source_file_id IN"
                                + " (SELECT id FROM source_file WHERE package_id = ?) OR page_id IN"
                                + " (SELECT id FROM page WHERE package_id = ?))",
                        org,
                        p,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM logical_document WHERE package_id = ? AND org_id = ?",
                        p,
                        org);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM logical_document_page WHERE org_id = ? AND"
                                + " logical_document_id IN (SELECT id FROM logical_document WHERE"
                                + " package_id = ?)",
                        org,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM classification_result WHERE org_id = ? AND"
                                + " ((subject_type = 'PAGE' AND subject_id IN (SELECT id FROM page"
                                + " WHERE package_id = ?)) OR (subject_type = 'LOGICAL_DOCUMENT' AND"
                                + " subject_id IN (SELECT id FROM logical_document WHERE package_id ="
                                + " ?)))",
                        org,
                        p,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM extracted_field WHERE org_id = ? AND"
                                + " logical_document_id IN (SELECT id FROM logical_document WHERE"
                                + " package_id = ?)",
                        org,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM field_evidence WHERE org_id = ? AND extracted_field_id"
                                + " IN (SELECT ef.id FROM extracted_field ef JOIN logical_document"
                                + " ld ON ld.id = ef.logical_document_id WHERE ld.package_id = ?)",
                        org,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM processing_job WHERE package_id = ? AND org_id = ?",
                        p,
                        org);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM processing_stage WHERE org_id = ? AND job_id IN"
                                + " (SELECT id FROM processing_job WHERE package_id = ?)",
                        org,
                        p);
        total +=
                one(
                        jdbc,
                        "SELECT count(*) FROM engine_result WHERE package_id = ? AND org_id = ?",
                        p,
                        org);
        return total;
    }

    private static long one(JdbcTemplate jdbc, String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static byte[] pngBytes() {
        byte[] content = new byte[64];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, content, 0, magic.length);
        return content;
    }

    private static byte[] engineResultBytes(UUID packageId, int revision) {
        return ("{\"envelopeVersion\":\"1.0.0\",\"package\":{\"id\":\""
                        + packageId
                        + "\"},\"revision\":"
                        + revision
                        + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String engineResultKey(UUID orgId, String digest) {
        return "org/" + orgId + "/engine-results/sha256/" + digest + ".json";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
