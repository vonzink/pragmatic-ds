package com.pragmaticds.rag.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code personal-lookup} is the one optional canned answer. Two prod brains load
 * packs from a data volume that predates it, so a missing key must load cleanly
 * and fall back to the pack's escalation text rather than fail the brain.
 */
class CannedAnswersTest {

    @Test
    void missingPersonalLookupFallsBackToEscalation() {
        DomainPack.CannedAnswers canned = new DomainPack.CannedAnswers(
                "no source", "escalate please", "legal", "tax", "rates", "fraud", null);

        assertNull(canned.personalLookup());
        assertEquals("escalate please", canned.personalLookupOrEscalation());
    }

    @Test
    void blankPersonalLookupAlsoFallsBack() {
        DomainPack.CannedAnswers canned = new DomainPack.CannedAnswers(
                "no source", "escalate please", "legal", "tax", "rates", "fraud", "  ");

        assertEquals("escalate please", canned.personalLookupOrEscalation());
    }

    @Test
    void presentPersonalLookupWins() {
        DomainPack.CannedAnswers canned = new DomainPack.CannedAnswers(
                "no source", "escalate please", "legal", "tax", "rates", "fraud", "no lookups");

        assertEquals("no lookups", canned.personalLookupOrEscalation());
    }

    // _template is excluded: its slug is a placeholder until PackTemplateService
    // instantiates it, and PackTemplateServiceTest already loads that path.
    @Test
    void everyRepoPackLoadsWithTheNewRuleAndAnswer() {
        DomainPackLoader loader = new DomainPackLoader();
        for (String pack : new String[] {"generic"}) {
            DomainPack loaded = loader.load(Path.of("packs", pack));
            assertEquals(false, loaded.guardrails().cannedAnswers().personalLookup().isBlank(),
                    pack + " should define personal-lookup");
            assertEquals(true, loaded.classifierRules().stream()
                            .anyMatch(r -> r.category() == com.pragmaticds.rag.service.ai.QuestionCategory.PERSONAL_LOOKUP),
                    pack + " should carry the PERSONAL_LOOKUP rule");
        }
    }
}
