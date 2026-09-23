package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.ai.AiExtractionRequest;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase F — page images on the AI extraction path, WITH the gate on. The trigger rule (F2) is the
 * whole point: pixels ride along ONLY for a page whose OCR failed its floor, never
 * unconditionally — a healthy package with the gate on still sends text-only, because every image
 * is more NPI per call (signatures, photos, whole-page context; roadmap R10) and more tokens, and
 * the page that earns one is exactly the page whose text layer cannot be trusted.
 */
@Import(AiExtractionStageIT.AiTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.ai.enabled=true",
            "docengine.ai.page-images.enabled=true",
            "spring.main.allow-bean-definition-overriding=true"
        })
class AiPageImagesIT extends AbstractExtractionIT {

    private static final byte[] RENDER_PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    @Autowired AiExtractionStageIT.CannedAiPort ai;
    @Autowired BlobStoragePort storage;

    @BeforeEach
    void resetAi() {
        ai.recorded().clear();
        ai.respondWith(AiExtractionStageIT.successfulExtraction());
    }

    private UUID preparedPackage() {
        UUID packageId = insertPackage("ai-page-images-" + UUID.randomUUID());
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);
        return packageId;
    }

    @Test
    void a_page_that_failed_its_ocr_floor_ships_its_own_render_pixels() {
        UUID packageId = preparedPackage();
        UUID failedPage =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ? ORDER BY package_page_index DESC LIMIT 1",
                        UUID.class,
                        packageId);
        String renderKey = "it-render/" + failedPage;
        storage.put(renderKey, RENDER_PNG);
        jdbc.update(
                "UPDATE page SET ocr_fallback_reason = 'OCR_LOW_CONFIDENCE',"
                        + " render_storage_key = ? WHERE id = ?",
                renderKey,
                failedPage);
        Integer failedIndex =
                jdbc.queryForObject(
                        "SELECT package_page_index FROM page WHERE id = ?", Integer.class, failedPage);

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(ai.recorded()).hasSize(1);
        AiExtractionRequest request = ai.recorded().get(0);
        assertThat(request.pageImages()).hasSize(1);
        assertThat(request.pageImages().get(0).packagePageIndex()).isEqualTo(failedIndex);
        // The engine's OWN render blob — the same pixels a reviewer sees — not a re-render.
        assertThat(request.pageImages().get(0).png()).isEqualTo(RENDER_PNG);
        // The text input is unchanged by the escalation: pixels are ADDITIVE, never a substitute.
        assertThat(request.documentText()).isNotBlank();
    }

    @Test
    void healthy_pages_send_no_pixels_even_with_the_gate_on() {
        // The rule that keeps the compliance surface and the token bill honest: enabling the
        // feature changes nothing for a package whose OCR held — text-only, byte-for-byte.
        UUID packageId = preparedPackage();

        StageOutcome outcome = runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(outcome.success()).isTrue();
        assertThat(ai.recorded()).hasSize(1);
        assertThat(ai.recorded().get(0).pageImages()).isEmpty();
    }

    @Test
    void a_weak_ocr_median_below_the_floor_also_triggers_the_escalation() {
        UUID packageId = preparedPackage();
        UUID weakPage =
                jdbc.queryForObject(
                        "SELECT id FROM page WHERE package_id = ? ORDER BY package_page_index LIMIT 1",
                        UUID.class,
                        packageId);
        String renderKey = "it-render/" + weakPage;
        storage.put(renderKey, RENDER_PNG);
        jdbc.update(
                "UPDATE page SET ocr_confidence_median = 0.31, render_storage_key = ?"
                        + " WHERE id = ?",
                renderKey,
                weakPage);

        runStage(packageId, ProcessingStatus.AI_EXTRACTION);

        assertThat(ai.recorded()).hasSize(1);
        assertThat(ai.recorded().get(0).pageImages()).hasSize(1);
    }
}
