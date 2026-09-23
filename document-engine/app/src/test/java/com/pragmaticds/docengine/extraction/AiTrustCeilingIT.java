package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiExtractionStatus;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Summary;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase H — the handwriting trust ceiling, end to end through the real stage (owner decision:
 * flag, don't bend the math). The three confidence components stay honest measurements; what the
 * engine is willing to ASSERT is controlled at {@code validationStatus} by an OR of one
 * structural rule and two mechanical ones, and the WHY lands in the evidence so the queue reads
 * "verify this handwritten amount", never an unexplained flag.
 *
 * <p>The three legs, each isolated: a model-tagged HANDWRITTEN value flags even when perfectly
 * anchored to trusted native text; a value anchored ONLY to below-floor OCR spans flags
 * WEAK_ANCHOR even though the match succeeded — the garbage-glyph hole a handwriting misread
 * slips through; and the same value with the same anchor and no tag does NOT flag, proving the
 * ceiling adds no blanket suspicion. (UNANCHORED, the structural leg, has been review-flagged
 * since Phase 3 and keeps its behaviour.)
 */
@Import(AiExtractionStageIT.AiTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.ai.enabled=true",
            "spring.main.allow-bean-definition-overriding=true"
        })
class AiTrustCeilingIT extends AbstractExtractionIT {

    private static final String ADDRESS = "123 Main Street";

    @Autowired AiExtractionStageIT.CannedAiPort ai;

    @BeforeEach
    void resetAi() {
        ai.recorded().clear();
        ai.respondWith(AiExtractionStageIT.successfulExtraction());
    }

    private UUID preparedPackage() {
        UUID packageId = insertPackage("ai-trust-ceiling-" + UUID.randomUUID());
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    /** Plants the canned address on page 1 as three consecutive spans of the given source/confidence. */
    private void plantAddressSpans(UUID packageId, String source, String confidence) {
        UUID pageOne =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ? ORDER BY package_page_index LIMIT 1",
                        UUID.class,
                        packageId);
        Integer nextOrdinal =
                jdbc.queryForObject(
                        "SELECT coalesce(max(ordinal), -1) + 1 FROM text_span WHERE page_id = ?",
                        Integer.class,
                        pageOne);
        double x = 72;
        for (String word : ADDRESS.split(" ")) {
            jdbc.update(
                    """
                    INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width, height,
                        source, confidence)
                    VALUES (?, ?, ?, ?, ?, 500, 60, 12, ?, ?::numeric)
                    """,
                    ORG_DEV,
                    pageOne,
                    nextOrdinal++,
                    word,
                    x,
                    source,
                    confidence);
            x += 70;
        }
    }

    private Map<String, Object> addressRow(UUID packageId) {
        return jdbc.queryForMap(
                """
                SELECT f.validation_status, f.normalized_json::text AS evidence
                  FROM extracted_field f
                  JOIN logical_document d ON d.id = f.logical_document_id
                 WHERE d.package_id = ? AND f.field_name = 'accountHolderAddress' AND f.is_current
                """,
                packageId);
    }

    /** The canned extraction with the address cell model-tagged handwritten. */
    private static AiExtractionResult handwrittenAddressExtraction() {
        BankStatementExtraction base =
                (BankStatementExtraction) AiExtractionStageIT.successfulExtraction().extraction();
        Summary summary = base.summary();
        Summary tagged =
                new Summary(
                        summary.bankName(),
                        summary.accountHolderName(),
                        new TextCell(
                                ADDRESS,
                                ADDRESS,
                                1,
                                BankStatementExtraction.Confidence.HIGH,
                                true),
                        summary.accountNumber(),
                        summary.statementPeriodStart(),
                        summary.statementPeriodEnd(),
                        summary.beginningBalance(),
                        summary.endingBalance(),
                        summary.totalDeposits(),
                        summary.totalWithdrawals());
        return new AiExtractionResult(
                "{}",
                new BankStatementExtraction(tagged, base.transactions(), base.checks()),
                "test",
                "synthetic",
                AiExtractionStatus.OK,
                new AiTokenCounts(120, 42, 80, 40),
                null);
    }

    @Test
    void a_model_tagged_handwritten_value_is_flagged_even_when_perfectly_anchored() {
        UUID packageId = preparedPackage();
        // Trusted NATIVE spans: the anchor MATCHES and is strong — only the tag can flag it.
        plantAddressSpans(packageId, "NATIVE", "1.0");
        ai.respondWith(handwrittenAddressExtraction());

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        Map<String, Object> row = addressRow(packageId);
        assertThat(row.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat((String) row.get("evidence"))
                .contains("reviewReasons")
                .contains("HANDWRITTEN")
                .doesNotContain("WEAK_ANCHOR")
                .contains("\"anchorStatus\": \"MATCHED\"");
    }

    @Test
    void a_value_anchored_only_to_below_floor_ocr_spans_is_flagged_WEAK_ANCHOR() {
        // The garbage-span hole: OCR "matched" the printed characters at 0.20 confidence — a
        // match against glyphs nobody trusts is not proof, and before Phase H it read as one.
        UUID packageId = preparedPackage();
        plantAddressSpans(packageId, "OCR", "0.20");

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        Map<String, Object> row = addressRow(packageId);
        assertThat(row.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat((String) row.get("evidence"))
                .contains("WEAK_ANCHOR")
                .contains("\"anchorStatus\": \"MATCHED\"")
                .doesNotContain("HANDWRITTEN");
    }

    @Test
    void the_same_value_untagged_and_well_anchored_is_not_flagged() {
        // The control that keeps the ceiling honest: no tag, trusted anchor — the OR-rule stays
        // silent and the value keeps whatever status it earned. Blanket suspicion would sink
        // every clean AI value to the bottom of the review queue and bury the flagged ones.
        UUID packageId = preparedPackage();
        plantAddressSpans(packageId, "NATIVE", "1.0");

        assertThat(runStage(packageId, ProcessingStatus.AI_EXTRACTION).success()).isTrue();

        Map<String, Object> row = addressRow(packageId);
        assertThat(row.get("validation_status")).isNotEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat((String) row.get("evidence")).doesNotContain("reviewReasons");
    }
}
