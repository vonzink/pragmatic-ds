package com.pragmaticds.docengine.classification.triage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.PageClassifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a package's split plus its persisted classification evidence into the TRIAGE items a
 * reviewer can act on: the page runs the engine could not type, and the near misses where a pack
 * came within a whisker of its own threshold.
 *
 * <p><b>Why this exists.</b> {@code PageClassifier} refuses to guess and lands {@code UNKNOWN}; the
 * splitter then makes an untyped page a CONTINUATION, never a document start, and its own javadoc
 * names the consequence — "a whole document that classifies UNKNOWN end to end is absorbed by the
 * typed document in front of it&nbsp;… that is a real loss". Today that loss is silent: the pages
 * are captured, boxed, classified and persisted, and nothing anywhere says a reviewer should look.
 * The signal to look already exists — an UNKNOWN result records the best loser's confidence AND
 * every pack's weak anchor matches in {@code classification_result.evidence} — it simply has no
 * reader. This class is that reader.
 *
 * <p><b>Read-time projection, no new state.</b> Everything here is derived from rows the engine
 * already writes. There is no triage table, no queue column, nothing to enqueue and nothing to
 * drift: re-running CLASSIFYING changes the queue because it changes the evidence, which is the
 * correct behaviour and would be a cache-invalidation bug in any materialized form.
 *
 * <p><b>Pure static functions over value records</b> — no repository, no clock, no tenant — for
 * exactly the reason {@code PackageSplitter.group} and {@code BoundaryWindowPlanner.plan} are
 * pure: the grouping rule is the part worth testing exhaustively, and it should be testable
 * without a database. The loading, tenancy, and human-label join live in {@code :review}'s
 * {@code UnknownTriageService}, because {@code :classification} cannot depend on {@code :review}.
 *
 * <p><b>Never any matched text.</b> Classification evidence deliberately stores anchor IDS, span
 * ids, boxes and offsets — never the matched text (Phase 4 rule 3). This projection carries the
 * anchor ids forward and nothing more; a caller that wants geometry has
 * {@code GET /v1/packages/{id}/classification}, which serves the stored evidence verbatim.
 */
public final class UnknownTriagePlanner {

    private UnknownTriagePlanner() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A whole logical document typed {@code UNKNOWN}. Either it really is unclassifiable, or — the
     * case that matters — it is a real document whose pack does not exist yet.
     */
    public static final String REASON_WHOLE_UNKNOWN_DOCUMENT = "WHOLE_UNKNOWN_DOCUMENT";

    /**
     * A run of consecutive untyped pages INSIDE a typed document: the absorption the splitter
     * documents. Named to echo {@code BoundaryWindowPlanner.REASON_UNKNOWN_RUN}, which finds the
     * same shape for a different consumer (the model gets a window; a reviewer gets a queue item).
     * The two deliberately do NOT share a threshold: the planner suppresses runs shorter than its
     * configured N because a one-page window costs tokens for little, whereas a one-page absorbed
     * run is exactly the cheap, common case a human can fix in a second.
     */
    public static final String REASON_ABSORBED_UNKNOWN_RUN = "ABSORBED_UNKNOWN_RUN";

    /** One page as triage sees it. {@code typeCode} null = never classified. */
    public record TriagePage(
            UUID pageId, int packagePageIndex, String typeCode, String evidenceJson) {}

    /** One logical document with its member pages, in membership order. */
    public record TriageDocument(
            UUID logicalDocumentId, int ordinal, String documentTypeCode, List<TriagePage> pages) {}

    /**
     * The pack that came closest without winning, over a whole run.
     *
     * @param shortfall {@code minConfidence - score}. ZERO OR NEGATIVE is possible and meaningful:
     *     it is the ambiguous-tie UNKNOWN, where two packs both cleared their thresholds and
     *     {@code PageClassifier.decide} refused to flip a coin. A reviewer reads that as "the
     *     engine had two answers", not "the engine had none".
     */
    public record BestLoser(
            UUID pageId,
            int packagePageIndex,
            String documentTypeCode,
            BigDecimal score,
            BigDecimal minConfidence,
            BigDecimal shortfall) {}

