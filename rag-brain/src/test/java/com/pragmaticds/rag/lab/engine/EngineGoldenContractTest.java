package com.pragmaticds.rag.lab.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LlmEvidence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.PageClassification;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.RuleAnchorEvidence;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses envelopes the ENGINE produced, not envelopes we wrote by hand.
 *
 * <p>The hand-written fixtures in {@link EngineEnvelopeParserTest} pin what this parser believes
 * the contract is; they cannot tell us when the engine's output drifts from that belief (it did,
 * silently, with {@code coQualifyingTypes}). Every file under {@code engine-goldens/} is the exact
 * bytes {@code GET /v1/packages/{id}/engine-result} served from a local engine at the commit
 * recorded in {@code MANIFEST.json}, over the engine's synthetic {@code fixtures/} only. Regenerate
 * with {@code tools/engine-goldens/capture_goldens.py} whenever the engine pin moves.
 */
class EngineGoldenContractTest {

    private static final String GOLDENS = "engine-goldens/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EngineEnvelopeParser parser = new EngineEnvelopeParser();

    @TestFactory
    List<DynamicTest> everyEngineGoldenParsesAndMatchesItsManifest() throws IOException {
        JsonNode manifest = MAPPER.readTree(resource("MANIFEST.json"));
        assertTrue(manifest.path("engineCommit").asText().matches("[0-9a-f]{40}"),
                "manifest must record the engine commit the goldens came from");
        List<DynamicTest> tests = new ArrayList<>();
        manifest.path("packages").fields().forEachRemaining(entry -> {
            if (isFieldsGolden(entry.getValue())) {
                return; // not an envelope; see ReviewedFieldsParserTest for its golden coverage.
            }
            tests.add(DynamicTest.dynamicTest(entry.getKey(), () -> verify(entry.getValue())));
        });
        assertTrue(tests.size() >= 7, "expected the seven captured packages");
        return tests;
    }

    private void verify(JsonNode expected) throws Exception {
        byte[] bytes = resource(expected.path("file").asText());
        assertEquals(expected.path("sha256").asText(), sha256(bytes), "golden bytes changed");
        assertEquals(expected.path("bytes").asInt(), bytes.length);

        EngineResultEnvelope envelope = parser.parse(bytes);

        assertEquals(EngineEnvelopeParser.SUPPORTED_ENVELOPE_VERSION, envelope.envelopeVersion());
        JsonNode pages = expected.path("pages");
        assertEquals(pages.size(), envelope.pages().size(), "page count");
        for (int i = 0; i < pages.size(); i++) {
            EnginePage page = envelope.pages().get(i);
            JsonNode want = pages.get(i);
            if (want.isNull()) {
                assertNull(page.classification(), "page " + i + " should be unclassified");
                continue;
            }
            PageClassification classification = page.classification();
            assertNotNull(classification, "page " + i);
            assertEquals(want.path("type").asText(), classification.documentTypeCode(), "page " + i);
            assertEquals(want.path("method").asText(), classification.method(), "page " + i);
            RuleAnchorEvidence evidence =
                    assertInstanceOf(RuleAnchorEvidence.class, classification.evidence());
            List<String> coQualifying = new ArrayList<>();
            want.path("coQualifyingTypes").forEach(t -> coQualifying.add(t.asText()));
            assertEquals(coQualifying, evidence.coQualifyingTypes(), "page " + i);
        }

        JsonNode documents = expected.path("documents");
        assertEquals(documents.size(), envelope.documents().size(), "document count");
        for (int i = 0; i < documents.size(); i++) {
            LogicalDocument document = envelope.documents().get(i);
            assertEquals(documents.get(i).path("type").asText(), document.documentTypeCode());
            assertEquals(documents.get(i).path("pages").asInt(), document.pageIds().size());
            assertEquals(documents.get(i).path("fields").asInt(), document.fields().size());
        }
        assertEquals(expected.path("unassignedPages").asInt(), envelope.unassignedPageIds().size());
        long sensitive = envelope.documents().stream()
                .flatMap(d -> d.fields().stream()).filter(f -> f.sensitive()).count();
        assertEquals(expected.path("sensitiveFields").asLong(), sensitive);
    }

    /** The exact case the hand-written fixtures never covered: a sheet two packs both claimed. */
    @Test
    void aCoQualifyingSheetCarriesTheOtherTypeAndStillParses() throws Exception {
        EngineResultEnvelope tie = parser.parse(resource("tall-1040-with-schedule-c.json"));
        PageClassification ambiguous = tie.pages().get(0).classification();
        assertEquals("UNKNOWN", ambiguous.documentTypeCode(), "an exact tie is UNKNOWN, by design");
        assertEquals(List.of("SCHEDULE_C", "TAX_RETURN"),
                ((RuleAnchorEvidence) ambiguous.evidence()).coQualifyingTypes());

        EngineResultEnvelope won = parser.parse(resource("tall-w2-with-paystub.json"));
        PageClassification paystub = won.pages().get(0).classification();
        assertEquals("PAYSTUB", paystub.documentTypeCode());
        assertEquals(List.of("PAYSTUB", "W2"),
                ((RuleAnchorEvidence) paystub.evidence()).coQualifyingTypes(),
                "the winner is listed among the co-qualifiers, sorted");
    }

    /** The engine emits UNKNOWN pages with rule-anchor evidence and no fields, not as blanks. */
    @Test
    void anUnknownPageIsClassifiedUnknownWithEvidenceRatherThanLeftUnclassified() throws Exception {
        EngineResultEnvelope envelope = parser.parse(resource("unknown-page.json"));
        PageClassification classification = envelope.pages().get(0).classification();
        assertNotNull(classification);
        assertEquals("UNKNOWN", classification.documentTypeCode());
        assertEquals("RULE_ANCHOR", classification.method());
        assertEquals(1, envelope.documents().size());
        assertEquals("UNKNOWN", envelope.documents().get(0).documentTypeCode());
        assertTrue(envelope.documents().get(0).fields().isEmpty());
    }

    /**
     * No golden carries LLM evidence: the engine only writes it with model egress enabled, which
     * the capture never turns on. Pinned here so the gap is visible, not forgotten.
     */
    @Test
    void noGoldenCarriesLlmEvidenceYet() throws Exception {
        JsonNode manifest = MAPPER.readTree(resource("MANIFEST.json"));
        for (Map.Entry<String, JsonNode> entry : iterable(manifest.path("packages"))) {
            if (isFieldsGolden(entry.getValue())) {
                continue; // not an envelope
            }
            EngineResultEnvelope envelope = parser.parse(resource(entry.getValue().path("file").asText()));
            for (EnginePage page : envelope.pages()) {
                if (page.classification() != null) {
                    assertTrue(!(page.classification().evidence() instanceof LlmEvidence),
                            entry.getKey() + " unexpectedly carries LLM evidence; add it to the manifest");
                }
            }
        }
    }

    /** {@code "kind": "fields"} marks a {@code GET /v1/documents/{id}/fields} capture, not an envelope. */
    private static boolean isFieldsGolden(JsonNode entry) {
        return "fields".equals(entry.path("kind").asText(null));
    }

    private static List<Map.Entry<String, JsonNode>> iterable(JsonNode object) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        object.fields().forEachRemaining(entries::add);
        return entries;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = EngineGoldenContractTest.class.getClassLoader()
                .getResourceAsStream(GOLDENS + name)) {
            assertNotNull(in, "missing golden " + name);
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
