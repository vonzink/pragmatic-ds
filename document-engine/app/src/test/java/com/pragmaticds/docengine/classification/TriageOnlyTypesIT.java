package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.PageClassifier.PackEvaluation;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V51 against the migrated packs: the five triage-only types — pages a later change will SKIP
 * rather than OCR and extract. That makes the usual cross-confusion question sharper than for any
 * other type: a triage pack qualifying on an income page does not mislabel it, it loses its data.
 *
 * <p>So this class holds three things {@link CrossConfusionIT} does not. It scores the five packs
 * against EVERY committed fixture truth page, not only the matrix's own-type rows (the preparer
 * 1040, the box-grid return, the ADP stub, the combined packages, the scans). It pins each pack's
 * SHARED vocabulary — every anchor an income document can print — to a sum under the bar, so the
 * invariant is data-checked and not a comment. And it pins, to the number, the income wording the
 * brief named as the traps: a 1040 citing Form 8879, a Schedule C "Note:", a 1098's "Mortgage", a
 * voucher's "Balance due", a VOE's "I have applied for a mortgage loan".
 */
class TriageOnlyTypesIT extends AbstractClassificationIT {

    /** type -> its fixture. */
    private static final Map<String, String> TRIAGE_FIXTURE =
            Map.of(
                    "FAX_COVER_SHEET", "fax_cover_sheet",
                    "EFILE_AUTHORIZATION", "efile_authorization",
                    "TAX_PREPARER_LETTER", "tax_preparer_letter",
                    "LOAN_DISCLOSURE_PACKAGE", "loan_disclosure_package",
                    "CLOSING_PACKAGE", "closing_package");

    /** type -> every anchor an income or neighbouring document can print (V51's SHARED rows). */
    private static final Map<String, Set<String>> SHARED =
            Map.of(
                    "FAX_COVER_SHEET", Set.of("fax-count", "fax-number", "fax-re"),
                    "EFILE_AUTHORIZATION", Set.of("ef-form", "ef-efin"),
                    "TAX_PREPARER_LETTER",
                            Set.of("tpl-mail", "tpl-deadline", "tpl-enclosed", "tpl-amount",
                                    "tpl-copy"),
                    "LOAN_DISCLOSURE_PACKAGE",
                            Set.of("ldp-le-title", "ldp-servicing", "ldp-applied", "ldp-ecoa"),
                    "CLOSING_PACKAGE", Set.of("cp-first-payment", "cp-notary", "cp-mers"));

    private static final Map<String, String> TITLE =
            Map.of(
                    "FAX_COVER_SHEET", "fax-title",
                    "EFILE_AUTHORIZATION", "ef-title",
                    "TAX_PREPARER_LETTER", "tpl-title",
                    "LOAN_DISCLOSURE_PACKAGE", "ldp-title",
                    "CLOSING_PACKAGE", "cp-title");

    private RulePack pack(String type) {
        rulePackLoader.invalidateAll();
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active " + type + " pack"));
    }

    private static AnchorMatcher matcherForTruthPage(JsonNode truthPage) {
        List<AnchorSpan> spans = new ArrayList<>();
        long id = 1;
        for (JsonNode word : truthPage.get("words")) {
            spans.add(
                    new AnchorSpan(
                            id++,
                            word.get("text").asText(),
                            word.get("x").decimalValue(),
                            word.get("y").decimalValue(),
                            word.get("width").decimalValue(),
                            word.get("height").decimalValue()));
        }
        return AnchorMatcher.forSpans(spans);
    }

    /** A sentence becomes spans on one visual line, in reading order. */
    private static AnchorMatcher matcherFor(String sentence) {
        List<AnchorSpan> spans = new ArrayList<>();
        long id = 1;
        double x = 20.0;
        for (String word : sentence.split(" ")) {
            spans.add(
                    new AnchorSpan(
                            id++,
                            word,
                            BigDecimal.valueOf(x),
                            new BigDecimal("42.0"),
                            new BigDecimal("30.0"),
                            new BigDecimal("12.0")));
            x += 34.0;
        }
        return AnchorMatcher.forSpans(spans);
    }

    private static List<String> anchorIds(PackEvaluation evaluation) {
        return evaluation.matches().stream().map(match -> match.anchorId()).toList();
    }

    // ── trip ────────────────────────────────────────────────────────────────

