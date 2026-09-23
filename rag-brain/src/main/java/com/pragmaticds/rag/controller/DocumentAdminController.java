package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.DocumentDto;
import com.pragmaticds.rag.dto.DocumentUpdateRequest;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ingestion.DocumentIngestionService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.sync.MetadataRefreshReport;
import com.pragmaticds.rag.service.sync.SyncReport;
import com.pragmaticds.rag.service.sync.SyncService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin endpoints for managing guideline documents and testing retrieval.
 * Protected by AdminApiKeyFilter (X-Admin-Api-Key header) until Cognito
 * is wired in at deployment.
 */
@RestController
@RequestMapping("/api/ai/documents")
public class DocumentAdminController {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    private final DocumentIngestionService ingestionService;
    private final BrainDocumentRepository documentRepository;
    private final RetrievalService retrievalService;
    private final SyncService syncService;
    private final BrainResolver brainResolver;

    public DocumentAdminController(DocumentIngestionService ingestionService,
                                   BrainDocumentRepository documentRepository,
                                   RetrievalService retrievalService,
                                   SyncService syncService,
                                   BrainResolver brainResolver) {
        this.ingestionService = ingestionService;
        this.documentRepository = documentRepository;
        this.retrievalService = retrievalService;
        this.syncService = syncService;
        this.brainResolver = brainResolver;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam("sourceName") String sourceName,
            @RequestParam("sourceType") SourceType sourceType,
            @RequestParam(value = "visibility", required = false) SourceVisibility visibility,
            @RequestParam(value = "trustLevel", required = false) SourceTrustLevel trustLevel,
            @RequestParam(value = "documentVersion", required = false) String documentVersion,
            @RequestParam(value = "effectiveDate", required = false) LocalDate effectiveDate,
            @RequestParam(value = "expirationDate", required = false) LocalDate expirationDate,
            @RequestParam(value = "brain", required = false) String brain)
            throws IOException {

        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String extension = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported file type '" + extension + "'. Allowed: " + ALLOWED_EXTENSIONS);
        }

        UUID brainId = brainResolver.resolve(brain).getId();
        BrainDocument document = ingestionService.ingest(
                fileName, file.getBytes(), title, sourceName, sourceType,
                conservativeVisibility(visibility), conservativeTrust(trustLevel),
                documentVersion, effectiveDate, expirationDate, brainId, null);

