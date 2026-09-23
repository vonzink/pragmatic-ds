package com.pragmaticds.rag.lab.eval;

import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.Scenario;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry.ScenarioSet;
import com.pragmaticds.rag.lab.instance.VerifiedInstanceReleaseReader;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.run.domain.LabReleaseEvaluation;
import com.pragmaticds.rag.lab.run.repository.LabReleaseEvaluationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs a scenario set against one exact candidate release and records only the verdict.
 *
 * <p><b>What is stored is a score, a pass flag, and a digest.</b> A scenario report contains model
 * output about the fixture documents, so the report itself never reaches the database. The digest
 * is what makes the stored verdict checkable: a result and a report that no longer hash together
 * are visibly not about each other.
 *
 * <p><b>Exact on every axis.</b> An evaluation names the release, the scenario set, and the set's
 * version, and the promotion gate looks it up by all three. A pass recorded for a different release
 * says nothing about this one; a pass against version 1 of a set says nothing about version 2. That
 * is what stops a stale result from vouching for a release nobody re-tested.
 *
 * <p><b>A runner is a registered bean, and a set whose fixtures nothing serves refuses.</b>
 * {@link PackageFixtureScenarioRunner} executes cases against committed parses; a scenario naming
 * a fixture no runner supports fails the evaluation with {@code EVALUATION_RUNNER_UNAVAILABLE}
 * rather than skipping the case, so nothing can be promoted on a set that was only partly run.
 * That is the correct direction to fail: stated, rather than simulated with a result nobody
 * measured.
 */
public interface InstanceEvaluationService {

    /**
     * Executes one scenario against one release and says whether it held.
     *
     * <p>Its outcome carries no model output: a scenario either satisfied every required pointer
     * and no forbidden one, or it did not, and the reason is a code.
     */
    interface ScenarioRunner {

        /**
         * The outcome of one case. {@code failureCode} is null exactly when {@code passed}.
         *
         * <p>The code may name the JSON pointer that decided it. A pointer comes from a
         * source-controlled, reviewed scenario file rather than from a model or a package, so it
         * carries nothing this taxonomy exists to keep out — and without it an operator reading a
         * failed verdict cannot tell which assertion broke, because the report is never stored.
         */
        record ScenarioOutcome(String scenarioName, boolean passed, String failureCode) {}

        /** True when this runner can serve the named fixture. */
        boolean supports(String packageFixture);

        ScenarioOutcome run(LabInstanceRelease release, InstanceReleaseManifest manifest,
                            Scenario scenario);
    }

    /** The recorded verdict. The report itself is not returned and not stored. */
    record EvaluationResult(
            UUID evaluationId,
            UUID releaseId,
            String scenarioSetId,
            int scenarioSetVersion,
            BigDecimal score,
            boolean passed,
            String reportSha256,
            int scenariosRun,
            int scenariosPassed) {}

    /** Why an evaluation cannot be produced. Stable, value-free codes. */
    final class EvaluationException extends RuntimeException {
        public enum Code {
            EVALUATION_REQUEST_INVALID,
            EVALUATION_RELEASE_NOT_FOUND,
            /** The release predates v2 and declares no scenario contract to run. */
            EVALUATION_RELEASE_NOT_PINNABLE,
            EVALUATION_SCENARIO_SET_UNKNOWN,
            /** No runner in this build can serve the set's fixtures. */
            EVALUATION_RUNNER_UNAVAILABLE,
            /**
             * The brain is over its daily budget, so the scenarios cannot be executed.
             *
             * <p>Not a verdict about the release. Recording the cases that did run and scoring
             * them would be a promotion-authorizing result that measured less than it claims.
             */
            EVALUATION_BUDGET_EXHAUSTED,
            /**
             * The release pins collections this brain does not have, has disabled, or has moved
             * past — so there is no snapshot to evaluate it against.
             *
             * <p>A refusal rather than an empty-retrieval pass: every grounded case would fail for
             * a reason that has nothing to do with the release, and every ungrounded one would
             * pass while proving less than it appears to.
             */
            EVALUATION_CORPUS_UNAVAILABLE
        }

        private final Code code;