    @Test
    void each_fixture_trips_its_own_pack_through_the_anchors_it_prints() {
        Map<String, List<String>> expected =
                Map.of(
                        "FAX_COVER_SHEET",
                                List.of("fax-title", "fax-pages", "fax-error", "fax-checkboxes",
                                        "fax-count", "fax-number", "fax-re"),
                        "EFILE_AUTHORIZATION",
                                List.of("ef-title", "ef-declaration", "ef-pin", "ef-consent",
                                        "ef-form", "ef-efin"),
                        "TAX_PREPARER_LETTER",
                                List.of("tpl-title", "tpl-prepared", "tpl-mail", "tpl-deadline",
                                        "tpl-enclosed", "tpl-amount", "tpl-copy"),
                        "LOAN_DISCLOSURE_PACKAGE",
                                List.of("ldp-title", "ldp-intent", "ldp-appraisal-copy",
                                        "ldp-le-title", "ldp-ecoa"),
                        "CLOSING_PACKAGE", List.of("cp-title", "cp-note", "cp-instrument"));
        expected.forEach(
                (type, anchors) -> {
                    JsonNode page = truth(TRIAGE_FIXTURE.get(type)).get("pages").get(0);
                    PackEvaluation evaluation =
                            PageClassifier.evaluate(pack(type), matcherForTruthPage(page));
                    assertThat(anchorIds(evaluation)).as(type).containsExactlyInAnyOrderElementsOf(anchors);
                    assertThat(evaluation.score()).as(type).isCloseTo(1.0, within(1e-9));
                });
    }

    @Test
    void each_pack_declares_exactly_one_form_boundary_on_its_printed_title() {
        TITLE.forEach(
                (type, title) ->
                        assertThat(
                                        pack(type).anchors().stream()
                                                .filter(Anchor::startsDocument)
                                                .map(Anchor::id)
                                                .toList())
                                .as(type)
                                .containsExactly(title));
    }

    // ── the safety invariant ────────────────────────────────────────────────

    @Test
    void every_packs_shared_vocabulary_together_stays_under_the_bar() {
        Map<String, Double> expected =
                Map.of(
                        "FAX_COVER_SHEET", 0.30,
                        "EFILE_AUTHORIZATION", 0.30,
                        "TAX_PREPARER_LETTER", 0.50,
                        "LOAN_DISCLOSURE_PACKAGE", 0.50,
                        "CLOSING_PACKAGE", 0.50);
        SHARED.forEach(
                (type, shared) -> {
                    RulePack pack = pack(type);
                    assertThat(pack.anchors().stream().map(Anchor::id).toList())
                            .as("%s declares every SHARED anchor this test names", type)
                            .containsAll(shared);
                    double sum =
                            pack.anchors().stream()
                                    .filter(anchor -> shared.contains(anchor.id()))
                                    .mapToDouble(Anchor::weight)
                                    .sum();
                    double score = Math.min(1.0, sum / pack.targetScore());
                    assertThat(score).as(type).isCloseTo(expected.get(type), within(1e-9));
                    assertThat(score).as(type).isLessThan(pack.minConfidence());
                });
    }