        return ResponseEntity.ok(DocumentDto.from(document));
    }

    @GetMapping
    public List<DocumentDto> list(@RequestParam(value = "brain", required = false) String brain) {
        return documentRepository.findByBrainId(brainResolver.resolve(brain).getId())
                .stream().map(DocumentDto::from).toList();
    }

    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable UUID id) {
        int chunkCount = ingestionService.reindex(id);
        return ResponseEntity.ok(Map.of("documentId", id, "chunkCount", chunkCount));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<DocumentDto> activate(@PathVariable UUID id) {
        return setActive(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<DocumentDto> deactivate(@PathVariable UUID id) {
        return setActive(id, false);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        ingestionService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DocumentDto> update(@PathVariable UUID id,
                                              @RequestBody DocumentUpdateRequest req) {
        BrainDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));

        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (req.sourceName() == null || req.sourceName().isBlank()) {
            throw new IllegalArgumentException("sourceName is required");
        }
        if (req.sourceType() == null || req.sourceType().isBlank()) {
            throw new IllegalArgumentException("sourceType is required");
        }
        if (req.visibility() == null || req.visibility().isBlank()) {
            throw new IllegalArgumentException("visibility is required");
        }
        if (req.trustLevel() == null || req.trustLevel().isBlank()) {
            throw new IllegalArgumentException("trustLevel is required");
        }
        SourceType type = SourceType.valueOf(req.sourceType());
        SourceVisibility visibility = SourceVisibility.valueOf(req.visibility());
        SourceTrustLevel trustLevel = SourceTrustLevel.valueOf(req.trustLevel());

        document.setTitle(req.title().strip());
        document.setSourceName(req.sourceName().strip());
        document.setSourceType(type);
        document.setVisibility(visibility);
        document.setTrustLevel(trustLevel);
        // Never unversioned: a corpus snapshot refuses such a document, and one already pinned
        // stops resolving. A PATCH that omits the version (the dashboard form sends null for an
        // empty field) keeps the stored one, or derives one if the document never had it.
        boolean stated = req.documentVersion() != null && !req.documentVersion().isBlank();
        String storedVersion = document.getDocumentVersion();
        document.setDocumentVersion(stated ? req.documentVersion()
                : storedVersion != null && !storedVersion.isBlank() ? storedVersion
                : DocumentIngestionService.versionOf(null, req.effectiveDate(),
                        document.getContentSha256()));
        document.setEffectiveDate(req.effectiveDate());
        document.setExpirationDate(req.expirationDate());

        return ResponseEntity.ok(DocumentDto.from(documentRepository.save(document)));
    }

    /**
     * Sync the S3 corpus into the brain (dashboard "Sync now"). dryRun=true
     * returns the plan without changing anything. scope narrows the sync to one
     * analyzer scope (slug, or "shared" for unscoped/root files); force=true
     * overrides the mass-deactivation guard, which otherwise refuses suspicious
     * plans — including the whole-corpus (no-scope) sync, deliberately.
     */
    @PostMapping("/sync")
    public SyncReport sync(@RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
                           @RequestParam(value = "brain", required = false) String brain,
                           @RequestParam(value = "scope", required = false) String scope,
                           @RequestParam(value = "force", defaultValue = "false") boolean force) {
        SyncService.validateScope(scope);
        return syncService.sync(dryRun, brainResolver.resolve(brain).getId(), scope, force);
    }

    /**
     * Metadata-only refresh (dashboard/CLI "Refresh metadata"): re-parses each
     * corpus file's front-matter {@code document_id:} and backfills/corrects
     * {@code external_doc_id} on the matching document — nothing else is
     * touched, and no chunk is ever re-created or re-embedded. Mirrors
     * {@code /sync}'s brain/scope/dryRun params and admin-key protection
     * exactly; see {@link SyncService#refreshMetadata} for why this exists
     * and what it deliberately does not do.
     */
    @PostMapping("/refresh-metadata")
    public MetadataRefreshReport refreshMetadata(@RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
                                                  @RequestParam(value = "brain", required = false) String brain,
                                                  @RequestParam(value = "scope", required = false) String scope) {
        SyncService.validateScope(scope);
        return syncService.refreshMetadata(dryRun, brainResolver.resolve(brain).getId(), scope);
    }

    /**
     * Admin retrieval test: see exactly which chunks would be retrieved for a
     * question, with scores, before any AI answer is generated.
     */
    @GetMapping("/test-retrieval")
    public RetrievalResult testRetrieval(@RequestParam("question") String question,
                                         @RequestParam(value = "brain", required = false) String brain,
                                         @RequestParam(value = "visibility", required = false) SourceVisibility visibility,
                                         @RequestParam(value = "analyzerScope", required = false) String analyzerScope) {
        return retrievalService.retrieveAdmin(question, brainResolver.resolve(brain).getId(), visibility, analyzerScope);
    }

    private ResponseEntity<DocumentDto> setActive(UUID id, boolean active) {
        BrainDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        document.setActive(active);
        return ResponseEntity.ok(DocumentDto.from(documentRepository.save(document)));
    }

    private SourceVisibility conservativeVisibility(SourceVisibility visibility) {
        return visibility == null ? SourceVisibility.INTERNAL : visibility;
    }

    private SourceTrustLevel conservativeTrust(SourceTrustLevel trustLevel) {
        return trustLevel == null ? SourceTrustLevel.APPROVED : trustLevel;
    }
}
