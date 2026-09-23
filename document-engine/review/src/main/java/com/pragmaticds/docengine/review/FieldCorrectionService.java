package com.pragmaticds.docengine.review;

import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.pii.MaskableValue;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The correction path (docs/ARCHITECTURE.md 5-6). A field correction does THREE things atomically
 * and NEVER writes a Layer-2 value column:
 *
 * <ol>
 *   <li>appends a Layer-4 {@code review_decision} (previous = current effective value, new = the
 *       correction) — the machine's value stays permanently readable;
 *   <li>flips {@code extracted_field.review_status} in place (the one sanctioned mutation);
 *   <li>emits a {@code FIELD_CORRECTED} audit event.
 * </ol>
 *
 * <p>A human is always accountable: {@code decided_by} is NOT NULL, so a SYSTEM principal (no
 * {@code userId}) is refused before any row is written.
 */
@Service
public class FieldCorrectionService {

    /** PATCH actions this service accepts (a subset of the review_decision action set). */
    public enum Action {
        CONFIRM(ReviewDecision.ACTION_CONFIRM, ExtractedField.REVIEW_CONFIRMED),
        CORRECT(ReviewDecision.ACTION_CORRECT, ExtractedField.REVIEW_CORRECTED),
        REJECT(ReviewDecision.ACTION_REJECT, ExtractedField.REVIEW_REJECTED);

        private final String decisionAction;
        private final String reviewStatus;

        Action(String decisionAction, String reviewStatus) {
            this.decisionAction = decisionAction;
            this.reviewStatus = reviewStatus;
        }
    }

    private final ExtractedFieldRepository fields;
    private final LogicalDocumentRepository documents;
    private final PackageRefRepository packageRefs;
    private final ReviewDecisionRepository decisions;
    private final AuditService audit;

    public FieldCorrectionService(
            ExtractedFieldRepository fields,
            LogicalDocumentRepository documents,
            PackageRefRepository packageRefs,
            ReviewDecisionRepository decisions,
            AuditService audit) {
        this.fields = fields;
        this.documents = documents;
        this.packageRefs = packageRefs;
        this.decisions = decisions;
        this.audit = audit;
    }

    @Transactional
    public Result apply(UUID fieldId, Action action, String value, String reason) {
        return apply(fieldId, action, value, reason, null);
    }

    /**
     * @param pageIndex optional, CORRECT only: the document-relative page the value is printed on.
     *     Stored beside the value in the decision envelope; ignored for CONFIRM and REJECT.
     */
    @Transactional
    public Result apply(UUID fieldId, Action action, String value, String reason, Integer pageIndex) {
        AuthPrincipal principal = AuthContext.require();
        UUID decidedBy = principal.userId();
        if (decidedBy == null) {
            // decided_by is NOT NULL — a SYSTEM/API_KEY actor cannot author a review decision.
            throw new DomainException(ErrorCode.FORBIDDEN_DOCUMENT, 403);
        }
        if (action == Action.CORRECT && (value == null || value.isBlank())) {
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "CORRECT_REQUIRES_VALUE"));
        }

        // The org-scoped load IS the access check: a cross-tenant field id is absent (404).
        ExtractedField field =
                fields.findByIdAndOrgId(fieldId, principal.orgId())
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        // A tombstoned package is unwritable exactly as it is unreadable: a correction here would
        // append MORE PII destined to be orphaned by the purge. The field's document → package must
        // still be live (soft-delete read/write-exclusion), else 404 like a nonexistent field.
        LogicalDocument document =
                documents
                        .findByIdAndOrgId(field.getLogicalDocumentId(), principal.orgId())
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        packageRefs
                .findByIdAndOrgIdAndDeletedAtIsNull(document.getPackageId(), principal.orgId())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        String previousEffective = currentEffectiveValue(fieldId, field);
        String newValue = action == Action.CORRECT ? value : null;

        ReviewDecision decision =
                decisions.save(
                        new ReviewDecision(
                                ReviewDecision.SUBJECT_EXTRACTED_FIELD,
                                fieldId,
                                action.decisionAction,
                                ReviewJson.object("value", previousEffective),
                                action == Action.CORRECT
                                        ? ReviewJson.correction(newValue, pageIndex)
                                        : ReviewJson.object("value", newValue),
                                reason,
                                decidedBy));

        field.applyReviewStatus(action.reviewStatus);
        fields.save(field);

        // PII-free metadata: the action, the resulting status, and the field NAME (never its
        // value). The corrected value lives only in review_decision, masked at read boundaries.
        audit.record(
                AuditEvent.ACTION_FIELD_CORRECTED,
                ReviewDecision.SUBJECT_EXTRACTED_FIELD,
                fieldId,
                Map.of(
                        "action", action.decisionAction,
                        "reviewStatus", action.reviewStatus,
                        "field", field.getFieldName()));

        String effectiveAfter = action == Action.CORRECT ? value : previousEffective;
        boolean sensitive = field.isSensitive();
        return new Result(
                fieldId,
                field.getFieldName(),
                MaskableValue.of(field.getDisplayedText(), sensitive),
                MaskableValue.of(effectiveAfter, sensitive),
                action.reviewStatus,
                new DecisionView(
                        decision.getId(),
                        decision.getAction(),
                        MaskableValue.of(previousEffective, sensitive),
                        MaskableValue.of(newValue, sensitive),
                        decision.getReason(),
                        decision.getDecidedBy(),
                        decision.getDecidedAt()));
    }

    /** Latest CORRECT wins; else the machine's displayed Layer-2 value. */
    private String currentEffectiveValue(UUID fieldId, ExtractedField field) {
        return decisions
                .findFirstBySubjectTypeAndSubjectIdAndActionOrderByDecidedAtDesc(
                        ReviewDecision.SUBJECT_EXTRACTED_FIELD, fieldId, ReviewDecision.ACTION_CORRECT)
                .map(decision -> ReviewJson.read(decision.getNewValue(), "value"))
                .orElseGet(field::getDisplayedText);
    }

    /**
     * @param machineValue the untouched Layer-2 {@code displayed_text} — still readable after a
     *     correction, proving the correction did not overwrite the machine's value. A {@link
     *     MaskableValue} so a sensitive field's value is masked on this WRITE response too.
     * @param effectiveValue the value a reader now sees (the correction, or the machine value)
     */
    public record Result(
            UUID fieldId,
            String fieldName,
            MaskableValue machineValue,
            MaskableValue effectiveValue,
            String reviewStatus,
            DecisionView decision) {}

    public record DecisionView(
            UUID id,
            String action,
            MaskableValue previousValue,
            MaskableValue newValue,
            String reason,
            UUID decidedBy,
            Instant decidedAt) {}
}
