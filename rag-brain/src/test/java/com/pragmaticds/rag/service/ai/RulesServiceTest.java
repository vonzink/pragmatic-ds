package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.domain.RuleRevision;
import com.pragmaticds.rag.pack.TestPacks;
import com.pragmaticds.rag.repository.RuleRevisionRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.pragmaticds.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RulesServiceTest {

    private final RuleRevisionRepository repo = mock(RuleRevisionRepository.class);

    private RulesService service() {
        return new RulesService(repo, TestPacks.registry());
    }

    // ── 1. Falls back to pack defaults when repo is empty ────────────────────

    @Test
    void effectiveFallsBackToPackDefaults() {
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), any()))
                .thenReturn(Optional.empty());
        RulesService s = service();

        assertEquals(TestPacks.sample().hardRules(), s.effectiveHard(DEFAULT_ID));
        assertEquals(TestPacks.sample().guidance(),   s.effectiveGuidance(DEFAULT_ID));

        Map<String, RulesService.RuleState> state = s.state(DEFAULT_ID);
        assertEquals("pack", state.get("rules.hard").source());
        assertEquals("pack", state.get("rules.guidance").source());
    }

    // ── 2. Latest non-null revision overrides the pack ───────────────────────

    @Test
    void latestRevisionOverridesPack() {
        RuleRevision rev = new RuleRevision(DEFAULT_ID, "rules.hard", "CUSTOM HARD", "tester");
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), eq("rules.hard")))
                .thenReturn(Optional.of(rev));
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), eq("rules.guidance")))
                .thenReturn(Optional.empty());
        RulesService s = service();

        assertEquals("CUSTOM HARD", s.effectiveHard(DEFAULT_ID));
        assertEquals("custom", s.state(DEFAULT_ID).get("rules.hard").source());
        assertEquals("pack",   s.state(DEFAULT_ID).get("rules.guidance").source());
    }

    // ── 3. Null-content revision reverts to pack default ─────────────────────

    @Test
    void nullContentRevisionRevertsToPack() {
        // Use a mock so @PrePersist-populated fields are present without a DB
        RuleRevision revert = mock(RuleRevision.class);
        when(revert.getBrainId()).thenReturn(DEFAULT_ID);
        when(revert.getRuleKey()).thenReturn("rules.hard");
        when(revert.getContent()).thenReturn(null);
        when(revert.getCreatedBy()).thenReturn("admin-api");
        when(revert.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), eq("rules.hard")))
                .thenReturn(Optional.of(revert));
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), eq("rules.guidance")))
                .thenReturn(Optional.empty());
        RulesService s = service();

        // effective content falls back to pack
        assertEquals(TestPacks.sample().hardRules(), s.effectiveHard(DEFAULT_ID));

        // source is "pack" but revert revision attribution is preserved
        RulesService.RuleState st = s.state(DEFAULT_ID).get("rules.hard");
        assertEquals("pack", st.source());
        assertEquals("admin-api", st.updatedBy());
        assertNotNull(st.updatedAt(), "revert revision's timestamp should be surfaced");
    }

    // ── 4. save() appends revision and invalidates cache ─────────────────────

    @Test
    void saveAppendsAndInvalidates() {
        // Before save: empty
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), any()))
                .thenReturn(Optional.empty());
        RulesService s = service();
        s.effectiveHard(DEFAULT_ID); // prime cache → 1 findFirst for rules.hard

        // After save the cache is invalidated; next read re-queries
        s.save(DEFAULT_ID, "rules.hard", "X", "admin-api");

        verify(repo).save(argThat(r ->
                DEFAULT_ID.equals(r.getBrainId())
                        && r.getRuleKey().equals("rules.hard")
                        && "X".equals(r.getContent())
                        && "admin-api".equals(r.getCreatedBy())));

        s.effectiveHard(DEFAULT_ID); // must hit repo again
        // prime + post-save re-read = 2 findFirst calls for rules.hard
        verify(repo, times(2))
                .findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.hard");
    }

    // ── 5. Cache suppresses extra repo reads within TTL ──────────────────────

    @Test
    void cachesWithinTtl() {
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), any()))
                .thenReturn(Optional.empty());
        RulesService s = service();

        s.effectiveHard(DEFAULT_ID);
        s.effectiveGuidance(DEFAULT_ID);
        s.effectiveHard(DEFAULT_ID);
        s.effectiveGuidance(DEFAULT_ID);

        // Both keys loaded in the initial snapshot; subsequent calls must not re-read
        verify(repo, times(1))
                .findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.hard");
        verify(repo, times(1))
                .findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.guidance");
    }

    // ── 6. save() rejects unknown key and blank content ──────────────────────

    @Test
    void rejectsUnknownKeyAndBlankContent() {
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), any()))
                .thenReturn(Optional.empty());
        RulesService s = service();

        assertThrows(IllegalArgumentException.class,
                () -> s.save(DEFAULT_ID, "nope", "some content", "admin-api"),
                "unknown key must be rejected");

        assertThrows(IllegalArgumentException.class,
                () -> s.save(DEFAULT_ID, "rules.hard", "  ", "admin-api"),
                "blank content must be rejected");

        verify(repo, never()).save(any());
    }

    // ── 7. revert() appends a null-content revision ──────────────────────────

    @Test
    void revertAppendsNullRevision() {
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(eq(DEFAULT_ID), any()))
                .thenReturn(Optional.empty());
        RulesService s = service();

        s.revert(DEFAULT_ID, "rules.hard", "admin-api");

        verify(repo).save(argThat(r ->
                DEFAULT_ID.equals(r.getBrainId())
                        && r.getRuleKey().equals("rules.hard")
                        && r.getContent() == null
                        && "admin-api".equals(r.getCreatedBy())));
    }

    @Test
    void differentBrainsResolveSeparateRuleRevisions() {
        UUID otherBrainId = UUID.randomUUID();
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.hard"))
                .thenReturn(Optional.of(new RuleRevision(DEFAULT_ID, "rules.hard", "DEFAULT HARD", "tester")));
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(DEFAULT_ID, "rules.guidance"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(otherBrainId, "rules.hard"))
                .thenReturn(Optional.of(new RuleRevision(otherBrainId, "rules.hard", "OTHER HARD", "tester")));
        when(repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(otherBrainId, "rules.guidance"))
                .thenReturn(Optional.empty());
        RulesService s = service();

        assertEquals("DEFAULT HARD", s.effectiveHard(DEFAULT_ID));
        assertEquals("OTHER HARD", s.effectiveHard(otherBrainId));
    }
}
