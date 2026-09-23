package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiExtractionStatus;
import com.pragmaticds.docengine.platform.ai.AiSecondPass;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase G — the confidence-triggered second pass, gate ON, both providers canned. What is pinned:
 * the SELECTOR (a document with a review-flagged field earns exactly one stronger read, after the
 * first pass), the PRECEDENCE (the deterministic row survives whatever the stronger reader says —
 * agreement or a conflict suggestion, never an overwrite), the LEDGER (second-pass calls are
 * recorded under their own prompt-version marker, which is what G3's reviewer-queue measurement
 * groups by), and the FAILURE posture (a second-pass provider error is logged and skipped — the
 * first pass already applied, and a refinement must never un-apply it).
 *
 * <p>The off-state needs no test here: every context without {@code
 * docengine.ai.second-pass.enabled} runs the first pass alone, and {@code AiExtractionStageIT}'s
 * exact request counts would fail on any stray second call.
 */
@Import({AiExtractionStageIT.AiTestConfig.class, AiSecondPassIT.SecondPassConfig.class})
@TestPropertySource(
        properties = {
            "docengine.ai.enabled=true",
            "docengine.ai.second-pass.enabled=true",
            "spring.main.allow-bean-definition-overriding=true"
        })
class AiSecondPassIT extends AbstractExtractionIT {

    static final AiExtractionStageIT.CannedAiPort SECOND_PORT =
            new AiExtractionStageIT.CannedAiPort();

    @TestConfiguration
    static class SecondPassConfig {
        @Bean
        @Primary
        AiSecondPass cannedAiSecondPass() {
            return new AiSecondPass(SECOND_PORT);
        }
    }

    @Autowired AiExtractionStageIT.CannedAiPort ai;

    @BeforeEach
    void resetPorts() {
        ai.recorded().clear();
        ai.respondWith(AiExtractionStageIT.successfulExtraction());
        SECOND_PORT.recorded().clear();
        SECOND_PORT.respondWith(AiExtractionStageIT.successfulExtraction());
    }

    private record Prepared(UUID packageId, UUID documentId, UUID flaggedFieldId, String method) {}

    /** A processed bank statement with ONE deterministic field flagged for review — the selector's trigger. */
    private Prepared preparedWithShakyField() {
        UUID packageId = insertPackage("ai-second-pass-" + UUID.randomUUID());
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        UUID documentId =
                jdbc.queryForObject(
                        "SELECT id FROM logical_document WHERE package_id = ?", UUID.class, packageId);
        Map<String, Object> flagged =
                jdbc.queryForMap(
                        """
                        SELECT id, extraction_method FROM extracted_field
                         WHERE logical_document_id = ? AND is_current
                           AND extraction_method NOT IN ('NONE', 'AI')
                         ORDER BY field_name LIMIT 1
                        """,
                        documentId);
        UUID flaggedId = (UUID) flagged.get("id");
        jdbc.update(
                "UPDATE extracted_field SET validation_status = 'MANUAL_REVIEW_REQUIRED'"
                        + " WHERE id = ?",
                flaggedId);
        return new Prepared(
                packageId, documentId, flaggedId, (String) flagged.get("extraction_method"));
    }

    @Test
    void a_review_flagged_document_earns_one_stronger_read_that_cannot_overwrite_it() {
        Prepared prepared = preparedWithShakyField();

        StageOutcome outcome = runStage(prepared.packageId(), ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(ai.recorded()).hasSize(1);
        assertThat(SECOND_PORT.recorded()).as("exactly one Pro-tier read").hasSize(1);
        // Precedence: the deterministic row is still the current answer, method untouched —
        // whatever the stronger reader said, it could only agree or leave a conflict suggestion.
        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT extraction_method, is_current FROM extracted_field WHERE id = ?",
                        prepared.flaggedFieldId());
        assertThat(after.get("is_current")).isEqualTo(true);
        assertThat(after.get("extraction_method")).isEqualTo(prepared.method());
        // G3's measurement hook: second-pass calls are their own ledger population.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM ai_interpretation"
                                        + " WHERE subject_id = ? AND prompt_version = ?",
                                Integer.class,
                                prepared.documentId(),
                                "bank-statement/1.1.1+second-pass"))
                .isEqualTo(1);
    }

    @Test
    void a_second_pass_provider_error_is_recorded_and_never_unapplies_the_first_pass() {
        Prepared prepared = preparedWithShakyField();
        SECOND_PORT.respondWith(
                new AiExtractionResult(
                        null,
                        "canned",
                        "canned",
                        AiExtractionStatus.ERROR,
                        AiTokenCounts.ZERO,
                        "provider_transient"));

        StageOutcome outcome = runStage(prepared.packageId(), ProcessingStatus.AI_EXTRACTION);

        // The refinement failing is not the stage failing: the first pass applied and stays.
        assertThat(outcome.success()).isTrue();
        assertThat(SECOND_PORT.recorded()).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM ai_interpretation"
                                        + " WHERE subject_id = ? AND prompt_version = ?",
                                Integer.class,
                                prepared.documentId(),
                                "bank-statement/1.1.1+second-pass"))
                .isEqualTo(1);
        Map<String, Object> after =
                jdbc.queryForMap(
                        "SELECT extraction_method, is_current FROM extracted_field WHERE id = ?",
                        prepared.flaggedFieldId());
        assertThat(after.get("is_current")).isEqualTo(true);
        assertThat(after.get("extraction_method")).isEqualTo(prepared.method());
    }
}
