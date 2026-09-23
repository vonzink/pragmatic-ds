package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The wire shape of an instance's parsed inputs: identity, structure, and verdicts only.
 *
 * <p><b>No extracted value crosses this boundary.</b> A field is represented by its name, group
 * key, status, data type, schema, extractor, confidence, and validation status — never by its raw
 * text, its displayed text, or any normalized arm. Neither is a browser filename, an engine
 * storage locator, a page's text, or a credential, none of which have a member here to travel in.
 *
 * <p>That is a deliberate divergence from the Income Lab's {@code EnvelopeResponse}, which does
 * return values because a human reviewing the parser's output is the Lab prototype's whole point.
 * These routes serve a different purpose — choosing and pinning which parse an instance analyzes —
 * and that purpose needs structure, not content.
 */
public final class ParsedInputDtos {
    private ParsedInputDtos() {}

    // ================================================================ requests

    /**
     * Pins one existing revision of one package to this instance.
     *
     * <p>{@code loanFacts} is optional and is NOT part of the request digest. What a registration
     * IS — the package, the revision, the reconciled source set — decides idempotency; the loan
     * it belongs to does not, so supplying facts never turns a replay into a new registration.
     */
    public record SelectParsedInputRequest(
            UUID packageId, Integer revision, List<UUID> selectedSourceIds,
            LoanFactsRequest loanFacts) {}

    /**
     * The loan-level facts an assets run needs and a bank statement cannot supply.
     *
     * <p>All four are optional, and an omitted or unrecognized value means "not supplied" rather
     * than a default — the analyzer reports its large-deposit screen as unavailable instead of
     * applying a threshold that may not be this loan's.
     *
     * @param program                 FANNIE_MAE, FREDDIE_MAC, FHA, VA, or USDA
     * @param loanPurpose             PURCHASE or REFINANCE
     * @param qualifyingMonthlyIncome the Fannie/Freddie threshold basis
     * @param adjustedValue           the FHA threshold basis; for most purchases the sales price
     */
    public record LoanFactsRequest(
            String program, String loanPurpose,
            BigDecimal qualifyingMonthlyIncome, BigDecimal adjustedValue) {}

    // ================================================================ responses

    /** What an accepted upload produced. Digest prefixes and counts, never a filename. */
    public record UploadedParsedInput(
            UUID registrationId,
            UUID packageId,
            UUID processingJobId,
            UUID engineSourceId,
            int sourceCount,
            List<String> duplicateShaPrefixes,
            boolean created) {

        public UploadedParsedInput {
            duplicateShaPrefixes = List.copyOf(duplicateShaPrefixes);
        }
    }

    /** One registration as stored: what it points at, and whether it is pinned to a revision. */
    public record ParsedInputSummary(
            UUID registrationId,
            UUID brainId,
            String instanceSlug,
            UUID packageId,
            UUID processingJobId,
            String registrationMode,
            boolean pinned,
            Integer selectedRevision,
            String sourceSetSha256,
            List<UUID> selectedSourceIds,
            OffsetDateTime registeredAt) {

        public ParsedInputSummary {
            selectedSourceIds = List.copyOf(selectedSourceIds);
        }
    }

    /** A parse verified against the engine, plus this release's verdict on it. */
    public record PinnedParsedInput(
            UUID registrationId,
            UUID brainId,
            String instanceSlug,
            UUID packageId,
            int revision,
            UUID processingJobId,
            int parseGeneration,
            String envelopeVersion,
            String canonicalizationVersion,
            String envelopeSha256,
            long envelopeSizeBytes,
            String sourceSetSha256,
            List<UUID> selectedSourceIds,
            Compatibility compatibility) {

        public PinnedParsedInput {
            selectedSourceIds = List.copyOf(selectedSourceIds);
        }
    }

    /**
     * The structure of one verified parse.
     *
     * <p>{@code pinned} distinguishes the two things this can be: the exact revision and selection
     * a registration was pinned to, or — for an upload nothing has selected yet — the whole of the
     * package's newest parse, which is what a caller reads in order to choose a selection.
     */
    public record ParsedInputEnvelope(
            UUID registrationId,
            UUID packageId,
            int revision,
            boolean pinned,
            UUID processingJobId,
            int parseGeneration,
            String envelopeVersion,
            String canonicalizationVersion,
            String envelopeSha256,
            long envelopeSizeBytes,
            String sourceSetSha256,
            Compatibility compatibility,
            List<SourceMetadata> sources,
            List<PageMetadata> pages,
            List<DocumentMetadata> documents,
            List<UUID> unassignedPageIds) {

        public ParsedInputEnvelope {
            sources = List.copyOf(sources);
            pages = List.copyOf(pages);
            documents = List.copyOf(documents);
            unassignedPageIds = List.copyOf(unassignedPageIds);
        }
    }

    /** A release's verdict; {@code rejection} is null exactly when {@code compatible}. */
    public record Compatibility(
            boolean compatible,
            String rejection,
            int supportedDocumentCount,
            List<CompatibilityWarning> warnings) {

        public Compatibility {
            warnings = List.copyOf(warnings);
        }
    }

    /** One warning, carrying field identity and never a field value. */
    public record CompatibilityWarning(
            String code, int documentOrdinal, String documentTypeCode, String fieldName,
            String groupKey) {}

    /** One selectable original, identified by content digest — never by filename. */
    public record SourceMetadata(
            UUID sourceId, int ordinal, String contentSha256, long sizeBytes, String contentType) {}

    /** One page's placement and classification. {@code textLayer} is a mode, never page text. */
    public record PageMetadata(
            UUID pageId,
            UUID sourceId,
            int packagePageIndex,
            int sourcePageIndex,
            String textLayer,
            boolean blank,
            boolean duplicate,
            String documentTypeCode,
            BigDecimal classificationConfidence) {}

