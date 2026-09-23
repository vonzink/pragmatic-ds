package com.pragmaticds.docengine.results.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Machine-only rows captured inside finalization's transaction.
 *
 * <p>Mutable review columns, corrections, names, storage keys, raw page text, actors, and
 * timestamps are deliberately not representable here.
 */
public record MachineResultSnapshot(
        UUID packageId,
        UUID processingJobId,
        int parseGeneration,
        List<Source> sources,
        List<Page> pages,
        List<Classification> classifications,
        List<Document> documents,
        List<Membership> memberships,
        List<Field> fields,
        List<Evidence> evidence,
        List<StageProvenance> successfulStages) {

    public MachineResultSnapshot {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(processingJobId, "processingJobId");
        if (parseGeneration <= 0) {
            throw new IllegalArgumentException("parseGeneration must be positive");
        }
        sources = List.copyOf(sources);
        pages = List.copyOf(pages);
        classifications = List.copyOf(classifications);
        documents = List.copyOf(documents);
        memberships = List.copyOf(memberships);
        fields = List.copyOf(fields);
        evidence = List.copyOf(evidence);
        successfulStages = List.copyOf(successfulStages);
    }

    public record Source(
            UUID id, int ordinal, String contentSha256, long sizeBytes, String contentType) {}

    public record Page(
            UUID id,
            UUID sourceFileId,
            int sourcePageIndex,
            int packagePageIndex,
            BigDecimal widthPt,
            BigDecimal heightPt,
            int rotation,
            Integer renderDpi,
            String textLayer,
            boolean blank,
            boolean duplicate) {}

    public record Classification(
            UUID pageId,
            String documentTypeCode,
            BigDecimal confidence,
            String method,
            String rulePackVersion,
            ClassificationEvidence evidence) {}

    /**
     * Text-free projection of a classification's stored evidence.
     *
     * <p>Evidence is shaped by the method that produced it, and the two shapes share no members: a
     * rule pack proves its answer with anchors and scores, a model proves its answer with the spans
     * it quoted and the prompt it was asked under. Each alternative stays an EXACT member set —
     * {@link MachineResultSnapshotLoader} dispatches on {@link Classification#method()} and then
     * pins that method's shape, so neither shape is loosened to admit the other and an evidence
     * document written under the wrong method is still refused.
     */
    public sealed interface ClassificationEvidence permits RuleAnchorEvidence, LlmEvidence {}

    /**
     * The exact JSON shape emitted by {@code PageClassifier} for {@code method = RULE_ANCHOR}.
     *
     * @param coQualifyingTypes every type whose pack cleared its OWN threshold on the page (Phase
     *     B4's suspected multi-document sheet); empty when the classifier wrote no such member,
     *     which is every page of a clean single-document package
     */
    public record RuleAnchorEvidence(
            List<ClassificationAnchor> anchors,
            List<ClassificationScore> scores,
            List<String> coQualifyingTypes)
            implements ClassificationEvidence {
        public RuleAnchorEvidence {
            anchors = List.copyOf(anchors);
            scores = List.copyOf(scores);
            coQualifyingTypes = List.copyOf(coQualifyingTypes);
        }

        public RuleAnchorEvidence(
                List<ClassificationAnchor> anchors, List<ClassificationScore> scores) {
            this(anchors, scores, List.of());
        }
    }

    /**
     * The exact JSON shape emitted by {@code AiPageClassificationService} for {@code method = LLM}:
     * where the answer came from and where the engine verified it against the page, never what the
     * page says.
     *
     * @param matchedSpanIds the spans the model's quote was proven against — ids only, no text
     * @param offsets the character range on the page that quote occupied
     * @param deterministicRunnerUp the best-scoring pack that did not itself type the page; null
     *     when the superseded row recorded no score at all
     */
    public record LlmEvidence(
            String source,
            String model,
            String promptVersion,
            List<Long> matchedSpanIds,
            ClassificationRange offsets,
            DeterministicRunnerUp deterministicRunnerUp)
            implements ClassificationEvidence {
        public LlmEvidence {
            matchedSpanIds = List.copyOf(matchedSpanIds);
        }
    }

    /** A pack type and the score it reached — the tuning loop's record of the loser. */
    public record DeterministicRunnerUp(String type, BigDecimal score) {}

    public record ClassificationAnchor(
            String packType,
            String packVersion,
            String anchorId,
            BigDecimal weight,
            List<Long> spanIds,
            List<ClassificationBox> boxes,
            ClassificationRange range) {
        public ClassificationAnchor {
            spanIds = List.copyOf(spanIds);
            boxes = List.copyOf(boxes);
        }
    }

    public record ClassificationBox(
            BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}

    public record ClassificationRange(int start, int end) {}

    public record ClassificationScore(
            String packType,
            String packVersion,
            BigDecimal score,
            BigDecimal minConfidence,
            BigDecimal targetScore) {}

    public record Document(UUID id, int ordinal, String documentTypeCode) {}

    public record Membership(UUID logicalDocumentId, UUID pageId, int ordinal) {}

    public record Field(
            UUID id,
            UUID logicalDocumentId,
            UUID schemaId,
            String schemaVersion,
            String fieldName,
            String groupKey,
            String dataType,
            String displayedText,
            String rawValue,
            String normalizedText,
            BigDecimal normalizedNumber,
            LocalDate normalizedDate,
            JsonNode normalizedJson,
            String extractionMethod,
            String extractorVersion,
            BigDecimal confidence,
            JsonNode confidenceComponents,
            String validationStatus,
            boolean sensitive) {}

    public record Evidence(
            UUID id,
            UUID extractedFieldId,
            UUID pageId,
            UUID layoutElementId,
            Long textSpanId,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            String role,
            int ordinal) {}

    public record StageProvenance(
            String stage,
            int attempt,
            String outputDigest,
            String workerVersion,
            JsonNode parserVersions) {}
}
