package com.pragmaticds.docengine.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.match.AnchorMatch;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.classification.rules.RulePackLoader;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classifies ONE page: every applicable rule pack scores it; packs that clear their OWN
 * threshold qualify, the highest qualifier wins, and the result persists append-only with
 * anchor evidence.
 *
 * <p>Plan acceptance criterion 3 is the heart of {@link #decide}: an exact tie between top scores
 * or nothing above threshold lands {@code UNKNOWN} — the classifier NEVER guesses to avoid an
 * unknown. An UNKNOWN result still records the best loser's confidence and the weak matches of
 * EVERY pack, so a reviewer sees exactly why the page was ambiguous instead of a bare zero.
 *
 * <p>The evidence document carries anchor ids, weights, span ids, boxes, and matched-text OFFSETS
 * in the joined reading-order text — never the text itself (Phase 4 rule 3).
 */
@Service
public class PageClassifier {

    /** The type code recorded when no pack wins. Seeded as a built-in document_type in V6. */
    public static final String UNKNOWN = "UNKNOWN";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RulePackLoader loader;
    private final ClassificationResultRepository results;

    public PageClassifier(RulePackLoader loader, ClassificationResultRepository results) {
        this.loader = loader;
        this.results = results;
    }

    /** One pack's outcome on one page: the capped score and its matched anchors (hits only). */
    public record PackEvaluation(RulePack pack, double score, List<AnchorMatch> matches) {}

    /**
     * @param winner the winning evaluation, null when the decision is UNKNOWN
     * @param coQualifyingTypes every type whose pack cleared its OWN threshold on this page, in
     *     code order, when MORE THAN ONE did — otherwise empty. See {@link #decide}.
     */
    public record Decision(
            String documentTypeCode,
            double confidence,
            PackEvaluation winner,
            List<PackEvaluation> evaluations,
            List<String> coQualifyingTypes) {}

    /**
     * Classifies the page against every applicable pack and persists the result, superseding any
     * prior current PAGE result for it. Blank and duplicate pages never reach here — the
     * CLASSIFYING stage skips them entirely.
     *
     * @param spansInReadingOrder the page's persisted spans ordered by {@code ordinal}
     */
    @Transactional
    public ClassificationResult classify(Page page, List<TextSpan> spansInReadingOrder) {
        List<AnchorSpan> anchorSpans =
                spansInReadingOrder.stream()
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
        AnchorMatcher matcher = AnchorMatcher.forSpans(anchorSpans);

        List<PackEvaluation> evaluations =
                loader.activePacksForCurrentOrg().stream()
                        .map(pack -> evaluate(pack, matcher))
                        .toList();
        Decision decision = decide(evaluations);

        results.supersedeCurrent(TenantContext.require(), ClassificationResult.SUBJECT_PAGE, page.getId());
        ClassificationResult result =
                new ClassificationResult(
                        ClassificationResult.SUBJECT_PAGE,
                        page.getId(),
                        decision.documentTypeCode(),
                        BigDecimal.valueOf(decision.confidence()).setScale(4, RoundingMode.HALF_UP),
                        ClassificationResult.METHOD_RULE_ANCHOR,
                        decision.winner() == null ? null : decision.winner().pack().version(),
                        evidenceJson(decision));
        return results.save(result);
    }

    /** Score = min(1, sum(matched weights) / targetScore) — the V6 pack-format contract. */
    static PackEvaluation evaluate(RulePack pack, AnchorMatcher matcher) {
        List<AnchorMatch> matches = new ArrayList<>();
        double matchedWeight = 0;
        for (Anchor anchor : pack.anchors()) {
            AnchorMatch match = matcher.match(anchor);
            if (match.matched()) {
                matches.add(match);
                matchedWeight += anchor.weight();
            }
        }
        double score = Math.min(1.0, matchedWeight / pack.targetScore());
        return new PackEvaluation(pack, score, List.copyOf(matches));
    }

    /**
     * <b>Co-qualification is a signal, not noise (Phase B, roadmap B4).</b> Two packs BOTH clearing
     * their own thresholds on one page is the engine's only cheap evidence of a sheet carrying more
     * than one document — the processor's photocopy with a driver's licence and a Social Security
     * card side by side, two small receipts scanned together, a VOE with a paystub behind it on the
     * same pass. {@code CrossConfusionIT} makes it meaningful: it fails the build if any type's
     * fixture pages qualify under another type's pack, so on a clean single-document page
     * co-qualification does not happen by construction, and on a real page it is worth reporting.
     *
     * <p>It deliberately does NOT change the verdict. The engine cannot split within a page — every
     * mechanism it has cuts BETWEEN pages (design D8) — so the highest qualifier still wins and the
     * page still belongs to exactly one document. What changes is that the second document stops
     * being invisible: today its spans are captured, boxed and persisted, and nothing anywhere
     * says a reviewer should look. That silence is the failure, not the missing split.
     */
    static Decision decide(List<PackEvaluation> evaluations) {
        if (evaluations.isEmpty()) {
            return new Decision(UNKNOWN, 0.0, null, List.of(), List.of());
        }
        // The documented rule, implemented literally (Phase 4 review): QUALIFY first —
        // a pack is in the running only if it cleared ITS OWN threshold — then the
        // highest qualifier wins. The old shape picked the global best score and
        // checked only that pack's threshold, so a strict pack scoring 0.7 against
        // its 0.8 bar wrongly blocked a lenient pack that legitimately cleared 0.6.
        //
        // DECISION (2026-08-08, V10): `>=` stays `>=`. An exact tie at the threshold
        // QUALIFIES, and that is deliberate — it is not an oversight left in place.
        //   * `>=` is the documented contract everywhere else: "at or above ITS OWN
        //     pack's threshold wins", and min_confidence is a MINIMUM, which is the
        //     plain reading an operator tuning a pack will assume.
        //   * The case that prompted the question — a Form 1040 scoring exactly 0.60
        //     against the W2 pack's 0.60 — was a MIS-WEIGHTED PACK, not a boundary
        //     bug (the pack's two heaviest anchors were not W-2-exclusive; V10 fixes
        //     that as data). Flipping the comparator would have hidden that pack's
        //     defect while silently shifting EVERY pack's effective bar by an epsilon.
        //   * A pack that genuinely wants a strict bar expresses it as data —
        //     min_confidence 0.6001 — which keeps thresholds tunable without a deploy,
        //     the whole point of packs-as-data (D7/D15).
        //   * Exact ties are an artifact of which integer weights happen to divide
        //     evenly into targetScore, so `>` would not make the classifier more
        //     correct; it would only move an arbitrary line to a different place.
        // Pinned by PageClassifierDecisionTest#a_score_exactly_at_the_threshold_qualifies.
        List<PackEvaluation> qualifiers =
                evaluations.stream()
                        .filter(e -> e.score() > 0 && e.score() >= e.pack().minConfidence())
                        .toList();
        double bestOverall =
                evaluations.stream().mapToDouble(PackEvaluation::score).max().orElse(0.0);
        // Reported whatever the verdict turns out to be, including the ambiguous-tie UNKNOWN
        // below: "no type won" and "two documents are on this sheet" are different problems and a
        // reviewer needs to tell them apart.
        List<String> coQualifying =
                qualifiers.size() > 1
                        ? qualifiers.stream()
                                .map(e -> e.pack().documentTypeCode())
                                .distinct()
                                .sorted()
                                .toList()
                        : List.<String>of();
        if (qualifiers.isEmpty()) {
            // Never a bare unknown: the best loser's confidence and every pack's weak
            // matches ride along as the reviewer's explanation.
            return new Decision(UNKNOWN, bestOverall, null, evaluations, List.of());
        }
        double bestQualifying =
                qualifiers.stream().mapToDouble(PackEvaluation::score).max().orElse(0.0);
        List<PackEvaluation> top =
                qualifiers.stream().filter(e -> e.score() == bestQualifying).toList();
        if (top.size() > 1) {
            // An exact tie between qualifying packs is ambiguity, not a coin flip.
            return new Decision(UNKNOWN, bestQualifying, null, evaluations, coQualifying);
        }
        PackEvaluation winner = top.get(0);
        return new Decision(
                winner.pack().documentTypeCode(), winner.score(), winner, evaluations, coQualifying);
    }

    /**
     * The evidence jsonb: matched anchors (winner's on a win, EVERY pack's on UNKNOWN) plus the
     * full score breakdown. Ids, weights, boxes, offsets — no text.
     */
    static String evidenceJson(Decision decision) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode anchors = root.putArray("anchors");
        List<PackEvaluation> evidenceSource =
                decision.winner() != null ? List.of(decision.winner()) : decision.evaluations();
        for (PackEvaluation evaluation : evidenceSource) {
            for (AnchorMatch match : evaluation.matches()) {
                ObjectNode node = anchors.addObject();
                node.put("packType", evaluation.pack().documentTypeCode());
                node.put("packVersion", evaluation.pack().version());
                node.put("anchorId", match.anchorId());
                node.put("weight", weightOf(evaluation.pack(), match.anchorId()));
                ArrayNode spanIds = node.putArray("spanIds");
                match.spanIds().forEach(spanIds::add);
                ArrayNode boxes = node.putArray("boxes");
                for (Box box : match.boxes()) {
                    ObjectNode boxNode = boxes.addObject();
                    boxNode.put("x", box.x());
                    boxNode.put("y", box.y());
                    boxNode.put("width", box.width());
                    boxNode.put("height", box.height());
                }
                ObjectNode range = node.putObject("range");
                range.put("start", match.rangeStart());
                range.put("end", match.rangeEnd());
            }
        }
        // Derivable from `scores` in principle — score >= minConfidence, per pack — but only if
        // every reader re-implements the qualification predicate. Written explicitly so the
        // predicate lives in exactly one place, the same reason boundary provenance is decided
        // inside the splitter rather than reconstructed from the rows afterwards.
        if (!decision.coQualifyingTypes().isEmpty()) {
            ArrayNode coQualifying = root.putArray("coQualifyingTypes");
            decision.coQualifyingTypes().forEach(coQualifying::add);
        }
        ArrayNode scores = root.putArray("scores");
        for (PackEvaluation evaluation : decision.evaluations()) {
            ObjectNode node = scores.addObject();
            node.put("packType", evaluation.pack().documentTypeCode());
            node.put("packVersion", evaluation.pack().version());
            node.put("score", evaluation.score());
            node.put("minConfidence", evaluation.pack().minConfidence());
            node.put("targetScore", evaluation.pack().targetScore());
        }
        return root.toString();
    }

    private static double weightOf(RulePack pack, String anchorId) {
        return pack.anchors().stream()
                .filter(anchor -> anchor.id().equals(anchorId))
                .mapToDouble(Anchor::weight)
                .findFirst()
                .orElse(0);
    }
}
