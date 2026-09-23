package com.pragmaticds.docengine.classification.ai;

import com.pragmaticds.docengine.classification.PageClassifier;
import com.pragmaticds.docengine.classification.match.AnchorMatch;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every rule that decides whether the model is asked about a page, and whether its answer is
 * believed — as pure functions over values, with no repository and no configuration, for the same
 * reason {@code BoundaryProposalGates} is pure: these ARE the feature, and a rule that can only be
 * exercised through Postgres is a rule that is exercised rarely.
 *
 * <p>Two questions live here, in this order.
 *
 * <p><b>Who is asked.</b> The model is a FALLBACK, never an override (plan invariant 1): only a
 * page the rule packs actually judged and left {@code UNKNOWN} is a candidate. A page the packs
 * typed is not shown to the model at all — not shown and then refused, but never sent, so there is
 * no path on which a paid opinion could displace a free one. Blank and duplicate pages are
 * excluded for the reason they are excluded everywhere else in the engine: they carry no spans, so
 * there is nothing for the model to read and nothing for the quote gate to verify against. The cap
 * is a SPEND ceiling and is applied in ascending package page order — deterministic, so two runs
 * over the same package ask about the same pages, which is what makes the outcome reproducible and
 * a stage digest meaningful.
 *
 * <p><b>Whether the answer is believed.</b> A proposal survives only if the text the model claims
 * it read is actually on the page. Verification runs through {@link AnchorMatcher} — the module's
 * own matcher, over its own reading-order join ({@code SpanJoin}) and its own punctuation seam
 * ({@code TextFold}) — rather than a normalizer written for this gate. That is deliberate and
 * load-bearing: a second normalization is a second definition of "the same text", and two
 * definitions that can disagree mean a quote the classifier would match and this gate would refuse
 * (or worse, the reverse). One seam, one answer. It also hands back exactly what the evidence
 * needs — the span ids the match overlapped and the offsets into the joined text — so the accepted
 * row can cite the page without ever storing a character of it (Phase 4 rule 3).
 */
public final class AiClassificationGates {

    private AiClassificationGates() {}

    /**
     * The type code that means "no type": the value the rule packs record when nothing qualifies,
     * and the value the model returns to abstain. Borrowed from {@link PageClassifier} rather than
     * re-declared so the two can never drift into meaning different strings.
     */
    public static final String UNKNOWN = PageClassifier.UNKNOWN;

    /**
     * What eligibility needs to know about one page of the package.
     *
     * @param currentTypeCode the type on the page's CURRENT classification row, or null when the
     *     classifier never judged it
     * @param transparent blank or duplicate — boundary-invisible everywhere else in the engine
     */
    public record PageFacts(
            UUID pageId, int packagePageIndex, String currentTypeCode, boolean transparent) {}

    /** Why a proposal was not believed, or {@link #NONE} when it was. */
    public enum Refusal {
        NONE,
        /** The model said {@code UNKNOWN} (or named nothing): an abstention, not a failed claim. */
        MODEL_ABSTAINED,
        BELOW_MIN_CONFIDENCE,
        /** The quoted evidence is not on the page — the refusal this whole stage exists for. */
        QUOTE_UNVERIFIED,
        /** A proposal about a page that was never a candidate: no page, so nothing to verify. */
        PAGE_NOT_ELIGIBLE,
        /**
         * A type code the request never offered. {@code classification_result.document_type_code}
         * is bare {@code text} with no foreign key, so an invented code would be written as a
         * CURRENT classification no {@code document_type} row defines: extraction then finds no
         * schema, and the splitter opens a document of a type the engine has never heard of.
         */
        TYPE_NOT_IN_TAXONOMY,
        /**
         * A second answer about a page already answered. Nothing in the schema stops two retypes
         * of one page from both committing — the supersession flip has no unique index behind it —
         * so the second silently overwrites the first and reads ITS runner-up out of the LLM row
         * the first just wrote, which carries no pack scores. The first proposal wins.
         */
        DUPLICATE_PAGE
    }

    /**
     * Where the verified quote was found: {@code text_span} ids and the half-open offset range in
     * the page's joined reading-order text. Ids and numbers only — never the matched text.
     */
    public record QuoteEvidence(List<Long> spanIds, int rangeStart, int rangeEnd) {}

    /** One proposal's outcome. {@code evidence} is non-null exactly when it was accepted. */
    public record Verdict(Refusal refusal, QuoteEvidence evidence) {

        public boolean accepted() {
            return refusal == Refusal.NONE;
        }
    }

