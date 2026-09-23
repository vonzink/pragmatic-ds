package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.service.analyze.AnalysisPromptAssembler.Kind;
import com.pragmaticds.rag.service.analyze.AnalysisPromptAssembler.Mode;
import com.pragmaticds.rag.service.analyze.AnalysisPromptAssembler.PromptSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisPromptAssemblerTest {

    private final AnalyzerConfig v1 = new AnalyzerConfig(
            "income", "Income", "BASE", "income guidelines", 8, "{\"borrowers\":[]}", null, "income");
    private final AnalyzerConfig v2 = new AnalyzerConfig(
            "income-v2", "Income (v2)", "BASE2", "income guidelines", 8, "{\"borrowers\":[]}",
            null, "income", "v2");

    @Test
    void skeletonListsEverySectionInModelOrder() {
        List<PromptSection> s = AnalysisPromptAssembler.analyze(Mode.SKELETON, v1, List.of(), null, null);
        assertEquals(List.of("base-prompt", "guidelines", "loan-snapshot", "stated-income",
                        "application-terms", "org-catalog", "document-list", "analyst-notes", "page-selection",
                        "inline-texts", "engine-renderings", "extra-instructions", "output-contract"),
                s.stream().map(PromptSection::id).toList());
        assertEquals(Kind.BASE_PROMPT, s.get(0).kind());
        // The base-prompt section renders exactly as a run renders it, blank line and all:
        // SKELETON differs from RUN only where a run would splice in loan data.
        assertEquals("BASE\n\n", s.get(0).text());
        assertEquals(Kind.STATIC, s.get(s.size() - 1).kind());
        assertTrue(s.get(s.size() - 1).text().contains("Return ONLY valid JSON in EXACTLY this shape"));
        assertTrue(s.stream().filter(x -> x.kind() == Kind.DYNAMIC).count() >= 8);
        assertTrue(s.get(1).text().contains("income guidelines"), "guideline placeholder names the query");
        assertTrue(s.get(1).text().contains("8"), "guideline placeholder names top-k");
    }

    @Test
    void skeletonShowsApplicationTermsOnlyForSubmissionDomainAnalyzers() {
        AnalyzerConfig submission = new AnalyzerConfig(
                "submission-review", "Submission Review", "BASE3", "submission guidelines", 8,
                "{\"schemaVersion\": \"submission-domain-v1\"}", null, "submission", "v2");
        String declared = section(AnalysisPromptAssembler.analyze(Mode.SKELETON, submission,
                List.of(), null, null), "application-terms");
        assertTrue(declared.contains("Filled at run time when the suite sends application terms"), declared);
        assertTrue(declared.contains("earnestMoney"), declared);

        String plain = section(AnalysisPromptAssembler.analyze(Mode.SKELETON, v1,
                List.of(), null, null), "application-terms");
        assertTrue(plain.startsWith("[Not used"), plain);
    }

    private static String section(List<PromptSection> sections, String id) {
        return sections.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow().text();
    }

    @Test
    void skeletonOutputContractFollowsEnvelope() {
        List<PromptSection> s = AnalysisPromptAssembler.analyze(Mode.SKELETON, v2, List.of(), null, null);
        String contract = s.get(s.size() - 1).text();
        assertTrue(contract.contains("Envelope v2"));
        assertTrue(contract.contains("\"analyzer\": \"income-v2\""));
    }

    @Test
    void runModeWithEmptyInputsMatchesTheOldMinimalPrompt() {
        AnalysisContext ctx = new AnalysisContext(List.of(), null, null, null, List.of(), false);
        DocumentBlockService.BuildResult built =
                new DocumentBlockService.BuildResult(List.of(), 0, List.of());
        String joined = AnalysisPromptAssembler.join(
                AnalysisPromptAssembler.analyze(Mode.RUN, v1, List.of(), ctx, built));
        assertTrue(joined.startsWith("BASE\n\nNo guideline context was retrieved; note this in the report.\n\n"));
        assertTrue(joined.endsWith("\"snippet\":\"\"}]}\n"));
        assertFalse(joined.contains("Loan snapshot"));
    }
}