    @Test
    void no_committed_fixture_page_qualifies_any_triage_pack_except_its_own() throws IOException {
        List<RulePack> packs = TITLE.keySet().stream().map(this::pack).toList();
        Set<String> own = Set.copyOf(TRIAGE_FIXTURE.values());
        Map<String, String> highest = new TreeMap<>();
        Map<String, Double> highestScore = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        List<Path> truthFiles;
        try (Stream<Path> files = Files.list(fixtureTruthDir())) {
            truthFiles = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(truthFiles).as("fixture truth files").hasSizeGreaterThan(70);
        for (Path file : truthFiles) {
            String fixture = file.getFileName().toString().replace(".json", "");
            if (own.contains(fixture)) {
                continue;
            }
            JsonNode pages = readTree(file).get("pages");
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index).get("words").isEmpty()) {
                    continue;
                }
                AnchorMatcher matcher = matcherForTruthPage(pages.get(index));
                for (RulePack pack : packs) {
                    PackEvaluation evaluation = PageClassifier.evaluate(pack, matcher);
                    String type = pack.documentTypeCode();
                    if (evaluation.score() > highestScore.getOrDefault(type, -1.0)) {
                        highestScore.put(type, evaluation.score());
                        highest.put(
                                type,
                                "%.2f on %s page %d via %s"
                                        .formatted(evaluation.score(), fixture, index,
                                                anchorIds(evaluation)));
                    }
                    if (evaluation.score() > 0 && evaluation.score() >= pack.minConfidence()) {
                        violations.add(type + " qualifies on " + fixture + " page " + index
                                + " via " + anchorIds(evaluation));
                    }
                }
            }
        }
        // The measured ceiling, printed for the change record and pinned under the bar.
        highest.forEach((type, where) -> System.out.println("V51 highest foreign score " + type + ": " + where));
        assertThat(violations).as("a triage pack qualifying on a committed fixture").isEmpty();
        highestScore.values().forEach(score -> assertThat(score).isLessThanOrEqualTo(0.20));
    }

    // ── the named traps ─────────────────────────────────────────────────────

    private void assertScores(String sentence, Map<String, Double> expected) {
        AnchorMatcher matcher = matcherFor(sentence);
        for (String type : TITLE.keySet()) {
            RulePack pack = pack(type);
            PackEvaluation evaluation = PageClassifier.evaluate(pack, matcher);
            assertThat(evaluation.score())
                    .as("%s on '%s' via %s", type, sentence, anchorIds(evaluation))
                    .isCloseTo(expected.getOrDefault(type, 0.0), within(1e-9));
            assertThat(evaluation.score()).isLessThan(pack.minConfidence());
        }
    }

    @Test
    void a_1040_that_cites_Form_8879_and_prints_amount_you_owe_qualifies_nothing() {
        assertScores(
                "Form 1040 U.S. Individual Income Tax Return 2025 Amount you owe. Subtract line 33"
                        + " from line 24 Third Party Designee Sign Here Identity Protection PIN"
                        + " Paid Preparer Use Only PTIN Firm's EIN If you e-file, see Form 8879"
                        + " Client Copy",
                Map.of("EFILE_AUTHORIZATION", 0.20, "TAX_PREPARER_LETTER", 0.10));
    }

    @Test
    void a_schedule_C_note_and_a_1098_mortgage_are_not_closing_package_pages() {
        assertScores(
                "SCHEDULE C (Form 1040) Profit or Loss From Business Note: If you checked 32b,"
                        + " you must attach Form 6198. Your loss may be limited.",
                Map.of());
        assertScores(
                "Form 1098 Mortgage Interest Statement RECIPIENT'S/LENDER'S name Mortgage"
                        + " interest received from payer(s)/borrower(s) Outstanding mortgage"
                        + " principal Mortgage origination date Refund of overpaid interest",
                Map.of());
    }

    @Test
    void a_voucher_with_balance_due_mail_to_and_april_15_stays_under_the_letter_bar() {
        // Every shared tax-preparer anchor a 1040-V or a state voucher can print at once.
        assertScores(
                "Payment Voucher Balance due Mail your payment to the Department of Revenue"
                        + " postmarked by April 15, 2026. Enclosed is my check Client Copy",
                Map.of("TAX_PREPARER_LETTER", 0.50));
    }

    @Test
    void a_VOE_and_a_mortgage_statement_stay_off_the_disclosure_and_closing_packs() {
        assertScores(
                "Request for Verification of Employment I have applied for a mortgage loan and"
                        + " stated in my application that I am now or was formerly employed by you."
                        + " Equal Credit Opportunity Act Fax: (555) 010-4400",
                Map.of("LOAN_DISCLOSURE_PACKAGE", 0.20, "FAX_COVER_SHEET", 0.10));
        assertScores(
                "Mortgage Statement Principal Balance Escrow Balance Payment Due Date Notice of"
                        + " servicing transfer: the servicing of your mortgage loan will be"
                        + " transferred. Your first payment is due to your new servicer. Mortgage"
                        + " Electronic Registration Systems",
                Map.of("LOAN_DISCLOSURE_PACKAGE", 0.10, "CLOSING_PACKAGE", 0.40));
    }

    @Test
    void a_closing_disclosure_comparison_and_a_title_commitment_stay_under_the_bar() {
        assertScores(
                "Calculating Cash to Close Loan Estimate Final Did this change? Total Closing"
                        + " Costs (J)",
                Map.of("LOAN_DISCLOSURE_PACKAGE", 0.20));
        assertScores(
                "Requirements: Deed of Trust from Jordan Q. Fixture to the Public Trustee for the"
                        + " benefit of Mortgage Electronic Registration Systems, acknowledged"
                        + " before me, Notary Public",
                Map.of("CLOSING_PACKAGE", 0.20));
    }

    @Test
    void prose_naming_the_documents_does_not_fire_a_title() {
        // On an UNKNOWN page the splitter cuts at ANY startsDocument anchor it finds.
        String prose =
                "please sign your e-file signature authorization, read the filing instructions,"
                        + " keep your promissory note and deed of trust with the settlement"
                        + " statement, compare the loan estimate, discard this fax cover sheet and"
                        + " tell us of your intent to proceed";
        AnchorMatcher matcher = matcherFor(prose);
        TITLE.forEach(
                (type, title) ->
                        assertThat(anchorIds(PageClassifier.evaluate(pack(type), matcher)))
                                .as(type)
                                .doesNotContain(title));
    }

    private static JsonNode readTree(Path file) {
        try {
            return JSON.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
