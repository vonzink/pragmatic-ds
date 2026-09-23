package com.pragmaticds.docengine.download;

import com.pragmaticds.docengine.documents.DocumentPdfController;
import com.pragmaticds.docengine.documents.DocumentPdfService;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobNotFoundException;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.storage.SignedObjectRef;
import com.pragmaticds.docengine.platform.storage.SignedUrlException;
import com.pragmaticds.docengine.platform.storage.SignedUrlService;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public half of the signed-URL pair: verify a token and serve the bytes it authorizes, WITHOUT
 * a session. That is the point of a shareable link — but it is authorized by the HMAC signature, not
 * a principal, and gated on the org baked INTO the token. This controller binds exactly that org and
 * loads the object under it, so a token can only ever reach its own tenant's data; an expired,
 * forged, cross-tenant, or soft-deleted target all collapse to the same opaque 404.
 *
 * <p>Lives in the app module because it must resolve BOTH a page render (parsing) and a file's
 * bytes (ingestion) — only here do both repositories sit on one classpath. Wired into the security
 * chain's public paths: the signature is the authorization.
 */
@RestController
public class SignedDownloadController {

    private final SignedUrlService signedUrls;
    private final SourceFileRepository sourceFiles;
    private final PageRepository pages;
    private final DocumentPackageRepository packages;
    private final BlobStoragePort storage;
    private final DocumentPdfService documentPdfs;

    public SignedDownloadController(
            SignedUrlService signedUrls,
            SourceFileRepository sourceFiles,
            PageRepository pages,
            DocumentPackageRepository packages,
            BlobStoragePort storage,
            DocumentPdfService documentPdfs) {
        this.signedUrls = signedUrls;
        this.sourceFiles = sourceFiles;
        this.pages = pages;
        this.packages = packages;
        this.storage = storage;
        this.documentPdfs = documentPdfs;
    }

    @GetMapping("/v1/download")
    public ResponseEntity<byte[]> download(@RequestParam("token") String token) {
        SignedObjectRef ref;
        try {
            ref = signedUrls.verify(token);
        } catch (SignedUrlException rejected) {
            // Expired, forged, or malformed — never disclose which, and never reveal an object.
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }

        // Bind the token's org for the org-scoped load (and the RLS connection stamp), restoring
        // whatever was bound before — a public request may carry a dev/system binding we must not
        // clobber past this handler.
        Optional<UUID> previous = TenantContext.current();
        TenantContext.set(ref.orgId());
        try {
            return switch (ref.type()) {
                case SignedObjectRef.TYPE_PAGE_RENDER -> serveRender(ref.id(), ref.orgId());
                case SignedObjectRef.TYPE_DOCUMENT_PDF -> serveDocumentPdf(ref.id(), ref.orgId());
                default -> serveFile(ref.id(), ref.orgId());
            };
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * A logical document burst to its own PDF. The service repeats the org-scoped, not-tombstoned
     * loads under the token's org, so a token minted before a soft delete stops working — same
     * guarantee as the other two kinds.
     */
    private ResponseEntity<byte[]> serveDocumentPdf(UUID documentId, UUID orgId) {
        DocumentPdfService.DocumentPdf result = documentPdfs.burst(documentId, orgId);
        return DocumentPdfController.withDocumentHeaders(result).body(result.bytes());
    }

    private ResponseEntity<byte[]> serveFile(UUID fileId, UUID orgId) {
        SourceFile file =
                sourceFiles
                        .findByIdAndOrgId(fileId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        requirePackageLive(file.getPackageId(), orgId);
        byte[] bytes = read(file.getStorageKeyOriginal());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(bytes);
    }

    private ResponseEntity<byte[]> serveRender(UUID pageId, UUID orgId) {
        Page page =
                pages.findByIdAndOrgId(pageId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        requirePackageLive(page.getPackageId(), orgId);
        if (page.getRenderStorageKey() == null) {
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }
        byte[] png = read(page.getRenderStorageKey());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(png);
    }

    /** A tombstoned package serves nothing — a token minted before soft delete stops working. */
    private void requirePackageLive(UUID packageId, UUID orgId) {
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(packageId, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
    }

    private byte[] read(String key) {
        try {
            return storage.get(key);
        } catch (BlobNotFoundException absent) {
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }
    }
}
