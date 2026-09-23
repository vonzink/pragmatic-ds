package com.pragmaticds.docengine.classification.boundary;

import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.Window;
import com.pragmaticds.docengine.classification.domain.BoundaryProposal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * The anchoring gates — how a proposal earns belief (design §6). A returned boundary is accepted
 * only if EVERY gate holds; the verdict names the FIRST gate that refused it, and a refused
 * proposal is recorded, never discarded silently.
 *
 * <p>Evaluation order deviates from the design table's listing order, deliberately: IN_WINDOW
 * runs first because every later gate interrogates the page itself, and a proposal about a page
 * the model was never shown has no page to interrogate — it is a hallucination by definition
 * before any other question makes sense. TRANSPARENT runs before QUOTE_MATCH because a blank or
 * duplicate page has no spans, and letting it fall through to the quote gate would record
 * "the quote matched nothing" when the true refusal is "this page is boundary-invisible".
 *
 * <p>The quote match is the same trick the AI extraction layer uses to make values provable: the
 * model must return the header text it READ, verbatim, and the engine independently verifies that
 * text exists in the page's TOP REGION. Matching is containment after normalization (uppercase,
 * strip everything but letters/digits/spaces, collapse whitespace) — lenient about punctuation
 * and casing, strict about the words themselves. An unmatched quote is dropped, never
 * downgraded-and-kept.
 *
 * <p>Pure static functions — no repository, no config — for the same testability reason
 * {@code PackageSplitter.group} is static. The one adversarial test that IS this design
 * (roadmap D6) drives these directly and through the stage.
 */
public final class BoundaryProposalGates {

    private BoundaryProposalGates() {}

    /**
     * Top region of the page the quote must live in, as a fraction of page height. Wider than the
     * ~15% band the request sends as {@code headText} on purpose: the gate must not refuse a
     * quote the model legitimately read near the band's edge.
     */
    public static final BigDecimal TOP_REGION_FRACTION = new BigDecimal("0.25");

    /** What the gates need to know about the page a proposal points at. */
    public record PageFacts(
            boolean transparent, boolean alreadyStartsDocument, String topRegionText) {}

    /**
     * The verdict for one proposal: {@link BoundaryProposal#VERDICT_ACCEPTED} or the first
     * refusing gate's {@code REJECTED_*} constant.
     *
     * @param pageFacts null when the proposed index names no real page — refused as
     *     out-of-window, the same class of claim
     */
    public static String verdict(
            int packagePageIndex,
            BigDecimal confidence,
            String quotedHeaderText,
            List<Window> windows,
            PageFacts pageFacts,
            BigDecimal confidenceFloor) {
        boolean inWindow =
                windows.stream().anyMatch(window -> window.contains(packagePageIndex));
        if (!inWindow || pageFacts == null) {
            return BoundaryProposal.VERDICT_REJECTED_OUT_OF_WINDOW;
        }
        if (pageFacts.transparent()) {
            return BoundaryProposal.VERDICT_REJECTED_TRANSPARENT;
        }
        if (!quoteMatches(quotedHeaderText, pageFacts.topRegionText())) {
            return BoundaryProposal.VERDICT_REJECTED_QUOTE_MATCH;
        }
        if (pageFacts.alreadyStartsDocument()) {
            // Precedence (design §2): the deterministic split already cuts here — a human,
            // an anchor, a type change or an instance key. The AI adds nothing and must not
            // relabel a proven boundary as its own.
            return BoundaryProposal.VERDICT_REJECTED_OVERRIDE;
        }
        if (confidence == null || confidence.compareTo(confidenceFloor) < 0) {
            return BoundaryProposal.VERDICT_REJECTED_CONFIDENCE_FLOOR;
        }
        return BoundaryProposal.VERDICT_ACCEPTED;
    }

    /** Containment after normalization; an empty quote matches nothing — the model must have READ something. */
    static boolean quoteMatches(String quotedHeaderText, String topRegionText) {
        String quote = normalize(quotedHeaderText);
        if (quote.isEmpty()) {
            return false;
        }
        return normalize(topRegionText).contains(quote);
    }

    /** Uppercase, letters/digits/spaces only, whitespace collapsed. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
