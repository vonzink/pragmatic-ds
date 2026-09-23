package com.pragmaticds.docengine.gold;

import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.domain.FieldEvidence;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.extraction.repo.FieldEvidenceRepository;
import com.pragmaticds.docengine.gold.GoldDocumentView.GoldField;
import com.pragmaticds.docengine.gold.GoldDocumentView.GoldPage;
import com.pragmaticds.docengine.gold.GoldDocumentView.GoldWord;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.review.ReviewJson;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles {@link GoldDocumentView}: a REVIEWED document's decided fields, unmasked, with the
 * persisted words of its pages. Read-only; every load is org-scoped, so another org's document is
 * indistinguishable from an absent one (404).
 */
@Service
public class GoldExportService {

    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository documentPages;
    private final PageRepository pages;
    private final TextSpanRepository spans;
    private final ExtractedFieldRepository fields;
    private final FieldEvidenceRepository evidence;
    private final ReviewDecisionRepository decisions;

    public GoldExportService(
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository documentPages,
            PageRepository pages,
            TextSpanRepository spans,
            ExtractedFieldRepository fields,
            FieldEvidenceRepository evidence,
            ReviewDecisionRepository decisions) {
        this.documents = documents;
        this.documentPages = documentPages;
        this.pages = pages;
        this.spans = spans;
        this.fields = fields;
        this.evidence = evidence;
        this.decisions = decisions;
    }

    @Transactional(readOnly = true)
    public GoldDocumentView export(UUID documentId) {
        UUID orgId = AuthContext.require().orgId();
        LogicalDocument document =
                documents
                        .findByIdAndOrgId(documentId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        if (!LogicalDocument.REVIEW_REVIEWED.equals(document.getReviewStatus())) {
            throw DomainException.conflict(
                    ErrorCode.CONFLICT, Map.of("reason", "DOCUMENT_NOT_REVIEWED"));
        }

        // Pages, document-relative: ordinal order within the document.
        List<LogicalDocumentPage> members =
                documentPages.findByLogicalDocumentIdOrderByOrdinal(documentId);
        List<UUID> pageIds = members.stream().map(LogicalDocumentPage::getPageId).toList();
        Map<UUID, Page> pageById = new HashMap<>();
        for (Page page : pages.findByIdInAndOrgId(pageIds, orgId)) {
            pageById.put(page.getId(), page);
        }
        Map<UUID, Integer> documentIndexByPageId = new HashMap<>();
        List<GoldPage> goldPages = new ArrayList<>();
        for (int index = 0; index < pageIds.size(); index++) {
            UUID pageId = pageIds.get(index);
            Page page = pageById.get(pageId);
            if (page == null) {
                throw new IllegalStateException(
                        "document " + documentId + " references missing page " + pageId);
            }
            documentIndexByPageId.put(pageId, index);
            List<GoldWord> words = new ArrayList<>();
            for (TextSpan span : spans.findByPageIdOrderByOrdinal(pageId)) {
                words.add(
                        new GoldWord(
                                span.getText(),
                                span.getX(),
                                span.getY(),
                                span.getWidth(),
                                span.getHeight()));
            }
            goldPages.add(
                    new GoldPage(
                            index,
                            page.getPackagePageIndex(),
                            page.getWidthPt(),
                            page.getHeightPt(),
                            page.getRotation(),
                            document.getDocumentTypeCode(),
                            words));
        }

        // Fields with a decision, in the read model's order (name, then group key).
        List<ExtractedField> current = fields.findCurrentOccurrencesByLogicalDocumentId(documentId);
        List<UUID> fieldIds = current.stream().map(ExtractedField::getId).toList();
        Map<UUID, UUID> evidencePageByFieldId = new HashMap<>();
        if (!fieldIds.isEmpty()) {
            for (FieldEvidence item :
                    evidence.findByExtractedFieldIdInOrderByExtractedFieldIdAscRoleAscOrdinalAsc(
                            fieldIds)) {
                // Roles sort alphabetically (CONTEXT < LABEL < VALUE), so prefer VALUE explicitly
                // and otherwise keep whatever came first.
                boolean value = FieldEvidence.ROLE_VALUE.equals(item.getRole());
                if (value || !evidencePageByFieldId.containsKey(item.getExtractedFieldId())) {
                    evidencePageByFieldId.put(item.getExtractedFieldId(), item.getPageId());
                }
            }
        }

        List<GoldField> goldFields = new ArrayList<>();
        for (ExtractedField field : current) {
            String status = field.getReviewStatus();
            if (ExtractedField.REVIEW_NOT_REVIEWED.equals(status)) {
                continue; // untouched: outside the denominator
            }
            Optional<ReviewDecision> latestCorrect =
                    decisions.findFirstBySubjectTypeAndSubjectIdAndActionOrderByDecidedAtDesc(
                            ReviewDecision.SUBJECT_EXTRACTED_FIELD,
                            field.getId(),
                            ReviewDecision.ACTION_CORRECT);
            String displayedText;
            Integer pageIndex = null;
            switch (status) {
                case ExtractedField.REVIEW_REJECTED -> displayedText = null;
                case ExtractedField.REVIEW_CORRECTED -> {
                    ReviewDecision decision =
                            latestCorrect.orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "CORRECTED field "
                                                            + field.getId()
                                                            + " has no CORRECT decision"));
                    displayedText = ReviewJson.read(decision.getNewValue(), "value");
                    pageIndex = ReviewJson.readInt(decision.getNewValue(), "pageIndex");
                }
                // CONFIRMED: the effective value, exactly as every read surface overlays it.
                default ->
                        displayedText =
                                latestCorrect
                                        .map(d -> ReviewJson.read(d.getNewValue(), "value"))
                                        .orElse(field.getDisplayedText());
            }
            if (pageIndex == null) {
                UUID evidencePage = evidencePageByFieldId.get(field.getId());
                pageIndex =
                        evidencePage == null
                                ? 0
                                : documentIndexByPageId.getOrDefault(evidencePage, 0);
            }
            goldFields.add(
                    new GoldField(
                            field.getFieldName(),
                            field.getGroupKey() == null ? "" : field.getGroupKey(),
                            status,
                            displayedText,
                            pageIndex));
        }

        return new GoldDocumentView(
                document.getId(),
                document.getPackageId(),
                document.getDocumentTypeCode(),
                goldPages,
                goldFields);
    }
}