    /**
     * One anchor that matched somewhere in the run without carrying its pack over the line.
     *
     * @param anchorId the pack's anchor id — an IDENTIFIER, never the matched text
     * @param pageCount how many pages of the run this anchor matched on; an anchor hitting every
     *     page of a run is the strongest hint available that the run is one real form
     */
    public record WeakAnchor(
            String documentTypeCode, String anchorId, BigDecimal weight, int pageCount) {}

    /**
     * One actionable row of the queue.
     *
     * @param nearMiss the best loser fell within the configured margin of its own threshold — the
     *     cheapest class of fix there is, because the pack is nearly right and wants one more
     *     anchor or a slightly lower bar, not a new pack
     */
    public record TriageItem(
            UUID logicalDocumentId,
            int documentOrdinal,
            String documentTypeCode,
            String reason,
            int startPackagePageIndex,
            int endPackagePageIndex,
            List<UUID> pageIds,
            boolean nearMiss,
            BestLoser bestLoser,
            List<WeakAnchor> weakAnchors) {}

    /**
     * Plans one package's triage items.
     *
     * <p>Ordering is NEAR MISSES FIRST, then package page order. That is a queue policy, not an
     * accident: a near miss is a pack that almost worked, and one label against it teaches more per
     * minute of reviewer time than a page with no signal at all. Within each group, package order
     * keeps the queue stable across calls — a queue that reshuffles between refreshes is unusable.
     *
     * @param documents the split, in ordinal order, each with its pages in membership order
     * @param nearMissMargin how far under its own threshold a pack may fall and still count as a
     *     near miss. CONFIG, not a constant, for the reason {@code BoundaryWindowPlanner} gives
     *     for its own thresholds: it is a guess until a corpus run measures it, and a guess that
     *     cannot be tuned without a deploy hardens by accident.
     */
    public static List<TriageItem> plan(List<TriageDocument> documents, BigDecimal nearMissMargin) {
        List<TriageItem> items = new ArrayList<>();
        for (TriageDocument document : documents) {
            if (document.pages().isEmpty()) {
                continue;
            }
            if (PageClassifier.UNKNOWN.equals(document.documentTypeCode())) {
                // Every member page of an UNKNOWN document is untyped by construction: the
                // splitter only opens an UNKNOWN run where no run is open, and a TYPED page
                // always starts a run of its own type. So the whole document is one item.
                items.add(
                        item(
                                document,
                                REASON_WHOLE_UNKNOWN_DOCUMENT,
                                document.pages(),
                                nearMissMargin));
                continue;
            }
            for (List<TriagePage> run : untypedRuns(document.pages())) {
                items.add(item(document, REASON_ABSORBED_UNKNOWN_RUN, run, nearMissMargin));
            }
        }
        items.sort(
                Comparator.comparing(TriageItem::nearMiss)
                        .reversed()
                        .thenComparingInt(TriageItem::startPackagePageIndex));
        return List.copyOf(items);
    }

    /**
     * Maximal runs of consecutive untyped pages, in membership order.
     *
     * <p>A page with NO classification row counts as untyped, the same degradation
     * {@code PackageSplitter.group} applies: no verdict carries strictly less information than a
     * verdict of UNKNOWN, so it must not be triaged less aggressively either.
     *
     * <p>Consecutive means consecutive IN THE DOCUMENT, not in the package — a human regroup can
     * produce a non-contiguous document (that is why {@code logical_document_page} is a join table
     * rather than a page range), and its untyped pages are still one run of the reviewer's work.
     */
    private static List<List<TriagePage>> untypedRuns(List<TriagePage> pages) {
        List<List<TriagePage>> runs = new ArrayList<>();
        List<TriagePage> open = null;
        for (TriagePage page : pages) {
            if (untyped(page)) {
                if (open == null) {
                    open = new ArrayList<>();
                    runs.add(open);
                }
                open.add(page);
            } else {
                open = null;
            }
        }
        return runs;
    }

    private static boolean untyped(TriagePage page) {
        return page.typeCode() == null || PageClassifier.UNKNOWN.equals(page.typeCode());
    }

