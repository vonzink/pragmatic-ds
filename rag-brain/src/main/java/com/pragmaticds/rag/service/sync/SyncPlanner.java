package com.pragmaticds.rag.service.sync;

import com.pragmaticds.rag.domain.BrainDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure sync planning (no IO) — port of scripts/s3-ingest/plan.mjs planSync,
 * extended with content-hash UPDATE detection (spec §6). Per corpus file:
 * ingest:false -> SKIP; unsupported extension -> SKIP; no row -> UPLOAD;
 * no active row -> REACTIVATE (or UPDATE if the hash differs); active row ->
 * SKIP when unchanged or hash unknown, UPDATE when the stored hash differs.
 * Finally every active row not wanted by the corpus -> DEACTIVATE.
 *
 * <p>Duplicate fileName rows: prefer the active row; else the last row in
 * input order (mirrors the script's single-entry Map which last-wins).
 *
 * <p>An optional analyzer-scope filter narrows the plan to one scope: only
 * files whose resolved analyzerScope matches the filter become candidates, and
 * only active docs whose stored analyzerScope matches the filter are eligible
 * for DEACTIVATE. Everything out of scope is completely untouched — it does
 * not even produce SKIP rows. The literal filter {@code "shared"} matches the
 * null scope (root files / unscoped docs). A null filter plans the whole
 * corpus exactly as before.
 */
public final class SyncPlanner {

    /** Mirror of DocumentAdminController.ALLOWED_EXTENSIONS. */
    static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    /** Scope filter literal matching resolved-null scopes (root/unscoped). */
    public static final String SHARED_SCOPE = "shared";

    /** Refuse when planned deactivations exceed this share of active docs. */
    static final double MASS_DEACTIVATION_THRESHOLD = 0.3;

    private SyncPlanner() {}

    public static List<SyncAction> plan(List<String> s3Files,
                                        SyncManifest manifest,
                                        List<BrainDocument> brainDocs,
                                        Map<String, String> s3Hashes) {
        return plan(s3Files, manifest, brainDocs, s3Hashes, null);
    }

    public static List<SyncAction> plan(List<String> s3Files,
                                        SyncManifest manifest,
                                        List<BrainDocument> brainDocs,
                                        Map<String, String> s3Hashes,
                                        String scopeFilter) {
        // Duplicate fileNames: prefer the active row; else the last in input order.
        // Deliberately unfiltered — an in-scope file must still find its existing
        // row even when that row's stored scope differs (that IS the UPDATE case).
        Map<String, BrainDocument> byName = latestByFileName(brainDocs);

        List<SyncAction> actions = new ArrayList<>();
        Set<String> wanted = new HashSet<>();

        for (String fileName : s3Files) {
            SyncManifest.Entry meta = manifest.resolve(fileName);
            if (!matchesScope(meta.analyzerScope(), scopeFilter)) {
                continue;   // out of scope: untouched, not even a SKIP row
            }
            if (!meta.ingest()) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.SKIP,
                        meta.reason() != null ? meta.reason() : "ingest:false", null, meta));
                continue;
            }
            String extension = extensionOf(fileName);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.SKIP,
                        "unsupported extension ." + extension, null, meta));
                continue;
            }
            wanted.add(fileName);

            BrainDocument existing = byName.get(fileName);
            String s3Hash = s3Hashes.get(fileName);
            if (existing == null) {
                actions.add(SyncAction.of(fileName, SyncAction.Type.UPLOAD, null, null, meta));
            } else {
                boolean contentChanged = existing.getContentSha256() != null && s3Hash != null
                        && !existing.getContentSha256().equals(s3Hash);
                boolean scopeChanged = !Objects.equals(
                        existing.getAnalyzerScope(), meta.analyzerScope());
                if (contentChanged || scopeChanged) {
                    actions.add(SyncAction.of(fileName, SyncAction.Type.UPDATE,
                            contentChanged ? "content changed" : "scope changed",
                            existing.getId(), meta));
                } else if (existing.isActive()) {
                    actions.add(SyncAction.of(fileName, SyncAction.Type.SKIP,
                            existing.getContentSha256() == null
                                    ? "already ingested (no stored hash)" : "unchanged",
                            existing.getId(), meta));
                } else {
                    actions.add(SyncAction.of(fileName, SyncAction.Type.REACTIVATE,
                            null, existing.getId(), meta));
                }
            }
        }

        for (BrainDocument doc : brainDocs) {
            if (doc.isActive()
                    && matchesScope(doc.getAnalyzerScope(), scopeFilter)
                    && !wanted.contains(doc.getFileName())) {
                actions.add(SyncAction.of(doc.getFileName(), SyncAction.Type.DEACTIVATE,
                        "not in current corpus", doc.getId(), null));
            }
        }
        return actions;
    }

    /**
     * Guard against a wrong bucket/prefix or an accidentally emptied corpus
     * wiping a brain in one run — port of scripts/s3-ingest/plan.mjs
     * massDeactivationReason, evaluated over the same scope the plan used.
     * Returns a human-readable reason when the plan is a suspicious
     * mass-deactivation, else null. Pure, so {@link SyncService} decides to
     * block (and {@code force=true} can override).
     *
     * <p>Refuses when (a) the scope-filtered corpus listing is empty while
     * scope-filtered active docs exist, or (b) planned deactivations exceed
     * {@value #MASS_DEACTIVATION_THRESHOLD} of the scope-filtered active docs.
     * With a null scope this deliberately guards the unfiltered whole-corpus
     * sync too — new protection for the dashboard "Sync now", which the legacy
     * JS pipeline already had.
     */
    public static String massDeactivationReason(List<String> s3Files,
                                                SyncManifest manifest,
                                                List<BrainDocument> brainDocs,
                                                List<SyncAction> actions,
                                                String scopeFilter) {
        long deactivate = actions.stream()
                .filter(a -> a.type() == SyncAction.Type.DEACTIVATE).count();
        if (deactivate == 0) {
            return null;
        }
        long active = brainDocs.stream()
                .filter(d -> d.isActive() && matchesScope(d.getAnalyzerScope(), scopeFilter))
                .count();
        long listed = s3Files.stream()
                .filter(f -> matchesScope(manifest.resolve(f).analyzerScope(), scopeFilter))
                .count();
        String scopeLabel = scopeFilter == null ? "" : " in scope '" + scopeFilter + "'";
        if (listed == 0) {
            return "corpus listing returned 0 files" + scopeLabel + " but the brain has "
                    + active + " active document(s)" + scopeLabel
                    + " — this would deactivate all of them"
                    + " (likely a wrong bucket/prefix or an emptied corpus)";
        }
        if (active > 0 && (double) deactivate / active > MASS_DEACTIVATION_THRESHOLD) {
            return "plan would deactivate " + deactivate + " of " + active
                    + " active document(s)" + scopeLabel
                    + " (> " + Math.round(MASS_DEACTIVATION_THRESHOLD * 100)
                    + "%) — refusing as a likely misconfiguration";
        }
        return null;
    }

    /**
     * Collapses possibly-duplicate {@code fileName} rows (e.g. an old UPDATE
     * left a stale duplicate active row) to one row per file: prefer the
     * active row; else the last row in input order. Shared by {@link #plan}
     * and by {@link SyncService#refreshMetadata} so both pick the exact same
     * document for a given corpus file.
     */
    static Map<String, BrainDocument> latestByFileName(List<BrainDocument> brainDocs) {
        Map<String, BrainDocument> byName = new HashMap<>();
        for (BrainDocument doc : brainDocs) {
            BrainDocument present = byName.get(doc.getFileName());
            byName.put(doc.getFileName(),
                    (present != null && present.isActive() && !doc.isActive()) ? present : doc);
        }
        return byName;
    }

    /**
     * Null filter matches everything; the {@link #SHARED_SCOPE} filter matches
     * the null scope (and a literal "shared" scope — defense for legacy rows
     * stored before {@link SyncManifest#resolve} normalized "shared" to null);
     * otherwise exact match.
     */
    static boolean matchesScope(String resolvedScope, String scopeFilter) {
        if (scopeFilter == null) {
            return true;
        }
        if (SHARED_SCOPE.equals(scopeFilter)) {
            return resolvedScope == null || SHARED_SCOPE.equals(resolvedScope);
        }
        return scopeFilter.equals(resolvedScope);
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.US);
    }
}
