package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.PackageSplitter.AnchorRef;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.classification.rules.RulePack;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two pure halves of the form-boundary wire (Spec 5a, design D6/D8).
 *
 * <p>{@code boundaryAnchors} reads the loaded packs; {@code startsDocument} reads ONE page's
 * {@code classification_result.evidence} — the jsonb {@code PackageSplitter.split} already holds
 * in memory at zero extra query cost. Together they answer "did this page begin a new form?",
 * which {@code group()} then turns into a document boundary.
 *
 * <p>The evidence documents below are the literal shape {@code PageClassifier.evidenceJson}
 * writes: {@code anchors[]} entries carrying {@code packType}, {@code packVersion},
 * {@code anchorId}, {@code weight}, {@code spanIds}, {@code boxes} and {@code range}, plus a
 * {@code scores[]} array. On a WIN only the WINNING pack's matches are written, which is why a
 * boundary anchor is only ever visible on a page that pack actually won.
 */
class PackageSplitterBoundaryTest {

    private static Anchor anchor(String id, boolean startsDocument) {
        return new Anchor(id, AnchorKind.LITERAL, id, 5.0, startsDocument);
    }

    private static RulePack pack(String type, Anchor... anchors) {
        return new RulePack(type, "1.0.0", 0.6, 10.0, List.of(anchors));
    }

    /** One matched-anchor evidence document in PageClassifier's exact wire shape. */
    private static String evidence(String packType, String anchorId) {
        return """
               {"anchors":[{"packType":"%s","packVersion":"1.0.0","anchorId":"%s","weight":5.0,
                            "spanIds":[1,2],"boxes":[{"x":72.0,"y":42.0,"width":71.5,"height":12.0}],
                            "range":{"start":0,"end":21}}],
                "scores":[{"packType":"%s","packVersion":"1.0.0","score":0.9,
                           "minConfidence":0.6,"targetScore":10.0}]}
               """
                .formatted(packType, anchorId, packType);
    }

    @Test
    void only_anchors_that_declare_the_flag_become_boundary_anchors() {
        Set<AnchorRef> boundary =
                PackageSplitter.boundaryAnchors(
                        List.of(
                                pack("SCHEDULE_E", anchor("schedule-e-header", true), anchor("rents", false)),
                                pack("PAYSTUB", anchor("pay-period", false))));

        assertThat(boundary).containsExactly(new AnchorRef("SCHEDULE_E", "schedule-e-header"));
    }

    @Test
    void a_page_whose_evidence_names_a_boundary_anchor_starts_a_document() {
        Set<AnchorRef> boundary = Set.of(new AnchorRef("SCHEDULE_E", "schedule-e-header"));

        assertThat(
                        PackageSplitter.startsDocument(
                                evidence("SCHEDULE_E", "schedule-e-header"), boundary))
                .isTrue();
    }

    @Test
    void a_page_whose_evidence_names_only_ordinary_anchors_does_not() {
        Set<AnchorRef> boundary = Set.of(new AnchorRef("SCHEDULE_E", "schedule-e-header"));

        assertThat(PackageSplitter.startsDocument(evidence("SCHEDULE_E", "rents"), boundary))
                .isFalse();
    }

    @Test
    void the_SAME_anchor_id_under_a_DIFFERENT_pack_is_not_a_boundary() {
        // Anchor ids are pack-scoped: two packs may legitimately both name an anchor "header".
        // Keying the boundary set on the id alone would split a mortgage statement because a
        // Schedule E pack happened to reuse a word.
        Set<AnchorRef> boundary = Set.of(new AnchorRef("SCHEDULE_E", "header"));

        assertThat(PackageSplitter.startsDocument(evidence("MORTGAGE_STATEMENT", "header"), boundary))
                .isFalse();
    }

    @Test
    void no_pack_declares_a_boundary_so_no_page_can_start_one() {
        // The state of the world until T8 seeds the SCHEDULE_E pack: the mechanism ships dark
        // and every existing package splits exactly as it did before Spec 5a.
        assertThat(PackageSplitter.boundaryAnchors(List.of(pack("PAYSTUB", anchor("pay-period", false)))))
                .isEmpty();
        assertThat(PackageSplitter.startsDocument(evidence("PAYSTUB", "pay-period"), Set.of()))
                .isFalse();
    }

    @Test
    void absent_or_unreadable_evidence_never_claims_a_boundary() {
        // Evidence is derived data. A page with no current classification, or a blob that does
        // not parse, must degrade to TODAY's behaviour — the page joins its run — and must never
        // fail the SPLITTING stage or guess a boundary that is not proven.
        Set<AnchorRef> boundary = Set.of(new AnchorRef("SCHEDULE_E", "schedule-e-header"));

        assertThat(PackageSplitter.startsDocument(null, boundary)).isFalse();
        assertThat(PackageSplitter.startsDocument("   ", boundary)).isFalse();
        assertThat(PackageSplitter.startsDocument("{\"anchors\": ", boundary)).isFalse();
        assertThat(PackageSplitter.startsDocument("{\"scores\":[]}", boundary)).isFalse();
    }
}
