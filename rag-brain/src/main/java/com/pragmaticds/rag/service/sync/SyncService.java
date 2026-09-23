package com.pragmaticds.rag.service.sync;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.ingestion.DocumentIngestionService;
import com.pragmaticds.rag.service.ingestion.FrontmatterDocId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * In-app port of scripts/s3-ingest/sync.mjs: list corpus + manifest, hash,
 * plan, execute through the ingestion pipeline. Per-file failures are
 * collected, never abort the batch, and an UPDATE deactivates the previous
 * version only after the replacement ingested successfully (spec §10).
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** Valid scope filters: an analyzer slug or the literal "shared". */
    private static final Pattern SCOPE_PATTERN = Pattern.compile("[a-z0-9-]+");

    private final CorpusSourceFactory corpusSourceFactory;
    private final BrainRepository brainRepository;
    private final DocumentIngestionService ingestionService;
    private final BrainDocumentRepository documentRepository;

    public SyncService(CorpusSourceFactory corpusSourceFactory,
                       BrainRepository brainRepository,
                       DocumentIngestionService ingestionService,
                       BrainDocumentRepository documentRepository) {
        this.corpusSourceFactory = corpusSourceFactory;
        this.brainRepository = brainRepository;
        this.ingestionService = ingestionService;
        this.documentRepository = documentRepository;
    }

    /** Whole-corpus sync — no scope filter, guard not overridable. */
    public SyncReport sync(boolean dryRun, UUID brainId) {
        return sync(dryRun, brainId, null, false);
    }

    /**
     * Sync the corpus into the brain, optionally narrowed to one analyzer
     * scope ({@code scope} = an analyzer slug, or {@code "shared"} for
     * unscoped/root files; null = whole corpus). Out-of-scope files and docs
     * are completely untouched.
     *
     * <p>The mass-deactivation guard ({@link SyncPlanner#massDeactivationReason})
     * refuses to execute a plan that would deactivate a suspicious share of the
     * brain — including on the unfiltered whole-corpus sync, which is deliberate
     * new protection for the dashboard "Sync now". {@code force=true} overrides
     * the guard. A refused run executes nothing and reports
     * {@code refused=true} with the reason; dry runs still compute the guard so
     * the report warns ahead of a real run.
     */
    public SyncReport sync(boolean dryRun, UUID brainId, String scope, boolean force) {
        validateScope(scope);
        Brain brain = brainRepository.findById(brainId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));
        CorpusSource corpusSource = corpusSourceFactory.forBrain(brain);

        SyncManifest manifest = SyncManifest.parse(corpusSource.fetchManifest());
        List<String> s3Files = corpusSource.listFiles();
        List<BrainDocument> brainDocs = documentRepository.findByBrainId(brainId);

        // Fetch once; reuse bytes for hashing and (later) ingestion.
        Map<String, byte[]> bytesByFile = new HashMap<>();
        Map<String, String> hashes = new HashMap<>();
        for (String fileName : s3Files) {
            byte[] bytes = corpusSource.fetch(fileName);
            bytesByFile.put(fileName, bytes);
            hashes.put(fileName, Sha256.hex(bytes));
        }

        List<SyncAction> plan = SyncPlanner.plan(s3Files, manifest, brainDocs, hashes, scope);

        String guardReason = SyncPlanner.massDeactivationReason(
                s3Files, manifest, brainDocs, plan, scope);
        boolean refused = guardReason != null && !force;
        if (guardReason != null && force) {
            log.warn("Sync mass-deactivation guard overridden by force for brain {}: {}",
                    brainId, guardReason);
        }

        Map<String, Integer> summary = new LinkedHashMap<>();
        for (SyncAction action : plan) {
            summary.merge(action.type().name().toLowerCase(Locale.US), 1, Integer::sum);
        }

        List<SyncReport.Result> results = new ArrayList<>(plan.size());
        for (SyncAction action : plan) {
            if (dryRun || refused || action.type() == SyncAction.Type.SKIP) {
                results.add(new SyncReport.Result(action.fileName(), action.type().name(),
                        action.reason(), false, true, null));
                continue;
            }
            try {
                execute(action, bytesByFile, brainId);
                results.add(new SyncReport.Result(action.fileName(), action.type().name(),
                        action.reason(), true, true, null));
            } catch (Exception e) {
                log.warn("Sync {} failed for {}: {}", action.type(), action.fileName(), e.getMessage());
                results.add(new SyncReport.Result(action.fileName(), action.type().name(),
                        action.reason(), true, false, e.getMessage()));
            }
        }
        // guardReason survives a force override (refused=false) — audit trail
        // of what the caller pushed past; see SyncReport javadoc.
        return new SyncReport(dryRun, summary, results, refused, guardReason);
    }

    /**
     * Metadata-only refresh: re-derives {@code external_doc_id} from each
     * corpus file's front-matter and backfills/corrects it on the matching
     * {@link BrainDocument} row. Built for {@code external_doc_id} rows left
     * {@code NULL} by documents ingested before V32 added the column — the
     * normal {@code sync} path never revisits an unchanged file (SKIP), and
     * {@code reindex} never sets this field, so there was previously no way
     * to backfill it without a destructive re-ingest.
     *
     * <p>This reuses the same S3 listing, manifest/scope resolution, and
     * duplicate-fileName resolution as {@code sync} (see
     * {@link SyncPlanner#latestByFileName}) so a document is picked exactly
     * the same way here as during a real sync.
     *
     * <p><b>Deliberately narrow:</b> the only write this method ever performs
     * is {@code document.setExternalDocId(...)} followed by a save of that
     * same row. It never calls into {@link #ingest}, {@code reindex}, or any
     * chunk/embedding machinery, and it never touches {@code visibility},
     * {@code analyzerScope}, {@code trustLevel}, {@code contentSha256},
     * {@code active}, {@code effectiveDate}, or any other column —
     * re-deriving those from front-matter here could, for example, silently
     * flip a document's visibility from INTERNAL to PUBLIC. If a document's
     * other metadata needs correcting, that goes through {@code sync} (which
     * treats a scope/content change as UPDATE) or the {@code PATCH} endpoint,
     * never through this method.
     *
     * <p>{@code dryRun=true} computes and reports the same actions but saves
     * nothing. Idempotent: a second run over unchanged input reports every
     * file UNCHANGED.
     */
    public MetadataRefreshReport refreshMetadata(boolean dryRun, UUID brainId, String scope) {
        validateScope(scope);
        Brain brain = brainRepository.findById(brainId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));
        CorpusSource corpusSource = corpusSourceFactory.forBrain(brain);

        SyncManifest manifest = SyncManifest.parse(corpusSource.fetchManifest());
        List<String> s3Files = corpusSource.listFiles();
        List<BrainDocument> brainDocs = documentRepository.findByBrainId(brainId);
        Map<String, BrainDocument> byFileName = SyncPlanner.latestByFileName(brainDocs);

        List<MetadataRefreshReport.Result> results = new ArrayList<>(s3Files.size());
        for (String fileName : s3Files) {
            if (!SyncPlanner.matchesScope(manifest.resolve(fileName).analyzerScope(), scope)) {
                continue;   // out of scope: untouched, exactly like sync's plan()
            }
            results.add(refreshOne(fileName, byFileName.get(fileName), corpusSource, dryRun));
        }

        Map<String, Integer> summary = new LinkedHashMap<>();
        for (MetadataRefreshReport.Result result : results) {
            summary.merge(result.action().name().toLowerCase(Locale.US), 1, Integer::sum);
        }
        return new MetadataRefreshReport(dryRun, summary, results);
    }

    private MetadataRefreshReport.Result refreshOne(String fileName, BrainDocument document,
                                                     CorpusSource corpusSource, boolean dryRun) {
        if (document == null) {
            return new MetadataRefreshReport.Result(fileName, MetadataRefreshReport.Action.NO_DOCUMENT,
                    "no BrainDocument row for this file", null, null);
        }

        String stored = document.getExternalDocId();
        byte[] bytes = corpusSource.fetch(fileName);
        String parsed = FrontmatterDocId.parse(fileName, bytes);

        if (parsed == null) {
            return new MetadataRefreshReport.Result(fileName, MetadataRefreshReport.Action.NO_FRONTMATTER_ID,
                    "front-matter declares no document_id", stored, null);
        }
        if (parsed.equals(stored)) {
            return new MetadataRefreshReport.Result(fileName, MetadataRefreshReport.Action.UNCHANGED,
                    "external_doc_id already matches front-matter", stored, parsed);
        }

        if (!dryRun) {
            // ONLY external_doc_id is written — see refreshMetadata javadoc.
            document.setExternalDocId(parsed);
            documentRepository.save(document);
        }
        return new MetadataRefreshReport.Result(fileName, MetadataRefreshReport.Action.UPDATED,
                stored == null ? "external_doc_id populated from front-matter"
                        : "external_doc_id corrected from front-matter", stored, parsed);
    }

    /**
     * A scope filter is an analyzer slug ([a-z0-9-]+) or the literal
     * {@code "shared"} (which the pattern also matches); null = no filter.
     * Anything else -> IllegalArgumentException (HTTP 400 via
     * GlobalExceptionHandler). Static so controllers validate before touching
     * the service.
     */
    public static String validateScope(String scope) {
        if (scope != null && !SCOPE_PATTERN.matcher(scope).matches()) {
            throw new IllegalArgumentException("Invalid scope '" + scope
                    + "': must be an analyzer slug matching [a-z0-9-]+ or the literal 'shared'");
        }
        return scope;
    }

    private void execute(SyncAction action, Map<String, byte[]> bytesByFile, UUID brainId) {
        switch (action.type()) {
            case UPLOAD -> ingest(action, bytesByFile.get(action.fileName()), brainId);
            case UPDATE -> {
                // New version first; old rows stay active until success. Then
                // deactivate every stale active row with this fileName — covers
                // the planned row AND any pre-existing duplicate actives.
                BrainDocument replacement = ingest(action, bytesByFile.get(action.fileName()), brainId);
                for (BrainDocument stale : documentRepository.findByBrainIdAndActiveTrue(brainId)) {
                    if (stale.getFileName().equals(action.fileName())) {
                        boolean same = stale == replacement
                                || (stale.getId() != null && stale.getId().equals(replacement.getId()));
                        if (!same) {
                            stale.setActive(false);
                            documentRepository.save(stale);
                        }
                    }
                }
            }
            case REACTIVATE -> setActive(action.documentId(), true);
            case DEACTIVATE -> setActive(action.documentId(), false);
            case SKIP -> { /* never reaches here */ }
        }
    }

    private BrainDocument ingest(SyncAction action, byte[] bytes, UUID brainId) {
        SyncManifest.Entry meta = action.meta();
        return ingestionService.ingest(
                action.fileName(),
                bytes,
                meta.title(),
                meta.sourceName(),
                SourceType.valueOf(meta.sourceType()),
                meta.visibility() == null ? null : SourceVisibility.valueOf(meta.visibility()),
                SourceTrustLevel.valueOf(meta.trustLevel()),
                meta.documentVersion(),
                meta.effectiveDate() == null ? null : LocalDate.parse(meta.effectiveDate()),
                meta.expirationDate() == null ? null : LocalDate.parse(meta.expirationDate()),
                brainId,
                meta.analyzerScope());
    }

    private void setActive(java.util.UUID documentId, boolean active) {
        BrainDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        document.setActive(active);
        documentRepository.save(document);
    }
}
