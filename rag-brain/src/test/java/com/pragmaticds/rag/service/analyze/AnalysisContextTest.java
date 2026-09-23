package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest the suite actually sends survives deserialization. {@code DocMeta} used to declare
 * only the four pairing fields, and the Spring-default ObjectMapper (unknown properties ignored)
 * silently dropped every decided-type key the prompt promised to honour — the ALREADY DECIDED
 * rule was in the prompt, and its data never arrived. This pins the round trip so the gap
 * cannot reopen quietly, and pins that unknown keys are ignored by annotation rather than by the
 * mapper's mood, so a suite that adds a key first never turns into a 400.
 */
class AnalysisContextTest {

    /** The suite's manifest entry, verbatim in shape (RagFolderBrainAdapter.contextPart). */
    private static final String SUITE_CONTEXT = """
            {"docs":[{"id":"d1","fileName":"w2.fields.md","sourceFileName":"w2.pdf",
                      "contentType":"text/markdown","sizeBytes":60,"parsed":true,
                      "mdContract":"DOCENGINE-MD-1","engineDocumentIds":["e1"],
                      "assignedDocType":"W-2","assignedBy":"engine","engineConfidence":0.92,
                      "engineSegments":[{"pageStart":1,"pageEnd":1,"assignedDocType":"W-2",
                                         "engineConfidence":0.92,"someFutureKey":true}]},
                     {"id":"d2","fileName":"scan.pdf","contentType":"application/pdf","sizeBytes":9}]}
            """;

    @Test
    void decidedTypeKeysTheSuiteSendsSurviveTheLenientMapper() throws Exception {
        ObjectMapper lenient = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AnalysisContext ctx = lenient.readValue(SUITE_CONTEXT, AnalysisContext.class);

        AnalysisContext.DocMeta d1 = ctx.docs().get(0);
        assertTrue(d1.isParsed());
        assertEquals("DOCENGINE-MD-1", d1.mdContract());
        assertTrue(d1.hasAssignedType());
        assertEquals("W-2", d1.assignedDocType());
        assertEquals("engine", d1.assignedBy());
        assertEquals(0.92, d1.engineConfidence(), 1e-9);
        assertTrue(d1.hasEngineSegments());
        assertEquals(1, d1.engineSegments().get(0).pageStart().intValue());
        assertEquals(1, d1.engineSegments().get(0).pageEnd().intValue());
        assertEquals("W-2", d1.engineSegments().get(0).assignedDocType());

        AnalysisContext.DocMeta d2 = ctx.docs().get(1);
        assertFalse(d2.isParsed());
        assertFalse(d2.hasAssignedType());
        assertFalse(d2.hasEngineSegments(), "absent list reads as empty, never null");
    }

    @Test
    void unknownKeysAreIgnoredByAnnotation_notByMapperConfiguration() throws Exception {
        ObjectMapper strict = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AnalysisContext ctx = strict.readValue(SUITE_CONTEXT, AnalysisContext.class);
        assertEquals("d1", ctx.docs().get(0).id());
        assertEquals("W-2", ctx.docs().get(0).engineSegments().get(0).assignedDocType());
    }

    @Test
    void pairingOnlyConstructorStillWorks() {
        AnalysisContext.DocMeta d = new AnalysisContext.DocMeta("d1", "p.png", "image/png", 3L);
        assertFalse(d.isParsed());
        assertFalse(d.hasAssignedType());
        assertTrue(d.engineSegments().isEmpty());
    }
}
