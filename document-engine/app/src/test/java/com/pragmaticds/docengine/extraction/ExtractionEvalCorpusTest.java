package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.EvalCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loader, without Spring or Docker: the committed corpus resolves truth for every case, and
 * the directory form — the one the gitignored real-document tree uses — discovers types and cases
 * from directory names alone and refuses a case filed under the wrong type.
 */
class ExtractionEvalCorpusTest {

    private static final String CASE =
            """
            {
              "id": "sample",
              "layout": "NONE",
              "note": "a labelled document",
              "documents": [{"type": "%s"}]
            }
            """;

    /** The truth shape a corpus case carries: worker words per page, hand-labelled expectations. */
    private static final String TRUTH =
            """
            {
              "pages": [
                {"pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "contentRotation": 0,
                 "words": [
                   {"text": "Wages", "x": 40.0, "y": 100.0, "width": 30.0, "height": 8.0},
                   {"text": "1,234.56", "x": 40.0, "y": 112.0, "width": 40.0, "height": 8.0}
                 ]}
              ],
              "expectedFields": [
                {"field": "wages", "displayedText": "1,234.56", "normalized": {"number": "1234.56"}, "pageIndex": 0},
                {"field": "tips", "displayedText": null, "method": "NONE", "normalized": null, "pageIndex": 0}
              ]
            }
            """;

    @Test
    void the_committed_corpus_carries_truth_pages_for_every_case() {
        ExtractionEvalCorpus corpus = ExtractionEvalCorpus.load();

        assertThat(corpus.cases()).isNotEmpty();
        for (EvalCase testCase : corpus.cases()) {
            assertThat(testCase.truthPages().size()).as("%s truth pages", testCase.id()).isPositive();
            assertThat(testCase.synthetic()).as("%s is synthetic", testCase.id()).isTrue();
        }
    }

    @Test
    void a_directory_corpus_is_discovered_by_type_then_case(@TempDir Path root) throws IOException {
        writeCase(root, "W2", "sample", "W2");

        ExtractionEvalCorpus corpus = ExtractionEvalCorpus.loadDirectory(root);

        assertThat(corpus.cases()).hasSize(1);
        EvalCase loaded = corpus.caseById("sample");
        assertThat(loaded.synthetic()).as("a directory case is real unless it says otherwise").isFalse();
        assertThat(loaded.fixture()).as("no fixture name: the id stands in").isEqualTo("sample");
        assertThat(loaded.truthPages()).hasSize(1);
        assertThat(loaded.documents()).hasSize(1);
        assertThat(loaded.documents().get(0).type()).isEqualTo("W2");
        assertThat(loaded.documents().get(0).fields()).containsOnlyKeys("wages#", "tips#");
        assertThat(loaded.documents().get(0).fields().get("wages#").mustCapture()).isTrue();
        assertThat(loaded.documents().get(0).fields().get("tips#").mustCapture()).isFalse();
        assertThat(corpus.coveredTypes()).containsExactly("W2");
    }

    @Test
    void a_case_filed_under_the_wrong_type_directory_is_rejected(@TempDir Path root)
            throws IOException {
        writeCase(root, "W2", "sample", "PAYSTUB");

        assertThatThrownBy(() -> ExtractionEvalCorpus.loadDirectory(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filed under W2 but declares a PAYSTUB document");
    }

    @Test
    void a_case_without_its_truth_is_rejected_rather_than_scored_as_empty(@TempDir Path root)
            throws IOException {
        Path caseDir = Files.createDirectories(root.resolve("W2").resolve("sample"));
        Files.writeString(caseDir.resolve(ExtractionEvalCorpus.CASE_FILE), CASE.formatted("W2"));

        assertThatThrownBy(() -> ExtractionEvalCorpus.loadDirectory(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no truth.json");
    }

    @Test
    void a_missing_or_empty_directory_loads_no_cases(@TempDir Path root) {
        assertThat(ExtractionEvalCorpus.loadDirectory(root.resolve("absent")).cases()).isEmpty();
        assertThat(ExtractionEvalCorpus.loadDirectory(root).cases()).isEmpty();
    }

    @Test
    void an_absent_baseline_file_gates_nothing(@TempDir Path root) throws IOException {
        assertThat(ExtractionEvalCorpus.baselineAt(root.resolve("baseline.json")).isEmpty()).isTrue();

        Files.writeString(
                root.resolve("baseline.json"), "{\"byType\": {\"W2\": {\"completeness\": 0.3}}}");
        assertThat(ExtractionEvalCorpus.baselineAt(root.resolve("baseline.json")).byType())
                .containsKey("W2");
    }

    private static void writeCase(Path root, String typeDir, String id, String declaredType)
            throws IOException {
        Path caseDir = Files.createDirectories(root.resolve(typeDir).resolve(id));
        Files.writeString(caseDir.resolve(ExtractionEvalCorpus.CASE_FILE), CASE.formatted(declaredType));
        Files.writeString(caseDir.resolve(ExtractionEvalCorpus.TRUTH_FILE), TRUTH);
    }
}
