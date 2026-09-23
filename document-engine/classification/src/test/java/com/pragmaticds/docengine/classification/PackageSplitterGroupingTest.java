package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.PackageSplitter.DocumentGroup;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.PackageSplitter.PageForSplit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The grouping core: maximal consecutive runs of the same type become one logical document;
 * blank and duplicate pages are TRANSPARENT — they neither join a document nor break a run —
 * and a document's confidence is the MINIMUM of its member pages' confidences (a chain is as
 * strong as its weakest link, and review sorts by exactly this number).
 *
 * <p>Spec 5a adds ONE more break condition: a page that matched a pack-declared
 * {@code startsDocument} anchor begins a new document even when the type has not changed. A
 * borrower with four rentals files TWO Schedule Es, and without this the splitter merges them
 * into one four-page document where even column-suffixed field names would collide.
 */
class PackageSplitterGroupingTest {

    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();
    private static final UUID P3 = UUID.randomUUID();
    private static final UUID P4 = UUID.randomUUID();

    private static PageForSplit typed(UUID pageId, String type, String confidence) {
        return new PageForSplit(pageId, false, false, type, new BigDecimal(confidence), false);
    }

    /** A page the classifier looked at and could give no type — the real-document majority. */
    private static PageForSplit untyped(UUID pageId, String confidence) {
        return typed(pageId, PageClassifier.UNKNOWN, confidence);
    }

    /** A page carrying a form header the pack declared as {@code startsDocument}. */
    private static PageForSplit header(UUID pageId, String type, String confidence) {
        return new PageForSplit(pageId, false, false, type, new BigDecimal(confidence), true);
    }

    private static PageForSplit blank(UUID pageId) {
        return new PageForSplit(pageId, true, false, null, null, false);
    }

    private static PageForSplit duplicate(UUID pageId) {
        return new PageForSplit(pageId, false, true, null, null, false);
    }

