package com.pragmaticds.rag.service.answer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationContextServiceTest {

    private final ConversationContextService svc = new ConversationContextService();

    @Test
    void anaphoricFollowUpAdoptsPriorQuestionTopic() {
        // The screenshot case: a bare "how do i use it" retrieves nothing on its
        // own. Prepending the prior question carries the "it" antecedent.
        String out = svc.contextualize("how do i use it",
                List.of("where is the link for loan sifter"));
        assertEquals("where is the link for loan sifter how do i use it", out);
    }

    @Test
    void usesMostRecentPriorQuestionOnly() {
        String out = svc.contextualize("how does that work",
                List.of("where is the pipeline", "where is loan sifter"));
        assertEquals("where is loan sifter how does that work", out);
    }

    @Test
    void variousAnaphorsTrigger() {
        assertEquals("topic what about them",
                svc.contextualize("what about them", List.of("topic")));
        assertEquals("topic show me those",
                svc.contextualize("show me those", List.of("topic")));
        assertEquals("topic open the second one",
                svc.contextualize("open the second one", List.of("topic")));
        assertEquals("topic is this available",
                svc.contextualize("is this available", List.of("topic")));
    }

    @Test
    void selfContainedFollowUpIsLeftUnchanged() {
        // No anaphor → a standalone question must NOT be polluted with the prior
        // topic, even mid-conversation.
        String q = "how do i fund a loan";
        assertEquals(q, svc.contextualize(q, List.of("where is loan sifter")));
    }

    @Test
    void firstQuestionWithNoPriorIsUnchanged() {
        String q = "how do i use it";
        assertEquals(q, svc.contextualize(q, List.of()));
        assertEquals(q, svc.contextualize(q, null));
    }

    @Test
    void anaphorMustBeAWholeWordNotASubstring() {
        // "commit", "itemize", "district" contain "it"/"this" as substrings but
        // are not anaphors — must not trigger.
        String q = "how do i commit and itemize a district report";
        assertEquals(q, svc.contextualize(q, List.of("prior topic")));
    }

    @Test
    void blankPriorQuestionsAreSkipped() {
        String out = svc.contextualize("how do i use it", java.util.Arrays.asList("  ", null, "loan sifter"));
        assertEquals("loan sifter how do i use it", out);
    }

    @Test
    void allBlankPriorLeavesQuestionUnchanged() {
        String q = "how do i use it";
        assertEquals(q, svc.contextualize(q, java.util.Arrays.asList("  ", null)));
    }

    @Test
    void nullOrBlankQuestionReturnedAsIs() {
        assertEquals(null, svc.contextualize(null, List.of("topic")));
        assertEquals("   ", svc.contextualize("   ", List.of("topic")));
    }

    @Test
    void caseInsensitiveAnaphorAndTrimmedJoin() {
        String out = svc.contextualize("How Do I Use IT",
                List.of("  where is loan sifter  "));
        assertEquals("where is loan sifter How Do I Use IT", out);
    }
}
