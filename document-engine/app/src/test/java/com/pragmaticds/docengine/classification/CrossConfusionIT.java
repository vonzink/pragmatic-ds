package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.PageClassifier.PackEvaluation;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.rules.RulePack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Spec 3 cross-confusion CI gate (design §4 rule 3): every type's truth-backed fixture pages
 * are scored against every OTHER type's pack, exactly as the classifier would — the qualification
 * predicate is verbatim {@code score > 0 && score >= minConfidence}, including the deliberate
 * {@code >=}. Any cross-qualification fails the build: pack separation is test-enforced, not
 * hoped for (the W-2/1040 near-miss, generalized).
 *
 * <p>Packs come FROM THE MIGRATED DB via {@link com.pragmaticds.docengine.classification.rules
 * .RulePackLoader} with the cache invalidated first — the gate scores what V6+V10+V11 actually
 * seeded, never a copy that could drift. Pages come from fixture truth JSON, the same
 * truth-by-construction spans the classification ITs insert.
 *
 * <p>This class lives in the {@code com.pragmaticds.docengine.classification} package so it can call the
 * package-private {@code PageClassifier.evaluate} — the same split-package convention every
 * classification IT in this source set already uses.
 */
class CrossConfusionIT extends AbstractClassificationIT {

    /** fixture -> the ONE type its pages are allowed to qualify for. */
    private static final Map<String, String> FIXTURE_OWN_TYPE = new LinkedHashMap<>();

