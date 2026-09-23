package com.pragmaticds.docengine.results.canonical;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import com.pragmaticds.docengine.extraction.domain.FieldEvidence;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import com.pragmaticds.docengine.extraction.repo.ExtractionSchemaRepository;
import com.pragmaticds.docengine.extraction.repo.FieldEvidenceRepository;
import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Classification;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationAnchor;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationBox;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationRange;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationScore;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.DeterministicRunnerUp;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Document;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Evidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Field;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.LlmEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Membership;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.RuleAnchorEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.StageProvenance;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Loads the exact live machine projections used by one finalization generation. */
@Component
public class MachineResultSnapshotLoader {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> CLASSIFICATION_EVIDENCE_MEMBERS =
            Set.of("anchors", "scores");
    /**
     * Written by {@code AiPageClassificationService} for {@code method = LLM}. A separate exact
     * member set rather than a widening of the anchor one: the two shapes share nothing, and
     * admitting either shape under either method would let an evidence document written by one
     * producer be published as if the other had verified it (2026-09-10,
     * MACHINE_SNAPSHOT_UNAVAILABLE on every package holding an LLM-classified page).
     */
    private static final Set<String> LLM_EVIDENCE_MEMBERS =
            Set.of("source", "model", "promptVersion", "matchedSpanIds", "offsets");
    /** Absent when the superseded row carried no scores for a runner-up to be read from. */
    private static final String RUNNER_UP_MEMBER = "deterministicRunnerUp";
    private static final Set<String> RUNNER_UP_MEMBERS = Set.of("type", "score");
    /** Row kinds, never stored values: these travel into {@code processing_stage.error_detail}. */
    private static final String RULE_ANCHOR_EVIDENCE_KIND = "RULE_ANCHOR classification evidence";
    private static final String LLM_EVIDENCE_KIND = "LLM classification evidence";
    /**
     * Written by {@code PageClassifier} only when two packs both clear their thresholds on one page
     * (Phase B4). Optional here for exactly that reason: a lone bank statement never carries it, a
     * tax return with schedules routinely does, and requiring the exact member set refused every
     * multi-document package at FINALIZING (2026-09-07, MACHINE_SNAPSHOT_UNAVAILABLE).
     */
    private static final String CO_QUALIFYING_TYPES_MEMBER = "coQualifyingTypes";
    private static final Set<String> CLASSIFICATION_ANCHOR_MEMBERS =
            Set.of(
                    "packType",
                    "packVersion",
                    "anchorId",
                    "weight",
                    "spanIds",
                    "boxes",
                    "range");
    private static final Set<String> CLASSIFICATION_BOX_MEMBERS =
            Set.of("x", "y", "width", "height");
    private static final Set<String> CLASSIFICATION_RANGE_MEMBERS = Set.of("start", "end");
    private static final Set<String> CLASSIFICATION_SCORE_MEMBERS =
            Set.of("packType", "packVersion", "score", "minConfidence", "targetScore");
    private static final Pattern DOCUMENT_TYPE_IDENTIFIER =
            Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern EVIDENCE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern VERSION_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    /**
     * A configured model id, which routinely carries a vendor prefix ({@code anthropic/claude-…})
     * or a deployment suffix ({@code gpt-4o:2026-05}). Still an identifier, never page text.
     */
    private static final Pattern MODEL_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+:/-]{0,127}");

    private final DocumentPackageRepository packages;
    private final SourceFileRepository sources;
    private final PageRepository pages;
    private final ClassificationResultRepository classifications;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository memberships;
    private final ExtractedFieldRepository fields;
    private final FieldEvidenceRepository evidence;
    private final ExtractionSchemaRepository schemas;
    private final ProcessingJobRepository jobs;
    private final ProcessingStageRepository stages;
    private final CanonicalJsonWriter canonical = new CanonicalJsonWriter();

    public MachineResultSnapshotLoader(
            DocumentPackageRepository packages,
            SourceFileRepository sources,
            PageRepository pages,
            ClassificationResultRepository classifications,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository memberships,
            ExtractedFieldRepository fields,
            FieldEvidenceRepository evidence,
            ExtractionSchemaRepository schemas,
            ProcessingJobRepository jobs,
            ProcessingStageRepository stages) {
        this.packages = packages;
        this.sources = sources;
        this.pages = pages;
        this.classifications = classifications;
        this.documents = documents;
        this.memberships = memberships;
        this.fields = fields;
        this.evidence = evidence;
        this.schemas = schemas;
        this.jobs = jobs;
        this.stages = stages;
    }

    /**
     * Participates in a caller's finalization transaction, or creates one read transaction for a
     * focused projection. Every repository read is completed before the immutable snapshot is
     * returned.
     */
    @Transactional(readOnly = true)
    public MachineResultSnapshot load(EnvelopeAssemblyRequest request) {
        UUID orgId = TenantContext.require();
        DocumentPackage documentPackage =
                packages
                        .findByIdAndOrgIdAndDeletedAtIsNull(request.packageId(), orgId)
                        .orElseThrow(() -> inconsistent("package not visible to tenant"));
        ProcessingJob job =
                jobs.findByIdAndOrgId(request.processingJobId(), orgId)
                        .orElseThrow(() -> inconsistent("job not visible to tenant"));
        if (!orgId.equals(documentPackage.getOrgId())
                || !orgId.equals(job.getOrgId())
                || !request.packageId().equals(job.getPackageId())
                || request.parseGeneration() != job.getParseGeneration()) {
            throw inconsistent("job does not match the requested package, tenant or generation");
        }

        List<SourceFile> sourceRows = sources.findByPackageIdOrderByOrdinal(request.packageId());
        List<Page> pageRows = pages.findByPackageIdOrderByPackagePageIndex(request.packageId());
        Set<UUID> pageIds = pageRows.stream().map(Page::getId).collect(Collectors.toSet());
        List<ClassificationResult> classificationRows =
                pageIds.isEmpty()
                        ? List.of()
                        : classifications.findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                                ClassificationResult.SUBJECT_PAGE, pageIds);
        List<LogicalDocument> documentRows =
                documents.findByPackageIdOrderByOrdinal(request.packageId());
        Set<UUID> documentIds =
                documentRows.stream().map(LogicalDocument::getId).collect(Collectors.toSet());
        List<LogicalDocumentPage> membershipRows =
                documentIds.isEmpty()
                        ? List.of()
                        : memberships.findByLogicalDocumentIdIn(documentIds);
        List<ExtractedField> fieldRows =
                documentIds.isEmpty()
                        ? List.of()
                        : fields.findByLogicalDocumentIdInAndCurrentTrue(documentIds);
        Set<UUID> fieldIds =
                fieldRows.stream().map(ExtractedField::getId).collect(Collectors.toSet());
        List<FieldEvidence> evidenceRows =
                fieldIds.isEmpty()
                        ? List.of()
                        : evidence
                                .findByExtractedFieldIdInOrderByExtractedFieldIdAscRoleAscOrdinalAsc(
                                        fieldIds);
        Set<UUID> schemaIds =
                fieldRows.stream().map(ExtractedField::getSchemaId).collect(Collectors.toSet());
        List<ExtractionSchema> schemaRows =
                schemaIds.isEmpty() ? List.of() : schemas.findExactVisibleTo(schemaIds, orgId);
        Map<UUID, ExtractionSchema> schemaById = exactSchemaMap(schemaIds, schemaRows);
        List<ProcessingStage> successfulStageRows =
                stages.findByJobIdOrderByCreatedAtAsc(request.processingJobId()).stream()
                        .filter(stage -> stage.getStatus() == StageStatus.SUCCEEDED)
                        .filter(stage -> !"FINALIZING".equals(stage.getStage().name()))
                        .toList();

        validateLoadedRows(
                orgId,
                request.packageId(),
                sourceRows,
                pageRows,
                classificationRows,
                documentRows,
                membershipRows,
                fieldRows,
                evidenceRows,
                successfulStageRows,
                schemaById);

        MachineResultSnapshot snapshot =
                new MachineResultSnapshot(
                        request.packageId(),
                        request.processingJobId(),
                        request.parseGeneration(),
                        sourceRows.stream()
                                .map(
                                        source ->
                                                new MachineResultSnapshot.Source(
                                                        source.getId(),
                                                        source.getOrdinal(),
                                                        source.getSha256(),
                                                        source.getSizeBytes(),
                                                        source.getContentType()))
                                .toList(),
                        pageRows.stream()
                                .map(
                                        page ->
                                                new MachineResultSnapshot.Page(
                                                        page.getId(),
                                                        page.getSourceFileId(),
                                                        page.getPageIndex(),
                                                        page.getPackagePageIndex(),
                                                        page.getWidthPt(),
                                                        page.getHeightPt(),
                                                        page.getRotation(),
                                                        page.getRenderDpi(),
                                                        page.getTextLayer().name(),
                                                        page.isBlank(),
                                                        page.getDuplicateOfPageId() != null))
                                .toList(),
                        classificationRows.stream().map(this::classification).toList(),
                        documentRows.stream()
                                .map(
                                        document ->
                                                new Document(
                                                        document.getId(),
                                                        document.getOrdinal(),
                                                        document.getDocumentTypeCode()))
                                .toList(),
                        membershipRows.stream()
                                .map(
                                        membership ->
                                                new Membership(
                                                        membership.getLogicalDocumentId(),
                                                        membership.getPageId(),
                                                        membership.getOrdinal()))
                                .toList(),
                        fieldRows.stream().map(field -> field(field, schemaById)).toList(),
                        evidenceRows.stream().map(this::evidence).toList(),
                        successfulStageRows.stream().map(this::stage).toList());
        EngineResultEnvelopeAssembler.validate(request, snapshot);
        return snapshot;
    }

    private Classification classification(ClassificationResult row) {
        return new Classification(
                row.getSubjectId(),
                row.getDocumentTypeCode(),
                row.getConfidence(),
                row.getMethod(),
                row.getRulePackVersion(),
                classificationEvidence(row.getMethod(), row.getEvidence()));
    }

    /**
     * Dispatches to the one evidence contract the producing method is allowed to have written.
     *
     * <p>An unrecognised method is refused rather than read as anchors: a method with no declared
     * contract has no shape this loader can honestly pin, and reading it under another method's
     * contract would publish evidence the digest claims was verified when it was not.
     */
    private ClassificationEvidence classificationEvidence(String method, String storedJson) {
        if (ClassificationResult.METHOD_RULE_ANCHOR.equals(method)) {
            return ruleAnchorEvidence(storedJson);
        }
        if (ClassificationResult.METHOD_LLM.equals(method)) {
            return llmEvidence(storedJson);
        }
        throw inconsistent("classification method declares no evidence contract");
    }

    private RuleAnchorEvidence ruleAnchorEvidence(String storedJson) {
        ObjectNode root = strictObject(storedJson);
        requireMembers(
                root,
                RULE_ANCHOR_EVIDENCE_KIND,
                CLASSIFICATION_EVIDENCE_MEMBERS,
                Set.of(CO_QUALIFYING_TYPES_MEMBER));

        List<ClassificationAnchor> anchors = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "anchors")) {
            anchors.add(classificationAnchor(requiredObject(node, "anchors entry")));
        }
        List<ClassificationScore> scores = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "scores")) {
            scores.add(classificationScore(requiredObject(node, "scores entry")));
        }
        return new RuleAnchorEvidence(anchors, scores, coQualifyingTypes(root));
    }

    /**
     * The model's evidence: provenance plus the span ids and character range its quote was proven
     * against. {@code deterministicRunnerUp} is the one optional member — the classifier omits it
     * when the row it superseded carried no scores at all.
     */
    private LlmEvidence llmEvidence(String storedJson) {
        ObjectNode root = strictObject(storedJson);
        requireMembers(
                root, LLM_EVIDENCE_KIND, LLM_EVIDENCE_MEMBERS, Set.of(RUNNER_UP_MEMBER));

        List<Long> spanIds = new ArrayList<>();
        for (JsonNode spanId : requiredArray(root, "matchedSpanIds")) {
            if (!spanId.isIntegralNumber()
                    || !spanId.canConvertToLong()
                    || spanId.longValue() <= 0) {
                throw inconsistent("matchedSpanIds entry is not a positive integer");
            }
            spanIds.add(spanId.longValue());
        }
        return new LlmEvidence(
                requiredText(root, "source", EVIDENCE_IDENTIFIER),
                requiredText(root, "model", MODEL_IDENTIFIER),
                requiredText(root, "promptVersion", EVIDENCE_IDENTIFIER),
                spanIds,
                classificationRange(requiredObject(root.get("offsets"), "offsets"), "offsets"),
                deterministicRunnerUp(root));
    }

    private static DeterministicRunnerUp deterministicRunnerUp(ObjectNode root) {
        if (!root.has(RUNNER_UP_MEMBER)) {
            return null;
        }
        ObjectNode node = requiredObject(root.get(RUNNER_UP_MEMBER), RUNNER_UP_MEMBER);
        requireMembers(node, RUNNER_UP_MEMBER, RUNNER_UP_MEMBERS, Set.of());
        return new DeterministicRunnerUp(
                requiredText(node, "type", DOCUMENT_TYPE_IDENTIFIER),
                requiredDecimal(node, "score"));
    }

    /**
     * Absent on every page that did not co-qualify. When present it is the classifier's exact
     * shape: a non-empty array of distinct document type identifiers, never document text.
     */
    private static List<String> coQualifyingTypes(ObjectNode root) {
        if (!root.has(CO_QUALIFYING_TYPES_MEMBER)) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (JsonNode type : requiredArray(root, CO_QUALIFYING_TYPES_MEMBER)) {
            if (!type.isTextual()
                    || !DOCUMENT_TYPE_IDENTIFIER.matcher(type.textValue()).matches()) {
                throw inconsistent(CO_QUALIFYING_TYPES_MEMBER + " entry is not a type identifier");
            }
            if (types.contains(type.textValue())) {
                throw inconsistent(CO_QUALIFYING_TYPES_MEMBER + " repeats a type");
            }
            types.add(type.textValue());
        }
        if (types.isEmpty()) {
            throw inconsistent(CO_QUALIFYING_TYPES_MEMBER + " is present but empty");
        }
        return types;
    }

    private static ClassificationAnchor classificationAnchor(ObjectNode node) {
        requireMembers(node, "classification anchor", CLASSIFICATION_ANCHOR_MEMBERS, Set.of());
        List<Long> spanIds = new ArrayList<>();
        for (JsonNode spanId : requiredArray(node, "spanIds")) {
            if (!spanId.isIntegralNumber() || !spanId.canConvertToLong() || spanId.longValue() <= 0) {
                throw inconsistent("anchor spanIds entry is not a positive integer");
            }
            spanIds.add(spanId.longValue());
        }
        List<ClassificationBox> boxes = new ArrayList<>();
        for (JsonNode box : requiredArray(node, "boxes")) {
            boxes.add(classificationBox(requiredObject(box, "boxes entry")));
        }
        ClassificationRange range =
                classificationRange(requiredObject(node.get("range"), "range"), "range");
        return new ClassificationAnchor(
                requiredText(node, "packType", DOCUMENT_TYPE_IDENTIFIER),
                requiredText(node, "packVersion", VERSION_IDENTIFIER),
                requiredText(node, "anchorId", EVIDENCE_IDENTIFIER),
                requiredDecimal(node, "weight"),
                spanIds,
                boxes,
                range);
    }

    /** One half-open character range contract, shared by anchor {@code range} and LLM offsets. */
    private static ClassificationRange classificationRange(ObjectNode node, String kind) {
        requireMembers(node, kind, CLASSIFICATION_RANGE_MEMBERS, Set.of());
        int start = requiredNonNegativeInt(node, "start");
        int end = requiredNonNegativeInt(node, "end");
        if (end < start) {
            throw inconsistent(kind + " ends before it starts");
        }
        return new ClassificationRange(start, end);
    }

    private static ClassificationBox classificationBox(ObjectNode node) {
        requireMembers(node, "classification anchor box", CLASSIFICATION_BOX_MEMBERS, Set.of());
        BigDecimal x = requiredDecimal(node, "x");
        BigDecimal y = requiredDecimal(node, "y");
        BigDecimal width = requiredDecimal(node, "width");
        BigDecimal height = requiredDecimal(node, "height");
        if (width.signum() < 0 || height.signum() < 0) {
            throw inconsistent("anchor box has a negative size");
        }
        return new ClassificationBox(x, y, width, height);
    }

    private static ClassificationScore classificationScore(ObjectNode node) {
        requireMembers(node, "classification score", CLASSIFICATION_SCORE_MEMBERS, Set.of());
        return new ClassificationScore(
                requiredText(node, "packType", DOCUMENT_TYPE_IDENTIFIER),
                requiredText(node, "packVersion", VERSION_IDENTIFIER),
                requiredDecimal(node, "score"),
                requiredDecimal(node, "minConfidence"),
                requiredDecimal(node, "targetScore"));
    }

    private Field field(ExtractedField row, Map<UUID, ExtractionSchema> schemaById) {
        ExtractionSchema schema = schemaById.get(row.getSchemaId());
        if (schema == null) {
            throw inconsistent("field references a schema that was not loaded");
        }
        return new Field(
                row.getId(),
                row.getLogicalDocumentId(),
                row.getSchemaId(),
                schema.getVersion(),
                row.getFieldName(),
                row.getGroupKey(),
                row.getDataType(),
                row.getDisplayedText(),
                row.getRawValue(),
                row.getNormalizedText(),
                row.getNormalizedNumber(),
                row.getNormalizedDate(),
                strictOptionalObject(row.getNormalizedJson()),
                row.getExtractionMethod(),
                row.getExtractorVersion(),
                row.getConfidence(),
                strictOptionalObject(row.getConfidenceComponents()),
                row.getValidationStatus(),
                row.isSensitive());
    }

    private Evidence evidence(FieldEvidence row) {
        return new Evidence(
                row.getId(),
                row.getExtractedFieldId(),
                row.getPageId(),
                row.getLayoutElementId(),
                row.getTextSpanId(),
                row.getX(),
                row.getY(),
                row.getWidth(),
                row.getHeight(),
                row.getRole(),
                row.getOrdinal());
    }

    private StageProvenance stage(ProcessingStage row) {
        return new StageProvenance(
                row.getStage().name(),
                row.getAttempt(),
                row.getOutputDigest(),
                row.getWorkerVersion(),
                strictOptionalObject(row.getParserVersions()));
    }

    private JsonNode strictOptionalObject(String storedJson) {
        return storedJson == null ? null : strictObject(storedJson);
    }

    /** All stored JSON crosses Task 3's duplicate/trailing-token boundary before tree use. */
    private ObjectNode strictObject(String storedJson) {
        try {
            byte[] canonicalBytes = canonical.parseAndWrite(storedJson.getBytes(UTF_8)).bytes();
            JsonNode node = JSON.readTree(canonicalBytes);
            if (!(node instanceof ObjectNode object)) {
                throw inconsistent("stored JSON is not an object");
            }
            return object;
        } catch (CanonicalizationException | IOException exception) {
            throw inconsistent("stored JSON failed strict canonical parsing");
        }
    }

    /**
     * Exact member sets, with named optional members. The reason names the ROW KIND and the member
     * names only — never a stored value — because it travels into {@code
     * processing_stage.error_detail}. The kind is what turns a bare "members differ" into a
     * diagnosis: the 2026-09-10 LLM failure listed six unexpected members with nothing saying which
     * of the two evidence contracts had been applied to them.
     */
    private static void requireMembers(
            ObjectNode node, String kind, Set<String> required, Set<String> optional) {
        Set<String> actual = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        Set<String> missing = new java.util.TreeSet<>(required);
        missing.removeAll(actual);
        Set<String> unexpected = new java.util.TreeSet<>(actual);
        unexpected.removeAll(required);
        unexpected.removeAll(optional);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw inconsistent(
                    kind
                            + " object members differ (missing "
                            + missing
                            + ", unexpected "
                            + unexpected
                            + ")");
        }
    }

    private static ObjectNode requiredObject(JsonNode node, String what) {
        if (!(node instanceof ObjectNode object)) {
            throw inconsistent(what + " is not an object");
        }
        return object;
    }

    private static ArrayNode requiredArray(ObjectNode node, String member) {
        JsonNode value = node.get(member);
        if (!(value instanceof ArrayNode array)) {
            throw inconsistent(member + " is not an array");
        }
        return array;
    }

    private static String requiredText(ObjectNode node, String member, Pattern pattern) {
        JsonNode value = node.get(member);
        if (value == null || !value.isTextual() || !pattern.matcher(value.textValue()).matches()) {
            throw inconsistent(member + " is not a valid identifier");
        }
        return value.textValue();
    }

    private static BigDecimal requiredDecimal(ObjectNode node, String member) {
        JsonNode value = node.get(member);
        if (value == null || !value.isNumber()) {
            throw inconsistent(member + " is not a number");
        }
        return value.decimalValue();
    }

    private static int requiredNonNegativeInt(ObjectNode node, String member) {
        JsonNode value = node.get(member);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 0) {
            throw inconsistent(member + " is not a non-negative integer");
        }
        return value.intValue();
    }

    private static Map<UUID, ExtractionSchema> exactSchemaMap(
            Set<UUID> requestedIds, List<ExtractionSchema> rows) {
        Map<UUID, ExtractionSchema> byId = new HashMap<>();
        for (ExtractionSchema row : rows) {
            if (row.getId() == null || byId.put(row.getId(), row) != null) {
                throw inconsistent("schema rows carry a null or duplicate id");
            }
        }
        if (!byId.keySet().equals(requestedIds)) {
            throw inconsistent("visible schemas do not match the schemas fields reference");
        }
        return Map.copyOf(byId);
    }

    private static void validateLoadedRows(
            UUID orgId,
            UUID packageId,
            List<SourceFile> sourceRows,
            List<Page> pageRows,
            List<ClassificationResult> classificationRows,
            List<LogicalDocument> documentRows,
            List<LogicalDocumentPage> membershipRows,
            List<ExtractedField> fieldRows,
            List<FieldEvidence> evidenceRows,
            List<ProcessingStage> stageRows,
            Map<UUID, ExtractionSchema> schemaById) {
        requireTenant(orgId, sourceRows, SourceFile::getOrgId);
        requireTenant(orgId, pageRows, Page::getOrgId);
        requireTenant(orgId, classificationRows, ClassificationResult::getOrgId);
        requireTenant(orgId, documentRows, LogicalDocument::getOrgId);
        requireTenant(orgId, membershipRows, LogicalDocumentPage::getOrgId);
        requireTenant(orgId, fieldRows, ExtractedField::getOrgId);
        requireTenant(orgId, evidenceRows, FieldEvidence::getOrgId);
        requireTenant(orgId, stageRows, ProcessingStage::getOrgId);

        if (sourceRows.stream()
                        .anyMatch(
                                source ->
                                        !packageId.equals(source.getPackageId())
                                                || source.getDeletedAt() != null)
                || pageRows.stream().anyMatch(page -> !packageId.equals(page.getPackageId()))
                || documentRows.stream()
                        .anyMatch(document -> !packageId.equals(document.getPackageId()))) {
            throw inconsistent("a source, page or document row belongs to another package");
        }

        Map<UUID, LogicalDocument> documentsById =
                documentRows.stream()
                        .collect(Collectors.toMap(LogicalDocument::getId, Function.identity()));
        for (ExtractedField field : fieldRows) {
            LogicalDocument document = documentsById.get(field.getLogicalDocumentId());
            ExtractionSchema schema = schemaById.get(field.getSchemaId());
            if (document == null
                    || schema == null
                    || !(schema.getOrgId() == null || orgId.equals(schema.getOrgId()))
                    || !document.getDocumentTypeCode().equals(schema.getDocumentTypeCode())) {
                throw inconsistent(
                        "a field's document, schema visibility or schema type code disagree");
            }
        }
    }

    private static <T> void requireTenant(
            UUID orgId, Collection<T> rows, Function<T, UUID> tenant) {
        if (rows.stream().anyMatch(row -> !orgId.equals(tenant.apply(row)))) {
            throw inconsistent("a loaded row belongs to another tenant");
        }
    }

    /**
     * Every refusal names its invariant. The reason is structural — member names, row kinds —
     * and never carries a stored value, so the finalizer can publish it as failure detail.
     */
    static MachineSnapshotInconsistentException inconsistent(String reason) {
        return new MachineSnapshotInconsistentException(reason);
    }
}
