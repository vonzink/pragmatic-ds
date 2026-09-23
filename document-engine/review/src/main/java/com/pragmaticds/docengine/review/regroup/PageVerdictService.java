package com.pragmaticds.docengine.review.regroup;

import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.review.ReviewJson;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The page verdict override (Spec 2 design §4.2): a reviewer clears a page-level blank/duplicate
 * signal so the page becomes assignable — {@code NOT_BLANK} sets {@code is_blank=false},
 * {@code NOT_DUPLICATE} clears {@code duplicate_of_page_id}. It does NOT re-run detection and does
 * NOT assign the page; assignment is a subsequent regroup.
 *
 * <p>Lives in {@code :review}, not {@code :classification}, for the same reason {@link
 * RegroupService} does: it edits {@code page} (parsing's table) AND writes a {@code review_decision}
 * (review's table). {@code :review} already depends on {@code :classification} and {@code :parsing};
 * the reverse would be a Gradle cycle. It mirrors {@code FieldCorrectionService}'s shape — principal,
 * org guard, tombstone guard, append-only decision, audit.
 *
 * <p>A human is always accountable: {@code decided_by} is NOT NULL, so a SYSTEM principal (no
 * {@code userId}) is refused 403 before any row is written.
 */
@Service
public class PageVerdictService {

    /** The signal a reviewer can clear. */
    public enum Verdict {
        NOT_BLANK,
        NOT_DUPLICATE
    }

    private final PageRepository pages;
    private final PackageRefRepository packages;
    private final ReviewDecisionRepository decisions;
    private final AuditService audit;

    public PageVerdictService(
            PageRepository pages,
            PackageRefRepository packages,
            ReviewDecisionRepository decisions,
            AuditService audit) {
        this.pages = pages;
        this.packages = packages;
        this.decisions = decisions;
        this.audit = audit;
    }

    @Transactional
    public void override(UUID pageId, Verdict verdict, String reason) {
        AuthPrincipal principal = AuthContext.require();
        UUID decidedBy = principal.userId();
        if (decidedBy == null) {
            // decided_by is NOT NULL — only a human may override a verdict.
            throw new DomainException(ErrorCode.FORBIDDEN_DOCUMENT, 403);
        }
        UUID orgId = principal.orgId();

        // The org-scoped load IS the access check: a cross-tenant page id is absent (404).
        Page page =
                pages.findByIdAndOrgId(pageId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        // A tombstoned package is unwritable exactly as it is unreadable (Phase 7c): the page's
        // package must still be live, else 404 like a nonexistent page.
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(page.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        // Snapshot the prior signal for previous_value, then clear it in place.
        String previous;
        if (verdict == Verdict.NOT_BLANK) {
            previous = ReviewJson.object("isBlank", String.valueOf(page.isBlank()));
            page.overrideBlank();
        } else {
            UUID duplicateOf = page.getDuplicateOfPageId();
            previous =
                    ReviewJson.object(
                            "duplicateOfPageId", duplicateOf == null ? null : duplicateOf.toString());
            page.overrideDuplicate();
        }
        pages.save(page);

        decisions.save(
                new ReviewDecision(
                        ReviewDecision.SUBJECT_PAGE,
                        pageId,
                        ReviewDecision.ACTION_OVERRIDE_VERDICT,
                        previous,
                        ReviewJson.object("verdict", verdict.name()),
                        reason,
                        decidedBy));

        // PII-free metadata: the verdict only (a page id carries no content).
        audit.record(
                AuditEvent.ACTION_PAGE_VERDICT_OVERRIDDEN,
                ReviewDecision.SUBJECT_PAGE,
                pageId,
                Map.of("verdict", verdict.name()));
    }
}
