package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.AnalyzerPromptRevision;
import com.pragmaticds.rag.domain.AnalyzerPromptRevision.State;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.analyze.AnalysisPromptAssembler;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.analyze.AnalyzerNotFoundException;
import com.pragmaticds.rag.service.analyze.AnalyzerPromptService;
import com.pragmaticds.rag.service.analyze.AnalyzerPromptService.Draft;
import com.pragmaticds.rag.service.analyze.AnalyzerPromptService.PromptState;
import com.pragmaticds.rag.service.analyze.AnalyzerPromptService.Published;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAnalyzerPromptControllerTest {

    private final AnalyzerPromptService prompts = mock(AnalyzerPromptService.class);
    private final AnalysisService analysis = mock(AnalysisService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final AdminAnalyzerPromptController controller = newController();

    private AdminAnalyzerPromptController newController() {
        when(brainResolver.resolve(any())).thenReturn(
                new Brain(TestBrains.DEFAULT_ID, "mortgage", "Acme Corp"));
        return new AdminAnalyzerPromptController(prompts, analysis, brainResolver);
    }

    private static PromptState state(Draft draft) {
        return new PromptState("income", "Income", "income", "income guidelines", 8, "v1", false,
                "PACK", new Published("PACK", "pack", null, null), draft);
    }

    @Test
    void listDelegatesToStates() {
        when(prompts.states(TestBrains.DEFAULT_ID)).thenReturn(List.of(state(null)));
        List<PromptState> body = controller.list("mortgage");
        verify(brainResolver).resolve("mortgage");
        assertEquals(1, body.size());
        assertEquals("income", body.get(0).slug());
    }

    @Test
    void draftSaveValidatesBlankBeforeDelegating() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.saveDraft("income", "mortgage",
                        new AdminAnalyzerPromptController.ContentBody("  ")));
        verify(prompts, never()).saveDraft(any(), any(), any(), any());
    }

    @Test
    void draftSaveAndDiscardDelegate() {
        when(prompts.saveDraft(TestBrains.DEFAULT_ID, "income", "NEW", "admin-api"))
                .thenReturn(state(new Draft("NEW", OffsetDateTime.now(), "admin-api")));
        PromptState saved = controller.saveDraft("income", "mortgage",
                new AdminAnalyzerPromptController.ContentBody("NEW"));
        assertEquals("NEW", saved.draft().content());

        when(prompts.discardDraft(TestBrains.DEFAULT_ID, "income")).thenReturn(state(null));
        assertNull(controller.discardDraft("income", "mortgage").draft());
    }

    @Test
    void publishAndRevertDelegate() {
        when(prompts.publish(TestBrains.DEFAULT_ID, "income", "admin-api")).thenReturn(state(null));
        controller.publish("income", "mortgage");
        verify(prompts).publish(TestBrains.DEFAULT_ID, "income", "admin-api");

        when(prompts.revert(TestBrains.DEFAULT_ID, "income", "admin-api")).thenReturn(state(null));
        controller.revert("income", "mortgage");
        verify(prompts).revert(TestBrains.DEFAULT_ID, "income", "admin-api");
    }

    @Test
    void publishWithoutDraftIs409() {
        ResponseEntity<Map<String, String>> r =
                controller.handleNoDraft(new AnalyzerPromptService.NoDraftException("income"));
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("NO_DRAFT", r.getBody().get("error"));
    }

    @Test
    void unknownAnalyzerIs404() {
        ResponseEntity<Map<String, String>> r =
                controller.handleUnknownAnalyzer(new AnalyzerNotFoundException("nope"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test
    void historyNumbersNewestFirstAndFlagsReverts() {
        AnalyzerPromptRevision newest = mock(AnalyzerPromptRevision.class);
        when(newest.getContent()).thenReturn(null);
        when(newest.getCreatedBy()).thenReturn("a");
        when(newest.getCreatedAt()).thenReturn(OffsetDateTime.now());
        AnalyzerPromptRevision oldest = mock(AnalyzerPromptRevision.class);
        when(oldest.getContent()).thenReturn("X");
        when(oldest.getCreatedBy()).thenReturn("b");
        when(oldest.getCreatedAt()).thenReturn(OffsetDateTime.now().minusDays(1));
        when(prompts.history(TestBrains.DEFAULT_ID, "income")).thenReturn(List.of(newest, oldest));

        List<Map<String, Object>> rows = controller.history("income", "mortgage");

        assertEquals(2, rows.get(0).get("revision"));
        assertEquals(true, rows.get(0).get("reverted"));
        assertEquals(1, rows.get(1).get("revision"));
        assertEquals("X", rows.get(1).get("content"));
    }

    @Test
    void assemblyUsesDraftWhenAskedAndFallsBackToPublished() {
        when(prompts.state(TestBrains.DEFAULT_ID, "income"))
                .thenReturn(state(new Draft("DRAFT", OffsetDateTime.now(), "x")));
        List<AnalysisPromptAssembler.PromptSection> sections = List.of(
                new AnalysisPromptAssembler.PromptSection("base-prompt", "Analyzer base prompt", "DRAFT\n\n",
                        AnalysisPromptAssembler.Kind.BASE_PROMPT));
        when(analysis.assembly(TestBrains.DEFAULT_ID, "income", "DRAFT")).thenReturn(sections);
        when(analysis.assembly(TestBrains.DEFAULT_ID, "income", "PACK")).thenReturn(sections);

        controller.assembly("income", "mortgage", "draft");
        verify(analysis).assembly(TestBrains.DEFAULT_ID, "income", "DRAFT");

        controller.assembly("income", "mortgage", "published");
        verify(analysis).assembly(TestBrains.DEFAULT_ID, "income", "PACK");

        when(prompts.state(TestBrains.DEFAULT_ID, "income")).thenReturn(state(null));
        controller.assembly("income", "mortgage", "draft");
        verify(analysis, times(2)).assembly(TestBrains.DEFAULT_ID, "income", "PACK");
    }
}