    private static TriageItem item(
            TriageDocument document,
            String reason,
            List<TriagePage> run,
            BigDecimal nearMissMargin) {
        BestLoser bestLoser = bestLoser(run);
        boolean nearMiss =
                bestLoser != null && bestLoser.shortfall().compareTo(nearMissMargin) <= 0;
        return new TriageItem(
                document.logicalDocumentId(),
                document.ordinal(),
                document.documentTypeCode(),
                reason,
                run.get(0).packagePageIndex(),
                run.get(run.size() - 1).packagePageIndex(),
                run.stream().map(TriagePage::pageId).toList(),
                nearMiss,
                bestLoser,
                weakAnchors(run));
    }

    /**
     * The single strongest non-winning signal anywhere in the run.
     *
     * <p>The MAXIMUM over pages, not an average: a five-page run where one page scores 0.58 against
     * a 0.60 bar and four score nothing is a near miss, and averaging would bury the one page that
     * says which document this is. Ties break on the type code so the answer is deterministic.
     */
    private static BestLoser bestLoser(List<TriagePage> run) {
        BestLoser best = null;
        for (TriagePage page : run) {
            for (JsonNode score : array(page.evidenceJson(), "scores")) {
                BigDecimal value = decimal(score, "score");
                // A pack that matched nothing is not a loser, it is an absence — reporting
                // "closest type: W2 at 0.00" would be noise dressed as a hint.
                if (value == null || value.signum() == 0) {
                    continue;
                }
                BigDecimal minConfidence = decimal(score, "minConfidence");
                if (minConfidence == null) {
                    continue;
                }
                BestLoser candidate =
                        new BestLoser(
                                page.pageId(),
                                page.packagePageIndex(),
                                score.path("packType").asText(),
                                value,
                                minConfidence,
                                minConfidence.subtract(value));
                if (best == null
                        || candidate.score().compareTo(best.score()) > 0
                        || (candidate.score().compareTo(best.score()) == 0
                                && candidate.documentTypeCode()
                                                .compareTo(best.documentTypeCode())
                                        < 0)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Every anchor that matched anywhere in the run, with how many of the run's pages it matched.
     *
     * <p>On an UNKNOWN page the evidence holds EVERY pack's matches, not just a winner's — that is
     * the whole reason this is answerable without re-running the matcher. Ordered by page count
     * then weight, both descending: an anchor on every page of the run outranks a heavier anchor
     * seen once, because the reviewer is deciding what the RUN is.
     */
    private static List<WeakAnchor> weakAnchors(List<TriagePage> run) {
        record Key(String documentTypeCode, String anchorId, BigDecimal weight) {}
        Map<Key, Integer> counts = new LinkedHashMap<>();
        for (TriagePage page : run) {
            // Distinct per page: one anchor matching three spans on one page is one page's worth
            // of evidence, not three.
            List<Key> seenOnThisPage = new ArrayList<>();
            for (JsonNode anchor : array(page.evidenceJson(), "anchors")) {
                BigDecimal weight = decimal(anchor, "weight");
                Key key =
                        new Key(
                                anchor.path("packType").asText(),
                                anchor.path("anchorId").asText(),
                                weight == null ? BigDecimal.ZERO : weight);
                if (key.anchorId().isEmpty() || seenOnThisPage.contains(key)) {
                    continue;
                }
                seenOnThisPage.add(key);
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(
                        entry ->
                                new WeakAnchor(
                                        entry.getKey().documentTypeCode(),
                                        entry.getKey().anchorId(),
                                        entry.getKey().weight(),
                                        entry.getValue()))
                .sorted(
                        Comparator.comparingInt(WeakAnchor::pageCount)
                                .reversed()
                                .thenComparing(
                                        Comparator.comparing(WeakAnchor::weight).reversed())
                                .thenComparing(WeakAnchor::documentTypeCode)
                                .thenComparing(WeakAnchor::anchorId))
                .toList();
    }

    /**
     * Evidence is DERIVED data. Absent, blank or unparseable yields nothing — the item still
     * lists, carrying its page range and no signal, which is strictly better than failing the
     * reviewer's whole queue over one bad row. The same rule {@code PackageDocumentsAssembler}
     * applies to {@code coQualifyingTypes} and the splitter applies to unreadable evidence.
     */
    private static Iterable<JsonNode> array(String evidenceJson, String field) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readTree(evidenceJson).path(field);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Exact decimal, never a double: these numbers are compared against a pack's own threshold. */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }
}
