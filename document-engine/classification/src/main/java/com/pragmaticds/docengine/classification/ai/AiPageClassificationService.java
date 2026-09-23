package com.pragmaticds.docengine.classification.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.classification.ai.AiClassificationGates.PageFacts;
import com.pragmaticds.docengine.classification.ai.AiClassificationGates.QuoteEvidence;
import com.pragmaticds.docengine.classification.ai.AiClassificationGates.Refusal;
import com.pragmaticds.docengine.classification.ai.AiClassificationGates.Verdict;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.DocumentType;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.DocumentTypeRepository;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationPort;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationRequest;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationRequest.CandidatePage;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationRequest.TypeDescription;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationResult;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationResult.PageTypeProposal;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationStatus;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The model half of the CLASSIFYING stage: after the rule packs have had their say, the pages they
 * left {@code UNKNOWN} are put to the model, and any answer the model can PROVE against the page's
 * own spans is written as a new current {@link ClassificationResult} with {@code method = LLM}.
 *
 * <p>The rules live next door in the pure {@link AiClassificationGates}; this class is only the
 * shell around them — load pages and spans, assemble the request, call the port, persist the
 * survivors, count what happened. Keeping the decisions out of here is what lets the adversarial
 * cases be tested exhaustively without a database.
 *
 * <p><b>It cannot make anything worse.</b> Disabled (the default), it returns before touching a
 * repository. A provider that is DISABLED, errors, or — against its contract — throws, ends the
 * attempt having written nothing: every page stays exactly as the deterministic classifier left
 * it, which is today's behaviour. Nothing here ever throws, because the caller folds the outcome
 * into a stage summary and a fallback that could fail the pipeline it is meant to improve would be
 * a strictly worse engine.
 *
 * <p><b>Never a downgrade.</b> A page the packs typed is not sent, so a model opinion can never
 * displace a deterministic one, and a refused proposal writes nothing at all — no row, no
 * confidence adjustment. The only write is an accepted retype.
 *
 * <p><b>Never document content.</b> The evidence a retype records is the model + prompt version,
 * the {@code text_span} ids the verified quote overlapped, and its offsets in the page's joined
 * text — never the quote, never page text (Phase 4 rule 3, as {@code PageClassifier} already
 * honours). Log lines carry ids and counts only.
 */
@Service
public class AiPageClassificationService {

