package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The CONTRACT: off means byte-identical. With {@code docengine.boundary-extraction.enabled}
 * unset (the default — this class deliberately adds no properties, so it runs in the same Spring
 * context every other extraction IT uses), the stage skips before touching a repository, writes
 * no ledger row, and the split is exactly what the deterministic pass produced.
 */
class BoundaryExtractionDisabledIT extends AbstractExtractionIT {

    @Test
    void the_stage_skips_writes_nothing_and_the_split_is_untouched() {
        UUID packageId = insertPackage("boundary-extraction-disabled-it");
        insertFixturePages(packageId, "bank_statement_three");
        for (ProcessingStatus stage :
                List.of(ProcessingStatus.CLASSIFYING, ProcessingStatus.SPLITTING)) {
            assertThat(runStage(packageId, stage).success()).isTrue();
        }
        List<String> before =
                jdbc.queryForList(
                        "SELECT id || ':' || boundary_provenance FROM logical_document"
                                + " WHERE package_id = ? ORDER BY ordinal",
                        String.class,
                        packageId);
        assertThat(before).hasSize(3); // Phase C's split, the baseline being protected

        StageOutcome outcome = runStage(packageId, ProcessingStatus.BOUNDARY_EXTRACTION);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.skipReason()).isEqualTo("BOUNDARY_EXTRACTION_DISABLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM boundary_proposal WHERE package_id = ?",
                                Integer.class,
                                packageId))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT id || ':' || boundary_provenance FROM logical_document"
                                        + " WHERE package_id = ? ORDER BY ordinal",
                                String.class,
                                packageId))
                .isEqualTo(before); // same rows, same ids — the stage touched nothing
    }
}
