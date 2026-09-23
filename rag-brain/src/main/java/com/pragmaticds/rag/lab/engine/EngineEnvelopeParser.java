package com.pragmaticds.rag.lab.engine;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ClassificationAnchor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.CharacterOffsets;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ClassificationEvidence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.DeterministicRunnerUp;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ClassificationRange;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ClassificationScore;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ConfidenceComponents;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LlmEvidence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.RuleAnchorEvidence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.NormalizedValue;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.PageClassification;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SourceFile;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;
import com.pragmaticds.rag.lab.engine.LabContractException.Code;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Strict consumer-side parser for Document Engine canonical result envelopes.
 *
 * <p>Rejects anything the pinned contract does not define: duplicate keys, trailing tokens,
 * non-canonical numbers or key order, unknown/missing/forbidden members, unsupported versions,
 * duplicate semantic identities, order violations, page-membership violations, and field
 * status contradictions. Retains the received bytes and their SHA-256 as the only identity —
 * this parser never reserializes to establish identity.
 */
public final class EngineEnvelopeParser {

    /** The only envelope version this consumer understands. */
    public static final String SUPPORTED_ENVELOPE_VERSION = "1.0.0";

    /** The only canonicalization version this consumer understands. */
    public static final String SUPPORTED_CANONICALIZATION_VERSION = "DOCENGINE-C14N-1";

    private static final JsonFactory STRICT_FACTORY =
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper STRICT_MAPPER =
            new ObjectMapper(STRICT_FACTORY)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DIGEST_SHAPE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DOCUMENT_TYPE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern CANONICAL_NUMBER =
            Pattern.compile("0|-?[1-9][0-9]*(\\.[0-9]*[1-9])?|-?0\\.[0-9]*[1-9]");

    private static final Set<String> TEXT_LAYERS = Set.of("NATIVE", "SCANNED", "MIXED", "NONE");
    private static final Set<String> FIELD_STATUSES = Set.of("FOUND", "MISSING");
    private static final Set<String> VALIDATION_STATUSES =
            Set.of(
                    "NOT_VALIDATED",
                    "VALID",
                    "WARNING",
                    "ERROR",
                    "UNABLE_TO_VALIDATE",
                    "MANUAL_REVIEW_REQUIRED");
    private static final Set<String> REUSE_ELIGIBILITIES = Set.of("PARSE_ONCE_CURRENT_PACKAGE");
    private static final Map<String, Integer> EVIDENCE_ROLE_ORDER =
            Map.of("VALUE", 0, "LABEL", 1, "CONTEXT", 2);
    private static final Map<String, Integer> STAGE_ORDER =
            Map.ofEntries(
                    Map.entry("VALIDATING", 0),
                    Map.entry("NORMALIZING", 1),
                    Map.entry("RENDERING", 2),
                    Map.entry("TEXT_EXTRACTION", 3),
                    Map.entry("OCR_PROCESSING", 4),
                    Map.entry("PARSING", 5),
                    Map.entry("CLASSIFYING", 6),
                    Map.entry("SPLITTING", 7),
                    Map.entry("EXTRACTING", 8));

    /**
     * Members that must never appear at any depth: raw page text, storage locators,
     * filenames, review state, and corrections are not machine-envelope content.
     * Compared case-insensitively so a casing variant cannot smuggle one through.
     *
     * <p>Public and immutable so the prompt boundary can re-check the same vocabulary over a
     * field's free-form {@code normalized.json} arm rather than keeping a second copy of a
     * security-relevant list that could drift from this one.
     */
    public static final Set<String> FORBIDDEN_MEMBERS =
            Set.of(
                    "rawtext", "raw_text", "pagetext", "page_text", "fulltext", "full_text",
                    "ocrtext", "ocr_text", "textcontent", "text_content", "documenttext",
                    "storagekey", "storage_key", "s3key", "s3bucket", "objectkey", "object_key",
                    "storagepath", "storage_path", "storageuri", "storage_uri", "presignedurl",
                    "downloadurl", "url", "uri",
                    "filename", "file_name", "originalfilename", "original_filename",
                    "sourcefilename", "source_file_name", "uploadfilename",
                    "review", "reviewer", "reviewstatus", "review_status", "reviewedby",
                    "reviewed_by", "reviewedat", "reviewnote",
                    "correction", "corrections", "correctedvalue", "corrected_value",
                    "correctedby", "corrected_by", "correctedat", "correctionnote");

