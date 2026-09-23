package com.pragmaticds.docengine.classification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.BoundaryProposalRepository;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.classification.rules.RulePackLoader;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Splits one package's classified pages into logical documents: maximal consecutive runs of the
 * same type, in {@code package_page_index} order, cut additionally wherever a page begins a new
 * FORM.
 *
 * <p><b>UNKNOWN pages are CONTINUATIONS, never starts.</b> A page the classifier could not
 * type joins the run that is open rather than cutting it; leading untyped pages, having no run to
 * join, open an UNKNOWN run of their own. See {@link #group} for the rule, the evidence and the
 * trade-off it accepts.
 *
 * <p>Blank pages and duplicate pages are TRANSPARENT: they never join a document AND never break
 * a run — paystub, blank, paystub is ONE two-page paystub, because the blank separator sheet in a
 * scanned stack carries no information about document boundaries. Transparent pages stay
 * unassigned (no {@code logical_document_page} row), which is exactly how the documents endpoint
 * reports them.
 *
 * <p><b>Form boundaries (Spec 5a, design D6).</b> A rule pack may mark an anchor
 * {@code "startsDocument": true}; a page whose classification evidence names such an anchor
 * begins a new document even when the type has not changed. That is how a borrower's SECOND
 * Schedule E becomes a second document instead of extending the first — the case that makes
 * repeating-group field names collide. The rule is DATA: multiple Schedule Cs and multiple K-1s
 * need identical behaviour, and a form-specific rule here would be mortgage knowledge in the
 * wrong module. The flag is read from the packs; the per-page match is read from the
 * classification evidence this class already loads.
 *
 * <p><b>Known limit, stated rather than discovered (design D8):</b> {@code AnchorMatcher} records
 * the FIRST hit per anchor per page and never a count, so a boundary is detectable only ACROSS
 * pages. Two forms printed on one physical sheet would not split, and a header on a page marked
 * blank or duplicate is invisible — such a page never reaches the classifier and so has no
 * evidence at all.
 *
 * <p><b>Instance boundaries (Phase C).</b> Neither rule above can see a seam between two documents
 * of the SAME type carrying no distinguishing anchor — three consecutive monthly bank statements
 * being the everyday case, which merges into one document reporting ONE beginning balance where
 * there should be three. A second pass therefore asks {@link InstanceBoundaryDetector} whether a
 * run holds more than one instance of its own type (the statement PERIOD changed), and regroups
 * through the same {@link #group} with the answer marked. Deterministic, bounded to one extra
 * pass, and inert for every type whose schema declares no instance key.
 *
 * <p>Idempotent per stage retry: the package's existing documents and links are deleted first, so
 * re-running SPLITTING converges instead of accumulating. The instance pass re-derives its
 * boundaries from the same pages each time, so a replay reaches the same cut without needing the
 * answer persisted — the discipline Phase D's model-driven boundaries WILL need.
 */
@Service
public class PackageSplitter {

    private static final Logger log = LoggerFactory.getLogger(PackageSplitter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PageRepository pages;
    private final ClassificationResultRepository results;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final RulePackLoader rulePacks;
    private final InstanceBoundaryDetector instanceBoundaries;
    private final BoundaryProposalRepository boundaryProposals;

    public PackageSplitter(
            PageRepository pages,
            ClassificationResultRepository results,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            RulePackLoader rulePacks,
            InstanceBoundaryDetector instanceBoundaries,
            BoundaryProposalRepository boundaryProposals) {
        this.pages = pages;
        this.results = results;
        this.documents = documents;
        this.links = links;
        this.rulePacks = rulePacks;
        this.instanceBoundaries = instanceBoundaries;
        this.boundaryProposals = boundaryProposals;
    }

    /**
     * The grouping core's view of one page, in package order. {@code startsDocument} is this
     * page's answer to "did you match an anchor a pack declared as a form header?" — resolved in
     * {@link #split} from the packs plus the page's classification evidence, so {@link #group}
     * stays a pure function of what it is handed.
     */
    public record PageForSplit(
            UUID pageId,
            boolean blank,
            boolean duplicate,
            String typeCode,
            BigDecimal confidence,
            boolean startsDocument,
            boolean startsInstance,
            boolean startsAiBoundary) {

        /** The pre-Phase-D shape: no AI-accepted boundary. */
        public PageForSplit(
                UUID pageId,
                boolean blank,
                boolean duplicate,
                String typeCode,
                BigDecimal confidence,
                boolean startsDocument,
                boolean startsInstance) {
            this(pageId, blank, duplicate, typeCode, confidence, startsDocument, startsInstance, false);
        }

        /** The pre-Phase-C shape: no instance boundary. */
        public PageForSplit(
                UUID pageId,
                boolean blank,
                boolean duplicate,
                String typeCode,
                BigDecimal confidence,
                boolean startsDocument) {
            this(pageId, blank, duplicate, typeCode, confidence, startsDocument, false, false);
        }

        boolean transparent() {
            return blank || duplicate;
        }
    }

    /**
     * One future logical document: type, MIN member confidence, member page ids in order, WHY
     * this run started where it did, and how many of its pages were UNTYPED continuations the
     * rule in {@link #group} absorbed. The provenance is decided inside {@link #group} because
     * that is the only place the reason is known — by the time a row is being written, "the type
     * changed" and "an anchor declared a header" look identical — and so is the count, for the
     * same reason: once the rows exist, an absorbed page and a page that classified as the
     * document's own type are indistinguishable.
     *
     * @param absorbedUntypedPages member pages that carried no type (UNKNOWN, or no verdict at
     *     all) and joined this run only because the continuation rule says they do. Always 0 for
     *     an UNKNOWN run — its pages were not absorbed INTO anything, they simply had no run to
     *     join — and never counts a transparent page, which joins nothing.
     */
    public record DocumentGroup(
            String typeCode,
            BigDecimal confidence,
            List<UUID> pageIds,
            String boundaryProvenance,
            int absorbedUntypedPages) {

        /** The pre-issue-60 shape: a group whose absorbed count was never taken. */
        public DocumentGroup(
                String typeCode, BigDecimal confidence, List<UUID> pageIds, String boundaryProvenance) {
            this(typeCode, confidence, pageIds, boundaryProvenance, 0);
        }
    }

    /** One persisted document with its member page ids — what the stage digest is built from. */
    public record SplitDocument(LogicalDocument document, List<UUID> pageIds) {}

    /**
     * One anchor, named the way the boundary set must key it: {@code (packType, anchorId)}.
     * Anchor ids are pack-scoped — two packs may each legitimately name an anchor {@code header}
     * — so an id alone is not an identity and would split the wrong document.
     */
    record AnchorRef(String packType, String anchorId) {}

    /**
     * The anchors, across every pack applicable to this org, that DECLARE a form boundary.
     * Empty until a pack ships {@code "startsDocument": true} — until then the mechanism is
     * inert and every package splits exactly as it did before Spec 5a.
     */
    static Set<AnchorRef> boundaryAnchors(List<RulePack> packs) {
        Set<AnchorRef> refs = new HashSet<>();
        for (RulePack pack : packs) {
            for (Anchor anchor : pack.anchors()) {
                if (anchor.startsDocument()) {
                    refs.add(new AnchorRef(pack.documentTypeCode(), anchor.id()));
                }
            }
        }
        return Set.copyOf(refs);
    }

    /**
     * Did this page match a boundary anchor? Read from the page's own
     * {@code classification_result.evidence} — already in memory in {@link #split}, so this
     * costs no extra query.
     *
     * <p>On a WIN the classifier writes ONLY the winning pack's matched anchors, so a boundary
     * anchor is visible exactly when its pack won the page — which is the semantic wanted: a
     * Schedule E header on a page that classified as something else is not a Schedule E
     * boundary.
     *
     * <p>Evidence is derived data. Absent, blank or unparseable, this returns FALSE: no boundary
     * is claimed, the page joins its run, and the split degrades to exactly today's behaviour.
     * It never fails the SPLITTING stage and never guesses a boundary it cannot prove.
     */
    static boolean startsDocument(String evidenceJson, Set<AnchorRef> boundaryAnchors) {
        if (boundaryAnchors.isEmpty() || evidenceJson == null || evidenceJson.isBlank()) {
            return false;
        }
        try {
            for (JsonNode anchor : JSON.readTree(evidenceJson).path("anchors")) {
                AnchorRef ref =
                        new AnchorRef(
                                anchor.path("packType").asText(), anchor.path("anchorId").asText());
                if (boundaryAnchors.contains(ref)) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException e) {
            // Ids only, never the document body — the same rule RulePackLoader follows for a
            // broken pack definition.
            log.warn("unreadable classification evidence during split; no form boundary claimed");
            return false;
        }
    }

    /** Splits the package and persists the documents. Returns them in ordinal order. */
    @Transactional
    public List<SplitDocument> split(UUID packageId) {
        List<Page> packagePages = pages.findByPackageIdOrderByPackagePageIndex(packageId);
        Map<UUID, ClassificationResult> currentByPage =
                results
                        .findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                                ClassificationResult.SUBJECT_PAGE,
                                packagePages.stream().map(Page::getId).toList())
                        .stream()
                        .collect(Collectors.toMap(ClassificationResult::getSubjectId, Function.identity()));

        // The packs' boundary declarations, resolved ONCE per split. Empty for every pack
        // shipped before Spec 5a, in which case startsDocument short-circuits to false and this
        // whole path is inert.
        Set<AnchorRef> boundaries = boundaryAnchors(rulePacks.activePacksForCurrentOrg());

        // The ACCEPTED rows of Phase D's append-only ledger — what makes the model's cuts survive
        // this method's own delete-and-recreate idempotency (roadmap R2): a replayed SPLITTING
        // reads the same rows and converges without re-calling anything. Empty until the
        // BOUNDARY_EXTRACTION stage accepts a proposal, in which case its re-split lands here.
        // Page ids rotate on reprocess, so a stale accepted row matches no current page and the
        // mechanism degrades to exactly the deterministic split — it can IMPROVE a split, never
        // break one.
        Set<UUID> aiBoundaryPages = new HashSet<>();
        for (var proposal :
                boundaryProposals.findByPackageIdAndVerdict(
                        packageId,
                        com.pragmaticds.docengine.classification.domain.BoundaryProposal
                                .VERDICT_ACCEPTED)) {
            if (proposal.getPageId() != null) {
                aiBoundaryPages.add(proposal.getPageId());
            }
        }

        List<PageForSplit> forSplit =
                packagePages.stream()
                        .map(
                                page -> {
                                    ClassificationResult current = currentByPage.get(page.getId());
                                    return new PageForSplit(
                                            page.getId(),
                                            page.isBlank(),
                                            page.getDuplicateOfPageId() != null,
                                            current == null ? null : current.getDocumentTypeCode(),
                                            current == null ? null : current.getConfidence(),
                                            // The evidence jsonb is ALREADY in memory here — no
                                            // extra query buys the form-boundary signal.
                                            current != null
                                                    && startsDocument(current.getEvidence(), boundaries),
                                            false,
                                            aiBoundaryPages.contains(page.getId()));
                                })
                        .toList();

        // PASS 2 (Phase C): the type-and-anchor grouping cannot see a seam between two documents
        // of the SAME type — three consecutive monthly statements are one run with no type change
        // and no distinguishing anchor. Ask the detector about each candidate run, then regroup
        // through the SAME group() with the answers marked. There is exactly one place where
        // pages become documents, and this is not a second one.
        //
        // Bounded to one extra pass on purpose: the detector is asked about the runs the FIRST
        // pass produced, never about the runs its own answers create. A run already cut at every
        // instance boundary has nothing left to report, so a third pass could only repeat the
        // second — and an unbounded loop here would be the oscillation risk the roadmap names
        // (R5) rather than a convergence guarantee.
        forSplit = withInstanceBoundaries(forSplit, group(forSplit));

        // Retry idempotency: links first (FK), then documents.
        UUID orgId = TenantContext.require();
        links.deleteByPackageIdAndOrgId(packageId, orgId);
        documents.deleteByPackageIdAndOrgId(packageId, orgId);

        List<SplitDocument> created = new ArrayList<>();
        int ordinal = 0;
        for (DocumentGroup group : group(forSplit)) {
            LogicalDocument document =
                    documents.save(
                            new LogicalDocument(
                                    packageId,
                                    ordinal++,
                                    group.typeCode(),
                                    group.confidence(),
                                    group.boundaryProvenance(),
                                    group.absorbedUntypedPages()));
            if (group.absorbedUntypedPages() > 0) {
                // Ids and counts only, never page content. The count is the one signal that
                // separates "a multi-page form with untyped backs" from "an unrelated document
                // glued on", and a log line is the cheapest place an operator can see it.
                log.info(
                        "document {} ({}) absorbed {} untyped page(s) of {} as continuations",
                        document.getId(),
                        group.typeCode(),
                        group.absorbedUntypedPages(),
                        group.pageIds().size());
            }
            for (int pageOrdinal = 0; pageOrdinal < group.pageIds().size(); pageOrdinal++) {
                links.save(
                        new LogicalDocumentPage(
                                document.getId(), group.pageIds().get(pageOrdinal), pageOrdinal));
            }
            created.add(new SplitDocument(document, group.pageIds()));
        }
        return List.copyOf(created);
    }

    /**
     * PASS 2's worker: ask the detector about every run the first pass produced and, if any run
     * reports an internal instance boundary, return the page list with those pages marked.
     * Returns the ORIGINAL list unchanged — by identity, so the caller can skip the regroup — when
     * nothing is reported, which is every package of every type that declares no instance key.
     *
     * <p>Never throws. A detector that fails, or answers with a page outside the run it was asked
     * about, leaves the split exactly as the deterministic type-and-anchor pass made it: this
     * mechanism may IMPROVE a split and must never be able to break one.
     */
    private List<PageForSplit> withInstanceBoundaries(
            List<PageForSplit> forSplit, List<DocumentGroup> firstPass) {
        Set<UUID> starts = new HashSet<>();
        for (DocumentGroup group : firstPass) {
            if (group.pageIds().size() < 2) {
                continue; // a one-page run cannot hold two instances this mechanism can separate
            }
            Set<UUID> reported;
            try {
                reported =
                        instanceBoundaries.pagesStartingNewInstance(
                                group.typeCode(), group.pageIds());
            } catch (RuntimeException e) {
                // Ids only, never document content — the same rule unreadable evidence follows.
                log.warn("instance boundary detection failed; keeping the deterministic split");
                continue;
            }
            if (reported == null || reported.isEmpty()) {
                continue;
            }
            // The run's OWN first page can never start a new instance inside it — it already
            // begins the document — and a page from some other run is not this run's answer.
            Set<UUID> withinRun = new HashSet<>(group.pageIds().subList(1, group.pageIds().size()));
            for (UUID pageId : reported) {
                if (withinRun.contains(pageId)) {
                    starts.add(pageId);
                }
            }
        }
        if (starts.isEmpty()) {
            return forSplit;
        }
        return forSplit.stream()
                .map(
                        page ->
                                starts.contains(page.pageId())
                                        ? new PageForSplit(
                                                page.pageId(),
                                                page.blank(),
                                                page.duplicate(),
                                                page.typeCode(),
                                                page.confidence(),
                                                page.startsDocument(),
                                                true,
                                                page.startsAiBoundary())
                                        : page)
                .toList();
    }

    /**
     * The pure grouping rule. A page with no current classification (which SPLITTING-after-
     * CLASSIFYING should never produce) degrades to UNKNOWN — the same page in every respect as
     * one the classifier looked at and could not type, because no verdict carries strictly LESS
     * information than a verdict of UNKNOWN and must not therefore cut more aggressively.
     *
     * <p>A run starts on exactly three conditions, ORed:
     *
     * <ol>
     *   <li>the page declared a form boundary ({@code startsDocument}) — POSITIVE proof of a form
     *       header, which outranks everything below;
     *   <li>no run is open yet — the package's first non-transparent page, whatever its type;
     *   <li>the page is TYPED and its type differs from the open run's.
     * </ol>
     *
     * <p><b>An UNKNOWN page never starts a document.</b> Measured on real documents: a
     * 2-page bank statement split in two because the reverse side is error-resolution boilerplate
     * carrying no type signal, and a 26-page tax return split into SEVEN because every
     * unclassifiable page between typed pages severed the run. Real multi-page documents routinely
     * carry pages with no type signal — terms and notices, continuation tables, blank backs,
     * worksheets, instruction pages — and cutting at each one makes their content unreachable from
     * the document it belongs to. An UNKNOWN page therefore JOINS the open run as a continuation
     * and leaves the run's type alone; only with no run open at all does it open an UNKNOWN run of
     * its own, so that leading untyped pages stay reachable rather than vanishing.
     *
     * <p><b>The trade-off, stated rather than discovered.</b> Two unrelated documents separated
     * only by an unclassifiable page now glue at that page, and a whole document that classifies
     * UNKNOWN end to end is absorbed by the typed document in front of it. That is a real loss,
     * and {@code group()} has no signal that could tell the two shapes apart — "typed run, then
     * untyped run" is the same input either way. It is the better default because the failure it
     * replaces is worse and more common: today EVERY real multi-page document shreds, its untyped
     * pages never reach extraction at all, and a reviewer must merge seven fragments by hand,
     * where the new failure costs one split. Spec 2's regroup path repairs either, at that
     * asymmetric cost. A package that genuinely needs the cut should earn it with a
     * {@code startsDocument} anchor, which is what condition 1 is for.
     *
     * <p>Confidence is the MINIMUM over the run's members whose type MATCHES the run's — an
     * untyped continuation page is not evidence against the type, and letting it set the number
     * would land every real multi-page document at 0.00 and sink it to the bottom of the review
     * queue.
     *
     * <p><b>The absorption is counted (issue #60).</b> The trade-off above has no signal inside
     * this method, but it leaves one behind: every untyped page that joined a TYPED run is a page
     * the rule absorbed on faith. {@link DocumentGroup#absorbedUntypedPages} carries that number
     * out, so a reviewer sees "Schedule C, pp. 13–44, 30 untyped pages absorbed" rather than a
     * 32-page Schedule C that looks like every other. The rule itself is unchanged: a package that
     * genuinely needs the cut still earns it with a {@code startsDocument} anchor, which is
     * exactly what V46 gave the schedules, Form 8962 and the state return.
     */
    static List<DocumentGroup> group(List<PageForSplit> pagesInPackageOrder) {
        List<DocumentGroup> groups = new ArrayList<>();
        String runType = null;
        BigDecimal runConfidence = null;
        List<UUID> runPages = null;
        String runProvenance = null;
        int runAbsorbed = 0;

        for (PageForSplit page : pagesInPackageOrder) {
            if (page.transparent()) {
                // Transparent FIRST: a blank or duplicate page neither joins a document nor
                // breaks a run, and that stays true of one claiming a form header.
                continue;
            }
            String type = page.typeCode() == null ? PageClassifier.UNKNOWN : page.typeCode();
            BigDecimal confidence = page.confidence() == null ? BigDecimal.ZERO : page.confidence();
            boolean untyped = PageClassifier.UNKNOWN.equals(type);
            boolean startsRun =
                    page.startsDocument()
                            || page.startsInstance()
                            || page.startsAiBoundary()
                            || runType == null
                            || (!untyped && !type.equals(runType));

            if (startsRun) {
                if (runType != null) {
                    groups.add(
                            new DocumentGroup(
                                    runType,
                                    runConfidence,
                                    List.copyOf(runPages),
                                    runProvenance,
                                    runAbsorbed));
                }
                // The reason, in the same precedence order the design's rule ranks the evidence
                // (§2): a declared form header is POSITIVE proof and outranks everything; a read
                // instance key is deterministic; with no run open the package simply begins here;
                // a typed page differing from the run is deterministic inference; and ONLY where
                // all of those are silent may the cut read AI — an accepted proposal on a page
                // that also changed type records TYPE_CHANGE, because the deterministic reason
                // alone fully explains the cut.
                runProvenance =
                        page.startsDocument()
                                ? LogicalDocument.BOUNDARY_RULE
                                : page.startsInstance()
                                        ? LogicalDocument.BOUNDARY_INSTANCE_CHANGE
                                        : runType == null
                                                ? LogicalDocument.BOUNDARY_PACKAGE_START
                                                : (!untyped && !type.equals(runType))
                                                        ? LogicalDocument.BOUNDARY_TYPE_CHANGE
                                                        : LogicalDocument.BOUNDARY_AI;
                runType = type;
                runConfidence = confidence;
                runPages = new ArrayList<>();
                runAbsorbed = 0;
            } else if (type.equals(runType) && confidence.compareTo(runConfidence) < 0) {
                runConfidence = confidence;
            } else if (untyped && !PageClassifier.UNKNOWN.equals(runType)) {
                // The continuation rule just fired: an untyped page joined a TYPED run. Counted
                // here and nowhere else — an untyped page that OPENS a run (leading pages, or one
                // carrying a boundary mark) is a document start, not an absorption, and an
                // untyped page inside an UNKNOWN run was absorbed into nothing.
                runAbsorbed++;
            }
            runPages.add(page.pageId());
        }
        if (runType != null) {
            groups.add(
                    new DocumentGroup(
                            runType, runConfidence, List.copyOf(runPages), runProvenance, runAbsorbed));
        }
        return List.copyOf(groups);
    }
}
