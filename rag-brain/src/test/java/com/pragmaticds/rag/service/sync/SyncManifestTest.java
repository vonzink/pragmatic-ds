package com.pragmaticds.rag.service.sync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncManifestTest {

    private static final String JSON = """
            {
              "defaults": { "sourceName": "Acme KB", "sourceType": "INTERNAL_POLICY" },
              "files": {
                "fha-handbook.pdf": {
                  "title": "FHA Handbook 4000.1",
                  "sourceType": "AGENCY_GUIDELINE",
                  "effectiveDate": "2026-01-01"
                },
                "old-rates.pdf": { "ingest": false, "reason": "stale rates" }
              }
            }""";

    @Test
    void entryMergesFileOverDefaults() {
        SyncManifest manifest = SyncManifest.parse(Optional.of(JSON.getBytes(StandardCharsets.UTF_8)));
        SyncManifest.Entry entry = manifest.resolve("fha-handbook.pdf");

        assertTrue(entry.ingest());
        assertEquals("FHA Handbook 4000.1", entry.title());
        assertEquals("Acme KB", entry.sourceName());           // from defaults
        assertEquals("AGENCY_GUIDELINE", entry.sourceType());  // file overrides default
        assertNull(entry.visibility());                         // undeclared → ingestion decides (frontmatter, else INTERNAL)
        assertEquals("APPROVED", entry.trustLevel());
        assertEquals("2026-01-01", entry.effectiveDate());
        assertNull(entry.documentVersion());
    }

    @Test
    void entryCanSetVisibilityAndTrustFromDefaultsAndFileOverrides() {
        String json = """
                {
                  "defaults": {
                    "sourceName": "KB",
                    "sourceType": "AGENCY_GUIDELINE",
                    "visibility": "INTERNAL",
                    "trustLevel": "REFERENCE"
                  },
                  "files": {
                    "public.pdf": {
                      "visibility": "PUBLIC",
                      "trustLevel": "AUTHORITATIVE"
                    }
                  }
                }""";
        SyncManifest manifest = SyncManifest.parse(Optional.of(json.getBytes(StandardCharsets.UTF_8)));

        SyncManifest.Entry publicEntry = manifest.resolve("public.pdf");
        assertEquals("PUBLIC", publicEntry.visibility());
        assertEquals("AUTHORITATIVE", publicEntry.trustLevel());

        SyncManifest.Entry defaultEntry = manifest.resolve("internal.pdf");
        assertEquals("INTERNAL", defaultEntry.visibility());
        assertEquals("REFERENCE", defaultEntry.trustLevel());
    }

    @Test
    void ingestFalseCarriesReason() {
        SyncManifest manifest = SyncManifest.parse(Optional.of(JSON.getBytes(StandardCharsets.UTF_8)));
        SyncManifest.Entry entry = manifest.resolve("old-rates.pdf");

        assertFalse(entry.ingest());
        assertEquals("stale rates", entry.reason());
    }

    @Test
    void unknownFileDerivesTitleAndHardDefaults() {
        SyncManifest manifest = SyncManifest.parse(Optional.empty());
        SyncManifest.Entry entry = manifest.resolve("va_loan-guide.pdf");

        assertTrue(entry.ingest());
        assertEquals("va loan guide", entry.title());
        assertEquals("Generic Knowledge Base", entry.sourceName());
        assertEquals("AGENCY_GUIDELINE", entry.sourceType());
        assertNull(entry.visibility());
        assertEquals("APPROVED", entry.trustLevel());
    }

    @Test
    void malformedManifestActsAsEmpty() {
        SyncManifest manifest = SyncManifest.parse(Optional.of("not-json".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Generic Knowledge Base", manifest.resolve("x.pdf").sourceName());
    }

    @Test
    void scopeDerivedFromSubfolderAndTitleUsesBasename() {
        SyncManifest manifest = SyncManifest.parse(Optional.empty());
        SyncManifest.Entry entry = manifest.resolve("income/b3-3.1_starter-notes.md");

        assertEquals("income", entry.analyzerScope());
        // title derives from the BASENAME, not the full path
        assertEquals("b3 3.1 starter notes", entry.title());
    }

    @Test
    void topLevelFileHasNullScope() {
        SyncManifest manifest = SyncManifest.parse(Optional.empty());
        assertNull(manifest.resolve("fha-handbook.pdf").analyzerScope());
    }

    @Test
    void manifestEntryScopeOverridesSubfolder() {
        SyncManifest manifest = SyncManifest.parse(Optional.of("""
                {"files": {"income/misfiled.md": {"analyzerScope": "assets"}}}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("assets", manifest.resolve("income/misfiled.md").analyzerScope());
    }

    @Test
    void defaultsScopeAppliesWhenNoSubfolderOrEntry() {
        SyncManifest manifest = SyncManifest.parse(Optional.of("""
                {"defaults": {"analyzerScope": "credit"}}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("credit", manifest.resolve("flat-file.md").analyzerScope());
    }

    @Test
    void subfolderScopeBeatsManifestDefaults() {
        SyncManifest manifest = SyncManifest.parse(Optional.of("""
                {"defaults": {"analyzerScope": "credit"}}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("income", manifest.resolve("income/foo.md").analyzerScope());
    }

    @Test
    void sharedFolderScopeNormalizesToNull() {
        // Retrieval treats only analyzer_scope IS NULL as shared; a literal
        // "shared" row would ground NO analyzer. resolve() must normalize it.
        SyncManifest manifest = SyncManifest.parse(Optional.empty());
        assertNull(manifest.resolve("shared/doc.md").analyzerScope());
    }

    @Test
    void explicitSharedScopeNormalizesToNullFromEntryAndDefaults() {
        SyncManifest perFile = SyncManifest.parse(Optional.of("""
                {"files": {"doc.md": {"analyzerScope": "shared"}}}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(perFile.resolve("doc.md").analyzerScope());

        SyncManifest viaDefaults = SyncManifest.parse(Optional.of("""
                {"defaults": {"analyzerScope": "shared"}}"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(viaDefaults.resolve("flat-file.md").analyzerScope());
    }

    @Test
    void nestedSubfolderUsesFirstSegmentAsScope() {
        SyncManifest manifest = SyncManifest.parse(Optional.empty());
        SyncManifest.Entry entry = manifest.resolve("credit/reports/tri-merge.md");
        assertEquals("credit", entry.analyzerScope());
        assertEquals("tri merge", entry.title());
    }
}
