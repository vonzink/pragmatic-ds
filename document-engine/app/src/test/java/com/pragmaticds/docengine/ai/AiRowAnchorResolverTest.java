package com.pragmaticds.docengine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row-scoped anchor resolution, exercised on the shape that motivated it.
 *
 * <p>The geometry in these tests is taken from a real Chase business statement that paid three
 * $130.00 checks on 06/18: the three transaction rows sit at y≈584.6, 596.9 and 609.1, roughly
 * twelve points apart, with spans about five points tall. Every {@code 130.00} and every
 * {@code 06/18} on that page tied, so twelve correct cells were review-flagged while the ledger
 * reconciled to the penny. The row pitch is what makes those ties breakable, and keeping the real
 * numbers here means a change to the overlap rule is measured against a document that exists.
 */
class AiRowAnchorResolverTest {

    private static final UUID PAGE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_PAGE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private static final String AMOUNT = "transactionAmount#000001";
    private static final String DESCRIPTION = "transactionDescription#000001";
    private static final String BALANCE = "transactionBalance#000001";
    private static final String ROW = "000001";

    /** The repeating group the transaction fixtures live in. */
    private static final String TXNS = "transactions";

    private final AiRowAnchorResolver resolver = new AiRowAnchorResolver();

    /** The defect itself: the row's unique siblings say which of three {@code -130.00} it read. */
    @Test
    void promotes_the_one_candidate_sharing_a_row_with_its_uniquely_matched_siblings() {
        TextSpan description = span("Check # 1192 Passportservices", 79.9, 584.6, 5.1);
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);
        TextSpan thisRow = span("-130.00", 427.4, 585.0, 4.6);
        TextSpan nextRow = span("-130.00", 427.4, 597.0, 4.6);
        TextSpan rowAfter = span("-130.00", 427.4, 609.2, 4.6);

        Map<String, AiEvidenceAnchor.Match> promotions =
                resolver.resolve(
                        members(DESCRIPTION, BALANCE, AMOUNT),
                        Map.of(
                                DESCRIPTION, matched(description),
                                BALANCE, matched(balance),
                                AMOUNT, ambiguous(thisRow, nextRow, rowAfter)));