    /** One logical document the engine grouped out of the selected pages. */
    public record DocumentMetadata(
            UUID documentId,
            String documentTypeCode,
            int ordinal,
            List<UUID> pageIds,
            List<FieldMetadata> fields) {

        public DocumentMetadata {
            pageIds = List.copyOf(pageIds);
            fields = List.copyOf(fields);
        }
    }

    /**
     * One extracted field, described but never quoted.
     *
     * <p>There is no {@code rawValue}, {@code displayedText}, or normalized arm here on purpose.
     * {@code status} says whether the engine found the field at all and {@code evidencePageIds}
     * says where it looked, which is everything an operator choosing a parse needs.
     */
    public record FieldMetadata(
            String name,
            String groupKey,
            String status,
            String dataType,
            String schemaVersion,
            String method,
            String extractorVersion,
            BigDecimal confidence,
            String validationStatus,
            boolean sensitive,
            List<UUID> evidencePageIds) {

        public FieldMetadata {
            evidencePageIds = List.copyOf(evidencePageIds);
        }
    }

    // ================================================================ projections

    static UploadedParsedInput uploaded(ParsedDataResolver.RegisteredUpload upload) {
        return new UploadedParsedInput(upload.registrationId(), upload.packageId(),
                upload.processingJobId(), upload.engineSourceId(), upload.sourceCount(),
                upload.duplicateShaPrefixes(), upload.created());
    }

    static ParsedInputSummary summary(ParsedDataResolver.RegisteredInput registered) {
        return new ParsedInputSummary(
                registered.registrationId(),
                registered.brainId(),
                registered.instanceSlug(),
                registered.packageId(),
                registered.processingJobId(),
                registered.registrationMode().name(),
                registered.selectedRevision() != null,
                registered.selectedRevision(),
                registered.sourceSetSha256(),
                registered.selectedSourceIds(),
                registered.registeredAt());
    }

    static PinnedParsedInput pinned(
            UUID brainId, String instanceSlug, ParsedDataResolver.VerifiedParsedInput verified) {
        return new PinnedParsedInput(
                verified.registrationId(),
                brainId,
                instanceSlug,
                verified.packageId(),
                verified.revision(),
                verified.processingJobId(),
                verified.parseGeneration(),
                verified.envelopeVersion(),
                verified.canonicalizationVersion(),
                verified.envelopeSha256(),
                verified.envelopeSizeBytes(),
                verified.sourceSetSha256(),
                verified.selectedSourceIds(),
                compatibility(verified.compatibility()));
    }

    static ParsedInputEnvelope envelope(
            ParsedDataResolver.VerifiedParsedInput verified, boolean pinned) {
        // The selection is what an instance would analyze, so the selection is what is described.
        EngineResultEnvelope selected = verified.selectedEnvelope();
        return new ParsedInputEnvelope(
                verified.registrationId(),
                verified.packageId(),
                verified.revision(),
                pinned,
                verified.processingJobId(),
                verified.parseGeneration(),
                verified.envelopeVersion(),
                verified.canonicalizationVersion(),
                verified.envelopeSha256(),
                verified.envelopeSizeBytes(),
                verified.sourceSetSha256(),
                compatibility(verified.compatibility()),
                selected.sources().stream()
                        .sorted(Comparator.comparingInt(EngineResultEnvelope.SourceFile::ordinal))
                        .map(ParsedInputDtos::source).toList(),
                selected.pages().stream().map(ParsedInputDtos::page).toList(),
                selected.documents().stream().map(ParsedInputDtos::document).toList(),
                selected.unassignedPageIds());
    }

    private static Compatibility compatibility(
            ParsedDataCompatibilityService.CompatibilityDecision decision) {
        return new Compatibility(
                decision.compatible(),
                decision.rejection() == null ? null : decision.rejection().name(),
                decision.supportedDocumentCount(),
                decision.warnings().stream()
                        .map(warning -> new CompatibilityWarning(
                                warning.code().name(), warning.documentOrdinal(),
                                warning.documentTypeCode(), warning.fieldName(),
                                warning.groupKey()))
                        .toList());
    }

    private static SourceMetadata source(EngineResultEnvelope.SourceFile source) {
        return new SourceMetadata(source.id(), source.ordinal(), source.contentSha256(),
                source.sizeBytes(), source.contentType());
    }

    private static PageMetadata page(EngineResultEnvelope.EnginePage page) {
        EngineResultEnvelope.PageClassification classification = page.classification();
        return new PageMetadata(
                page.id(),
                page.sourceFileId(),
                page.packagePageIndex(),
                page.sourcePageIndex(),
                page.textLayer(),
                page.blank(),
                page.duplicate(),
                classification == null ? null : classification.documentTypeCode(),
                classification == null ? null : classification.confidence());
    }

    private static DocumentMetadata document(EngineResultEnvelope.LogicalDocument document) {
        return new DocumentMetadata(
                document.id(),
                document.documentTypeCode(),
                document.ordinal(),
                document.pageIds(),
                document.fields().stream().map(ParsedInputDtos::field).toList());
    }

    private static FieldMetadata field(EngineResultEnvelope.FieldOccurrence field) {
        return new FieldMetadata(
                field.name(),
                field.groupKey(),
                field.status().name(),
                field.dataType(),
                field.schema() == null ? null : field.schema().version(),
                field.method(),
                field.extractorVersion(),
                field.confidence(),
                field.validationStatus(),
                field.sensitive(),
                field.evidence().stream()
                        .map(EngineResultEnvelope.EvidenceSpan::pageId)
                        .distinct().toList());
    }
}
