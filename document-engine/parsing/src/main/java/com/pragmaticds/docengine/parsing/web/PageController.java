package com.pragmaticds.docengine.parsing.web;

import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.ParsingSourceFileRef;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.ParsingPackageRefRepository;
import com.pragmaticds.docengine.parsing.repo.ParsingSourceFileRefRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobNotFoundException;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.storage.SignedObjectRef;
import com.pragmaticds.docengine.platform.storage.SignedUrlIssuer;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Page geometry and rasters for the review UI (Phase 6).
 *
 * <p>Every stored evidence box is in PDF points at rotation-0. A client can only turn that into
 * a highlight if it knows the page's point dimensions and rotation, so this controller is what
 * makes the evidence chain renderable at all.
 */
@RestController
public class PageController {

    private final ParsingPackageRefRepository packages;
    private final ParsingSourceFileRefRepository sourceFiles;
    private final PageRepository pages;
    private final BlobStoragePort storage;
    private final SignedUrlIssuer signedUrls;

    public PageController(
            ParsingPackageRefRepository packages,
            ParsingSourceFileRefRepository sourceFiles,
            PageRepository pages,
            BlobStoragePort storage,
            SignedUrlIssuer signedUrls) {
        this.packages = packages;
        this.sourceFiles = sourceFiles;
        this.pages = pages;
        this.storage = storage;
        this.signedUrls = signedUrls;
    }

    @GetMapping("/v1/packages/{id}/pages")
    public PackagePagesView list(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        // Guard on the package, not on the page list: a package awaiting RENDERING has no pages
        // yet and must still answer 200 with an empty list while the UI polls.
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(id, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        List<Page> packagePages = pages.findByPackageIdOrderByPackagePageIndex(id);
        // One query for the whole package's files, not one per page.
        Map<UUID, String> contentTypes =
                sourceFiles
                        .findByIdIn(
                                packagePages.stream().map(Page::getSourceFileId).distinct().toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        ParsingSourceFileRef::getId,
                                        ParsingSourceFileRef::getContentType));

        List<PackagePagesView.PageView> views =
                packagePages.stream()
                        .map(page -> toView(page, contentTypes.get(page.getSourceFileId())))
                        .toList();
        return new PackagePagesView(id, views);
    }

    /**
     * The rendered raster for one page — thumbnails, and a viewer fallback where the original
     * bytes cannot be rendered client-side. Rendered at ingest DPI, in the page's own frame.
     */
    @GetMapping("/v1/pages/{id}/render")
    public ResponseEntity<byte[]> render(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        Page page =
                pages.findByIdAndOrgId(id, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        // A tombstoned package's page rows survive until purge, but their rasters must not be
        // served — a soft-deleted package reads as absent on every path (the pages LIST already
        // did this; render and file content did not, which was the read-exclusion bug).
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(page.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        if (page.getRenderStorageKey() == null) {
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }
        byte[] png;
        try {
            png = storage.get(page.getRenderStorageKey());
        } catch (BlobNotFoundException e) {
            // The row promises a render the blob store does not have. That is a real
            // inconsistency, but to a caller it is simply absent — and the key never leaks.
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                // A page raster is immutable for the life of the page id: a re-render creates new
                // page rows. Private, because it is borrower content.
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                .body(png);
    }

    /**
     * Issues a short-lived, signed download URL for one page's rendered raster — a session-free
     * link the download endpoint verifies by signature. The page must exist in the caller's org, in
     * a non-tombstoned package, and actually have a render; otherwise 404 (and no token). The token
     * carries this org, so it can only ever fetch this org's raster.
     */
    @GetMapping("/v1/pages/{id}/signed-url")
    public SignedUrlIssuer.IssuedUrl signedUrl(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        Page page =
                pages.findByIdAndOrgId(id, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(page.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        if (page.getRenderStorageKey() == null) {
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }
        return signedUrls.issue(new SignedObjectRef(SignedObjectRef.TYPE_PAGE_RENDER, id, orgId));
    }

    private static PackagePagesView.PageView toView(Page page, String sourceContentType) {
        return new PackagePagesView.PageView(
                page.getId(),
                page.getSourceFileId(),
                page.getPageIndex(),
                page.getPackagePageIndex(),
                page.getWidthPt(),
                page.getHeightPt(),
                page.getRotation(),
                page.getDetectedRotation(),
                page.getRenderDpi(),
                page.getRenderStorageKey() != null,
                page.getTextLayer() == null ? null : page.getTextLayer().name(),
                page.isBlank(),
                page.getDuplicateOfPageId(),
                sourceContentType);
    }
}
