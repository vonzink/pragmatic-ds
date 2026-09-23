package com.pragmaticds.docengine.classification.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.PlannerDocument;
import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.PlannerPage;
import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.Window;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The windows ARE the cost model: everything outside them is never sent, so these pins are what
 * keeps a clean package at zero tokens and stops a tight parameter from opening a window on every
 * 26-page tax return (roadmap R6). Windows use defaults N=3, floor=0.40 unless a test says
 * otherwise.
 */
class BoundaryWindowPlannerTest {

    private static final BigDecimal FLOOR = new BigDecimal("0.40");

    private static PlannerPage page(int index, String type, String confidence) {
        return new PlannerPage(
                index,
                UUID.randomUUID(),
                type,
                confidence == null ? null : new BigDecimal(confidence),
                false);
    }

    private static PlannerPage blank(int index) {
        return new PlannerPage(index, UUID.randomUUID(), null, null, true);
    }

    private static PlannerDocument document(String type, String confidence, PlannerPage... pages) {
        return new PlannerDocument(
                type, confidence == null ? null : new BigDecimal(confidence), List.of(pages));
    }

    private static List<PlannerPage> flatten(List<PlannerDocument> documents) {
        List<PlannerPage> all = new ArrayList<>();
        documents.forEach(document -> all.addAll(document.pages()));
        return all;
    }

    @Test
    void a_clean_confident_package_plans_zero_windows() {
        List<PlannerDocument> documents =
                List.of(
                        document("PAYSTUB", "0.9", page(0, "PAYSTUB", "0.9"), page(1, "PAYSTUB", "0.9")),
                        document("W2", "0.95", page(2, "W2", "0.95")));

        assertThat(
                        BoundaryWindowPlanner.plan(
                                flatten(documents), documents, 3, FLOOR, Map.of()))
                .isEmpty();
    }

