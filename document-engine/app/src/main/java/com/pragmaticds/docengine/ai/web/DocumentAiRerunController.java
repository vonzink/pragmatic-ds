package com.pragmaticds.docengine.ai.web;

import com.pragmaticds.docengine.ai.AiExtractionStageService;
import com.pragmaticds.docengine.ai.DocumentRerunResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/documents/{id}/ai-extract} — REVIEWER+. Re-runs AI extraction for ONE logical
 * document (issue #66): the recovery for a document whose provider call failed while its
 * siblings applied, which the package-level stage deliberately does not retry because that would
 * re-bill the siblings.
 *
 * <p>Answers 200 with {@link DocumentRerunResult} ({@code APPLIED}, {@code ERROR} or {@code
 * NOT_ELIGIBLE} — counters only, never a value); 404 for an unknown or another org's document
 * (the service's {@code findByIdAndOrgId} load, the same answer every document route gives); 409
 * while a pipeline is running against the package — the job row locked by a running stage
 * ({@code status: PROCESSING}) or a job in any status other than HUMAN_REVIEW_REQUIRED,
 * COMPLETED or FAILED. That predicate is wider than the regroup re-extract's (HUMAN_REVIEW_REQUIRED
 * only, plus its ABA guard): the re-run needs the job settled, not re-kickable. RBAC is enforced
 * centrally in {@code SecurityConfig}, beside the regroup that re-extracts — the same authority, a
 * human deciding to read machine output again.
 */
@RestController
public class DocumentAiRerunController {

    private final AiExtractionStageService aiExtraction;

    public DocumentAiRerunController(AiExtractionStageService aiExtraction) {
        this.aiExtraction = aiExtraction;
    }

    @PostMapping("/v1/documents/{id}/ai-extract")
    public DocumentRerunResult rerun(@PathVariable UUID id) {
        return aiExtraction.extractForDocument(id);
    }
}