    static {
        FIXTURE_OWN_TYPE.put("native_paystub", "PAYSTUB");
        FIXTURE_OWN_TYPE.put("w2_form", "W2");
        FIXTURE_OWN_TYPE.put("bank_statement", "BANK_STATEMENT");
        FIXTURE_OWN_TYPE.put("drivers_license", "DRIVERS_LICENSE");
        FIXTURE_OWN_TYPE.put("mortgage_statement", "MORTGAGE_STATEMENT");
        FIXTURE_OWN_TYPE.put("hoi_declaration", "HOI_DECLARATION");
        FIXTURE_OWN_TYPE.put("purchase_contract_signed", "PURCHASE_CONTRACT");
        FIXTURE_OWN_TYPE.put("tax_return", "TAX_RETURN");
        // Spec 5a. This fixture gates the matrix in BOTH directions and both matter:
        // TAX_RETURN must not qualify on a lettered schedule that merely cross-references
        // a numbered one (the reason tax_return@1.1.0 exists), and the new SCHEDULE_E pack
        // must not qualify on the tax_return fixture's SCHEDULE 2 page, which prints the
        // same generic schedule furniture — "Attachment Sequence", the Treasury line,
        // "(Form 1040)". Anchoring SCHEDULE_E on any of those would fail from that side.
        FIXTURE_OWN_TYPE.put("schedule_e", "SCHEDULE_E");
        // Spec 6. The 1099 family shares furniture (PAYER'S TIN / RECIPIENT'S TIN), so
        // each 1099 fixture gates the OTHER 1099 pack from qualifying on it — the shared
        // anchors are weighted 1 each precisely so their sum stays under qualification.
        // The 4-up W-2 maps to plain W2: the payroll 2x2 layout is a geometry variant,
        // not a type, and it must neither lose its own pack nor pick up anyone else's.
        FIXTURE_OWN_TYPE.put("form_1099r", "FORM_1099_R");
        FIXTURE_OWN_TYPE.put("form_1099g", "FORM_1099_G");
        FIXTURE_OWN_TYPE.put("voe_form", "VOE");
        FIXTURE_OWN_TYPE.put("w2_form_fourup", "W2");
        // Spec 6b. schedule_c gates in BOTH directions that matter: its "(Form 1040)"
        // and "Attach to Form 1040" furniture is worth 0.20 on the TAX_RETURN pack, and
        // its page 2 must qualify SCHEDULE_C on its own Part III/IV headings. The NEC
        // is the third 1099-family member: each family fixture now gates the other TWO
        // family packs from qualifying on the shared TIN/calendar-year furniture.
        FIXTURE_OWN_TYPE.put("schedule_c", "SCHEDULE_C");
        FIXTURE_OWN_TYPE.put("form_1099nec", "FORM_1099_NEC");
        // Spec 6c. The MISC makes the 1099 family FOUR packs sharing the TIN and
        // calendar-year furniture, all pairwise gated here. The SSA-1099 gates the
        // "Social security" vocabulary against W2 in both directions: no SSA-1099
        // prints "Social security wages" and no W-2 prints the benefit-statement title.
        FIXTURE_OWN_TYPE.put("form_1099misc", "FORM_1099_MISC");
        FIXTURE_OWN_TYPE.put("ssa_1099", "FORM_SSA_1099");
        // Spec 6d. The K-1 siblings share more furniture than the 1099s do, and the
        // schedule_e fixture prints bare "Schedule K-1" cross-references on its Part II
        // page — which is why every K-1 title anchor is the parenthesized-form regex
        // and never the bare phrase. Each sibling's fixture gates the other two packs.
        FIXTURE_OWN_TYPE.put("k1_1065", "SCHEDULE_K1_1065");
        FIXTURE_OWN_TYPE.put("k1_1120s", "SCHEDULE_K1_1120S");
        FIXTURE_OWN_TYPE.put("k1_1041", "SCHEDULE_K1_1041");
        // Spec 6e. Schedule B shares the "Name(s) shown on return" header with every
        // 1040 schedule (weighted 1, exactly the schedule_e overlap). The award letter
        // gates the "Social Security" vocabulary a THIRD way: statement (SSA-1099),
        // wage box (W-2) and letter must all stay apart.
        FIXTURE_OWN_TYPE.put("schedule_b", "SCHEDULE_B");
        FIXTURE_OWN_TYPE.put("ssa_award_letter", "SSA_AWARD_LETTER");
        // Spec 6f. The CD prints "Closing Date" (a PURCHASE_CONTRACT anchor) and
        // "Interest Rate" (MORTGAGE_STATEMENT); the commitment prints a policy premium
        // and property address near HOI vocabulary. All of it stays under 0.30 on the
        // neighbours, gated here in both directions.
        FIXTURE_OWN_TYPE.put("closing_disclosure", "CLOSING_DISCLOSURE");
        FIXTURE_OWN_TYPE.put("title_commitment", "TITLE_COMMITMENT");
        FIXTURE_OWN_TYPE.put("wiring_instructions", "WIRING_INSTRUCTIONS");
        // Spec 6g. paystub_oracle is the V34 supersession's regression gate: the
        // Oracle-HCM layout scored 0.40 on paystub@1.0.0 (a real recall miss on a
        // real employer's stub) and must qualify 1.1.0 — while every OTHER pack
        // stays off its "Payment Date"/"Gross Earnings" furniture.
        FIXTURE_OWN_TYPE.put("urla_1003", "URLA");
        FIXTURE_OWN_TYPE.put("appraisal_urar", "APPRAISAL");
        FIXTURE_OWN_TYPE.put("form_4506", "FORM_4506");
        FIXTURE_OWN_TYPE.put("disaster_cert", "DISASTER_CERT");
        FIXTURE_OWN_TYPE.put("paystub_oracle", "PAYSTUB");
        // Spec 6h. paystub_bureau is the V36 supersession's regression gate: the
        // payroll-bureau layout (a SCANNED stub whose spans arrive through OCR)
        // scored 0.50 on paystub@1.1.0 and must qualify 1.2.0 on its OCR-fused
        // vocabulary ("Deposit Date", "PayFrequency:", "TotalCurrentNet:") — while
        // its deposit-advice footer ("BankAccount", "RoutingID", masked numbers)
        // keeps every other pack, BANK_STATEMENT above all, from qualifying on it.
        FIXTURE_OWN_TYPE.put("paystub_bureau", "PAYSTUB");
        // V38. bank_statement_chase is to the BANK_STATEMENT supersession what
        // paystub_oracle is to PAYSTUB's: a real bank's vocabulary that scored 0.50
        // on bank_statement@1.0.0 and classified UNKNOWN. It gates BOTH directions,
        // and the second direction is the one that needed proving — 1.1.0 adds a
        // bare "checking|savings|money market" anchor, and the fixtures that
        // legitimately print those words in prose (schedule_b's interest-and-
        // dividends listing, urla_1003's asset section) must stay far below the bar.
        FIXTURE_OWN_TYPE.put("bank_statement_chase", "BANK_STATEMENT");
        // V39. The online activity print-out, whose whole vocabulary is tier-3
        // "online-view furniture" plus one account-type word. It gates the direction
        // that matters for that tier: the print header and balance captions are shared
        // with any online account view, so no OTHER type's pack may qualify on them.
        FIXTURE_OWN_TYPE.put("bank_statement_online", "BANK_STATEMENT");
        // V42. The bureau layout as NATIVE text: the same vocabulary as paystub_bureau
        // with the spaces the text layer keeps ("Name and Address", "Pay Period:",
        // "07/12/26 - 07/18/26"). It must still qualify PAYSTUB on the pack's optional-
        // space anchors, and its deposit-advice footer must keep BANK_STATEMENT off it
        // exactly as the scanned form does — the spacing changes nothing a pack may read.
        FIXTURE_OWN_TYPE.put("paystub_bureau_native", "PAYSTUB");
        // V43. The IRS box grid as ADP renders it. Same grid as w2_form with ADP's
        // captions; it must qualify W2 on the box furniture ADP keeps verbatim, and the
        // "Social security" vocabulary it prints must not pull in SSA-1099 or the award
        // letter — the three-way gate Spec 6c/6e established, now from a fourth layout.
        FIXTURE_OWN_TYPE.put("w2_adp", "W2");
        // V44. Schedule F is the hardest cell this matrix has held: it overlaps
        // Schedule C on 648 phrases, prints "Name of proprietor" verbatim, and is a
        // "Profit or Loss From …" form down to the masthead's shape. The whole reason
        // no expense wording is anchored above weight 2 in either pack is this pair of
        // cells — schedule_f must never qualify SCHEDULE_C, and schedule_c must never
        // qualify SCHEDULE_F on the farm vocabulary it does not print. Schedule D gates
        // the other direction that could bite: it prints "Capital gain distributions"
        // and "Schedule(s) K-1" near SCHEDULE_B's and the K-1 family's vocabulary.
        FIXTURE_OWN_TYPE.put("schedule_d", "SCHEDULE_D");
        FIXTURE_OWN_TYPE.put("schedule_f", "SCHEDULE_F");
        // Issue #60 (V46). The cells that matter: every one of these pages prints "(Form
        // 1040)" / "Attach to Form 1040" and the stacked Treasury line, so TAX_RETURN must stay
        // at 0.20 on all of them now that its schedule-title anchor is gone; schedule_2 prints
        // "Attach Form 8962" and form_8962 cites "Schedule 2 (Form 1040), line 2" — references
        // weighted 1 and 2, never identity; schedule_1 prints "Unemployment compensation", a
        // FORM_1099_G anchor worth 3 there and deliberately NOT one here. state_tax_return's
        // second page is UNKNOWN by construction and must qualify NOTHING (it scores 0.30 on
        // its own pack's form-number anchor — a reference again — and 0 everywhere else).
        FIXTURE_OWN_TYPE.put("schedule_1", "SCHEDULE_1");
        FIXTURE_OWN_TYPE.put("schedule_2", "SCHEDULE_2");
        FIXTURE_OWN_TYPE.put("form_8962", "FORM_8962");
        FIXTURE_OWN_TYPE.put("state_tax_return", "STATE_TAX_RETURN");
        // V50. The county tax statement prints "Taxable Value", "Special Assessments", a
        // parcel caption and "Statement No" — vocabulary an appraisal, a title commitment and
        // a bank or mortgage statement share — so this row gates that none of those packs
        // qualifies on it, and the mortgage_statement, hoi_declaration, appraisal and
        // title_commitment rows gate the reverse.
        FIXTURE_OWN_TYPE.put("property_tax_statement", "PROPERTY_TAX_STATEMENT");
        // V51. The triage-only types, whose pages a later change SKIPS — so a false positive
        // in this matrix is not a wrong label but income data never read. Every income row
        // above gates the five new packs; these rows gate the reverse, and each fixture prints
        // the income vocabulary its real counterpart sits next to: the fax cover names a W-2
        // and a Form 1040, the 8879 repeats "Federal income tax withheld from Form(s) W-2" and
        // "Amount you owe", the preparer letter cites Form 8879 and a balance due, the
        // disclosure page prints "Loan Number" and "Loan Estimate", the note a loan amount.
        FIXTURE_OWN_TYPE.put("fax_cover_sheet", "FAX_COVER_SHEET");
        FIXTURE_OWN_TYPE.put("efile_authorization", "EFILE_AUTHORIZATION");
        FIXTURE_OWN_TYPE.put("tax_preparer_letter", "TAX_PREPARER_LETTER");
        FIXTURE_OWN_TYPE.put("loan_disclosure_package", "LOAN_DISCLOSURE_PACKAGE");
        FIXTURE_OWN_TYPE.put("closing_package", "CLOSING_PACKAGE");
    }

