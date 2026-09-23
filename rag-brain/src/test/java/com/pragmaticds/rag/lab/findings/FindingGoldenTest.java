package com.pragmaticds.rag.lab.findings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Findings over the five shipped fixtures, compared byte for byte.
 *
 * <p>Goldens are RECORDED, never typed: every run writes what it saw to {@code build/findings/},
 * and the recording step copies those files into {@code src/test/resources/findings/golden/}. A
 * golden someone typed proves that they typed what the code does.
 *
 * <p>Five fixtures, not four: none of the four originally shipped fixtures carries a field
 * occurrence with {@code validationStatus} of {@code MANUAL_REVIEW_REQUIRED}, so all four alone
 * would only exercise the empty-array path of {@link ManualReviewRequiredFindingTool}.
 * {@code fixture-paystub-review-required} is the fifth fixture, added specifically to give the
 * reference rule something to fire on.
 */
class FindingGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path RECORDED = Path.of("build", "findings");
    private static final String SCOPE = "golden-scope";

    private final ManualReviewRequiredFindingTool tool = new ManualReviewRequiredFindingTool();

    private static List<String> fixtures() {
        return List.of(
                "fixture-paystub-single",
                "fixture-paystub-review-required",
                "fixture-paystub-w2",
                "fixture-paystub-unreadable",
                "fixture-unsupported-only",
                "fixture-bank-statement-single");
    }

    @Test
    void findingsMatchTheirGoldens() throws Exception {
        Files.createDirectories(RECORDED);
        for (String fixture : fixtures()) {
            EngineResultEnvelope envelope = Fixtures.load(fixture);

            List<Finding> findings = FindingAssembler.assemble(
                    FindingJson.fromOutput(tool.execute(envelope)), SCOPE);
            String actual = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(FindingJson.toArray(findings));

            Files.writeString(RECORDED.resolve(fixture + ".json"), actual,
                    StandardCharsets.UTF_8);

            Path golden = Path.of("src", "test", "resources", "findings", "golden",
                    fixture + ".json");
            assertTrue(Files.exists(golden),
                    "Golden not recorded yet: copy build/findings/" + fixture
                            + ".json to " + golden);
            assertEquals(Files.readString(golden, StandardCharsets.UTF_8).trim(), actual.trim(),
                    fixture);
        }
    }

    @Test
    void findingsAreStableAcrossTwoRuns() throws Exception {
        for (String fixture : fixtures()) {
            EngineResultEnvelope envelope = Fixtures.load(fixture);
            String first = FindingJson.toArray(FindingAssembler.assemble(
                    FindingJson.fromOutput(tool.execute(envelope)), SCOPE)).toString();
            String second = FindingJson.toArray(FindingAssembler.assemble(
                    FindingJson.fromOutput(tool.execute(envelope)), SCOPE)).toString();
            assertEquals(first, second, fixture);
        }
    }

    @Test
    void everyAnchorNamesAPageInItsEnvelope() throws Exception {
        for (String fixture : fixtures()) {
            EngineResultEnvelope envelope = Fixtures.load(fixture);
            List<Finding> findings =
                    FindingJson.fromOutput(tool.execute(envelope));
            for (Finding finding : findings) {
                for (FindingAnchor anchor : finding.anchors()) {
                    if (!anchor.anchored()) {
                        continue;
                    }
                    boolean known = envelope.pages().stream()
                            .anyMatch(p -> p.id().equals(anchor.pageId()));
                    assertTrue(known, fixture + ": anchor names an unknown page");
                }
            }
        }
    }
}
