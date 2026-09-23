package com.pragmaticds.rag.lab.engine;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LlmEvidence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.RuleAnchorEvidence;
import com.pragmaticds.rag.lab.engine.LabContractException.Code;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Strict-consumer contract tests against small synthetic canonical envelopes.
 * Every value below is invented; nothing is copied from any corpus or real document.
 */
class EngineEnvelopeParserTest {

    private static final String PKG = "11111111-1111-4111-8111-111111111111";
    private static final String JOB = "22222222-2222-4222-8222-222222222222";
    private static final String SRC = "33333333-3333-4333-8333-333333333333";
    private static final String PAGE0 = "44444444-4444-4444-8444-444444444440";
    private static final String PAGE1 = "44444444-4444-4444-8444-444444444441";
    private static final String PAGE2 = "44444444-4444-4444-8444-444444444442";
    private static final String DOC0 = "55555555-5555-4555-8555-555555555550";
    private static final String DOC1 = "55555555-5555-4555-8555-555555555551";
    private static final String SCHEMA0 = "66666666-6666-4666-8666-666666666660";
    private static final String SCHEMA1 = "66666666-6666-4666-8666-666666666661";
    private static final String LAYOUT0 = "77777777-7777-4777-8777-777777777770";
    private static final String SRC_SHA = "a1".repeat(32);
    private static final String SET_SHA = "b2".repeat(32);
    private static final String STAGE_SHA = "c3".repeat(32);

