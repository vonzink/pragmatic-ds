package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.pack.TestPacks;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalServiceTest {

    private static final java.util.Map<String, String> ACRONYMS =
            TestPacks.sample().acronymExpansions();
    private static final java.util.List<com.pragmaticds.rag.pack.CompiledProgram> PROGRAMS =
            com.pragmaticds.rag.pack.CompiledProgram.compile(TestPacks.sample().programRules());

    @Test
    void orQueryDropsStopwordsAndOrsTerms() {
        assertEquals("minimum OR credit OR score OR fha OR loan",
                RetrievalService.toOrQuery("What is the minimum credit score for an FHA loan?"));
    }

    @Test
    void orQueryDeduplicatesTerms() {
        assertEquals("gift OR funds OR down OR payment",
                RetrievalService.toOrQuery("gift funds gift funds down payment"));
    }

    @Test
    void orQueryStripsPunctuation() {
        String result = RetrievalService.toOrQuery("Can I use a co-borrower's income?");
        assertFalse(result.contains("-"));
        assertFalse(result.contains("'"));
    }

    @Test
    void expandsKnownAcronymByAppendingDefinition() {
        assertEquals("What is PMI? private mortgage insurance",
                RetrievalService.expandQuery("What is PMI?", ACRONYMS));
    }

    @Test
    void expandsAcronymRegardlessOfCase() {
        assertEquals("what is pmi? private mortgage insurance",
                RetrievalService.expandQuery("what is pmi?", ACRONYMS));
    }

    @Test
    void expandsMultipleAcronymsInQuestionOrder() {
        assertEquals("How do DTI and LTV affect approval? debt-to-income loan-to-value",
                RetrievalService.expandQuery("How do DTI and LTV affect approval?", ACRONYMS));
    }

    @Test
    void leavesQuestionWithoutAcronymsUnchanged() {
        assertEquals("What documents are required to close?",
                RetrievalService.expandQuery("What documents are required to close?", ACRONYMS));
    }

    // The expansion has to survive tokenization so the keyword arm of hybrid
    // search matches the PMI definition, not just the embedding.
    @Test
    void expandedAcronymReachesKeywordQuery() {
        assertEquals("pmi OR private OR mortgage OR insurance",
                RetrievalService.toOrQuery(RetrievalService.expandQuery("What is PMI?", ACRONYMS)));
    }

    @Test
    void detectsBothProgramsInAComparisonQuestion() {
        assertEquals(Set.of("FHA", "CONVENTIONAL"),
                RetrievalService.detectPrograms("How is an FHA loan different from a conventional loan?", PROGRAMS));
    }

    @Test
    void detectsSingleProgram() {
        assertEquals(Set.of("FHA"),
                RetrievalService.detectPrograms("What is the minimum credit score for an FHA loan?", PROGRAMS));
    }

    @Test
    void detectsNoProgramWhenNoneNamed() {
        assertTrue(RetrievalService.detectPrograms("What documents are required to close?", PROGRAMS).isEmpty());
    }

    @Test
    void programFactorBoostsMatchAndDemotesMismatch() {
        assertEquals(1.2, RetrievalService.programScoreFactor(Set.of("FHA"), "FHA"));
        assertEquals(0.4, RetrievalService.programScoreFactor(Set.of("FHA"), "CONVENTIONAL"));
    }

    @Test
    void programFactorIsNeutralWithoutQuestionProgramOrChunkProgram() {
        assertEquals(1.0, RetrievalService.programScoreFactor(Set.of(), "FHA"));
        assertEquals(1.0, RetrievalService.programScoreFactor(Set.of("FHA"), null));
    }

    // The fix: a comparison question must boost BOTH named programs so neither
    // side is demoted out of the candidate pool (the bug that made
    // "FHA vs conventional" return no-source).
    @Test
    void comparisonQuestionBoostsEitherNamedProgram() {
        Set<String> programs = Set.of("FHA", "CONVENTIONAL");
        assertEquals(1.2, RetrievalService.programScoreFactor(programs, "FHA"));
        assertEquals(1.2, RetrievalService.programScoreFactor(programs, "CONVENTIONAL"));
        assertEquals(0.4, RetrievalService.programScoreFactor(programs, "VA"));
    }

    @Test
    void perBrainThresholdOverridesGlobalDefault() {
        assertEquals(0.40, RetrievalService.effectiveThreshold(0.40, 0.60), 1e-9);
    }

    @Test
    void nullPerBrainThresholdFallsBackToGlobalDefault() {
        assertEquals(0.60, RetrievalService.effectiveThreshold(null, 0.60), 1e-9);
    }
}
