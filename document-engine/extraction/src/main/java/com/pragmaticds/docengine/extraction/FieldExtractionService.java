package com.pragmaticds.docengine.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.domain.FieldEvidence;
import com.pragmaticds.docengine.extraction.extract.DetectionRef;
import com.pragmaticds.docengine.extraction.extract.EvidenceRef;
import com.pragmaticds.docengine.extraction.extract.FieldExtractionEngine;
import com.pragmaticds.docengine.extraction.extract.FieldOutcome;
import com.pragmaticds.docengine.extraction.extract.LayoutNode;
import com.pragmaticds.docengine.extraction.extract.NormalizedValue;
import com.pragmaticds.docengine.extraction.extract.PageContent;
import com.pragmaticds.docengine.extraction.extract.SpanRef;
import com.pragmaticds.docengine.extraction.overlay.FieldSnapshot;
import com.pragmaticds.docengine.extraction.overlay.ReviewCarryForward;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.extraction.repo.FieldEvidenceRepository;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.LayoutElementRepository;
import com.pragmaticds.docengine.parsing.repo.LayoutElementSpanRepository;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The EXTRACTING stage's persistence half: projects each logical document's pages into the
 * engine-facing records ({@link PageContent}/{@link SpanRef}/{@link LayoutNode}), runs the
 * {@link FieldExtractionEngine}, and persists one {@link ExtractedField} per schema field —
 * found or missing — plus the {@link FieldEvidence} chain behind every found value.
 *
 * <p>Documents whose type has no active schema are SKIPPED, not failed: a bank statement flowing
 * through before its schema ships is normal operation, and the stage detail reports the count.
 *
 * <p>Idempotent per stage retry: the package's evidence and fields are deleted first
 * (children before parents), so re-running EXTRACTING converges instead of accumulating —
 * the same delete-then-recreate rule as every other stage.
 */
