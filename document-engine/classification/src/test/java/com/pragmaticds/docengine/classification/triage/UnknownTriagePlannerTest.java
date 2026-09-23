package com.pragmaticds.docengine.classification.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.TriageDocument;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.TriageItem;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.TriagePage;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.WeakAnchor;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The triage rule, pinned without a database — the same reason {@code PackageSplitterGroupingTest}
 * exercises {@code group()} directly. What these assert is the difference between a queue a
 * reviewer trusts and one they stop opening: which runs surface, which pack came closest, and
 * which anchors argue for it.
 */
class UnknownTriagePlannerTest {

    private static final BigDecimal MARGIN = new BigDecimal("0.10");

    /** Evidence in the exact shape {@code PageClassifier.evidenceJson} writes for an UNKNOWN. */
    private static String evidence(String scores, String anchors) {
        return "{\"anchors\":[" + anchors + "],\"scores\":[" + scores + "]}";
    }

    private static String score(String packType, String value, String minConfidence) {
        return "{\"packType\":\""
                + packType
                + "\",\"packVersion\":\"1\",\"score\":"
                + value
                + ",\"minConfidence\":"
                + minConfidence
                + ",\"targetScore\":10}";
    }

    private static String anchor(String packType, String anchorId, String weight) {
        return "{\"packType\":\""
                + packType
                + "\",\"packVersion\":\"1\",\"anchorId\":\""
                + anchorId
                + "\",\"weight\":"
                + weight
                + ",\"spanIds\":[],\"boxes\":[],\"range\":{\"start\":0,\"end\":1}}";
    }

    private static TriagePage page(int index, String type, String evidenceJson) {
        return new TriagePage(UUID.randomUUID(), index, type, evidenceJson);
    }

    private static TriageDocument document(int ordinal, String type, TriagePage... pages) {
        return new TriageDocument(UUID.randomUUID(), ordinal, type, List.of(pages));
    }

    @Test
    void a_clean_package_has_nothing_to_triage() {
        List<TriageDocument> documents =
                List.of(
                        document(0, "PAYSTUB", page(0, "PAYSTUB", null), page(1, "PAYSTUB", null)),
                        document(1, "W2", page(2, "W2", null)));

        assertThat(UnknownTriagePlanner.plan(documents, MARGIN)).isEmpty();
    }