    private static final Logger log = LoggerFactory.getLogger(AiPageClassificationService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Version of the system instructions this stage prompts with, recorded on every ledger row and
     * every retype's evidence. It changes when the prompt resource changes — a retype is only
     * interpretable against the instructions that produced it, and "which prompt said that?" must
     * not be answerable only by reading a git history.
     */
    static final String PROMPT_VERSION = "page-classification-1";

    /** Marks the evidence as the model's, distinguishing it from anchor evidence at a glance. */
    private static final String EVIDENCE_SOURCE = "AI";

    /** Head band sent to the model — the same bands the boundary request assembles. */
    private static final BigDecimal HEAD_FRACTION = new BigDecimal("0.15");

    /** Foot band: form numbers and "Page 1 of 3" markers live down here. */
    private static final BigDecimal FOOT_FRACTION = new BigDecimal("0.90");

    /**
     * What the caller folds into the stage summary. Counters and a status — never content.
     *
     * @param pagesConsidered pages actually put to the model (post-eligibility, post-cap)
     * @param pagesTypedByModel pages retyped because a quote verified
     * @param pagesRefusedByGate proposals that did not survive the gates, and so changed nothing
     * @param pagesFailedToPersist accepted proposals whose write failed — the answer was paid for
     *     and believed, and the page is nevertheless still UNKNOWN. Distinct from a refusal on
     *     purpose: a refusal is the stage working, this is the stage losing.
     * @param refusalsByReason how many refusals each gate accounted for, non-zero reasons only.
     *     "Which gate refused this page" is the first question the tuning loop asks, and computing
     *     the reason and discarding it leaves it unanswerable in production. Counts only — never
     *     the quote, never a page id's text.
     */
    public record StageResult(
            PageTypeClassificationStatus status,
            int pagesConsidered,
            int pagesTypedByModel,
            int pagesRefusedByGate,
            int pagesFailedToPersist,
            Map<AiClassificationGates.Refusal, Integer> refusalsByReason,
            long inputTokens,
            long outputTokens) {

        static StageResult none(PageTypeClassificationStatus status, int pagesConsidered) {
            return new StageResult(status, pagesConsidered, 0, 0, 0, Map.of(), 0, 0);
        }
    }

    private final boolean enabled;
    private final String model;
    private final BigDecimal minConfidence;
    private final int maxPages;
    private final PageTypeClassificationPort port;
    private final PageRepository pages;
    private final TextSpanRepository spans;
    private final ClassificationResultRepository classifications;
    private final DocumentTypeRepository documentTypes;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    public AiPageClassificationService(
            @Value("${docengine.ai.page-classification.enabled:false}") boolean enabled,
            @Value("${docengine.ai.page-classification.model:gemini-2.5-flash-lite}") String model,
            @Value("${docengine.ai.page-classification.min-confidence:0.60}")
                    BigDecimal minConfidence,
            @Value("${docengine.ai.page-classification.max-pages:40}") int maxPages,
            PageTypeClassificationPort port,
            PageRepository pages,
            TextSpanRepository spans,
            ClassificationResultRepository classifications,
            DocumentTypeRepository documentTypes,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.model = model;
        this.minConfidence = minConfidence;
        this.maxPages = maxPages;
        this.port = port;
        this.pages = pages;
        this.spans = spans;
        this.classifications = classifications;
        this.documentTypes = documentTypes;
        // Each retype is its own transaction, deliberately: the pages of a package are independent
        // questions, so one page failing to persist must not discard the retypes that already
        // succeeded — and a method that must NEVER throw cannot sit inside one caller-owned
        // transaction it might mark rollback-only and then fail at commit.
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    /** The gate the behaviour fingerprint consults: an enabled model stage is not describable. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Asks the model about this package's unknown pages and applies whatever survives the gates.
     *
     * @return what happened, always — this method has no failure mode that reaches the caller
     */
    public StageResult classifyUnknownPages(UUID packageId) {
        if (!enabled) {
            return StageResult.none(PageTypeClassificationStatus.DISABLED, 0);
        }
        List<PageFacts> candidates = List.of();
        try {
            Map<UUID, Page> pagesById = pagesById(packageId);
            candidates = AiClassificationGates.eligible(facts(pagesById), maxPages);
            if (candidates.isEmpty()) {
                // Every page typed, or none left to ask about: the clean package costs nothing,
                // which is the property that makes an always-on fallback affordable.
                return StageResult.none(PageTypeClassificationStatus.OK, 0);
            }
            // Assembled ONCE and used twice — as the request's taxonomy and as the gate's
            // allowlist — so the two can never disagree about which codes were on offer.
            List<TypeDescription> taxonomy = taxonomy();
            PageTypeClassificationResult result =
                    port.classify(request(candidates, pagesById, taxonomy));
            if (result.status() != PageTypeClassificationStatus.OK) {
                log.info(
                        "ai page classification package={} pages={} provider status={}",
                        packageId,
                        candidates.size(),
                        result.status());
                return StageResult.none(result.status(), candidates.size());
            }
            recordCall(candidates, result);
            return apply(packageId, candidates, pagesById, result, allowedCodes(taxonomy));
        } catch (RuntimeException failure) {
            // The port's contract is that it never throws; this is the belt to that braces. A
            // fallback stage that can fail the pipeline it exists to improve is a worse engine
            // than no fallback at all.
            log.warn(
                    "ai page classification package={} pages={} failed, pages left unchanged: {}",
                    packageId,
                    candidates.size(),
                    failure.toString());
            return StageResult.none(PageTypeClassificationStatus.ERROR, candidates.size());
        }
    }

    /**
     * Applies whatever survives the gates, ONE PAGE AT A TIME and never abandoning the rest.
     *
     * <p>Two properties are load-bearing here and neither is visible from the loop's shape alone.
     *
     * <p><b>One answer per page.</b> A page id is consumed by the FIRST proposal about it,
     * accepted or not. Two accepted proposals for one page would both commit — nothing in the
     * schema forbids it — with the second superseding the first and reading its "deterministic"
     * runner-up out of the LLM row the first had just written. Consuming on the PAGE rather than
     * on acceptance also means the outcome does not depend on the order a model emitted its own
     * contradictions in.
     *
     * <p><b>A failed write costs one page.</b> Each retype is its own transaction, so a page that
     * cannot be persisted must cost exactly that page: letting the exception escape would land in
     * the outer catch, which reports ZERO retypes and discards every page already committed —
     * answers that were paid for, believed, and written.
     */
    private StageResult apply(
            UUID packageId,
            List<PageFacts> candidates,
            Map<UUID, Page> pagesById,
            PageTypeClassificationResult result,
            Set<String> allowedCodes) {
        Map<UUID, PageFacts> eligibleById =
                candidates.stream()
                        .collect(
                                Collectors.toMap(
                                        PageFacts::pageId,
                                        Function.identity(),
                                        (first, second) -> first,
                                        LinkedHashMap::new));
        Set<UUID> answeredPageIds = new HashSet<>();
        Map<Refusal, Integer> refusals = new EnumMap<>(Refusal.class);
        int typed = 0;
        int refused = 0;
        int failed = 0;
        for (PageTypeProposal proposal : result.proposals()) {
            PageFacts facts = eligibleById.get(proposal.pageId());
            if (facts == null) {
                // An answer about a page that was never asked about. The adapter already drops the
                // ones it can see; this is the stage's own floor under the same claim.
                refused += count(refusals, Refusal.PAGE_NOT_ELIGIBLE);
                continue;
            }
            if (!answeredPageIds.add(proposal.pageId())) {
                refused += count(refusals, Refusal.DUPLICATE_PAGE);
                continue;
            }
            List<TextSpan> pageSpans =
                    spans.findByPageIdOrderBySourceAscOrdinalAsc(proposal.pageId());
            Verdict verdict =
                    AiClassificationGates.verdict(
                            proposal.documentTypeCode(),
                            proposal.confidence(),
                            proposal.quotedEvidenceText(),
                            AnchorMatcher.forSpans(anchorSpans(pageSpans)),
                            minConfidence,
                            allowedCodes);
            if (!verdict.accepted()) {
                refused += count(refusals, verdict.refusal());
                continue;
            }
            try {
                retype(pagesById.get(proposal.pageId()), proposal, verdict.evidence());
                typed++;
            } catch (RuntimeException unwritable) {
                // Ids and a class name: a persistence failure is not a reason to start logging
                // what the model said about the page.
                log.warn(
                        "ai page classification package={} page={} retype not persisted: {}",
                        packageId,
                        proposal.pageId(),
                        unwritable.toString());
                failed++;
            }
        }
        log.info(
                "ai page classification package={} considered={} typed={} refused={} failed={}"
                        + " refusals={}",
                packageId,
                candidates.size(),
                typed,
                refused,
                failed,
                refusals);
        return new StageResult(
                PageTypeClassificationStatus.OK,
                candidates.size(),
                typed,
                refused,
                failed,
                // An EnumMap copy rather than Map.copyOf: the breakdown reaches a stage detail and
                // a log line, and declaration order is the only stable order it could have.
                java.util.Collections.unmodifiableMap(new EnumMap<>(refusals)),
                result.tokens().inputTokens(),
                result.tokens().outputTokens());
    }

    /** Tallies one refusal and returns 1, so the running total and the breakdown cannot drift. */
    private static int count(Map<Refusal, Integer> refusals, Refusal reason) {
        refusals.merge(reason, 1, Integer::sum);
        return 1;
    }

    /** The codes the request offered, as the gate's allowlist. */
    private static Set<String> allowedCodes(List<TypeDescription> taxonomy) {
        return taxonomy.stream()
                .map(TypeDescription::code)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The one write: supersede the page's current {@code UNKNOWN} row and append the model's, in
     * one transaction so the table can never show two current rows for a page — the supersession
     * flip being the single sanctioned mutation of an append-only table.
     */
    private void retype(Page page, PageTypeProposal proposal, QuoteEvidence evidence) {
        // Built BEFORE the supersession, because the runner-up it cites is read from the row this
        // retype is about to flip out of current.
        String evidenceDocument = evidenceJson(page, proposal, evidence);
        transactions.executeWithoutResult(
                status -> {
                    classifications.supersedeCurrent(
                            TenantContext.require(),
                            ClassificationResult.SUBJECT_PAGE,
                            page.getId());
                    classifications.save(
                            new ClassificationResult(
                                    ClassificationResult.SUBJECT_PAGE,
                                    page.getId(),
                                    proposal.documentTypeCode(),
                                    proposal.confidence(),
                                    ClassificationResult.METHOD_LLM,
                                    // No pack decided this, and borrowing the version of a pack
                                    // that FAILED would misattribute the answer.
                                    null,
                                    evidenceDocument));
                });
    }

    /**
     * The evidence jsonb: where the answer came from, and where the engine verified it. The
     * deterministic runner-up rides along because it is the whole tuning loop — a model answer that
     * keeps agreeing with a pack that scored 0.55 says the pack's threshold is wrong, and that is
     * only visible if the loser is recorded beside the winner.
     */
    private String evidenceJson(Page page, PageTypeProposal proposal, QuoteEvidence evidence) {
        ObjectNode root = JSON.createObjectNode();
        root.put("source", EVIDENCE_SOURCE);
        root.put("model", model);
        root.put("promptVersion", PROMPT_VERSION);
        ArrayNode spanIds = root.putArray("matchedSpanIds");
        evidence.spanIds().forEach(spanIds::add);
        ObjectNode offsets = root.putObject("offsets");
        offsets.put("start", evidence.rangeStart());
        offsets.put("end", evidence.rangeEnd());
        runnerUp(page.getId()).ifPresent(node -> root.set("deterministicRunnerUp", node));
        return root.toString();
    }

    /**
     * The best-scoring pack on the page the model just retyped, read back out of the UNKNOWN row's
     * own {@code scores} breakdown — the classifier already records every pack's score there, so
     * nothing has to be recomputed and the two rows cannot disagree about what the packs said.
     */
    private java.util.Optional<ObjectNode> runnerUp(UUID pageId) {
        return classifications
                .findBySubjectTypeAndSubjectIdAndCurrentTrue(
                        ClassificationResult.SUBJECT_PAGE, pageId)
                .map(ClassificationResult::getEvidence)
                .flatMap(
                        evidence -> {
                            try {
                                JsonNode scores = JSON.readTree(evidence).path("scores");
                                JsonNode best = null;
                                for (JsonNode score : scores) {
                                    if (best == null
                                            || score.path("score").asDouble()
                                                    > best.path("score").asDouble()) {
                                        best = score;
                                    }
                                }
                                if (best == null) {
                                    return java.util.Optional.empty();
                                }
                                ObjectNode node = JSON.createObjectNode();
                                node.put("type", best.path("packType").asText());
                                node.put("score", best.path("score").asDouble());
                                return java.util.Optional.of(node);
                            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                                // Evidence is a reviewer's explanation, not an input to a decision.
                                // An unreadable one costs the runner-up field, never the retype.
                                return java.util.Optional.empty();
                            }
                        });
    }

    /**
     * ONE {@code ai_interpretation} row per model call — the token/cost ledger, written straight
     * after the response so spend is recorded whatever the gates then decide.
     *
     * <p>Written through {@link JdbcTemplate} rather than {@code AiInterpretationLedger}, which
     * lives in {@code :app}: {@code :classification} cannot depend on the application module, and
     * inverting that for a ledger insert would be a heavier seam than the two statements it saves.
     * The SQL is the ledger's, column for column.
     *
     * <p>{@code subject_id} is NOT NULL and one call covers many pages, so the row is ANCHORED on
     * the first candidate page and the full set travels in {@code interpretation}. The row is a
     * record of a CALL — its tokens belong to no single page and are deliberately not divided.
     * What it stores is ids, codes and confidences: never a quote, never page text.
     *
     * <p>{@code provider} is the ADAPTER's name, not the {@code "AI"} evidence marker: the ledger's
     * provider column is what a per-provider cost rollup groups by, and a stage that wrote a
     * constant there would never attribute a cent of its spend to the model that earned it. {@code
     * tokens_in} likewise sums input + cache-read + cache-write, because that is what every other
     * row in this column means and two rows counting different things is a silently wrong bill.
     */
    private void recordCall(List<PageFacts> candidates, PageTypeClassificationResult result) {
        ObjectNode interpretation = JSON.createObjectNode();
        interpretation.put("stage", "PAGE_CLASSIFICATION");
        ArrayNode pageIds = interpretation.putArray("pageIds");
        candidates.forEach(page -> pageIds.add(page.pageId().toString()));
        ArrayNode proposals = interpretation.putArray("proposals");
        for (PageTypeProposal proposal : result.proposals()) {
            ObjectNode node = proposals.addObject();
            node.put("pageId", proposal.pageId() == null ? null : proposal.pageId().toString());
            node.put("documentTypeCode", proposal.documentTypeCode());
            node.put("confidence", proposal.confidence());
        }
        AiTokenCounts tokens = result.tokens();
        long allInputTokens =
                Math.addExact(
                        Math.addExact(tokens.inputTokens(), tokens.cacheReadInputTokens()),
                        tokens.cacheWriteInputTokens());
        try {
            jdbc.update(
                    """
                    INSERT INTO ai_interpretation
                        (id, org_id, subject_type, subject_id, provider, model, prompt_version,
                         interpretation, tokens_in, tokens_out, cost_usd)
                    VALUES (?, ?, 'PAGE', ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, NULL)
                    """,
                    UUID.randomUUID(),
                    TenantContext.require(),
                    candidates.get(0).pageId(),
                    namedProvider(),
                    model,
                    PROMPT_VERSION,
                    interpretation.toString(),
                    Math.toIntExact(allInputTokens),
                    Math.toIntExact(tokens.outputTokens()));
        } catch (RuntimeException failure) {
            // An unrecorded call is an accounting gap; a discarded retype is a worse engine.
            log.warn("ai page classification ledger write failed: {}", failure.toString());
        }
    }

    /** The port's provider name, degraded exactly as {@code AiInterpretationLedger} degrades it. */
    private String namedProvider() {
        String named = port.provider();
        return named == null || named.isBlank() ? "unknown" : named;
    }

    private Map<UUID, Page> pagesById(UUID packageId) {
        return pages.findByPackageIdOrderByPackagePageIndex(packageId).stream()
                .collect(
                        Collectors.toMap(
                                Page::getId,
                                Function.identity(),
                                (first, second) -> first,
                                LinkedHashMap::new));
    }

    private List<PageFacts> facts(Map<UUID, Page> pagesById) {
        Map<UUID, ClassificationResult> currentByPage =
                classifications
                        .findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                                ClassificationResult.SUBJECT_PAGE, pagesById.keySet())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        ClassificationResult::getSubjectId, Function.identity()));
        List<PageFacts> facts = new ArrayList<>(pagesById.size());
        for (Page page : pagesById.values()) {
            ClassificationResult current = currentByPage.get(page.getId());
            facts.add(
                    new PageFacts(
                            page.getId(),
                            page.getPackagePageIndex(),
                            current == null ? null : current.getDocumentTypeCode(),
                            page.isBlank() || page.getDuplicateOfPageId() != null));
        }
        return facts;
    }

