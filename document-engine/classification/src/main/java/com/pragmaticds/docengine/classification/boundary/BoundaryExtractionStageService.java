package com.pragmaticds.docengine.classification.boundary;

import com.pragmaticds.docengine.classification.PackageSplitter;
import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.PlannerDocument;
import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.PlannerPage;
import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.Window;
import com.pragmaticds.docengine.classification.domain.BoundaryProposal;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.DocumentType;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.repo.BoundaryProposalRepository;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.DocumentTypeRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.classification.rules.RulePackLoader;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionPort;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionRequest;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionRequest.CandidatePage;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionRequest.TypeDescription;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionResult;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionResult.ProposedBoundary;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The BOUNDARY_EXTRACTION stage (Phase D): asks the model about the deterministic split's
 * ambiguity windows, verifies every answer through the anchoring gates, records each proposal
 * with its verdict in the append-only {@code boundary_proposal} ledger, and — when anything was
 * accepted — re-splits through the ONE grouping primitive, which reads the accepted rows itself.
 *
 * <p>Lives in {@code classification} because windows, gates and the re-split are all statements
 * about how pages become documents — the module that owns splitting — while the provider seam
 * stays generic in {@code platform/ai} and the taxonomy stays data ({@code document_type} rows),
 * so no mortgage knowledge leaks into either.
 *
 * <p><b>Off means byte-identical:</b> disabled (the default), this returns SKIPPED before
 * touching a repository, the stage row records SKIPPED (which the canonical envelope excludes),
 * and every package splits exactly as it does today.
 *
 * <p><b>Idempotent through the ledger, not through memory</b> (roadmap R2): proposals commit in
 * the same transaction as the re-split, and a replay for the same job finds them and re-splits
 * WITHOUT re-calling the model. SPLITTING's own replays converge the same way, because {@code
 * PackageSplitter.split} reads the accepted rows on every run. A reprocess rotates page ids, so
 * stale accepted rows match no current page and degrade to a no-op, never to a wrong cut.
 *
 * <p>Failure posture mirrors {@code AiExtractionStageService}: the port never throws; a provider
 * ERROR fails the attempt (retryable, and the stage is NON_FATAL so exhaustion never blocks the
 * deterministic pipeline); DISABLED from a fail-closed provider config skips. Log lines carry ids
 * and counts only — never page text, never quotes.
 */
@Service
public class BoundaryExtractionStageService {

    private static final Logger log = LoggerFactory.getLogger(BoundaryExtractionStageService.class);

    /** Head band sent to the model; the gate's top region is deliberately wider (0.25). */
    private static final BigDecimal HEAD_FRACTION = new BigDecimal("0.15");

    /** Foot band sent to the model — continuation markers live here ("Page 1 of 3"). */
    private static final BigDecimal FOOT_FRACTION = new BigDecimal("0.90");

    public enum Status {
        SKIPPED,
        ERROR,
        APPLIED
    }

    /** What the adapter turns into a {@code StageOutcome}. Counters only — never content. */
    public record StageResult(
            Status status,
            String reason,
            boolean retryable,
            String digest,
            int windows,
            int proposals,
            int accepted,
            long inputTokens,
            long outputTokens,
            int windowsSkippedByBudget) {

        static StageResult skipped(String reason) {
            return new StageResult(Status.SKIPPED, reason, false, null, 0, 0, 0, 0, 0, 0);
        }

        static StageResult error(String reason, int windows) {
            return new StageResult(Status.ERROR, reason, true, null, windows, 0, 0, 0, 0, 0);
        }
    }

    private final boolean enabled;
    private final int unknownRunMin;
    private final BigDecimal windowConfidenceFloor;
    private final BigDecimal boundaryConfidenceFloor;
    private final long maxInputTokens;
    private final long maxOutputTokens;
    private final BoundaryExtractionPort port;
    private final PageRepository pages;
    private final TextSpanRepository spans;
    private final ClassificationResultRepository classifications;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final DocumentTypeRepository documentTypes;
    private final BoundaryProposalRepository proposals;
    private final RulePackLoader rulePacks;
    private final PackageSplitter splitter;

