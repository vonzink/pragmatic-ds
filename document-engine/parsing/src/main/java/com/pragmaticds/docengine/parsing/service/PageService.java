package com.pragmaticds.docengine.parsing.service;

import com.pragmaticds.docengine.parsing.client.RenderResult;
import com.pragmaticds.docengine.parsing.client.RenderedPage;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists {@code /v1/render} results: one {@code page} row per rendered page plus the PNG blob at
 * {@code {orgId}/{packageId}/pages/{pageId}.png}.
 *
 * <p>Geometry is stored VERBATIM from the worker (canonical space, contract invariant 1); the only
 * derived value is {@code content_hash} = sha256 of the PNG bytes, which doubles as the input to
 * the RENDERING stage output digest.
 *
 * <p>Blob writes happen inside the transaction but are not transactional themselves — same
 * documented trade as UploadService: an orphan blob after rollback is unreachable and sweepable;
 * a committed row pointing at missing bytes would be worse.
 */
@Service
public class PageService {

    private final PageRepository pages;
    private final TextSpanRepository textSpans;
    private final BlobStoragePort storage;

    public PageService(
            PageRepository pages, TextSpanRepository textSpans, BlobStoragePort storage) {
        this.pages = pages;
        this.textSpans = textSpans;
        this.storage = storage;
    }

    /**
     * Persists one source file's rendered pages. {@code packagePageIndexStart} is where this
     * file's pages begin in the package-wide ordering — the caller walks files by ordinal and
     * accumulates, which is what makes {@code package_page_index} run across files.
     */
    @Transactional
    public List<Page> persistRenderedPages(
            UUID packageId, UUID sourceFileId, int packagePageIndexStart, RenderResult render) {
        UUID orgId = TenantContext.require();
        List<Page> persisted = new ArrayList<>(render.pages().size());
        int offset = 0;
        for (RenderedPage rendered : render.pages()) {
            Page page =
                    new Page(
                            sourceFileId,
                            packageId,
                            rendered.meta().pageIndex(),
                            packagePageIndexStart + offset++,
                            rendered.meta().widthPt(),
                            rendered.meta().heightPt(),
                            rendered.meta().rotation());
            pages.save(page);
            String key = orgId + "/" + packageId + "/pages/" + page.getId() + ".png";
            storage.put(key, rendered.png());
            page.recordRender(key, rendered.meta().dpi(), Digests.sha256Hex(rendered.png()));
            persisted.add(page);
        }
        return persisted;
    }

    /**
     * RENDERING retry idempotency: each stage attempt commits even when it fails, so a re-run must
     * clear the partial rows first. Spans go first (FK), then pages. Orphaned PNG blobs are left
     * for the janitor — same policy as upload orphans.
     */
    @Transactional
    public void deleteAllForPackage(UUID packageId) {
        UUID orgId = TenantContext.require();
        textSpans.deleteByPackageId(packageId, orgId);
        pages.deleteByPackageIdAndOrgId(packageId, orgId);
    }
}