    @Test
    void consecutive_same_type_pages_group_into_one_document() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                typed(P2, "PAYSTUB", "0.8"),
                                typed(P3, "W2", "1.0")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).typeCode()).isEqualTo("PAYSTUB");
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
        assertThat(groups.get(1).typeCode()).isEqualTo("W2");
        assertThat(groups.get(1).pageIds()).containsExactly(P3);
    }

    @Test
    void a_blank_page_between_two_paystub_pages_does_not_split_the_document() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "PAYSTUB", "0.9"), blank(P2), typed(P3, "PAYSTUB", "0.8")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P3);
    }

    @Test
    void a_duplicate_page_is_transparent_exactly_like_a_blank_one() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "W2", "1.0"), duplicate(P2), typed(P3, "W2", "0.7")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P3);
    }

    @Test
    void unknown_pages_group_together_too() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "UNKNOWN", "0.2"),
                                typed(P2, "UNKNOWN", "0.0"),
                                typed(P3, "PAYSTUB", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).typeCode()).isEqualTo("UNKNOWN");
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
    }

    @Test
    void document_confidence_is_the_minimum_of_its_member_pages() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "BANK_STATEMENT", "1.0"),
                                typed(P2, "BANK_STATEMENT", "0.8"),
                                typed(P3, "BANK_STATEMENT", "0.95")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).confidence()).isEqualByComparingTo("0.8");
    }

    @Test
    void a_package_of_only_blank_and_duplicate_pages_yields_no_documents() {
        assertThat(PackageSplitter.group(List.of(blank(P1), duplicate(P2)))).isEmpty();
    }

    @Test
    void a_classifiable_page_without_a_current_result_lands_UNKNOWN_at_zero_confidence() {
        // Defensive: SPLITTING should always run after CLASSIFYING, but a page that somehow has
        // no current result must not crash the split. It degrades to UNKNOWN at zero confidence
        // — and to UNKNOWN's BEHAVIOUR with it, because no verdict carries strictly less
        // information than a verdict of UNKNOWN and must not cut a document more aggressively
        // than one would.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                new PageForSplit(P1, false, false, null, null, false),
                                typed(P2, "PAYSTUB", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).typeCode()).isEqualTo("UNKNOWN");
        assertThat(groups.get(0).confidence()).isEqualByComparingTo("0");
        assertThat(groups.get(0).pageIds()).containsExactly(P1);
    }

    @Test
    void a_page_without_a_current_result_continues_an_open_run_exactly_like_an_UNKNOWN_one() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                new PageForSplit(P2, false, false, null, null, false)));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).typeCode()).isEqualTo("PAYSTUB");
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
        assertThat(groups.get(0).confidence())
                .as("a page with no verdict is not evidence against the run's type")
                .isEqualByComparingTo("0.9");
    }

    @Test
    void the_same_type_reappearing_after_another_type_is_a_new_document() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                typed(P2, "W2", "1.0"),
                                typed(P3, "PAYSTUB", "0.8"),
                                typed(P4, "PAYSTUB", "0.7")));

        assertThat(groups)
                .extracting(DocumentGroup::typeCode)
                .containsExactly("PAYSTUB", "W2", "PAYSTUB");
        assertThat(groups.get(2).pageIds()).containsExactly(P3, P4);
    }

    @Test
    void a_second_form_header_starts_a_new_document_even_though_the_type_never_changes() {
        // The case Spec 5a exists for: a borrower with four rentals files TWO Schedule Es. Both
        // forms classify SCHEDULE_E, so the type-change rule alone yields ONE four-page
        // document — and then form 1's column A and form 2's column A are the same field name
        // with the same group key in the same document.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                header(P1, "SCHEDULE_E", "0.9"),
                                typed(P2, "SCHEDULE_E", "0.8"),
                                header(P3, "SCHEDULE_E", "0.9"),
                                typed(P4, "SCHEDULE_E", "0.7")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
        assertThat(groups.get(1).pageIds()).containsExactly(P3, P4);
        assertThat(groups)
                .extracting(DocumentGroup::typeCode)
                .containsExactly("SCHEDULE_E", "SCHEDULE_E");
    }

    @Test
    void a_two_page_form_whose_header_is_only_on_page_one_stays_ONE_document() {
        // The regression that proves the rule keys on the HEADER, not on page count. Schedule E
        // is a two-sided form: Part I is page 1, Parts II-V are page 2. If this ever splits,
        // every multi-page form in the corpus fragments into one document per page.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(header(P1, "SCHEDULE_E", "0.9"), typed(P2, "SCHEDULE_E", "0.8")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
    }

    @Test
    void a_form_header_on_the_packages_very_first_page_emits_no_empty_leading_document() {
        // The off-by-one that would show up as a phantom zero-page document at ordinal 0.
        List<DocumentGroup> groups = PackageSplitter.group(List.of(header(P1, "SCHEDULE_E", "0.9")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1);
        assertThat(groups.get(0).confidence()).isEqualByComparingTo("0.9");
    }

    @Test
    void a_form_header_that_also_changes_type_splits_exactly_once() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "PAYSTUB", "0.9"), header(P2, "SCHEDULE_E", "0.8")));

        assertThat(groups)
                .extracting(DocumentGroup::typeCode)
                .containsExactly("PAYSTUB", "SCHEDULE_E");
        assertThat(groups.get(1).pageIds()).containsExactly(P2);
    }

    @Test
    void each_form_carries_the_MINIMUM_of_its_OWN_pages_not_the_packages() {
        // The run's confidence must RESET at a header boundary, or a clean second form inherits
        // the first form's worst page and sorts wrongly in review.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                header(P1, "SCHEDULE_E", "0.9"),
                                typed(P2, "SCHEDULE_E", "0.7"),
                                header(P3, "SCHEDULE_E", "0.95"),
                                typed(P4, "SCHEDULE_E", "0.8")));

        assertThat(groups.get(0).confidence()).isEqualByComparingTo("0.7");
        assertThat(groups.get(1).confidence()).isEqualByComparingTo("0.8");
    }

    @Test
    void a_duplicate_page_carrying_a_form_header_is_still_transparent() {
        // Design D8's neighbouring limit, pinned rather than discovered. A page marked blank or
        // duplicate never reaches the classifier at all, so it has no classification_result and
        // no anchor evidence — a form header on a page the deduper collapsed is INVISIBLE to
        // this rule. This test constructs the flag anyway to prove transparency wins: a
        // transparent page neither joins a document nor breaks a run, whatever it claims.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "SCHEDULE_E", "0.9"),
                                new PageForSplit(P2, false, true, "SCHEDULE_E", new BigDecimal("0.9"), true),
                                typed(P3, "SCHEDULE_E", "0.8")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P3);
    }

    // ── an untyped page is a CONTINUATION, never a document start ───────────
    //
    // Measured on the product owner's real documents, not imagined: a 2-page Chase statement
    // split into 2 documents because the reverse side is error-resolution boilerplate carrying
    // no type signal, and a 26-page tax return split into 7 because every unclassifiable page
    // between typed pages severed the run. Real multi-page documents routinely carry pages with
    // no type signal — terms, notices, continuation tables, blank backs, worksheets.

    @Test
    void an_untyped_page_after_a_typed_one_continues_the_SAME_document() {
        // The bank statement, exactly: page 1 is the account, page 2 the boilerplate reverse.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "BANK_STATEMENT", "0.95"), untyped(P2, "0.10")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).typeCode()).isEqualTo("BANK_STATEMENT");
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
    }

    @Test
    void the_tax_returns_interleaved_typed_and_untyped_pages_are_ONE_document() {
        // The 26-page return's measured classification, in shape:
        //   p0 UNKNOWN(0.00) p1 TAX_RETURN(1.00) p2 UNKNOWN(0.20) p3 TAX_RETURN(0.70)
        //   p4 UNKNOWN(0.20) p5 TAX_RETURN(0.70) p6..p25 UNKNOWN
        // Today that is SEVEN documents. It is one return with a cover page in front of it.
        List<UUID> ids = new ArrayList<>();
        List<PageForSplit> pages = new ArrayList<>();
        for (int index = 0; index < 26; index++) {
            ids.add(UUID.randomUUID());
        }
        pages.add(untyped(ids.get(0), "0.00"));
        pages.add(typed(ids.get(1), "TAX_RETURN", "1.00"));
        pages.add(untyped(ids.get(2), "0.20"));
        pages.add(typed(ids.get(3), "TAX_RETURN", "0.70"));
        pages.add(untyped(ids.get(4), "0.20"));
        pages.add(typed(ids.get(5), "TAX_RETURN", "0.70"));
        for (int index = 6; index < 26; index++) {
            pages.add(untyped(ids.get(index), "0.10"));
        }

        List<DocumentGroup> groups = PackageSplitter.group(pages);

        assertThat(groups).extracting(DocumentGroup::typeCode).containsExactly("UNKNOWN", "TAX_RETURN");
        assertThat(groups.get(0).pageIds()).containsExactly(ids.get(0));
        assertThat(groups.get(1).pageIds()).containsExactlyElementsOf(ids.subList(1, 26));
    }

    @Test
    void leading_untyped_pages_have_no_run_to_join_so_they_open_an_UNKNOWN_one() {
        // The decision, asserted rather than assumed. An untyped page is NOT unassignable:
        // `unassignedPages` in the read models means BLANK or DUPLICATE — a page with no
        // content to attribute, or content already attributed elsewhere. An untyped page has
        // real content and no home, so dropping it would reproduce the very defect this rule
        // fixes: content unreachable from any document.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(untyped(P1, "0.00"), untyped(P2, "0.20"), typed(P3, "TAX_RETURN", "1.0")));

        assertThat(groups).extracting(DocumentGroup::typeCode).containsExactly("UNKNOWN", "TAX_RETURN");
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
        assertThat(groups.get(1).pageIds()).containsExactly(P3);
    }

    @Test
    void a_package_of_nothing_but_untyped_pages_is_still_ONE_reachable_document() {
        List<DocumentGroup> groups =
                PackageSplitter.group(List.of(untyped(P1, "0.00"), untyped(P2, "0.30")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).typeCode()).isEqualTo("UNKNOWN");
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
    }

    @Test
    void a_trailing_tail_of_untyped_pages_joins_the_last_open_run() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                typed(P2, "W2", "1.0"),
                                untyped(P3, "0.10"),
                                untyped(P4, "0.00")));

        assertThat(groups).extracting(DocumentGroup::typeCode).containsExactly("PAYSTUB", "W2");
        assertThat(groups.get(1).pageIds()).containsExactly(P2, P3, P4);
    }

    @Test
    void an_untyped_page_does_not_drag_its_documents_confidence_below_its_typed_pages() {
        // A page with no type signal is not evidence AGAINST the type. If it set the MIN,
        // every real multi-page document would land at 0.00 and sink to the bottom of the
        // review queue — a new defect handed over with the fix for the old one.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "TAX_RETURN", "1.00"),
                                untyped(P2, "0.00"),
                                typed(P3, "TAX_RETURN", "0.70")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).confidence()).isEqualByComparingTo("0.70");
    }

    @Test
    void an_untyped_page_between_two_DIFFERENT_types_joins_the_document_it_follows() {
        // The trade-off this rule accepts, pinned so nobody has to rediscover it: two
        // unrelated documents separated only by an unclassifiable page now glue at that page.
        // The page goes to the run it FOLLOWS — a continuation reads backwards, and a page
        // that begins something new is exactly the page a `startsDocument` anchor is for.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"), untyped(P2, "0.10"), typed(P3, "W2", "1.0")));

        assertThat(groups).extracting(DocumentGroup::typeCode).containsExactly("PAYSTUB", "W2");
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
        assertThat(groups.get(1).pageIds()).containsExactly(P3);
    }

    @Test
    void a_genuine_type_change_still_splits_with_no_untyped_page_in_sight() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "W2", "1.0"), typed(P2, "BANK_STATEMENT", "0.9")));

        assertThat(groups).extracting(DocumentGroup::typeCode).containsExactly("W2", "BANK_STATEMENT");
    }

    @Test
    void an_untyped_page_that_declared_a_form_boundary_still_starts_a_document() {
        // The boundary machinery is not weakened by any of this. "Untyped never starts a
        // document" is a rule about the ABSENCE of signal; a `startsDocument` anchor is
        // POSITIVE proof of a form header, and positive proof outranks absence. (The
        // classifier only writes the WINNING pack's anchors, so an UNKNOWN page carrying one
        // is not reachable today — which is precisely why the precedence must be pinned here
        // rather than left to be discovered when a pack changes.)
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "SCHEDULE_E", "0.9"),
                                new PageForSplit(P2, false, false, "UNKNOWN", new BigDecimal("0.1"), true)));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).pageIds()).containsExactly(P1);
        assertThat(groups.get(1).pageIds()).containsExactly(P2);
    }

    @Test
    void a_typed_page_after_an_untyped_stretch_does_not_reopen_its_own_type() {
        // The run's type survives the untyped stretch, so the return's p3 TAX_RETURN is a
        // continuation of p1's document and not a third one.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "TAX_RETURN", "1.0"),
                                untyped(P2, "0.20"),
                                typed(P3, "TAX_RETURN", "0.70")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2, P3);
    }

    // ── boundary provenance (Phase B): WHY each run started where it did ────

    /**
     * Provenance is decided at the moment a run starts, and only there: once the rows exist, "an
     * anchor declared a header" and "the type changed" are indistinguishable. These assert the
     * precedence written into {@link PackageSplitter#group} — a declared header outranks
     * everything, a package simply BEGINS at its first document, and a differing typed page is the
     * weakest of the three real reasons.
     */
    @Test
    void the_first_document_of_a_package_is_package_start_not_a_type_change() {
        List<DocumentGroup> groups =
                PackageSplitter.group(List.of(typed(P1, "PAYSTUB", "0.9"), typed(P2, "W2", "0.8")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_PACKAGE_START);
        assertThat(groups.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_TYPE_CHANGE);
    }

    @Test
    void a_declared_form_header_records_rule_even_when_the_type_did_not_change() {
        // The Schedule E case: same type either side, so TYPE_CHANGE could never have cut here —
        // the anchor is the whole reason, and the row must say so.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "SCHEDULE_E", "0.9"), header(P2, "SCHEDULE_E", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_RULE);
    }

    @Test
    void a_header_on_the_packages_first_page_records_rule_not_package_start() {
        // Proof outranks structure: the page really did declare a form header, and a later
        // reviewer sorting by "which boundaries were only inferred" must not see this one.
        List<DocumentGroup> groups = PackageSplitter.group(List.of(header(P1, "SCHEDULE_E", "0.9")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_RULE);
    }

    @Test
    void a_header_that_also_changes_type_records_rule_the_stronger_evidence() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "PAYSTUB", "0.9"), header(P2, "SCHEDULE_E", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_RULE);
    }

    @Test
    void an_untyped_page_opening_a_leading_run_is_still_package_start() {
        // A leading UNKNOWN run opens a document of its own (it has no run to join). Its boundary
        // is structural, not inferred — nothing was compared to decide it.
        List<DocumentGroup> groups =
                PackageSplitter.group(List.of(untyped(P1, "0"), typed(P2, "PAYSTUB", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_PACKAGE_START);
        assertThat(groups.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_TYPE_CHANGE);
    }

    @Test
    void every_group_names_a_provenance_and_never_the_ai_value_no_producer_writes_yet() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                blank(P2),
                                header(P3, "PAYSTUB", "0.9"),
                                typed(P4, "W2", "0.7")));

        assertThat(groups)
                .isNotEmpty()
                .allSatisfy(
                        group ->
                                assertThat(group.boundaryProvenance())
                                        .isIn(
                                                LogicalDocument.BOUNDARY_PACKAGE_START,
                                                LogicalDocument.BOUNDARY_RULE,
                                                LogicalDocument.BOUNDARY_TYPE_CHANGE));
    }

    // ── instance boundaries (Phase C): the same-type seam ───────────────────

    /** A page the detector reported as starting a second instance of its own type. */
    private static PageForSplit instance(UUID pageId, String type, String confidence) {
        return new PageForSplit(pageId, false, false, type, new BigDecimal(confidence), false, true);
    }

    @Test
    void a_new_instance_cuts_a_run_the_type_and_anchor_rules_cannot_see() {
        // Three pages of one type: the case that merged three monthly statements into one
        // document reporting a single beginning balance. Page 2 begins the second statement.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "BANK_STATEMENT", "0.9"),
                                instance(P2, "BANK_STATEMENT", "0.9"),
                                typed(P3, "BANK_STATEMENT", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).pageIds()).containsExactly(P1);
        assertThat(groups.get(1).pageIds()).containsExactly(P2, P3);
        assertThat(groups.get(1).boundaryProvenance())
                .isEqualTo(LogicalDocument.BOUNDARY_INSTANCE_CHANGE);
    }

    @Test
    void a_declared_form_header_outranks_an_instance_change_on_the_same_page() {
        // Both are deterministic, but RULE is the stronger statement: a printed form header was
        // found, where an instance change is read off a value. Precedence must be stable or the
        // same page could report either reason depending on evaluation order.
        PageForSplit both =
                new PageForSplit(P2, false, false, "SCHEDULE_E", new BigDecimal("0.9"), true, true);

        List<DocumentGroup> groups =
                PackageSplitter.group(List.of(typed(P1, "SCHEDULE_E", "0.9"), both));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_RULE);
    }

    @Test
    void an_instance_change_outranks_a_type_change_on_the_same_page() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "PAYSTUB", "0.9"), instance(P2, "BANK_STATEMENT", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(1).boundaryProvenance())
                .isEqualTo(LogicalDocument.BOUNDARY_INSTANCE_CHANGE);
    }

    @Test
    void a_transparent_page_claiming_an_instance_change_still_neither_joins_nor_breaks() {
        // Transparency is checked FIRST and stays absolute: a blank separator sheet carries no
        // information about boundaries, and that does not change because a detector spoke.
        PageForSplit blankClaimingInstance =
                new PageForSplit(P2, true, false, null, null, false, true);

        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                blankClaimingInstance,
                                typed(P3, "PAYSTUB", "0.9")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P3);
    }

    @Test
    void the_pre_phase_c_constructor_leaves_the_mechanism_inert() {
        // Every existing caller builds the 6-component shape; it must mean "no instance boundary"
        // and split byte-identically to the way it did before Phase C.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "BANK_STATEMENT", "0.9"),
                                typed(P2, "BANK_STATEMENT", "0.9")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2);
    }

    // ── AI boundaries (Phase D): accepted proposals reach the same rule ─────

    /** A page carrying an ACCEPTED boundary proposal — marked by split() from the ledger. */
    private static PageForSplit ai(UUID pageId, String type, String confidence) {
        return new PageForSplit(
                pageId,
                false,
                false,
                type,
                confidence == null ? null : new BigDecimal(confidence),
                false,
                false,
                true);
    }

    @Test
    void an_accepted_ai_boundary_cuts_a_same_type_run_and_reads_AI() {
        // The 14-page-"paystub" shape: no type change, no anchor, no instance key — only the
        // model saw the seam, so only AI can honestly explain the cut.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                ai(P2, "PAYSTUB", "0.9"),
                                typed(P3, "PAYSTUB", "0.9")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).pageIds()).containsExactly(P1);
        assertThat(groups.get(1).pageIds()).containsExactly(P2, P3);
        assertThat(groups.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_AI);
    }

    @Test
    void an_ai_boundary_at_an_untyped_page_cuts_the_glue() {
        // The glue case the windows exist for: a typed run followed by unclassifiable pages that
        // are actually a second document. UNKNOWN never starts a run on its own — the AI mark is
        // the only signal that can cut here, and the cut must say so.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "W2", "0.9"),
                                ai(P2, PageClassifier.UNKNOWN, "0.0"),
                                untyped(P3, "0.0")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).pageIds()).containsExactly(P1);
        assertThat(groups.get(1).pageIds()).containsExactly(P2, P3);
        assertThat(groups.get(1).typeCode()).isEqualTo(PageClassifier.UNKNOWN);
        assertThat(groups.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_AI);
    }

    @Test
    void a_deterministic_reason_on_the_same_page_outranks_the_ai_mark() {
        // Precedence (design §2): a cut a type change fully explains records TYPE_CHANGE even if
        // the model also proposed it, and a declared form header outranks both. AI is only ever
        // the reason where everything deterministic is silent.
        PageForSplit aiAndTypeChange =
                new PageForSplit(
                        P2, false, false, "W2", new BigDecimal("0.9"), false, false, true);
        List<DocumentGroup> typeChange =
                PackageSplitter.group(List.of(typed(P1, "PAYSTUB", "0.9"), aiAndTypeChange));
        assertThat(typeChange.get(1).boundaryProvenance())
                .isEqualTo(LogicalDocument.BOUNDARY_TYPE_CHANGE);

        PageForSplit aiAndHeader =
                new PageForSplit(
                        P2, false, false, "PAYSTUB", new BigDecimal("0.9"), true, false, true);
        List<DocumentGroup> rule =
                PackageSplitter.group(List.of(typed(P1, "PAYSTUB", "0.9"), aiAndHeader));
        assertThat(rule.get(1).boundaryProvenance()).isEqualTo(LogicalDocument.BOUNDARY_RULE);
    }

    @Test
    void an_ai_mark_on_the_package_first_page_reads_package_start() {
        // The first document opens the package whatever the model said about its first page —
        // there is no cut to attribute.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(ai(P1, "PAYSTUB", "0.9"), typed(P2, "PAYSTUB", "0.8")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).boundaryProvenance())
                .isEqualTo(LogicalDocument.BOUNDARY_PACKAGE_START);
    }

    @Test
    void a_transparent_page_with_an_ai_mark_stays_transparent() {
        // Gates refuse transparent pages, but even a row that slipped through could not cut:
        // transparency is checked before any boundary signal, same as the other marks.
        PageForSplit blankWithAi =
                new PageForSplit(P2, true, false, null, null, false, false, true);
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                blankWithAi,
                                typed(P3, "PAYSTUB", "0.9")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P3);
    }

    // ── absorbed untyped pages (issue #60): the continuation rule, COUNTED ──
    //
    // The rule is unchanged. What it leaves behind is now a number on the group: how many of the
    // run's pages were untyped continuations it absorbed on faith. "Schedule C, pp. 13–44, 30
    // untyped pages absorbed" is a document a reviewer opens first; a count of 0 is one every
    // page of which classified as its type.

    @Test
    void a_typed_run_counts_the_untyped_pages_it_absorbed() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "SCHEDULE_C", "0.9"),
                                typed(P2, "SCHEDULE_C", "0.7"),
                                untyped(P3, "0.10"),
                                untyped(P4, "0.00")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).pageIds()).containsExactly(P1, P2, P3, P4);
        assertThat(groups.get(0).absorbedUntypedPages()).isEqualTo(2);
    }

    @Test
    void a_run_every_page_of_which_classified_as_its_type_reports_zero() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "BANK_STATEMENT", "0.9"), typed(P2, "BANK_STATEMENT", "0.8")));

        assertThat(groups.get(0).absorbedUntypedPages()).isZero();
    }

    @Test
    void an_UNKNOWN_run_absorbs_nothing_because_it_had_no_run_to_join() {
        // Leading untyped pages open a run of their own: they were not absorbed INTO anything,
        // so counting them would flag every cover page as a glued-on document.
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(untyped(P1, "0.00"), untyped(P2, "0.20"), typed(P3, "W2", "1.0")));

        assertThat(groups).extracting(DocumentGroup::typeCode).containsExactly("UNKNOWN", "W2");
        assertThat(groups.get(0).absorbedUntypedPages()).isZero();
        assertThat(groups.get(1).absorbedUntypedPages()).isZero();
    }

    @Test
    void a_page_with_no_verdict_at_all_counts_as_absorbed_exactly_like_an_UNKNOWN_one() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "PAYSTUB", "0.9"),
                                new PageForSplit(P2, false, false, null, null, false)));

        assertThat(groups.get(0).absorbedUntypedPages()).isEqualTo(1);
    }

    @Test
    void the_count_resets_at_every_boundary_so_a_clean_second_form_reports_zero() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                header(P1, "SCHEDULE_E", "0.9"),
                                untyped(P2, "0.10"),
                                header(P3, "SCHEDULE_E", "0.9"),
                                typed(P4, "SCHEDULE_E", "0.8")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).absorbedUntypedPages()).isEqualTo(1);
        assertThat(groups.get(1).absorbedUntypedPages()).isZero();
    }

    @Test
    void a_transparent_page_is_never_counted_as_absorbed() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(typed(P1, "PAYSTUB", "0.9"), blank(P2), duplicate(P3)));

        assertThat(groups.get(0).pageIds()).containsExactly(P1);
        assertThat(groups.get(0).absorbedUntypedPages()).isZero();
    }

    @Test
    void an_untyped_page_that_opens_a_run_on_a_boundary_mark_is_a_start_not_an_absorption() {
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(P1, "W2", "0.9"),
                                ai(P2, PageClassifier.UNKNOWN, "0.0"),
                                untyped(P3, "0.0")));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).absorbedUntypedPages()).isZero();
        // P2 opened an UNKNOWN run; P3 joined an UNKNOWN run — neither was absorbed into a
        // typed document.
        assertThat(groups.get(1).absorbedUntypedPages()).isZero();
    }

    @Test
    void the_issue_60_package_splits_into_five_documents_and_the_state_return_reports_one_absorbed_page() {
        // The shape issue #60 measured, once every page has a type to earn: a 1040 (two typed
        // pages), then Schedule 1, Schedule 2 and Form 8962 — each opening on its own
        // startsDocument title anchor — then a state return whose second page is untyped and
        // joins the state return as a continuation. Before V46 the schedules typed TAX_RETURN
        // and the 8962 and state pages typed nothing, so this was ONE seven-page TAX_RETURN.
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            ids.add(UUID.randomUUID());
        }
        List<DocumentGroup> groups =
                PackageSplitter.group(
                        List.of(
                                typed(ids.get(0), "TAX_RETURN", "1.0"),
                                typed(ids.get(1), "TAX_RETURN", "0.6"),
                                header(ids.get(2), "SCHEDULE_1", "0.9"),
                                header(ids.get(3), "SCHEDULE_2", "0.7"),
                                header(ids.get(4), "FORM_8962", "0.8"),
                                header(ids.get(5), "STATE_TAX_RETURN", "1.0"),
                                untyped(ids.get(6), "0.30")));

        assertThat(groups)
                .extracting(DocumentGroup::typeCode)
                .containsExactly(
                        "TAX_RETURN", "SCHEDULE_1", "SCHEDULE_2", "FORM_8962", "STATE_TAX_RETURN");
        assertThat(groups.get(0).pageIds()).containsExactly(ids.get(0), ids.get(1));
        assertThat(groups.get(1).pageIds()).containsExactly(ids.get(2));
        assertThat(groups.get(2).pageIds()).containsExactly(ids.get(3));
        assertThat(groups.get(3).pageIds()).containsExactly(ids.get(4));
        assertThat(groups.get(4).pageIds()).containsExactly(ids.get(5), ids.get(6));
        assertThat(groups)
                .extracting(DocumentGroup::absorbedUntypedPages)
                .containsExactly(0, 0, 0, 0, 1);
        assertThat(groups)
                .extracting(DocumentGroup::boundaryProvenance)
                .containsExactly(
                        LogicalDocument.BOUNDARY_PACKAGE_START,
                        LogicalDocument.BOUNDARY_RULE,
                        LogicalDocument.BOUNDARY_RULE,
                        LogicalDocument.BOUNDARY_RULE,
                        LogicalDocument.BOUNDARY_RULE);
        // The state return's confidence is its typed page's, not the absorbed page's 0.30.
        assertThat(groups.get(4).confidence()).isEqualByComparingTo("1.0");
    }

    @Test
    void the_four_component_constructor_means_a_count_of_zero() {
        // Every pre-issue-60 caller builds the 4-component shape; it must mean "nothing
        // absorbed", never a null that a reader has to special-case.
        DocumentGroup group =
                new DocumentGroup(
                        "PAYSTUB", new BigDecimal("0.9"), List.of(P1), LogicalDocument.BOUNDARY_PACKAGE_START);

        assertThat(group.absorbedUntypedPages()).isZero();
    }
}