        assertThat(promotions).containsOnlyKeys(AMOUNT);
        AiEvidenceAnchor.Match resolved = promotions.get(AMOUNT);
        assertThat(resolved.status()).isEqualTo(AiEvidenceAnchor.Status.MATCHED);
        assertThat(resolved.spans()).containsExactly(thisRow);
        assertThat(resolved.resolution()).isEqualTo(AiEvidenceAnchor.Resolution.ROW_SCOPED);
    }

    /** A multi-span run anchors as a unit, and only when the WHOLE run sits on the row. */
    @Test
    void promotes_a_multi_span_candidate_run() {
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);
        List<TextSpan> onRow =
                List.of(span("Green", 79.9, 584.7, 4.4), span("Fog", 110.6, 584.9, 4.4));
        List<TextSpan> onNextRow =
                List.of(span("Green", 79.9, 597.0, 4.4), span("Fog", 110.6, 597.2, 4.4));

        Map<String, AiEvidenceAnchor.Match> promotions =
                resolver.resolve(
                        members(BALANCE, DESCRIPTION),
                        Map.of(
                                BALANCE, matched(balance),
                                DESCRIPTION,
                                        AiEvidenceAnchor.Match.ambiguous(
                                                List.of(onRow, onNextRow))));

        assertThat(promotions.get(DESCRIPTION).spans()).containsExactlyElementsOf(onRow);
    }

    /** Two rows claiming the same candidate is the tie this pass exists to refuse, not to break. */
    @Test
    void refuses_when_two_candidates_share_the_band() {
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);
        TextSpan left = span("130.00", 300.0, 585.0, 4.6);
        TextSpan right = span("130.00", 427.4, 585.2, 4.6);

        assertThat(
                        resolver.resolve(
                                members(BALANCE, AMOUNT),
                                Map.of(BALANCE, matched(balance), AMOUNT, ambiguous(left, right))))
                .isEmpty();
    }

    @Test
    void refuses_when_no_candidate_shares_the_band() {
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);

        assertThat(
                        resolver.resolve(
                                members(BALANCE, AMOUNT),
                                Map.of(
                                        BALANCE,
                                        matched(balance),
                                        AMOUNT,
                                        ambiguous(
                                                span("130.00", 427.4, 597.0, 4.6),
                                                span("130.00", 427.4, 609.2, 4.6)))))
                .isEmpty();
    }

    /** No uniquely-matched sibling, no band — the group is left exactly as the matcher left it. */
    @Test
    void refuses_when_the_group_has_no_uniquely_matched_sibling() {
        assertThat(
                        resolver.resolve(
                                members(DESCRIPTION, AMOUNT),
                                Map.of(
                                        DESCRIPTION,
                                        AiEvidenceAnchor.Match.unanchored(),
                                        AMOUNT,
                                        ambiguous(span("130.00", 427.4, 585.0, 4.6)))))
                .isEmpty();
    }

    /**
     * A previously row-scoped anchor must not widen the band. Otherwise one promotion would feed
     * the next and the outcome would depend on iteration order rather than on the page.
     */
    @Test
    void a_row_scoped_match_does_not_contribute_to_the_band() {
        AiEvidenceAnchor.Match alreadyPromoted =
                AiEvidenceAnchor.Match.rowScoped(List.of(span("121,899.14", 489.1, 585.1, 4.6)));

        assertThat(
                        resolver.resolve(
                                members(BALANCE, AMOUNT),
                                Map.of(
                                        BALANCE,
                                        alreadyPromoted,
                                        AMOUNT,
                                        ambiguous(span("130.00", 427.4, 585.0, 4.6)))))
                .isEmpty();
    }

    /** A row is on one page; siblings matched on two describe no band this pass will trust. */
    @Test
    void refuses_when_matched_siblings_straddle_two_pages() {
        TextSpan here = span("121,899.14", 489.1, 585.1, 4.6);
        TextSpan elsewhere =
                new TextSpan(
                        OTHER_PAGE,
                        9,
                        "Check # 1192",
                        BigDecimal.valueOf(79.9),
                        BigDecimal.valueOf(584.6),
                        BigDecimal.valueOf(60),
                        BigDecimal.valueOf(5.1),
                        SpanSource.NATIVE,
                        null,
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        "Synthetic");

        assertThat(
                        resolver.resolve(
                                members(BALANCE, DESCRIPTION, AMOUNT),
                                Map.of(
                                        BALANCE, matched(here),
                                        DESCRIPTION, matched(elsewhere),
                                        AMOUNT, ambiguous(span("130.00", 427.4, 585.0, 4.6)))))
                .isEmpty();
    }

    /** A candidate on another page can never be this row's, however well its y lines up. */
    @Test
    void refuses_a_candidate_on_another_page() {
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);
        TextSpan sameYOtherPage =
                new TextSpan(
                        OTHER_PAGE,
                        3,
                        "130.00",
                        BigDecimal.valueOf(427.4),
                        BigDecimal.valueOf(585.0),
                        BigDecimal.valueOf(35),
                        BigDecimal.valueOf(4.6),
                        SpanSource.NATIVE,
                        null,
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        "Synthetic");

        assertThat(
                        resolver.resolve(
                                members(BALANCE, AMOUNT),
                                Map.of(
                                        BALANCE,
                                        matched(balance),
                                        AMOUNT,
                                        AiEvidenceAnchor.Match.ambiguous(
                                                List.of(List.of(sameYOtherPage))))))
                .isEmpty();
    }

    /** A zero-height box makes the overlap fraction meaningless; ambiguous is the honest answer. */
    @Test
    void refuses_a_degenerate_height_candidate() {
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);

        assertThat(
                        resolver.resolve(
                                members(BALANCE, AMOUNT),
                                Map.of(
                                        BALANCE,
                                        matched(balance),
                                        AMOUNT,
                                        ambiguous(span("130.00", 427.4, 585.0, 0.0)))))
                .isEmpty();
    }

    /** Row geometry cannot conjure evidence for text that appears nowhere on the page. */
    @Test
    void never_promotes_an_unanchored_value() {
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);

        assertThat(
                        resolver.resolve(
                                members(BALANCE, AMOUNT),
                                Map.of(
                                        BALANCE,
                                        matched(balance),
                                        AMOUNT,
                                        AiEvidenceAnchor.Match.unanchored())))
                .isEmpty();
    }

    /** An ambiguity past the retention cap carries no candidates and stays ambiguous. */
    @Test
    void leaves_an_ambiguity_with_no_retained_candidates_alone() {
        TextSpan balance = span("121,899.14", 489.1, 585.1, 4.6);

        assertThat(
                        resolver.resolve(
                                members(BALANCE, AMOUNT),
                                Map.of(
                                        BALANCE,
                                        matched(balance),
                                        AMOUNT,
                                        AiEvidenceAnchor.Match.ambiguous())))
                .isEmpty();
    }

    /** Summary fields have no row. They are ignored rather than pooled into a pseudo-group. */
    @Test
    void ignores_values_that_belong_to_no_group() {
        assertThat(
                        resolver.resolve(
                                List.of(
                                        new AiRowAnchorResolver.Member("endingBalance#", null, null),
                                        new AiRowAnchorResolver.Member("totalWithdrawals#", null, null)),
                                Map.of(
                                        "endingBalance#",
                                        matched(span("130,127.06", 326.1, 370.6, 4.6)),
                                        "totalWithdrawals#",
                                        ambiguous(span("-240.36", 341.2, 331.2, 4.4)))))
                .isEmpty();
    }

    /** A field in ANOTHER row must never be resolved against this row's band. */
    @Test
    void scopes_each_group_to_its_own_band() {
        String otherAmount = "transactionAmount#000002";
        TextSpan firstBalance = span("121,899.14", 489.1, 585.1, 4.6);
        TextSpan secondBalance = span("121,769.14", 489.1, 597.4, 4.6);
        TextSpan firstAmount = span("-130.00", 427.4, 585.0, 4.6);
        TextSpan secondAmount = span("-130.00", 427.4, 597.3, 4.6);

        Map<String, AiEvidenceAnchor.Match> promotions =
                resolver.resolve(
                        List.of(
                                new AiRowAnchorResolver.Member(BALANCE, TXNS, ROW),
                                new AiRowAnchorResolver.Member(AMOUNT, TXNS, ROW),
                                new AiRowAnchorResolver.Member("transactionBalance#000002", TXNS, "000002"),
                                new AiRowAnchorResolver.Member(otherAmount, TXNS, "000002")),
                        Map.of(
                                BALANCE, matched(firstBalance),
                                AMOUNT, ambiguous(firstAmount, secondAmount),
                                "transactionBalance#000002", matched(secondBalance),
                                otherAmount, ambiguous(firstAmount, secondAmount)));

        assertThat(promotions.get(AMOUNT).spans()).containsExactly(firstAmount);
        assertThat(promotions.get(otherAmount).spans()).containsExactly(secondAmount);
    }

    /**
     * The collision this partition exists for. Row keys restart per group: a check with no printed
     * number falls back to an ordinal and is {@code 000001}, exactly like the first transaction.
     * Partitioning on the key alone puts them in one band, and the transaction's geometry then
     * resolves the check's ambiguous amount to a span from a different row — silently, and reported
     * as MATCHED. Same page, so the cross-page guard does not save it.
     */
    @Test
    void never_bands_a_check_with_a_transaction_that_shares_its_row_key() {
        String checks = "checks";
        String txnAmount = "transactionAmount#000001";
        String txnBalance = "transactionBalance#000001";
        String checkAmount = "checkAmount#000001";
        String checkNumber = "checkNumber#000001";

        // One transaction row high on the page; one check row far below it. Both keyed 000001.
        TextSpan txnBalanceSpan = span("121,899.14", 489.1, 140.0, 4.6);
        TextSpan txnAmountSpan = span("-130.00", 427.4, 140.0, 4.6);
        TextSpan checkNumberSpan = span("1041", 60.0, 620.0, 4.6);
        TextSpan checkAmountSpan = span("-130.00", 427.4, 620.0, 4.6);

        Map<String, AiEvidenceAnchor.Match> promotions =
                resolver.resolve(
                        List.of(
                                new AiRowAnchorResolver.Member(txnBalance, TXNS, ROW),
                                new AiRowAnchorResolver.Member(txnAmount, TXNS, ROW),
                                new AiRowAnchorResolver.Member(checkNumber, checks, ROW),
                                new AiRowAnchorResolver.Member(checkAmount, checks, ROW)),
                        Map.of(
                                txnBalance, matched(txnBalanceSpan),
                                txnAmount, ambiguous(txnAmountSpan, checkAmountSpan),
                                checkNumber, matched(checkNumberSpan),
                                checkAmount, ambiguous(txnAmountSpan, checkAmountSpan)));

        // Each identical -130.00 resolves to the row it is actually on.
        assertThat(promotions.get(txnAmount).spans())
                .as("the transaction takes the span on the transaction's line")
                .containsExactly(txnAmountSpan);
        assertThat(promotions.get(checkAmount).spans())
                .as("the check takes the span on the check's line")
                .containsExactly(checkAmountSpan);
    }

    /** Two groups, same row key, and only one of them has a band: the other stays ambiguous. */
    @Test
    void a_group_without_a_band_borrows_nothing_from_one_that_has_it() {
        String checks = "checks";
        String checkAmount = "checkAmount#000001";
        TextSpan txnBalanceSpan = span("121,899.14", 489.1, 140.0, 4.6);
        TextSpan first = span("-130.00", 427.4, 140.0, 4.6);
        TextSpan second = span("-130.00", 427.4, 620.0, 4.6);

        Map<String, AiEvidenceAnchor.Match> promotions =
                resolver.resolve(
                        List.of(
                                new AiRowAnchorResolver.Member("transactionBalance#000001", TXNS, ROW),
                                new AiRowAnchorResolver.Member(checkAmount, checks, ROW)),
                        Map.of(
                                "transactionBalance#000001", matched(txnBalanceSpan),
                                checkAmount, ambiguous(first, second)));

        assertThat(promotions)
                .as("the check group has no UNIQUE-matched sibling, so it earns no band")
                .doesNotContainKey(checkAmount);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static List<AiRowAnchorResolver.Member> members(String... coordinates) {
        return java.util.Arrays.stream(coordinates)
                .map(coordinate -> new AiRowAnchorResolver.Member(coordinate, TXNS, ROW))
                .toList();
    }

    private static AiEvidenceAnchor.Match matched(TextSpan span) {
        return new AiEvidenceAnchor.Match(AiEvidenceAnchor.Status.MATCHED, List.of(span));
    }

    private static AiEvidenceAnchor.Match ambiguous(TextSpan... candidates) {
        return AiEvidenceAnchor.Match.ambiguous(
                java.util.Arrays.stream(candidates).map(List::of).toList());
    }

    private static TextSpan span(String text, double x, double y, double height) {
        return new TextSpan(
                PAGE,
                0,
                text,
                BigDecimal.valueOf(x),
                BigDecimal.valueOf(y),
                BigDecimal.valueOf(35),
                BigDecimal.valueOf(height),
                SpanSource.NATIVE,
                null,
                BigDecimal.ONE,
                BigDecimal.TEN,
                "Synthetic");
    }
}
