package com.pragmaticds.docengine.documents;

import com.pragmaticds.docengine.platform.storage.SignedObjectRef;
import com.pragmaticds.docengine.platform.storage.SignedUrlIssuer;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A logical document as its own PDF file — what an LOS asks for when it wants "the paystub" rather
 * than the whole package or a page image. Phase A of the Reducto-parity roadmap; design
 * 2026-08-22-ai-document-splitting-design.md §9.
 *
 * <p>No cover page, ever (owner decision 2026-08-22): output page N is exactly the document's page
 * N, which is what keeps the re-ingest fidelity gate meaningful. Document identity travels in the
 * response headers instead — {@code X-Document-Type} and the {@code Content-Disposition} filename
 * ({@code <TYPE>-<document id>.pdf}: both engine vocabulary, never borrower content).
 *
 * <p>Authorization: parity with {@code /v1/files/{id}/content} — see {@link DocumentPdfService}.
 */
@RestController
public class DocumentPdfController {

    private final DocumentPdfService pdfs;
    private final SignedUrlIssuer signedUrls;

    public DocumentPdfController(DocumentPdfService pdfs, SignedUrlIssuer signedUrls) {
        this.pdfs = pdfs;
        this.signedUrls = signedUrls;
    }

    @GetMapping("/v1/documents/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        DocumentPdfService.DocumentPdf result = pdfs.burst(id, orgId);
        return withDocumentHeaders(result).body(result.bytes());
    }

    /**
     * A short-lived signed URL for the same bytes — the shareable-link form, verified by {@code
     * /v1/download} without a session. Issuing costs no worker call; the burst happens when the
     * link is followed. The org travels inside the token, so it can only ever fetch this org's
     * document.
     */
    @GetMapping("/v1/documents/{id}/pdf/signed-url")
    public SignedUrlIssuer.IssuedUrl signedUrl(@PathVariable UUID id) {
        UUID orgId = TenantContext.require();
        pdfs.requireReadable(id, orgId);
        return signedUrls.issue(new SignedObjectRef(SignedObjectRef.TYPE_DOCUMENT_PDF, id, orgId));
    }

    /**
     * The header set both serving paths must agree on. PUBLIC because the other path is {@code
     * download.SignedDownloadController} — a different package — and the agreement is the point:
     * a session download and a signed-link download of the same document must be
     * byte-and-header-identical.
     */
    public static ResponseEntity.BodyBuilder withDocumentHeaders(
            DocumentPdfService.DocumentPdf result) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + result.documentTypeCode()
                                + "-"
                                + result.documentId()
                                + ".pdf\"")
                .header("X-Document-Type", result.documentTypeCode())
                // attachment-typed but non-sniffing, same as the file-content endpoint: the
                // browser must not re-interpret the type.
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate());
    }
}