    private static final Set<String> TOP_LEVEL_MEMBERS =
            Set.of(
                    "canonicalizationVersion",
                    "documents",
                    "envelopeVersion",
                    "generation",
                    "package",
                    "pages",
                    "provenance",
                    "sources",
                    "unassignedPageIds");
    private static final Set<String> PACKAGE_MEMBERS = Set.of("id");
    private static final Set<String> GENERATION_MEMBERS =
            Set.of(
                    "packageRevision",
                    "parseGeneration",
                    "processingJobId",
                    "reuseEligibility",
                    "sourceSetSha256");
    private static final Set<String> SOURCE_MEMBERS =
            Set.of("contentSha256", "contentType", "id", "ordinal", "sizeBytes");
    private static final Set<String> PAGE_MEMBERS =
            Set.of(
                    "blank",
                    "classification",
                    "duplicate",
                    "heightPt",
                    "id",
                    "packagePageIndex",
                    "renderDpi",
                    "rotation",
                    "sourceFileId",
                    "sourcePageIndex",
                    "textLayer",
                    "widthPt");
    private static final Set<String> CLASSIFICATION_MEMBERS =
            Set.of("confidence", "documentTypeCode", "evidence", "method", "rulePackVersion");
    /** Engine classification methods that declare an evidence contract (mirrors the loader). */
    private static final String METHOD_RULE_ANCHOR = "RULE_ANCHOR";
    private static final String METHOD_LLM = "LLM";
    private static final Set<String> RULE_ANCHOR_EVIDENCE_MEMBERS = Set.of("anchors", "scores");
    private static final Set<String> RULE_ANCHOR_EVIDENCE_OPTIONAL = Set.of("coQualifyingTypes");
    private static final Set<String> LLM_EVIDENCE_MEMBERS =
            Set.of("matchedSpanIds", "model", "offsets", "promptVersion", "source");
    private static final Set<String> LLM_EVIDENCE_OPTIONAL = Set.of("deterministicRunnerUp");
    private static final Set<String> RUNNER_UP_MEMBERS = Set.of("score", "type");
    private static final Set<String> OFFSETS_MEMBERS = Set.of("end", "start");
    /** Mirrors the engine assembler's EVIDENCE_IDENTIFIER and MODEL_IDENTIFIER. */
    private static final Pattern EVIDENCE_IDENTIFIER_SHAPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern MODEL_IDENTIFIER_SHAPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+:/-]{0,127}");
    private static final Set<String> ANCHOR_MEMBERS =
            Set.of("anchorId", "boxes", "packType", "packVersion", "range", "spanIds", "weight");
    private static final Set<String> RANGE_MEMBERS = Set.of("end", "start");
    private static final Set<String> SCORE_MEMBERS =
            Set.of("minConfidence", "packType", "packVersion", "score", "targetScore");
    private static final Set<String> BOX_MEMBERS = Set.of("height", "width", "x", "y");
    private static final Set<String> DOCUMENT_MEMBERS =
            Set.of("documentTypeCode", "fields", "id", "ordinal", "pageIds");
    private static final Set<String> FIELD_MEMBERS =
            Set.of(
                    "confidence",
                    "confidenceComponents",
                    "dataType",
                    "displayedText",
                    "evidence",
                    "extractorVersion",
                    "groupKey",
                    "method",
                    "name",
                    "normalized",
                    "rawValue",
                    "schema",
                    "sensitive",
                    "status",
                    "validationStatus");
    private static final Set<String> NORMALIZED_MEMBERS = Set.of("date", "json", "number", "text");
    private static final Set<String> SCHEMA_MEMBERS = Set.of("id", "version");
    private static final Set<String> COMPONENT_MEMBERS =
            Set.of("anchorStrength", "normalizerCertainty", "spanConfidence");
    private static final Set<String> EVIDENCE_MEMBERS =
            Set.of("box", "layoutElementId", "ordinal", "pageId", "role", "textSpanId");
    private static final Set<String> PROVENANCE_MEMBERS =
            Set.of(
                    "applicationRelease",
                    "extractionEngineRelease",
                    "stages",
                    "workerContractRelease");
    private static final Set<String> RELEASE_MEMBERS = Set.of("availability");
    private static final Set<String> STAGE_MEMBERS =
            Set.of("attempt", "outputDigest", "parserVersions", "stage", "workerVersion");

    /**
     * Parses and validates one received envelope.
     *
     * @throws LabContractException with a stable payload-free code on any contract violation
     */
    public EngineResultEnvelope parse(byte[] receivedBytes) {
        Objects.requireNonNull(receivedBytes, "receivedBytes");
        byte[] received = receivedBytes.clone();
        if (received.length == 0) {
            throw new LabContractException(Code.ENVELOPE_EMPTY);
        }

        streamValidate(received);

        JsonNode root;
        try {
            root = STRICT_MAPPER.readTree(received);
        } catch (IOException exception) {
            throw new LabContractException(Code.ENVELOPE_NOT_STRICT_JSON);
        }

        String envelopeVersion = requireString(member(root, "envelopeVersion"));
        if (!SUPPORTED_ENVELOPE_VERSION.equals(envelopeVersion)) {
            throw new LabContractException(Code.ENVELOPE_VERSION_UNSUPPORTED);
        }
        String canonicalizationVersion = requireString(member(root, "canonicalizationVersion"));
        if (!SUPPORTED_CANONICALIZATION_VERSION.equals(canonicalizationVersion)) {
            throw new LabContractException(Code.CANONICALIZATION_VERSION_UNSUPPORTED);
        }
        requireExactMembers(root, TOP_LEVEL_MEMBERS);

        JsonNode packageNode = requireObjectNode(member(root, "package"));
        requireExactMembers(packageNode, PACKAGE_MEMBERS);
        UUID packageId = requireUuid(member(packageNode, "id"));

        Generation generation = generation(member(root, "generation"));
        List<SourceFile> sources = sources(member(root, "sources"));
        List<EnginePage> pages = pages(member(root, "pages"));
        List<LogicalDocument> documents = documents(member(root, "documents"));
        List<UUID> unassignedPageIds = uuidList(member(root, "unassignedPageIds"));
        Provenance provenance = provenance(member(root, "provenance"));

        crossValidate(sources, pages, documents, unassignedPageIds, provenance);

        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of(received),
                envelopeVersion,
                canonicalizationVersion,
                packageId,
                generation,
                sources,
                pages,
                documents,
                unassignedPageIds,
                provenance);
    }

    // ------------------------------------------------------------------ streaming boundary

    /**
     * One strict streaming pass over the exact received bytes: duplicate keys, trailing
     * tokens, non-object roots, forbidden members at any depth, canonical numeric lexemes,
     * and canonical code-point key order.
     */
    private static void streamValidate(byte[] received) {
        try (JsonParser stream = STRICT_FACTORY.createParser(received)) {
            JsonToken first = stream.nextToken();
            if (first == null) {
                throw new LabContractException(Code.ENVELOPE_EMPTY);
            }
            if (first != JsonToken.START_OBJECT) {
                throw new LabContractException(Code.ENVELOPE_ROOT_NOT_OBJECT);
            }

            Deque<String[]> contexts = new ArrayDeque<>();
            contexts.push(new String[1]);
            while (!contexts.isEmpty()) {
                JsonToken token = stream.nextToken();
                if (token == null) {
                    throw new LabContractException(Code.ENVELOPE_NOT_STRICT_JSON);
                }
                switch (token) {
                    case FIELD_NAME -> {
                        String name = stream.currentName();
                        if (FORBIDDEN_MEMBERS.contains(name.toLowerCase(Locale.ROOT))) {
                            throw new LabContractException(Code.ENVELOPE_FORBIDDEN_MEMBER);
                        }
                        String[] context = contexts.peek();
                        if (context.length == 1) {
                            if (context[0] != null
                                    && codePointCompare(context[0], name) >= 0) {
                                throw new LabContractException(
                                        Code.ENVELOPE_KEY_ORDER_NOT_CANONICAL);
                            }
                            context[0] = name;
                        }
                    }
                    case START_OBJECT -> contexts.push(new String[1]);
                    case START_ARRAY -> contexts.push(new String[0]);
                    case END_OBJECT, END_ARRAY -> contexts.pop();
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                        if (!CANONICAL_NUMBER.matcher(stream.getText()).matches()) {
                            throw new LabContractException(Code.ENVELOPE_NUMBER_NOT_CANONICAL);
                        }
                    }
                    default -> {
                        // scalar values need no structural bookkeeping
                    }
                }
            }
            if (stream.nextToken() != null) {
                throw new LabContractException(Code.ENVELOPE_NOT_STRICT_JSON);
            }
        } catch (IOException exception) {
            throw new LabContractException(Code.ENVELOPE_NOT_STRICT_JSON);
        }
    }

    // ------------------------------------------------------------------ section builders

    private static Generation generation(JsonNode node) {
        requireExactMembers(requireObjectNode(node), GENERATION_MEMBERS);
        int parseGeneration = requireInt(member(node, "parseGeneration"));
        int packageRevision = requireInt(member(node, "packageRevision"));
        if (parseGeneration < 1 || packageRevision < 1) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        String reuseEligibility = requireString(member(node, "reuseEligibility"));
        if (!REUSE_ELIGIBILITIES.contains(reuseEligibility)) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return new Generation(
                requireUuid(member(node, "processingJobId")),
                parseGeneration,
                packageRevision,
                requireDigest(member(node, "sourceSetSha256")),
                reuseEligibility);
    }

    private static List<SourceFile> sources(JsonNode node) {
        List<SourceFile> sources = new ArrayList<>();
        for (JsonNode item : requireArrayNode(node)) {
            requireExactMembers(requireObjectNode(item), SOURCE_MEMBERS);
            int ordinal = requireInt(member(item, "ordinal"));
            long sizeBytes = requireLong(member(item, "sizeBytes"));
            if (ordinal < 0 || sizeBytes < 0) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            sources.add(
                    new SourceFile(
                            requireUuid(member(item, "id")),
                            ordinal,
                            requireDigest(member(item, "contentSha256")),
                            sizeBytes,
                            requireNonEmptyString(member(item, "contentType"))));
        }
        if (sources.isEmpty()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return sources;
    }

    private static List<EnginePage> pages(JsonNode node) {
        List<EnginePage> pages = new ArrayList<>();
        for (JsonNode item : requireArrayNode(node)) {
            requireExactMembers(requireObjectNode(item), PAGE_MEMBERS);
            int sourcePageIndex = requireInt(member(item, "sourcePageIndex"));
            int packagePageIndex = requireInt(member(item, "packagePageIndex"));
            if (sourcePageIndex < 0 || packagePageIndex < 0) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            BigDecimal widthPt = requireDecimal(member(item, "widthPt"));
            BigDecimal heightPt = requireDecimal(member(item, "heightPt"));
            if (widthPt.signum() <= 0 || heightPt.signum() <= 0) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            String textLayer = requireString(member(item, "textLayer"));
            if (!TEXT_LAYERS.contains(textLayer)) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            JsonNode renderDpiNode = member(item, "renderDpi");
            Integer renderDpi = renderDpiNode.isNull() ? null : requireInt(renderDpiNode);
            if (renderDpi != null && renderDpi < 1) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            JsonNode classificationNode = member(item, "classification");
            pages.add(
                    new EnginePage(
                            requireUuid(member(item, "id")),
                            requireUuid(member(item, "sourceFileId")),
                            sourcePageIndex,
                            packagePageIndex,
                            widthPt,
                            heightPt,
                            requireInt(member(item, "rotation")),
                            renderDpi,
                            textLayer,
                            requireBoolean(member(item, "blank")),
                            requireBoolean(member(item, "duplicate")),
                            classificationNode.isNull()
                                    ? null
                                    : classification(classificationNode)));
        }
        if (pages.isEmpty()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return pages;
    }

    private static PageClassification classification(JsonNode node) {
        requireExactMembers(requireObjectNode(node), CLASSIFICATION_MEMBERS);
        JsonNode rulePackNode = member(node, "rulePackVersion");
        String method = requireNonEmptyString(member(node, "method"));
        return new PageClassification(
                requirePattern(member(node, "documentTypeCode"), DOCUMENT_TYPE_SHAPE),
                requireUnitInterval(member(node, "confidence")),
                method,
                rulePackNode.isNull() ? null : requireNonEmptyString(rulePackNode),
                classificationEvidence(method, member(node, "evidence")));
    }

    /**
     * Dispatches on the declared {@code method}, exactly as the engine's loader does: each method
     * has one evidence contract, and a shape that disagrees with its method is refused rather than
     * sniffed. A method with no declared contract (the engine column also allows ML and HUMAN) is
     * refused because there is no shape this parser can honestly pin.
     */
    private static ClassificationEvidence classificationEvidence(String method, JsonNode node) {
        return switch (method) {
            case METHOD_RULE_ANCHOR -> ruleAnchorEvidence(node);
            case METHOD_LLM -> llmEvidence(node);
            default -> throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        };
    }

    private static LlmEvidence llmEvidence(JsonNode node) {
        requireMembers(requireObjectNode(node), LLM_EVIDENCE_MEMBERS, LLM_EVIDENCE_OPTIONAL);
        DeterministicRunnerUp runnerUp = null;
        JsonNode runnerUpNode = node.get("deterministicRunnerUp");
        if (runnerUpNode != null) {
            requireExactMembers(requireObjectNode(runnerUpNode), RUNNER_UP_MEMBERS);
            runnerUp =
                    new DeterministicRunnerUp(
                            requirePattern(member(runnerUpNode, "type"), DOCUMENT_TYPE_SHAPE),
                            requireDecimal(member(runnerUpNode, "score")));
        }
        List<Long> matchedSpanIds = new ArrayList<>();
        for (JsonNode spanId : requireArrayNode(member(node, "matchedSpanIds"))) {
            long value = requireLong(spanId);
            if (value < 1) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            matchedSpanIds.add(value);
        }
        JsonNode offsetsNode = requireObjectNode(member(node, "offsets"));
        requireExactMembers(offsetsNode, OFFSETS_MEMBERS);
        int start = requireInt(member(offsetsNode, "start"));
        int end = requireInt(member(offsetsNode, "end"));
        if (start < 0 || end < start) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return new LlmEvidence(
                runnerUp,
                matchedSpanIds,
                requirePattern(member(node, "model"), MODEL_IDENTIFIER_SHAPE),
                new CharacterOffsets(start, end),
                requirePattern(member(node, "promptVersion"), EVIDENCE_IDENTIFIER_SHAPE),
                requirePattern(member(node, "source"), EVIDENCE_IDENTIFIER_SHAPE));
    }

    private static RuleAnchorEvidence ruleAnchorEvidence(JsonNode node) {
        requireMembers(
                requireObjectNode(node), RULE_ANCHOR_EVIDENCE_MEMBERS, RULE_ANCHOR_EVIDENCE_OPTIONAL);
        List<ClassificationAnchor> anchors = new ArrayList<>();
        for (JsonNode item : requireArrayNode(member(node, "anchors"))) {
            requireExactMembers(requireObjectNode(item), ANCHOR_MEMBERS);
            List<Long> spanIds = new ArrayList<>();
            for (JsonNode spanId : requireArrayNode(member(item, "spanIds"))) {
                long value = requireLong(spanId);
                if (value < 1) {
                    throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
                }
                spanIds.add(value);
            }
            List<Box> boxes = new ArrayList<>();
            for (JsonNode boxNode : requireArrayNode(member(item, "boxes"))) {
                Box box = box(boxNode);
                if (box.width().signum() < 0 || box.height().signum() < 0) {
                    throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
                }
                boxes.add(box);
            }
            JsonNode rangeNode = requireObjectNode(member(item, "range"));
            requireExactMembers(rangeNode, RANGE_MEMBERS);
            int start = requireInt(member(rangeNode, "start"));
            int end = requireInt(member(rangeNode, "end"));
            if (start < 0 || end < start) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            anchors.add(
                    new ClassificationAnchor(
                            requirePattern(member(item, "packType"), DOCUMENT_TYPE_SHAPE),
                            requireNonEmptyString(member(item, "packVersion")),
                            requireNonEmptyString(member(item, "anchorId")),
                            requireDecimal(member(item, "weight")),
                            spanIds,
                            boxes,
                            new ClassificationRange(start, end)));
        }
        List<ClassificationScore> scores = new ArrayList<>();
        for (JsonNode item : requireArrayNode(member(node, "scores"))) {
            requireExactMembers(requireObjectNode(item), SCORE_MEMBERS);
            scores.add(
                    new ClassificationScore(
                            requirePattern(member(item, "packType"), DOCUMENT_TYPE_SHAPE),
                            requireNonEmptyString(member(item, "packVersion")),
                            requireDecimal(member(item, "score")),
                            requireUnitInterval(member(item, "minConfidence")),
                            requireDecimal(member(item, "targetScore"))));
        }
        return new RuleAnchorEvidence(anchors, scores, coQualifyingTypes(node));
    }

    /**
     * Present only when the classifier wrote it, and the engine never writes an empty list; so an
     * absent member reads as empty and an empty array is a contract violation. Entries are distinct
     * document type codes.
     */
    private static List<String> coQualifyingTypes(JsonNode evidence) {
        JsonNode node = evidence.get("coQualifyingTypes");
        if (node == null) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (JsonNode item : requireArrayNode(node)) {
            String type = requirePattern(item, DOCUMENT_TYPE_SHAPE);
            if (types.contains(type)) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            types.add(type);
        }
        if (types.isEmpty()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return types;
    }

    private static List<LogicalDocument> documents(JsonNode node) {
        List<LogicalDocument> documents = new ArrayList<>();
        for (JsonNode item : requireArrayNode(node)) {
            requireExactMembers(requireObjectNode(item), DOCUMENT_MEMBERS);
            int ordinal = requireInt(member(item, "ordinal"));
            if (ordinal < 0) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            List<UUID> pageIds = uuidList(member(item, "pageIds"));
            if (pageIds.isEmpty()) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            List<FieldOccurrence> fields = new ArrayList<>();
            for (JsonNode fieldNode : requireArrayNode(member(item, "fields"))) {
                fields.add(field(fieldNode));
            }
            documents.add(
                    new LogicalDocument(
                            requireUuid(member(item, "id")),
                            requirePattern(member(item, "documentTypeCode"), DOCUMENT_TYPE_SHAPE),
                            ordinal,
                            pageIds,
                            fields));
        }
        return documents;
    }

    private static FieldOccurrence field(JsonNode node) {
        requireExactMembers(requireObjectNode(node), FIELD_MEMBERS);

        String statusText = requireString(member(node, "status"));
        if (!FIELD_STATUSES.contains(statusText)) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        FieldStatus status = FieldStatus.valueOf(statusText);
        String validationStatus = requireString(member(node, "validationStatus"));
        if (!VALIDATION_STATUSES.contains(validationStatus)) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }

        JsonNode groupKeyNode = member(node, "groupKey");
        String groupKey = groupKeyNode.isNull() ? null : requireNonEmptyString(groupKeyNode);
        JsonNode displayedTextNode = member(node, "displayedText");
        String displayedText =
                displayedTextNode.isNull() ? null : requireString(displayedTextNode);
        JsonNode rawValueNode = member(node, "rawValue");
        String rawValue = rawValueNode.isNull() ? null : requireString(rawValueNode);

        JsonNode normalizedNode = member(node, "normalized");
        NormalizedValue normalized =
                normalizedNode.isNull() ? null : normalizedValue(normalizedNode);

        JsonNode schemaNode = requireObjectNode(member(node, "schema"));
        requireExactMembers(schemaNode, SCHEMA_MEMBERS);
        SchemaRef schema =
                new SchemaRef(
                        requireUuid(member(schemaNode, "id")),
                        requireNonEmptyString(member(schemaNode, "version")));

        JsonNode componentsNode = member(node, "confidenceComponents");
        ConfidenceComponents components =
                componentsNode.isNull() ? null : confidenceComponents(componentsNode);

        List<EvidenceSpan> evidence = new ArrayList<>();
        for (JsonNode evidenceNode : requireArrayNode(member(node, "evidence"))) {
            evidence.add(evidenceSpan(evidenceNode));
        }

        FieldOccurrence occurrence =
                new FieldOccurrence(
                        requireNonEmptyString(member(node, "name")),
                        groupKey,
                        status,
                        requireNonEmptyString(member(node, "dataType")),
                        displayedText,
                        rawValue,
                        normalized,
                        schema,
                        requireNonEmptyString(member(node, "method")),
                        requireNonEmptyString(member(node, "extractorVersion")),
                        requireUnitInterval(member(node, "confidence")),
                        components,
                        validationStatus,
                        requireBoolean(member(node, "sensitive")),
                        evidence);
        requireInternallyConsistent(occurrence);
        return occurrence;
    }

    /** MISSING and method NONE imply each other and forbid every value arm. */
    private static void requireInternallyConsistent(FieldOccurrence field) {
        boolean missing = field.status() == FieldStatus.MISSING;
        if (missing != "NONE".equals(field.method())) {
            throw new LabContractException(Code.FIELD_CONTRADICTION);
        }
        if (missing) {
            if (field.displayedText() != null
                    || field.rawValue() != null
                    || field.normalized() != null
                    || field.confidenceComponents() != null
                    || !field.evidence().isEmpty()
                    || field.confidence().signum() != 0) {
                throw new LabContractException(Code.FIELD_CONTRADICTION);
            }
            return;
        }
        NormalizedValue normalized = field.normalized();
        if (normalized == null || field.confidenceComponents() == null) {
            throw new LabContractException(Code.FIELD_CONTRADICTION);
        }
        int populatedArms = 0;
        populatedArms += normalized.text() == null ? 0 : 1;
        populatedArms += normalized.number() == null ? 0 : 1;
        populatedArms += normalized.date() == null ? 0 : 1;
        populatedArms += normalized.json() == null ? 0 : 1;
        if (populatedArms != 1) {
            throw new LabContractException(Code.FIELD_CONTRADICTION);
        }
    }

    private static NormalizedValue normalizedValue(JsonNode node) {
        requireExactMembers(requireObjectNode(node), NORMALIZED_MEMBERS);
        JsonNode textNode = member(node, "text");
        JsonNode numberNode = member(node, "number");
        JsonNode dateNode = member(node, "date");
        JsonNode jsonNode = member(node, "json");

        LocalDate date = null;
        if (!dateNode.isNull()) {
            try {
                date = LocalDate.parse(requireString(dateNode));
            } catch (DateTimeParseException exception) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
        }
        Object json = null;
        if (!jsonNode.isNull()) {
            if (!jsonNode.isObject()) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            json = immutableTree(jsonNode);
        }
        return new NormalizedValue(
                textNode.isNull() ? null : requireString(textNode),
                numberNode.isNull() ? null : requireDecimal(numberNode),
                date,
                json);
    }

    private static ConfidenceComponents confidenceComponents(JsonNode node) {
        requireExactMembers(requireObjectNode(node), COMPONENT_MEMBERS);
        return new ConfidenceComponents(
                requireUnitInterval(member(node, "spanConfidence")),
                requireUnitInterval(member(node, "anchorStrength")),
                requireUnitInterval(member(node, "normalizerCertainty")));
    }

    private static EvidenceSpan evidenceSpan(JsonNode node) {
        requireExactMembers(requireObjectNode(node), EVIDENCE_MEMBERS);
        String role = requireString(member(node, "role"));
        if (!EVIDENCE_ROLE_ORDER.containsKey(role)) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        int ordinal = requireInt(member(node, "ordinal"));
        if (ordinal < 0) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        JsonNode layoutNode = member(node, "layoutElementId");
        JsonNode spanIdNode = member(node, "textSpanId");
        Long textSpanId = spanIdNode.isNull() ? null : requireLong(spanIdNode);
        if (textSpanId != null && textSpanId < 1) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return new EvidenceSpan(
                requireUuid(member(node, "pageId")),
                layoutNode.isNull() ? null : requireUuid(layoutNode),
                textSpanId,
                role,
                ordinal,
                box(member(node, "box")));
    }

    private static Box box(JsonNode node) {
        requireExactMembers(requireObjectNode(node), BOX_MEMBERS);
        return new Box(
                requireDecimal(member(node, "x")),
                requireDecimal(member(node, "y")),
                requireDecimal(member(node, "width")),
                requireDecimal(member(node, "height")));
    }

    private static Provenance provenance(JsonNode node) {
        requireExactMembers(requireObjectNode(node), PROVENANCE_MEMBERS);
        List<StageAttempt> stages = new ArrayList<>();
        for (JsonNode item : requireArrayNode(member(node, "stages"))) {
            requireExactMembers(requireObjectNode(item), STAGE_MEMBERS);
            String stage = requireString(member(item, "stage"));
            if (!STAGE_ORDER.containsKey(stage)) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            int attempt = requireInt(member(item, "attempt"));
            if (attempt < 1) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            JsonNode digestNode = member(item, "outputDigest");
            JsonNode workerNode = member(item, "workerVersion");
            JsonNode parserVersionsNode = member(item, "parserVersions");
            Object parserVersions = null;
            if (!parserVersionsNode.isNull()) {
                if (!parserVersionsNode.isObject()) {
                    throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
                }
                parserVersions = immutableTree(parserVersionsNode);
            }
            stages.add(
                    new StageAttempt(
                            stage,
                            attempt,
                            digestNode.isNull() ? null : requireDigest(digestNode),
                            workerNode.isNull() ? null : requireNonEmptyString(workerNode),
                            parserVersions));
        }
        return new Provenance(
                releaseAvailability(member(node, "applicationRelease")),
                releaseAvailability(member(node, "extractionEngineRelease")),
                releaseAvailability(member(node, "workerContractRelease")),
                stages);
    }

    private static ReleaseAvailability releaseAvailability(JsonNode node) {
        requireExactMembers(requireObjectNode(node), RELEASE_MEMBERS);
        return new ReleaseAvailability(requireNonEmptyString(member(node, "availability")));
    }

    // ------------------------------------------------------------------ cross validation

    private static void crossValidate(
            List<SourceFile> sources,
            List<EnginePage> pages,
            List<LogicalDocument> documents,
            List<UUID> unassignedPageIds,
            Provenance provenance) {
        // 1. Semantic identity uniqueness.
        unique(sources, SourceFile::id);
        unique(sources, SourceFile::ordinal);
        unique(pages, EnginePage::id);
        unique(pages, EnginePage::packagePageIndex);
        unique(pages, page -> page.sourceFileId() + ":" + page.sourcePageIndex());
        unique(documents, LogicalDocument::id);
        unique(documents, LogicalDocument::ordinal);
        for (LogicalDocument document : documents) {
            unique(
                    document.fields(),
                    field ->
                            new java.util.AbstractMap.SimpleImmutableEntry<>(
                                    field.name(), field.groupKey()));
            for (FieldOccurrence field : document.fields()) {
                unique(field.evidence(), span -> span.role() + ":" + span.ordinal());
            }
        }
        unique(provenance.stages(), StageAttempt::stage);

        // 2. Pinned semantic ordering.
        strictlyAscending(sources, Comparator.comparingInt(SourceFile::ordinal));
        strictlyAscending(pages, Comparator.comparingInt(EnginePage::packagePageIndex));
        strictlyAscending(documents, Comparator.comparingInt(LogicalDocument::ordinal));
        Comparator<FieldOccurrence> fieldOrder =
                Comparator.comparing(FieldOccurrence::name, EngineEnvelopeParser::codePointCompare)
                        .thenComparing(
                                FieldOccurrence::groupKey,
                                Comparator.nullsFirst(EngineEnvelopeParser::codePointCompare));
        Comparator<EvidenceSpan> evidenceOrder =
                Comparator.<EvidenceSpan>comparingInt(
                                span -> EVIDENCE_ROLE_ORDER.get(span.role()))
                        .thenComparingInt(EvidenceSpan::ordinal);
        for (LogicalDocument document : documents) {
            strictlyAscending(document.fields(), fieldOrder);
            for (FieldOccurrence field : document.fields()) {
                strictlyAscending(field.evidence(), evidenceOrder);
            }
        }
        strictlyAscending(
                provenance.stages(),
                Comparator.<StageAttempt>comparingInt(stage -> STAGE_ORDER.get(stage.stage()))
                        .thenComparingInt(StageAttempt::attempt));

        // 3. Page membership.
        Set<UUID> sourceIds = new HashSet<>();
        sources.forEach(source -> sourceIds.add(source.id()));
        Map<UUID, EnginePage> pagesById = new HashMap<>();
        for (EnginePage page : pages) {
            if (!sourceIds.contains(page.sourceFileId())) {
                throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
            }
            pagesById.put(page.id(), page);
        }
        Set<UUID> claimed = new HashSet<>();
        for (LogicalDocument document : documents) {
            for (UUID pageId : document.pageIds()) {
                if (!pagesById.containsKey(pageId) || !claimed.add(pageId)) {
                    throw new LabContractException(Code.PAGE_MEMBERSHIP_VIOLATION);
                }
            }
            Set<UUID> documentPages = new HashSet<>(document.pageIds());
            for (FieldOccurrence field : document.fields()) {
                for (EvidenceSpan span : field.evidence()) {
                    if (!documentPages.contains(span.pageId())) {
                        throw new LabContractException(Code.PAGE_MEMBERSHIP_VIOLATION);
                    }
                }
            }
        }
        List<UUID> expectedUnassigned =
                pages.stream()
                        .map(EnginePage::id)
                        .filter(pageId -> !claimed.contains(pageId))
                        .toList();
        if (!expectedUnassigned.equals(unassignedPageIds)) {
            if (new HashSet<>(expectedUnassigned).equals(new HashSet<>(unassignedPageIds))) {
                throw new LabContractException(Code.ORDERING_VIOLATION);
            }
            throw new LabContractException(Code.PAGE_MEMBERSHIP_VIOLATION);
        }
    }

    private static <T> void unique(List<T> values, Function<T, Object> identity) {
        Set<Object> identities = new HashSet<>();
        for (T value : values) {
            if (!identities.add(identity.apply(value))) {
                throw new LabContractException(Code.IDENTITY_DUPLICATE);
            }
        }
    }

    private static <T> void strictlyAscending(List<T> values, Comparator<T> order) {
        for (int index = 1; index < values.size(); index++) {
            if (order.compare(values.get(index - 1), values.get(index)) >= 0) {
                throw new LabContractException(Code.ORDERING_VIOLATION);
            }
        }
    }

    private static int codePointCompare(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            int comparison = Integer.compare(leftCodePoint, rightCodePoint);
            if (comparison != 0) {
                return comparison;
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    // ------------------------------------------------------------------ node helpers

    private static JsonNode member(JsonNode object, String name) {
        JsonNode value = requireObjectNode(object).get(name);
        if (value == null) {
            throw new LabContractException(Code.ENVELOPE_MEMBER_MISSING);
        }
        return value;
    }

    /** Every required member present, no member outside the required and optional sets. */
    private static void requireMembers(
            JsonNode object, Set<String> requiredMembers, Set<String> optionalMembers) {
        Iterator<String> names = requireObjectNode(object).fieldNames();
        int requiredPresent = 0;
        while (names.hasNext()) {
            String name = names.next();
            if (requiredMembers.contains(name)) {
                requiredPresent++;
            } else if (!optionalMembers.contains(name)) {
                throw new LabContractException(Code.ENVELOPE_MEMBER_UNKNOWN);
            }
        }
        if (requiredPresent != requiredMembers.size()) {
            throw new LabContractException(Code.ENVELOPE_MEMBER_MISSING);
        }
    }

    private static void requireExactMembers(JsonNode object, Set<String> exactMembers) {
        Iterator<String> names = requireObjectNode(object).fieldNames();
        int present = 0;
        while (names.hasNext()) {
            String name = names.next();
            if (!exactMembers.contains(name)) {
                throw new LabContractException(Code.ENVELOPE_MEMBER_UNKNOWN);
            }
            present++;
        }
        if (present != exactMembers.size()) {
            throw new LabContractException(Code.ENVELOPE_MEMBER_MISSING);
        }
    }

    private static JsonNode requireObjectNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return node;
    }

    private static JsonNode requireArrayNode(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return node;
    }

    private static String requireString(JsonNode node) {
        if (!node.isTextual()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return node.textValue();
    }

    private static String requireNonEmptyString(JsonNode node) {
        String value = requireString(node);
        if (value.isEmpty()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return value;
    }

    private static String requirePattern(JsonNode node, Pattern pattern) {
        String value = requireString(node);
        if (!pattern.matcher(value).matches()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return value;
    }

    private static UUID requireUuid(JsonNode node) {
        return UUID.fromString(requirePattern(node, UUID_SHAPE));
    }

    private static String requireDigest(JsonNode node) {
        return requirePattern(node, DIGEST_SHAPE);
    }

    private static int requireInt(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return node.intValue();
    }

    private static long requireLong(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return node.longValue();
    }

    private static boolean requireBoolean(JsonNode node) {
        if (!node.isBoolean()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return node.booleanValue();
    }

    private static BigDecimal requireDecimal(JsonNode node) {
        if (!node.isNumber()) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return node.decimalValue();
    }

    private static BigDecimal requireUnitInterval(JsonNode node) {
        BigDecimal value = requireDecimal(node);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
        }
        return value;
    }

    private static List<UUID> uuidList(JsonNode node) {
        List<UUID> values = new ArrayList<>();
        for (JsonNode item : requireArrayNode(node)) {
            values.add(requireUuid(item));
        }
        return values;
    }

    /** Converts a validated JsonNode subtree into unmodifiable maps/lists of scalars. */
    private static Object immutableTree(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isObject()) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                copy.put(entry.getKey(), immutableTree(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (node.isArray()) {
            ArrayList<Object> copy = new ArrayList<>(node.size());
            node.forEach(element -> copy.add(immutableTree(element)));
            return Collections.unmodifiableList(copy);
        }
        throw new LabContractException(Code.ENVELOPE_VALUE_MALFORMED);
    }
}