    /**
     * One full synthetic envelope: member names in canonical code-point order, numbers in
     * canonical form, semantic arrays in their pinned orders. Line breaks and indentation
     * exist only for readability and are stripped before parsing.
     */
    private static final String TEMPLATE =
            """
            {"canonicalizationVersion":"DOCENGINE-C14N-1",
            "documents":[
            {"documentTypeCode":"W2","fields":[
            {"confidence":0,"confidenceComponents":null,"dataType":"TEXT","displayedText":null,\
            "evidence":[],"extractorVersion":"5.1.0","groupKey":null,"method":"NONE",\
            "name":"employer_ein","normalized":null,"rawValue":null,\
            "schema":{"id":"@SCHEMA0@","version":"2024.1"},"sensitive":true,\
            "status":"MISSING","validationStatus":"NOT_VALIDATED"},
            {"confidence":0.97,"confidenceComponents":{"anchorStrength":1,\
            "normalizerCertainty":1,"spanConfidence":0.97},"dataType":"TEXT",\
            "displayedText":"Example Widgets LLC",\
            "evidence":[{"box":{"height":11.2,"width":120.4,"x":72,"y":96.5},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE0@","role":"VALUE",\
            "textSpanId":9100}],"extractorVersion":"5.1.0","groupKey":null,\
            "method":"ANCHOR_LABEL","name":"employer_name",\
            "normalized":{"date":null,"json":null,"number":null,"text":"Example Widgets LLC"},\
            "rawValue":"Example Widgets LLC",\
            "schema":{"id":"@SCHEMA0@","version":"2024.1"},"sensitive":false,\
            "status":"FOUND","validationStatus":"VALID"},
            {"confidence":0.995,"confidenceComponents":{"anchorStrength":1,\
            "normalizerCertainty":1,"spanConfidence":0.995},"dataType":"DATE",\
            "displayedText":"12/31/2025",\
            "evidence":[{"box":{"height":10,"width":48.2,"x":301.4,"y":58},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE0@","role":"VALUE",\
            "textSpanId":9101}],"extractorVersion":"5.1.0","groupKey":null,\
            "method":"ANCHOR_LABEL","name":"period_end",\
            "normalized":{"date":"2025-12-31","json":null,"number":null,"text":null},\
            "rawValue":"12/31/2025",\
            "schema":{"id":"@SCHEMA0@","version":"2024.1"},"sensitive":false,\
            "status":"FOUND","validationStatus":"VALID"},
            {"confidence":0.9812,"confidenceComponents":{"anchorStrength":1,\
            "normalizerCertainty":0.99,"spanConfidence":0.9911},"dataType":"MONEY",\
            "displayedText":"$1,234.50",\
            "evidence":[{"box":{"height":11.2,"width":54.6,"x":402.35,"y":214.8},\
            "layoutElementId":"@LAYOUT0@","ordinal":0,"pageId":"@PAGE0@","role":"VALUE",\
            "textSpanId":9001},\
            {"box":{"height":11.2,"width":40,"x":352.1,"y":214.8},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE0@","role":"LABEL",\
            "textSpanId":9000}],"extractorVersion":"5.1.0","groupKey":null,\
            "method":"ANCHOR_LABEL","name":"wages",\
            "normalized":{"date":null,"json":null,"number":1234.5,"text":null},\
            "rawValue":"1,234.50",\
            "schema":{"id":"@SCHEMA0@","version":"2024.1"},"sensitive":false,\
            "status":"FOUND","validationStatus":"VALID"}],\
            "id":"@DOC0@","ordinal":0,"pageIds":["@PAGE0@"]},
            {"documentTypeCode":"SCHEDULE_E","fields":[
            {"confidence":0.91,"confidenceComponents":{"anchorStrength":0.95,\
            "normalizerCertainty":1,"spanConfidence":0.9579},"dataType":"ADDRESS",\
            "displayedText":"12 Example Way, Faketown",\
            "evidence":[{"box":{"height":10.1,"width":150,"x":96.3,"y":120.7},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE1@","role":"VALUE",\
            "textSpanId":9200}],"extractorVersion":"5.1.0","groupKey":"A",\
            "method":"ANCHOR_LABEL","name":"property_address",\
            "normalized":{"date":null,"json":{"city":"Faketown","line1":"12 Example Way"},\
            "number":null,"text":null},\
            "rawValue":"12 Example Way, Faketown",\
            "schema":{"id":"@SCHEMA1@","version":"2025.5a"},"sensitive":false,\
            "status":"FOUND","validationStatus":"VALID"},
            {"confidence":0.93,"confidenceComponents":{"anchorStrength":0.93,\
            "normalizerCertainty":1,"spanConfidence":1},"dataType":"MONEY",\
            "displayedText":"2,450",\
            "evidence":[{"box":{"height":10.1,"width":42,"x":198.6,"y":180.2},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE1@","role":"VALUE",\
            "textSpanId":9201}],"extractorVersion":"5.1.0","groupKey":null,\
            "method":"ANCHOR_LABEL","name":"rents_received",\
            "normalized":{"date":null,"json":null,"number":2450,"text":null},\
            "rawValue":"2,450",\
            "schema":{"id":"@SCHEMA1@","version":"2025.5a"},"sensitive":false,\
            "status":"FOUND","validationStatus":"VALID"},
            {"confidence":0.88,"confidenceComponents":{"anchorStrength":0.88,\
            "normalizerCertainty":1,"spanConfidence":1},"dataType":"MONEY",\
            "displayedText":"100.25",\
            "evidence":[{"box":{"height":10.1,"width":36,"x":240.5,"y":180.2},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE1@","role":"VALUE",\
            "textSpanId":9202}],"extractorVersion":"5.1.0","groupKey":"A",\
            "method":"ANCHOR_LABEL","name":"rents_received",\
            "normalized":{"date":null,"json":null,"number":100.25,"text":null},\
            "rawValue":"100.25",\
            "schema":{"id":"@SCHEMA1@","version":"2025.5a"},"sensitive":false,\
            "status":"FOUND","validationStatus":"VALID"},
            {"confidence":0.86,"confidenceComponents":{"anchorStrength":0.86,\
            "normalizerCertainty":1,"spanConfidence":1},"dataType":"MONEY",\
            "displayedText":"200",\
            "evidence":[{"box":{"height":10.1,"width":36,"x":282.75,"y":180.2},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE1@","role":"VALUE",\
            "textSpanId":9203}],"extractorVersion":"5.1.0","groupKey":"B",\
            "method":"ANCHOR_LABEL","name":"rents_received",\
            "normalized":{"date":null,"json":null,"number":200,"text":null},\
            "rawValue":"200",\
            "schema":{"id":"@SCHEMA1@","version":"2025.5a"},"sensitive":false,\
            "status":"FOUND","validationStatus":"MANUAL_REVIEW_REQUIRED"},
            {"confidence":0,"confidenceComponents":null,"dataType":"MONEY",\
            "displayedText":null,"evidence":[],"extractorVersion":"5.1.0","groupKey":"C",\
            "method":"NONE","name":"rents_received","normalized":null,"rawValue":null,\
            "schema":{"id":"@SCHEMA1@","version":"2025.5a"},"sensitive":false,\
            "status":"MISSING","validationStatus":"NOT_VALIDATED"},
            {"confidence":0.9,"confidenceComponents":{"anchorStrength":0.9,\
            "normalizerCertainty":1,"spanConfidence":1},"dataType":"MONEY",\
            "displayedText":"12345678901234567890.123456789",\
            "evidence":[{"box":{"height":10.1,"width":88,"x":198.6,"y":210.9},\
            "layoutElementId":null,"ordinal":0,"pageId":"@PAGE1@","role":"VALUE",\
            "textSpanId":9204}],"extractorVersion":"5.1.0","groupKey":null,\
            "method":"ANCHOR_LABEL","name":"royalties_received",\
            "normalized":{"date":null,"json":null,"number":12345678901234567890.123456789,\
            "text":null},\
            "rawValue":"12345678901234567890.123456789",\
            "schema":{"id":"@SCHEMA1@","version":"2025.5a"},"sensitive":false,\
            "status":"FOUND","validationStatus":"VALID"}],\
            "id":"@DOC1@","ordinal":1,"pageIds":["@PAGE1@"]}],
            "envelopeVersion":"1.0.0",
            "generation":{"packageRevision":1,"parseGeneration":1,\
            "processingJobId":"@JOB@",\
            "reuseEligibility":"PARSE_ONCE_CURRENT_PACKAGE","sourceSetSha256":"@SET_SHA@"},
            "package":{"id":"@PKG@"},
            "pages":[
            {"blank":false,"classification":{"confidence":0.9876,"documentTypeCode":"W2",\
            "evidence":{"anchors":[{"anchorId":"w2.title",\
            "boxes":[{"height":12.5,"width":180,"x":216,"y":36}],"packType":"W2",\
            "packVersion":"3.1.0","range":{"end":18,"start":0},"spanIds":[41,42],\
            "weight":2.5}],\
            "scores":[{"minConfidence":0.7,"packType":"W2","packVersion":"3.1.0",\
            "score":6.5,"targetScore":6}]},"method":"RULE_ANCHOR","rulePackVersion":"3.1.0"},\
            "duplicate":false,"heightPt":792,"id":"@PAGE0@","packagePageIndex":0,\
            "renderDpi":300,"rotation":0,"sourceFileId":"@SRC@","sourcePageIndex":0,\
            "textLayer":"NATIVE","widthPt":612},
            {"blank":false,"classification":{"confidence":0.9542,\
            "documentTypeCode":"SCHEDULE_E",\
            "evidence":{"anchors":[{"anchorId":"sche.part1",\
            "boxes":[{"height":12.5,"width":210,"x":180,"y":40.5}],"packType":"SCHEDULE_E",\
            "packVersion":"1.0.0","range":{"end":24,"start":0},"spanIds":[77],\
            "weight":3}],\
            "scores":[{"minConfidence":0.7,"packType":"SCHEDULE_E","packVersion":"1.0.0",\
            "score":7.25,"targetScore":7}]},"method":"RULE_ANCHOR","rulePackVersion":"1.0.0"},\
            "duplicate":false,"heightPt":792,"id":"@PAGE1@","packagePageIndex":1,\
            "renderDpi":null,"rotation":90,"sourceFileId":"@SRC@","sourcePageIndex":1,\
            "textLayer":"SCANNED","widthPt":612},
            {"blank":true,"classification":null,\
            "duplicate":false,"heightPt":792,"id":"@PAGE2@","packagePageIndex":2,\
            "renderDpi":null,"rotation":0,"sourceFileId":"@SRC@","sourcePageIndex":2,\
            "textLayer":"NONE","widthPt":612}],
            "provenance":{"applicationRelease":{"availability":"UNAVAILABLE"},\
            "extractionEngineRelease":{"availability":"UNAVAILABLE"},\
            "stages":[{"attempt":1,"outputDigest":"@STAGE_SHA@","parserVersions":null,\
            "stage":"VALIDATING","workerVersion":"1.4.0"},\
            {"attempt":1,"outputDigest":null,"parserVersions":{"pdf":"3.0.7"},\
            "stage":"EXTRACTING","workerVersion":null}],\
            "workerContractRelease":{"availability":"UNAVAILABLE"}},
            "sources":[{"contentSha256":"@SRC_SHA@","contentType":"application/pdf",\
            "id":"@SRC@","ordinal":0,"sizeBytes":48211}],
            "unassignedPageIds":["@PAGE2@"]}""";

    private final EngineEnvelopeParser parser = new EngineEnvelopeParser();

