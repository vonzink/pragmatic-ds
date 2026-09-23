package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec 3's split end-to-end: the nine-page combined_package_v2 fixture — one page of each of the
 * seven schema-bearing types, one blank, one exact duplicate — classifies and splits into EXACTLY
 * its truth's expectedDocuments, with the blank and duplicate pages exactly the unassigned ones.
 * The Spec 1 SplittingStageIT shape, pointed at the Spec 3 type roster.
 */
class CombinedPackageV2SplitIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    private StageOutcome runStage(UUID packageId, ProcessingStatus stage) {
        return parserPort.run(
                new ParserPort.StageRequest(UUID.randomUUID(), packageId, stage, 1, "it-idem"));
    }

    @Test
    void the_combined_v2_package_splits_into_one_document_per_type() {
        UUID packageId = insertPackage("combined-v2-split-it");
        List<UUID> pageIds = insertFixturePages(packageId, "combined_package_v2");

        assertThat(runStage(packageId, ProcessingStatus.CLASSIFYING).success()).isTrue();
        StageOutcome split = runStage(packageId, ProcessingStatus.SPLITTING);
        assertThat(split.success()).isTrue();
        assertThat(split.outputDigest()).isNotNull();

        // The truth's expectedDocuments ARE the assertion — types and page sets in order.
        JsonNode expectedDocuments = truth("combined_package_v2").get("expectedDocuments");
        List<Map<String, Object>> documents =
                jdbc.queryForList(
                        "SELECT * FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        packageId);
        assertThat(documents).hasSize(expectedDocuments.size());
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> document = documents.get(i);
            JsonNode expected = expectedDocuments.get(i);
            assertThat(document.get("ordinal")).isEqualTo(i);
            assertThat(document.get("document_type_code"))
                    .isEqualTo(expected.get("type").asText());

            List<UUID> expectedPages = new ArrayList<>();
            expected.get("pages").forEach(index -> expectedPages.add(pageIds.get(index.asInt())));
            List<UUID> actualPages =
                    jdbc.queryForList(
                            "SELECT page_id FROM logical_document_page"
                                    + " WHERE logical_document_id = ? ORDER BY ordinal",
                            UUID.class,
                            (UUID) document.get("id"));
            assertThat(actualPages).isEqualTo(expectedPages);
        }

        // Unassigned pages are EXACTLY the truth's: blank 7, duplicate 8 — no link rows.
        JsonNode unassigned = truth("combined_package_v2").get("unassignedPages");
        List<UUID> expectedUnassigned = new ArrayList<>();
        unassigned.forEach(index -> expectedUnassigned.add(pageIds.get(index.asInt())));
        List<UUID> linked =
                jdbc.queryForList(
                        """
                        SELECT page_id FROM logical_document_page WHERE logical_document_id IN
                            (SELECT id FROM logical_document WHERE package_id = ?)
                        """,
                        UUID.class,
                        packageId);
        assertThat(linked)
                .doesNotContainAnyElementsOf(expectedUnassigned)
                .hasSize(pageIds.size() - expectedUnassigned.size());
    }
}
