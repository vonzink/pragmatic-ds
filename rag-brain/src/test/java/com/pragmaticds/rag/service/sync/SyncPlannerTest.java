package com.pragmaticds.rag.service.sync;

import com.pragmaticds.rag.domain.BrainDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPlannerTest {

    private final SyncManifest emptyManifest = SyncManifest.parse(Optional.empty());

    private BrainDocument doc(String fileName, boolean active, String sha) {
        BrainDocument d = new BrainDocument();
        d.setFileName(fileName);
        d.setActive(active);
        d.setContentSha256(sha);
        d.setTitle(fileName);
        return d;
    }

    private Map<String, SyncAction.Type> byFile(List<SyncAction> plan) {
        return plan.stream().collect(java.util.stream.Collectors.toMap(
                SyncAction::fileName, SyncAction::type, (a, b) -> b));
    }

    @Test
    void newFileUploads_missingFileDeactivates_unchangedSkips() {
        BrainDocument unchanged = doc("same.pdf", true, "aaa");
        BrainDocument removed = doc("gone.pdf", true, "bbb");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("new.pdf", "same.pdf"),
                emptyManifest,
                List.of(unchanged, removed),
                Map.of("new.pdf", "ccc", "same.pdf", "aaa"));

        Map<String, SyncAction.Type> types = byFile(plan);
        assertEquals(SyncAction.Type.UPLOAD, types.get("new.pdf"));
        assertEquals(SyncAction.Type.SKIP, types.get("same.pdf"));
        assertEquals(SyncAction.Type.DEACTIVATE, types.get("gone.pdf"));
    }

    @Test
    void changedHashUpdates() {
        BrainDocument stale = doc("guide.pdf", true, "old-hash");
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("guide.pdf"), emptyManifest, List.of(stale),
                Map.of("guide.pdf", "new-hash"));

        assertEquals(SyncAction.Type.UPDATE, plan.get(0).type());
    }

    @Test
    void legacyNullHashSkipsWhenActive() {
        BrainDocument legacy = doc("legacy.pdf", true, null);
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("legacy.pdf"), emptyManifest, List.of(legacy),
                Map.of("legacy.pdf", "whatever"));

        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals("already ingested (no stored hash)", plan.get(0).reason());
    }

    @Test
    void inactiveRowReactivatesWhenHashMatchesOrIsNull() {
        BrainDocument inactive = doc("back.pdf", false, "h1");
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("back.pdf"), emptyManifest, List.of(inactive),
                Map.of("back.pdf", "h1"));
        assertEquals(SyncAction.Type.REACTIVATE, plan.get(0).type());

        BrainDocument inactiveChanged = doc("back2.pdf", false, "h1");
        List<SyncAction> plan2 = SyncPlanner.plan(
                List.of("back2.pdf"), emptyManifest, List.of(inactiveChanged),
                Map.of("back2.pdf", "h2"));
        assertEquals(SyncAction.Type.UPDATE, plan2.get(0).type());
    }

    @Test
    void ingestFalseSkipsAndDeactivatesExistingActive() {
        SyncManifest manifest = SyncManifest.parse(Optional.of("""
                {"files": {"skipme.pdf": {"ingest": false, "reason": "retired"}}}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        BrainDocument existing = doc("skipme.pdf", true, "x");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("skipme.pdf"), manifest, List.of(existing), Map.of("skipme.pdf", "x"));

        // skip the file AND deactivate the now-unwanted active row (plan.mjs parity)
        assertEquals(2, plan.size());
        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals(SyncAction.Type.DEACTIVATE, plan.get(1).type());
    }

    @Test
    void unsupportedExtensionSkips() {
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("image.png"), emptyManifest, List.of(), Map.of("image.png", "h"));
        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals("unsupported extension .png", plan.get(0).reason());
    }

    @Test
    void duplicateRowsPreferTheActiveOne() {
        BrainDocument oldInactive = doc("dup.pdf", false, "h-old");
        BrainDocument current = doc("dup.pdf", true, "h-cur");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("dup.pdf"), emptyManifest, List.of(oldInactive, current),
                Map.of("dup.pdf", "h-cur"));

        assertEquals(SyncAction.Type.SKIP, plan.get(0).type());
        assertEquals("unchanged", plan.get(0).reason());
    }

    @Test
    void onlyActiveUnwantedRowsDeactivate() {
        BrainDocument inactiveGone = doc("gone.pdf", false, "x");
        List<SyncAction> plan = SyncPlanner.plan(
                List.of(), emptyManifest, List.of(inactiveGone), Map.of());
        assertEquals(0, plan.size(), "inactive rows need no deactivation");
    }

    @Test
    void scopeChangeAloneIsAnUpdate() {
        BrainDocument existing = doc("income/starter.md", true, "aaa");
        existing.setAnalyzerScope("assets");   // stored scope differs from derived "income"

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("income/starter.md"), emptyManifest,
                List.of(existing), Map.of("income/starter.md", "aaa"));   // same hash

        assertEquals(SyncAction.Type.UPDATE, byFile(plan).get("income/starter.md"));
        assertEquals("scope changed", plan.get(0).reason());
    }

    @Test
    void matchingScopeAndHashStillSkips() {
        BrainDocument existing = doc("income/starter.md", true, "aaa");
        existing.setAnalyzerScope("income");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("income/starter.md"), emptyManifest,
                List.of(existing), Map.of("income/starter.md", "aaa"));

        assertEquals(SyncAction.Type.SKIP, byFile(plan).get("income/starter.md"));
    }

    @Test
    void inactiveRowWithChangedScopeUpdatesInsteadOfReactivating() {
        BrainDocument existing = doc("credit/notes.md", false, "aaa");
        existing.setAnalyzerScope(null);   // was shared; file now lives under credit/

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("credit/notes.md"), emptyManifest,
                List.of(existing), Map.of("credit/notes.md", "aaa"));

        assertEquals(SyncAction.Type.UPDATE, byFile(plan).get("credit/notes.md"));
    }

    // ---- per-analyzer-scope filtering ----

    @Test
    void scopedPlanOnlyTouchesInScopeFilesAndDocs() {
        BrainDocument incomeGone = doc("income/old.md", true, "h1");
        incomeGone.setAnalyzerScope("income");
        BrainDocument creditKeep = doc("credit/keep.md", true, "h2");
        creditKeep.setAnalyzerScope("credit");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("income/new.md", "credit/other.md", "root.md"),
                emptyManifest,
                List.of(incomeGone, creditKeep),
                Map.of("income/new.md", "a", "credit/other.md", "b", "root.md", "c"),
                "income");

        Map<String, SyncAction.Type> types = byFile(plan);
        assertEquals(2, plan.size(), "out-of-scope files/docs must produce no rows at all");
        assertEquals(SyncAction.Type.UPLOAD, types.get("income/new.md"));
        assertEquals(SyncAction.Type.DEACTIVATE, types.get("income/old.md"));
        assertTrue(creditKeep.isActive(), "out-of-scope active doc untouched");
    }

    @Test
    void sharedFilterMatchesNullScopeDocsAndRootFiles() {
        BrainDocument rootGone = doc("gone.md", true, "h1");        // analyzerScope null
        BrainDocument scopedKeep = doc("income/keep.md", true, "h2");
        scopedKeep.setAnalyzerScope("income");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("root.md", "income/a.md"),
                emptyManifest,
                List.of(rootGone, scopedKeep),
                Map.of("root.md", "a", "income/a.md", "b"),
                "shared");

        Map<String, SyncAction.Type> types = byFile(plan);
        assertEquals(2, plan.size());
        assertEquals(SyncAction.Type.UPLOAD, types.get("root.md"));
        assertEquals(SyncAction.Type.DEACTIVATE, types.get("gone.md"));
    }

    @Test
    void literalSharedStoredScopeIsDeactivateEligibleUnderSharedFilter() {
        // Legacy rows may still carry a literal "shared" analyzerScope
        // (pre-normalization); the shared filter must see them so they can
        // be deactivated — pins matchesScope's literal-"shared" branch.
        BrainDocument legacyShared = doc("legacy-shared.md", true, "h1");
        legacyShared.setAnalyzerScope("shared");

        List<SyncAction> plan = SyncPlanner.plan(
                List.of(), emptyManifest, List.of(legacyShared), Map.of(), "shared");

        assertEquals(1, plan.size());
        assertEquals(SyncAction.Type.DEACTIVATE, plan.get(0).type());
        assertEquals("legacy-shared.md", plan.get(0).fileName());
    }

    @Test
    void sharedFolderFileIsCandidateUnderSharedFilterWithNullResolvedScope() {
        // A shared/ subfolder file resolves to the null (shared) scope and is
        // still an in-scope candidate under scope=shared.
        List<SyncAction> plan = SyncPlanner.plan(
                List.of("shared/x.md"), emptyManifest, List.of(),
                Map.of("shared/x.md", "h"), "shared");

        assertEquals(1, plan.size());
        assertEquals(SyncAction.Type.UPLOAD, plan.get(0).type());
        assertNull(plan.get(0).meta().analyzerScope(),
                "shared/ folder scope must normalize to null so retrieval treats it as shared");
    }

    @Test
    void scopeChangeOfFileStillUpdatesWithinScopedPlan() {
        BrainDocument existing = doc("income/starter.md", true, "aaa");
        existing.setAnalyzerScope("assets");   // stored scope differs from derived "income"

        List<SyncAction> plan = SyncPlanner.plan(
                List.of("income/starter.md"), emptyManifest,
                List.of(existing), Map.of("income/starter.md", "aaa"),   // same hash
                "income");

        assertEquals(1, plan.size());
        assertEquals(SyncAction.Type.UPDATE, plan.get(0).type());
        assertEquals("scope changed", plan.get(0).reason());
    }

    @Test
    void nullFilterPlansTheWholeCorpusExactlyAsBefore() {
        BrainDocument unchanged = doc("same.pdf", true, "aaa");
        BrainDocument removed = doc("gone.pdf", true, "bbb");
        List<String> files = List.of("new.pdf", "same.pdf");
        Map<String, String> hashes = Map.of("new.pdf", "ccc", "same.pdf", "aaa");

        List<SyncAction> legacy = SyncPlanner.plan(
                files, emptyManifest, List.of(unchanged, removed), hashes);
        List<SyncAction> viaNullFilter = SyncPlanner.plan(
                files, emptyManifest, List.of(unchanged, removed), hashes, null);

        assertEquals(legacy, viaNullFilter);
        Map<String, SyncAction.Type> types = byFile(viaNullFilter);
        assertEquals(SyncAction.Type.UPLOAD, types.get("new.pdf"));
        assertEquals(SyncAction.Type.SKIP, types.get("same.pdf"));
        assertEquals(SyncAction.Type.DEACTIVATE, types.get("gone.pdf"));
    }

    // ---- mass-deactivation guard (port of plan.mjs massDeactivationReason) ----

    @Test
    void emptyListingWithActivesRefuses() {
        BrainDocument a = doc("a.md", true, "h");
        BrainDocument b = doc("b.md", true, "h");
        List<BrainDocument> docs = List.of(a, b);
        List<SyncAction> plan = SyncPlanner.plan(List.of(), emptyManifest, docs, Map.of());

        String reason = SyncPlanner.massDeactivationReason(
                List.of(), emptyManifest, docs, plan, null);

        assertNotNull(reason);
        assertTrue(reason.contains("2 active"), "reason must carry the active count: " + reason);
    }

    @Test
    void deactivationRatioAboveThresholdRefuses() {
        List<BrainDocument> docs = new java.util.ArrayList<>();
        Map<String, String> hashes = new java.util.HashMap<>();
        List<String> files = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            docs.add(doc("f" + i + ".md", true, "h" + i));
        }
        for (int i = 0; i < 6; i++) {          // 4 of 10 vanish -> 0.4 > 0.3
            files.add("f" + i + ".md");
            hashes.put("f" + i + ".md", "h" + i);
        }
        List<SyncAction> plan = SyncPlanner.plan(files, emptyManifest, docs, hashes);

        String reason = SyncPlanner.massDeactivationReason(files, emptyManifest, docs, plan, null);

        assertNotNull(reason);
        assertTrue(reason.contains("4") && reason.contains("10"),
                "reason must carry the counts: " + reason);
    }

    @Test
    void deactivationRatioAtThresholdIsAllowed() {
        List<BrainDocument> docs = new java.util.ArrayList<>();
        Map<String, String> hashes = new java.util.HashMap<>();
        List<String> files = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            docs.add(doc("f" + i + ".md", true, "h" + i));
        }
        for (int i = 0; i < 7; i++) {          // 3 of 10 vanish -> 0.3, not > 0.3
            files.add("f" + i + ".md");
            hashes.put("f" + i + ".md", "h" + i);
        }
        List<SyncAction> plan = SyncPlanner.plan(files, emptyManifest, docs, hashes);

        assertNull(SyncPlanner.massDeactivationReason(files, emptyManifest, docs, plan, null));
    }

    @Test
    void noDeactivationsNeverRefuse() {
        // Empty corpus AND empty brain: nothing to deactivate, nothing to protect.
        assertNull(SyncPlanner.massDeactivationReason(
                List.of(), emptyManifest, List.of(),
                SyncPlanner.plan(List.of(), emptyManifest, List.of(), Map.of()), null));
    }

    @Test
    void guardUsesScopedCountsNotWholeCorpus() {
        // Income scope: 1 of 2 active docs vanishing (0.5 > 0.3, refuse). The
        // whole corpus has 12 actives, so the unscoped ratio 1/12 would pass.
        BrainDocument incomeKeep = doc("income/keep.md", true, "hk");
        incomeKeep.setAnalyzerScope("income");
        BrainDocument incomeGone = doc("income/gone.md", true, "hg");
        incomeGone.setAnalyzerScope("income");
        List<BrainDocument> docs = new java.util.ArrayList<>(List.of(incomeKeep, incomeGone));
        Map<String, String> hashes = new java.util.HashMap<>();
        List<String> files = new java.util.ArrayList<>(List.of("income/keep.md"));
        hashes.put("income/keep.md", "hk");
        for (int i = 0; i < 10; i++) {
            BrainDocument credit = doc("credit/c" + i + ".md", true, "h" + i);
            credit.setAnalyzerScope("credit");
            docs.add(credit);
            files.add("credit/c" + i + ".md");
            hashes.put("credit/c" + i + ".md", "h" + i);
        }
        List<SyncAction> plan = SyncPlanner.plan(files, emptyManifest, docs, hashes, "income");

        String reason = SyncPlanner.massDeactivationReason(files, emptyManifest, docs, plan, "income");
        assertNotNull(reason, "scoped ratio 1/2 must trip the guard even though 1/12 would not");
        assertTrue(reason.contains("1 of 2"),
                "reason must carry the scoped counts: " + reason);
    }

    @Test
    void guardScopedEmptyListingBranch() {
        // Corpus still has credit files, but the income scope resolves to zero files
        // while an income-scoped active doc exists -> empty-listing branch, scoped.
        BrainDocument incomeActive = doc("income/only.md", true, "h");
        incomeActive.setAnalyzerScope("income");
        List<String> files = List.of("credit/other.md");
        Map<String, String> hashes = Map.of("credit/other.md", "x");

        List<SyncAction> plan = SyncPlanner.plan(
                files, emptyManifest, List.of(incomeActive), hashes, "income");

        String reason = SyncPlanner.massDeactivationReason(
                files, emptyManifest, List.of(incomeActive), plan, "income");
        assertNotNull(reason);
        assertTrue(reason.contains("0"), "empty-listing branch must be named: " + reason);
    }
}
