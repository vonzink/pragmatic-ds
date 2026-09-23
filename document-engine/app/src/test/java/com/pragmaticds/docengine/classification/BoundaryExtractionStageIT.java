package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionPort;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionRequest;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionRequest.CandidatePage;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionResult;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionResult.ProposedBoundary;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionStatus;
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
 * The BOUNDARY_EXTRACTION stage end to end, with the gate ON and a scriptable port standing where
 * the model will (Phase E). The package under test is the glue case the windows exist for: one
 * confidently-classified paystub page followed by three pages the classifier cannot type, which
 * the splitter (correctly, by its own documented trade-off) absorbs into the paystub — one
 * four-page document where a human sees two.
 *
 * <p>The adversarial test here IS the design (design §11, roadmap D6): a plausible boundary whose
 * quote appears nowhere on the page must produce ZERO cuts and ONE {@code REJECTED_QUOTE_MATCH}
 * row — recorded, never silently dropped, never downgraded-and-kept.
 */
@TestPropertySource(
        properties = {
            "docengine.boundary-extraction.enabled=true",
            // Small enough for one scripted 1200-token call to exhaust it — the E4 cap test.
            // Irrelevant to every other test here: the cap is checked BEFORE each call, so a
            // package with one window is always sent.
            "docengine.boundary-extraction.max-input-tokens=1000"
        })
@Import(BoundaryExtractionStageIT.ScriptablePortConfig.class)
class BoundaryExtractionStageIT extends AbstractExtractionIT {

    /** Scriptable, recording stand-in for the Phase E adapter. */
    static final class ScriptablePort implements BoundaryExtractionPort {
        final List<BoundaryExtractionRequest> requests = new CopyOnWriteArrayList<>();
        volatile Function<BoundaryExtractionRequest, BoundaryExtractionResult> script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.OK, List.of(), AiTokenCounts.ZERO);