    @Test
    void a_document_typed_UNKNOWN_end_to_end_is_one_item_over_all_its_pages() {
        TriageDocument unknown =
                document(0, "UNKNOWN", page(0, "UNKNOWN", null), page(1, "UNKNOWN", null));

        List<TriageItem> items = UnknownTriagePlanner.plan(List.of(unknown), MARGIN);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).reason())
                .isEqualTo(UnknownTriagePlanner.REASON_WHOLE_UNKNOWN_DOCUMENT);
        assertThat(items.get(0).startPackagePageIndex()).isZero();
        assertThat(items.get(0).endPackagePageIndex()).isEqualTo(1);
        assertThat(items.get(0).pageIds()).hasSize(2);
    }

    /**
     * The absorption {@code PackageSplitter.group} documents and accepts — "a real loss" — is
     * exactly what the queue exists to surface. This is the shape the combined fixture produces:
     * eight untyped letter pages riding inside a W-2.
     */
    @Test
    void untyped_pages_inside_a_typed_document_surface_as_an_absorbed_run() {
        TriageDocument w2 =
                document(
                        0,
                        "W2",
                        page(0, "W2", null),
                        page(1, "UNKNOWN", null),
                        page(2, "UNKNOWN", null));

        List<TriageItem> items = UnknownTriagePlanner.plan(List.of(w2), MARGIN);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).reason())
                .isEqualTo(UnknownTriagePlanner.REASON_ABSORBED_UNKNOWN_RUN);
        assertThat(items.get(0).documentTypeCode()).isEqualTo("W2");
        assertThat(items.get(0).startPackagePageIndex()).isEqualTo(1);
        assertThat(items.get(0).endPackagePageIndex()).isEqualTo(2);
    }

    @Test
    void two_untyped_stretches_split_by_a_typed_page_are_two_items() {
        TriageDocument document =
                document(
                        0,
                        "W2",
                        page(0, "UNKNOWN", null),
                        page(1, "W2", null),
                        page(2, "UNKNOWN", null));

        assertThat(UnknownTriagePlanner.plan(List.of(document), MARGIN)).hasSize(2);
    }

    /**
     * A page the classifier never judged degrades to untyped, the same rule
     * {@code PackageSplitter.group} applies: no verdict carries strictly less information than a
     * verdict of UNKNOWN, so it cannot be triaged less aggressively either.
     */
    @Test
    void a_page_with_no_classification_at_all_counts_as_untyped() {
        TriageDocument document = document(0, "W2", page(0, "W2", null), page(1, null, null));

        assertThat(UnknownTriagePlanner.plan(List.of(document), MARGIN))
                .singleElement()
                .satisfies(item -> assertThat(item.startPackagePageIndex()).isEqualTo(1));
    }

    @Test
    void the_best_loser_is_the_highest_scoring_pack_anywhere_in_the_run() {
        TriageDocument document =
                document(
                        0,
                        "UNKNOWN",
                        page(0, "UNKNOWN", evidence(score("W2", "0.20", "0.60"), "")),
                        page(1, "UNKNOWN", evidence(score("VOE", "0.55", "0.60"), "")));

        TriageItem item = UnknownTriagePlanner.plan(List.of(document), MARGIN).get(0);

        assertThat(item.bestLoser().documentTypeCode()).isEqualTo("VOE");
        assertThat(item.bestLoser().packagePageIndex()).isEqualTo(1);
        assertThat(item.bestLoser().shortfall()).isEqualByComparingTo("0.05");
        assertThat(item.nearMiss()).isTrue();
    }

    /** Five points under a 0.60 bar is a pack fix; forty points under is a missing pack. */
    @Test
    void a_pack_that_missed_by_more_than_the_margin_is_not_a_near_miss() {
        TriageDocument document =
                document(0, "UNKNOWN", page(0, "UNKNOWN", evidence(score("W2", "0.20", "0.60"), "")));

        TriageItem item = UnknownTriagePlanner.plan(List.of(document), MARGIN).get(0);

        assertThat(item.bestLoser().shortfall()).isEqualByComparingTo("0.40");
        assertThat(item.nearMiss()).isFalse();
    }

    /**
     * The ambiguous-tie UNKNOWN: both packs cleared their own thresholds and
     * {@code PageClassifier.decide} refused to flip a coin. The shortfall goes NEGATIVE, which is
     * the reviewer's cue that the engine had two answers rather than none.
     */
    @Test
    void an_ambiguous_tie_reads_as_a_negative_shortfall_and_a_near_miss() {
        TriageDocument document =
                document(
                        0,
                        "UNKNOWN",
                        page(
                                0,
                                "UNKNOWN",
                                evidence(
                                        score("VOE", "0.80", "0.60")
                                                + ","
                                                + score("W2", "0.80", "0.60"),
                                        "")));

        TriageItem item = UnknownTriagePlanner.plan(List.of(document), MARGIN).get(0);

        assertThat(item.bestLoser().shortfall()).isEqualByComparingTo("-0.20");
        assertThat(item.nearMiss()).isTrue();
        // Deterministic tie-break on the type code, so the queue does not reshuffle per call.
        assertThat(item.bestLoser().documentTypeCode()).isEqualTo("VOE");
    }

    @Test
    void a_pack_that_matched_nothing_is_an_absence_not_a_best_loser() {
        TriageDocument document =
                document(0, "UNKNOWN", page(0, "UNKNOWN", evidence(score("W2", "0.0", "0.60"), "")));

        TriageItem item = UnknownTriagePlanner.plan(List.of(document), MARGIN).get(0);

        assertThat(item.bestLoser()).isNull();
        assertThat(item.nearMiss()).isFalse();
    }

    /**
     * An anchor seen on every page of the run outranks a heavier anchor seen once: the reviewer is
     * deciding what the RUN is, and a repeated header is the strongest cheap evidence of one form.
     */
    @Test
    void weak_anchors_count_pages_and_the_most_widespread_ranks_first() {
        String repeated = anchor("VOE", "voe.header", "2");
        String heavyOnce = anchor("W2", "w2.box1", "6");
        TriageDocument document =
                document(
                        0,
                        "UNKNOWN",
                        // The same anchor twice on one page is ONE page's worth of evidence.
                        page(0, "UNKNOWN", evidence(score("VOE", "0.2", "0.6"), repeated + "," + repeated)),
                        page(1, "UNKNOWN", evidence(score("VOE", "0.2", "0.6"), repeated + "," + heavyOnce)));

        List<WeakAnchor> anchors =
                UnknownTriagePlanner.plan(List.of(document), MARGIN).get(0).weakAnchors();

        assertThat(anchors).hasSize(2);
        assertThat(anchors.get(0).anchorId()).isEqualTo("voe.header");
        assertThat(anchors.get(0).pageCount()).isEqualTo(2);
        assertThat(anchors.get(1).anchorId()).isEqualTo("w2.box1");
        assertThat(anchors.get(1).pageCount()).isEqualTo(1);
    }

    @Test
    void near_misses_sort_ahead_of_signal_free_runs() {
        TriageDocument signalFree = document(0, "UNKNOWN", page(0, "UNKNOWN", null));
        TriageDocument nearMiss =
                document(1, "UNKNOWN", page(5, "UNKNOWN", evidence(score("W2", "0.55", "0.60"), "")));

        List<TriageItem> items =
                UnknownTriagePlanner.plan(List.of(signalFree, nearMiss), MARGIN);

        assertThat(items).extracting(TriageItem::startPackagePageIndex).containsExactly(5, 0);
    }

    /**
     * Evidence is DERIVED data: a bad row costs its own signal, never the reviewer's whole queue.
     */
    @Test
    void unparseable_evidence_still_lists_the_item_with_no_signal() {
        TriageDocument document = document(0, "UNKNOWN", page(0, "UNKNOWN", "{not json"));

        TriageItem item = UnknownTriagePlanner.plan(List.of(document), MARGIN).get(0);

        assertThat(item.bestLoser()).isNull();
        assertThat(item.weakAnchors()).isEmpty();
    }
}
