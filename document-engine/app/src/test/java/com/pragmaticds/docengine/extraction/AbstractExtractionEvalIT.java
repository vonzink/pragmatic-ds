package com.pragmaticds.docengine.extraction;

import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.Baseline;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.EvalCase;
import com.pragmaticds.docengine.extraction.ExtractionEvalScorer.ActualDocument;
import com.pragmaticds.docengine.extraction.ExtractionEvalScorer.ActualField;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one runner behind both evaluation ITs: seed a case's words as text spans, drive CLASSIFYING →
 * SPLITTING → EXTRACTING through the real pipeline (no AI, no worker, no network), read back what
 * the engine persisted, score it, write the report pair. {@code ExtractionEvalIT} feeds it the
 * committed synthetic corpus on every build; {@code CorpusExtractionEvalIT} feeds it the gitignored
 * real-document tree on demand. Neither knows a document type — a case's type is a string in its
 * file, and the scorer tallies under that string.
 *
 * <p>Fields are read from {@code extracted_field} rather than from {@code GET
 * /v1/documents/{id}/fields} on purpose: the API masks sensitive values ({@code •••-••-6789}),
 * and a masked SSN can never equal its expectation, so scoring through the API would report every
 * sensitive field as wrong. The rows are the engine's output; the API is a view of them.
 */
abstract class AbstractExtractionEvalIT extends AbstractExtractionIT {

    static final Path REPORT_DIR = Path.of("build/reports/extraction-eval");

    /** Set {@code -Ddocengine.eval.calibrate=true} to regenerate the floors instead of gating. */
    static final boolean CALIBRATE =
            Boolean.parseBoolean(System.getProperty("docengine.eval.calibrate", "false"));

    /** Every case through the pipeline, scored. */
    protected ExtractionEvalReport evaluate(ExtractionEvalCorpus corpus) {
        Map<String, List<ActualDocument>> observed = new LinkedHashMap<>();
        for (EvalCase testCase : corpus.cases()) {
            observed.put(testCase.id(), run(testCase));
        }
        return new ExtractionEvalScorer().score(corpus, observed);
    }

    /**
     * Score, report, and gate: {@code <prefix>summary.json} + {@code <prefix>summary.md} always;
     * {@code <prefix>baseline.json} instead of a verdict when calibrating.
     *
     * @return the gate result — the caller decides whether a floor-less baseline is acceptable
     */
    protected ExtractionEvalGate.Result report(
            ExtractionEvalReport report, Baseline baseline, String prefix) {
        ExtractionEvalGate.Result result = ExtractionEvalGate.check(report, baseline);
        report.writeJson(REPORT_DIR.resolve(prefix + "summary.json"));
        ExtractionEvalReport.write(REPORT_DIR.resolve(prefix + "summary.md"), result.toMarkdown());
        System.out.println(report.toSummaryTable());

        if (CALIBRATE) {
            Path proposed = REPORT_DIR.resolve(prefix + ExtractionEvalCorpus.BASELINE_FILE);
            ExtractionEvalReport.write(proposed, ExtractionEvalGate.calibrate(report));
            System.out.println(
                    "Calibration mode: wrote proposed floors to "
                            + proposed.toAbsolutePath()
                            + "\nReview the diff before committing them as the baseline.");
        }
        return result;
    }

    /** One case: seed the words, run the pipeline, read back what the engine produced. */
    protected List<ActualDocument> run(EvalCase testCase) {
        UUID packageId = insertPackage("extraction-eval-" + testCase.id());
        insertFixturePages(packageId, ORG_DEV, testCase.truthPages());
        if (testCase.needsLayout()) {
            insertFixtureLayout(packageId, testCase.truth(), testCase.ruled());
        }
        runPipelineToExtraction(packageId);
        return documentsOf(packageId);
    }

    private List<ActualDocument> documentsOf(UUID packageId) {
        List<ActualDocument> documents = new ArrayList<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT id, document_type_code FROM logical_document"
                                + " WHERE package_id = ? ORDER BY ordinal",
                        packageId)) {
            documents.add(
                    new ActualDocument(
                            (String) row.get("document_type_code"),
                            fieldsOf((UUID) row.get("id"))));
        }
        return documents;
    }

    /**
     * Current field rows with their evidence count, keyed by {@code fieldName#groupKey} — the
     * coordinate a grouped field actually has. Keying by name alone would keep whichever occurrence
     * sorted last and silently discard the rest, the failure {@code currentFieldsByName} throws on.
     */
    private Map<String, ActualField> fieldsOf(UUID logicalDocumentId) {
        Map<String, ActualField> fields = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        """
                        SELECT f.field_name, f.group_key, f.displayed_text, f.normalized_text,
                               f.normalized_number, f.normalized_date, f.extraction_method,
                               f.is_sensitive,
                               (SELECT count(*) FROM field_evidence e
                                 WHERE e.extracted_field_id = f.id) AS evidence_count
                          FROM extracted_field f
                         WHERE f.logical_document_id = ? AND f.is_current
                         ORDER BY f.field_name, f.group_key NULLS FIRST
                        """,
                        logicalDocumentId)) {
            Object groupKey = row.get("group_key");
            Object date = row.get("normalized_date");
            fields.put(
                    row.get("field_name") + "#" + (groupKey == null ? "" : groupKey),
                    new ActualField(
                            (String) row.get("displayed_text"),
                            (String) row.get("normalized_text"),
                            (BigDecimal) row.get("normalized_number"),
                            date == null ? null : date.toString(),
                            (String) row.get("extraction_method"),
                            Boolean.TRUE.equals(row.get("is_sensitive")),
                            ((Number) row.get("evidence_count")).intValue()));
        }
        return fields;
    }
}
