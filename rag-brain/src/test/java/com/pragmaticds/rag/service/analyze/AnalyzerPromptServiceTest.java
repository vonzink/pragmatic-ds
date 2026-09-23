package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.domain.AnalyzerPromptRevision;
import com.pragmaticds.rag.domain.AnalyzerPromptRevision.State;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.TestPacks;
import com.pragmaticds.rag.repository.AnalyzerPromptRevisionRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.pragmaticds.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyzerPromptServiceTest {

    private final AnalyzerPromptRevisionRepository repo = mock(AnalyzerPromptRevisionRepository.class);

    private AnalyzerPromptService service() {
        return new AnalyzerPromptService(repo, TestPacks.registry());
    }

    private static String packIncomePrompt() {
        return TestPacks.sample().analyzers().stream()
                .filter(a -> a.slug().equals("income")).findFirst().orElseThrow().basePrompt();
    }

    /** A persisted-looking revision: @PrePersist never ran, so stub the timestamp. */
    private static AnalyzerPromptRevision row(State state, String content, String by) {
        AnalyzerPromptRevision r = mock(AnalyzerPromptRevision.class);
        when(r.getBrainId()).thenReturn(DEFAULT_ID);
        when(r.getAnalyzerSlug()).thenReturn("income");
        when(r.getState()).thenReturn(state);
        when(r.getContent()).thenReturn(content);
        when(r.getCreatedBy()).thenReturn(by);
        when(r.getCreatedAt()).thenReturn(OffsetDateTime.now());
        return r;
    }

    private void noRows() {
        when(repo.findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                eq(DEFAULT_ID), anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void fallsBackToPackDefaultWhenNoRows() {
        noRows();
        AnalyzerPromptService s = service();

        assertEquals(packIncomePrompt(), s.effectiveBasePrompt(DEFAULT_ID, "income"));
        AnalyzerPromptService.PromptState st = s.state(DEFAULT_ID, "income");
        assertEquals("pack", st.published().source());
        assertEquals(packIncomePrompt(), st.published().content());
        assertEquals(packIncomePrompt(), st.packDefault());
        assertNull(st.draft());
        assertEquals("income", st.corpusScope());
    }

    @Test
    void publishedOverrideWinsAndDraftIsReported() {
        noRows();
        // Build the mocked rows before opening any when(...).thenReturn(...) chain: evaluating
        // row(), which itself does when(r.getX()).thenReturn(...), as a thenReturn(...) argument
        // would invoke a mock method while the outer stub is still open, and Mockito's global
        // ongoing-stubbing tracker throws UnfinishedStubbingException.
        AnalyzerPromptRevision publishedRow = row(State.PUBLISHED, "CUSTOM", "alice");
        AnalyzerPromptRevision draftRow = row(State.DRAFT, "DRAFT TEXT", "bob");
        when(repo.findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.PUBLISHED))
                .thenReturn(Optional.of(publishedRow));
        when(repo.findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.DRAFT))
                .thenReturn(Optional.of(draftRow));
        AnalyzerPromptService s = service();

        assertEquals("CUSTOM", s.effectiveBasePrompt(DEFAULT_ID, "income"));
        AnalyzerPromptService.PromptState st = s.state(DEFAULT_ID, "income");
        assertEquals("custom", st.published().source());
        assertEquals("alice", st.published().updatedBy());
        assertNotNull(st.draft());
        assertEquals("DRAFT TEXT", st.draft().content());
        assertEquals("bob", st.draft().savedBy());
    }

    @Test
    void revertMarkerRestoresPackButKeepsAttribution() {
        noRows();
        AnalyzerPromptRevision revertRow = row(State.PUBLISHED, null, "admin-api");
        when(repo.findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.PUBLISHED))
                .thenReturn(Optional.of(revertRow));
        AnalyzerPromptService s = service();

        assertEquals(packIncomePrompt(), s.effectiveBasePrompt(DEFAULT_ID, "income"));
        AnalyzerPromptService.PromptState st = s.state(DEFAULT_ID, "income");
        assertEquals("pack", st.published().source());
        assertEquals("admin-api", st.published().updatedBy());
        assertNotNull(st.published().updatedAt());
    }

    @Test
    void withEffectivePromptReturnsSameInstanceOnPackAndCopyOnOverride() {
        noRows();
        AnalyzerConfig income = TestPacks.sample().analyzers().stream()
                .filter(a -> a.slug().equals("income")).findFirst().orElseThrow();
        AnalyzerPromptService s = service();
        assertSame(income, s.withEffectivePrompt(DEFAULT_ID, income));

        AnalyzerPromptRevision publishedRow = row(State.PUBLISHED, "CUSTOM", "alice");
        when(repo.findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.PUBLISHED))
                .thenReturn(Optional.of(publishedRow));
        s.invalidate();
        AnalyzerConfig swapped = s.withEffectivePrompt(DEFAULT_ID, income);
        assertNotSame(income, swapped);
        assertEquals("CUSTOM", swapped.basePrompt());
        assertEquals(income.outputSchema(), swapped.outputSchema());
        assertEquals(income.retrievalTopK(), swapped.retrievalTopK());
        assertEquals(income.corpusScope(), swapped.corpusScope());
    }

    @Test
    void saveDraftReplacesExistingDraftAndInvalidates() {
        noRows();
        AnalyzerPromptService s = service();
        s.effectiveBasePrompt(DEFAULT_ID, "income"); // prime cache

        s.saveDraft(DEFAULT_ID, "income", "NEW DRAFT", "admin-api");

        verify(repo).deleteByBrainIdAndAnalyzerSlugAndState(DEFAULT_ID, "income", State.DRAFT);
        verify(repo).save(argThat(r ->
                DEFAULT_ID.equals(r.getBrainId())
                        && "income".equals(r.getAnalyzerSlug())
                        && r.getState() == State.DRAFT
                        && "NEW DRAFT".equals(r.getContent())
                        && "admin-api".equals(r.getCreatedBy())));
        s.effectiveBasePrompt(DEFAULT_ID, "income");
        verify(repo, times(2)).findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.PUBLISHED);
    }

    @Test
    void discardDraftDeletesOnlyTheDraft() {
        noRows();
        service().discardDraft(DEFAULT_ID, "income");
        verify(repo).deleteByBrainIdAndAnalyzerSlugAndState(DEFAULT_ID, "income", State.DRAFT);
        verify(repo, never()).save(any());
    }

    @Test
    void publishMovesDraftToPublished() {
        noRows();
        AnalyzerPromptRevision draftRow = row(State.DRAFT, "READY", "bob");
        when(repo.findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.DRAFT))
                .thenReturn(Optional.of(draftRow));
        AnalyzerPromptService s = service();

        s.publish(DEFAULT_ID, "income", "admin-api");

        verify(repo).save(argThat(r ->
                r.getState() == State.PUBLISHED && "READY".equals(r.getContent())
                        && "admin-api".equals(r.getCreatedBy())));
        verify(repo).deleteByBrainIdAndAnalyzerSlugAndState(DEFAULT_ID, "income", State.DRAFT);
    }

    @Test
    void publishWithoutDraftThrows() {
        noRows();
        assertThrows(AnalyzerPromptService.NoDraftException.class,
                () -> service().publish(DEFAULT_ID, "income", "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void revertWritesNullMarkerAndLeavesDraft() {
        noRows();
        service().revert(DEFAULT_ID, "income", "admin-api");
        verify(repo).save(argThat(r -> r.getState() == State.PUBLISHED && r.getContent() == null));
        verify(repo, never()).deleteByBrainIdAndAnalyzerSlugAndState(any(), any(), any());
    }

    @Test
    void rejectsUnknownSlugBlankAndOversizedContent() {
        noRows();
        AnalyzerPromptService s = service();
        assertThrows(AnalyzerNotFoundException.class, () -> s.state(DEFAULT_ID, "nope"));
        assertThrows(AnalyzerNotFoundException.class,
                () -> s.saveDraft(DEFAULT_ID, "nope", "x", "admin-api"));
        assertThrows(IllegalArgumentException.class,
                () -> s.saveDraft(DEFAULT_ID, "income", "   ", "admin-api"));
        assertThrows(IllegalArgumentException.class,
                () -> s.saveDraft(DEFAULT_ID, "income",
                        "x".repeat(AnalyzerPromptService.CONTENT_MAX_CHARS + 1), "admin-api"));
    }

    @Test
    void statesListsEveryPackAnalyzerInPackOrder() {
        noRows();
        List<AnalyzerPromptService.PromptState> states = service().states(DEFAULT_ID);
        List<String> expected = TestPacks.sample().analyzers().stream().map(AnalyzerConfig::slug).toList();
        assertEquals(expected, states.stream().map(AnalyzerPromptService.PromptState::slug).toList());
        assertTrue(states.stream().anyMatch(st -> st.slug().equals("income-v2") && st.v2()));
    }

    @Test
    void historyReturnsPublishedRowsOnly() {
        AnalyzerPromptRevision rowB = row(State.PUBLISHED, "B", "x");
        AnalyzerPromptRevision rowY = row(State.PUBLISHED, null, "y");
        when(repo.findTop20ByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.PUBLISHED))
                .thenReturn(List.of(rowB, rowY));
        noRows();
        List<AnalyzerPromptRevision> h = service().history(DEFAULT_ID, "income");
        assertEquals(2, h.size());
        verify(repo, never()).findTop20ByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                DEFAULT_ID, "income", State.DRAFT);
    }
}
