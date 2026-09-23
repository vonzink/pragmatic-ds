package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase C's end-to-end proof: three consecutive monthly statements in one upload become THREE
 * documents.
 *
 * <p>This is the case both older cut rules are blind to. Every page of {@code
 * bank_statement_three} classifies {@code BANK_STATEMENT}, so no type ever changes; every page
 * prints the same bank, the same account holder and the same account number, so no anchor
 * distinguishes one statement's header from the next. Before Phase C the splitter produced ONE
 * six-page document — which then reported a single beginning balance for three months and failed
 * an arithmetic reconciliation it was never wrong about.
 *
 * <p>What separates them is the printed statement period, which is what the instance-key probe
 * reads. The fixture repeats each period on BOTH of its statement's pages on purpose (real
 * statements reprint their header), so it also proves the probe cuts on a CHANGED key and not
 * merely on the presence of one.
 */
class ConsecutiveStatementSplitIT extends AbstractExtractionIT {

    private void classifyAndSplit(UUID packageId) {
        for (ProcessingStatus stage :
                List.of(ProcessingStatus.CLASSIFYING, ProcessingStatus.SPLITTING)) {
            assertThat(runStage(packageId, stage).success())
                    .as("stage %s succeeds", stage)
                    .isTrue();
        }
    }

    private record Document(UUID id, int ordinal, String provenance) {}

    private List<Document> documentsOf(UUID packageId) {
        return jdbc.query(
                """
                SELECT id, ordinal, boundary_provenance FROM logical_document
                 WHERE package_id = ? ORDER BY ordinal
                """,
                (rs, row) -> new Document(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)),
                packageId);
    }

    private List<Integer> pageIndicesOf(UUID documentId) {
        return jdbc.queryForList(
                """
                SELECT p.package_page_index FROM logical_document_page lp
                  JOIN page p ON p.id = lp.page_id
                 WHERE lp.logical_document_id = ? ORDER BY lp.ordinal
                """,
                Integer.class,
                documentId);
    }

    @Test
    void three_consecutive_statements_become_three_documents() {
        UUID packageId = insertPackage("consecutive-statements-it");
        List<UUID> pageIds = insertFixturePages(packageId, "bank_statement_three");
        assertThat(pageIds).hasSize(6);

        classifyAndSplit(packageId);

        List<Document> documents = documentsOf(packageId);
        assertThat(documents)
                .as("one document per statement, not one document per upload")
                .hasSize(3);

        assertThat(pageIndicesOf(documents.get(0).id())).containsExactly(0, 1);
        assertThat(pageIndicesOf(documents.get(1).id())).containsExactly(2, 3);
        assertThat(pageIndicesOf(documents.get(2).id())).containsExactly(4, 5);
    }

    @Test
    void the_two_cuts_are_recorded_as_instance_changes_and_the_first_as_the_package_start() {
        UUID packageId = insertPackage("consecutive-statements-provenance-it");
        insertFixturePages(packageId, "bank_statement_three");

        classifyAndSplit(packageId);

        List<Document> documents = documentsOf(packageId);
        assertThat(documents).hasSize(3);
        // Nothing was inferred here: the first document opens the package, and the other two were
        // cut because the engine READ a different period. A reviewer sorting by "which boundaries
        // are only inferred" must not be sent to any of these three.
        assertThat(documents.get(0).provenance()).isEqualTo("PACKAGE_START");
        assertThat(documents.get(1).provenance()).isEqualTo("INSTANCE_CHANGE");
        assertThat(documents.get(2).provenance()).isEqualTo("INSTANCE_CHANGE");
    }

    @Test
    void every_page_still_classifies_as_one_type_so_no_older_rule_could_have_cut_here() {
        // The assertion that makes the two above load-bearing rather than lucky: if the pages
        // classified as different types, or one carried a startsDocument anchor, the split would
        // be explained by a rule that predates Phase C and this fixture would prove nothing.
        UUID packageId = insertPackage("consecutive-statements-control-it");
        insertFixturePages(packageId, "bank_statement_three");

        classifyAndSplit(packageId);

        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT DISTINCT document_type_code FROM classification_result
                                 WHERE subject_type = 'PAGE' AND is_current
                                   AND subject_id IN (SELECT id FROM page WHERE package_id = ?)
                                """,
                                String.class,
                                packageId))
                .containsExactly("BANK_STATEMENT");
    }

    @Test
    void splitting_is_idempotent_across_the_instance_pass() {
        // SPLITTING deletes and recreates a package's documents on every run, and the instance
        // pass re-derives its boundaries from the same pages each time. A replay must therefore
        // converge on the same three documents rather than accumulating or reverting to one —
        // the property that lets Phase C skip persisting its boundaries at all (roadmap R2).
        UUID packageId = insertPackage("consecutive-statements-replay-it");
        insertFixturePages(packageId, "bank_statement_three");

        classifyAndSplit(packageId);
        List<Integer> firstRun = documentsOf(packageId).stream().map(Document::ordinal).toList();

        assertThat(runStage(packageId, ProcessingStatus.SPLITTING).success()).isTrue();

        List<Document> replayed = documentsOf(packageId);
        assertThat(replayed).hasSize(3);
        assertThat(replayed.stream().map(Document::ordinal).toList()).isEqualTo(firstRun);
        assertThat(replayed.stream().map(Document::provenance).toList())
                .containsExactly("PACKAGE_START", "INSTANCE_CHANGE", "INSTANCE_CHANGE");
        assertThat(pageIndicesOf(replayed.get(1).id())).containsExactly(2, 3);
    }
}
