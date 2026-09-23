package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.fail;

import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.Baseline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The same harness over REAL documents — the gitignored {@code corpus/eval/<TYPE>/<case>/} tree —
 * on demand only: {@code ./gradlew corpusEval -PcorpusEval=true}. Tagged {@code corpus-eval}, which
 * the ordinary {@code test} task excludes, so CI never looks for the directory and never could
 * score a borrower's document. The Gradle task additionally refuses to start while anything under
 * {@code corpus/} is tracked by git.
 *
 * <p>Why a second IT rather than a flag on {@code ExtractionEvalIT}: the synthetic run is gated
 * against the committed baseline and must stay green on every build; this run is gated against
 * {@code corpus/eval/baseline.json} if one exists and otherwise only reports. Their reports land
 * side by side ({@code summary.md} and {@code corpus-summary.md}) so the fixture number and the
 * real-form number can be read as the same metric on different input — which is the comparison
 * issue #59 is about.
 *
 * <p>The directory is passed as {@code -Ddocengine.eval.corpus-dir} by the task. Missing directory
 * or zero cases FAILS rather than passes: a manual run that silently scored nothing is the kind of
 * green nobody should trust.
 */
@Tag("corpus-eval")
class CorpusExtractionEvalIT extends AbstractExtractionEvalIT {

    private static final String CORPUS_DIR_PROPERTY = "docengine.eval.corpus-dir";

    @Test
    void every_real_document_type_is_scored_and_reported() {
        String configured = System.getProperty(CORPUS_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            fail(
                    "no corpus directory: run through `./gradlew corpusEval -PcorpusEval=true`, which"
                            + " sets -D" + CORPUS_DIR_PROPERTY);
        }
        Path root = Path.of(configured);
        // A previous run's report describes a previous run's documents. It must not outlive the
        // run that wrote it, whatever this run goes on to do.
        for (String stale : List.of("corpus-summary.json", "corpus-summary.md", "corpus-baseline.json")) {
            try {
                Files.deleteIfExists(REPORT_DIR.resolve(stale));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        ExtractionEvalCorpus corpus = ExtractionEvalCorpus.loadDirectory(root);
        if (corpus.cases().isEmpty()) {
            fail(
                    "no corpus cases under "
                            + root.toAbsolutePath()
                            + " — expected <TYPE>/<case>/"
                            + ExtractionEvalCorpus.CASE_FILE
                            + " beside "
                            + ExtractionEvalCorpus.TRUTH_FILE
                            + " (see corpus/README.md)");
        }

        ExtractionEvalReport report = evaluate(corpus);
        Baseline baseline =
                ExtractionEvalCorpus.baselineAt(root.resolve(ExtractionEvalCorpus.BASELINE_FILE));
        ExtractionEvalGate.Result result = report(report, baseline, "corpus-");
        // The harness is deterministic. Two types run an AI stage in production that this run did
        // not exercise; their rows measure the rule-pack layer only, and the report must say so.
        Path summary = REPORT_DIR.resolve("corpus-summary.md");
        try {
            Files.writeString(
                    summary,
                    "\n> **Deterministic path only.** BANK_STATEMENT and PAYSTUB run an AI extraction"
                            + " stage in production that this run does not exercise; their rows above"
                            + " measure the rule packs alone.\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        if (CALIBRATE) {
            return;
        }
        if (baseline.isEmpty()) {
            System.out.println(
                    "corpus-eval: no "
                            + root.resolve(ExtractionEvalCorpus.BASELINE_FILE)
                            + " so nothing is gated; the report is at "
                            + REPORT_DIR.resolve("corpus-summary.md").toAbsolutePath());
            return;
        }
        if (!result.passed()) {
            fail(result.describe());
        }
    }
}