    private PageTypeClassificationRequest request(
            List<PageFacts> candidates,
            Map<UUID, Page> pagesById,
            List<TypeDescription> taxonomy) {
        List<CandidatePage> candidatePages = new ArrayList<>(candidates.size());
        for (PageFacts facts : candidates) {
            Page page = pagesById.get(facts.pageId());
            candidatePages.add(
                    new CandidatePage(
                            facts.pageId(),
                            facts.packagePageIndex(),
                            bandText(page, true),
                            bandText(page, false)));
        }
        return new PageTypeClassificationRequest(List.copyOf(candidatePages), taxonomy);
    }

    /**
     * The types the model may name: data from {@code document_type} rows, never literals — the same
     * source and the same precedence the boundary request uses, so one authored sentence per type
     * serves both stages and neither carries mortgage knowledge in code.
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

    /**
     * Reading-order span text from the page's head (top 15%) or foot (bottom 10%) band — what the
     * model is SHOWN. The quote is later verified against the WHOLE page, not this band: the bands
     * are a spend decision, and refusing a real quote because the engine's own crop clipped it
     * would punish the model for the engine's economics.
     */
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

    private static List<AnchorSpan> anchorSpans(List<TextSpan> spansInReadingOrder) {
        return spansInReadingOrder.stream()
                .map(
                        span ->
                                new AnchorSpan(
                                        span.getId(),
                                        span.getText(),
                                        span.getX(),
                                        span.getY(),
                                        span.getWidth(),
                                        span.getHeight()))
                .toList();
    }
}
