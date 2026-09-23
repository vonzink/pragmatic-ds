package com.pragmaticds.docengine.review.web;

import com.pragmaticds.docengine.review.ReviewDecisionService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document-level review endpoints (Phase 7b):
 *
 * <ul>
 *   <li>{@code POST /v1/documents/{id}/review} — MARK_REVIEWED: sign the document off;
 *   <li>{@code POST /v1/documents/{id}/classification} — RECLASSIFY: override the document type,
 *       or, with {@code pageIds}, LABEL a page run inside it (the triage answer);
 *   <li>{@code GET /v1/documents/{id}/history} — the append-only decision strip for the document
 *       and its fields, newest first.
 * </ul>
 *
 * <p>The two writes are REVIEWER+; the history read is READONLY+ (SecurityConfig). Cross-tenant
 * document ids answer 404 via the service's {@code findByIdAndOrgId} load.
 */
@RestController
public class DocumentReviewController {

    private final ReviewDecisionService reviews;

    public DocumentReviewController(ReviewDecisionService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/v1/documents/{id}/review")
    public ReviewDecisionService.DocumentDecisionResult markReviewed(@PathVariable UUID id) {
        return reviews.markReviewed(id);
    }

    /**
     * One verb, two scopes: "a human states the classification of this document" and, narrowed by
     * {@code pageIds}, "…of these pages within it".
     *
     * <p><b>Why the triage label is a narrowing of this endpoint rather than a new one.</b> The
     * RBAC matrix in {@code SecurityConfig} is CENTRAL and deliberately coarse — every other
     * {@code POST /v1/**} falls through to the ADMIN catch-all — and a reviewer's triage answer is
     * the same authority as the reclassification already gated here at REVIEWER+, on the same
     * subject, differing only in how much of the document it speaks for. Hanging it off this path
     * keeps one rule covering one authority instead of two rules that must be kept in step. An
     * absent {@code pageIds} is the pre-existing whole-document behaviour, unchanged.
     */
    @PostMapping("/v1/documents/{id}/classification")
    public ReviewDecisionService.DocumentDecisionResult reclassify(
            @PathVariable UUID id, @RequestBody ReclassifyRequest body) {
        return body.pageIds() == null || body.pageIds().isEmpty()
                ? reviews.reclassify(id, body.documentTypeCode(), body.reason())
                : reviews.labelClassification(
                        id, body.documentTypeCode(), body.pageIds(), body.reason());
    }

    @GetMapping("/v1/documents/{id}/history")
    public ReviewDecisionService.HistoryResult history(@PathVariable UUID id) {
        return reviews.history(id);
    }

    /**
     * @param documentTypeCode the human-chosen type, e.g. {@code W2}
     * @param reason optional free text
     * @param pageIds optional: the PAGE RUN this label applies to, from a triage item. Present =
     *     record the label and change nothing; absent = retype the whole document, as before. They
     *     must be current member pages of the document.
     */
    public record ReclassifyRequest(String documentTypeCode, String reason, List<UUID> pageIds) {}
}
