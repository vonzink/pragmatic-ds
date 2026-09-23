package com.pragmaticds.rag.lab.findings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FindingAssemblerTest {

    private static Finding finding(String ruleId, String docType, int ordinal, String field) {
        FindingAnchor anchor = new FindingAnchor(
                UUID.randomUUID(), docType, ordinal, field, null, null, null);
        return new Finding(ruleId, "1.0.0", Finding.Severity.WARNING, "summary",
                null, "sha256:aa", List.of(anchor), List.of());
    }

    @Test
    void stampsASubjectKeyOnEveryFinding() {
        List<Finding> assembled = FindingAssembler.assemble(
                List.of(finding("r.one", "PAYSTUB", 0, "ytd_gross")), "scope-a");

        assertEquals(1, assembled.size());
        assertEquals(true, assembled.get(0).subjectKey().startsWith("sha256:"));
    }

    @Test
    void theSameProblemInADifferentPackageKeepsItsSubjectKey() {
        String first = FindingAssembler
                .assemble(List.of(finding("r.one", "PAYSTUB", 0, "ytd_gross")), "scope-a")
                .get(0).subjectKey();
        String second = FindingAssembler
                .assemble(List.of(finding("r.one", "PAYSTUB", 0, "ytd_gross")), "scope-a")
                .get(0).subjectKey();

        assertEquals(first, second);
    }

    @Test
    void twoPaystubsInOnePackageDoNotCollide() {
        List<Finding> assembled = FindingAssembler.assemble(List.of(
                finding("r.one", "PAYSTUB", 0, "ytd_gross"),
                finding("r.one", "PAYSTUB", 1, "ytd_gross")), "scope-a");

        assertNotEquals(assembled.get(0).subjectKey(), assembled.get(1).subjectKey());
    }

    @Test
    void adifferentScopeIsADifferentSubject() {
        String a = FindingAssembler
                .assemble(List.of(finding("r.one", "PAYSTUB", 0, "ytd_gross")), "scope-a")
                .get(0).subjectKey();
        String b = FindingAssembler
                .assemble(List.of(finding("r.one", "PAYSTUB", 0, "ytd_gross")), "scope-b")
                .get(0).subjectKey();

        assertNotEquals(a, b);
    }

    @Test
    void withoutAScopeTheSubjectKeyStaysNull() {
        List<Finding> assembled = FindingAssembler.assemble(
                List.of(finding("r.one", "PAYSTUB", 0, "ytd_gross")), null);

        assertNull(assembled.get(0).subjectKey());
    }

    @Test
    void ordersByStageOneFieldsNotBySubjectKey() {
        List<Finding> assembled = FindingAssembler.assemble(List.of(
                finding("r.two", "PAYSTUB", 0, "net_pay"),
                finding("r.one", "W2", 1, "wages"),
                finding("r.one", "PAYSTUB", 0, "ytd_gross")), null);

        assertEquals("r.one", assembled.get(0).ruleId());
        assertEquals("PAYSTUB", assembled.get(0).anchors().get(0).documentTypeCode());
        assertEquals("r.one", assembled.get(1).ruleId());
        assertEquals("W2", assembled.get(1).anchors().get(0).documentTypeCode());
        assertEquals("r.two", assembled.get(2).ruleId());
    }

    @Test
    void orderIsIndependentOfInputOrder() {
        List<Finding> forward = FindingAssembler.assemble(List.of(
                finding("r.one", "PAYSTUB", 0, "a"), finding("r.two", "PAYSTUB", 0, "b")), null);
        List<Finding> reversed = FindingAssembler.assemble(List.of(
                finding("r.two", "PAYSTUB", 0, "b"), finding("r.one", "PAYSTUB", 0, "a")), null);

        assertEquals(forward.get(0).ruleId(), reversed.get(0).ruleId());
        assertEquals(forward.get(1).ruleId(), reversed.get(1).ruleId());
    }

    @Test
    void twoRulesClaimingOneSubjectIsRejected() {
        assertThrows(FindingAssembler.DuplicateSubjectException.class,
                () -> FindingAssembler.assemble(List.of(
                        finding("r.one", "PAYSTUB", 0, "ytd_gross"),
                        finding("r.one", "PAYSTUB", 0, "ytd_gross")), "scope-a"));
    }

    @Test
    void duplicateSubjectsAreNotRejectedWithoutAScope() {
        List<Finding> assembled = FindingAssembler.assemble(List.of(
                finding("r.one", "PAYSTUB", 0, "ytd_gross"),
                finding("r.one", "PAYSTUB", 0, "ytd_gross")), null);

        assertEquals(2, assembled.size());
    }

    @Test
    void aFindingWithNoAnchorsStillGetsASubjectKey() {
        Finding unanchored = new Finding("r.one", "1.0.0", Finding.Severity.INFO, "package-level",
                null, "sha256:aa", List.of(), List.of());

        assertEquals(true,
                FindingAssembler.assemble(List.of(unanchored), "scope-a")
                        .get(0).subjectKey().startsWith("sha256:"));
    }
}