    /**
     * The pages worth spending a model call on, in ascending package page order, at most {@code
     * maxPages} of them. Ordering is imposed here rather than assumed of the caller because it is
     * the cap's meaning: "the first N unknown pages", not "whichever N the query happened to
     * return".
     */
    public static List<PageFacts> eligible(Collection<PageFacts> pages, int maxPages) {
        if (maxPages <= 0) {
            return List.of();
        }
        return pages.stream()
                .filter(page -> !page.transparent())
                .filter(page -> UNKNOWN.equals(page.currentTypeCode()))
                .sorted(Comparator.comparingInt(PageFacts::packagePageIndex))
                .limit(maxPages)
                .toList();
    }

    /**
     * Whether one proposal about one page is believed.
     *
     * <p>Order of refusal is the order of honesty, not of cost. Abstention is read FIRST: a model
     * that answered {@code UNKNOWN} made no claim, and recording it as a hallucinated quote or a
     * low-confidence guess would mis-describe the one behaviour the prompt asks for when the page
     * is unreadable. Confidence follows, because it interrogates only the answer. The quote gate is
     * last, because it interrogates the PAGE — and its refusal is the meaningful one to count, the
     * signal that the model is inventing rather than reading.
     *
     * <p>The taxonomy check sits between them, and it sits HERE rather than only in the adapter
     * that happens to know it: the code is written to a column with no foreign key, so "the
     * adapter dropped it" is a promise made by every future adapter, one of which will forget.
     * Same defence in depth as the stage's own eligibility floor. It is read AFTER abstention
     * because {@code UNKNOWN} is the abstention token and never a {@code document_type} row —
     * checking membership first would report an honest "I could not tell" as an invented type.
     *
     * @param matcher the page's own matcher, built over its persisted spans in reading order
     * @param allowedTypeCodes the codes the request offered; null or empty believes nothing
     */
    public static Verdict verdict(
            String documentTypeCode,
            BigDecimal confidence,
            String quotedEvidenceText,
            AnchorMatcher matcher,
            BigDecimal minConfidence,
            Set<String> allowedTypeCodes) {
        if (documentTypeCode == null
                || documentTypeCode.isBlank()
                || UNKNOWN.equals(documentTypeCode)) {
            return new Verdict(Refusal.MODEL_ABSTAINED, null);
        }
        if (allowedTypeCodes == null || !allowedTypeCodes.contains(documentTypeCode)) {
            return new Verdict(Refusal.TYPE_NOT_IN_TAXONOMY, null);
        }
        if (confidence == null || confidence.compareTo(minConfidence) < 0) {
            // `>=` is a MINIMUM, the same reading PageClassifier gives a pack's min_confidence: a
            // score exactly at the configured floor qualifies.
            return new Verdict(Refusal.BELOW_MIN_CONFIDENCE, null);
        }
        QuoteEvidence evidence = verifyQuote(quotedEvidenceText, matcher);
        return evidence == null
                ? new Verdict(Refusal.QUOTE_UNVERIFIED, null)
                : new Verdict(Refusal.NONE, evidence);
    }

    /**
     * The quote, looked for in the page's joined reading-order text as a LITERAL anchor — so the
     * comparison is exactly the one a rule pack's literal anchor gets: the punctuation fold on both
     * sides, case-insensitive, no regex meaning read into the model's characters.
     *
     * <p>The single adjustment is to the model's own whitespace: leading, trailing and repeated
     * spaces in the quote collapse to one. That cannot lose a distinction the page makes, because
     * {@code SpanJoin} emits either nothing or exactly ONE space between spans — a RUN of
     * whitespace in a quote can only have come from the model's own formatting of its answer.
     * Beyond that the quote must be verbatim, which is precisely what the prompt demands: leniency
     * about the WORDS is how an invented quote gets accepted.
     *
     * @return null when the quote is blank or is not on the page
     */
    static QuoteEvidence verifyQuote(String quotedEvidenceText, AnchorMatcher matcher) {
        if (quotedEvidenceText == null) {
            return null;
        }
        String quote = quotedEvidenceText.replaceAll("\\s+", " ").trim();
        if (quote.isEmpty()) {
            // A model that quoted nothing has pointed at nothing. There is no "confident enough to
            // skip the evidence" case: the quote IS the evidence.
            return null;
        }
        AnchorMatch match =
                matcher.match(new Anchor("ai-quoted-evidence", AnchorKind.LITERAL, quote, 0, false));
        return match.matched()
                ? new QuoteEvidence(match.spanIds(), match.rangeStart(), match.rangeEnd())
                : null;
    }
}
