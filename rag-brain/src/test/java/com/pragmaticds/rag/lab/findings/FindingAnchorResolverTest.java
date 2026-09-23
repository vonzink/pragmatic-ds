package com.pragmaticds.rag.lab.findings;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingAnchorResolverTest {

    private static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAGE_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PAGE_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SCHEMA_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static Box box(int x) {
        return new Box(BigDecimal.valueOf(x), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static FieldOccurrence occurrence(String groupKey, List<EvidenceSpan> evidence) {
        return new FieldOccurrence("ytd_gross", groupKey, FieldStatus.FOUND, "MONEY",
                null, null, null, new SchemaRef(SCHEMA_ID, "1"), "OCR", "1.0.0",
                BigDecimal.ONE, null, "OK", false, evidence);
    }

    private static LogicalDocument document(FieldOccurrence occurrence) {
        return new LogicalDocument(DOC, "PAYSTUB", 0, List.of(PAGE_A), List.of(occurrence));
    }

    @Test
    void resolvesTheFirstSpanByOrdinal() {
        FieldOccurrence occurrence = occurrence(null, List.of(
                new EvidenceSpan(PAGE_B, null, null, "VALUE", 2, box(9)),
                new EvidenceSpan(PAGE_A, null, null, "VALUE", 1, box(1))));

        FindingAnchor anchor = FindingAnchorResolver.resolve(document(occurrence), occurrence);

        assertTrue(anchor.anchored());
        assertEquals(PAGE_A, anchor.pageId());
        assertEquals(BigDecimal.ONE, anchor.box().x());
    }

    @Test
    void carriesDocumentIdentityAndOrdinal() {
        FieldOccurrence occurrence = occurrence("row-1", List.of(
                new EvidenceSpan(PAGE_A, null, null, "VALUE", 1, box(1))));

        FindingAnchor anchor = FindingAnchorResolver.resolve(document(occurrence), occurrence);

        assertEquals(DOC, anchor.logicalDocumentId());
        assertEquals("PAYSTUB", anchor.documentTypeCode());
        assertEquals(0, anchor.documentOrdinal());
        assertEquals("ytd_gross", anchor.fieldName());
        assertEquals("row-1", anchor.groupKey());
    }

    @Test
    void anOccurrenceWithNoEvidenceIsUnanchorableNotDropped() {
        FieldOccurrence occurrence = occurrence(null, List.of());

        FindingAnchor anchor = FindingAnchorResolver.resolve(document(occurrence), occurrence);

        assertFalse(anchor.anchored());
        assertNull(anchor.pageId());
        assertNull(anchor.box());
        assertEquals("ytd_gross", anchor.fieldName());
    }
}
