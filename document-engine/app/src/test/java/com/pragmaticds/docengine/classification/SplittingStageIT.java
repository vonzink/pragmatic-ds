package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The SPLITTING stage — plan acceptance criterion 1 above all: the 20-page combined fixture
 * splits into EXACTLY its truth's expectedDocuments, with the blank and duplicate pages exactly
 * the unassigned ones. Plus the transparency rule in isolation and stage-retry idempotency.
 */
class SplittingStageIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    private StageOutcome runStage(UUID packageId, ProcessingStatus stage) {
        return parserPort.run(
                new ParserPort.StageRequest(UUID.randomUUID(), packageId, stage, 1, "it-idem"));
    }

    private List<Map<String, Object>> documentsOf(UUID packageId) {
        return jdbc.queryForList(
                "SELECT * FROM logical_document WHERE package_id = ? ORDER BY ordinal", packageId);
    }

    private List<UUID> pagesOf(UUID documentId) {
        return jdbc.queryForList(
                        "SELECT page_id FROM logical_document_page WHERE logical_document_id = ?"
                                + " ORDER BY ordinal",
                        UUID.class,
                        documentId);
    }

    @Test
    void the_combined_package_splits_into_exactly_the_expected_documents() {
        UUID packageId = insertPackage("combined-split-it");
        List<UUID> pageIds = insertFixturePages(packageId, "combined_package");

        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();
        StageOutcome split = runStage(packageId, ProcessingStatus.SPLITTING);
        assertThat(split.success()).isTrue();
        assertThat(split.outputDigest()).isNotNull();

        // The truth's expectedDocuments ARE the assertion — types and page-index sets in order.
        JsonNode expectedDocuments = truth("combined_package").get("expectedDocuments");
        List<Map<String, Object>> documents = documentsOf(packageId);
        assertThat(documents).hasSize(expectedDocuments.size());
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> document = documents.get(i);
            JsonNode expected = expectedDocuments.get(i);
            assertThat(document.get("ordinal")).isEqualTo(i);
            assertThat(document.get("document_type_code")).isEqualTo(expected.get("type").asText());
            assertThat(document.get("review_status")).isEqualTo("NOT_REVIEWED");

            List<UUID> expectedPages = new ArrayList<>();
            expected.get("pages").forEach(index -> expectedPages.add(pageIds.get(index.asInt())));
            assertThat(pagesOf((UUID) document.get("id"))).isEqualTo(expectedPages);
        }

        // Unassigned pages are EXACTLY the truth's: blank 9, duplicates 10-11 — no link rows.
        JsonNode unassigned = truth("combined_package").get("unassignedPages");
        List<UUID> linked =
                jdbc.queryForList(
                        """
                        SELECT page_id FROM logical_document_page WHERE logical_document_id IN
                            (SELECT id FROM logical_document WHERE package_id = ?)
                        """,
                        UUID.class,
                        packageId);
        List<UUID> expectedUnassigned = new ArrayList<>();
        unassigned.forEach(index -> expectedUnassigned.add(pageIds.get(index.asInt())));
        assertThat(linked)
                .doesNotContainAnyElementsOf(expectedUnassigned)
                .hasSize(pageIds.size() - expectedUnassigned.size());

        // Document confidence is the MIN of member pages: the six-page bank run contains the
        // interior pages that score 0.8 while first and last score 1.0.
        assertThat((BigDecimal) documents.get(1).get("classification_confidence"))
                .isEqualByComparingTo("0.8");
    }

    @Test
    void paystub_blank_paystub_is_ONE_two_page_document() {
        JsonNode combined = truth("combined_package").get("pages");
        ArrayNode pages = JSON.createArrayNode();
        pages.add(combined.get(0)).add(combined.get(9)).add(combined.get(1));
        UUID packageId = insertPackage("transparency-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, pages);

        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();
        assertThat(runStage(packageId, ProcessingStatus.SPLITTING).success()).isTrue();

        List<Map<String, Object>> documents = documentsOf(packageId);
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).get("document_type_code")).isEqualTo("PAYSTUB");
        assertThat(pagesOf((UUID) documents.get(0).get("id")))
                .containsExactly(pageIds.get(0), pageIds.get(2));
    }

    @Test
    void rerunning_SPLITTING_converges_instead_of_duplicating() {
        UUID packageId = insertPackage("split-retry-it");
        insertFixturePages(packageId, "combined_package");

        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();
        StageOutcome first = runStage(packageId, ProcessingStatus.SPLITTING);
        StageOutcome second = runStage(packageId, ProcessingStatus.SPLITTING);
        assertThat(second.success()).isTrue();
        assertThat(second.outputDigest()).isEqualTo(first.outputDigest());

        Integer documents =
                jdbc.queryForObject(
                        "SELECT count(*) FROM logical_document WHERE package_id = ?",
                        Integer.class,
                        packageId);
        Integer links =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM logical_document_page WHERE logical_document_id IN
                            (SELECT id FROM logical_document WHERE package_id = ?)
                        """,
                        Integer.class,
                        packageId);
        // THREE, not four: the letter pages 12-19 classify UNKNOWN end to end and an
        // untyped page continues the open run instead of starting a document, so they extend the
        // W-2. The LINK count is unchanged at 17 — the same 20 pages minus the 3 transparent
        // ones — which is the assertion that proves the pages moved rather than vanished.
        assertThat(documents).isEqualTo(3);
        assertThat(links).isEqualTo(17);
    }
}