    private static String validEnvelope() {
        return TEMPLATE.lines().map(String::strip).collect(Collectors.joining())
                .replace("@PKG@", PKG)
                .replace("@JOB@", JOB)
                .replace("@SRC@", SRC)
                .replace("@PAGE0@", PAGE0)
                .replace("@PAGE1@", PAGE1)
                .replace("@PAGE2@", PAGE2)
                .replace("@DOC0@", DOC0)
                .replace("@DOC1@", DOC1)
                .replace("@SCHEMA0@", SCHEMA0)
                .replace("@SCHEMA1@", SCHEMA1)
                .replace("@LAYOUT0@", LAYOUT0)
                .replace("@SRC_SHA@", SRC_SHA)
                .replace("@SET_SHA@", SET_SHA)
                .replace("@STAGE_SHA@", STAGE_SHA);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Replaces one unique occurrence; fails the test if the target is absent or ambiguous. */
    private static String mutate(String json, String target, String replacement) {
        int first = json.indexOf(target);
        assertTrue(first >= 0, "fixture must contain the mutation target");
        assertEquals(first, json.lastIndexOf(target), "mutation target must be unique");
        return json.replace(target, replacement);
    }

    /** Replaces every occurrence; the target must exist at least once. */
    private static String mutateAll(String json, String target, String replacement) {
        assertTrue(json.contains(target), "fixture must contain the mutation target");
        return json.replace(target, replacement);
    }

    private EngineResultEnvelope parseValid() {
        return parser.parse(bytes(validEnvelope()));
    }

    private void assertRejected(Code expected, String json) {
        LabContractException exception =
                assertThrows(LabContractException.class, () -> parser.parse(bytes(json)));
        assertEquals(expected, exception.code());
    }

    // ---------------------------------------------------------------- positive contract

    @Test
    void parsesTheFullSyntheticEnvelopeAndPinsVersionAndIdentity() {
        EngineResultEnvelope envelope = parseValid();

        assertEquals("1.0.0", envelope.envelopeVersion());
        assertEquals("DOCENGINE-C14N-1", envelope.canonicalizationVersion());
        assertEquals("1.0.0", EngineEnvelopeParser.SUPPORTED_ENVELOPE_VERSION);
        assertEquals(
                "DOCENGINE-C14N-1", EngineEnvelopeParser.SUPPORTED_CANONICALIZATION_VERSION);
        assertEquals(UUID.fromString(PKG), envelope.packageId());
        assertEquals(UUID.fromString(JOB), envelope.generation().processingJobId());
        assertEquals(1, envelope.generation().parseGeneration());
        assertEquals(1, envelope.generation().packageRevision());
        assertEquals(SET_SHA, envelope.generation().sourceSetSha256());
        assertEquals("PARSE_ONCE_CURRENT_PACKAGE", envelope.generation().reuseEligibility());
    }

    @Test
    void retainsTheReceivedBytesAndTheirSha256AsTheOnlyIdentity() throws Exception {
        byte[] received = bytes(validEnvelope());

        EngineResultEnvelope envelope = parser.parse(received);

        assertArrayEquals(received, envelope.artifact().bytes());
        assertEquals(received.length, envelope.artifact().byteCount());
        String expectedSha =
                HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(received));
        assertEquals(expectedSha, envelope.artifact().sha256());
    }

    @Test
    void pinsTheVersionedCanonicalMediaType() {
        assertEquals(
                "application/vnd.pragmaticds.document-engine-result+json;version=1",
                EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE);
    }

    @Test
    void parsesSourcesWithContentDigestIdentity() {
        List<EngineResultEnvelope.SourceFile> sources = parseValid().sources();

        assertEquals(1, sources.size());
        assertEquals(UUID.fromString(SRC), sources.get(0).id());
        assertEquals(0, sources.get(0).ordinal());
        assertEquals(SRC_SHA, sources.get(0).contentSha256());
        assertEquals(48211L, sources.get(0).sizeBytes());
        assertEquals("application/pdf", sources.get(0).contentType());
    }

    @Test
    void parsesPagesWithGeometryRotationTextLayerAndExplicitNullMembers() {
        List<EnginePage> pages = parseValid().pages();

        assertEquals(3, pages.size());
        EnginePage first = pages.get(0);
        assertEquals(new BigDecimal("612"), first.widthPt());
        assertEquals(new BigDecimal("792"), first.heightPt());
        assertEquals(0, first.rotation());
        assertEquals(300, first.renderDpi());
        assertEquals("NATIVE", first.textLayer());
        assertFalse(first.blank());

        EnginePage second = pages.get(1);
        assertEquals(90, second.rotation());
        assertNull(second.renderDpi());
        assertEquals("SCANNED", second.textLayer());

        EnginePage third = pages.get(2);
        assertTrue(third.blank());
        assertNull(third.classification());
        assertEquals("NONE", third.textLayer());
    }

    @Test
    void parsesPageClassificationWithAnchorsAndScores() {
        EngineResultEnvelope.PageClassification classification =
                parseValid().pages().get(0).classification();

        assertNotNull(classification);
        assertEquals("W2", classification.documentTypeCode());
        assertEquals(new BigDecimal("0.9876"), classification.confidence());
        assertEquals("RULE_ANCHOR", classification.method());
        assertEquals("3.1.0", classification.rulePackVersion());
        RuleAnchorEvidence evidence = (RuleAnchorEvidence) classification.evidence();
        assertEquals(1, evidence.anchors().size());
        assertTrue(evidence.coQualifyingTypes().isEmpty(), "absent member reads as empty");
        EngineResultEnvelope.ClassificationAnchor anchor =
                evidence.anchors().get(0);
        assertEquals("w2.title", anchor.anchorId());
        assertEquals(new BigDecimal("2.5"), anchor.weight());
        assertEquals(List.of(41L, 42L), anchor.spanIds());
        assertEquals(new BigDecimal("216"), anchor.boxes().get(0).x());
        assertEquals(0, anchor.range().start());
        assertEquals(18, anchor.range().end());
        EngineResultEnvelope.ClassificationScore score =
                evidence.scores().get(0);
        assertEquals(new BigDecimal("0.7"), score.minConfidence());
        assertEquals(new BigDecimal("6.5"), score.score());
        assertEquals(new BigDecimal("6"), score.targetScore());
    }

    @Test
    void parsesDocumentsPageMembershipAndUnassignedPages() {
        EngineResultEnvelope envelope = parseValid();

        assertEquals(2, envelope.documents().size());
        LogicalDocument w2 = envelope.documents().get(0);
        assertEquals(UUID.fromString(DOC0), w2.id());
        assertEquals("W2", w2.documentTypeCode());
        assertEquals(0, w2.ordinal());
        assertEquals(List.of(UUID.fromString(PAGE0)), w2.pageIds());
        LogicalDocument scheduleE = envelope.documents().get(1);
        assertEquals("SCHEDULE_E", scheduleE.documentTypeCode());
        assertEquals(List.of(UUID.fromString(PAGE1)), scheduleE.pageIds());
        assertEquals(List.of(UUID.fromString(PAGE2)), envelope.unassignedPageIds());
    }

    @Test
    void parsesExplicitFoundAndMissingFieldStates() {
        LogicalDocument w2 = parseValid().documents().get(0);

        FieldOccurrence missing = w2.fields().get(0);
        assertEquals("employer_ein", missing.name());
        assertEquals(FieldStatus.MISSING, missing.status());
        assertEquals("NONE", missing.method());
        assertEquals(0, missing.confidence().signum());
        assertNull(missing.normalized());
        assertNull(missing.displayedText());
        assertNull(missing.rawValue());
        assertNull(missing.confidenceComponents());
        assertTrue(missing.evidence().isEmpty());
        assertTrue(missing.sensitive());

        FieldOccurrence wages = w2.fields().get(3);
        assertEquals("wages", wages.name());
        assertEquals(FieldStatus.FOUND, wages.status());
        assertEquals("ANCHOR_LABEL", wages.method());
        assertEquals("VALID", wages.validationStatus());
        assertEquals(UUID.fromString(SCHEMA0), wages.schema().id());
        assertEquals("2024.1", wages.schema().version());
    }

