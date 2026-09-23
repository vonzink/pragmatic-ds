package com.pragmaticds.docengine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.config.AiExtractionConfig;
import com.pragmaticds.docengine.platform.ai.AiExtractionPort;
import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiExtractionStatus;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.StubAiExtractionAdapter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Manual-only provider evaluation. The committed inputs are synthetic and normal CI excludes it. */
@Tag("live-eval")
class BankStatementLiveEvalTest {

    @Test
    void live_provider_is_scored_only_against_the_committed_synthetic_corpus() {
        assertThat(System.getProperty("docengine.live-eval.enabled"))
                .as("the Gradle liveEval gate must explicitly enable this test")
                .isEqualTo("true");
        BankStatementEvalCorpus corpus = BankStatementEvalCorpus.load();
        assertThat(corpus.cases()).allMatch(BankStatementEvalCorpus.EvalCase::synthetic);

        try (AnnotationConfigApplicationContext context = providerContext()) {
            AiExtractionPort provider = context.getBean(AiExtractionPort.class);
            assertThat(provider)
                    .as("provider configuration or credentials are missing; refusing a stub live run")
                    .isNotInstanceOf(StubAiExtractionAdapter.class);

            BankStatementEvalReport report =
                    new BankStatementEvalRunner()
                            .evaluate(
                                    corpus,
                                    testCase -> extract(provider, testCase));
            report.writeJson(Path.of("build/reports/ai-eval/live-summary.json"));
            System.out.print(report.toSummaryTable());

            assertThat(report.autoAcceptedMoneyDatePrecision())
                    .as(report.toJson())
                    .isGreaterThanOrEqualTo(new BigDecimal("0.995"));
            assertThat(report.cases())
                    .noneMatch(BankStatementEvalReport.CaseResult::forbiddenValueAutoAccepted);
        }
    }

    private static BankStatementExtraction extract(
            AiExtractionPort provider, BankStatementEvalCorpus.EvalCase testCase) {
        AiExtractionResult result = provider.extract(testCase.request());
        if (result == null
                || result.status() != AiExtractionStatus.OK
                || !(result.extraction() instanceof BankStatementExtraction extraction)) {
            String reason = result == null ? "null_result" : result.reason();
            throw new AssertionError(
                    "live provider failed synthetic case " + testCase.id() + " reason=" + reason);
        }
        return extraction;
    }

    private static AnnotationConfigApplicationContext providerContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("docengine.ai.enabled", "true");
        copyRequiredEnvironment(properties, "docengine.ai.provider", "DOCENGINE_AI_PROVIDER");
        copyRequiredEnvironment(properties, "docengine.ai.model", "DOCENGINE_AI_MODEL");
        copyOptionalEnvironment(properties, "docengine.ai.api-key", "DOCENGINE_AI_API_KEY");
        copyOptionalEnvironment(properties, "docengine.ai.base-url", "DOCENGINE_AI_BASE_URL");
        copyOptionalEnvironment(
                properties, "docengine.ai.vertex.project", "DOCENGINE_AI_VERTEX_PROJECT");
        copyOptionalEnvironment(
                properties, "docengine.ai.vertex.region", "DOCENGINE_AI_VERTEX_REGION");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("liveEval", properties));
        context.register(AiExtractionConfig.class);
        context.refresh();
        return context;
    }

    private static void copyRequiredEnvironment(
            Map<String, Object> properties, String property, String environmentVariable) {
        String value = System.getenv(environmentVariable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    environmentVariable + " is required for the explicit synthetic live evaluation");
        }
        properties.put(property, value);
    }

    private static void copyOptionalEnvironment(
            Map<String, Object> properties, String property, String environmentVariable) {
        String value = System.getenv(environmentVariable);
        if (value != null && !value.isBlank()) {
            properties.put(property, value);
        }
    }
}