    @Test
    void an_unknown_run_of_n_opens_a_window_padded_by_one_confirmed_page_each_side() {
        // Pages 2-4 are the glue: absorbed by the paystub in front of them. The window must
        // include the typed pages 1 and 5 as anchors.
        List<PlannerDocument> documents =
                List.of(
                        document(
                                "PAYSTUB",
                                "0.9",
                                page(0, "PAYSTUB", "0.9"),
                                page(1, "PAYSTUB", "0.9"),
                                page(2, "UNKNOWN", "0.0"),
                                page(3, "UNKNOWN", "0.0"),
                                page(4, "UNKNOWN", "0.0")),
                        document("W2", "0.95", page(5, "W2", "0.95")));

        List<Window> windows =
                BoundaryWindowPlanner.plan(flatten(documents), documents, 3, FLOOR, Map.of());

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).startIndex()).isEqualTo(1);
        assertThat(windows.get(0).endIndex()).isEqualTo(5);
        assertThat(windows.get(0).reason()).isEqualTo(BoundaryWindowPlanner.REASON_UNKNOWN_RUN);
    }

    @Test
    void an_unknown_run_shorter_than_n_opens_nothing() {
        // The parameter is the tax-return guard: real multi-page documents carry untyped pages
        // routinely, and a window on every two-page gap would send the whole package.
        List<PlannerDocument> documents =
                List.of(
                        document(
                                "TAX_RETURN",
                                "0.9",
                                page(0, "TAX_RETURN", "0.9"),
                                page(1, "UNKNOWN", "0.0"),
                                page(2, "UNKNOWN", "0.0"),
                                page(3, "TAX_RETURN", "0.9")));

        assertThat(
                        BoundaryWindowPlanner.plan(
                                flatten(documents), documents, 3, FLOOR, Map.of()))
                .isEmpty();
    }

    @Test
    void a_low_confidence_document_opens_a_window_over_its_span() {
        List<PlannerDocument> documents =
                List.of(
                        document("PAYSTUB", "0.9", page(0, "PAYSTUB", "0.9")),
                        document(
                                "BANK_STATEMENT",
                                "0.25",
                                page(1, "BANK_STATEMENT", "0.25"),
                                page(2, "BANK_STATEMENT", "0.30")),
                        document("W2", "0.95", page(3, "W2", "0.95")));

        List<Window> windows =
                BoundaryWindowPlanner.plan(flatten(documents), documents, 3, FLOOR, Map.of());

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).startIndex()).isEqualTo(0);
        assertThat(windows.get(0).endIndex()).isEqualTo(3);
        assertThat(windows.get(0).reason())
                .isEqualTo(BoundaryWindowPlanner.REASON_LOW_CONFIDENCE);
    }

    @Test
    void a_run_longer_than_the_packs_plausible_page_count_opens_a_window() {
        // The 14-page "paystub". The pack declares 2; nothing else about the run is suspicious.
        PlannerPage[] pages = new PlannerPage[5];
        for (int i = 0; i < 5; i++) {
            pages[i] = page(i, "PAYSTUB", "0.9");
        }
        List<PlannerDocument> documents = List.of(document("PAYSTUB", "0.9", pages));

        List<Window> windows =
                BoundaryWindowPlanner.plan(
                        flatten(documents), documents, 3, FLOOR, Map.of("PAYSTUB", 2));

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).startIndex()).isEqualTo(0);
        assertThat(windows.get(0).endIndex()).isEqualTo(4);
        assertThat(windows.get(0).reason())
                .isEqualTo(BoundaryWindowPlanner.REASON_IMPLAUSIBLE_LENGTH);
    }

    @Test
    void a_type_with_no_declared_maximum_never_opens_a_length_window() {
        PlannerPage[] pages = new PlannerPage[30];
        for (int i = 0; i < 30; i++) {
            pages[i] = page(i, "TAX_RETURN", "0.9");
        }
        List<PlannerDocument> documents = List.of(document("TAX_RETURN", "0.9", pages));

        assertThat(
                        BoundaryWindowPlanner.plan(
                                flatten(documents), documents, 3, FLOOR, Map.of("PAYSTUB", 2)))
                .isEmpty();
    }

    @Test
    void overlapping_windows_merge_and_join_their_reasons() {
        // One weak document that is also implausibly long: one window, both reasons named, so
        // the ledger can say WHY a region was sent without sending it twice.
        PlannerPage[] pages = new PlannerPage[4];
        for (int i = 0; i < 4; i++) {
            pages[i] = page(i, "PAYSTUB", "0.2");
        }
        List<PlannerDocument> documents = List.of(document("PAYSTUB", "0.2", pages));

        List<Window> windows =
                BoundaryWindowPlanner.plan(
                        flatten(documents), documents, 3, FLOOR, Map.of("PAYSTUB", 2));

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).reason())
                .isEqualTo(
                        BoundaryWindowPlanner.REASON_IMPLAUSIBLE_LENGTH
                                + "+"
                                + BoundaryWindowPlanner.REASON_LOW_CONFIDENCE);
    }

    @Test
    void padding_walks_past_transparent_pages_to_a_confirmed_one() {
        // A blank separator sheet is boundary-invisible; the anchor page the model sees must be
        // one that carries information.
        PlannerPage anchor = page(0, "W2", "0.95");
        PlannerPage separator = blank(1);
        List<PlannerDocument> documents =
                List.of(
                        new PlannerDocument("W2", new BigDecimal("0.95"), List.of(anchor)),
                        document(
                                "BANK_STATEMENT",
                                "0.25",
                                page(2, "BANK_STATEMENT", "0.25"),
                                page(3, "BANK_STATEMENT", "0.30")));
        List<PlannerPage> all = List.of(anchor, separator, flatten(documents).get(1), flatten(documents).get(2));

        List<Window> windows =
                BoundaryWindowPlanner.plan(all, documents, 3, FLOOR, Map.of());

        assertThat(windows).hasSize(1);
        // Padding jumped over the blank page 1 to the typed page 0.
        assertThat(windows.get(0).startIndex()).isEqualTo(0);
        assertThat(windows.get(0).endIndex()).isEqualTo(3);
    }
}