    @Test
    void everyParsedOccurrenceIsMarkedAsComingFromTheUnreviewedMachineSource() {
        EngineResultEnvelope envelope = parseValid();
        for (LogicalDocument document : envelope.documents()) {
            for (FieldOccurrence field : document.fields()) {
                assertEquals(EngineResultEnvelope.ReviewState.UNREVIEWED_SOURCE,
                        field.reviewState(), field.name());
            }
        }
    }

    @Test
    void parsesTypedNormalizedArmsWithExactlyOneArmPopulated() {
        EngineResultEnvelope envelope = parseValid();
        LogicalDocument w2 = envelope.documents().get(0);
        LogicalDocument scheduleE = envelope.documents().get(1);

        FieldOccurrence text = w2.fields().get(1);
        assertEquals("Example Widgets LLC", text.normalized().text());
        assertNull(text.normalized().number());
        assertNull(text.normalized().date());
        assertNull(text.normalized().json());

        FieldOccurrence date = w2.fields().get(2);
        assertEquals(LocalDate.of(2025, 12, 31), date.normalized().date());
        assertNull(date.normalized().text());

        FieldOccurrence number = w2.fields().get(3);
        assertEquals(new BigDecimal("1234.5"), number.normalized().number());

        FieldOccurrence json = scheduleE.fields().get(0);
        assertEquals(
                Map.of("city", "Faketown", "line1", "12 Example Way"),
                json.normalized().json());
    }

    @Test
    void preservesBigDecimalPrecisionWithoutRounding() {
        LogicalDocument scheduleE = parseValid().documents().get(1);

        FieldOccurrence royalties = scheduleE.fields().get(5);
        assertEquals("royalties_received", royalties.name());
        assertEquals(
                new BigDecimal("12345678901234567890.123456789"),
                royalties.normalized().number());
    }

    @Test
    void parsesGroupedOccurrencesWithNullAThenBThenCKeys() {
        LogicalDocument scheduleE = parseValid().documents().get(1);
        List<FieldOccurrence> rents =
                scheduleE.fields().stream()
                        .filter(field -> field.name().equals("rents_received"))
                        .toList();

        assertEquals(4, rents.size());
        assertNull(rents.get(0).groupKey());
        assertEquals("A", rents.get(1).groupKey());
        assertEquals("B", rents.get(2).groupKey());
        assertEquals("C", rents.get(3).groupKey());
        assertEquals(new BigDecimal("2450"), rents.get(0).normalized().number());
        assertEquals(new BigDecimal("100.25"), rents.get(1).normalized().number());
        assertEquals(new BigDecimal("200"), rents.get(2).normalized().number());
        assertEquals(FieldStatus.MISSING, rents.get(3).status());
        assertEquals("C", rents.get(3).groupKey());
        assertEquals("MANUAL_REVIEW_REQUIRED", rents.get(2).validationStatus());
    }

    @Test
    void parsesCompleteEvidenceCoordinatesInValueThenLabelOrder() {
        FieldOccurrence wages = parseValid().documents().get(0).fields().get(3);

        assertEquals(2, wages.evidence().size());
        EvidenceSpan value = wages.evidence().get(0);
        assertEquals("VALUE", value.role());
        assertEquals(0, value.ordinal());
        assertEquals(UUID.fromString(PAGE0), value.pageId());
        assertEquals(UUID.fromString(LAYOUT0), value.layoutElementId());
        assertEquals(9001L, value.textSpanId());
        assertEquals(new BigDecimal("402.35"), value.box().x());
        assertEquals(new BigDecimal("214.8"), value.box().y());
        assertEquals(new BigDecimal("54.6"), value.box().width());
        assertEquals(new BigDecimal("11.2"), value.box().height());
        EvidenceSpan label = wages.evidence().get(1);
        assertEquals("LABEL", label.role());
        assertNull(label.layoutElementId());
        assertEquals(9000L, label.textSpanId());
    }

    @Test
    void parsesConfidenceWithExactlyThreeComponents() {
        FieldOccurrence wages = parseValid().documents().get(0).fields().get(3);

        assertEquals(new BigDecimal("0.9812"), wages.confidence());
        assertEquals(new BigDecimal("0.9911"), wages.confidenceComponents().spanConfidence());
        assertEquals(new BigDecimal("1"), wages.confidenceComponents().anchorStrength());
        assertEquals(
                new BigDecimal("0.99"), wages.confidenceComponents().normalizerCertainty());
    }

    @Test
    void parsesProvenanceStagesAndParserVersions() {
        EngineResultEnvelope.Provenance provenance = parseValid().provenance();

        assertEquals("UNAVAILABLE", provenance.applicationRelease().availability());
        assertEquals("UNAVAILABLE", provenance.extractionEngineRelease().availability());
        assertEquals("UNAVAILABLE", provenance.workerContractRelease().availability());
        assertEquals(2, provenance.stages().size());
        assertEquals("VALIDATING", provenance.stages().get(0).stage());
        assertEquals(1, provenance.stages().get(0).attempt());
        assertEquals(STAGE_SHA, provenance.stages().get(0).outputDigest());
        assertEquals("1.4.0", provenance.stages().get(0).workerVersion());
        assertNull(provenance.stages().get(0).parserVersions());
        assertEquals("EXTRACTING", provenance.stages().get(1).stage());
        assertEquals(Map.of("pdf", "3.0.7"), provenance.stages().get(1).parserVersions());
    }

    // ---------------------------------------------------------------- strict JSON boundary

    @Test
    void rejectsAnEmptyBody() {
        assertRejected(Code.ENVELOPE_EMPTY, "");
    }

