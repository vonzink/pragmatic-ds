package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.domain.BrainPageGuide;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.domain.BrainSourceLink;
import com.pragmaticds.rag.domain.LinkAuthority;
import com.pragmaticds.rag.domain.Surface;
import com.pragmaticds.rag.pack.TestPacks;
import com.pragmaticds.rag.service.profile.BrainProfileService;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.pragmaticds.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptBuilderServiceTest {

    private RulesService rulesService;
    private BrainProfileService profileService;
    private PromptBuilderService promptBuilder;

    @BeforeEach
    void setUp() {
        rulesService = mock(RulesService.class);
        when(rulesService.effectiveHard(DEFAULT_ID)).thenReturn(TestPacks.sample().hardRules());
        when(rulesService.effectiveGuidance(DEFAULT_ID)).thenReturn(TestPacks.sample().guidance());
        profileService = mock(BrainProfileService.class);
        when(profileService.getOrCreate(DEFAULT_ID)).thenReturn(defaultProfile());
        promptBuilder = new PromptBuilderService(TestPacks.registry(), rulesService, profileService);
    }

    private RetrievedChunk sampleChunk() {
        return new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                "Overtime income may be used when received for two years.",
                "Fannie Mae Selling Guide", "AGENCY_GUIDELINE",
                "selling-guide.pdf", "Selling Guide 2026",
                "B3-3.1-01", 12, LocalDate.of(2026, 1, 1),
                0.9, 0.7, 0.83);
    }

    private RetrievedChunk chunkWithContent(String content) {
        return new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                content, "Source", "AGENCY_GUIDELINE",
                "doc.pdf", "Doc", "S1", 1, LocalDate.of(2026, 1, 1),
                0.9, 0.7, 0.83);
    }

    @Test
    void capsSourceContextToTokenBudget() {
        String filler = "lorem ipsum dolor sit amet ".repeat(50);
        RetrievedChunk first = chunkWithContent("FIRST-CHUNK " + filler);
        RetrievedChunk second = chunkWithContent("SECOND-CHUNK " + filler);
        PromptBuilderService capped =
                new PromptBuilderService(TestPacks.registry(), rulesService, profileService, 40);

        String prompt = capped.build("Q?", List.of(first, second), DEFAULT_ID);

        // Top-ranked chunk is always kept; the second blows the 40-token budget.
        assertTrue(prompt.contains("FIRST-CHUNK"));
        assertFalse(prompt.contains("SECOND-CHUNK"));
    }

    @Test
    void includesQuestionAndContext() {
        String prompt = promptBuilder.build("Can I use overtime income?", List.of(sampleChunk()), DEFAULT_ID);
        assertTrue(prompt.contains("Can I use overtime income?"));
        assertTrue(prompt.contains("Overtime income may be used"));
        assertTrue(prompt.contains("B3-3.1-01"));
        assertTrue(prompt.contains("Fannie Mae Selling Guide"));
    }

    @Test
    void includesComplianceRules() {
        String prompt = promptBuilder.build("What is PMI?", List.of(sampleChunk()), DEFAULT_ID);
        assertTrue(prompt.contains("Do not answer from general knowledge"));
        assertTrue(prompt.contains("Do not invent mortgage guidelines"));
        assertTrue(prompt.contains("Include citations"));
    }

    @Test
    void requiresNonEmptyCitationsWhenSourcesProvided() {
        String prompt = promptBuilder.build("What is PMI?", List.of(sampleChunk()), DEFAULT_ID);
        assertTrue(prompt.contains("must contain at least one entry"));
    }

    @Test
    void includesDisclaimer() {
        String prompt = promptBuilder.build("What is DTI?", List.of(sampleChunk()), DEFAULT_ID);
        assertTrue(prompt.contains("Profile disclaimer."));
    }

    @Test
    void disclaimerUsesProfileBeforePackFallback() {
        assertEquals("Profile disclaimer.", promptBuilder.disclaimer(DEFAULT_ID));
    }

    @Test
    void includesProfileGuidanceFields() {
        BrainProfile profile = defaultProfile();
        profile.setPurpose("Help users navigate approved product information.");
        profile.setAudience("first-time buyer");
        profile.setPersonality("direct and plainspoken");
        profile.setTone("warm but precise");
        profile.setExpertiseLevel("beginner");
        profile.setAnswerLength("short");
        profile.setConfidenceTarget(0.82);
        profile.setClarificationPolicy("Ask one question before guessing.");
        profile.setEscalationPolicy("Escalate custom qualification advice.");
        profile.setCitationPolicy("cite every factual claim");
        profile.setCtaPolicy("Offer the next relevant page.");
        profile.setDisclaimer("Custom profile disclaimer.");
        when(profileService.getOrCreate(DEFAULT_ID)).thenReturn(profile);

        String prompt = promptBuilder.build("What is PMI?", List.of(sampleChunk()), DEFAULT_ID);

        assertTrue(prompt.contains("Help users navigate approved product information."));
        assertTrue(prompt.contains("first-time buyer"));
        assertTrue(prompt.contains("direct and plainspoken"));
        assertTrue(prompt.contains("warm but precise"));
        assertTrue(prompt.contains("beginner"));
        assertTrue(prompt.contains("short"));
        assertTrue(prompt.contains("0.82"));
        assertTrue(prompt.contains("Ask one question before guessing."));
        assertTrue(prompt.contains("Escalate custom qualification advice."));
        assertTrue(prompt.contains("cite every factual claim"));
        assertTrue(prompt.contains("Offer the next relevant page."));
        assertTrue(prompt.contains("Custom profile disclaimer."));
    }

    @Test
    void includesSideEvidenceWithoutTreatingLinksAsCorpusProof() {
        BrainPageGuide guide = new BrainPageGuide(
                DEFAULT_ID,
                "/purchase",
                "Purchase Guide",
                "Help users understand purchase loan options.",
                Surface.PUBLIC,
                List.of("purchase"),
                List.of("Review the purchase checklist."),
                List.of(),
                List.of(),
                List.of("purchase"),
                "test");
        BrainSourceLink link = new BrainSourceLink(
                DEFAULT_ID,
                "HUD Handbook",
                "https://example.test/hud",
                "example.test",
                LinkAuthority.PRIMARY,
                List.of("pmi"),
                false,
                List.of("navigation reference"),
                List.of("eligibility decisioning"),
                Surface.PUBLIC,
                "test");

        String prompt = promptBuilder.build("What is PMI?", List.of(sampleChunk()), DEFAULT_ID,
                new PlannedEvidence(List.of(guide), List.of(link)));

        assertTrue(prompt.contains("Side evidence"));
        assertTrue(prompt.contains("Purchase Guide"));
        assertTrue(prompt.contains("/purchase"));
        assertTrue(prompt.contains("Review the purchase checklist."));
        assertTrue(prompt.contains("HUD Handbook"));
        assertTrue(prompt.contains("https://example.test/hud"));
        assertTrue(prompt.contains("Do not treat side links as corpus factual proof."));
    }

    @Test
    void handlesEmptyContext() {
        String prompt = promptBuilder.build("What is escrow?", List.of(), DEFAULT_ID);
        assertTrue(prompt.contains("(no source context found)"));
    }

    @Test
    void composesTemplateSlotsInOrder() {
        String prompt = promptBuilder.build("What is escrow?", List.of(), DEFAULT_ID);
        String expected = TestPacks.sample().promptTemplate().formatted(
                TestPacks.sample().hardRules() + "\n\n" + promptBuilder.profileGuidance(DEFAULT_ID),
                TestPacks.sample().guidance() + "\n\n" + promptBuilder.sideEvidenceGuidance(PlannedEvidence.empty()),
                "(no source context found)",
                "What is escrow?",
                "Profile disclaimer.");
        assertEquals(expected, prompt);
    }

    @Test
    void customHardRulesReachThePrompt() {
        when(rulesService.effectiveHard(DEFAULT_ID)).thenReturn("ONLY ANSWER IN HAIKU.");
        String prompt = promptBuilder.build("What is PMI?", List.of(), DEFAULT_ID);
        assertTrue(prompt.contains("ONLY ANSWER IN HAIKU."));
        assertTrue(prompt.contains("What is PMI?"));
    }

    private BrainProfile defaultProfile() {
        BrainProfile profile = new BrainProfile();
        profile.setBrainId(DEFAULT_ID);
        profile.setPurpose("Answer from approved sources.");
        profile.setAudience("public visitor");
        profile.setPersonality("source-grounded");
        profile.setTone("professional");
        profile.setExpertiseLevel("intermediate");
        profile.setAnswerLength("balanced");
        profile.setConfidenceTarget(0.9);
        profile.setClarificationPolicy("Ask when missing facts.");
        profile.setEscalationPolicy("Escalate unsupported requests.");
        profile.setCitationPolicy("required_when_sources_used");
        profile.setCtaPolicy("Suggest useful next steps.");
        profile.setDisclaimer("Profile disclaimer.");
        return profile;
    }
}
