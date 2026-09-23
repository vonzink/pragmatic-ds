package com.pragmaticds.rag.lab.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A strictly validated, deeply immutable view of one Document Engine canonical result
 * envelope (envelope {@code 1.0.0}, canonicalization {@code DOCENGINE-C14N-1}).
 *
 * <p>Identity is {@link #artifact()} — the exact received bytes and their SHA-256 — never a
 * reserialization of this parsed view. Every list is defensively copied and unmodifiable;
 * free-form JSON trees ({@code normalized.json}, {@code parserVersions}) are deep-copied into
 * unmodifiable maps/lists of immutable scalars at construction.
 */
public record EngineResultEnvelope(
        EngineArtifactDescriptor artifact,
        String envelopeVersion,
        String canonicalizationVersion,
        UUID packageId,
        Generation generation,
        List<SourceFile> sources,
        List<EnginePage> pages,
        List<LogicalDocument> documents,
        List<UUID> unassignedPageIds,
        Provenance provenance) {

    public EngineResultEnvelope {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(envelopeVersion, "envelopeVersion");
        Objects.requireNonNull(canonicalizationVersion, "canonicalizationVersion");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(provenance, "provenance");
        sources = List.copyOf(sources);
        pages = List.copyOf(pages);
        documents = List.copyOf(documents);
        unassignedPageIds = List.copyOf(unassignedPageIds);
    }

    /** Exact parse-generation identity of this immutable revision. */
    public record Generation(
            UUID processingJobId,
            int parseGeneration,
            int packageRevision,
            String sourceSetSha256,
            String reuseEligibility) {

        public Generation {
            Objects.requireNonNull(processingJobId, "processingJobId");
            Objects.requireNonNull(sourceSetSha256, "sourceSetSha256");
            Objects.requireNonNull(reuseEligibility, "reuseEligibility");
        }
    }

    /** One uploaded original, identified by content digest — never by filename. */
    public record SourceFile(
            UUID id, int ordinal, String contentSha256, long sizeBytes, String contentType) {

        public SourceFile {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contentSha256, "contentSha256");
            Objects.requireNonNull(contentType, "contentType");
        }
    }

    /** One package page; {@code classification} is explicit-null when the page has none. */
    public record EnginePage(
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
            boolean duplicate,
            PageClassification classification) {

        public EnginePage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sourceFileId, "sourceFileId");
            Objects.requireNonNull(widthPt, "widthPt");
            Objects.requireNonNull(heightPt, "heightPt");
            Objects.requireNonNull(textLayer, "textLayer");
        }
    }

    public record PageClassification(
            String documentTypeCode,
            BigDecimal confidence,
            String method,
            String rulePackVersion,
            ClassificationEvidence evidence) {

        public PageClassification {
            Objects.requireNonNull(documentTypeCode, "documentTypeCode");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /**
     * The closed union of classification-evidence contracts, one per engine classification
     * {@code method}: {@code RULE_ANCHOR} pages carry {@link RuleAnchorEvidence}, {@code LLM} pages
     * carry {@link LlmEvidence}. The engine allows other method values in its column but declares
     * no evidence shape for them, so the parser refuses them rather than guessing.
     */
    public sealed interface ClassificationEvidence permits RuleAnchorEvidence, LlmEvidence {}

    /**
     * Evidence for a page a rule pack typed. {@code coQualifyingTypes} lists the other document
     * types the same page also qualified for (engine Phase B4, e.g. a 1040 page that also matches a
     * schedule); the engine omits the member entirely when the list is empty, so an empty list here
     * means "absent".
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
    }

    /**
     * Evidence for a page the engine's model fallback typed (engine #64): the model's provenance
     * plus the span ids and character range its quote was verified against. Weaker provenance than
     * an anchor match. {@code deterministicRunnerUp} is the rule packs' best losing candidate and
     * is the one optional member.
     */
    public record LlmEvidence(
            DeterministicRunnerUp deterministicRunnerUp,
            List<Long> matchedSpanIds,
            String model,
            CharacterOffsets offsets,
            String promptVersion,
            String source)
            implements ClassificationEvidence {

        public LlmEvidence {
            matchedSpanIds = List.copyOf(matchedSpanIds);
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(offsets, "offsets");
            Objects.requireNonNull(promptVersion, "promptVersion");
            Objects.requireNonNull(source, "source");
        }
    }

    public record DeterministicRunnerUp(String type, BigDecimal score) {

        public DeterministicRunnerUp {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(score, "score");
        }
    }

    /** Character range in the page's text, {@code 0 <= start <= end}. */
    public record CharacterOffsets(int start, int end) {}

    public record ClassificationAnchor(
            String packType,
            String packVersion,
            String anchorId,
            BigDecimal weight,
            List<Long> spanIds,
            List<Box> boxes,
            ClassificationRange range) {

        public ClassificationAnchor {
            Objects.requireNonNull(packType, "packType");
            Objects.requireNonNull(packVersion, "packVersion");
            Objects.requireNonNull(anchorId, "anchorId");
            Objects.requireNonNull(weight, "weight");
            Objects.requireNonNull(range, "range");
            spanIds = List.copyOf(spanIds);
            boxes = List.copyOf(boxes);
        }
    }

    public record ClassificationRange(int start, int end) {}

    public record ClassificationScore(
            String packType,
            String packVersion,
            BigDecimal score,
            BigDecimal minConfidence,
            BigDecimal targetScore) {

        public ClassificationScore {
            Objects.requireNonNull(packType, "packType");
            Objects.requireNonNull(packVersion, "packVersion");
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(minConfidence, "minConfidence");
            Objects.requireNonNull(targetScore, "targetScore");
        }
    }

    /** Page-space rectangle in PDF points. */
    public record Box(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {

        public Box {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
            Objects.requireNonNull(width, "width");
            Objects.requireNonNull(height, "height");
        }
    }

    /** One logical document with its member pages and occurrence-keyed fields. */
    public record LogicalDocument(
            UUID id,
            String documentTypeCode,
            int ordinal,
            List<UUID> pageIds,
            List<FieldOccurrence> fields) {

        public LogicalDocument {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(documentTypeCode, "documentTypeCode");
            pageIds = List.copyOf(pageIds);
            fields = List.copyOf(fields);
        }
    }

    /** Explicit FOUND/MISSING; a field row is written even when its value is absent. */
    public enum FieldStatus {
        FOUND,
        MISSING
    }

    /**
     * Where an occurrence's value channel came from. {@code UNREVIEWED_SOURCE} is what the
     * envelope parser sets: the machine envelope carries no review state at all. The other three
     * are the engine read model's {@code effectiveStatus}, written by {@code ReviewedValueOverlay}.
     */
    public enum ReviewState {
        UNREVIEWED_SOURCE,
        MACHINE,
        CORRECTED,
        REJECTED
    }

    /**
     * One extracted field occurrence. {@code groupKey} is present-and-null for a field that
     * does not repeat; {@code normalized} is null exactly when the occurrence is MISSING.
     */
    public record FieldOccurrence(
            String name,
            String groupKey,
            FieldStatus status,
            String dataType,
            String displayedText,
            String rawValue,
            NormalizedValue normalized,
            SchemaRef schema,
            String method,
            String extractorVersion,
            BigDecimal confidence,
            ConfidenceComponents confidenceComponents,
            String validationStatus,
            boolean sensitive,
            List<EvidenceSpan> evidence,
            ReviewState reviewState) {

        public FieldOccurrence {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(dataType, "dataType");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(extractorVersion, "extractorVersion");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(validationStatus, "validationStatus");
            Objects.requireNonNull(reviewState, "reviewState");
            evidence = List.copyOf(evidence);
        }

        /** The envelope's own shape: fifteen members, review state implied. */
        public FieldOccurrence(
                String name,
                String groupKey,
                FieldStatus status,
                String dataType,
                String displayedText,
                String rawValue,
                NormalizedValue normalized,
                SchemaRef schema,
                String method,
                String extractorVersion,
                BigDecimal confidence,
                ConfidenceComponents confidenceComponents,
                String validationStatus,
                boolean sensitive,
                List<EvidenceSpan> evidence) {
            this(name, groupKey, status, dataType, displayedText, rawValue, normalized, schema,
                    method, extractorVersion, confidence, confidenceComponents, validationStatus,
                    sensitive, evidence, ReviewState.UNREVIEWED_SOURCE);
        }
    }

    /** Typed normalized arms; exactly one arm is non-null for a FOUND occurrence. */
    public record NormalizedValue(String text, BigDecimal number, LocalDate date, Object json) {

        public NormalizedValue {
            json = deepImmutable(json);
        }
    }

    public record SchemaRef(UUID id, String version) {

        public SchemaRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
        }
    }

    /** The confidence formula's exactly-three inputs. */
    public record ConfidenceComponents(
            BigDecimal spanConfidence, BigDecimal anchorStrength, BigDecimal normalizerCertainty) {

        public ConfidenceComponents {
            Objects.requireNonNull(spanConfidence, "spanConfidence");
            Objects.requireNonNull(anchorStrength, "anchorStrength");
            Objects.requireNonNull(normalizerCertainty, "normalizerCertainty");
        }
    }

    /** One evidence span with complete page-space coordinates. */
    public record EvidenceSpan(
            UUID pageId,
            UUID layoutElementId,
            Long textSpanId,
            String role,
            int ordinal,
            Box box) {

        public EvidenceSpan {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(box, "box");
        }
    }

    public record Provenance(
            ReleaseAvailability applicationRelease,
            ReleaseAvailability extractionEngineRelease,
            ReleaseAvailability workerContractRelease,
            List<StageAttempt> stages) {

        public Provenance {
            Objects.requireNonNull(applicationRelease, "applicationRelease");
            Objects.requireNonNull(extractionEngineRelease, "extractionEngineRelease");
            Objects.requireNonNull(workerContractRelease, "workerContractRelease");
            stages = List.copyOf(stages);
        }
    }

    public record ReleaseAvailability(String availability) {

        public ReleaseAvailability {
            Objects.requireNonNull(availability, "availability");
        }
    }

    public record StageAttempt(
            String stage,
            int attempt,
            String outputDigest,
            String workerVersion,
            Object parserVersions) {

        public StageAttempt {
            Objects.requireNonNull(stage, "stage");
            parserVersions = deepImmutable(parserVersions);
        }
    }

    /**
     * Deep-copies a JSON-shaped tree into unmodifiable maps/lists of immutable scalars.
     * Integral numbers normalize to {@link BigDecimal} so every numeric leaf is one type.
     */
    private static Object deepImmutable(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Integer number) {
            return BigDecimal.valueOf(number);
        }
        if (value instanceof Long number) {
            return BigDecimal.valueOf(number);
        }
        if (value instanceof BigInteger number) {
            return new BigDecimal(number);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON tree keys must be strings");
                }
                copy.put(key, deepImmutable(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(deepImmutable(element));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Unsupported JSON tree value type");
    }
}
