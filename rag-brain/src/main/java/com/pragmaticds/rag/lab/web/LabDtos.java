package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.domain.LabRunReviewSnapshot;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every shape the Income Lab prototype puts on the wire.
 *
 * <p>Three rules hold across all of them, and each is enforced here rather than trusted to the
 * caller:
 *
 * <ol>
 *   <li><b>The prototype boundary travels with the payload.</b> Every instance, run, and discussion
 *       response carries {@link Prototype}, built from
 *       {@link IncomeLabReleaseService#prototypeBoundary()} — a constant, so no response pays a
 *       database read to stay honest about corpus/model/tool dependencies still being live.
 *   <li><b>Normalized financial decimals cross as strings.</b> {@link Field#normalizedNumber()} is
 *       a {@code String} produced by {@link BigDecimal#toPlainString()}. A JSON number would be
 *       re-read by the browser as an IEEE-754 double and silently lose digits, which for a
 *       parsed income figure is a wrong answer rather than a rounding detail. Coordinates and
 *       bounded confidences stay numeric: those are geometry and ratios, not money.
 *   <li><b>No engine credential, storage key, filename, or engine URL has a member to travel in.</b>
 *       The record components below are the whole vocabulary; there is no map, no passthrough, and
 *       no {@code Object} field that could carry one.
 * </ol>
 */
public final class LabDtos {

    private LabDtos() {}

    // ================================================================ prototype boundary

    /** The declared prototype limitation code and the dependencies that are still live. */
    public record Prototype(String code, List<String> liveDependencies) {

        public Prototype {
            liveDependencies = List.copyOf(liveDependencies);
        }

        /** The constant boundary, with no database read behind it. */
        public static Prototype current() {
            return new Prototype(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                    LabReleaseManifest.LIVE_DEPENDENCIES);
        }
    }

    // ================================================================ instances

    /** One Lab instance, its pinned release, and any drift the live pack has introduced. */
    public record Instance(
            String slug,
            String analyzerSlug,
            UUID productionReleaseId,
            int productionReleaseNumber,
            String productionManifestSha256,
            boolean driftDetected,
            UUID candidateReleaseId,
            String candidateManifestSha256,
            Prototype prototype) {

        public static Instance from(IncomeLabReleaseService.InstanceState state) {
            return new Instance(
                    state.instanceSlug(),
                    state.analyzerSlug(),
                    state.productionReleaseId(),
                    state.productionReleaseNumber(),
                    state.productionManifestSha256(),
                    state.driftDetected(),
                    state.candidateReleaseId(),
                    state.candidateManifestSha256(),
                    new Prototype(state.prototypeLimitations(), state.liveDependencies()));
        }
    }

    /** The instances view. One instance today; the list shape is what the dashboard renders. */
    public record InstancesResponse(List<Instance> instances, Prototype prototype) {

        public InstancesResponse {
            instances = List.copyOf(instances);
        }
    }

    // ================================================================ registration

    /**
     * An accepted registration. Identifiers and digest prefixes only — the upload itself was
     * forwarded to Document Engine and never copied, and its filename was never read.
     *
     * @param created false when this registration already existed, i.e. an idempotent replay
     */
    public record RegistrationResponse(
            UUID registrationId,
            UUID packageId,
            UUID jobId,
            UUID sourceId,
            int sourceCount,
            List<String> duplicateShaPrefixes,
            boolean created,
            Prototype prototype) {

        public RegistrationResponse {
            duplicateShaPrefixes = List.copyOf(duplicateShaPrefixes);
        }
    }

    // ================================================================ document status

    /** One immutable engine-result revision descriptor, as the browser sees it. */
    public record Revision(
            int revision,
            UUID processingJobId,
            int parseGeneration,
            String envelopeSchemaVersion,
            String envelopeSha256,
            long envelopeSizeBytes,
            String sourceSetSha256,
            String reuseEligibility,
            Instant createdAt) {}

    /**
     * A registered package's processing state.
     *
     * @param analyzable true for {@code COMPLETED} and for {@code HUMAN_REVIEW_REQUIRED}; the
     *     latter also carries {@code REVIEW_REQUIRED} in {@code warnings}
     */
    public record DocumentStatusResponse(
            UUID registrationId,
            UUID packageId,
            UUID jobId,
            String status,
            String currentStage,
            boolean analyzable,
            List<String> warnings,
            List<Revision> revisions,
            Prototype prototype) {

        public DocumentStatusResponse {
            warnings = List.copyOf(warnings);
            revisions = List.copyOf(revisions);
        }
    }

    // ================================================================ envelope view

    /** An evidence box in page points. Geometry, so it stays numeric. */
    public record Box(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {

        static Box from(EngineResultEnvelope.Box box) {
            return box == null ? null
                    : new Box(box.x(), box.y(), box.width(), box.height());
        }
    }

    /** One evidence span: where on which page this field was read. */
    public record Evidence(UUID pageId, String role, int ordinal, Box box) {}

    /**
     * One parsed field occurrence.
     *
     * @param normalizedNumber the normalized numeric value as a canonical decimal STRING, never a
     *     JSON number — see the class comment
     */
    public record Field(
            String name,
            String groupKey,
            String status,
            String dataType,
            String displayedText,
            String rawValue,
            String normalizedText,
            String normalizedNumber,
            String normalizedDate,
            BigDecimal confidence,
            String method,
            String extractorVersion,
            String validationStatus,
            boolean sensitive,
            List<Evidence> evidence,
            String reviewState) {

        public Field {
            evidence = List.copyOf(evidence);
        }

        /**
         * A sensitive occurrence keeps its identity, status, confidence, and evidence geometry
         * but drops every value arm. This projection leaves the engine's ADMIN boundary for a
         * browser, and the envelope it is cut from is unmasked; dropping the value here means no
         * screen has to remember to hide it.
         */
        static Field from(EngineResultEnvelope.FieldOccurrence field) {
            boolean redact = field.sensitive();
            EngineResultEnvelope.NormalizedValue normalized = redact ? null : field.normalized();
            return new Field(
                    field.name(),
                    field.groupKey(),
                    field.status().name(),
                    field.dataType(),
                    redact ? null : field.displayedText(),
                    redact ? null : field.rawValue(),
                    normalized == null ? null : normalized.text(),
                    normalized == null || normalized.number() == null
                            ? null : normalized.number().toPlainString(),
                    normalized == null || normalized.date() == null
                            ? null : normalized.date().toString(),
                    field.confidence(),
                    field.method(),
                    field.extractorVersion(),
                    field.validationStatus(),
                    field.sensitive(),
                    field.evidence().stream()
                            .map(span -> new Evidence(span.pageId(), span.role(), span.ordinal(),
                                    Box.from(span.box())))
                            .toList(),
                    field.reviewState().name());
        }
    }

    /** One logical document the engine grouped out of the package's pages. */
    public record Document(
            UUID id, String documentTypeCode, int ordinal, List<UUID> pageIds, List<Field> fields) {

        public Document {
            pageIds = List.copyOf(pageIds);
            fields = List.copyOf(fields);
        }

        static Document from(EngineResultEnvelope.LogicalDocument document) {
            return new Document(
                    document.id(),
                    document.documentTypeCode(),
                    document.ordinal(),
                    document.pageIds(),
                    document.fields().stream().map(Field::from).toList());
        }
    }

    /** One page's geometry and classification. {@code textLayer} is a mode, never page text. */
    public record Page(
            UUID id,
            int packagePageIndex,
            int sourcePageIndex,
            BigDecimal widthPt,
            BigDecimal heightPt,
            int rotation,
            String textLayer,
            boolean blank,
            boolean duplicate,
            String documentTypeCode,
            BigDecimal classificationConfidence,
            String classificationMethod) {

        static Page from(EngineResultEnvelope.EnginePage page) {
            EngineResultEnvelope.PageClassification classification = page.classification();
            return new Page(
                    page.id(),
                    page.packagePageIndex(),
                    page.sourcePageIndex(),
                    page.widthPt(),
                    page.heightPt(),
                    page.rotation(),
                    page.textLayer(),
                    page.blank(),
                    page.duplicate(),
                    classification == null ? null : classification.documentTypeCode(),
                    classification == null ? null : classification.confidence(),
                    classification == null ? null : classification.method());
        }
    }

    /** One compatibility warning, carrying field identity but never a field VALUE. */
    public record CompatibilityWarning(
            String code, int documentOrdinal, String documentTypeCode, String fieldName,
            String groupKey) {

        static CompatibilityWarning from(IncomeEnvelopeCompatibility.Warning warning) {
            return new CompatibilityWarning(warning.code().name(), warning.documentOrdinal(),
                    warning.documentTypeCode(), warning.fieldName(), warning.groupKey());
        }
    }

    /**
     * The verified parse of one exact engine revision, plus the descriptor identity that says
     * which immutable bytes produced it.
     */
    public record EnvelopeResponse(
            UUID registrationId,
            UUID packageId,
            int revision,
            int parseGeneration,
            UUID processingJobId,
            String envelopeVersion,
            String canonicalizationVersion,
            String envelopeSha256,
            int envelopeSizeBytes,
            String sourceSetSha256,
            String reuseEligibility,
            boolean compatible,
            String rejection,
            List<CompatibilityWarning> warnings,
            List<Page> pages,
            List<Document> documents,
            List<UUID> unassignedPageIds,
            Prototype prototype) {

        public EnvelopeResponse {
            warnings = List.copyOf(warnings);
            pages = List.copyOf(pages);
            documents = List.copyOf(documents);
            unassignedPageIds = List.copyOf(unassignedPageIds);
        }

        /** Projects a verified envelope and its compatibility decision onto the wire shape. */
        public static EnvelopeResponse of(UUID registrationId,
                                          EngineResultEnvelope envelope,
                                          int revision,
                                          String envelopeSha256,
                                          int envelopeSizeBytes,
                                          IncomeEnvelopeCompatibility.Decision decision) {
            return new EnvelopeResponse(
                    registrationId,
                    envelope.packageId(),
                    revision,
                    envelope.generation().parseGeneration(),
                    envelope.generation().processingJobId(),
                    envelope.envelopeVersion(),
                    envelope.canonicalizationVersion(),
                    envelopeSha256,
                    envelopeSizeBytes,
                    envelope.generation().sourceSetSha256(),
                    envelope.generation().reuseEligibility(),
                    decision.compatible(),
                    decision.rejection() == null ? null : decision.rejection().name(),
                    decision.warnings().stream().map(CompatibilityWarning::from).toList(),
                    envelope.pages().stream().map(Page::from).toList(),
                    envelope.documents().stream().map(Document::from).toList(),
                    envelope.unassignedPageIds(),
                    Prototype.current());
        }
    }

    // ================================================================ runs

    /** The body of a run request: which package, and optionally which historical revision. */
    public record RunRequest(UUID packageId, Integer revision) {}

    /** The engine-result identity a run was pinned to. */
    public record RunSource(
            UUID packageId,
            int revision,
            int parseGeneration,
            UUID processingJobId,
            String envelopeVersion,
            String canonicalizationVersion,
            String envelopeSha256,
            long envelopeSizeBytes,
            String sourceSetSha256,
            String reuseEligibility,
            int documentCount,
            int pageCount) {}

    /** One history row. Metadata only: the list view never decrypts a payload. */
    public record RunSummary(
            UUID runId,
            String instanceSlug,
            String status,
            String failureCode,
            UUID releaseId,
            UUID analysisRunId,
            UUID packageId,
            Integer revision,
            OffsetDateTime createdAt,
            OffsetDateTime terminalAt,
            int discussionExchangeCount,
            Prototype prototype) {}

    /** The history view, newest first. */
    public record RunHistoryResponse(List<RunSummary> runs, Prototype prototype) {

        public RunHistoryResponse {
            runs = List.copyOf(runs);
        }
    }

    /** One guideline citation, mirroring {@code CitationDto} so the shape matches {@code /analyze}. */
    public record Citation(
            String sourceName, String documentName, String section, String pageNumber,
            String effectiveDate) {

        public static Citation from(com.pragmaticds.rag.dto.CitationDto citation) {
            return new Citation(citation.sourceName(), citation.documentName(), citation.section(),
                    citation.pageNumber(), citation.effectiveDate());
        }
    }

    /** The decrypted terminal analysis of one run. Only a run DETAIL read produces this. */
    public record RunAnalysis(
            String status,
            String reportMarkdown,
            String findingsJson,
            List<Citation> citations,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            double costUsd,
            int providerAttempts,
            String reason) {

        public RunAnalysis {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    /** The read-model snapshot a run consumed; absent on envelope-only runs. Counts only. */
    public record RunReviewSnapshot(String fieldsSha256, int documentCount, int machineCount,
                                    int correctedCount, int rejectedCount) {
        public static RunReviewSnapshot from(LabRunReviewSnapshot row) {
            return new RunReviewSnapshot(row.getFieldsSha256(), row.getDocumentCount(),
                    row.getMachineCount(), row.getCorrectedCount(), row.getRejectedCount());
        }
    }

    /** One run's full detail: identity, pinned source, and — when SUCCEEDED — its analysis. */
    public record RunResponse(
            UUID runId,
            String instanceSlug,
            String status,
            String failureCode,
            UUID releaseId,
            int releaseNumber,
            String releaseManifestSha256,
            UUID analysisRunId,
            UUID registrationId,
            RunSource source,
            RunAnalysis analysis,
            RunReviewSnapshot reviewSnapshot,
            OffsetDateTime createdAt,
            OffsetDateTime terminalAt,
            boolean replayed,
            Prototype prototype) {}

    // ================================================================ discussion

    /** The body of a discussion request. The Lab composes the context; the caller sends a question. */
    public record MessageRequest(String question) {}

    /** One stored discussion message, decrypted for an authorized read. */
    public record DiscussionMessage(String role, int ordinal, String body, OffsetDateTime createdAt) {}

    /** One discussion exchange: its lifecycle plus, when terminal, its two message bodies. */
    public record DiscussionExchange(
            UUID exchangeId,
            int sequenceNumber,
            String status,
            String failureCode,
            List<DiscussionMessage> messages,
            String prototypeLimitations,
            OffsetDateTime createdAt,
            OffsetDateTime terminalAt) {

        public DiscussionExchange {
            messages = List.copyOf(messages);
        }
    }

    /** How the engine's reviewer state moved since this run pinned its snapshot; counts only. */
    public record ReviewDrift(
            boolean changed,
            int machineBefore, int machineNow,
            int correctedBefore, int correctedNow,
            int rejectedBefore, int rejectedNow) {}

    /** A run-pinned discussion transcript, in ascending exchange order. */
    public record DiscussionResponse(
            UUID runId,
            List<DiscussionExchange> exchanges,
            boolean replayed,
            ReviewDrift reviewDrift,
            Prototype prototype) {

        public DiscussionResponse {
            exchanges = List.copyOf(exchanges);
        }
    }

    // ================================================================ purge

    /** What an idempotent purge removed. Counts only — never a body, digest, or identifier set. */
    public record PurgeResponse(
            UUID runId,
            boolean deleted,
            int messagesDeleted,
            int exchangesDeleted,
            int payloadsDeleted,
            int documentsDeleted,
            boolean analysisRunDeleted,
            boolean enginePackageRetained,
            Prototype prototype) {}

    // ================================================================ errors

    /**
     * The only error body the Lab emits: a stable code, the request's correlation id, and safe
     * counts. There is no {@code message}, {@code cause}, or {@code detail} member, so a provider
     * body, engine URI, filename, or parsed value has nowhere to appear.
     */
    public record ErrorResponse(String code, String correlationId, Map<String, Object> counts) {

        public ErrorResponse {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
        }
    }
}