    private static final List<String> ALL_PACK_TYPES =
            List.of("PAYSTUB", "W2", "BANK_STATEMENT", "DRIVERS_LICENSE", "MORTGAGE_STATEMENT",
                    "HOI_DECLARATION", "PURCHASE_CONTRACT", "TAX_RETURN", "SCHEDULE_E",
                    "FORM_1099_R", "FORM_1099_G", "VOE", "SCHEDULE_C", "FORM_1099_NEC",
                    "FORM_1099_MISC", "FORM_SSA_1099", "SCHEDULE_K1_1065",
                    "SCHEDULE_K1_1120S", "SCHEDULE_K1_1041", "SCHEDULE_B",
                    "SSA_AWARD_LETTER", "CLOSING_DISCLOSURE", "TITLE_COMMITMENT",
                    "WIRING_INSTRUCTIONS", "URLA", "APPRAISAL", "FORM_4506",
                    "DISASTER_CERT", "SCHEDULE_D", "SCHEDULE_F", "SCHEDULE_1", "SCHEDULE_2",
                    "FORM_8962", "STATE_TAX_RETURN", "PROPERTY_TAX_STATEMENT",
                    "FAX_COVER_SHEET", "EFILE_AUTHORIZATION", "TAX_PREPARER_LETTER",
                    "LOAN_DISCLOSURE_PACKAGE", "CLOSING_PACKAGE");

