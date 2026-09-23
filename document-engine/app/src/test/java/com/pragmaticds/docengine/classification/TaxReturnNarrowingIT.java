package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pragmaticds.docengine.classification.PageClassifier.PackEvaluation;
import com.pragmaticds.docengine.classification.match.AnchorMatch;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Spec 5a narrowing of the TAX_RETURN pack, scored against the MIGRATED pack.
 *
 * <p>The defect: {@code schedule-form-1040}, regex {@code (?i)Schedule \d \(Form 1040\)}, weight
 * 5. The {@code \d} means it never matched {@code SCHEDULE E (Form 1040)} — it fired on the
 * CROSS-REFERENCE {@code Schedule 1 (Form 1040), line 5} that a lettered schedule prints inside
 * its own instructions, scoring 5 + 2 = 0.70 and qualifying TAX_RETURN on a document that is not
 * a tax return. The same 0.70 appears on Schedule C. A reference to a form is not evidence of
 * being that form — the V10 lesson, this time inside TAX_RETURN's own pack.
 *
 * <p>The fix (V13, tax_return@1.1.0) kept NUMBERED schedules classifying via their own printed
 * titles and demoted the citation anchor to weight 0: still recorded as evidence a reviewer can
 * see, worth nothing toward the score.
 *
 * <p>Issue #60 (V46, tax_return@1.2.0) narrows again: Schedules 1 and 2 are their OWN types now,
 * so their titles leave this pack and a numbered-schedule page scores the same 0.20 a lettered one
 * does. Only Schedule 3's title remains, at V13's weight — out of that issue's scope, and a title
 * dropped without a type to catch it would turn a typed page into an absorbed one.
 *
 * <p>This class lives in {@code com.pragmaticds.docengine.classification} so it can call the
 * package-private {@code PageClassifier.evaluate} — the convention {@code CrossConfusionIT}
 * already uses.
 */
class TaxReturnNarrowingIT extends AbstractClassificationIT {

    /** Words become spans in reading order, exactly as AnchorMatcher joins them. */
    private static AnchorMatcher matcherFor(String... words) {
        List<AnchorSpan> spans = new ArrayList<>();
        long id = 1;
        double x = 72.0;
        for (String word : words) {
            spans.add(
                    new AnchorSpan(
                            id++,
                            word,
                            BigDecimal.valueOf(x),
                            new BigDecimal("42.0"),
                            new BigDecimal("30.0"),
                            new BigDecimal("12.0")));
            x += 36.0;
        }
        return AnchorMatcher.forSpans(spans);
    }

    private RulePack taxReturnPack() {
        rulePackLoader.invalidateAll();
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals("TAX_RETURN"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active TAX_RETURN pack for the dev org"));
    }

    /**
     * A LETTERED schedule's page: its own header, the Treasury line WITHOUT the em dash (which is
     * how the real form prints it, so {@code treasury-irs} legitimately misses), and the Part I
     * cross-reference to a numbered schedule that used to score 5.
     */
    private static AnchorMatcher letteredSchedulePage() {
        return matcherFor(
                "SCHEDULE", "E", "(Form", "1040)",
                "Department", "of", "the", "Treasury", "Internal", "Revenue", "Service",
                "Supplemental", "Income", "and", "Loss",
                "Attachment", "Sequence", "No.", "13",
                "Part", "I", "Income", "or", "Loss", "From", "Rental", "Real", "Estate",
                "also", "enter", "this", "amount", "on", "Schedule", "1", "(Form", "1040),",
                "line", "5.");
    }

    /** A NUMBERED schedule's page: header, its own printed TITLE, and the attach instruction. */
    private static AnchorMatcher numberedSchedulePage() {
        return matcherFor(
                "SCHEDULE", "2", "(Form", "1040)",
                "Additional", "Taxes",
                "Department", "of", "the", "Treasury", "Internal", "Revenue", "Service",
                "Attach", "to", "Form", "1040,", "1040-SR,", "1040-NR,", "or", "1040-SS.",
                "Attachment", "Sequence", "No.", "02");
    }

    @Test
    void the_active_TAX_RETURN_pack_is_the_narrowed_version() {
        assertThat(taxReturnPack().version()).isEqualTo("1.2.0");
    }

    @Test
    void a_LETTERED_schedule_that_merely_CITES_a_numbered_one_never_qualifies_TAX_RETURN() {
        RulePack pack = taxReturnPack();
        PackEvaluation evaluation = PageClassifier.evaluate(pack, letteredSchedulePage());

        // 0 (the demoted citation) + 2 (the literal "Form 1040" inside the page's own header).
        assertThat(evaluation.score()).isCloseTo(0.20, within(1e-9));
        // The EXACT predicate from PageClassifier.decide, including the deliberate >=.
        assertThat(evaluation.score()).isLessThan(pack.minConfidence());
    }

    @Test
    void the_citation_anchor_is_still_RECORDED_as_evidence_and_still_counts_for_nothing() {
        RulePack pack = taxReturnPack();

        // Weight 0, not deleted: the reviewer's evidence document still shows that the page
        // cited a numbered schedule, which is true and worth seeing. What changed is that it
        // no longer PROVES anything, because weight is exclusivity and this anchor has none.
        assertThat(pack.anchors())
                .filteredOn(anchor -> anchor.id().equals("schedule-form-1040"))
                .singleElement()
                .extracting(Anchor::weight)
                .isEqualTo(0.0);

        assertThat(PageClassifier.evaluate(pack, letteredSchedulePage()).matches())
                .extracting(AnchorMatch::anchorId)
                .contains("schedule-form-1040");
    }

    @Test
    void a_SCHEDULE_2_page_no_longer_qualifies_TAX_RETURN() {
        // Issue #60 reverses V13's recall half for Schedules 1 and 2: their titles left this
        // pack because they have packs of their own, so the page scores only the 2 that "Form
        // 1040" in the attach line is worth — the same 0.20 a lettered schedule scores — and
        // classifies SCHEDULE_2 instead of folding into the 1040 in front of it.
        RulePack pack = taxReturnPack();
        PackEvaluation evaluation = PageClassifier.evaluate(pack, numberedSchedulePage());

        assertThat(evaluation.score()).isCloseTo(0.20, within(1e-9));
        assertThat(evaluation.score()).isLessThan(pack.minConfidence());
    }

    @Test
    void the_remaining_schedule_3_title_anchor_cannot_qualify_the_pack_on_its_own() {
        // Exclusivity applies to the surviving title anchor too: a page printing Schedule 3's
        // title and nothing else 1040-shaped reaches 5 = 0.50 < 0.60 and does not qualify.
        RulePack pack = taxReturnPack();
        PackEvaluation evaluation =
                PageClassifier.evaluate(
                        pack, matcherFor("Additional", "Credits", "and", "Payments", "Worksheet"));

        assertThat(evaluation.score()).isCloseTo(0.50, within(1e-9));
        assertThat(evaluation.score()).isLessThan(pack.minConfidence());
    }
}
