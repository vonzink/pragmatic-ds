package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationPort;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationRequest;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationRequest.CandidatePage;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationResult;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationResult.PageTypeProposal;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * The CLASSIFYING stage's model fallback end to end, with the gate ON and a scriptable port
 * standing where Gemini will — the same shape as {@code BoundaryExtractionStageIT}, deliberately,
 * because the two model seams answer to the same anchoring discipline and a reviewer should be able
 * to read one against the other.
 *
 * <p>The package under test is the whole point of the feature: a confidently-typed paystub page
 * followed by pages no rule pack can name. Three of the tests are the three ways the model can be
 * wrong or right — it anchors and wins, it hallucinates and is refused, or it answers about a page
 * it was never asked about (one the packs already typed) and is refused there too. Only the first
 * writes anything. The other two pin the run's identity: the stage digest moves when a retype
 * happens, and an enabled model stage is never describable.
 */
@TestPropertySource(properties = "docengine.ai.page-classification.enabled=true")
@Import(AiPageClassificationStageIT.ScriptablePortConfig.class)
class AiPageClassificationStageIT extends AbstractExtractionIT {

    /** Scriptable, recording stand-in for the live adapter. */
    static final class ScriptablePort implements PageTypeClassificationPort {
        final List<PageTypeClassificationRequest> requests = new CopyOnWriteArrayList<>();
        volatile Function<PageTypeClassificationRequest, PageTypeClassificationResult> script =
                request ->
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK, List.of(), AiTokenCounts.ZERO);

