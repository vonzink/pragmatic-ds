package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.RuleRevision;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ai.PromptBuilderService;
import com.pragmaticds.rag.service.ai.RulesService;
import com.pragmaticds.rag.service.ai.RulesService.RuleState;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminRulesControllerTest {

    private final RulesService rulesService = mock(RulesService.class);
    private final PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final AdminRulesController controller =
            newController();

    private AdminRulesController newController() {
        when(brainResolver.resolve(any())).thenReturn(
                new Brain(TestBrains.DEFAULT_ID, "mortgage", "Acme Corp"));
        return new AdminRulesController(rulesService, promptBuilder, brainResolver);
    }

    // ── GET /api/ai/admin/rules ──────────────────────────────────────────────

    @Test
    void getStateMapsHardAndGuidanceByShortKey() {
        OffsetDateTime now = OffsetDateTime.now();
        RuleState hardState     = new RuleState("rules.hard",     "No fraud.",   "custom", now, "alice");
        RuleState guidanceState = new RuleState("rules.guidance", "Be helpful.", "pack",   null, null);

        when(rulesService.state(any())).thenReturn(Map.of(
                "rules.hard",     hardState,
                "rules.guidance", guidanceState));

        Map<String, RuleState> body = controller.getState("mortgage");

        verify(brainResolver).resolve("mortgage");
        assertEquals(hardState,     body.get("hard"),     "hard key must be shortened");
        assertEquals(guidanceState, body.get("guidance"), "guidance key must be shortened");
        assertEquals(2, body.size(), "exactly two entries");
    }

    // ── PUT /api/ai/admin/rules/{key} ────────────────────────────────────────

    @Test
    void putHappyPathSavesAndReturnsRefreshedState() {
        OffsetDateTime now = OffsetDateTime.now();
        RuleState savedState    = new RuleState("rules.hard", "Updated.", "custom", now, "admin-api");
        RuleState guidanceState = new RuleState("rules.guidance", "Guidance.", "pack", null, null);

        when(rulesService.state(any())).thenReturn(Map.of(
                "rules.hard",     savedState,
                "rules.guidance", guidanceState));

        Map<String, RuleState> body = controller.putRule("rules.hard",
                "mortgage",
                new AdminRulesController.ContentBody("Updated."));

        verify(brainResolver).resolve("mortgage");
        verify(rulesService).save(TestBrains.DEFAULT_ID, "rules.hard", "Updated.", "admin-api");
        assertEquals(savedState, body.get("hard"));
    }

    @Test
    void putRejectsBlankContent() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.putRule("rules.hard",
                        "mortgage",
                        new AdminRulesController.ContentBody("")));

        assertThrows(IllegalArgumentException.class,
                () -> controller.putRule("rules.guidance",
                        "mortgage",
                        new AdminRulesController.ContentBody(null)));

        verify(rulesService, never()).save(any(), any(), any(), any());
    }

    @Test
    void putRejectsUnknownKey() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.putRule("rules.unknown",
                        "mortgage",
                        new AdminRulesController.ContentBody("Some content.")));

        verify(rulesService, never()).save(any(), any(), any(), any());
    }

    // ── POST /api/ai/admin/rules/{key}/revert ────────────────────────────────

    @Test
    void revertDelegatesToServiceAndReturnsRefreshedState() {
        OffsetDateTime now = OffsetDateTime.now();
        RuleState packState     = new RuleState("rules.hard",     "Pack default.", "pack", now, "admin-api");
        RuleState guidanceState = new RuleState("rules.guidance", "Guidance.",     "pack", null, null);

        when(rulesService.state(any())).thenReturn(Map.of(
                "rules.hard",     packState,
                "rules.guidance", guidanceState));

        Map<String, RuleState> body = controller.revertRule("rules.guidance", "mortgage");

        verify(brainResolver).resolve("mortgage");
        verify(rulesService).revert(TestBrains.DEFAULT_ID, "rules.guidance", "admin-api");
        assertEquals(guidanceState, body.get("guidance"));
    }

    // ── GET /api/ai/admin/rules/{key}/history ────────────────────────────────

    @Test
    void historyMapsRevisionNumberAndRevertedFlag() {
        OffsetDateTime t1 = OffsetDateTime.now().minusDays(2);
        OffsetDateTime t2 = OffsetDateTime.now().minusDays(1);
        OffsetDateTime t3 = OffsetDateTime.now();

        // Simulate 3 revisions returned newest-first from service
        RuleRevision rev3 = makeRevision("rules.hard", "Third content", t3, "carol");
        RuleRevision rev2 = makeRevision("rules.hard", null, t2, "bob");    // revert marker
        RuleRevision rev1 = makeRevision("rules.hard", "First content", t1, "alice");

        when(rulesService.history(TestBrains.DEFAULT_ID, "rules.hard")).thenReturn(List.of(rev3, rev2, rev1));

        List<Map<String, Object>> history = controller.history("rules.hard", "mortgage");

        verify(brainResolver).resolve("mortgage");
        assertEquals(3, history.size());

        // Newest (index 0) gets revision = list.size() = 3
        assertEquals(3,       history.get(0).get("revision"));
        assertEquals(t3,      history.get(0).get("createdAt"));
        assertEquals("carol", history.get(0).get("createdBy"));
        assertEquals(false,   history.get(0).get("reverted"));
        assertEquals("Third content", history.get(0).get("content"));

        // Middle (index 1) is a revert marker
        assertEquals(2,     history.get(1).get("revision"));
        assertEquals(true,  history.get(1).get("reverted"));
        assertNull(history.get(1).get("content"), "revert marker content should be null");

        // Oldest (index 2) gets revision = 1
        assertEquals(1,       history.get(2).get("revision"));
        assertEquals(false,   history.get(2).get("reverted"));
        assertEquals("First content", history.get(2).get("content"));
    }

    // ── GET /api/ai/admin/rules/preview ─────────────────────────────────────

    @Test
    void previewReturnsBuildOutput() {
        when(promptBuilder.build(anyString(), eq(List.of()), any())).thenReturn("<<built prompt>>");

        Map<String, String> body = controller.preview("mortgage");

        verify(brainResolver).resolve("mortgage");
        assertEquals("<<built prompt>>", body.get("prompt"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private RuleRevision makeRevision(String key, String content, OffsetDateTime at, String by) {
        RuleRevision r = new RuleRevision(TestBrains.DEFAULT_ID, key, content, by);
        // inject createdAt via reflection (no public setter)
        try {
            var f = RuleRevision.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(r, at);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }
}