    @Test
    void rejectsDuplicateObjectKeys() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"envelopeVersion\":\"1.0.0\",",
                        "\"envelopeVersion\":\"1.0.0\",\"envelopeVersion\":\"1.0.0\",");
        assertRejected(Code.ENVELOPE_NOT_STRICT_JSON, json);
    }

    @Test
    void rejectsTrailingTokensAfterTheRootValue() {
        assertRejected(Code.ENVELOPE_NOT_STRICT_JSON, validEnvelope() + " {}");
    }

    @Test
    void rejectsNonFiniteNumbers() {
        String json = mutate(validEnvelope(), "\"confidence\":0.9876,", "\"confidence\":NaN,");
        assertRejected(Code.ENVELOPE_NOT_STRICT_JSON, json);
    }

    @Test
    void rejectsNumbersWithTrailingZeros() {
        String json =
                mutate(validEnvelope(), "\"confidence\":0.9812,", "\"confidence\":0.98120,");
        assertRejected(Code.ENVELOPE_NUMBER_NOT_CANONICAL, json);
    }

    @Test
    void rejectsExponentNumbers() {
        String json = mutate(validEnvelope(), "\"number\":2450,", "\"number\":2.45e3,");
        assertRejected(Code.ENVELOPE_NUMBER_NOT_CANONICAL, json);
    }

    @Test
    void rejectsNegativeZero() {
        String json = mutate(validEnvelope(), "\"y\":36}", "\"y\":-0}");
        assertRejected(Code.ENVELOPE_NUMBER_NOT_CANONICAL, json);
    }

    @Test
    void rejectsNonCanonicalObjectKeyOrder() {
        String json =
                mutate(
                        validEnvelope(),
                        "{\"blank\":true,\"classification\":null",
                        "{\"classification\":null,\"blank\":true");
        assertRejected(Code.ENVELOPE_KEY_ORDER_NOT_CANONICAL, json);
    }

    @Test
    void rejectsANonObjectRoot() {
        assertRejected(Code.ENVELOPE_ROOT_NOT_OBJECT, "[]");
        assertRejected(Code.ENVELOPE_ROOT_NOT_OBJECT, "\"envelope\"");
        assertRejected(Code.ENVELOPE_ROOT_NOT_OBJECT, "12");
    }

    // ---------------------------------------------------------------- versions and members

    @Test
    void rejectsAnUnknownEnvelopeVersion() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"envelopeVersion\":\"1.0.0\"",
                        "\"envelopeVersion\":\"2.0.0\"");
        assertRejected(Code.ENVELOPE_VERSION_UNSUPPORTED, json);
    }

    @Test
    void rejectsAnUnknownCanonicalizationVersion() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"canonicalizationVersion\":\"DOCENGINE-C14N-1\"",
                        "\"canonicalizationVersion\":\"DOCENGINE-C14N-9\"");
        assertRejected(Code.CANONICALIZATION_VERSION_UNSUPPORTED, json);
    }

    @Test
    void rejectsMissingGenerationIdentity() {
        String json =
                mutate(validEnvelope(), "\"processingJobId\":\"" + JOB + "\",", "");
        assertRejected(Code.ENVELOPE_MEMBER_MISSING, json);
    }

    @Test
    void rejectsAWhollyAbsentGenerationMember() {
        String generation =
                "\"generation\":{\"packageRevision\":1,\"parseGeneration\":1,"
                        + "\"processingJobId\":\"" + JOB + "\","
                        + "\"reuseEligibility\":\"PARSE_ONCE_CURRENT_PACKAGE\","
                        + "\"sourceSetSha256\":\"" + SET_SHA + "\"},";
        String json = mutate(validEnvelope(), generation, "");
        assertRejected(Code.ENVELOPE_MEMBER_MISSING, json);
    }

    @Test
    void rejectsAnUnknownTopLevelMember() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"unassignedPageIds\":[\"" + PAGE2 + "\"]}",
                        "\"unassignedPageIds\":[\"" + PAGE2 + "\"],\"zz_extra\":1}");
        assertRejected(Code.ENVELOPE_MEMBER_UNKNOWN, json);
    }

    @Test
    void rejectsAnOmittedGroupKeyMemberBecausePresentAndNullIsTheContract() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"groupKey\":null,\"method\":\"ANCHOR_LABEL\",\"name\":\"wages\"",
                        "\"method\":\"ANCHOR_LABEL\",\"name\":\"wages\"");
        assertRejected(Code.ENVELOPE_MEMBER_MISSING, json);
    }

    // ---------------------------------------------------------------- forbidden members

    @Test
    void rejectsAStorageKeyMember() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"sizeBytes\":48211}",
                        "\"sizeBytes\":48211,\"storageKey\":\"k\"}");
        assertRejected(Code.ENVELOPE_FORBIDDEN_MEMBER, json);
    }

    @Test
    void rejectsAReviewMemberInsideAField() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"rawValue\":\"12/31/2025\"",
                        "\"rawValue\":\"12/31/2025\",\"reviewStatus\":\"CONFIRMED\"");
        assertRejected(Code.ENVELOPE_FORBIDDEN_MEMBER, json);
    }

    @Test
    void rejectsACorrectionKeyAtAnyDepth() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"city\":\"Faketown\"",
                        "\"city\":\"Faketown\",\"correction\":\"x\"");
        assertRejected(Code.ENVELOPE_FORBIDDEN_MEMBER, json);
    }

    @Test
    void rejectsAFilenameKeyAtAnyDepth() {
        String json =
                mutate(
                        validEnvelope(),
                        "{\"pdf\":\"3.0.7\"}",
                        "{\"fileName\":\"upload.pdf\",\"pdf\":\"3.0.7\"}");
        assertRejected(Code.ENVELOPE_FORBIDDEN_MEMBER, json);
    }

    @Test
    void rejectsARawTextKeyAtAnyDepth() {
        String json =
                mutate(
                        validEnvelope(),
                        "{\"pdf\":\"3.0.7\"}",
                        "{\"pdf\":\"3.0.7\",\"rawText\":\"whole page\"}");
        assertRejected(Code.ENVELOPE_FORBIDDEN_MEMBER, json);
    }

    // ---------------------------------------------------------------- shapes and vocabulary

    @Test
    void rejectsAMalformedUuid() {
        String json =
                mutate(
                        validEnvelope(),
                        "{\"id\":\"" + PKG + "\"}",
                        "{\"id\":\"not-a-uuid\"}");
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsAMalformedDigest() {
        String truncated = SET_SHA.substring(0, 63);
        String json = mutate(validEnvelope(), SET_SHA, truncated);
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsAnUnknownReuseEligibility() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"reuseEligibility\":\"PARSE_ONCE_CURRENT_PACKAGE\"",
                        "\"reuseEligibility\":\"PARSE_MANY\"");
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsAnUnknownTextLayer() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"textLayer\":\"SCANNED\"",
                        "\"textLayer\":\"PHOTOCOPY\"");
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsAnUnknownFieldStatus() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"sensitive\":true,\"status\":\"MISSING\"",
                        "\"sensitive\":true,\"status\":\"PENDING\"");
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsAnUnknownValidationStatus() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"validationStatus\":\"MANUAL_REVIEW_REQUIRED\"",
                        "\"validationStatus\":\"SORT_OF_VALID\"");
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsAnUnknownEvidenceRole() {
        String json =
                mutate(validEnvelope(), "\"role\":\"LABEL\"", "\"role\":\"FOOTNOTE\"");
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsAnUnknownStageName() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"stage\":\"VALIDATING\"",
                        "\"stage\":\"DAYDREAMING\"");
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, json);
    }

    @Test
    void rejectsConfidenceComponentsWithAMissingComponent() {
        String json =
                mutate(
                        validEnvelope(),
                        "{\"anchorStrength\":1,\"normalizerCertainty\":0.99,"
                                + "\"spanConfidence\":0.9911}",
                        "{\"anchorStrength\":1,\"normalizerCertainty\":0.99}");
        assertRejected(Code.ENVELOPE_MEMBER_MISSING, json);
    }

    @Test
    void rejectsConfidenceComponentsWithAFourthComponent() {
        String json =
                mutate(
                        validEnvelope(),
                        "{\"anchorStrength\":1,\"normalizerCertainty\":0.99,"
                                + "\"spanConfidence\":0.9911}",
                        "{\"anchorStrength\":1,\"normalizerCertainty\":0.99,"
                                + "\"spanConfidence\":0.9911,\"zz\":1}");
        assertRejected(Code.ENVELOPE_MEMBER_UNKNOWN, json);
    }

    // ---------------------------------------------------------------- duplicate identities

    @Test
    void rejectsTwoPagesClaimingOnePageId() {
        String json =
                mutate(validEnvelope(), "\"id\":\"" + PAGE2 + "\"", "\"id\":\"" + PAGE1 + "\"");
        assertRejected(Code.IDENTITY_DUPLICATE, json);
    }

    @Test
    void rejectsTwoPagesClaimingOnePackagePageIndex() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"packagePageIndex\":2,",
                        "\"packagePageIndex\":1,");
        assertRejected(Code.IDENTITY_DUPLICATE, json);
    }

    @Test
    void rejectsTwoDocumentsClaimingOneOrdinal() {
        String json =
                mutate(validEnvelope(), "\"ordinal\":1,\"pageIds\"", "\"ordinal\":0,\"pageIds\"");
        assertRejected(Code.IDENTITY_DUPLICATE, json);
    }

    @Test
    void rejectsTwoOccurrencesClaimingOneFieldAndGroupKey() {
        String json = mutate(validEnvelope(), "\"groupKey\":\"B\"", "\"groupKey\":\"A\"");
        assertRejected(Code.IDENTITY_DUPLICATE, json);
    }

    @Test
    void rejectsTwoSuccessfulAttemptsClaimingOneStage() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"stage\":\"EXTRACTING\",\"workerVersion\":null",
                        "\"stage\":\"VALIDATING\",\"workerVersion\":null");
        assertRejected(Code.IDENTITY_DUPLICATE, json);
    }

    // ---------------------------------------------------------------- semantic ordering

    @Test
    void rejectsPagesOutOfPackagePageIndexOrder() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"packagePageIndex\":1,",
                        "\"packagePageIndex\":5,");
        assertRejected(Code.ORDERING_VIOLATION, json);
    }

    @Test
    void rejectsFieldsOutOfNameOrder() {
        String json =
                mutate(validEnvelope(), "\"name\":\"wages\"", "\"name\":\"aardvark_wages\"");
        assertRejected(Code.ORDERING_VIOLATION, json);
    }

    @Test
    void rejectsEvidenceRolesOutOfValueLabelContextOrder() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"role\":\"VALUE\",\"textSpanId\":9001",
                        "\"role\":\"CONTEXT\",\"textSpanId\":9001");
        assertRejected(Code.ORDERING_VIOLATION, json);
    }

    @Test
    void rejectsStagesOutOfPipelineOrder() {
        String swappedFirst =
                mutate(
                        validEnvelope(),
                        "\"stage\":\"VALIDATING\",\"workerVersion\":\"1.4.0\"",
                        "\"stage\":\"EXTRACTING\",\"workerVersion\":\"1.4.0\"");
        String json =
                mutate(
                        swappedFirst,
                        "\"stage\":\"EXTRACTING\",\"workerVersion\":null",
                        "\"stage\":\"VALIDATING\",\"workerVersion\":null");
        assertRejected(Code.ORDERING_VIOLATION, json);
    }

    // ---------------------------------------------------------------- page membership

    @Test
    void rejectsEvidenceOnAPageOutsideTheOwningDocument() {
        String json =
                mutateAll(
                        validEnvelope(),
                        "\"pageId\":\"" + PAGE0 + "\"",
                        "\"pageId\":\"" + PAGE1 + "\"");
        assertRejected(Code.PAGE_MEMBERSHIP_VIOLATION, json);
    }

    @Test
    void rejectsAPageClaimedByTwoDocuments() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"pageIds\":[\"" + PAGE1 + "\"]",
                        "\"pageIds\":[\"" + PAGE0 + "\",\"" + PAGE1 + "\"]");
        assertRejected(Code.PAGE_MEMBERSHIP_VIOLATION, json);
    }

    @Test
    void rejectsADocumentReferencingAnUnknownPage() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"pageIds\":[\"" + PAGE1 + "\"]",
                        "\"pageIds\":[\"99999999-9999-4999-8999-999999999999\"]");
        assertRejected(Code.PAGE_MEMBERSHIP_VIOLATION, json);
    }

    @Test
    void rejectsUnassignedPageIdsThatDoNotMatchTheUnclaimedPages() {
        String emptied =
                mutate(
                        validEnvelope(),
                        "\"unassignedPageIds\":[\"" + PAGE2 + "\"]",
                        "\"unassignedPageIds\":[]");
        assertRejected(Code.PAGE_MEMBERSHIP_VIOLATION, emptied);

        String assignedListed =
                mutate(
                        validEnvelope(),
                        "\"unassignedPageIds\":[\"" + PAGE2 + "\"]",
                        "\"unassignedPageIds\":[\"" + PAGE0 + "\"]");
        assertRejected(Code.PAGE_MEMBERSHIP_VIOLATION, assignedListed);
    }

    // ---------------------------------------------------------------- field contradictions

    @Test
    void rejectsAFoundFieldWithoutANormalizedValue() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"normalized\":{\"date\":null,\"json\":null,\"number\":1234.5,"
                                + "\"text\":null}",
                        "\"normalized\":null");
        assertRejected(Code.FIELD_CONTRADICTION, json);
    }

    @Test
    void rejectsAFoundFieldWithTwoNormalizedArms() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"normalized\":{\"date\":null,\"json\":null,\"number\":1234.5,"
                                + "\"text\":null}",
                        "\"normalized\":{\"date\":null,\"json\":null,\"number\":1234.5,"
                                + "\"text\":\"1234.5\"}");
        assertRejected(Code.FIELD_CONTRADICTION, json);
    }

    @Test
    void rejectsAFoundFieldWithoutConfidenceComponents() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"confidence\":0.9812,\"confidenceComponents\":{\"anchorStrength\":1,"
                                + "\"normalizerCertainty\":0.99,\"spanConfidence\":0.9911}",
                        "\"confidence\":0.9812,\"confidenceComponents\":null");
        assertRejected(Code.FIELD_CONTRADICTION, json);
    }

    @Test
    void rejectsAMissingFieldWithARawValue() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"name\":\"employer_ein\",\"normalized\":null,\"rawValue\":null",
                        "\"name\":\"employer_ein\",\"normalized\":null,"
                                + "\"rawValue\":\"77-0000000\"");
        assertRejected(Code.FIELD_CONTRADICTION, json);
    }

    @Test
    void rejectsAMissingFieldWithNonzeroConfidence() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"confidence\":0,\"confidenceComponents\":null,\"dataType\":\"TEXT\"",
                        "\"confidence\":0.5,\"confidenceComponents\":null,"
                                + "\"dataType\":\"TEXT\"");
        assertRejected(Code.FIELD_CONTRADICTION, json);
    }

    @Test
    void rejectsAMissingFieldWithEvidence() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"evidence\":[],\"extractorVersion\":\"5.1.0\",\"groupKey\":\"C\"",
                        "\"evidence\":[{\"box\":{\"height\":10.1,\"width\":36,\"x\":240.5,"
                                + "\"y\":180.2},\"layoutElementId\":null,\"ordinal\":0,"
                                + "\"pageId\":\"" + PAGE1 + "\",\"role\":\"VALUE\","
                                + "\"textSpanId\":9299}],"
                                + "\"extractorVersion\":\"5.1.0\",\"groupKey\":\"C\"");
        assertRejected(Code.FIELD_CONTRADICTION, json);
    }

    @Test
    void rejectsAStatusThatContradictsTheExtractionMethod() {
        String json =
                mutate(
                        validEnvelope(),
                        "\"sensitive\":true,\"status\":\"MISSING\"",
                        "\"sensitive\":true,\"status\":\"FOUND\"");
        assertRejected(Code.FIELD_CONTRADICTION, json);
    }

    // ---------------------------------------------------------------- immutability

    @Test
    void envelopeIsImmuneToLaterMutationOfTheInputArray() {
        byte[] received = bytes(validEnvelope());
        byte[] original = received.clone();

        EngineResultEnvelope envelope = parser.parse(received);
        received[0] = '[';
        received[received.length - 1] = 'X';

        assertArrayEquals(original, envelope.artifact().bytes());
    }

    @Test
    void artifactBytesAccessorReturnsDefensiveCopies() {
        EngineResultEnvelope envelope = parseValid();

        byte[] first = envelope.artifact().bytes();
        first[0] = 'X';

        assertFalse(envelope.artifact().bytes()[0] == 'X');
    }

    @Test
    void parsedCollectionsAreUnmodifiable() {
        EngineResultEnvelope envelope = parseValid();
        LogicalDocument w2 = envelope.documents().get(0);
        FieldOccurrence wages = w2.fields().get(3);

        assertThrows(
                UnsupportedOperationException.class, () -> envelope.documents().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> envelope.sources().clear());
        assertThrows(UnsupportedOperationException.class, () -> envelope.pages().remove(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> envelope.unassignedPageIds().add(UUID.randomUUID()));
        assertThrows(UnsupportedOperationException.class, () -> w2.fields().remove(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> w2.pageIds().add(UUID.randomUUID()));
        assertThrows(
                UnsupportedOperationException.class, () -> wages.evidence().remove(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> envelope.provenance().stages().remove(0));
        EngineResultEnvelope.PageClassification classification =
                envelope.pages().get(0).classification();
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((RuleAnchorEvidence) classification.evidence()).anchors().remove(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((RuleAnchorEvidence) classification.evidence()).anchors().get(0).spanIds().add(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizedJsonTreeIsDeeplyUnmodifiable() {
        FieldOccurrence address = parseValid().documents().get(1).fields().get(0);

        Map<String, Object> json = (Map<String, Object>) address.normalized().json();
        assertThrows(UnsupportedOperationException.class, () -> json.put("zip", "00000"));
        assertThrows(UnsupportedOperationException.class, () -> json.remove("city"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parserVersionsMapIsUnmodifiable() {
        EngineResultEnvelope.StageAttempt extracting =
                parseValid().provenance().stages().get(1);

        Map<String, Object> versions = (Map<String, Object>) extracting.parserVersions();
        assertThrows(UnsupportedOperationException.class, () -> versions.put("zz", "1"));
    }

    @Test
    void directRecordConstructionDefensivelyCopiesLists() {
        List<UUID> pageIds = new ArrayList<>(List.of(UUID.fromString(PAGE0)));
        LogicalDocument document =
                new LogicalDocument(
                        UUID.fromString(DOC0), "W2", 0, pageIds, new ArrayList<>());

        pageIds.add(UUID.fromString(PAGE1));

        assertEquals(1, document.pageIds().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> document.pageIds().add(UUID.fromString(PAGE2)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void directNormalizedJsonConstructionIsDeeplyDefensive() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("city", "Faketown");
        EngineResultEnvelope.NormalizedValue normalized =
                new EngineResultEnvelope.NormalizedValue(null, null, null, mutable);

        mutable.put("line1", "12 Example Way");

        Map<String, Object> stored = (Map<String, Object>) normalized.json();
        assertEquals(Map.of("city", "Faketown"), stored);
        assertThrows(UnsupportedOperationException.class, () -> stored.put("zip", "00000"));
    }

    // ---------------------------------------------------------------- payload-free errors

    @Test
    void contractErrorsCarryOnlyAStableCodeAndNeverThePayload() {
        String canary = "CANARY-987-65-4320-EXAMPLE";
        String withCanary =
                mutate(
                        validEnvelope(),
                        "\"displayedText\":\"100.25\"",
                        "\"displayedText\":\"" + canary + "\"");
        String json = mutate(withCanary, "\"groupKey\":\"B\"", "\"groupKey\":\"A\"");

        LabContractException exception =
                assertThrows(LabContractException.class, () -> parser.parse(bytes(json)));

        assertEquals(Code.IDENTITY_DUPLICATE, exception.code());
        assertEquals(Code.IDENTITY_DUPLICATE.name(), exception.getMessage());
        assertNull(exception.getCause());
        assertFalse(exception.toString().contains(canary));
        assertFalse(exception.toString().contains("100.25"));
    }
    // ---------------------------------------------------------------- classification evidence union

    /** The W2 page's rule-anchor evidence, exactly as the joined template spells it. */
    private static final String W2_ANCHOR_TAIL =
            "\"weight\":2.5}],\"scores\":[{\"minConfidence\":0.7,\"packType\":\"W2\"";

    /** The SCHEDULE_E page's classification object, from its evidence member to its end. */
    private static final String SCHEDULE_E_EVIDENCE_TO_END =
            "\"evidence\":{\"anchors\":[{\"anchorId\":\"sche.part1\","
                    + "\"boxes\":[{\"height\":12.5,\"width\":210,\"x\":180,\"y\":40.5}],"
                    + "\"packType\":\"SCHEDULE_E\",\"packVersion\":\"1.0.0\","
                    + "\"range\":{\"end\":24,\"start\":0},\"spanIds\":[77],\"weight\":3}],"
                    + "\"scores\":[{\"minConfidence\":0.7,\"packType\":\"SCHEDULE_E\","
                    + "\"packVersion\":\"1.0.0\",\"score\":7.25,\"targetScore\":7}]},"
                    + "\"method\":\"RULE_ANCHOR\",\"rulePackVersion\":\"1.0.0\"}";

    /** Engine #64 LLM evidence in canonical key order, with the optional runner-up present. */
    private static final String LLM_EVIDENCE =
            "{\"deterministicRunnerUp\":{\"score\":0.2,\"type\":\"PAYSTUB\"},"
                    + "\"matchedSpanIds\":[77,78],\"model\":\"anthropic/claude-sonnet-5\","
                    + "\"offsets\":{\"end\":24,\"start\":0},"
                    + "\"promptVersion\":\"page-classify.v3\",\"source\":\"LLM_FALLBACK\"}";

    private static String withCoQualifyingTypes(String types) {
        return mutate(
                validEnvelope(),
                W2_ANCHOR_TAIL,
                "\"weight\":2.5}],\"coQualifyingTypes\":" + types
                        + ",\"scores\":[{\"minConfidence\":0.7,\"packType\":\"W2\"");
    }

    private static String withLlmPage(String evidence, String method) {
        return mutate(
                validEnvelope(),
                SCHEDULE_E_EVIDENCE_TO_END,
                "\"evidence\":" + evidence + ",\"method\":\"" + method
                        + "\",\"rulePackVersion\":null}");
    }

    @Test
    void parsesRuleAnchorEvidenceWithCoQualifyingTypes() {
        EngineResultEnvelope envelope =
                parser.parse(bytes(withCoQualifyingTypes("[\"SCHEDULE_C\",\"TAX_RETURN\"]")));
        RuleAnchorEvidence evidence =
                (RuleAnchorEvidence) envelope.pages().get(0).classification().evidence();
        assertEquals(List.of("SCHEDULE_C", "TAX_RETURN"), evidence.coQualifyingTypes());
        assertEquals(1, evidence.anchors().size());
        assertEquals(1, evidence.scores().size());
    }

    @Test
    void parsesLlmClassificationEvidence() {
        EngineResultEnvelope envelope = parser.parse(bytes(withLlmPage(LLM_EVIDENCE, "LLM")));
        EngineResultEnvelope.PageClassification classification =
                envelope.pages().get(1).classification();
        assertEquals("LLM", classification.method());
        assertNull(classification.rulePackVersion());
        LlmEvidence evidence = (LlmEvidence) classification.evidence();
        assertEquals("anthropic/claude-sonnet-5", evidence.model());
        assertEquals("page-classify.v3", evidence.promptVersion());
        assertEquals("LLM_FALLBACK", evidence.source());
        assertEquals(List.of(77L, 78L), evidence.matchedSpanIds());
        assertEquals(0, evidence.offsets().start());
        assertEquals(24, evidence.offsets().end());
        assertNotNull(evidence.deterministicRunnerUp());
        assertEquals("PAYSTUB", evidence.deterministicRunnerUp().type());
        assertEquals(new BigDecimal("0.2"), evidence.deterministicRunnerUp().score());
    }

    @Test
    void parsesLlmEvidenceWithoutTheOptionalRunnerUp() {
        String evidence = LLM_EVIDENCE.replace(
                "{\"deterministicRunnerUp\":{\"score\":0.2,\"type\":\"PAYSTUB\"},", "{");
        EngineResultEnvelope envelope = parser.parse(bytes(withLlmPage(evidence, "LLM")));
        LlmEvidence parsed = (LlmEvidence) envelope.pages().get(1).classification().evidence();
        assertNull(parsed.deterministicRunnerUp());
    }

    @Test
    void rejectsAnEmptyCoQualifyingTypesArray() {
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, withCoQualifyingTypes("[]"));
    }

    @Test
    void rejectsCoQualifyingTypesThatAreNotDistinctTypeCodes() {
        assertRejected(
                Code.ENVELOPE_VALUE_MALFORMED,
                withCoQualifyingTypes("[\"SCHEDULE_C\",\"SCHEDULE_C\"]"));
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, withCoQualifyingTypes("[\"schedule_c\"]"));
        assertRejected(Code.ENVELOPE_VALUE_MALFORMED, withCoQualifyingTypes("[1]"));
    }

    @Test
    void rejectsAnLlmEvidenceWithAStrayMember() {
        String stray = LLM_EVIDENCE.replace(
                "\"source\":\"LLM_FALLBACK\"}", "\"source\":\"LLM_FALLBACK\",\"temperature\":0}");
        assertRejected(Code.ENVELOPE_MEMBER_UNKNOWN, withLlmPage(stray, "LLM"));
    }

    @Test
    void rejectsAnLlmEvidenceMissingARequiredMember() {
        String missing = LLM_EVIDENCE.replace("\"offsets\":{\"end\":24,\"start\":0},", "");
        assertRejected(Code.ENVELOPE_MEMBER_MISSING, withLlmPage(missing, "LLM"));
    }

    @Test
    void rejectsMalformedLlmEvidenceValues() {
        assertRejected(
                Code.ENVELOPE_VALUE_MALFORMED,
                withLlmPage(LLM_EVIDENCE.replace("[77,78]", "[0,78]"), "LLM"));
        assertRejected(
                Code.ENVELOPE_VALUE_MALFORMED,
                withLlmPage(LLM_EVIDENCE.replace("\"end\":24,\"start\":0", "\"end\":3,\"start\":9"), "LLM"));
        assertRejected(
                Code.ENVELOPE_VALUE_MALFORMED,
                withLlmPage(LLM_EVIDENCE.replace("\"model\":\"anthropic/claude-sonnet-5\"", "\"model\":\"\""), "LLM"));
        assertRejected(
                Code.ENVELOPE_VALUE_MALFORMED,
                withLlmPage(LLM_EVIDENCE.replace("\"type\":\"PAYSTUB\"", "\"type\":\"paystub\""), "LLM"));
    }

    @Test
    void rejectsEvidenceWhoseShapeDisagreesWithItsMethod() {
        // Anchor-shaped evidence declared as LLM: the anchor members are foreign to that contract.
        String anchorsAsLlm =
                mutate(
                        validEnvelope(),
                        "\"method\":\"RULE_ANCHOR\",\"rulePackVersion\":\"3.1.0\"",
                        "\"method\":\"LLM\",\"rulePackVersion\":\"3.1.0\"");
        assertRejected(Code.ENVELOPE_MEMBER_UNKNOWN, anchorsAsLlm);
        // LLM-shaped evidence declared as RULE_ANCHOR.
        assertRejected(Code.ENVELOPE_MEMBER_UNKNOWN, withLlmPage(LLM_EVIDENCE, "RULE_ANCHOR"));
    }

    @Test
    void rejectsAClassificationMethodWithNoEvidenceContract() {
        // The engine's column allows ML and HUMAN, but neither declares an evidence shape yet.
        for (String method : List.of("ML", "HUMAN", "RULES", "")) {
            assertRejected(Code.ENVELOPE_VALUE_MALFORMED, withLlmPage(LLM_EVIDENCE, method));
        }
    }
}
