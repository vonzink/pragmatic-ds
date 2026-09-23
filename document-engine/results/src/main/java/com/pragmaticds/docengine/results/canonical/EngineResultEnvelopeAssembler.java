package com.pragmaticds.docengine.results.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.results.canonical.CanonicalJsonWriter.CanonicalArtifact;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Classification;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationAnchor;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationBox;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationScore;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.DeterministicRunnerUp;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Document;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Evidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Field;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.LlmEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Membership;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Page;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.RuleAnchorEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Source;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.StageProvenance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds and hashes the immutable machine-only envelope. */
@Component
public final class EngineResultEnvelopeAssembler {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern DOCUMENT_TYPE_IDENTIFIER =
            Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern EVIDENCE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern VERSION_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    /** Mirrors {@link MachineResultSnapshotLoader}: vendor-prefixed model ids are identifiers. */
    private static final Pattern MODEL_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+:/-]{0,127}");
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
                    // Values are SORT KEYS, never serialized — renumbering downstream entries when
                    // a stage is inserted at its true pipeline position changes no envelope bytes.
                    Map.entry("BOUNDARY_EXTRACTION", 8),
                    Map.entry("EXTRACTING", 9),
                    Map.entry("AI_EXTRACTION", 10));

    private final CanonicalJsonWriter canonical;

    /** Spring/runtime constructor; the writer is stateless. */
    public EngineResultEnvelopeAssembler() {
        this(new CanonicalJsonWriter());
    }

    /** Explicit dependency constructor used by focused canonical-contract tests. */
    public EngineResultEnvelopeAssembler(CanonicalJsonWriter canonical) {
        this.canonical = Objects.requireNonNull(canonical, "canonical");
    }

    public AssembledEnvelope assemble(
            EnvelopeAssemblyRequest request, MachineResultSnapshot snapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshot, "snapshot");
        validate(request, snapshot);

        List<Source> sources = sorted(snapshot.sources(), SOURCE_ORDER);
        List<Page> pages = sorted(snapshot.pages(), PAGE_ORDER);
        List<Document> documents = sorted(snapshot.documents(), DOCUMENT_ORDER);
        List<Membership> memberships = sorted(snapshot.memberships(), MEMBERSHIP_ORDER);
        List<Field> fields = sorted(snapshot.fields(), FIELD_ORDER);
        List<Evidence> evidence = sorted(snapshot.evidence(), EVIDENCE_ORDER);
        List<StageProvenance> stages = sorted(snapshot.successfulStages(), STAGE_PROVENANCE_ORDER);

        String sourceSetSha256 = SourceSetIdentity.digest(sourceSetEntries(sources));
        ObjectNode provenance = provenance(stages);
        String provenanceSha256 = canonical.write(provenance).sha256();

        ObjectNode root = JSON.objectNode();
        root.put("envelopeVersion", request.envelopeVersion());
        root.put("canonicalizationVersion", request.canonicalizationVersion());
        root.set("package", object("id", request.packageId().toString()));
        root.set("generation", generation(request, sourceSetSha256));
        root.set("sources", sources(sources));
        root.set("pages", pages(pages, snapshot.classifications()));
        root.set("documents", documents(documents, memberships, fields, evidence));
        root.set("unassignedPageIds", unassignedPageIds(pages, memberships));
        root.set("provenance", provenance);

        CanonicalArtifact artifact = canonical.write(root);
        return new AssembledEnvelope(
                artifact.bytes(), artifact.sha256(), sourceSetSha256, provenanceSha256);
    }

    static void validate(
            EnvelopeAssemblyRequest request, MachineResultSnapshot snapshot) {
        if (!request.packageId().equals(snapshot.packageId())
                || !request.processingJobId().equals(snapshot.processingJobId())
                || request.parseGeneration() != snapshot.parseGeneration()) {
            inconsistent("snapshot identity does not match the assembly request");
        }

        unique("source id", snapshot.sources(), Source::id);
        unique("source ordinal", snapshot.sources(), Source::ordinal);
        unique("page id", snapshot.pages(), Page::id);
        unique("package page index", snapshot.pages(), Page::packagePageIndex);
        unique(
                "source page coordinate",
                snapshot.pages(),
                page -> new SourcePageIdentity(page.sourceFileId(), page.sourcePageIndex()));
        unique("document id", snapshot.documents(), Document::id);
        unique("document ordinal", snapshot.documents(), Document::ordinal);
        unique("field id", snapshot.fields(), Field::id);
        unique("evidence id", snapshot.evidence(), Evidence::id);
        unique("successful stage", snapshot.successfulStages(), StageProvenance::stage);

        Map<UUID, Source> sources = index(snapshot.sources(), Source::id);
        Map<UUID, Page> pages = index(snapshot.pages(), Page::id);
        Map<UUID, Document> documents = index(snapshot.documents(), Document::id);
        Map<UUID, Field> fields = index(snapshot.fields(), Field::id);

        for (Source source : snapshot.sources()) {
            if (source.id() == null
                    || source.ordinal() < 0
                    || source.contentSha256() == null
                    || source.sizeBytes() < 0
                    || source.contentType() == null) {
                inconsistent("source row is incomplete");
            }
        }
        for (Page page : snapshot.pages()) {
            if (page.id() == null
                    || !sources.containsKey(page.sourceFileId())
                    || page.sourcePageIndex() < 0
                    || page.packagePageIndex() < 0
                    || page.widthPt() == null
                    || page.heightPt() == null
                    || page.textLayer() == null) {
                inconsistent("page row is incomplete or references an unknown source");
            }
        }
        unique(
                "current classification per page",
                snapshot.classifications(),
                Classification::pageId);
        for (Classification classification : snapshot.classifications()) {
            if (!pages.containsKey(classification.pageId())
                    || !matches(classification.documentTypeCode(), DOCUMENT_TYPE_IDENTIFIER)
                    || classification.confidence() == null
                    || classification.method() == null
                    || classification.evidence() == null) {
                inconsistent("classification row is incomplete or references an unknown page");
            }
            validateClassificationEvidence(classification.evidence());
        }
        for (Document document : snapshot.documents()) {
            if (document.id() == null
                    || document.ordinal() < 0
                    || document.documentTypeCode() == null) {
                inconsistent("logical document row is incomplete");
            }
        }

        Set<UUID> assignedPages = new HashSet<>();
        Map<UUID, Set<Integer>> membershipOrdinals = new HashMap<>();
        Map<UUID, Set<UUID>> membershipPages = new HashMap<>();
        for (Membership membership : snapshot.memberships()) {
            if (!documents.containsKey(membership.logicalDocumentId())
                    || !pages.containsKey(membership.pageId())
                    || membership.ordinal() < 0
                    || !assignedPages.add(membership.pageId())
                    || !membershipOrdinals
                            .computeIfAbsent(membership.logicalDocumentId(), ignored -> new HashSet<>())
                            .add(membership.ordinal())) {
                inconsistent(
                        "membership references an unknown document or page, or repeats a page or"
                                + " ordinal");
            }
            membershipPages
                    .computeIfAbsent(membership.logicalDocumentId(), ignored -> new HashSet<>())
                    .add(membership.pageId());
        }
        for (Document document : snapshot.documents()) {
            if (!membershipOrdinals.containsKey(document.id())) {
                inconsistent("logical document has no page memberships");
            }
        }

        Set<OccurrenceIdentity> occurrences = new HashSet<>();
        for (Field field : snapshot.fields()) {
            if (field.confidenceComponents() != null) {
                requireObject(field.confidenceComponents(), "field confidenceComponents");
            }
            if (field.normalizedJson() != null) {
                requireObject(field.normalizedJson(), "field normalizedJson");
            }
            if (field.id() == null
                    || !documents.containsKey(field.logicalDocumentId())
                    || field.schemaId() == null
                    || field.schemaVersion() == null
                    || field.fieldName() == null
                    || field.dataType() == null
                    || field.extractionMethod() == null
                    || field.extractorVersion() == null
                    || field.confidence() == null
                    || field.validationStatus() == null
                    || !occurrences.add(
                            new OccurrenceIdentity(
                                    field.logicalDocumentId(), field.fieldName(), field.groupKey()))) {
                inconsistent(
                        "field row is incomplete, references an unknown document, or repeats an"
                                + " occurrence");
            }
            if ("NONE".equals(field.extractionMethod())
                    && (field.displayedText() != null
                            || field.rawValue() != null
                            || field.normalizedText() != null
                            || field.normalizedNumber() != null
                            || field.normalizedDate() != null
                            || field.normalizedJson() != null)) {
                inconsistent("field with extraction method NONE carries a value");
            }
        }

        Map<UUID, Set<RoleOrdinal>> evidenceIdentities = new HashMap<>();
        for (Evidence item : snapshot.evidence()) {
            Field field = fields.get(item.extractedFieldId());
            if (field == null
                    || !pages.containsKey(item.pageId())
                    || !membershipPages
                            .getOrDefault(field.logicalDocumentId(), Set.of())
                            .contains(item.pageId())
                    || "NONE".equals(field.extractionMethod())
                    || item.x() == null
                    || item.y() == null
                    || item.width() == null
                    || item.height() == null
                    || item.ordinal() < 0
                    || roleOrder(item.role()) == Integer.MAX_VALUE
                    || !evidenceIdentities
                            .computeIfAbsent(item.extractedFieldId(), ignored -> new HashSet<>())
                            .add(new RoleOrdinal(item.role(), item.ordinal()))) {
                inconsistent(
                        "field evidence is incomplete, repeats a role/ordinal, or points at a page"
                                + " outside its field's logical document");
            }
        }

        for (StageProvenance stage : snapshot.successfulStages()) {
            if (stage.stage() == null
                    || stage.attempt() <= 0
                    || !STAGE_ORDER.containsKey(stage.stage())) {
                inconsistent("successful stage row names an unknown stage or attempt");
            }
            if (stage.parserVersions() != null) {
                requireObject(stage.parserVersions(), "stage parserVersions");
            }
        }
    }

    private static ObjectNode generation(EnvelopeAssemblyRequest request, String sourceSetSha256) {
        ObjectNode node = JSON.objectNode();
        node.put("processingJobId", request.processingJobId().toString());
        node.put("parseGeneration", request.parseGeneration());
        node.put("packageRevision", request.packageRevision());
        node.put("sourceSetSha256", sourceSetSha256);
        node.put("reuseEligibility", request.reuseEligibility().name());
        return node;
    }

    /**
     * The source-set digest input, delegated to {@link SourceSetIdentity} — the ONE algorithm this
     * assembler shares with the upload-time reuse probe. The envelope byte-freeze test and the
     * extraction goldens prove the delegation changed nothing.
     */
    private static List<SourceSetIdentity.Entry> sourceSetEntries(List<Source> sources) {
        return sources.stream()
                .map(
                        source ->
                                new SourceSetIdentity.Entry(
                                        source.ordinal(),
                                        source.contentSha256(),
                                        source.sizeBytes(),
                                        source.contentType()))
                .toList();
    }

    private static ArrayNode sources(List<Source> sources) {
        ArrayNode array = JSON.arrayNode();
        for (Source source : sources) {
            ObjectNode node = JSON.objectNode();
            node.put("id", source.id().toString());
            node.put("ordinal", source.ordinal());
            node.put("contentSha256", source.contentSha256());
            node.put("sizeBytes", source.sizeBytes());
            node.put("contentType", source.contentType());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode pages(List<Page> pages, List<Classification> classifications) {
        Map<UUID, Classification> byPage = index(classifications, Classification::pageId);
        ArrayNode array = JSON.arrayNode();
        for (Page page : pages) {
            ObjectNode node = JSON.objectNode();
            node.put("id", page.id().toString());
            node.put("sourceFileId", page.sourceFileId().toString());
            node.put("sourcePageIndex", page.sourcePageIndex());
            node.put("packagePageIndex", page.packagePageIndex());
            node.put("widthPt", page.widthPt());
            node.put("heightPt", page.heightPt());
            node.put("rotation", page.rotation());
            putNullable(node, "renderDpi", page.renderDpi());
            node.put("textLayer", page.textLayer());
            node.put("blank", page.blank());
            node.put("duplicate", page.duplicate());
            Classification classification = byPage.get(page.id());
            node.set(
                    "classification",
                    classification == null ? JSON.nullNode() : classification(classification));
            array.add(node);
        }
        return array;
    }

    private static ObjectNode classification(Classification classification) {
        ObjectNode node = JSON.objectNode();
        node.put("documentTypeCode", classification.documentTypeCode());
        node.put("confidence", classification.confidence());
        node.put("method", classification.method());
        putNullable(node, "rulePackVersion", classification.rulePackVersion());
        node.set("evidence", classificationEvidence(classification.evidence()));
        return node;
    }

    /**
     * Each method serializes ONLY its own members. A RULE_ANCHOR page's evidence object therefore
     * carries exactly the members it always carried, and no envelope published before LLM
     * classification existed would assemble to different bytes today.
     */
    private static ObjectNode classificationEvidence(ClassificationEvidence evidence) {
        return switch (evidence) {
            case RuleAnchorEvidence ruleAnchor -> ruleAnchorEvidence(ruleAnchor);
            case LlmEvidence llm -> llmEvidence(llm);
        };
    }

    private static ObjectNode llmEvidence(LlmEvidence evidence) {
        ObjectNode node = JSON.objectNode();
        DeterministicRunnerUp runnerUp = evidence.deterministicRunnerUp();
        if (runnerUp != null) {
            ObjectNode runnerUpNode = node.putObject("deterministicRunnerUp");
            runnerUpNode.put("type", runnerUp.type());
            runnerUpNode.put("score", runnerUp.score());
        }
        ArrayNode matchedSpanIds = node.putArray("matchedSpanIds");
        evidence.matchedSpanIds().forEach(matchedSpanIds::add);
        node.put("model", evidence.model());
        ObjectNode offsets = node.putObject("offsets");
        offsets.put("start", evidence.offsets().start());
        offsets.put("end", evidence.offsets().end());
        node.put("promptVersion", evidence.promptVersion());
        node.put("source", evidence.source());
        return node;
    }

    private static ObjectNode ruleAnchorEvidence(RuleAnchorEvidence evidence) {
        ObjectNode node = JSON.objectNode();
        ArrayNode anchors = node.putArray("anchors");
        for (ClassificationAnchor anchor : evidence.anchors()) {
            ObjectNode anchorNode = anchors.addObject();
            anchorNode.put("packType", anchor.packType());
            anchorNode.put("packVersion", anchor.packVersion());
            anchorNode.put("anchorId", anchor.anchorId());
            anchorNode.put("weight", anchor.weight());
            ArrayNode spanIds = anchorNode.putArray("spanIds");
            anchor.spanIds().forEach(spanIds::add);
            ArrayNode boxes = anchorNode.putArray("boxes");
            for (ClassificationBox box : anchor.boxes()) {
                ObjectNode boxNode = boxes.addObject();
                boxNode.put("x", box.x());
                boxNode.put("y", box.y());
                boxNode.put("width", box.width());
                boxNode.put("height", box.height());
            }
            ObjectNode range = anchorNode.putObject("range");
            range.put("start", anchor.range().start());
            range.put("end", anchor.range().end());
        }
        // Present only when the classifier wrote it (Phase B4): pages that did not co-qualify
        // keep the exact members they always had, so previously published envelopes are unchanged.
        if (!evidence.coQualifyingTypes().isEmpty()) {
            ArrayNode coQualifying = node.putArray("coQualifyingTypes");
            evidence.coQualifyingTypes().forEach(coQualifying::add);
        }
        ArrayNode scores = node.putArray("scores");
        for (ClassificationScore score : evidence.scores()) {
            ObjectNode scoreNode = scores.addObject();
            scoreNode.put("packType", score.packType());
            scoreNode.put("packVersion", score.packVersion());
            scoreNode.put("score", score.score());
            scoreNode.put("minConfidence", score.minConfidence());
            scoreNode.put("targetScore", score.targetScore());
        }
        return node;
    }

    private static ArrayNode documents(
            List<Document> documents,
            List<Membership> memberships,
            List<Field> fields,
            List<Evidence> evidence) {
        Map<UUID, List<Membership>> membershipByDocument =
                memberships.stream()
                        .collect(Collectors.groupingBy(Membership::logicalDocumentId));
        Map<UUID, List<Field>> fieldsByDocument =
                fields.stream().collect(Collectors.groupingBy(Field::logicalDocumentId));
        Map<UUID, List<Evidence>> evidenceByField =
                evidence.stream().collect(Collectors.groupingBy(Evidence::extractedFieldId));

        ArrayNode array = JSON.arrayNode();
        for (Document document : documents) {
            ObjectNode node = JSON.objectNode();
            node.put("id", document.id().toString());
            node.put("documentTypeCode", document.documentTypeCode());
            node.put("ordinal", document.ordinal());
            ArrayNode pageIds = JSON.arrayNode();
            sorted(membershipByDocument.getOrDefault(document.id(), List.of()), MEMBERSHIP_ORDER)
                    .forEach(membership -> pageIds.add(membership.pageId().toString()));
            node.set("pageIds", pageIds);

            ArrayNode fieldNodes = JSON.arrayNode();
            for (Field field :
                    sorted(fieldsByDocument.getOrDefault(document.id(), List.of()), FIELD_ORDER)) {
                fieldNodes.add(
                        field(
                                field,
                                sorted(
                                        evidenceByField.getOrDefault(field.id(), List.of()),
                                        EVIDENCE_ORDER)));
            }
            node.set("fields", fieldNodes);
            array.add(node);
        }
        return array;
    }

    private static ObjectNode field(Field field, List<Evidence> evidence) {
        ObjectNode node = JSON.objectNode();
        node.put("name", field.fieldName());
        putNullable(node, "groupKey", field.groupKey());
        boolean missing = "NONE".equals(field.extractionMethod());
        node.put("status", missing ? "MISSING" : "FOUND");
        node.put("dataType", field.dataType());
        putNullable(node, "displayedText", field.displayedText());
        putNullable(node, "rawValue", field.rawValue());

        if (missing) {
            node.putNull("normalized");
        } else {
            ObjectNode normalized = JSON.objectNode();
            putNullable(normalized, "text", field.normalizedText());
            putNullable(normalized, "number", field.normalizedNumber());
            putNullable(
                    normalized,
                    "date",
                    field.normalizedDate() == null ? null : field.normalizedDate().toString());
            normalized.set(
                    "json",
                    field.normalizedJson() == null
                            ? JSON.nullNode()
                            : field.normalizedJson().deepCopy());
            node.set("normalized", normalized);
        }

        ObjectNode schema = JSON.objectNode();
        schema.put("id", field.schemaId().toString());
        schema.put("version", field.schemaVersion());
        node.set("schema", schema);
        node.put("method", field.extractionMethod());
        node.put("extractorVersion", field.extractorVersion());
        node.put("confidence", field.confidence());
        node.set(
                "confidenceComponents",
                field.confidenceComponents() == null
                        ? JSON.nullNode()
                        : field.confidenceComponents().deepCopy());
        node.put("validationStatus", field.validationStatus());
        node.put("sensitive", field.sensitive());

        ArrayNode evidenceNodes = JSON.arrayNode();
        evidence.forEach(item -> evidenceNodes.add(evidence(item)));
        node.set("evidence", evidenceNodes);
        return node;
    }

    private static ObjectNode evidence(Evidence evidence) {
        ObjectNode node = JSON.objectNode();
        node.put("pageId", evidence.pageId().toString());
        putNullable(
                node,
                "layoutElementId",
                evidence.layoutElementId() == null
                        ? null
                        : evidence.layoutElementId().toString());
        putNullable(node, "textSpanId", evidence.textSpanId());
        node.put("role", evidence.role());
        node.put("ordinal", evidence.ordinal());
        ObjectNode box = JSON.objectNode();
        box.put("x", evidence.x());
        box.put("y", evidence.y());
        box.put("width", evidence.width());
        box.put("height", evidence.height());
        node.set("box", box);
        return node;
    }

    private static ArrayNode unassignedPageIds(List<Page> pages, List<Membership> memberships) {
        Set<UUID> assigned =
                memberships.stream().map(Membership::pageId).collect(Collectors.toSet());
        ArrayNode array = JSON.arrayNode();
        pages.stream()
                .map(Page::id)
                .filter(pageId -> !assigned.contains(pageId))
                .forEach(pageId -> array.add(pageId.toString()));
        return array;
    }

    private static ObjectNode provenance(List<StageProvenance> stages) {
        ObjectNode node = JSON.objectNode();
        node.set("applicationRelease", unavailable());
        node.set("extractionEngineRelease", unavailable());
        node.set("workerContractRelease", unavailable());
        ArrayNode stageNodes = JSON.arrayNode();
        for (StageProvenance stage : stages) {
            ObjectNode stageNode = JSON.objectNode();
            stageNode.put("stage", stage.stage());
            stageNode.put("attempt", stage.attempt());
            putNullable(stageNode, "outputDigest", stage.outputDigest());
            putNullable(stageNode, "workerVersion", stage.workerVersion());
            stageNode.set(
                    "parserVersions",
                    stage.parserVersions() == null
                            ? JSON.nullNode()
                            : stage.parserVersions().deepCopy());
            stageNodes.add(stageNode);
        }
        node.set("stages", stageNodes);
        return node;
    }

    private static ObjectNode unavailable() {
        return object("availability", "UNAVAILABLE");
    }

    private static ObjectNode object(String key, String value) {
        ObjectNode node = JSON.objectNode();
        node.put(key, value);
        return node;
    }

    private static void putNullable(ObjectNode node, String key, Object value) {
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof String text) {
            node.put(key, text);
        } else if (value instanceof Integer number) {
            node.put(key, number);
        } else if (value instanceof Long number) {
            node.put(key, number);
        } else if (value instanceof java.math.BigDecimal number) {
            node.put(key, number);
        } else {
            throw new IllegalArgumentException("Unsupported envelope value");
        }
    }

    private static <T, K> void unique(String what, List<T> values, Function<T, K> identity) {
        Set<K> identities = new HashSet<>();
        for (T value : values) {
            if (value == null || !identities.add(identity.apply(value))) {
                inconsistent(what + " is null or repeated");
            }
        }
    }

    private static <T, K> Map<K, T> index(List<T> values, Function<T, K> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> comparator) {
        ArrayList<T> copy = new ArrayList<>(values);
        copy.sort(comparator);
        return copy;
    }

    private static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            inconsistent(what + " is not an object");
        }
    }

    private static void validateClassificationEvidence(ClassificationEvidence evidence) {
        switch (evidence) {
            case RuleAnchorEvidence ruleAnchor -> validateRuleAnchorEvidence(ruleAnchor);
            case LlmEvidence llm -> validateLlmEvidence(llm);
        }
    }

    /**
     * The model's evidence proves itself with span ids and a character range, so there is nothing
     * here to check against the rule packs — only that the provenance is present, that every id it
     * claims to have matched is a real span id, and that the range is well ordered.
     */
    private static void validateLlmEvidence(LlmEvidence evidence) {
        if (!matches(evidence.source(), EVIDENCE_IDENTIFIER)
                || !matches(evidence.model(), MODEL_IDENTIFIER)
                || !matches(evidence.promptVersion(), EVIDENCE_IDENTIFIER)
                || evidence.matchedSpanIds() == null
                || evidence.matchedSpanIds().stream().anyMatch(id -> id == null || id <= 0)
                || evidence.offsets() == null
                || evidence.offsets().start() < 0
                || evidence.offsets().end() < evidence.offsets().start()) {
            inconsistent("LLM classification evidence is incomplete or its identifiers are invalid");
        }
        DeterministicRunnerUp runnerUp = evidence.deterministicRunnerUp();
        if (runnerUp != null
                && (!matches(runnerUp.type(), DOCUMENT_TYPE_IDENTIFIER)
                        || runnerUp.score() == null)) {
            inconsistent("deterministicRunnerUp is incomplete or its identifiers are invalid");
        }
    }

    private static void validateRuleAnchorEvidence(RuleAnchorEvidence evidence) {
        if (evidence.anchors() == null
                || evidence.scores() == null
                || evidence.coQualifyingTypes() == null) {
            inconsistent("classification evidence is missing a member list");
        }
        for (ClassificationAnchor anchor : evidence.anchors()) {
            if (anchor == null
                    || !matches(anchor.packType(), DOCUMENT_TYPE_IDENTIFIER)
                    || !matches(anchor.packVersion(), VERSION_IDENTIFIER)
                    || !matches(anchor.anchorId(), EVIDENCE_IDENTIFIER)
                    || anchor.weight() == null
                    || anchor.range() == null
                    || anchor.range().start() < 0
                    || anchor.range().end() < anchor.range().start()
                    || anchor.spanIds().stream().anyMatch(id -> id == null || id <= 0)
                    || anchor.boxes().stream()
                            .anyMatch(
                                    box ->
                                            box == null
                                                    || box.x() == null
                                                    || box.y() == null
                                                    || box.width() == null
                                                    || box.height() == null
                                                    || box.width().signum() < 0
                                                    || box.height().signum() < 0)) {
                inconsistent("classification anchor is incomplete or its identifiers are invalid");
            }
        }
        for (ClassificationScore score : evidence.scores()) {
            if (score == null
                    || !matches(score.packType(), DOCUMENT_TYPE_IDENTIFIER)
                    || !matches(score.packVersion(), VERSION_IDENTIFIER)
                    || score.score() == null
                    || score.minConfidence() == null
                    || score.targetScore() == null) {
                inconsistent("classification score is incomplete or its identifiers are invalid");
            }
        }
        Set<String> distinctTypes = new HashSet<>();
        for (String type : evidence.coQualifyingTypes()) {
            if (!matches(type, DOCUMENT_TYPE_IDENTIFIER) || !distinctTypes.add(type)) {
                inconsistent("coQualifyingTypes entry is not a distinct type identifier");
            }
        }
    }

    private static boolean matches(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
    }

    /** Structural reason only — never a stored value; see {@link MachineResultSnapshotLoader}. */
    private static void inconsistent(String reason) {
        throw MachineResultSnapshotLoader.inconsistent(reason);
    }

    private static int roleOrder(String role) {
        if ("VALUE".equals(role)) {
            return 0;
        }
        if ("LABEL".equals(role)) {
            return 1;
        }
        if ("CONTEXT".equals(role)) {
            return 2;
        }
        return Integer.MAX_VALUE;
    }

    private static final Comparator<String> CODE_POINT_ORDER =
            (left, right) -> {
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
            };

    private static final Comparator<Source> SOURCE_ORDER =
            Comparator.comparingInt(Source::ordinal);
    private static final Comparator<Page> PAGE_ORDER =
            Comparator.comparingInt(Page::packagePageIndex);
    private static final Comparator<Document> DOCUMENT_ORDER =
            Comparator.comparingInt(Document::ordinal);
    private static final Comparator<Membership> MEMBERSHIP_ORDER =
            Comparator.comparing(Membership::logicalDocumentId)
                    .thenComparingInt(Membership::ordinal);
    private static final Comparator<Field> FIELD_ORDER =
            Comparator.comparing(Field::logicalDocumentId)
                    .thenComparing(Field::fieldName, CODE_POINT_ORDER)
                    .thenComparing(
                            Field::groupKey,
                            Comparator.nullsFirst(CODE_POINT_ORDER));
    private static final Comparator<Evidence> EVIDENCE_ORDER =
            Comparator.comparing(Evidence::extractedFieldId)
                    .thenComparingInt(item -> roleOrder(item.role()))
                    .thenComparingInt(Evidence::ordinal);
    private static final Comparator<StageProvenance> STAGE_PROVENANCE_ORDER =
            Comparator.<StageProvenance>comparingInt(
                            stage ->
                                    STAGE_ORDER.getOrDefault(
                                            stage.stage(), Integer.MAX_VALUE))
                    .thenComparingInt(StageProvenance::attempt);

    private record OccurrenceIdentity(UUID documentId, String fieldName, String groupKey) {}

    private record SourcePageIdentity(UUID sourceFileId, int sourcePageIndex) {}

    private record RoleOrdinal(String role, int ordinal) {}

    /** Canonical envelope bytes plus all digests needed by the immutable descriptor. */
    public record AssembledEnvelope(
            byte[] bytes,
            String envelopeSha256,
            String sourceSetSha256,
            String provenanceSha256) {
        public AssembledEnvelope {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            Objects.requireNonNull(envelopeSha256, "envelopeSha256");
            Objects.requireNonNull(sourceSetSha256, "sourceSetSha256");
            Objects.requireNonNull(provenanceSha256, "provenanceSha256");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
