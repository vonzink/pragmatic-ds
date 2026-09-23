package com.pragmaticds.docengine.documents;

import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.parsing.client.WorkerCallException;
import com.pragmaticds.docengine.parsing.client.WorkerClient;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobNotFoundException;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Bursts one logical document to its own PDF — a page-subset copy from the ORIGINAL upload bytes,
 * assembled by the worker ({@code POST /v1/burst}, docs/WORKER_CONTRACT.md), never re-rendered.
 * Design: docs/superpowers/specs/2026-08-22-ai-document-splitting-design.md §9.
 *
 * <p>Lives in the app module for the same reason {@link
 * com.pragmaticds.docengine.download.SignedDownloadController} does: the resolution walks classification
 * (the document and its page links), parsing (page geometry and source-page indices), ingestion
 * (the source files' bytes) and platform (blob storage) — only here do all four sit on one
 * classpath, and none of it is mortgage domain logic.
 *
 * <p><b>This serves unmasked original bytes.</b> Authorization is PARITY with {@code
 * /v1/files/{id}/content}: the generic authenticated GET rule plus the org-scoped, not-tombstoned
 * loads below — deliberately NOT stricter and NOT looser (SecurityConfig's raw-content note: the
 * text-shape gate is one representation deep, and the raster and original bytes sit under the
 * generic rule). A cross-tenant id, a soft-deleted package, and a missing blob all collapse to the
 * same 404.
 *
 * <p>Derived, not stored (roadmap risk R3): the burst streams per request and no second blob of
 * borrower bytes exists for the retention purge to chase.
 */
@Service
public class DocumentPdfService {

    /** One burst result: the PDF bytes plus the identity that travels in response HEADERS. */
    public record DocumentPdf(byte[] bytes, String documentTypeCode, UUID documentId) {}

    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final PageRepository pages;
    private final SourceFileRepository sourceFiles;
    private final DocumentPackageRepository packages;
    private final BlobStoragePort storage;

    /**
     * DEFERRED, not injected directly: {@code WorkerClient} exists only under {@code
     * docengine.processing.adapter=worker} ({@code WorkerConfig}'s condition — "the stub profile
     * must not require a worker URL to boot", and a hard constructor dependency here broke exactly
     * that: every stub-adapter test context failed to load, 185 tests at once). Under the stub
     * adapter the burst endpoints answer 503 {@code WORKER_UNAVAILABLE} at request time; the
     * context boots either way.
     */
    private final ObjectProvider<WorkerClient> worker;

    public DocumentPdfService(
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            PageRepository pages,
            SourceFileRepository sourceFiles,
            DocumentPackageRepository packages,
            BlobStoragePort storage,
            ObjectProvider<WorkerClient> worker) {
        this.documents = documents;
        this.links = links;
        this.pages = pages;
        this.sourceFiles = sourceFiles;
        this.packages = packages;
        this.storage = storage;
        this.worker = worker;
    }

    /**
     * The access check WITHOUT the burst — what the signed-url issuer needs: a cross-tenant or
     * soft-deleted target must never get a token, but issuing one must not cost a worker call.
     */
    public LogicalDocument requireReadable(UUID documentId, UUID orgId) {
        LogicalDocument document =
                documents
                        .findByIdAndOrgId(documentId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(document.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        return document;
    }

    /** Resolves the document's pages to source bytes + a page sequence and bursts them. */
    public DocumentPdf burst(UUID documentId, UUID orgId) {
        LogicalDocument document = requireReadable(documentId, orgId);

        List<LogicalDocumentPage> pageLinks =
                links.findByLogicalDocumentIdOrderByOrdinal(documentId);
        if (pageLinks.isEmpty()) {
            // A document with no pages has no bytes to serve. Legal state mid-regroup; absent to
            // a caller.
            throw DomainException.notFound(ErrorCode.NOT_FOUND);
        }

        Map<UUID, Page> pagesById =
                pages
                        .findByIdInAndOrgId(
                                pageLinks.stream().map(LogicalDocumentPage::getPageId).toList(), orgId)
                        .stream()
                        .collect(Collectors.toMap(Page::getId, Function.identity()));

        // Sources in first-appearance order, each loaded ONCE however many pages it contributes.
        // LinkedHashMap preserves that order; the map value is the part ordinal on the wire.
        Map<UUID, Integer> sourceOrdinals = new LinkedHashMap<>();
        List<WorkerClient.BurstPage> sequence = new ArrayList<>();
        for (LogicalDocumentPage link : pageLinks) {
            Page page = pagesById.get(link.getPageId());
            if (page == null) {
                // A link to a page row this org cannot see is an inconsistency; to a caller it is
                // simply absent — same posture as a promised-but-missing render blob.
                throw DomainException.notFound(ErrorCode.NOT_FOUND);
            }
            int ordinal =
                    sourceOrdinals.computeIfAbsent(
                            page.getSourceFileId(), key -> sourceOrdinals.size());
            sequence.add(new WorkerClient.BurstPage(ordinal, page.getPageIndex()));
        }

        List<byte[]> sourceBytes = new ArrayList<>();
        for (UUID sourceFileId : sourceOrdinals.keySet()) {
            SourceFile file =
                    sourceFiles
                            .findByIdAndOrgId(sourceFileId, orgId)
                            .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
            try {
                sourceBytes.add(storage.get(file.getStorageKeyOriginal()));
            } catch (BlobNotFoundException e) {
                throw DomainException.notFound(ErrorCode.NOT_FOUND);
            }
        }

        WorkerClient client = worker.getIfAvailable();
        if (client == null) {
            // The stub adapter has no worker to assemble a PDF with. 503, not 404: the document
            // exists and the caller's request was valid — the capability is what is absent.
            throw new DomainException(ErrorCode.WORKER_UNAVAILABLE, 503);
        }
        byte[] pdf;
        try {
            pdf = client.burst(sourceBytes, sequence);
        } catch (WorkerCallException e) {
            // The worker failing on a READ path is an upstream outage to the caller, not a 404 and
            // not a bare 500 — the stable code says which kind.
            throw new DomainException(e.errorCode(), 502);
        }
        return new DocumentPdf(pdf, document.getDocumentTypeCode(), documentId);
    }
}
