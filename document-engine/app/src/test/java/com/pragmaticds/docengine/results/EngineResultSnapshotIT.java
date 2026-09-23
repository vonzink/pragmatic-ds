package com.pragmaticds.docengine.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.canonical.EngineResultEnvelopeAssembler;
import com.pragmaticds.docengine.results.canonical.EnvelopeAssemblyRequest;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshotLoader;
import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic database contract for the one-transaction machine snapshot projection. */
class EngineResultSnapshotIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Autowired private MachineResultSnapshotLoader loader;
    @Autowired private EngineResultEnvelopeAssembler assembler;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void loadsExactInactiveSchemaMachineRowsAndIgnoresCorrectionsMutableReviewAndFailedStages()
            throws Exception {
        Fixture fixture = seedCompleteFixture();
        EnvelopeAssemblyRequest request = request(fixture);

        byte[] before = assembler.assemble(request, loader.load(request)).bytes();
        JsonNode root = JSON.readTree(before);

        assertThat(root.path("sources")).hasSize(2);
        assertThat(root.path("pages")).hasSize(2);
        assertThat(root.at("/pages/0/classification/evidence/anchors/0/spanIds/0").asLong())
                .isEqualTo(101L);
        assertThat(root.path("documents")).hasSize(1);
        assertThat(root.at("/documents/0/pageIds/0").asText())
                .isEqualTo(fixture.page0().toString());
        assertThat(root.at("/unassignedPageIds/0").asText())
                .isEqualTo(fixture.page1().toString());
        assertThat(root.at("/documents/0/fields/0/groupKey").asText()).isEqualTo("A");
        assertThat(root.at("/documents/0/fields/0/status").asText()).isEqualTo("FOUND");
        assertThat(root.at("/documents/0/fields/0/schema/version").asText())
                .isEqualTo(fixture.schemaVersion());
        assertThat(root.at("/documents/0/fields/1/groupKey").asText()).isEqualTo("B");
        assertThat(root.at("/documents/0/fields/1/normalized/number").decimalValue())
                .isEqualByComparingTo("-25");
        assertThat(root.at("/documents/0/fields/2/groupKey").asText()).isEqualTo("C");
        assertThat(root.at("/documents/0/fields/2/status").asText()).isEqualTo("MISSING");
        assertThat(root.at("/documents/0/fields/0/evidence").findValuesAsText("role"))
                .containsExactly("VALUE", "LABEL", "CONTEXT");
        assertThat(root.path("provenance").path("stages").findValuesAsText("stage"))
                .containsExactly("RENDERING", "PARSING", "EXTRACTING");
        assertThat(new String(before, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(
                        "fixture-private-name.pdf",
                        "fixture-private-storage-key",
                        "human-corrected-value",
                        "reviewer-reason",
                        "FAILED");

        jdbc.update(
                "UPDATE extracted_field SET review_status = 'CORRECTED' WHERE id = ?",
                fixture.fieldA());
        jdbc.update(
                "UPDATE logical_document SET review_status = 'REVIEWED', reviewed_by = ?,"
                        + " reviewed_at = now() WHERE id = ?",
                UUID.randomUUID(),
                fixture.documentId());
        jdbc.update(
                """
                INSERT INTO review_decision
                    (id, org_id, subject_type, subject_id, action, previous_value, new_value,
                     reason, decided_by)
                VALUES (?, ?, 'EXTRACTED_FIELD', ?, 'CORRECT', ?::jsonb, ?::jsonb, ?, ?)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                fixture.fieldA(),
                "{\"value\":\"machine-value\"}",
                "{\"value\":\"human-corrected-value\"}",
                "reviewer-reason",
                UUID.randomUUID());

        byte[] after = assembler.assemble(request, loader.load(request)).bytes();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void failsClosedForForeignExactSchemaBrokenPageSourceAndWrongGenerationIdentity() {
        Fixture fixture = seedCompleteFixture();
        EnvelopeAssemblyRequest request = request(fixture);
        UUID foreignSchema = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO extraction_schema
                    (id, org_id, document_type_code, version, definition, is_active)
                VALUES (?, ?, ?, 'foreign-1', '{}'::jsonb, false)
                """,
                foreignSchema,
                ORG_OTHER,
                fixture.documentTypeCode());
        jdbc.update(
                "UPDATE extracted_field SET schema_id = ? WHERE id = ?",
                foreignSchema,
                fixture.fieldA());

        assertThatThrownBy(() -> loader.load(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");

        jdbc.update(
                "UPDATE extracted_field SET schema_id = ? WHERE id = ?",
                fixture.schemaId(),
                fixture.fieldA());
        UUID otherPackage = UUID.randomUUID();
        UUID otherSource = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'other')",
                otherPackage,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO source_file
                    (id, org_id, package_id, ordinal, original_filename, content_type,
                     size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'other.pdf', 'application/pdf', 5, ?, 'other-key')
                """,
                otherSource,
                ORG_DEV,
                otherPackage,
                "c".repeat(64));
        jdbc.update("UPDATE page SET source_file_id = ? WHERE id = ?", otherSource, fixture.page0());

        assertThatThrownBy(() -> loader.load(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");

        assertThatThrownBy(
                        () ->
                                loader.load(
                                        new EnvelopeAssemblyRequest(
                                                fixture.packageId(),
                                                fixture.jobId(),
                                                99,
                                                1,
                                                "1.0.0",
                                                "DOCENGINE-C14N-1",
                                                ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    @Test
    void rejectsStoredJsonThatIsNotAnObjectAtTheStrictParseBoundary() {
        Fixture fixture = seedCompleteFixture();
        jdbc.update(
                "UPDATE extracted_field SET confidence_components = '[]'::jsonb WHERE id = ?",
                fixture.fieldA());

        assertThatThrownBy(() -> loader.load(request(fixture)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    @Test
    void rejectsClassificationEvidenceOutsideTheExactTextFreeSchema() {
        Fixture fixture = seedCompleteFixture();
        List<String> malformed =
                List.of(
                        "{\"anchors\":[],\"scores\":[],\"rawDocumentText\":\"borrower text\"}",
                        "{\"anchors\":[]}",
                        "{\"anchors\":\"not-an-array\",\"scores\":[]}",
                        classificationEvidenceJson(
                                        fixture.documentTypeCode(), "pack-inactive", 101L)
                                .replace(
                                        "\"weight\":3",
                                        "\"weight\":3,\"rawDocumentText\":\"borrower text\""),
                        classificationEvidenceJson(
                                        fixture.documentTypeCode(), "pack-inactive", 101L)
                                .replace("\"weight\":3", "\"weight\":\"3\""),
                        """
                        {"anchors":[{"packType":"raw borrower text","packVersion":"1.0.0",
                        "anchorId":"pay-period","weight":3,"spanIds":[101],"boxes":[],
                        "range":{"start":0,"end":3}}],"scores":[]}
                        """,
                        // An unknown top-level member is still refused: only Phase B4's
                        // coQualifyingTypes is admitted, and only in its exact shape.
                        "{\"anchors\":[],\"scores\":[],\"rawDocumentText\":\"borrower text\"}",
                        "{\"anchors\":[],\"scores\":[],\"coQualifyingTypes\":\"W2\"}",
                        "{\"anchors\":[],\"scores\":[],\"coQualifyingTypes\":[]}",
                        "{\"anchors\":[],\"scores\":[],\"coQualifyingTypes\":[\"w2 text\"]}",
                        "{\"anchors\":[],\"scores\":[],\"coQualifyingTypes\":[\"W2\",\"W2\"]}",
                        "{\"anchors\":[],\"scores\":[],\"coQualifyingTypes\":[{\"type\":\"W2\"}]}");

        for (String evidence : malformed) {
            jdbc.update(
                    "UPDATE classification_result SET evidence = ?::jsonb WHERE subject_id = ?",
                    evidence,
                    fixture.page0());

            assertThatThrownBy(() -> loader.load(request(fixture)))
                    .as("malformed classification evidence: %s", evidence)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Machine snapshot is inconsistent");
        }
    }

    /**
     * Phase B4 writes {@code coQualifyingTypes} when two packs both clear their thresholds on one
     * page — routine on a tax return with schedules, never on a lone bank statement. The loader
     * must admit it as an optional member and the envelope must carry it, omitting the member
     * entirely on pages that did not co-qualify so existing envelopes stay byte-identical.
     */
    @Test
    void admitsAndProjectsCoQualifyingTypesWrittenByThePhaseB4Classifier() throws Exception {
        Fixture fixture = seedCompleteFixture();
        jdbc.update(
                """
                UPDATE classification_result
                   SET evidence = jsonb_set(evidence, '{coQualifyingTypes}',
                                            '["SCHEDULE_C","TAX_RETURN"]'::jsonb)
                 WHERE subject_id = ?
                """,
                fixture.page0());
        EnvelopeAssemblyRequest request = request(fixture);

        JsonNode root = JSON.readTree(assembler.assemble(request, loader.load(request)).bytes());

        JsonNode coQualifying = root.at("/pages/0/classification/evidence/coQualifyingTypes");
        assertThat(coQualifying.isArray()).isTrue();
        assertThat(List.of(coQualifying.get(0).asText(), coQualifying.get(1).asText()))
                .containsExactly("SCHEDULE_C", "TAX_RETURN");
        assertThat(root.at("/pages/1/classification/evidence").has("coQualifyingTypes"))
                .isFalse();
    }

    @Test
    void preservesHighPrecisionNumbersFromEveryStoredJsonProjection() {
        Fixture fixture = seedCompleteFixture();
        String precise = "0.12345678901234567890123456789";
        jdbc.update(
                "UPDATE classification_result SET evidence ="
                        + " jsonb_set(evidence, '{anchors,0,weight}', to_jsonb(?::numeric))"
                        + " WHERE subject_id = ?",
                precise,
                fixture.page0());
        jdbc.update(
                "UPDATE extracted_field SET confidence_components ="
                        + " jsonb_build_object('precision', ?::numeric), normalized_json ="
                        + " jsonb_build_object('precision', ?::numeric) WHERE id = ?",
                precise,
                precise,
                fixture.fieldA());
        jdbc.update(
                "UPDATE processing_stage SET parser_versions ="
                        + " jsonb_build_object('precision', ?::numeric)"
                        + " WHERE job_id = ? AND stage = 'PARSING' AND status = 'SUCCEEDED'",
                precise,
                fixture.jobId());

        String envelope =
                new String(
                        assembler.assemble(request(fixture), loader.load(request(fixture))).bytes(),
                        java.nio.charset.StandardCharsets.UTF_8);

        assertThat(envelope).contains(precise);
        assertThat(envelope).doesNotContain("0.12345678901234568");
    }

    @Test
    void resolvesAnInactiveGlobalSchemaByItsPersistedExactIdentity() throws Exception {
        Fixture fixture = seedCompleteFixture();
        jdbc.update("UPDATE extraction_schema SET org_id = NULL WHERE id = ?", fixture.schemaId());

        JsonNode root =
                JSON.readTree(
                        assembler
                                .assemble(request(fixture), loader.load(request(fixture)))
                                .bytes());

        assertThat(root.at("/documents/0/fields/0/schema/id").asText())
                .isEqualTo(fixture.schemaId().toString());
        assertThat(root.at("/documents/0/fields/0/schema/version").asText())
                .isEqualTo(fixture.schemaVersion());
    }

    @Test
    void rejectsASameTenantJobWhosePackageDoesNotMatchTheRequestedFixturePackage() {
        Fixture fixture = seedCompleteFixture();
        UUID secondPackage = UUID.randomUUID();
        UUID secondJob = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'second-package')",
                secondPackage,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO processing_job
                    (id, org_id, package_id, idempotency_key, status, attempt, parse_generation)
                VALUES (?, ?, ?, ?, 'EXTRACTING', 1, 3)
                """,
                secondJob,
                ORG_DEV,
                secondPackage,
                "second-job-" + secondJob);
        EnvelopeAssemblyRequest mismatched =
                new EnvelopeAssemblyRequest(
                        fixture.packageId(),
                        secondJob,
                        3,
                        1,
                        "1.0.0",
                        "DOCENGINE-C14N-1",
                        ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);

        assertThatThrownBy(() -> loader.load(mismatched))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    private Fixture seedCompleteFixture() {
        UUID packageId = UUID.randomUUID();
        UUID source0 = UUID.randomUUID();
        UUID source1 = UUID.randomUUID();
        UUID page0 = UUID.randomUUID();
        UUID page1 = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID schemaId = UUID.randomUUID();
        UUID fieldA = UUID.randomUUID();
        UUID fieldB = UUID.randomUUID();
        UUID fieldC = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String documentTypeCode =
                "SYNTHETIC_"
                        + packageId
                                .toString()
                                .replace("-", "")
                                .toUpperCase(java.util.Locale.ROOT);
        String schemaVersion = "inactive-1";

        jdbc.update(
                "INSERT INTO document_package (id, org_id, name, page_count) VALUES (?, ?, ?, 2)",
                packageId,
                ORG_DEV,
                "fixture-package-name");
        insertSource(packageId, source0, 0, SHA_A, 101L);
        insertSource(packageId, source1, 1, SHA_B, 202L);
        insertPage(page0, source0, packageId, 0, 0, false);
        insertPage(page1, source1, packageId, 0, 1, true);

        insertClassification(page0, documentTypeCode, "0.95", "pack-inactive", 101L);
        insertClassification(page1, documentTypeCode, "0.40", null, 102L);
        jdbc.update(
                """
                INSERT INTO logical_document
                    (id, org_id, package_id, ordinal, document_type_code,
                     classification_confidence)
                VALUES (?, ?, ?, 0, ?, 0.95)
                """,
                documentId,
                ORG_DEV,
                packageId,
                documentTypeCode);
        jdbc.update(
                """
                INSERT INTO logical_document_page
                    (logical_document_id, page_id, org_id, ordinal)
                VALUES (?, ?, ?, 0)
                """,
                documentId,
                page0,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO extraction_schema
                    (id, org_id, document_type_code, version, definition, is_active)
                VALUES (?, ?, ?, ?, '{}'::jsonb, false)
                """,
                schemaId,
                ORG_DEV,
                documentTypeCode,
                schemaVersion);
        insertField(fieldA, documentId, schemaId, "A", "ANCHOR_LABEL", "125.5000", "VALID");
        insertField(fieldB, documentId, schemaId, "B", "OCR_LINE", "-25.0000", "WARNING");
        insertField(fieldC, documentId, schemaId, "C", "NONE", null, "MANUAL_REVIEW_REQUIRED");
        insertEvidence(fieldA, page0, "CONTEXT", 0, "30.30");
        insertEvidence(fieldA, page0, "LABEL", 0, "20.20");
        insertEvidence(fieldA, page0, "VALUE", 0, "10.10");

        jdbc.update(
                """
                INSERT INTO processing_job
                    (id, org_id, package_id, idempotency_key, status, attempt, parse_generation)
                VALUES (?, ?, ?, ?, 'EXTRACTING', 4, 3)
                """,
                jobId,
                ORG_DEV,
                packageId,
                "snapshot-it-" + jobId);
        insertStage(jobId, "RENDERING", "SUCCEEDED", 1, "render-digest", "worker-1", "{}");
        insertStage(jobId, "PARSING", "FAILED", 1, null, null, null);
        insertStage(
                jobId,
                "PARSING",
                "SUCCEEDED",
                2,
                "parse-digest",
                "worker-2",
                "{\"pdfium\":\"v5\"}");
        insertStage(jobId, "EXTRACTING", "SUCCEEDED", 1, "extract-digest", null, null);

        return new Fixture(
                packageId,
                source0,
                source1,
                page0,
                page1,
                documentId,
                schemaId,
                fieldA,
                fieldB,
                fieldC,
                jobId,
                documentTypeCode,
                schemaVersion);
    }

    private void insertSource(UUID packageId, UUID id, int ordinal, String sha, long size) {
        jdbc.update(
                """
                INSERT INTO source_file
                    (id, org_id, package_id, ordinal, original_filename, content_type,
                     declared_content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, ?, 'fixture-private-name.pdf', 'application/pdf',
                        'application/private', ?, ?, 'fixture-private-storage-key')
                """,
                id,
                ORG_DEV,
                packageId,
                ordinal,
                size,
                sha);
    }

    private void insertPage(
            UUID id,
            UUID sourceId,
            UUID packageId,
            int sourcePageIndex,
            int packagePageIndex,
            boolean blank) {
        jdbc.update(
                """
                INSERT INTO page
                    (id, org_id, source_file_id, package_id, page_index, package_page_index,
                     width_pt, height_pt, rotation, render_dpi, text_layer, is_blank)
                VALUES (?, ?, ?, ?, ?, ?, 612.50, 792.00, 0, 300, ?, ?)
                """,
                id,
                ORG_DEV,
                sourceId,
                packageId,
                sourcePageIndex,
                packagePageIndex,
                blank ? "NONE" : "NATIVE",
                blank);
    }

    private void insertClassification(
            UUID pageId,
            String documentTypeCode,
            String confidence,
            String pack,
            long spanId) {
        jdbc.update(
                """
                INSERT INTO classification_result
                    (id, org_id, subject_type, subject_id, document_type_code, confidence,
                     method, rule_pack_version, evidence, is_current)
                VALUES (?, ?, 'PAGE', ?, ?, ?::numeric, 'RULE_ANCHOR', ?, ?::jsonb, true)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                pageId,
                documentTypeCode,
                confidence,
                pack,
                classificationEvidenceJson(documentTypeCode, pack, spanId));
    }

    private static String classificationEvidenceJson(
            String documentTypeCode, String packVersion, long spanId) {
        if (packVersion == null) {
            return "{\"anchors\":[],\"scores\":[]}";
        }
        return """
                {"anchors":[{"packType":"%s","packVersion":"%s",
                "anchorId":"fixture-anchor","weight":3,"spanIds":[%d],
                "boxes":[{"x":1.1,"y":2.2,"width":3.3,"height":4.4}],
                "range":{"start":10,"end":20}}],
                "scores":[{"packType":"%s","packVersion":"%s","score":0.9,
                "minConfidence":0.6,"targetScore":5}]}
                """
                .formatted(documentTypeCode, packVersion, spanId, documentTypeCode, packVersion);
    }

    private void insertField(
            UUID id,
            UUID documentId,
            UUID schemaId,
            String groupKey,
            String method,
            String number,
            String validation) {
        boolean missing = "NONE".equals(method);
        jdbc.update(
                """
                INSERT INTO extracted_field
                    (id, org_id, logical_document_id, schema_id, field_name, data_type,
                     group_key, displayed_text, raw_value, normalized_number, extraction_method,
                     extractor_version, confidence, confidence_components, validation_status,
                     review_status, is_sensitive, is_current)
                VALUES (?, ?, ?, ?, 'amount', 'MONEY', ?, ?, ?, ?::numeric, ?, 'extractor-it',
                        ?::numeric, '{"spanConfidence":0.9}'::jsonb, ?, 'NOT_REVIEWED', true, true)
                """,
                id,
                ORG_DEV,
                documentId,
                schemaId,
                groupKey,
                missing ? null : "$125.50",
                missing ? null : "125.50",
                number,
                method,
                missing ? "0.0" : "0.9",
                validation);
    }

    private void insertEvidence(
            UUID fieldId, UUID pageId, String role, int ordinal, String x) {
        jdbc.update(
                """
                INSERT INTO field_evidence
                    (id, org_id, extracted_field_id, page_id, x, y, width, height, role, ordinal)
                VALUES (?, ?, ?, ?, ?::numeric, 20.20, 30.30, 40.40, ?, ?)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                fieldId,
                pageId,
                x,
                role,
                ordinal);
    }

    private void insertStage(
            UUID jobId,
            String stage,
            String status,
            int attempt,
            String digest,
            String worker,
            String parserVersions) {
        jdbc.update(
                """
                INSERT INTO processing_stage
                    (id, org_id, job_id, stage, status, attempt, output_digest, worker_version,
                     parser_versions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                jobId,
                stage,
                status,
                attempt,
                digest,
                worker,
                parserVersions);
    }

    private static EnvelopeAssemblyRequest request(Fixture fixture) {
        return new EnvelopeAssemblyRequest(
                fixture.packageId(),
                fixture.jobId(),
                3,
                1,
                "1.0.0",
                "DOCENGINE-C14N-1",
                ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);
    }

    private record Fixture(
            UUID packageId,
            UUID source0,
            UUID source1,
            UUID page0,
            UUID page1,
            UUID documentId,
            UUID schemaId,
            UUID fieldA,
            UUID fieldB,
            UUID fieldC,
            UUID jobId,
            String documentTypeCode,
            String schemaVersion) {}
}
