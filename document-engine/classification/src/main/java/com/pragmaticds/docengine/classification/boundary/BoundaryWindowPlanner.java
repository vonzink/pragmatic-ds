package com.pragmaticds.docengine.classification.boundary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Computes the AMBIGUITY WINDOWS — the only page ranges the model is ever shown (design §5). A
 * window opens where the deterministic split is unproven, which is exactly three shapes:
 *
 * <ol>
 *   <li><b>UNKNOWN_RUN</b> — a run of ≥ N consecutive untyped pages, whether it is a whole
 *       UNKNOWN document or a stretch absorbed inside a typed one. This is the glue case: two
 *       documents may have merged across pages the classifier could not read.
 *   <li><b>LOW_CONFIDENCE</b> — a document whose (minimum-member) confidence sits below the
 *       floor: the run exists but the type is weak.
 *   <li><b>IMPLAUSIBLE_LENGTH</b> — a document longer than its pack's declared
 *       {@code plausiblePageMax}. A 14-page "paystub" is three paystubs.
 * </ol>
 *
 * <p>Windows are padded by one CONFIRMED page on each side — the nearest non-transparent
 * neighbour — so the model always sees a page whose type is known as an anchor for what it is
 * asked to discriminate. Overlapping or adjacent windows merge, their reasons joined, so one
 * region is never sent twice. Everything outside a window is never sent: on a clean package this
 * plans zero windows and the stage costs zero tokens.
 *
 * <p>N and the confidence floor are CONFIG with defaults, deliberately not constants: they are
 * guesses until Phase E's corpus run measures them (roadmap R6), and a guess that cannot be tuned
 * without a deploy would harden by accident.
 *
 * <p>Pure functions over value records — no repository, no clock — for the same testability
 * reason {@code PackageSplitter.group} is static.
 */
public final class BoundaryWindowPlanner {

    private BoundaryWindowPlanner() {}

    /** One page as the planner sees it, in package order. {@code typeCode} null = untyped. */
    public record PlannerPage(
            int packagePageIndex,
            UUID pageId,
            String typeCode,
            BigDecimal confidence,
            boolean transparent) {}

    /** One deterministic document: its type, min-member confidence, and member pages in order. */
    public record PlannerDocument(
            String typeCode, BigDecimal confidence, List<PlannerPage> pages) {}

    /** One ambiguity window over INCLUSIVE package page indexes, reasons '+'-joined when merged. */
    public record Window(int startIndex, int endIndex, String reason) {

        public boolean contains(int packagePageIndex) {
            return packagePageIndex >= startIndex && packagePageIndex <= endIndex;
        }
    }

    public static final String REASON_UNKNOWN_RUN = "UNKNOWN_RUN";
    public static final String REASON_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String REASON_IMPLAUSIBLE_LENGTH = "IMPLAUSIBLE_LENGTH";

    /**
     * Plans the windows for one package.
     *
     * @param pagesInOrder every package page (transparent included — they are skipped for padding
     *     but their indexes stay real)
     * @param documents the deterministic split, in ordinal order
     * @param unknownRunMin N — untyped runs shorter than this open nothing
     * @param confidenceFloor documents at or above it open nothing
     * @param plausiblePageMaxByType per-type declared maximums; a type absent here has none
     */
    public static List<Window> plan(
            List<PlannerPage> pagesInOrder,
            List<PlannerDocument> documents,
            int unknownRunMin,
            BigDecimal confidenceFloor,
            Map<String, Integer> plausiblePageMaxByType) {
        List<Window> raw = new ArrayList<>();
        for (PlannerDocument document : documents) {
            if (document.pages().isEmpty()) {
                continue;
            }
            int first = document.pages().get(0).packagePageIndex();
            int last = document.pages().get(document.pages().size() - 1).packagePageIndex();

            Integer plausibleMax = plausiblePageMaxByType.get(document.typeCode());
            if (plausibleMax != null && document.pages().size() > plausibleMax) {
                raw.add(new Window(first, last, REASON_IMPLAUSIBLE_LENGTH));
            }
            if (document.confidence() != null
                    && document.confidence().compareTo(confidenceFloor) < 0) {
                raw.add(new Window(first, last, REASON_LOW_CONFIDENCE));
            }
            raw.addAll(unknownRuns(document, unknownRunMin));
        }
        return merge(pad(raw, pagesInOrder));
    }

    /** Maximal runs of ≥ N consecutive untyped member pages inside one document. */
    private static List<Window> unknownRuns(PlannerDocument document, int unknownRunMin) {
        List<Window> runs = new ArrayList<>();
        int runStart = -1;
        int runLength = 0;
        int lastIndex = -1;
        for (PlannerPage page : document.pages()) {
            boolean untyped =
                    page.typeCode() == null
                            || com.pragmaticds.docengine.classification.PageClassifier.UNKNOWN.equals(
                                    page.typeCode());
            if (untyped) {
                if (runLength == 0) {
                    runStart = page.packagePageIndex();
                }
                runLength++;
                lastIndex = page.packagePageIndex();
            } else {
                if (runLength >= unknownRunMin) {
                    runs.add(new Window(runStart, lastIndex, REASON_UNKNOWN_RUN));
                }
                runLength = 0;
            }
        }
        if (runLength >= unknownRunMin) {
            runs.add(new Window(runStart, lastIndex, REASON_UNKNOWN_RUN));
        }
        return runs;
    }

    /**
     * Pads each window with its nearest NON-TRANSPARENT neighbour on each side. A blank or
     * duplicate page is boundary-invisible everywhere else in the engine, so it is not a
     * "confirmed page" here either — the padding walks past it to the first page that carries
     * information, and clamps at the package edge.
     */
    private static List<Window> pad(List<Window> windows, List<PlannerPage> pagesInOrder) {
        if (windows.isEmpty()) {
            return windows;
        }
        TreeSet<Integer> informative = new TreeSet<>();
        for (PlannerPage page : pagesInOrder) {
            if (!page.transparent()) {
                informative.add(page.packagePageIndex());
            }
        }
        List<Window> padded = new ArrayList<>(windows.size());
        for (Window window : windows) {
            Integer before = informative.lower(window.startIndex());
            Integer after = informative.higher(window.endIndex());
            padded.add(
                    new Window(
                            before == null ? window.startIndex() : before,
                            after == null ? window.endIndex() : after,
                            window.reason()));
        }
        return padded;
    }

    /** Merges overlapping or adjacent windows; reasons join with '+', deduplicated, sorted. */
    private static List<Window> merge(List<Window> windows) {
        List<Window> sorted = new ArrayList<>(windows);
        sorted.sort(
                java.util.Comparator.comparingInt(Window::startIndex)
                        .thenComparingInt(Window::endIndex));
        List<Window> merged = new ArrayList<>();
        for (Window window : sorted) {
            if (merged.isEmpty()
                    || window.startIndex() > merged.get(merged.size() - 1).endIndex() + 1) {
                merged.add(window);
                continue;
            }
            Window previous = merged.remove(merged.size() - 1);
            TreeSet<String> reasons = new TreeSet<>();
            for (String reason : previous.reason().split("\\+")) {
                reasons.add(reason);
            }
            for (String reason : window.reason().split("\\+")) {
                reasons.add(reason);
            }
            merged.add(
                    new Window(
                            previous.startIndex(),
                            Math.max(previous.endIndex(), window.endIndex()),
                            String.join("+", reasons)));
        }
        return List.copyOf(merged);
    }
}
