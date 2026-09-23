package com.pragmaticds.rag.service.sync;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.ingestion.DocumentIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.pragmaticds.rag.TestBrains;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncServiceTest {

    private CorpusSource corpusSource;
    private CorpusSourceFactory corpusSourceFactory;
    private BrainRepository brainRepository;
    private DocumentIngestionService ingestionService;
    private BrainDocumentRepository documentRepository;
    private SyncService syncService;

    /** A minimal manifest JSON: defaults block, no per-file overrides. */
    private static final Optional<byte[]> EMPTY_MANIFEST = Optional.of(
            """
            {"defaults":{"sourceName":"Acme","sourceType":"AGENCY_GUIDELINE"},"files":{}}
            """.getBytes());

    @BeforeEach
    void setUp() {
        corpusSource = mock(CorpusSource.class);
        corpusSourceFactory = mock(CorpusSourceFactory.class);
        brainRepository = mock(BrainRepository.class);
        ingestionService = mock(DocumentIngestionService.class);
        documentRepository = mock(BrainDocumentRepository.class);

        // Wire factory to return the mock corpusSource for any brain
        when(corpusSourceFactory.forBrain(any(Brain.class))).thenReturn(corpusSource);
        // Wire repository to return a default brain for any id
        Brain defaultBrain = new Brain(TestBrains.DEFAULT_ID, "default", "Default Brain");
        defaultBrain.setSourceType("s3");
        defaultBrain.setS3Bucket("test-bucket");
        defaultBrain.setS3Prefix("prefix/");
        defaultBrain.setS3Region("us-west-1");
        when(brainRepository.findById(any(UUID.class))).thenReturn(Optional.of(defaultBrain));

        syncService = new SyncService(corpusSourceFactory, brainRepository, ingestionService, documentRepository);
    }

    // -------------------------------------------------------------------------
    // Case 1: dry-run executes nothing
    // -------------------------------------------------------------------------

    @Test
    void dryRunExecutesNothing() {
        byte[] pdfBytes = "PDF".getBytes();
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("policy.pdf"));
        when(corpusSource.fetch("policy.pdf")).thenReturn(pdfBytes);

        BrainDocument old = new BrainDocument();
        old.setFileName("old.pdf");
        old.setActive(true);
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(old));

        SyncReport report = syncService.sync(true, TestBrains.DEFAULT_ID);

        assertTrue(report.dryRun());
        report.results().forEach(r -> assertFalse(r.executed()));
        verifyNoInteractions(ingestionService);
        verify(documentRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Case 2: UPLOAD calls ingest with manifest metadata
    // -------------------------------------------------------------------------

    @Test
    void uploadIngestsWithManifestMetadata() {
        byte[] pdfBytes = "PDF-bytes".getBytes();
        Optional<byte[]> manifest = Optional.of("""
                {
                  "defaults":{"sourceName":"Acme","sourceType":"AGENCY_GUIDELINE"},
                  "files":{
                    "policy.pdf":{
                      "ingest":true,
                      "title":"Lending Policy",
                      "sourceName":"Acme Internal",
                      "sourceType":"INTERNAL_POLICY",
                      "effectiveDate":"2024-01-01"
                    }
                  }
                }
                """.getBytes());

        when(corpusSource.fetchManifest()).thenReturn(manifest);
        when(corpusSource.listFiles()).thenReturn(List.of("policy.pdf"));
        when(corpusSource.fetch("policy.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findByBrainId(any())).thenReturn(List.of());

        BrainDocument saved = new BrainDocument();
        saved.setFileName("policy.pdf");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID);

        verify(ingestionService).ingest(
                eq("policy.pdf"),
                eq(pdfBytes),
                eq("Lending Policy"),
                eq("Acme Internal"),
                eq(SourceType.INTERNAL_POLICY),
                // Manifest declares no visibility: sync must pass null through so
                // ingestion can honor the doc's own frontmatter (else INTERNAL).
                isNull(),
                eq(SourceTrustLevel.APPROVED),
                isNull(),
                eq(LocalDate.of(2024, 1, 1)),
                isNull(),
                eq(TestBrains.DEFAULT_ID),
                isNull());

        assertEquals(1, report.results().size());
        assertTrue(report.results().get(0).succeeded());
    }

    // -------------------------------------------------------------------------
    // Case 3: UPDATE → ingest succeeds → old doc deactivated AFTER ingest (InOrder)
    // -------------------------------------------------------------------------

    @Test
    void updateIngestsNewVersionThenDeactivatesOldOnSuccess() {
        byte[] pdfBytes = "PDF-v2".getBytes();

        BrainDocument stale = new BrainDocument();
        stale.setFileName("guide.pdf");
        stale.setActive(true);
        stale.setContentSha256("aaaaaa");   // differs from Sha256.hex(pdfBytes) → UPDATE

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.pdf"));
        when(corpusSource.fetch("guide.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(stale));

        // Replacement is a distinct object from stale; identity comparison governs (both ids null)
        BrainDocument replacement = new BrainDocument();
        replacement.setFileName("guide.pdf");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(replacement);
        when(documentRepository.findByBrainIdAndActiveTrue(any())).thenReturn(List.of(stale));

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID);

        InOrder order = inOrder(ingestionService, documentRepository);
        order.verify(ingestionService).ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        order.verify(documentRepository).save(stale);

        assertFalse(stale.isActive());
        assertEquals(1, report.results().stream().filter(SyncReport.Result::succeeded).count());
    }

    // -------------------------------------------------------------------------
    // Case 4: UPDATE ingest throws → old doc stays active; next action still runs
    // -------------------------------------------------------------------------

    @Test
    void updateFailureLeavesOldDocumentActive() {
        byte[] v2Bytes = "PDF-v2".getBytes();
        byte[] uploadBytes = "NEW-DOC".getBytes();

        BrainDocument stale = new BrainDocument();
        stale.setFileName("guide.pdf");
        stale.setActive(true);
        stale.setContentSha256("oldhash");

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.pdf", "new.pdf"));
        when(corpusSource.fetch("guide.pdf")).thenReturn(v2Bytes);
        when(corpusSource.fetch("new.pdf")).thenReturn(uploadBytes);
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(stale));

        when(ingestionService.ingest(
                eq("guide.pdf"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("embedding-timeout"));

        BrainDocument newDoc = new BrainDocument();
        newDoc.setFileName("new.pdf");
        when(ingestionService.ingest(
                eq("new.pdf"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(newDoc);

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID);

        // findByBrainIdAndActiveTrue never called; stale never saved
        verify(documentRepository, never()).findByBrainIdAndActiveTrue(any());
        verify(documentRepository, never()).save(stale);
        assertTrue(stale.isActive());

        SyncReport.Result updateResult = report.results().stream()
                .filter(r -> r.fileName().equals("guide.pdf")).findFirst().orElseThrow();
        assertFalse(updateResult.succeeded());
        assertNotNull(updateResult.error());

        // Per-file isolation: new.pdf UPLOAD still ran
        SyncReport.Result uploadResult = report.results().stream()
                .filter(r -> r.fileName().equals("new.pdf")).findFirst().orElseThrow();
        assertTrue(uploadResult.succeeded());
        assertTrue(uploadResult.executed());
    }

    // -------------------------------------------------------------------------
    // Case 5: REACTIVATE and DEACTIVATE planned correctly (dry-run level check)
    // -------------------------------------------------------------------------

    @Test
    void reactivateAndDeactivateTogglesActive() {
        BrainDocument inactive = new BrainDocument();
        inactive.setFileName("old.pdf");
        inactive.setActive(false);
        ReflectionTestUtils.setField(inactive, "id", UUID.randomUUID());

        BrainDocument active = new BrainDocument();
        active.setFileName("gone.pdf");
        active.setActive(true);
        ReflectionTestUtils.setField(active, "id", UUID.randomUUID());

        when(corpusSource.fetchManifest()).thenReturn(Optional.empty());
        when(corpusSource.listFiles()).thenReturn(List.of("old.pdf"));
        when(corpusSource.fetch("old.pdf")).thenReturn("PDF".getBytes());
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(inactive, active));
        when(documentRepository.findById(inactive.getId())).thenReturn(Optional.of(inactive));
        when(documentRepository.findById(active.getId())).thenReturn(Optional.of(active));

        // This fixture deactivates 1 of 1 actives (100%), which the
        // mass-deactivation guard now refuses — force past it; the guard
        // itself is covered by the dedicated guard tests below.
        syncService.sync(false, TestBrains.DEFAULT_ID, null, true);

        assertTrue(inactive.isActive(), "REACTIVATE must set active=true");
        assertFalse(active.isActive(), "DEACTIVATE must set active=false");
        verify(documentRepository).save(inactive);
        verify(documentRepository).save(active);
    }

    // -------------------------------------------------------------------------
    // Case 6: UPDATE deactivates ALL stale duplicate active rows (hygiene)
    // -------------------------------------------------------------------------

    @Test
    void updateDeactivatesStaleDuplicateActives() {
        byte[] pdfBytes = "PDF-v3".getBytes();

        BrainDocument stale1 = new BrainDocument();
        stale1.setFileName("guide.pdf");
        stale1.setActive(true);
        stale1.setContentSha256("hash1");

        BrainDocument stale2 = new BrainDocument();
        stale2.setFileName("guide.pdf");
        stale2.setActive(true);
        stale2.setContentSha256("hash1");

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.pdf"));
        when(corpusSource.fetch("guide.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(stale1, stale2));

        BrainDocument replacement = new BrainDocument();
        replacement.setFileName("guide.pdf");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(replacement);
        when(documentRepository.findByBrainIdAndActiveTrue(any())).thenReturn(List.of(stale1, stale2));

        syncService.sync(false, TestBrains.DEFAULT_ID);

        assertFalse(stale1.isActive(), "stale1 must be deactivated");
        assertFalse(stale2.isActive(), "stale2 must be deactivated");
        verify(documentRepository, times(2)).save(argThat(d ->
                d.getFileName().equals("guide.pdf") && !d.isActive()));
    }

    @Test
    void uploadIngestsWithManifestVisibilityAndTrust() {
        byte[] pdfBytes = "PDF-bytes".getBytes();
        Optional<byte[]> manifest = Optional.of("""
                {
                  "defaults":{
                    "sourceName":"Acme",
                    "sourceType":"AGENCY_GUIDELINE",
                    "visibility":"INTERNAL",
                    "trustLevel":"REFERENCE"
                  },
                  "files":{
                    "public-policy.pdf":{
                      "title":"Public Policy",
                      "visibility":"PUBLIC",
                      "trustLevel":"AUTHORITATIVE"
                    }
                  }
                }
                """.getBytes());

        when(corpusSource.fetchManifest()).thenReturn(manifest);
        when(corpusSource.listFiles()).thenReturn(List.of("public-policy.pdf"));
        when(corpusSource.fetch("public-policy.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findByBrainId(any())).thenReturn(List.of());

        BrainDocument saved = new BrainDocument();
        saved.setFileName("public-policy.pdf");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);

        syncService.sync(false, TestBrains.DEFAULT_ID);

        verify(ingestionService).ingest(
                eq("public-policy.pdf"),
                eq(pdfBytes),
                eq("Public Policy"),
                eq("Acme"),
                eq(SourceType.AGENCY_GUIDELINE),
                eq(SourceVisibility.PUBLIC),
                eq(SourceTrustLevel.AUTHORITATIVE),
                isNull(),
                isNull(),
                isNull(),
                eq(TestBrains.DEFAULT_ID),
                isNull());
    }

    // -------------------------------------------------------------------------
    // Mass-deactivation guard + scope filter (corpus-onboarding part 1)
    // -------------------------------------------------------------------------

    /** An active doc with a persisted id, so DEACTIVATE can round-trip findById. */
    private BrainDocument activeDoc(String fileName) {
        BrainDocument d = new BrainDocument();
        d.setFileName(fileName);
        d.setActive(true);
        d.setContentSha256("h-" + fileName);
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        return d;
    }

    @Test
    void guardRefusalExecutesNothingAndReportsReason() {
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of());   // empty listing + actives
        BrainDocument active = activeDoc("gone.pdf");
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(active));

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID, null, false);

        assertTrue(report.refused());
        assertNotNull(report.refusedReason());
        report.results().forEach(r -> assertFalse(r.executed()));
        assertTrue(active.isActive(), "refused sync must not deactivate anything");
        verifyNoInteractions(ingestionService);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void forceExecutesDespiteTrippedGuard() {
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of());
        BrainDocument active = activeDoc("gone.pdf");
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(active));
        when(documentRepository.findById(active.getId())).thenReturn(Optional.of(active));

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID, null, true);

        assertFalse(report.refused());
        assertNotNull(report.refusedReason(),
                "guard reason must SURVIVE a force override — audit trail of what was overridden");
        assertFalse(active.isActive(), "force=true must execute the deactivation");
        verify(documentRepository).save(active);
    }

    @Test
    void unforcedSyncExecutesDeactivationBelowGuardThreshold() {
        // 1 deactivation of 4 actives (0.25 <= 0.3): the guard stays silent
        // and an UNFORCED sync must actually execute the DEACTIVATE.
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("a.md", "b.md", "c.md"));
        java.util.List<BrainDocument> docs = new java.util.ArrayList<>();
        for (String fileName : List.of("a.md", "b.md", "c.md")) {
            byte[] bytes = fileName.getBytes();
            when(corpusSource.fetch(fileName)).thenReturn(bytes);
            BrainDocument kept = activeDoc(fileName);
            kept.setContentSha256(Sha256.hex(bytes));   // unchanged -> SKIP
            docs.add(kept);
        }
        BrainDocument gone = activeDoc("gone.md");
        docs.add(gone);
        when(documentRepository.findByBrainId(any())).thenReturn(docs);
        when(documentRepository.findById(gone.getId())).thenReturn(Optional.of(gone));

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID, null, false);

        assertFalse(report.refused());
        assertNull(report.refusedReason(), "guard did not trip: no reason to report");
        assertFalse(gone.isActive(), "unforced sync must execute the below-threshold DEACTIVATE");
        verify(documentRepository).save(gone);
        SyncReport.Result deactivation = report.results().stream()
                .filter(r -> r.fileName().equals("gone.md")).findFirst().orElseThrow();
        assertTrue(deactivation.executed());
        assertTrue(deactivation.succeeded());
        verifyNoInteractions(ingestionService);
    }

    @Test
    void dryRunComputesGuardWithoutExecuting() {
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of());
        BrainDocument active = activeDoc("gone.pdf");
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(active));

        SyncReport report = syncService.sync(true, TestBrains.DEFAULT_ID, null, false);

        assertTrue(report.dryRun());
        assertTrue(report.refused(), "dry-run must still warn about a tripped guard");
        assertNotNull(report.refusedReason());
        report.results().forEach(r -> assertFalse(r.executed()));
        verifyNoInteractions(ingestionService);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void legacyTwoArgSyncStaysUnrefusedWhenGuardNotTripped() {
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("policy.pdf"));
        when(corpusSource.fetch("policy.pdf")).thenReturn("PDF".getBytes());
        when(documentRepository.findByBrainId(any())).thenReturn(List.of());
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new BrainDocument());

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID);

        assertFalse(report.refused());
        assertNull(report.refusedReason());
        assertTrue(report.results().get(0).executed());
    }

    @Test
    void scopeFilterLimitsPlanAndExecution() {
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("income/a.md", "credit/b.md"));
        when(corpusSource.fetch("income/a.md")).thenReturn("A".getBytes());
        when(corpusSource.fetch("credit/b.md")).thenReturn("B".getBytes());
        when(documentRepository.findByBrainId(any())).thenReturn(List.of());
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new BrainDocument());

        SyncReport report = syncService.sync(false, TestBrains.DEFAULT_ID, "income", false);

        assertEquals(1, report.results().size(), "out-of-scope file must not even appear");
        assertEquals("income/a.md", report.results().get(0).fileName());
        verify(ingestionService).ingest(
                eq("income/a.md"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), eq(TestBrains.DEFAULT_ID), eq("income"));
        verify(ingestionService, never()).ingest(
                eq("credit/b.md"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void invalidScopeIsRejectedBeforeAnyWork() {
        assertThrows(IllegalArgumentException.class,
                () -> syncService.sync(false, TestBrains.DEFAULT_ID, "INVALID!", false));
        verifyNoInteractions(corpusSource, ingestionService);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void scopedUploadPassesAnalyzerScopeToIngestion() {
        byte[] bytes = "seed".getBytes();
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("income/starter.md"));
        when(corpusSource.fetch("income/starter.md")).thenReturn(bytes);
        when(documentRepository.findByBrainId(any())).thenReturn(List.of());
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new BrainDocument());

        syncService.sync(false, TestBrains.DEFAULT_ID);

        verify(ingestionService).ingest(
                eq("income/starter.md"), eq(bytes),
                eq("starter"),                       // title from basename (Task 1)
                any(), any(), any(), any(), isNull(), isNull(), isNull(),
                eq(TestBrains.DEFAULT_ID),
                eq("income"));                       // scope from subfolder
    }

    // -------------------------------------------------------------------------
    // Metadata-only refresh (refresh-metadata): backfills/corrects
    // external_doc_id from front-matter WITHOUT ever re-ingesting, re-chunking,
    // or re-embedding. See SyncService.refreshMetadata javadoc for why this
    // exists (external_doc_id was added by V32, after prod was last synced,
    // and every other path either SKIPs an unchanged file or destroys chunk
    // history by re-ingesting).
    // -------------------------------------------------------------------------

    private static final String FRONT_MATTER_TEMPLATE = "---\ndocument_id: %s\n---\nbody\n";

    private static byte[] withDocId(String docId) {
        return String.format(FRONT_MATTER_TEMPLATE, docId).getBytes();
    }

    @Test
    void refreshMetadataPopulatesNullExternalDocId() {
        // The production case: 95 mortgage-brain docs ingested before V32 all
        // have external_doc_id = NULL.
        BrainDocument doc = new BrainDocument();
        doc.setFileName("guide.md");
        doc.setExternalDocId(null);
        ReflectionTestUtils.setField(doc, "id", UUID.randomUUID());

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.md"));
        when(corpusSource.fetch("guide.md")).thenReturn(withDocId("fnma_sg_income"));
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(doc));

        MetadataRefreshReport report = syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, null);

        assertEquals(MetadataRefreshReport.Action.UPDATED, report.results().get(0).action());
        assertEquals("fnma_sg_income", report.results().get(0).newExternalDocId());
        assertNull(report.results().get(0).previousExternalDocId());
        assertEquals("fnma_sg_income", doc.getExternalDocId());
        verify(documentRepository).save(doc);
        assertEquals(Map.of("updated", 1), report.summary());
        assertFalse(report.dryRun());
    }

    @Test
    void refreshMetadataLeavesAlreadyCorrectIdUnchangedIdempotent() {
        BrainDocument doc = new BrainDocument();
        doc.setFileName("guide.md");
        doc.setExternalDocId("fnma_sg_income");
        ReflectionTestUtils.setField(doc, "id", UUID.randomUUID());

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.md"));
        when(corpusSource.fetch("guide.md")).thenReturn(withDocId("fnma_sg_income"));
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(doc));

        MetadataRefreshReport report = syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, null);

        assertEquals(MetadataRefreshReport.Action.UNCHANGED, report.results().get(0).action());
        assertEquals("fnma_sg_income", doc.getExternalDocId());
        verify(documentRepository, never()).save(any());
        assertEquals(Map.of("unchanged", 1), report.summary());
    }

    @Test
    void refreshMetadataUpdatesADriftedId() {
        BrainDocument doc = new BrainDocument();
        doc.setFileName("guide.md");
        doc.setExternalDocId("old_id");
        ReflectionTestUtils.setField(doc, "id", UUID.randomUUID());

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.md"));
        when(corpusSource.fetch("guide.md")).thenReturn(withDocId("new_id"));
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(doc));

        MetadataRefreshReport report = syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, null);

        assertEquals(MetadataRefreshReport.Action.UPDATED, report.results().get(0).action());
        assertEquals("old_id", report.results().get(0).previousExternalDocId());
        assertEquals("new_id", report.results().get(0).newExternalDocId());
        assertEquals("new_id", doc.getExternalDocId());
        verify(documentRepository).save(doc);
    }

    @Test
    void refreshMetadataReportsNoFrontmatterIdAndLeavesDocumentAlone() {
        BrainDocument doc = new BrainDocument();
        doc.setFileName("guide.md");
        doc.setExternalDocId(null);
        ReflectionTestUtils.setField(doc, "id", UUID.randomUUID());

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.md"));
        // No document_id key in the front matter at all.
        when(corpusSource.fetch("guide.md")).thenReturn("---\ntitle: Guide\n---\nbody\n".getBytes());
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(doc));

        MetadataRefreshReport report = syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, null);

        assertEquals(MetadataRefreshReport.Action.NO_FRONTMATTER_ID, report.results().get(0).action());
        assertNull(report.results().get(0).newExternalDocId());
        assertNull(doc.getExternalDocId(), "must not write null over null, or anything else");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void refreshMetadataReportsNoDocumentWhenS3FileHasNoMatchingRow() {
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("orphan.md"));
        when(documentRepository.findByBrainId(any())).thenReturn(List.of());

        MetadataRefreshReport report = syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, null);

        assertEquals(MetadataRefreshReport.Action.NO_DOCUMENT, report.results().get(0).action());
        // No document row means nothing to parse against — bytes must not even be fetched.
        verify(corpusSource, never()).fetch("orphan.md");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void refreshMetadataDryRunWritesNothing() {
        BrainDocument doc = new BrainDocument();
        doc.setFileName("guide.md");
        doc.setExternalDocId(null);
        ReflectionTestUtils.setField(doc, "id", UUID.randomUUID());

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.md"));
        when(corpusSource.fetch("guide.md")).thenReturn(withDocId("fnma_sg_income"));
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(doc));

        MetadataRefreshReport report = syncService.refreshMetadata(true, TestBrains.DEFAULT_ID, null);

        // The plan still reports what WOULD change...
        assertEquals(MetadataRefreshReport.Action.UPDATED, report.results().get(0).action());
        assertEquals("fnma_sg_income", report.results().get(0).newExternalDocId());
        assertTrue(report.dryRun());
        // ...but nothing is actually written.
        assertNull(doc.getExternalDocId(), "dryRun must not mutate the entity");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void refreshMetadataScopeFilterRestrictsConsideredDocuments() {
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("income/a.md", "credit/b.md"));
        when(corpusSource.fetch("income/a.md")).thenReturn(withDocId("income_a"));

        BrainDocument inScope = new BrainDocument();
        inScope.setFileName("income/a.md");
        BrainDocument outOfScope = new BrainDocument();
        outOfScope.setFileName("credit/b.md");
        when(documentRepository.findByBrainId(any())).thenReturn(List.of(inScope, outOfScope));

        MetadataRefreshReport report = syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, "income");

        assertEquals(1, report.results().size(), "out-of-scope file must not even appear");
        assertEquals("income/a.md", report.results().get(0).fileName());
        verify(corpusSource, never()).fetch("credit/b.md");
    }

    @Test
    void refreshMetadataRejectsInvalidScope() {
        assertThrows(IllegalArgumentException.class,
                () -> syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, "INVALID!"));
        verifyNoInteractions(corpusSource);
    }

    /**
     * The constraint that matters most: refreshMetadata must never reach the
     * chunk/embedding machinery. SyncService only has one door into that
     * machinery — DocumentIngestionService.ingest()/reindex() (which alone own
     * the ChunkRepository and EmbeddingService calls; see
     * DocumentIngestionService.prepareChunks/persistChunks). Proving
     * ingestionService has zero interactions here proves neither door was
     * opened, for every outcome (UPDATED/UNCHANGED/NO_DOCUMENT/NO_FRONTMATTER_ID)
     * in one run.
     */
    @Test
    void refreshMetadataNeverTouchesIngestionChunkingOrEmbedding() {
        BrainDocument updated = new BrainDocument();
        updated.setFileName("updated.md");
        updated.setExternalDocId("old");
        BrainDocument unchanged = new BrainDocument();
        unchanged.setFileName("unchanged.md");
        unchanged.setExternalDocId("same");
        BrainDocument noFrontmatter = new BrainDocument();
        noFrontmatter.setFileName("no-frontmatter.md");

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(
                List.of("updated.md", "unchanged.md", "no-frontmatter.md", "orphan.md"));
        when(corpusSource.fetch("updated.md")).thenReturn(withDocId("new"));
        when(corpusSource.fetch("unchanged.md")).thenReturn(withDocId("same"));
        when(corpusSource.fetch("no-frontmatter.md")).thenReturn("no frontmatter here".getBytes());
        when(documentRepository.findByBrainId(any()))
                .thenReturn(List.of(updated, unchanged, noFrontmatter));

        syncService.refreshMetadata(false, TestBrains.DEFAULT_ID, null);

        verifyNoInteractions(ingestionService);
    }
}
