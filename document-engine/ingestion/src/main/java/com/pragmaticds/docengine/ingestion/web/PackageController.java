package com.pragmaticds.docengine.ingestion.web;

import com.pragmaticds.docengine.ingestion.FileUpload;
import com.pragmaticds.docengine.ingestion.PackageLifecycleService;
import com.pragmaticds.docengine.ingestion.UploadResult;
import com.pragmaticds.docengine.ingestion.UploadService;
import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.security.DocumentAccessGuard;
import com.pragmaticds.docengine.platform.storage.BlobNotFoundException;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.storage.SignedObjectRef;
import com.pragmaticds.docengine.platform.storage.SignedUrlIssuer;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Package ingestion API. Upload answers 202 — accepted for processing, poll the job — and reads
 * answer 404 for both nonexistent and cross-tenant ids, so another org's identifiers are never
 * confirmed to exist.
 *
 * <p>DOCUMENTED DEVIATION (review finding, accepted for Phase 1): uploads are buffered on heap as
 * byte[] rather than streamed. The plan's own mitigation says "stream to blob storage; never
 * buffer whole files" — but PdfProbe needs full bytes for PDFBox regardless, the multipart caps
 * bound the exposure, and the storage layer is being reworked in Phase 2 for normalization anyway.
 * Streaming lands there. Tracked in docs/IMPLEMENTATION_PLAN.md Phase 1 risks.
 */
@RestController
public class PackageController {

    private final UploadService uploadService;
    private final DocumentPackageRepository packages;
    private final SourceFileRepository sourceFiles;
    private final BlobStoragePort storage;
    private final DocumentAccessGuard accessGuard;
    private final PackageLifecycleService lifecycle;
    private final SignedUrlIssuer signedUrls;

    public PackageController(
            UploadService uploadService,
            DocumentPackageRepository packages,
            SourceFileRepository sourceFiles,
            BlobStoragePort storage,
            DocumentAccessGuard accessGuard,
            PackageLifecycleService lifecycle,
            SignedUrlIssuer signedUrls) {
        this.uploadService = uploadService;
        this.packages = packages;
        this.sourceFiles = sourceFiles;
        this.storage = storage;
        this.accessGuard = accessGuard;
        this.lifecycle = lifecycle;
        this.signedUrls = signedUrls;
    }

    @PostMapping(path = "/v1/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadResult upload(
            // required=false so a request with no files reaches the service's EMPTY_UPLOAD
            // guard and gets the stable code instead of a framework-shaped error.
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "loanId", required = false) UUID loanId,
            @RequestParam(value = "name", required = false) String name,
            // The user's "parse again" button event: skips the parse-once reuse probe so the
            // same bytes get a NEW package and a full parse. The prior package is immutable and
            // untouched — both remain independently readable and independently deletable.
            @RequestParam(value = "forceReparse", required = false, defaultValue = "false")
                    boolean forceReparse,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        List<FileUpload> uploads =
                files == null ? List.of() : files.stream().map(PackageController::toUpload).toList();
        return uploadService.upload(uploads, loanId, name, idempotencyKey, forceReparse);
    }

    @GetMapping("/v1/packages/{id}")
    public PackageView get(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        // The org-scoped, NOT-tombstoned load IS the access check: a cross-tenant id and a
        // soft-deleted package are both absent, so the guard raises the same 404 as a nonexistent
        // one. DocumentAccessGuard is where per-document grants will later hang.
        DocumentPackage pkg =
                accessGuard.requireAccessible(
                        packages.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId));
        return PackageView.of(pkg, sourceFiles.findByPackageIdOrderByOrdinal(pkg.getId()));
    }

    /**
     * Soft delete: tombstone the package now, permanent purge later (docs/DATA_MODEL.md 2). ADMIN
     * only (SecurityConfig): a destructive lifecycle action outranks a REVIEWER's per-field
     * decisions. Answers 204; an already-tombstoned or cross-tenant id is 404 (the service's
     * {@code deleted_at IS NULL} load).
     */
    @DeleteMapping("/v1/packages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        lifecycle.softDelete(id);
    }

    /**
     * The original uploaded bytes for one file — what the review UI hands to pdf.js so a reviewer
     * sees the true document rather than a re-rendered approximation of it (Phase 6).
     *
     * <p>Serves the SNIFFED content type, never the client's declared one: echoing an attacker's
     * declared type back to a browser is how a mislabelled upload becomes stored XSS.
     */
    @GetMapping("/v1/files/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        SourceFile file = accessGuard.requireAccessible(sourceFiles.findByIdAndOrgId(id, orgId));
        // A tombstoned package's files must read as absent — the file row survives until purge, but
        // its bytes must not be served once the package is soft-deleted.
        accessGuard.requireAccessible(
                packages.findByIdAndOrgIdAndDeletedAtIsNull(file.getPackageId(), orgId));
        byte[] bytes;
        try {
            bytes = storage.get(file.getStorageKeyOriginal());
        } catch (BlobNotFoundException e) {
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                // attachment-free but non-sniffing: the browser must not re-interpret the type.
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(bytes);
    }

    /**
     * Issues a short-lived, signed download URL for one file's original bytes — a shareable link a
     * viewer can follow WITHOUT a session (the download endpoint verifies the signature instead).
     * The org-scoped, not-tombstoned load is the access check: a cross-tenant or soft-deleted id is
     * 404 and never gets a token, and the token carries THIS org so it can only ever fetch this
     * org's object. Issuance is audited by {@link SignedUrlIssuer}.
     */
    @GetMapping("/v1/files/{id}/signed-url")
    public SignedUrlIssuer.IssuedUrl signedUrl(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        SourceFile file = accessGuard.requireAccessible(sourceFiles.findByIdAndOrgId(id, orgId));
        accessGuard.requireAccessible(
                packages.findByIdAndOrgIdAndDeletedAtIsNull(file.getPackageId(), orgId));
        return signedUrls.issue(
                new SignedObjectRef(SignedObjectRef.TYPE_FILE_CONTENT, id, orgId));
    }

    private static FileUpload toUpload(MultipartFile file) {
        try {
            return new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            // A failed multipart read is an I/O problem, not a domain rejection; the global
            // handler turns it into INTERNAL without echoing anything about the file.
            throw new UncheckedIOException("failed to read multipart upload", e);
        }
    }
}
