package com.pragmaticds.docengine.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.platform.ai.AiDocumentType;
import com.pragmaticds.docengine.platform.ai.AiExtractionRequest;
import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiExtractionStatus;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtractionParser;
import com.pragmaticds.docengine.platform.ai.BankStatementExtractionSchema;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads the committed, synthetic-only bank-statement evaluation corpus. */
final class BankStatementEvalCorpus {

    private static final String ROOT = "/ai/eval/";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private final List<EvalCase> cases;

    private BankStatementEvalCorpus(List<EvalCase> cases) {
        this.cases = List.copyOf(cases);
    }

    static BankStatementEvalCorpus load() {
        Index index = readJson(ROOT + "index.json", Index.class);
        return new BankStatementEvalCorpus(index.cases().stream().map(EvalCase::load).toList());
    }

    List<EvalCase> cases() {
        return cases;
    }

    EvalCase caseById(String id) {
        return cases.stream()
                .filter(testCase -> testCase.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown eval case: " + id));
    }

    private static <T> T readJson(String resource, Class<T> type) {
        try (InputStream stream = BankStatementEvalCorpus.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing eval resource: " + resource);
            }
            return MAPPER.readValue(stream, type);
        } catch (IOException failure) {
            throw new IllegalStateException("invalid eval resource: " + resource, failure);
        }
    }

    private static String readText(String resource) {
        try (InputStream stream = BankStatementEvalCorpus.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing eval resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("unreadable eval resource: " + resource, failure);
        }
    }

    record EvalCase(
            Input input,
            Outcomes outcomes,
            String expectedJson,
            String goldenResponseJson,
            BankStatementExtraction expected,
            BankStatementExtraction golden) {

        private static EvalCase load(String id) {
            String directory = ROOT + id + "/";
            Input input = readJson(directory + "input.json", Input.class);
            if (!id.equals(input.id())) {
                throw new IllegalStateException("eval case id does not match directory: " + id);
            }
            Outcomes outcomes = readJson(directory + "outcomes.json", Outcomes.class);
            String expectedJson = readText(directory + "expected.json");
            String goldenJson = readText(directory + outcomes.goldenResponseFile());
            return new EvalCase(
                    input,
                    outcomes,
                    expectedJson,
                    goldenJson,
                    parse(id, "expected", expectedJson),
                    parse(id, "golden", goldenJson));
        }

        private static BankStatementExtraction parse(String id, String kind, String json) {
            AiExtractionResult parsed =
                    new BankStatementExtractionParser()
                            .parse(
                                    new AiExtractionResult(
                                            json,
                                            "golden",
                                            "synthetic",
                                            AiExtractionStatus.OK,
                                            AiTokenCounts.ZERO,
                                            null));
            if (parsed.status() != AiExtractionStatus.OK
                    || !(parsed.extraction() instanceof BankStatementExtraction extraction)) {
                throw new IllegalStateException(
                        "invalid " + kind + " extraction for eval case " + id);
            }
            return extraction;
        }

        String id() {
            return input.id();
        }

        boolean synthetic() {
            return input.synthetic();
        }

        List<String> coverage() {
            return input.coverage();
        }

        String assembledDocumentText() {
            StringBuilder assembled = new StringBuilder();
            input.pages().forEach(
                    page -> {
                        assembled.append("PAGE ").append(page.number()).append('\n');
                        page.spans().forEach(span -> assembled.append(span).append('\n'));
                    });
            return assembled.toString();
        }

        String assembledTableStructureText() {
            StringBuilder assembled = new StringBuilder();
            input.pages().forEach(
                    page ->
                            assembled.append("PAGE ")
                                    .append(page.number())
                                    .append('\n')
                                    .append(page.tableText())
                                    .append('\n'));
            return assembled.toString();
        }

        AiExtractionRequest request() {
            return new AiExtractionRequest(
                    AiDocumentType.BANK_STATEMENT,
                    assembledDocumentText(),
                    assembledTableStructureText(),
                    new BankStatementExtractionSchema().schemaJson());
        }

        Map<Integer, List<TextSpan>> spansByPage() {
            Map<Integer, List<TextSpan>> spans = new LinkedHashMap<>();
            for (Page page : input.pages()) {
                UUID pageId =
                        UUID.nameUUIDFromBytes(
                                (input.id() + ":" + page.number())
                                        .getBytes(StandardCharsets.UTF_8));
                List<TextSpan> pageSpans =
                        java.util.stream.IntStream.range(0, page.spans().size())
                                .mapToObj(
                                        ordinal ->
                                                new TextSpan(
                                                        pageId,
                                                        ordinal,
                                                        page.spans().get(ordinal),
                                                        BigDecimal.valueOf(10L),
                                                        BigDecimal.valueOf(10L + ordinal * 12L),
                                                        BigDecimal.valueOf(500L),
                                                        BigDecimal.TEN,
                                                        SpanSource.NATIVE,
                                                        null,
                                                        BigDecimal.ONE,
                                                        BigDecimal.TEN,
                                                        "SyntheticEval"))
                                .toList();
                spans.put(page.number(), pageSpans);
            }
            return Map.copyOf(spans);
        }

        AiEvidenceAnchor.Status expectedAnchor(String path) {
            String configured =
                    outcomes.anchorOverrides().getOrDefault(path, outcomes.defaultAnchorStatus());
            return AiEvidenceAnchor.Status.valueOf(configured);
        }

        BankStatementReconciler.Status expectedReconciliation() {
            return BankStatementReconciler.Status.valueOf(outcomes.reconciliationStatus());
        }
    }

    private record Index(List<String> cases) {
        private Index {
            cases = List.copyOf(cases);
        }
    }

    record Input(String id, boolean synthetic, List<String> coverage, List<Page> pages) {
        Input {
            coverage = List.copyOf(coverage);
            pages = List.copyOf(pages);
        }
    }

    record Page(int number, String classification, List<String> spans, String tableText) {
        Page {
            spans = List.copyOf(spans);
            tableText = tableText == null ? "" : tableText;
        }
    }

    record Outcomes(
            String defaultAnchorStatus,
            Map<String, String> anchorOverrides,
            String reconciliationStatus,
            boolean straightThrough,
            String goldenResponseFile,
            List<String> forbiddenAutoAcceptedValues) {
        Outcomes {
            anchorOverrides =
                    anchorOverrides == null ? Map.of() : Map.copyOf(anchorOverrides);
            forbiddenAutoAcceptedValues =
                    forbiddenAutoAcceptedValues == null
                            ? List.of()
                            : List.copyOf(forbiddenAutoAcceptedValues);
            goldenResponseFile =
                    goldenResponseFile == null || goldenResponseFile.isBlank()
                            ? "expected.json"
                            : goldenResponseFile;
        }
    }
}
