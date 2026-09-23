package com.pragmaticds.rag.lab.findings;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingTest {

    private static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAGE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static FindingAnchor anchored() {
        return new FindingAnchor(DOC, "PAYSTUB", 0, "ytd_gross", null, PAGE,
                new Box(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
    }

    private static Finding finding(List<FindingAnchor> anchors) {
        return new Finding("field.manual_review_required", "1.0.0", Finding.Severity.WARNING,
                "Field needs manual review.", null, "sha256:aa", anchors, List.of());
    }

    @Test
    void anchorWithAPageIsAnchored() {
        assertTrue(anchored().anchored());
    }

    @Test
    void anchorWithoutAPageIsNotAnchored() {
        FindingAnchor unanchorable =
                new FindingAnchor(DOC, "PAYSTUB", 0, "ytd_gross", null, null, null);
        assertFalse(unanchorable.anchored());
    }

    @Test
    void anchorRejectsAPageWithoutABox() {
        assertThrows(IllegalArgumentException.class,
                () -> new FindingAnchor(DOC, "PAYSTUB", 0, "ytd_gross", null, PAGE, null));
    }

    @Test
    void subjectPartIsStableAndCoversOrdinal() {
        FindingAnchor first = anchored();
        FindingAnchor second =
                new FindingAnchor(DOC, "PAYSTUB", 1, "ytd_gross", null, PAGE,
                        new Box(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(first.subjectPart(), anchored().subjectPart());
        assertFalse(first.subjectPart().equals(second.subjectPart()));
    }

    @Test
    void subjectPartIgnoresGeometry() {
        FindingAnchor moved = new FindingAnchor(DOC, "PAYSTUB", 0, "ytd_gross", null, PAGE,
                new Box(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(anchored().subjectPart(), moved.subjectPart());
    }

    @Test
    void findingStartsWithoutASubjectKey() {
        assertNull(finding(List.of(anchored())).subjectKey());
    }

    @Test
    void withSubjectKeyReturnsACopy() {
        Finding stamped = finding(List.of(anchored())).withSubjectKey("sha256:bb");
        assertEquals("sha256:bb", stamped.subjectKey());
        assertNull(finding(List.of(anchored())).subjectKey());
    }

    @Test
    void anchorsAndCitationIdsAreUnmodifiable() {
        Finding f = finding(List.of(anchored()));
        assertThrows(UnsupportedOperationException.class, () -> f.anchors().add(anchored()));
        assertThrows(UnsupportedOperationException.class, () -> f.citationIds().add("c1"));
    }

    @Test
    void emptyAnchorsAreAllowed() {
        assertEquals(0, finding(List.of()).anchors().size());
    }
}
