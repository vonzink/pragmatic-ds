package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The BANK_STATEMENT pack must fire on the vocabulary real banks print, and must NOT fire on an
 * online view that is not a deposit account. V38's guard, and the mirror image of {@link
 * W2PackExclusivityIT}: that one pins a pack that could fire on the WRONG document, this one pins
 * a pack that could not fire on the RIGHT one.
 *
 * <p>Empirically grounded (a real 5-page Chase checking document, 2026-09-01). Under the V6 seed
 * the reviewer's Parsing tab showed a summary card of em-dashes and "No transactions extracted" —
 * not because extraction failed, but because CLASSIFICATION did. bank_statement@1.0.0 anchors this
 * repository's own synthetic vocabulary ("Statement Period", "Account Statement", "Deposits and
 * Credits"); the bank printed a bare month-name date range, "Deposits and Additions", and — on the
 * online print-out — none of it at all. Both dialects landed UNKNOWN, an UNKNOWN document draws no
 * extraction schema and no AI dialect, and zero fields reach the reviewer.
 *
 * <p>The fix is data (V38 supersedes the pack with 1.1.0, weighted by exclusivity in three tiers),
 * so the guard here is data too: the tier-3 anchors an online CREDIT CARD view legitimately
 * carries must not reach the pack's own threshold on their own.
 */
class BankStatementDialectIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    /**
     * The V38 pack's tier-3 anchor ids — "online-view furniture", printed by ANY online account
     * view including a credit card's. A credit card statement is a LIABILITY document, and letting
     * this set qualify by itself is the one false positive the online dialect could introduce.
     */
    private static final Set<String> ONLINE_VIEW_FURNITURE =
            Set.of("online-print", "available-balance", "present-balance", "posted-activity");

    /** W2PackExclusivityIT's layout, verbatim: tokens laid left to right, wrapped at 520. */
    private static ArrayNode truthPage(List<String> tokens) {
        ArrayNode pages = JSON.createArrayNode();
        ObjectNode page = pages.addObject();
        page.put("pageIndex", 0);
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        ArrayNode words = page.putArray("words");
        double x = 72.0;
        double y = 730.0;
        for (String token : tokens) {
            ObjectNode word = words.addObject();
            word.put("text", token);
            word.put("x", x);
            word.put("y", y);
            word.put("width", token.length() * 6.0);
            word.put("height", 12.0);
            x += token.length() * 6.0 + 4.0;
            if (x > 520.0) {
                x = 72.0;
                y -= 18.0;
            }
        }
        return pages;
    }

    /**
     * The observed document, word for word: an online activity print-out. It prints no statement
     * period, no beginning or ending balance, and no summary block — the account TYPE in the
     * masthead is what makes it a deposit account rather than a card.
     */
    private static ArrayNode onlinePrintOutPage() {
        return truthPage(
                List.of(
                        "CHASE", "Printed", "from", "Chase", "Personal", "Online",
                        "TOTAL", "CHECKING", "(...6227)",
                        "Present", "balance", "$4,812.33",
                        "Available", "balance", "$4,712.33",
                        "Transaction", "activity",
                        "Date", "Description", "Amount", "Balance",
                        "08/24", "Card", "Purchase", "Grocery", "Mart", "-54.20", "4,812.33",
                        "08/22", "Online", "Transfer", "To", "Sav", "-500.00", "4,866.53",
                        "08/21", "Direct", "Dep", "Employer", "Payroll", "2,180.40", "5,366.53"));
    }

    /**
     * The same print-out for a CREDIT CARD: every piece of online-view furniture, and no deposit
     * account anywhere on it. This is the page the pack must refuse.
     */
    private static ArrayNode creditCardPrintOutPage() {
        return truthPage(
                List.of(
                        "CHASE", "Printed", "from", "Chase", "Personal", "Online",
                        "SAPPHIRE", "PREFERRED", "(...4417)",
                        "Present", "balance", "$1,204.55",
                        "Available", "balance", "$8,795.45",
                        "Minimum", "payment", "due", "$35.00",
                        "Transaction", "activity",
                        "08/24", "Coffee", "Roasters", "-6.75",
                        "08/22", "Airline", "Tickets", "-412.30",
                        "08/20", "Payment", "Thank", "You", "-500.00"));
    }

    private RulePack seededBankPack() {
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals("BANK_STATEMENT"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active BANK_STATEMENT pack for the dev org"));
    }

    private Map<String, Object> classifyOnePage(UUID packageId, List<UUID> pageIds, String idem) {
        assertThat(
                        parserPort
                                .run(
                                        new ParserPort.StageRequest(
                                                UUID.randomUUID(),
                                                packageId,
                                                ProcessingStatus.CLASSIFYING,
                                                1,
                                                idem))
                                .success())
                .isTrue();
        return jdbc.queryForMap(
                "SELECT * FROM classification_result WHERE subject_id = ? AND is_current",
                pageIds.get(0));
    }

    private static double packScore(JsonNode evidence, String packType) {
        return java.util.stream.StreamSupport.stream(evidence.get("scores").spliterator(), false)
                .filter(node -> node.get("packType").asText().equals(packType))
                .findFirst()
                .orElseThrow(() -> new AssertionError(packType + " absent from the score list"))
                .get("score")
                .asDouble();
    }

    @Test
    void the_real_bank_dialect_classifies_as_a_BANK_STATEMENT() throws Exception {
        // The regression the fixture exists for: this vocabulary scores 0.50 on 1.0.0 — below its
        // own threshold, so it landed UNKNOWN and yielded no fields at all.
        UUID packageId = insertPackage("bank-dialect-it");
        List<UUID> pageIds = insertFixturePages(packageId, "bank_statement_chase");

        Map<String, Object> result = classifyOnePage(packageId, pageIds, "bank-dialect-idem");

        assertThat(result.get("document_type_code")).isEqualTo("BANK_STATEMENT");
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
        assertThat(result.get("rule_pack_version")).isEqualTo("1.1.0");
    }

    @Test
    void an_online_activity_print_out_classifies_as_a_BANK_STATEMENT() throws Exception {
        // The observed document itself. Under 1.0.0 not one anchor matched — a flat 0.00.
        UUID packageId = insertPackage("online-printout-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, onlinePrintOutPage());

        Map<String, Object> result = classifyOnePage(packageId, pageIds, "online-printout-idem");

        assertThat(result.get("document_type_code")).isEqualTo("BANK_STATEMENT");
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
    }

    @Test
    void an_online_CREDIT_CARD_print_out_does_not_classify_as_a_BANK_STATEMENT() throws Exception {
        // The false positive the online dialect could have bought. Same bank, same print header,
        // same balance vocabulary — a liability document, and the pack must refuse it.
        UUID packageId = insertPackage("credit-card-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, creditCardPrintOutPage());

        Map<String, Object> result = classifyOnePage(packageId, pageIds, "credit-card-idem");

        assertThat(result.get("document_type_code")).isNotEqualTo("BANK_STATEMENT");

        // And the pack's own recorded score must sit BELOW its threshold — a page that merely lost
        // to some other pack would not prove anything (W2PackExclusivityIT's rule, same reason).
        JsonNode evidence = JSON.readTree(result.get("evidence").toString());
        RulePack bank = seededBankPack();
        assertThat(packScore(evidence, "BANK_STATEMENT")).isLessThan(bank.minConfidence());
    }

    @Test
    void the_online_view_anchors_cannot_reach_the_packs_threshold_alone() {
        RulePack bank = seededBankPack();

        // Guards the test itself: a pack version that renamed these anchors away would sum 0 and
        // pass vacuously — exactly the trap W2PackExclusivityIT documents.
        assertThat(
                        bank.anchors().stream()
                                .map(Anchor::id)
                                .filter(ONLINE_VIEW_FURNITURE::contains)
                                .toList())
                .containsExactlyInAnyOrderElementsOf(ONLINE_VIEW_FURNITURE);

        double furniture =
                bank.anchors().stream()
                        .filter(anchor -> ONLINE_VIEW_FURNITURE.contains(anchor.id()))
                        .mapToDouble(Anchor::weight)
                        .sum();

        // 2 + 1 + 1 + 1 = 5 of targetScore 10 = 0.50 < 0.60. The pack CANNOT qualify without at
        // least one deposit-account signal, which is what keeps a credit card out.
        assertThat(Math.min(1.0, furniture / bank.targetScore())).isLessThan(bank.minConfidence());
    }

    @Test
    void the_original_synthetic_fixture_still_classifies_above_its_threshold() throws Exception {
        // The other half of the contract: teaching the pack a second dialect must not cost recall
        // on the first. bank_statement.pdf loses a point as stmt-period is demoted 3 -> 2 and
        // stays far above the bar on the anchors it keeps.
        UUID packageId = insertPackage("bank-recall-it");
        List<UUID> pageIds = insertFixturePages(packageId, "bank_statement");

        Map<String, Object> result = classifyOnePage(packageId, pageIds, "bank-recall-idem");

        assertThat(result.get("document_type_code")).isEqualTo("BANK_STATEMENT");
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
    }
}