        @Override
        public BoundaryExtractionResult proposeBoundaries(BoundaryExtractionRequest request) {
            requests.add(request);
            return script.apply(request);
        }
    }

    static final ScriptablePort PORT = new ScriptablePort();

    @TestConfiguration
    static class ScriptablePortConfig {
        @Bean
        @Primary
        BoundaryExtractionPort scriptableBoundaryExtractionPort() {
            return PORT;
        }
    }

    @BeforeEach
    void resetPort() {
        PORT.requests.clear();
        PORT.script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.OK, List.of(), AiTokenCounts.ZERO);
    }

    // ── the glue package: PAYSTUB page 0, untypable rider pages 1-3 ─────────

    /** Top-of-page text of the first rider page — what an honest quote must match. */
    private static final String RIDER_HEADER = "Terms and Conditions Addendum";

    private UUID gluePackage() {
        UUID packageId = insertPackage("boundary-extraction-it-" + UUID.randomUUID());
        ArrayNode pages = truth("paystub_complete").get("pages").deepCopy();
        for (int rider = 0; rider < 3; rider++) {
            pages.add(riderPage(rider));
        }
        insertFixturePages(packageId, ORG_DEV, pages);
        return packageId;
    }

    /**
     * One untypable page: a header in the top band (y=40 of 792pt), body words below. Each page's
     * words differ so the duplicate detector stays out of the way.
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

    private void classifyAndSplit(UUID packageId) {
        for (ProcessingStatus stage :
                List.of(ProcessingStatus.CLASSIFYING, ProcessingStatus.SPLITTING)) {
            assertThat(runStage(packageId, stage).success())
                    .as("stage %s succeeds", stage)
                    .isTrue();
        }
    }

    private record Document(int ordinal, String type, String provenance, int pageCount) {}

    private List<Document> documentsOf(UUID packageId) {
        return jdbc.query(
                """
                SELECT d.ordinal, d.document_type_code, d.boundary_provenance,
                       (SELECT count(*) FROM logical_document_page lp
                         WHERE lp.logical_document_id = d.id) AS pages
                  FROM logical_document d WHERE d.package_id = ? ORDER BY d.ordinal
                """,
                (rs, row) ->
                        new Document(
                                rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                packageId);
    }

    private List<Map<String, Object>> proposalsOf(UUID packageId) {
        return jdbc.queryForList(
                "SELECT package_page_index, verdict FROM boundary_proposal WHERE package_id = ?"
                        + " ORDER BY created_at",
                packageId);
    }

    @Test
    void the_window_sent_is_the_unknown_run_with_its_typed_anchor_page() {
        UUID packageId = gluePackage();
        classifyAndSplit(packageId);

        assertThat(runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION).success()).isTrue();

        // One window: the three untyped pages padded by the confirmed paystub page. The model
        // sees a page whose type is KNOWN as its anchor — that is what the padding is for.
        assertThat(PORT.requests).hasSize(1);
        List<CandidatePage> sent = PORT.requests.get(0).pages();
        assertThat(sent).extracting(CandidatePage::packagePageIndex).containsExactly(0, 1, 2, 3);
        assertThat(sent.get(0).deterministicTypeCode()).isEqualTo("PAYSTUB");
        assertThat(sent.get(1).deterministicTypeCode()).isEqualTo(PageClassifier.UNKNOWN);
        assertThat(sent.get(1).headText()).contains("Terms").contains("Conditions");
        assertThat(PORT.requests.get(0).taxonomy()).isNotEmpty();
    }

    @Test
    void the_adversarial_case_zero_cuts_and_one_rejected_quote_match_row() {
        // The model proposes a PLAUSIBLE boundary at an in-window page — with a quote that
        // appears nowhere on it. This is the test that makes the feature trustworthy.
        UUID packageId = gluePackage();
        classifyAndSplit(packageId);
        PORT.script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.OK,
                                List.of(
                                        new ProposedBoundary(
                                                1,
                                                "W2",
                                                new BigDecimal("0.99"),
                                                "Uniform Residential Loan Application",
                                                null)),
                                new AiTokenCounts(1200, 40, 0, 0));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.detail()).containsEntry("accepted", 0).containsEntry("proposals", 1);
        // ZERO cuts: the split stands exactly as the deterministic pass left it.
        assertThat(documentsOf(packageId))
                .containsExactly(new Document(0, "PAYSTUB", "PACKAGE_START", 4));
        // ONE row, refused by name — recorded for the reviewer, never silently dropped.
        List<Map<String, Object>> rows = proposalsOf(packageId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("package_page_index")).isEqualTo(1);
        assertThat(rows.get(0).get("verdict")).isEqualTo("REJECTED_QUOTE_MATCH");
    }

    @Test
    void an_anchored_proposal_cuts_the_glue_and_the_document_reads_AI() {
        UUID packageId = gluePackage();
        classifyAndSplit(packageId);
        PORT.script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.OK,
                                List.of(
                                        new ProposedBoundary(
                                                1,
                                                PageClassifier.UNKNOWN,
                                                new BigDecimal("0.88"),
                                                RIDER_HEADER,
                                                null)),
                                new AiTokenCounts(1200, 40, 0, 0));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.detail()).containsEntry("accepted", 1);
        assertThat(documentsOf(packageId))
                .containsExactly(
                        new Document(0, "PAYSTUB", "PACKAGE_START", 1),
                        new Document(1, PageClassifier.UNKNOWN, "AI", 3));
        assertThat(proposalsOf(packageId))
                .singleElement()
                .satisfies(row -> assertThat(row.get("verdict")).isEqualTo("ACCEPTED"));
    }

    @Test
    void a_replayed_SPLITTING_converges_on_the_accepted_cut_without_recalling_the_model() {
        // Roadmap R2: split() deletes and recreates documents on every run. The accepted cut
        // must survive because the re-split READS the ledger — not because anyone remembered.
        UUID packageId = gluePackage();
        classifyAndSplit(packageId);
        PORT.script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.OK,
                                List.of(
                                        new ProposedBoundary(
                                                1,
                                                PageClassifier.UNKNOWN,
                                                new BigDecimal("0.88"),
                                                RIDER_HEADER,
                                                null)),
                                AiTokenCounts.ZERO);
        assertThat(runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION).success()).isTrue();
        int callsAfterStage = PORT.requests.size();

        assertThat(runStage(packageId, ProcessingStatus.SPLITTING).success()).isTrue();

        assertThat(PORT.requests).hasSize(callsAfterStage); // the replay spent nothing
        assertThat(documentsOf(packageId))
                .containsExactly(
                        new Document(0, "PAYSTUB", "PACKAGE_START", 1),
                        new Document(1, PageClassifier.UNKNOWN, "AI", 3));
    }

    @Test
    void a_package_with_no_ambiguity_never_calls_the_model() {
        // The cost model: everything outside a window is never sent, and a clean package HAS no
        // window — the stage succeeds having spent zero tokens.
        UUID packageId = insertPackage("boundary-extraction-clean-" + UUID.randomUUID());
        insertFixturePages(packageId, "paystub_complete");
        classifyAndSplit(packageId);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.detail()).containsEntry("windows", 0);
        assertThat(PORT.requests).isEmpty();
        assertThat(proposalsOf(packageId)).isEmpty();
    }

    @Test
    void the_token_budget_stops_further_windows_and_counts_what_it_skipped() {
        // E4 (owner-adopted cap): two separate ambiguity windows; the first call reports 1200
        // input tokens against a 1000-token cap, so the second window is never sent — and the
        // stage says so in its counters instead of silently truncating.
        UUID packageId = insertPackage("boundary-budget-it-" + UUID.randomUUID());
        ArrayNode pages = truth("paystub_complete").get("pages").deepCopy();
        for (int rider = 0; rider < 3; rider++) {
            pages.add(riderPage(rider));
        }
        // Three distinct paystub pages keep the two rider runs far enough apart that their
        // windows do not merge (padding meets at a gap of one, which the planner joins).
        for (int copy = 0; copy < 3; copy++) {
            ObjectNode stub = (ObjectNode) truth("paystub_complete").get("pages").get(0).deepCopy();
            ObjectNode marker = ((ArrayNode) stub.get("words")).addObject();
            marker.put("text", "BUDGET-STUB-" + copy);
            marker.put("x", 72.0);
            marker.put("y", 700.0);
            marker.put("width", 120.0);
            marker.put("height", 10.0);
            pages.add(stub);
        }
        for (int rider = 3; rider < 6; rider++) {
            pages.add(riderPage(rider));
        }
        insertFixturePages(packageId, ORG_DEV, pages);
        classifyAndSplit(packageId);
        PORT.script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.OK,
                                List.of(),
                                new AiTokenCounts(1200, 10, 0, 0));

        StageOutcome outcome = runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(PORT.requests).hasSize(1);
        assertThat(outcome.detail())
                .containsEntry("windows", 2)
                .containsEntry("windowsSkippedByBudget", 1);
    }

    @Test
    void a_package_a_human_has_reshaped_is_never_entered_at_all() {
        // Precedence's strongest rule, applied to the stage itself: the re-split deletes and
        // recreates documents, so a package carrying any human decision must be refused BEFORE
        // a window is computed or a token spent — not gated proposal by proposal.
        UUID packageId = gluePackage();
        classifyAndSplit(packageId);
        jdbc.update(
                "UPDATE logical_document SET boundary_provenance = 'HUMAN',"
                        + " classification_confidence = NULL WHERE package_id = ?",
                packageId);
        PORT.script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.OK,
                                List.of(
                                        new ProposedBoundary(
                                                1,
                                                PageClassifier.UNKNOWN,
                                                new BigDecimal("0.99"),
                                                RIDER_HEADER,
                                                null)),
                                AiTokenCounts.ZERO);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).isEqualTo("HUMAN_DECISIONS_PRESENT");
        assertThat(PORT.requests).isEmpty();
        assertThat(documentsOf(packageId))
                .containsExactly(new Document(0, "PAYSTUB", "HUMAN", 4));
    }

    @Test
    void a_provider_error_fails_the_attempt_and_records_no_partial_ledger() {
        UUID packageId = gluePackage();
        classifyAndSplit(packageId);
        PORT.script =
                request ->
                        new BoundaryExtractionResult(
                                BoundaryExtractionStatus.ERROR, List.of(), AiTokenCounts.ZERO);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isTrue();
        assertThat(proposalsOf(packageId)).isEmpty();
        // The deterministic split is untouched by the failure.
        assertThat(documentsOf(packageId))
                .containsExactly(new Document(0, "PAYSTUB", "PACKAGE_START", 4));
    }
}