    public BoundaryExtractionStageService(
            @Value("${docengine.boundary-extraction.enabled:false}") boolean enabled,
            @Value("${docengine.boundary-extraction.unknown-run-min:3}") int unknownRunMin,
            @Value("${docengine.boundary-extraction.window-confidence-floor:0.40}")
                    BigDecimal windowConfidenceFloor,
            @Value("${docengine.boundary-extraction.boundary-confidence-floor:0.75}")
                    BigDecimal boundaryConfidenceFloor,
            @Value("${docengine.boundary-extraction.max-input-tokens:2000000}") long maxInputTokens,
            @Value("${docengine.boundary-extraction.max-output-tokens:100000}")
                    long maxOutputTokens,
            BoundaryExtractionPort port,
            PageRepository pages,
            TextSpanRepository spans,
            ClassificationResultRepository classifications,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            DocumentTypeRepository documentTypes,
            BoundaryProposalRepository proposals,
            RulePackLoader rulePacks,
            PackageSplitter splitter) {
        if (unknownRunMin < 1) {
            throw new IllegalArgumentException("unknown-run-min must be positive");
        }
        if (maxInputTokens < 1 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("token budget caps must be positive");
        }
        this.enabled = enabled;
        this.unknownRunMin = unknownRunMin;
        this.windowConfidenceFloor = windowConfidenceFloor;
        this.boundaryConfidenceFloor = boundaryConfidenceFloor;
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.port = port;
        this.pages = pages;
        this.spans = spans;
        this.classifications = classifications;
        this.documents = documents;
        this.links = links;
        this.documentTypes = documentTypes;
        this.proposals = proposals;
        this.rulePacks = rulePacks;
        this.splitter = splitter;
    }

    /** The gate the behavior fingerprint consults: an enabled model stage is not describable. */
    public boolean enabled() {
        return enabled;
    }