        @Override
        public PageTypeClassificationResult classify(PageTypeClassificationRequest request) {
            requests.add(request);
            return script.apply(request);
        }
    }

    static final ScriptablePort PORT = new ScriptablePort();

    @TestConfiguration
    static class ScriptablePortConfig {
        @Bean
        @Primary
        PageTypeClassificationPort scriptablePageTypeClassificationPort() {
            return PORT;
        }
    }

    @BeforeEach
    void resetPort() {
        PORT.requests.clear();
        PORT.script =
                request ->
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK, List.of(), AiTokenCounts.ZERO);
    }

    /** Top-of-page text of every untypable page — what an honest quote must match. */
    private static final String RIDER_HEADER = "Terms and Conditions Addendum";

    /** A type the model may name: a real {@code document_type} code, not an invented one. */
    private static final String PROPOSED_TYPE = "VOE";

    /** Page 0 is a real paystub the packs type; pages 1-3 are untypable. */
    private UUID gluePackage() {
        UUID packageId = insertPackage("ai-page-classification-it-" + UUID.randomUUID());
        ArrayNode pages = truth("paystub_complete").get("pages").deepCopy();
        for (int rider = 0; rider < 3; rider++) {
            pages.add(riderPage(rider));
        }
        insertFixturePages(packageId, ORG_DEV, pages);
        return packageId;
    }

    /**
     * One untypable page: a header in the top band (y=40 of 792pt, inside the head fraction the
     * request reads), body words below. Each page's words differ so the duplicate detector — which
     * would skip the page entirely — stays out of the way.
     */
    private ObjectNode riderPage(int rider) {
        ObjectNode page = JSON.createObjectNode();
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        ArrayNode words = page.putArray("words");
        double x = 72;
        for (String text : (RIDER_HEADER + " Section " + (char) ('A' + rider)).split(" ")) {
            words.add(word(text, x, 40));
            x += 90;
        }
        words.add(word("This", 72, 400));
        words.add(word("page", 160, 400));
        words.add(word("intentionally", 248, 400));
        words.add(word("untypable", 380, 400));
        words.add(word(String.valueOf(rider), 470, 400));
        return page;
    }

    private ObjectNode word(String text, double x, double y) {
        ObjectNode word = JSON.createObjectNode();
        word.put("text", text);
        word.put("x", x);
        word.put("y", y);
        word.put("width", 80.0);
        word.put("height", 12.0);
        return word;
    }

    private UUID pageIdAt(UUID packageId, int packagePageIndex) {
        return jdbc.queryForObject(
                "SELECT id FROM page WHERE package_id = ? AND package_page_index = ?",
                UUID.class,
                packageId,
                packagePageIndex);
    }

    /** The one current classification row for a page: type, method, pack version. */
    private Map<String, Object> currentClassificationOf(UUID pageId) {
        return jdbc.queryForMap(
                "SELECT document_type_code, method, rule_pack_version FROM classification_result"
                        + " WHERE subject_type = 'PAGE' AND subject_id = ? AND is_current",
                pageId);
    }

    /** The package's documents after SPLITTING: type and member page indexes, in ordinal order. */
    private List<Map<String, Object>> documentsOf(UUID packageId) {
        return jdbc.queryForList(
                "SELECT d.document_type_code AS type,"
                    + " string_agg(p.package_page_index::text, ',' ORDER BY p.package_page_index)"
                    + " AS page_indexes"
                    + " FROM logical_document d"
                    + " JOIN logical_document_page dp ON dp.logical_document_id = d.id"
                    + " JOIN page p ON p.id = dp.page_id"
                    + " WHERE d.package_id = ?"
                    + " GROUP BY d.id, d.ordinal, d.document_type_code"
                    + " ORDER BY d.ordinal",
                packageId);
    }

    private int ledgerRowsFor(UUID pageId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ai_interpretation WHERE subject_type = 'PAGE'"
                        + " AND subject_id = ? AND interpretation ->> 'stage' = 'PAGE_CLASSIFICATION'",
                Integer.class,
                pageId);
    }

    @Test
    void an_anchored_proposal_retypes_the_unknown_page_as_LLM_and_records_the_call() {
        UUID packageId = gluePackage();
        PORT.script =
                request ->
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK,
                                List.of(
                                        new PageTypeProposal(
                                                request.pages().get(0).pageId(),
                                                PROPOSED_TYPE,
                                                new BigDecimal("0.91"),
                                                RIDER_HEADER)),
                                new AiTokenCounts(900, 30, 0, 0));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.CLASSIFYING);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.detail())
                .containsEntry("aiStatus", "OK")
                .containsEntry("pagesTypedByModel", 1)
                .containsEntry("pagesRefusedByGate", 0);
        // Only the pages the packs left UNKNOWN were ever asked about.
        assertThat(PORT.requests).hasSize(1);
        assertThat(PORT.requests.get(0).pages())
                .extracting(CandidatePage::packagePageIndex)
                .containsExactly(1, 2, 3);

        UUID retyped = pageIdAt(packageId, 1);
        assertThat(currentClassificationOf(retyped))
                .containsEntry("document_type_code", PROPOSED_TYPE)
                .containsEntry("method", "LLM")
                // No pack decided this, and borrowing the version of a pack that FAILED would
                // misattribute the answer to a rule that never fired.
                .containsEntry("rule_pack_version", null);
        // The cost ledger is the reason an always-on fallback is affordable to run at all.
        assertThat(ledgerRowsFor(PORT.requests.get(0).pages().get(0).pageId())).isEqualTo(1);
    }

    @Test
    void a_hallucinated_quote_is_refused_and_the_page_stays_UNKNOWN() {
        // The adversarial test IS the design: a plausible type at a high confidence, quoting text
        // that appears nowhere on the page, must write NOTHING — not a downgraded row, not a
        // lower-confidence one. A model that cannot point at evidence has not classified anything.
        UUID packageId = gluePackage();
        PORT.script =
                request ->
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK,
                                List.of(
                                        new PageTypeProposal(
                                                request.pages().get(0).pageId(),
                                                PROPOSED_TYPE,
                                                new BigDecimal("0.99"),
                                                "Uniform Residential Loan Application")),
                                new AiTokenCounts(900, 30, 0, 0));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.CLASSIFYING);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.detail())
                .containsEntry("pagesTypedByModel", 0)
                .containsEntry("pagesRefusedByGate", 1);
        assertThat(currentClassificationOf(pageIdAt(packageId, 1)))
                .containsEntry("document_type_code", PageClassifier.UNKNOWN)
                .containsEntry("method", "RULE_ANCHOR");
    }

    /**
     * The reuse invariant, stated where it can fail: an enabled model stage must make the run
     * non-describable, or a parse the model enriched gets served by parse-once reuse to a later
     * upload that ran without it — the same bytes coming back better-typed than the pipeline can
     * currently reproduce.
     */
    @Test
    void an_enabled_model_stage_makes_the_run_non_describable() {
        assertThat(parserPort.behaviorIdentity()).isEmpty();
    }

    @Test
    void the_stage_digest_changes_when_the_model_retypes_a_page() {
        // The deterministic tuples are built BEFORE the retypes, so without the model's outcome in
        // the digest a replay that typed a page would be indistinguishable from one that did not.
        UUID packageId = gluePackage();
        String deterministicOnly = runStage(packageId, ProcessingStatus.CLASSIFYING).outputDigest();

        PORT.script =
                request ->
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK,
                                List.of(
                                        new PageTypeProposal(
                                                request.pages().get(0).pageId(),
                                                PROPOSED_TYPE,
                                                new BigDecimal("0.91"),
                                                RIDER_HEADER)),
                                new AiTokenCounts(900, 30, 0, 0));
        String withRetype = runStage(packageId, ProcessingStatus.CLASSIFYING).outputDigest();

        assertThat(withRetype).isNotEqualTo(deterministicOnly);
    }

    @Test
    void a_page_the_packs_typed_is_never_offered_to_the_model_and_never_retyped() {
        // The invariant the whole feature rests on: a model opinion can never displace a
        // deterministic one. The paystub page is not in the batch, and an answer about it — the
        // shape a reordering or over-eager model would produce — is refused rather than applied.
        UUID packageId = gluePackage();
        UUID paystubPage = pageIdAt(packageId, 0);
        PORT.script =
                request ->
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK,
                                List.of(
                                        new PageTypeProposal(
                                                paystubPage,
                                                "W2",
                                                new BigDecimal("0.99"),
                                                RIDER_HEADER)),
                                new AiTokenCounts(900, 30, 0, 0));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.CLASSIFYING);

        assertThat(outcome.success()).isTrue();
        assertThat(PORT.requests.get(0).pages())
                .extracting(CandidatePage::pageId)
                .doesNotContain(paystubPage);
        assertThat(outcome.detail()).containsEntry("pagesTypedByModel", 0);
        assertThat(currentClassificationOf(paystubPage))
                .containsEntry("document_type_code", "PAYSTUB")
                .containsEntry("method", "RULE_ANCHOR");
    }

    // ── what a retype does to the stages downstream of it ───────────────────

    /**
     * The baseline the next test is read against: with the stage on but the model proposing
     * nothing, the untyped rider pages are CONTINUATIONS and the whole package is one document.
     * This is today's behaviour, and it is asserted here so the change the retype causes is a
     * visible delta rather than an assertion nobody can calibrate.
     */
    @Test
    void with_no_proposal_the_untyped_pages_stay_continuations_of_the_typed_document() {
        UUID packageId = gluePackage();

        runStage(packageId, ProcessingStatus.CLASSIFYING);
        assertThat(runStage(packageId, ProcessingStatus.SPLITTING).success()).isTrue();

        assertThat(documentsOf(packageId))
                .extracting("type", "page_indexes")
                .containsExactly(tuple("PAYSTUB", "0,1,2,3"));
    }

    /**
     * The blast radius, pinned rather than assumed. {@code PackageSplitter.group} starts a new
     * document on a TYPED page whose type differs from the open run's, and an UNKNOWN page never
     * starts one — so a single anchored retype in the MIDDLE of an untyped run cuts one document
     * into two, and the pages after the retype follow the NEW document's type.
     *
     * <p>That is the intended consequence of typing a page, not a defect: the same cut a rule pack
     * would have caused had it recognised the page. It is pinned because it is the one downstream
     * effect a reader of the classification stage alone would never see coming — the stage reports
     * "1 page retyped" and the package silently arrives at extraction as two documents.
     */
    @Test
    void one_anchored_retype_mid_run_cuts_the_glued_document_in_two() {
        UUID packageId = gluePackage();
        PORT.script =
                request ->
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK,
                                List.of(
                                        new PageTypeProposal(
                                                // pages() is ascending: index 1 here is package
                                                // page 2, the MIDDLE of the untyped run.
                                                request.pages().get(1).pageId(),
                                                PROPOSED_TYPE,
                                                new BigDecimal("0.91"),
                                                RIDER_HEADER)),
                                new AiTokenCounts(900, 30, 0, 0));

        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).detail())
                .containsEntry("pagesTypedByModel", 1);
        assertThat(runStage(packageId, ProcessingStatus.SPLITTING).success()).isTrue();

        assertThat(documentsOf(packageId))
                .extracting("type", "page_indexes")
                .containsExactly(
                        tuple("PAYSTUB", "0,1"),
                        // Page 3 stays UNKNOWN and joins the run the retype opened — a retype
                        // reaches pages the model was never even asked about.
                        tuple(PROPOSED_TYPE, "2,3"));
    }
}