        public EvaluationException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /** Evaluates a candidate release against the scenario set its manifest names. */
    EvaluationResult evaluate(UUID brainId, String instanceSlug, UUID releaseId);
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultInstanceEvaluationService implements InstanceEvaluationService {

    /** Scores are stored as NUMERIC(6,4), so the ratio is rounded to match before it leaves. */
    private static final int SCORE_SCALE = 4;

    private final InstanceScenarioSetRegistry scenarioSets;
    private final LabInstanceReleaseRepository releases;
    private final LabReleaseEvaluationRepository evaluations;
    /**
     * The exact stored JSON is the read boundary, not the entity's mapped {@code Map}.
     *
     * <p>Hibernate deserializes a JSONB attribute through its format mapper, which returns a
     * {@code BigDecimal} as a {@code Double} and a {@code Long} as an {@code Integer} — and a
     * {@code Double} cannot carry {@code 0.250} at all. Decoding that map therefore either fails
     * outright or silently produces a manifest whose digest is no longer the stored one, so every
     * read of a release goes through the verified reader, which reads the JSONB text itself and
     * checks it against {@code manifest_sha256} before decoding.
     */
    private final VerifiedInstanceReleaseReader manifests;
    private final ObjectProvider<ScenarioRunner> runners;

    DefaultInstanceEvaluationService(InstanceScenarioSetRegistry scenarioSets,
                                     LabInstanceReleaseRepository releases,
                                     LabReleaseEvaluationRepository evaluations,
                                     VerifiedInstanceReleaseReader manifests,
                                     ObjectProvider<ScenarioRunner> runners) {
        this.scenarioSets = Objects.requireNonNull(scenarioSets, "scenarioSets");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.evaluations = Objects.requireNonNull(evaluations, "evaluations");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.runners = Objects.requireNonNull(runners, "runners");
    }

    @Override
    public EvaluationResult evaluate(UUID brainId, String instanceSlug, UUID releaseId) {
        if (brainId == null || instanceSlug == null || instanceSlug.isBlank() || releaseId == null) {
            throw new EvaluationException(EvaluationException.Code.EVALUATION_REQUEST_INVALID);
        }
        LabInstanceRelease release = releases
                .findByIdAndBrainIdAndInstanceSlug(releaseId, brainId, instanceSlug)
                .orElseThrow(() -> new EvaluationException(
                        EvaluationException.Code.EVALUATION_RELEASE_NOT_FOUND));

        if (!(manifests.read(release) instanceof DecodedInstanceManifest.V2 v2)) {
            throw new EvaluationException(
                    EvaluationException.Code.EVALUATION_RELEASE_NOT_PINNABLE);
        }
        InstanceReleaseManifest manifest = v2.manifest();
        InstanceReleaseManifest.EvaluationContract contract = manifest.evaluations();
        if (contract == null) {
            throw new EvaluationException(EvaluationException.Code.EVALUATION_REQUEST_INVALID);
        }
        ScenarioSet set = scenarioSets
                .find(contract.scenarioSetId(), contract.scenarioSetVersion())
                .orElseThrow(() -> new EvaluationException(
                        EvaluationException.Code.EVALUATION_SCENARIO_SET_UNKNOWN));

        List<ScenarioRunner.ScenarioOutcome> outcomes = run(release, manifest, set);
        long passedCount = outcomes.stream()
                .filter(ScenarioRunner.ScenarioOutcome::passed).count();
        BigDecimal score = BigDecimal.valueOf(passedCount)
                .divide(BigDecimal.valueOf(outcomes.size()), SCORE_SCALE, RoundingMode.HALF_UP);
        // Both conditions, not either: a set whose average clears the bar while one case failed
        // outright has still demonstrated a way for this release to be wrong.
        boolean passed = passedCount == outcomes.size()
                && (contract.minimumScore() == null
                    || score.compareTo(contract.minimumScore()) >= 0);

        LabReleaseEvaluation stored = evaluations.saveAndFlush(new LabReleaseEvaluation(
                brainId, releaseId, set.id(), set.version(), score, passed,
                reportDigest(set, outcomes)));

        return new EvaluationResult(stored.getId(), releaseId, set.id(), set.version(),
                score, passed, stored.getReportSha256(), outcomes.size(), (int) passedCount);
    }

    private List<ScenarioRunner.ScenarioOutcome> run(
            LabInstanceRelease release, InstanceReleaseManifest manifest, ScenarioSet set) {
        List<ScenarioRunner> available = runners.orderedStream().toList();
        List<ScenarioRunner.ScenarioOutcome> outcomes = new ArrayList<>(set.scenarios().size());
        for (Scenario scenario : set.scenarios()) {
            ScenarioRunner runner = available.stream()
                    .filter(candidate -> candidate.supports(scenario.packageFixture()))
                    .findFirst()
                    .orElseThrow(() -> new EvaluationException(
                            EvaluationException.Code.EVALUATION_RUNNER_UNAVAILABLE));
            outcomes.add(runner.run(release, manifest, scenario));
        }
        return outcomes;
    }

    /**
     * A digest over the set's own digest and each case's verdict, in scenario order.
     *
     * <p>Length-prefixed, so no combination of scenario names and codes can be rearranged into a
     * different run that hashes the same. It deliberately covers the verdicts rather than the
     * model's answers: the point is to bind a stored result to the exact set and outcomes it came
     * from, not to fingerprint content that is never stored anyway.
     */
    private static String reportDigest(ScenarioSet set,
                                       List<ScenarioRunner.ScenarioOutcome> outcomes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, set.id());
            update(digest, Integer.toString(set.version()));
            update(digest, set.sha256());
            for (ScenarioRunner.ScenarioOutcome outcome : outcomes) {
                update(digest, outcome.scenarioName());
                update(digest, Boolean.toString(outcome.passed()));
                update(digest, outcome.failureCode() == null ? "" : outcome.failureCode());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static void update(MessageDigest digest, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }
}