    @Transactional
    public StageResult extractForPackage(UUID packageId, UUID jobId) {
        if (!enabled) {
            return StageResult.skipped("BOUNDARY_EXTRACTION_DISABLED");
        }

        // A human decision outranks everything (design §2), and this stage's re-split — like any
        // split() — deletes and recreates the package's documents. In the ordinary pipeline no
        // human has touched anything yet and this finds nothing; but a job finished before this
        // stage existed can reach here through a regroup re-kick (no stage row, so resume RUNS
        // rather than skips), and running then would wipe a reviewer's regroup. A package a human
        // has reshaped is therefore never entered at all.
        boolean humanReshaped =
                documents.findByPackageIdOrderByOrdinal(packageId).stream()
                        .anyMatch(
                                document ->
                                        LogicalDocument.BOUNDARY_HUMAN.equals(
                                                        document.getBoundaryProvenance())
                                                || document.getClassificationConfidence() == null);
        if (humanReshaped) {
            return StageResult.skipped("HUMAN_DECISIONS_PRESENT");
        }

        // Replay convergence (R2): rows for this (package, job) mean a prior attempt already
        // recorded the model's answers — re-split from the ledger, never re-spend.
        if (proposals.existsByPackageIdAndJobId(packageId, jobId)) {
            splitter.split(packageId);
            List<BoundaryProposal> replayed =
                    proposals.findByPackageIdOrderByCreatedAtAsc(packageId);
            int accepted =
                    (int)
                            replayed.stream()
                                    .filter(
                                            row ->
                                                    BoundaryProposal.VERDICT_ACCEPTED.equals(
                                                            row.getVerdict()))
                                    .count();
            return new StageResult(
                    Status.APPLIED,
                    "REPLAYED",
                    false,
                    digestOf(replayed),
                    0,
                    replayed.size(),
                    accepted,
                    0,
                    0,
                    0);
        }

        List<Page> packagePages = pages.findByPackageIdOrderByPackagePageIndex(packageId);
        Map<UUID, ClassificationResult> currentByPage =
                classifications
                        .findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                                ClassificationResult.SUBJECT_PAGE,
                                packagePages.stream().map(Page::getId).toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        ClassificationResult::getSubjectId, Function.identity()));
        Map<UUID, PlannerPage> plannerPagesById = new LinkedHashMap<>();
        for (Page page : packagePages) {
            ClassificationResult current = currentByPage.get(page.getId());
            plannerPagesById.put(
                    page.getId(),
                    new PlannerPage(
                            page.getPackagePageIndex(),
                            page.getId(),
                            current == null ? null : current.getDocumentTypeCode(),
                            current == null ? null : current.getConfidence(),
                            page.isBlank() || page.getDuplicateOfPageId() != null));
        }

        List<PlannerDocument> plannerDocuments = plannerDocuments(packageId, plannerPagesById);
        // The precedence gate's input, computed ONCE: the pages that already START a
        // deterministic document. A proposal about any of them is refused as an override.
        java.util.Set<UUID> documentStartPages =
                plannerDocuments.stream()
                        .filter(document -> !document.pages().isEmpty())
                        .map(document -> document.pages().get(0).pageId())
                        .collect(Collectors.toSet());

        List<Window> windows =
                BoundaryWindowPlanner.plan(
                        List.copyOf(plannerPagesById.values()),
                        plannerDocuments,
                        unknownRunMin,
                        windowConfidenceFloor,
                        plausiblePageMaxByType());
        if (windows.isEmpty()) {
            // Ran and found nothing ambiguous: SUCCEEDED with zero spend, zero rows. The clean
            // 180-page package costs nothing — the property that makes the stage affordable.
            log.info("boundary extraction package={} windows=0", packageId);
            return new StageResult(
                    Status.APPLIED, null, false, digestOf(List.of()), 0, 0, 0, 0, 0, 0);
        }

        Map<Integer, Page> pagesByIndex =
                packagePages.stream()
                        .collect(Collectors.toMap(Page::getPackagePageIndex, Function.identity()));
        List<TypeDescription> taxonomy = taxonomy();
        List<BoundaryProposal> recorded = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        int windowsSkippedByBudget = 0;
        for (Window window : windows) {
            // The per-package cap (E4, owner-adopted 2026-08-22: 2M in / 100k out). Checked
            // BEFORE each call: once the running totals exceed a cap, no further window is sent —
            // a runaway stops under a dollar, and the windows never sent are COUNTED in the stage
            // detail rather than silently truncated. Cuts already verified from earlier windows
            // are kept: the cap bounds SPEND, and keeping gate-anchored work spends nothing more.
            if (inputTokens > maxInputTokens || outputTokens > maxOutputTokens) {
                windowsSkippedByBudget++;
                continue;
            }
            BoundaryExtractionResult result =
                    port.proposeBoundaries(
                            request(window, pagesByIndex, plannerPagesById, taxonomy));
            switch (result.status()) {
                case DISABLED -> {
                    // Fail-closed provider config under an enabled stage: skip, write nothing.
                    // The transaction discards any rows from earlier windows so a half-recorded
                    // package cannot masquerade as a completed one.
                    log.info("boundary extraction package={} provider disabled", packageId);
                    throw new ProviderDisabled();
                }
                case ERROR -> {
                    log.warn(
                            "boundary extraction package={} window=[{},{}] provider error",
                            packageId,
                            window.startIndex(),
                            window.endIndex());
                    throw new ProviderError(windows.size());
                }
                case OK -> {
                    inputTokens += result.tokens().inputTokens();
                    outputTokens += result.tokens().outputTokens();
                    for (ProposedBoundary boundary : result.boundaries()) {
                        recorded.add(
                                record(
                                        packageId,
                                        jobId,
                                        boundary,
                                        windows,
                                        window,
                                        pagesByIndex,
                                        plannerPagesById,
                                        documentStartPages,
                                        result));
                    }
                }
            }
        }

        int accepted =
                (int)
                        recorded.stream()
                                .filter(
                                        row ->
                                                BoundaryProposal.VERDICT_ACCEPTED.equals(
                                                        row.getVerdict()))
                                .count();
        if (accepted > 0) {
            // The re-split: the same one splitter, which reads the accepted rows itself. The rows
            // and the new grouping commit together, so replay can never see one without the other.
            splitter.split(packageId);
        }
        if (windowsSkippedByBudget > 0) {
            log.warn(
                    "boundary extraction package={} token budget exhausted windowsSkipped={}",
                    packageId,
                    windowsSkippedByBudget);
        }
        log.info(
                "boundary extraction package={} windows={} proposals={} accepted={}",
                packageId,
                windows.size(),
                recorded.size(),
                accepted);
        return new StageResult(
                Status.APPLIED,
                windowsSkippedByBudget > 0 ? "TOKEN_BUDGET_EXHAUSTED" : null,
                false,
                digestOf(recorded),
                windows.size(),
                recorded.size(),
                accepted,
                inputTokens,
                outputTokens,
                windowsSkippedByBudget);
    }

    /** Converts the two provider failure shapes to results after the transaction unwinds. */
    public StageResult toResult(RuntimeException failure) {
        if (failure instanceof ProviderDisabled) {
            return StageResult.skipped("BOUNDARY_PROVIDER_DISABLED");
        }
        if (failure instanceof ProviderError error) {
            return StageResult.error("BOUNDARY_PROVIDER_ERROR", error.windows);
        }
        throw failure;
    }

    /** Thrown to unwind the transaction; the adapter converts it via {@link #toResult}. */
    static final class ProviderDisabled extends RuntimeException {}

    static final class ProviderError extends RuntimeException {
        final int windows;

        ProviderError(int windows) {
            this.windows = windows;
        }
    }

    private BoundaryProposal record(
            UUID packageId,
            UUID jobId,
            ProposedBoundary boundary,
            List<Window> windows,
            Window window,
            Map<Integer, Page> pagesByIndex,
            Map<UUID, PlannerPage> plannerPagesById,
            java.util.Set<UUID> documentStartPages,
            BoundaryExtractionResult result) {
        Page page = pagesByIndex.get(boundary.packagePageIndex());
        BoundaryProposalGates.PageFacts facts =
                page == null
                        ? null
                        : new BoundaryProposalGates.PageFacts(
                                plannerPagesById.get(page.getId()).transparent(),
                                documentStartPages.contains(page.getId()),
                                topRegionText(page));
        String verdict =
                BoundaryProposalGates.verdict(
                        boundary.packagePageIndex(),
                        boundary.confidence(),
                        boundary.quotedHeaderText(),
                        windows,
                        facts,
                        boundaryConfidenceFloor);
        boolean inWindow =
                windows.stream().anyMatch(w -> w.contains(boundary.packagePageIndex()));
        return proposals.save(
                new BoundaryProposal(
                        packageId,
                        jobId,
                        boundary.packagePageIndex(),
                        page == null ? null : page.getId(),
                        boundary.documentTypeCode(),
                        boundary.confidence(),
                        boundary.quotedHeaderText(),
                        boundary.partitionValue(),
                        inWindow ? window.startIndex() : null,
                        inWindow ? window.endIndex() : null,
                        verdict,
                        result.tokens().inputTokens(),
                        result.tokens().outputTokens()));
    }

    private List<PlannerDocument> plannerDocuments(
            UUID packageId, Map<UUID, PlannerPage> plannerPagesById) {
        List<PlannerDocument> result = new ArrayList<>();
        for (LogicalDocument document : documents.findByPackageIdOrderByOrdinal(packageId)) {
            List<PlannerPage> members =
                    links.findByLogicalDocumentIdOrderByOrdinal(document.getId()).stream()
                            .map(link -> plannerPagesById.get(link.getPageId()))
                            .filter(java.util.Objects::nonNull)
                            .toList();
            result.add(
                    new PlannerDocument(
                            document.getDocumentTypeCode(),
                            document.getClassificationConfidence(),
                            members));
        }
        return result;
    }

    private Map<String, Integer> plausiblePageMaxByType() {
        Map<String, Integer> byType = new HashMap<>();
        for (RulePack pack : rulePacks.activePacksForCurrentOrg()) {
            if (pack.plausiblePageMax() != null) {
                byType.put(pack.documentTypeCode(), pack.plausiblePageMax());
            }
        }
        return byType;
    }

    /**
     * The types the model may name: data from {@code document_type}, never literals (design §7).
     * The description is the V27 {@code split_description} — what the type LOOKS like at a
     * boundary — falling back to the display name for org-inserted types that never authored one.
     */
    private List<TypeDescription> taxonomy() {
        return documentTypes.findActiveVisibleTo(TenantContext.require()).stream()
                .sorted(Comparator.comparing(DocumentType::getCode))
                .map(
                        type ->
                                new TypeDescription(
                                        type.getCode(),
                                        type.getSplitDescription() != null
                                                ? type.getSplitDescription()
                                                : type.getDisplayName()))
                .toList();
    }

    private BoundaryExtractionRequest request(
            Window window,
            Map<Integer, Page> pagesByIndex,
            Map<UUID, PlannerPage> plannerPagesById,
            List<TypeDescription> taxonomy) {
        List<CandidatePage> candidates = new ArrayList<>();
        for (int index = window.startIndex(); index <= window.endIndex(); index++) {
            Page page = pagesByIndex.get(index);
            if (page == null) {
                continue;
            }
            PlannerPage planner = plannerPagesById.get(page.getId());
            if (planner.transparent()) {
                // Boundary-invisible everywhere else in the engine; not shown to the model either.
                continue;
            }
            candidates.add(
                    new CandidatePage(
                            index,
                            planner.typeCode() == null
                                    ? com.pragmaticds.docengine.classification.PageClassifier.UNKNOWN
                                    : planner.typeCode(),
                            planner.confidence() == null ? BigDecimal.ZERO : planner.confidence(),
                            bandText(page, true),
                            bandText(page, false),
                            // Reserved for the L2 summary; the live adapter (Phase E) assembles
                            // real prompts and wires it.
                            null));
        }
        return new BoundaryExtractionRequest(List.copyOf(candidates), taxonomy, null);
    }

    /** Reading-order span text from the page's head (top 15%) or foot (bottom 10%) band. */
    private String bandText(Page page, boolean head) {
        BigDecimal height = page.getHeightPt();
        BigDecimal headLimit = height.multiply(HEAD_FRACTION);
        BigDecimal footLimit = height.multiply(FOOT_FRACTION);
        StringBuilder text = new StringBuilder();
        for (TextSpan span : spans.findByPageIdOrderBySourceAscOrdinalAsc(page.getId())) {
            boolean inBand =
                    head
                            ? span.getY().compareTo(headLimit) < 0
                            : span.getY().add(span.getHeight()).compareTo(footLimit) > 0;
            if (inBand) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(span.getText());
            }
        }
        return text.toString();
    }

    /** The gate's view of the page top: same band the quote-match verifies against (0.25). */
    private String topRegionText(Page page) {
        BigDecimal limit = page.getHeightPt().multiply(BoundaryProposalGates.TOP_REGION_FRACTION);
        StringBuilder text = new StringBuilder();
        for (TextSpan span : spans.findByPageIdOrderBySourceAscOrdinalAsc(page.getId())) {
            if (span.getY().compareTo(limit) < 0) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(span.getText());
            }
        }
        return text.toString();
    }

    /** sha256 over the ordered (index, verdict, type) tuples — the stage's replayable identity. */
    private static String digestOf(List<BoundaryProposal> recorded) {
        StringBuilder digest = new StringBuilder();
        for (BoundaryProposal row : recorded) {
            digest.append(row.getPackagePageIndex())
                    .append('\u001F')
                    .append(row.getVerdict())
                    .append('\u001F')
                    .append(row.getProposedTypeCode())
                    .append('\u001E');
        }
        return Digests.sha256Hex(digest.toString());
    }
}