@Service
public class FieldExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FieldExtractionService.class);

    /**
     * Persisted onto every row this service writes. Keep in sync with the
     * DefaultFieldExtractionEngine VERSION constant (built in the parallel worktree).
     */
    static final String ENGINE_VERSION = "engine/1.0.0";

    private final ExtractionSchemaLoader schemaLoader;
    private final FieldExtractionEngine engine;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final PageRepository pages;
    private final TextSpanRepository textSpans;
    private final LayoutElementRepository layoutElements;
    private final LayoutElementSpanRepository layoutElementSpans;
    private final ExtractedFieldRepository fields;
    private final FieldEvidenceRepository evidence;
    /** Carries human review from the rows this stage destroys onto the ones it writes. */
    private final ReviewCarryForward reviewCarryForward;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final ObjectMapper mapper = new ObjectMapper();

    public FieldExtractionService(
            ExtractionSchemaLoader schemaLoader,
            FieldExtractionEngine engine,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            PageRepository pages,
            TextSpanRepository textSpans,
            LayoutElementRepository layoutElements,
            LayoutElementSpanRepository layoutElementSpans,
            ExtractedFieldRepository fields,
            FieldEvidenceRepository evidence,
            ReviewCarryForward reviewCarryForward,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.schemaLoader = schemaLoader;
        this.engine = engine;
        this.documents = documents;
        this.links = links;
        this.pages = pages;
        this.textSpans = textSpans;
        this.layoutElements = layoutElements;
        this.layoutElementSpans = layoutElementSpans;
        this.fields = fields;
        this.evidence = evidence;
        this.reviewCarryForward = reviewCarryForward;
        this.jdbc = jdbc;
    }

    /**
     * The REVIEWED current occurrences of a package, as they stand right now. Reviewed only: an
     * untouched row has nothing to carry, and loading every field of a large package to discard it
     * would make the common case (no corrections anywhere) pay for the rare one.
     */
    private List<FieldSnapshot> reviewedSnapshotOf(List<LogicalDocument> packageDocuments) {
        if (packageDocuments.isEmpty()) {
            return List.of();
        }
        return fields
                .findByLogicalDocumentIdInAndCurrentTrue(
                        packageDocuments.stream().map(LogicalDocument::getId).toList())
                .stream()
                .filter(field -> !ExtractedField.REVIEW_NOT_REVIEWED.equals(field.getReviewStatus()))
                .map(FieldSnapshot::of)
                .toList();
    }

    /**
     * One persisted field OCCURRENCE, projected for the stage digest. {@code groupKey} is part of
     * the tuple because without it three occurrences of one name — same method, same confidence —
     * are indistinguishable, and a swap between column A and column B would leave the digest
     * unchanged. Null for an ungrouped field.
     */
    public record FieldDigest(
            UUID documentId,
            String fieldName,
            String groupKey,
            String method,
            BigDecimal confidence) {}

    /** What the EXTRACTING stage reports: counters plus the ordered per-field digest tuples. */
    public record ExtractionSummary(
            int documentsProcessed,
            int documentsSkipped,
            int fieldsExtracted,
            int fieldsMissing,
            List<FieldDigest> fields) {}

    /** Extracts every schema-bearing document of the package. Joins the stage attempt's tx. */
    @Transactional
    public ExtractionSummary extractForPackage(UUID packageId) {
        UUID orgId = TenantContext.require();

        // Declare the schema view this whole stage runs under, before the first document is
        // looked up. A package with no documents would otherwise consult the loader zero times,
        // leaving the run unable to say what extraction behaved like — which costs a reuse hit
        // for a parse whose behavior was never in doubt.
        schemaLoader.pinViewForCurrentRun();

        List<LogicalDocument> packageDocuments = documents.findByPackageIdOrderByOrdinal(packageId);

        // Snapshot the human review attached to the rows about to be destroyed. This MUST happen
        // before the delete below: after it the rows are gone, and with them the only record of
        // which coordinate each review_decision's subject_id belonged to. Without this a
        // correction an LO made before a regroup is silently replaced by the machine's reading.
        List<FieldSnapshot> reviewedBefore = reviewedSnapshotOf(packageDocuments);

        // Retry idempotency: evidence before fields (FK), delete-then-recreate.
        evidence.deleteByPackageIdAndOrgId(packageId, orgId);
        fields.deleteByPackageIdAndOrgId(packageId, orgId);

        int processed = 0;
        int skipped = 0;
        int found = 0;
        int missing = 0;
        List<FieldDigest> digests = new ArrayList<>();
        List<ExtractedField> replacements = new ArrayList<>();
        for (LogicalDocument document : packageDocuments) {
            var schema = schemaLoader.activeSchemaFor(document.getDocumentTypeCode());
            if (schema.isEmpty()) {
                skipped++;
                continue;
            }
            UUID schemaId =
                    schemaLoader
                            .activeSchemaIdentityFor(document.getDocumentTypeCode())
                            .map(ExtractionSchemaLoader.SchemaIdentity::id)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "loaded schema without a persisted identity"));
            List<PageContent> pageContents = projectPages(document.getId(), orgId);
            for (FieldOutcome outcome : engine.extract(schema.get(), pageContents)) {
                ExtractedField persisted = persistOutcome(document.getId(), schemaId, outcome);
                replacements.add(persisted);
                digests.add(
                        new FieldDigest(
                                document.getId(),
                                persisted.getFieldName(),
                                persisted.getGroupKey(),
                                persisted.getExtractionMethod(),
                                persisted.getConfidence()));
                if (outcome.found()) {
                    found++;
                } else {
                    missing++;
                }
            }
            processed++;
        }

        // Re-attach the human review to the rows that replaced the ones it named. Only after every
        // document is persisted: a coordinate's replacement may be written by any iteration, and a
        // correction must be matched against the whole new generation, never a partial one.
        reviewCarryForward.apply(reviewedBefore, replacements);

        log.info(
                "extracted package={} documents={} skipped={} fieldsFound={} fieldsMissing={}",
                packageId,
                processed,
                skipped,
                found,
                missing);
        return new ExtractionSummary(processed, skipped, found, missing, List.copyOf(digests));
    }

    // ── page projection ─────────────────────────────────────────────────────

    /**
     * ONE page, projected exactly as {@link #projectPages} projects each of a document's — the
     * entry point {@link InstanceKeyBoundaryDetector} probes an instance key with.
     *
     * <p>Public and single-page because the question it serves is different in kind: extraction
     * asks "what does this DOCUMENT say", and the first page to answer wins the field, which is
     * precisely why a merged three-statement document reports one period and hides two. The probe
     * asks "what does THIS PAGE say" and must be able to get three different answers. Sharing the
     * projection rather than re-deriving it keeps the span, table and detection shapes identical
     * on both paths, so the probe reads what the extractor would read.
     *
     * <p>Empty when the page is absent under this org — never an exception: the detector's
     * contract is that it may answer "nothing" for any reason.
     */
    @Transactional(readOnly = true)
    public List<PageContent> projectPage(UUID pageId, UUID orgId) {
        Page page = pages.findByIdAndOrgId(pageId, orgId).orElse(null);
        if (page == null) {
            return List.of();
        }
        List<SpanRef> spans =
                textSpans.findByPageIdOrderBySourceAscOrdinalAsc(page.getId()).stream()
                        .map(FieldExtractionService::toSpanRef)
                        .toList();
        List<LayoutElement> elements = layoutElements.findByPageIdOrderByOrdinal(page.getId());
        return List.of(
                new PageContent(
                        page.getId(),
                        page.getPackagePageIndex(),
                        spans,
                        projectTables(elements, spans),
                        projectDetections(elements)));
    }

    private List<PageContent> projectPages(UUID logicalDocumentId, UUID orgId) {
        List<PageContent> contents = new ArrayList<>();
        for (var link : links.findByLogicalDocumentIdOrderByOrdinal(logicalDocumentId)) {
            Page page =
                    pages.findByIdAndOrgId(link.getPageId(), orgId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "logical document page without page row"));
            List<SpanRef> spans =
                    textSpans.findByPageIdOrderBySourceAscOrdinalAsc(page.getId()).stream()
                            .map(FieldExtractionService::toSpanRef)
                            .toList();
            List<LayoutElement> elements = layoutElements.findByPageIdOrderByOrdinal(page.getId());
            contents.add(
                    new PageContent(
                            page.getId(),
                            page.getPackagePageIndex(),
                            spans,
                            projectTables(elements, spans),
                            projectDetections(elements)));
        }
        return contents;
    }

    private static SpanRef toSpanRef(TextSpan span) {
        return new SpanRef(
                span.getId(),
                span.getText(),
                new Box(span.getX(), span.getY(), span.getWidth(), span.getHeight()),
                span.getConfidence() == null ? BigDecimal.ONE : span.getConfidence());
    }

    /** TABLE-rooted trees in reading order, cell spans in link-ordinal order (batch-fetched). */
    private List<LayoutNode> projectTables(List<LayoutElement> elements, List<SpanRef> pageSpans) {
        if (elements.isEmpty()) {
            return List.of();
        }
        Map<Long, SpanRef> spansById =
                pageSpans.stream().collect(Collectors.toMap(SpanRef::id, Function.identity()));
        Map<UUID, List<SpanRef>> spansByElement = new HashMap<>();
        layoutElementSpans
                .findByLayoutElementIdInOrderByLayoutElementIdAscOrdinalAsc(
                        elements.stream().map(LayoutElement::getId).toList())
                .forEach(
                        link -> {
                            SpanRef span = spansById.get(link.getTextSpanId());
                            if (span != null) {
                                spansByElement
                                        .computeIfAbsent(link.getLayoutElementId(), k -> new ArrayList<>())
                                        .add(span);
                            }
                        });
        Map<UUID, List<LayoutElement>> childrenByParent =
                elements.stream()
                        .filter(element -> element.getParentElementId() != null)
                        .collect(Collectors.groupingBy(LayoutElement::getParentElementId));
        return elements.stream()
                .filter(
                        element ->
                                element.getElementType() == LayoutElementType.TABLE
                                        && element.getParentElementId() == null)
                .map(table -> toNode(table, childrenByParent, spansByElement))
                .toList();
    }

    /**
     * CHECKBOX/SIGNATURE detections in element (reading) order — the detector rungs' input.
     * {@code checked} comes from the worker's attributes JSON ({@code {"checked": bool,
     * "fillRatio": n}}), parsed exactly the way {@link #toNode} parses {@code row}/{@code col};
     * signatures carry no checked state. Package-private static for the unit test.
     */
    static List<DetectionRef> projectDetections(List<LayoutElement> elements) {
        List<DetectionRef> detections = new ArrayList<>();
        for (LayoutElement element : elements) {
            LayoutElementType type = element.getElementType();
            if (type != LayoutElementType.CHECKBOX && type != LayoutElementType.SIGNATURE) {
                continue;
            }
            JsonNode attributes = parseAttributes(element.getAttributes());
            Boolean checked =
                    type == LayoutElementType.CHECKBOX && attributes.hasNonNull("checked")
                            ? attributes.get("checked").asBoolean()
                            : null;
            detections.add(
                    new DetectionRef(
                            element.getId(),
                            type,
                            new Box(
                                    element.getX(),
                                    element.getY(),
                                    element.getWidth(),
                                    element.getHeight()),
                            checked,
                            element.getConfidence()));
        }
        return List.copyOf(detections);
    }

    private LayoutNode toNode(
            LayoutElement element,
            Map<UUID, List<LayoutElement>> childrenByParent,
            Map<UUID, List<SpanRef>> spansByElement) {
        JsonNode attributes = parseAttributes(element.getAttributes());
        return new LayoutNode(
                element.getId(),
                element.getElementType(),
                new Box(element.getX(), element.getY(), element.getWidth(), element.getHeight()),
                attributes.hasNonNull("row") ? attributes.get("row").asInt() : null,
                attributes.hasNonNull("col") ? attributes.get("col").asInt() : null,
                spansByElement.getOrDefault(element.getId(), List.of()),
                childrenByParent.getOrDefault(element.getId(), List.of()).stream()
                        .map(child -> toNode(child, childrenByParent, spansByElement))
                        .toList());
    }

    private static JsonNode parseAttributes(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(attributes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // ids only — attribute content never reaches a log line.
            throw new IllegalStateException("unreadable layout element attributes");
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────

    /**
     * One row per OCCURRENCE, with its own VALUE and LABEL evidence chains: {@code field_evidence}
     * hangs off {@code extracted_field_id} alone, so N occurrences produce N chains with no new
     * mechanism (plan root, grounded fact 4). {@code group_key} is null for an ungrouped field,
     * which under {@code coalesce(group_key, '')} is byte-identically the pre-Spec-5a behaviour.
     */
    private ExtractedField persistOutcome(UUID documentId, UUID schemaId, FieldOutcome outcome) {
        NormalizedValue normalized = outcome.normalized();
        boolean found = outcome.found();
        if (found
                && outcome.valueEvidence().isEmpty()
                && outcome.method() != ExtractionMethod.SIGNATURE_PRESENCE
                && outcome.method() != ExtractionMethod.DERIVED) {
            // Invariant (Phase 5 criterion 2, amended by Spec 3): every found value traces to a
            // VALUE box — except SIGNATURE_PRESENCE UNSIGNED, the one answer whose value IS an
            // absence (D5). Any other empty-evidence find is an engine bug, not data.
            throw new IllegalStateException("found field without value evidence");
        }
        ExtractedField field =
                fields.save(
                        new ExtractedField(
                                documentId,
                                schemaId,
                                outcome.field().name(),
                                outcome.field().dataType().name(),
                                found ? outcome.displayedText() : null,
                                found ? outcome.rawValue() : null,
                                found && normalized != null ? normalized.text() : null,
                                found && normalized != null ? normalized.number() : null,
                                found && normalized != null ? normalized.date() : null,
                                null,
                                outcome.method().name(),
                                ENGINE_VERSION,
                                found
                                        ? outcome.confidence().overall()
                                        : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                                found ? componentsJson(outcome) : null,
                                found
                                        ? ExtractedField.VALIDATION_NOT_VALIDATED
                                        : ExtractedField.VALIDATION_MANUAL_REVIEW_REQUIRED,
                                ExtractedField.REVIEW_NOT_REVIEWED,
                                outcome.field().sensitive(),
                                outcome.groupKey()));
        persistEvidence(field.getId(), outcome, outcome.valueEvidence(), FieldEvidence.ROLE_VALUE);
        persistEvidence(field.getId(), outcome, outcome.labelEvidence(), FieldEvidence.ROLE_LABEL);
        return field;
    }

    private void persistEvidence(
            UUID fieldId, FieldOutcome outcome, List<EvidenceRef> refs, String role) {
        for (int ordinal = 0; ordinal < refs.size(); ordinal++) {
            EvidenceRef ref = refs.get(ordinal);
            evidence.save(
                    new FieldEvidence(
                            fieldId,
                            outcome.pageId(),
                            ref.layoutElementId(),
                            ref.spanId(),
                            stored(ref.box().x()),
                            stored(ref.box().y()),
                            stored(ref.box().width()),
                            stored(ref.box().height()),
                            role,
                            ordinal));
        }
    }

    private String componentsJson(FieldOutcome outcome) {
        ObjectNode components = mapper.createObjectNode();
        components.put("spanConfidence", outcome.confidence().spanConfidence());
        components.put("anchorStrength", outcome.confidence().anchorStrength());
        components.put("normalizerCertainty", outcome.confidence().normalizerCertainty());
        return components.toString();
    }

    
    /** numeric(10,2) is the stored form for evidence boxes. */
    private static BigDecimal stored(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
