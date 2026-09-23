package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.extraction.markdown.MarkdownDocumentRenderer;
import com.pragmaticds.docengine.extraction.markdown.MarkdownDocumentSource;
import com.pragmaticds.docengine.extraction.markdown.MarkdownDocumentSource.PageClassification;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.web.EntityTags;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/documents/{id}/fields.md} — one document's extracted occurrences as deterministic,
 * masked Markdown, for a human pasting into a loan note and for an LLM consumer with no browser.
 *
 * <h2>Why this path, and why here</h2>
 *
 * <p>The rendering is a projection of the SAME read model {@code GET /v1/documents/{id}/fields}
 * serves — same values, same masking, same ordering, same group keys, corrections included — so it
 * is served from the same module beside it, and its path says so. It is deliberately NOT called
 * {@code engine-result.md}: that name belongs to the immutable canonical envelope, and attaching it
 * to a corrections-bearing projection would blur the two artifacts the results module exists to keep
 * apart. When the envelope next takes a coordinated version bump this rendering moves onto it and
 * earns the hash footer; until then it states its provenance in words and claims nothing more.
 *
 * <h2>Security posture</h2>
 *
 * <p>Authenticated package readers (READONLY+) via the broad {@code GET /v1/**} rule — the same
 * posture as {@code /fields}, which is the same data. This does not widen the ADMIN-only raw-byte
 * boundary: the ADMIN matchers cover only the {@code engine-result} content paths, and every value
 * here has already passed through the read model's {@code MaskingSerializer} boundary. Tenancy and
 * soft-delete are {@link DocumentFieldsReader#require}'s job — a cross-tenant, tombstoned or
 * nonexistent id all answer the same opaque 404.
 *
 * <p>Headers follow the pair already in the codebase: an ETag like the engine-result reads, but
 * {@code must-revalidate} rather than {@code no-store}, because unlike the canonical bytes this body
 * is masked. The ETag is the SHA-256 of the rendered bytes themselves — an honest cache validator
 * that makes no claim about the machine parse, which a borrowed envelope hash would.
 */
@RestController
public class DocumentMarkdownController {

    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final DocumentFieldsReader reader;
    private final LogicalDocumentPageRepository links;
    private final PageRepository pages;
    private final ClassificationResultRepository classifications;

    public DocumentMarkdownController(
            DocumentFieldsReader reader,
            LogicalDocumentPageRepository links,
            PageRepository pages,
            ClassificationResultRepository classifications) {
        this.reader = reader;
        this.links = links;
        this.pages = pages;
        this.classifications = classifications;
    }

    @GetMapping(path = "/v1/documents/{id}/fields.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> get(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        LogicalDocument document = reader.require(id);
        MarkdownDocumentSource source =
                new MarkdownDocumentSource(
                        document.getPackageId(),
                        document.getOrdinal(),
                        reader.fieldsOf(document),
                        pageClassifications(document));

        // byte[], not String: the exact rendered bytes reach the wire without a converter getting a
        // chance to re-encode them, which is what makes the golden a claim about the RESPONSE.
        byte[] body = MarkdownDocumentRenderer.render(source).getBytes(StandardCharsets.UTF_8);
        String etag = "\"" + sha256(body) + "\"";
        // EntityTags, not etag.equals(ifNoneMatch): RFC 9110 lets a client send `*`, a
        // comma-separated list, or a weak `W/"..."` validator, and a naive equality answered 200
        // to all three — a silent cache miss on every conditional request that was not a byte-
        // identical single tag. The L1/L2 reads have always compared properly; this endpoint
        // shipped its own comparison and drifted from them.
        if (EntityTags.anyMatch(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .contentLength(body.length)
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, must-revalidate")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * The document's member pages with their current classification, in document page order.
     *
     * <p>Per page rather than per document: {@code logical_document} does carry a document-level
     * classification confidence, but it is a different statistic with different semantics (null for
     * a human-shaped document), and the page rows are what the classifier actually decided.
     */
    private List<PageClassification> pageClassifications(LogicalDocument document) {
        List<UUID> pageIds =
                links.findByLogicalDocumentIdOrderByOrdinal(document.getId()).stream()
                        .sorted(Comparator.comparingInt(LogicalDocumentPage::getOrdinal))
                        .map(LogicalDocumentPage::getPageId)
                        .toList();
        if (pageIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> pageIndexById =
                pages.findByPackageIdOrderByPackagePageIndex(document.getPackageId()).stream()
                        .collect(Collectors.toMap(Page::getId, Page::getPackagePageIndex));
        Map<UUID, ClassificationResult> currentByPage =
                classifications
                        .findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                                ClassificationResult.SUBJECT_PAGE, pageIds)
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        ClassificationResult::getSubjectId,
                                        result -> result,
                                        (first, second) -> first));

        List<PageClassification> rendered = new ArrayList<>();
        for (UUID pageId : pageIds) {
            ClassificationResult result = currentByPage.get(pageId);
            rendered.add(
                    new PageClassification(
                            pageIndexById.getOrDefault(pageId, -1) + 1,
                            result == null ? null : result.getDocumentTypeCode(),
                            result == null ? null : result.getConfidence(),
                            result == null ? null : result.getRulePackVersion()));
        }
        return List.copyOf(rendered);
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