    /** Truth words become AnchorSpans verbatim — insertFixturePages' bridge, minus the DB. */
    private static List<AnchorSpan> spansOf(JsonNode truthPage) {
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
        return spans;
    }

    /** Every (page x foreign pack) cell that QUALIFIES, described well enough to fix. */
    private List<String> violationsFor(String fixture, String ownType, List<RulePack> packs) {
        List<String> violations = new ArrayList<>();
        JsonNode pages = truth(fixture).get("pages");
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            JsonNode page = pages.get(pageIndex);
            if (page.get("words").isEmpty()) {
                continue; // a blank page scores nothing anywhere
            }
            AnchorMatcher matcher = AnchorMatcher.forSpans(spansOf(page));
            for (RulePack pack : packs) {
                if (pack.documentTypeCode().equals(ownType)) {
                    continue;
                }
                PackEvaluation evaluation = PageClassifier.evaluate(pack, matcher);
                // The EXACT predicate from PageClassifier.decide — including >=.
                if (evaluation.score() > 0 && evaluation.score() >= pack.minConfidence()) {
                    List<String> anchors =
                            evaluation.matches().stream().map(m -> m.anchorId()).toList();
                    violations.add(
                            "%s page %d qualifies for %s@%s: score %.2f >= min %.2f via %s"
                                    .formatted(
                                            fixture,
                                            pageIndex,
                                            pack.documentTypeCode(),
                                            pack.version(),
                                            evaluation.score(),
                                            pack.minConfidence(),
                                            anchors));
                }
            }
        }
        return violations;
    }

    private List<RulePack> migratedPacks() {
        // Root fact 15: the loader caches per org with no TTL — a warm context from an
        // earlier IT class could serve pre-V11 packs. Invalidate, then load.
        rulePackLoader.invalidateAll();
        return rulePackLoader.activePacksForCurrentOrg();
    }

    @Test
    void no_fixture_page_qualifies_for_any_other_types_pack() {
        List<RulePack> packs = migratedPacks();
        // Vacuous-pass guard: the matrix only means something with all eight packs loaded.
        assertThat(packs.stream().map(RulePack::documentTypeCode).toList())
                .containsAll(ALL_PACK_TYPES);

        List<String> violations = new ArrayList<>();
        FIXTURE_OWN_TYPE.forEach(
                (fixture, ownType) -> violations.addAll(violationsFor(fixture, ownType, packs)));

        assertThat(violations).as("cross-confusion matrix").isEmpty();
    }

    @Test
    void every_fixture_still_qualifies_for_its_OWN_pack() {
        // The gate's counterweight: prove the matrix is not green because anchors stopped
        // matching anything at all. Page 0 of every fixture must clear its own bar.
        List<RulePack> packs = migratedPacks();

        List<String> failures = new ArrayList<>();
        FIXTURE_OWN_TYPE.forEach(
                (fixture, ownType) -> {
                    JsonNode page = truth(fixture).get("pages").get(0);
                    AnchorMatcher matcher = AnchorMatcher.forSpans(spansOf(page));
                    RulePack own =
                            packs.stream()
                                    .filter(pack -> pack.documentTypeCode().equals(ownType))
                                    .findFirst()
                                    .orElseThrow();
                    PackEvaluation evaluation = PageClassifier.evaluate(own, matcher);
                    if (!(evaluation.score() > 0 && evaluation.score() >= own.minConfidence())) {
                        failures.add(
                                "%s page 0 does NOT qualify for its own %s pack (%.2f < %.2f)"
                                        .formatted(
                                                fixture,
                                                ownType,
                                                evaluation.score(),
                                                own.minConfidence()));
                    }
                });
        assertThat(failures).as("own-pack qualification").isEmpty();
    }

    @Test
    void the_gate_bites_on_a_deliberately_confusable_pack() {
        // The gate's own permanent RED: an org pack anchored on the 1040's exclusive title
        // MUST cross-qualify on the tax_return fixture. If this stops failing the matrix,
        // the matrix has stopped gating anything.
        jdbc.update(
                """
                INSERT INTO document_type (org_id, code, display_name, category)
                VALUES (?, 'CONFUSABLE', 'Deliberately Confusable', NULL)
                """,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO classification_rule_pack
                    (org_id, document_type_code, version, min_confidence, definition)
                VALUES (?, 'CONFUSABLE', '1.0.0', 0.6, '{
                  "targetScore": 10,
                  "anchors": [
                    {"id": "stolen-title", "kind": "literal", "pattern": "Form 1040", "weight": 10}
                  ]}'::jsonb)
                """,
                ORG_DEV);
        rulePackLoader.invalidate(ORG_DEV);
        try {
            List<RulePack> packs = rulePackLoader.activePacksForCurrentOrg();
            List<String> violations = violationsFor("tax_return", "TAX_RETURN", packs);
            assertThat(violations).isNotEmpty();
            assertThat(violations.toString()).contains("CONFUSABLE");
        } finally {
            jdbc.update(
                    "DELETE FROM classification_rule_pack WHERE document_type_code = 'CONFUSABLE'");
            jdbc.update("DELETE FROM document_type WHERE code = 'CONFUSABLE'");
            rulePackLoader.invalidateAll();
        }
    }
}
