package com.pragmaticds.rag.service.clarification;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.ClarificationRule;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.repository.ClarificationRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClarificationServiceTest {

    private final ClarificationRuleRepository rules = mock(ClarificationRuleRepository.class);
    private final ClarificationService service = new ClarificationService(rules);

    @Test
    void asksForFirstMissingRequiredFact() {
        ClarificationRule rule = new ClarificationRule();
        rule.setTopic("eligibility");
        rule.setIntent("ELIGIBILITY");
        rule.setRequiredFacts(List.of("occupancy", "propertyType"));
        rule.setQuestion("Is this for a primary residence?");
        rule.setPriority(10);
        rule.setRequiredForPublic(true);
        when(rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(TestBrains.DEFAULT_ID)).thenReturn(List.of(rule));

        ClarificationDecision decision = service.decide(
                TestBrains.DEFAULT_ID, "Can I use FHA for a duplex?", "PUBLIC", Map.of("propertyType", "duplex"));

        assertEquals(ResponseType.CLARIFY, decision.responseType());
        assertEquals(List.of("occupancy"), decision.missingFacts());
        assertEquals("Is this for a primary residence?", decision.question());
    }

    @Test
    void answersWhenRequiredFactsArePresent() {
        ClarificationRule rule = new ClarificationRule();
        rule.setTopic("eligibility");
        rule.setIntent("ELIGIBILITY");
        rule.setRequiredFacts(List.of("occupancy"));
        rule.setQuestion("Is this for a primary residence?");
        rule.setRequiredForPublic(true);
        when(rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(TestBrains.DEFAULT_ID)).thenReturn(List.of(rule));

        ClarificationDecision decision = service.decide(
                TestBrains.DEFAULT_ID, "Can I use FHA for a duplex?", "PUBLIC", Map.of("occupancy", "primary"));

        assertEquals(ResponseType.ANSWER, decision.responseType());
        assertTrue(decision.missingFacts().isEmpty());
    }

    @Test
    void navigatesForPageFindingIntent() {
        ClarificationDecision decision = service.decide(
                TestBrains.DEFAULT_ID, "Where do I apply?", "PUBLIC", Map.of());

        assertEquals(ResponseType.NAVIGATE, decision.responseType());
    }
}
