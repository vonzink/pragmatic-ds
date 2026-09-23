package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.AnalysisRun;
import com.pragmaticds.rag.repository.AnalysisRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the analysis_runs manifest row for every terminal analyze outcome.
 * MUST NEVER fail the run: any persistence error is logged and swallowed.
 *
 * <p>This is also the REDACTION boundary: {@code findings} (borrower data) is
 * stored only when ragbrain.rag.analyze.persist-findings is true. With the
 * flag off, {@code calcAudit} rows are trimmed to {@code id/method/status/
 * error} (dropping {@code name} — often model-authored free text that can
 * carry a borrower name — and {@code inputs}/{@code value}, which carry the
 * borrower's actual income figures), and {@code docs}/{@code skipped} rows
 * drop {@code fileName} (mortgage filenames routinely embed surnames, e.g.
 * "Smith_John_W2_2025.pdf"). {@code id} + {@code sha256} still identify the
 * document without naming the borrower. The redaction lives here — not in
 * {@link com.pragmaticds.rag.service.analyze.calc.CalculationExecutor} or
 * {@link RunManifest} — because the executor's full audit list and the
 * manifest's in-memory doc list are legitimately used elsewhere (the envelope
 * sent to the suite, shadow-mode tooling) for the lifetime of the request;
 * only the persisted row is narrowed.
 */
@Service
public class AnalysisRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunRecorder.class);

    private final AnalysisRunRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean persistFindings;

    public AnalysisRunRecorder(AnalysisRunRepository repository,
                               ObjectMapper objectMapper,
                               @Value("${ragbrain.rag.analyze.persist-findings:false}") boolean persistFindings) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.persistFindings = persistFindings;
    }

    public void save(RunManifest m, AnalysisResult r, int attempts) {
        try {
            repository.save(row(m, r, attempts, persistFindings));
        } catch (Exception e) {
            log.error("Failed to persist analysis_runs row for {} run {}: {}",
                    m.analyzerSlug(), m.runId(), e.getMessage());
        }
    }

    /**
     * The Lab's REQUIRED persistence path: a parsed run is not a success until this row exists.
     *
     * <p>Two differences from {@link #save}, both deliberate:
     *
     * <ul>
     *   <li><b>Always metadata only.</b> {@code findings} is never written and the redactions are
     *       applied unconditionally, <em>including</em> when the global
     *       {@code ragbrain.rag.analyze.persist-findings} is on. That flag is an operator's choice
     *       about the raw suite path; it is not consent to store parsed borrower values in a Lab
     *       row whose whole point is that the payload lives encrypted elsewhere.
     *   <li><b>Never swallowed.</b> A persistence failure propagates as a payload-free
     *       {@link RecorderException}, because a Lab run that reported success without its
     *       analyzer row would be a dangling identity the Lab schema forbids.
     * </ul>
     *
     * @throws RecorderException with a stable code; the underlying cause is neither attached nor
     *     logged, so a constraint message quoting a stored value cannot escape here
     */
    public void saveRequired(RunManifest m, AnalysisResult r, int attempts) {
        try {
            repository.saveAndFlush(row(m, r, attempts, false));
        } catch (RuntimeException persistenceFailure) {
            log.error("Lab analysis_runs write failed for run {} ({})",
                    m.runId(), persistenceFailure.getClass().getSimpleName());
            throw new RecorderException(RecorderException.Code.ANALYSIS_RUN_PERSIST_FAILED);
        }
        if (!repository.existsById(m.runId())) {
            log.error("Lab analysis_runs row {} was absent after a successful write", m.runId());
            throw new RecorderException(RecorderException.Code.ANALYSIS_RUN_ABSENT_AFTER_WRITE);
        }
    }

    private AnalysisRun row(RunManifest m, AnalysisResult r, int attempts, boolean withFindings) {
        AnalysisRun run = new AnalysisRun();
        run.setId(m.runId());
        run.setBrainId(m.brainId());
        run.setAnalyzerSlug(m.analyzerSlug());
        run.setEnvelopeVersion(m.envelopeVersion());
        run.setStatus(r.status().name());
        run.setErrorReason(r.reason());
        run.setProvider(r.provider());
        run.setModel(r.model());
        run.setPromptSha256(m.promptSha256());
        run.setAttempts(attempts);
        run.setInputTokens(r.inputTokens());
        run.setOutputTokens(r.outputTokens());
        run.setCostUsd(r.costUsd());
        run.setDocCount(m.docs().size());
        run.setPageCount(r.pageCount());
        run.setDocs(redactFileNames(m.docs(), withFindings));
        run.setSkipped(redactFileNames(objectMapper.convertValue(r.skippedDocs(),
                new TypeReference<List<Map<String, Object>>>() {}), withFindings));
        run.setFiltered(objectMapper.convertValue(r.filtered(),
                new TypeReference<List<Map<String, Object>>>() {}));
        run.setRetrievedChunkIds(m.retrievedChunkIds());
        run.setCalcAudit(redactCalcAudit(m.calcAudit(), withFindings));
        if (withFindings && r.findingsJson() != null) {
            try {
                run.setFindings(objectMapper.readValue(r.findingsJson(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (com.fasterxml.jackson.core.JsonProcessingException unparseable) {
                throw new IllegalStateException("findings JSON is unparseable", unparseable);
            }
        }
        return run;
    }

    /** A payload-free required-persistence failure: a stable code and nothing else. */
    public static final class RecorderException extends RuntimeException {

        /** Stable, value-free recorder failure taxonomy. */
        public enum Code {
            /** The write itself failed; the database's own message is deliberately discarded. */
            ANALYSIS_RUN_PERSIST_FAILED,
            /** The write reported success but the caller-allocated row is not readable. */
            ANALYSIS_RUN_ABSENT_AFTER_WRITE
        }

        private final Code code;

        public RecorderException(Code code) {
            super(java.util.Objects.requireNonNull(code, "code").name(), null, false, true);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /**
     * Drops the {@code fileName} key from each row unless {@code persistFindings} is
     * on. Used for both {@code docs} (from {@link RunManifest}) and {@code skipped}
     * (from {@link AnalysisResult#skippedDocs()}) — both carry an {@code id} +
     * (for docs) {@code sha256} that identify the document without naming the
     * borrower whose surname is often embedded in the original filename.
     */
    private List<Map<String, Object>> redactFileNames(List<Map<String, Object>> rows,
                                                      boolean keepEverything) {
        if (keepEverything || rows == null) {
            return rows;
        }
        List<Map<String, Object>> redacted = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("fileName");
            redacted.add(copy);
        }
        return redacted;
    }

    /**
     * With {@code persistFindings} off, trims each audit row to the calculation
     * SHAPE only: {@code id}, {@code method}, {@code status}, {@code error}.
     * {@code error} is safe to keep unconditionally — {@code IncomeCalcService}
     * messages name fields, never values (e.g. "missing or non-numeric input:
     * annual"). {@code name} (often model-authored free text that can carry a
     * borrower name), {@code inputs}, and {@code value} (the borrower's actual
     * income figures) are dropped until the flag is on.
     */
    private List<Map<String, Object>> redactCalcAudit(List<Map<String, Object>> audit,
                                                      boolean keepEverything) {
        if (keepEverything || audit == null) {
            return audit;
        }
        List<Map<String, Object>> redacted = new ArrayList<>(audit.size());
        for (Map<String, Object> row : audit) {
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("id", row.get("id"));
            shape.put("method", row.get("method"));
            shape.put("status", row.get("status"));
            shape.put("error", row.get("error"));
            redacted.add(shape);
        }
        return redacted;
    }
}
