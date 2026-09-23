package com.pragmaticds.docengine.results.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.results.canonical.EngineResultEnvelopeAssembler.AssembledEnvelope;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Classification;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationAnchor;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationBox;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.DeterministicRunnerUp;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.LlmEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.RuleAnchorEvidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationRange;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.ClassificationScore;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Document;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Evidence;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Field;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Membership;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Page;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.Source;
import com.pragmaticds.docengine.results.canonical.MachineResultSnapshot.StageProvenance;
import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EngineResultEnvelopeAssemblerTest {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final UUID PACKAGE_ID = uuid(1);
    private static final UUID JOB_ID = uuid(2);
    private static final UUID SOURCE_0 = uuid(10);
    private static final UUID SOURCE_1 = uuid(11);
    private static final UUID PAGE_0 = uuid(20);
    private static final UUID PAGE_1 = uuid(21);
    private static final UUID PAGE_2 = uuid(22);
    private static final UUID PAGE_3 = uuid(23);
    private static final UUID DOCUMENT_0 = uuid(30);
    private static final UUID DOCUMENT_1 = uuid(31);
    private static final UUID SCHEMA_ID = uuid(40);
    private static final UUID FIELD_TEXT = uuid(50);
    private static final UUID FIELD_NUMBER_A = uuid(51);
    private static final UUID FIELD_NUMBER_B = uuid(52);
    private static final UUID FIELD_DATE = uuid(53);
    private static final UUID FIELD_JSON = uuid(54);
    private static final UUID FIELD_MISSING = uuid(55);
    private static final UUID FIELD_SIGNATURE = uuid(56);

    /**
     * The digest of the envelope assembled from {@link #completeSnapshot()}. Recorded once, from the
     * bytes as shipped. It is a FREEZE, not a snapshot to be refreshed: see the test that uses it.
     */
    private static final String FROZEN_ENVELOPE_SHA256 =
            "8b5613276a198f8a458be6794b1aef72994283bb52fec7558f5ff4770ca73b42";

    private final CanonicalJsonWriter canonical = new CanonicalJsonWriter();
    private final EngineResultEnvelopeAssembler assembler =
            new EngineResultEnvelopeAssembler(canonical);

    @Test
    void assemblesOneStableMachineEnvelopeFromShuffledRowsWithAllTypedEvidenceAndProvenance() throws Exception {
        MachineResultSnapshot ordered = completeSnapshot();
        MachineResultSnapshot shuffled = shuffleEveryCollection(ordered);

        AssembledEnvelope first = assembler.assemble(request(), ordered);
        AssembledEnvelope second = assembler.assemble(request(), shuffled);

        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(second.envelopeSha256()).isEqualTo(first.envelopeSha256());
        JsonNode root = JSON.readTree(first.bytes());

        assertThat(root.path("envelopeVersion").asText()).isEqualTo("1.0.0");
        assertThat(root.path("canonicalizationVersion").asText()).isEqualTo("DOCENGINE-C14N-1");
        assertThat(root.at("/package/id").asText()).isEqualTo(PACKAGE_ID.toString());
        assertThat(root.at("/generation/processingJobId").asText()).isEqualTo(JOB_ID.toString());
        assertThat(root.at("/generation/parseGeneration").asInt()).isEqualTo(3);
        assertThat(root.at("/generation/packageRevision").asInt()).isEqualTo(7);
        assertThat(root.at("/generation/sourceSetSha256").asText())
                .isEqualTo(first.sourceSetSha256());

        assertThat(root.path("sources").findValuesAsText("ordinal")).containsExactly("0", "1");
        assertThat(root.path("pages").findValuesAsText("packagePageIndex"))
                .containsExactly("0", "1", "2", "3");
        assertThat(root.at("/pages/0/classification/rulePackVersion").asText())
                .isEqualTo("pack-2");
        assertThat(root.at("/pages/0/classification/evidence/anchors/0/spanIds/0").asLong())
                .isEqualTo(700L);
        assertThat(root.at("/pages/0/classification/evidence/anchors/0/spanIds/1").asLong())
                .isEqualTo(710L);
        assertThat(
                        root.at("/pages/0/classification/evidence/anchors")
                                .findValuesAsText("anchorId"))
                .containsExactly("z-anchor", "a-anchor");
        assertThat(
                        root.at("/pages/0/classification/evidence/scores")
                                .findValuesAsText("packType"))
                .containsExactly("SCHEDULE_E", "PAYSTUB");
        assertThat(root.at("/pages/0/classification/evidence/anchors/0/weight").decimalValue())
                .isEqualByComparingTo("5.12345678901234567890123456789");
        assertThat(root.at("/pages/1/widthPt").decimalValue())
                .isEqualByComparingTo("612.5");
        assertThat(root.at("/pages/1/renderDpi").asInt()).isEqualTo(300);
        assertThat(root.path("unassignedPageIds")).hasSize(1);
        assertThat(root.at("/unassignedPageIds/0").asText()).isEqualTo(PAGE_3.toString());

        JsonNode documents = root.path("documents");
        assertThat(documents.get(0).path("ordinal").asInt()).isZero();
        assertThat(documents.get(1).path("ordinal").asInt()).isOne();
        assertThat(documents.get(0).path("pageIds")).hasSize(2);
        assertThat(documents.at("/0/pageIds/0").asText()).isEqualTo(PAGE_0.toString());
        assertThat(documents.at("/0/pageIds/1").asText()).isEqualTo(PAGE_1.toString());

        List<JsonNode> fields = new ArrayList<>();
        documents.forEach(document -> document.path("fields").forEach(fields::add));
        assertThat(fields.stream().map(EngineResultEnvelopeAssemblerTest::occurrence))
                .containsExactly(
                        "amount#A",
                        "amount#B",
                        "json#",
                        "missing#",
                        "signature#",
                        "date#",
                        "text#");

        JsonNode amountA = field(fields, "amount", "A");
        JsonNode amountB = field(fields, "amount", "B");
        JsonNode text = field(fields, "text", null);
        JsonNode date = field(fields, "date", null);
        JsonNode json = field(fields, "json", null);
        JsonNode missing = field(fields, "missing", null);
        JsonNode signature = field(fields, "signature", null);

        assertThat(amountA.at("/normalized/number").decimalValue())
                .isEqualByComparingTo("1234.5");
        assertThat(amountB.at("/normalized/number").decimalValue())
                .isEqualByComparingTo("-25");
        assertThat(text.at("/normalized/text").asText()).isEqualTo("stable text value");
        assertThat(date.at("/normalized/date").asText()).isEqualTo("2026-08-15");
        assertThat(json.at("/normalized/json/address/city").asText()).isEqualTo("Testville");
        assertThat(missing.path("status").asText()).isEqualTo("MISSING");
        assertThat(missing.path("normalized").isNull()).isTrue();
        assertThat(signature.path("status").asText()).isEqualTo("FOUND");
        assertThat(signature.path("displayedText").isNull()).isTrue();
        assertThat(signature.at("/normalized/text").asText()).isEqualTo("UNSIGNED");
        assertThat(signature.path("evidence").findValuesAsText("role"))
                .containsExactly("LABEL");

        assertThat(amountA.path("evidence").findValuesAsText("role"))
                .containsExactly("VALUE", "VALUE", "LABEL", "CONTEXT");
        assertThat(amountA.at("/evidence/0/pageId").asText()).isEqualTo(PAGE_0.toString());
        assertThat(amountA.at("/evidence/0/box/x").decimalValue()).isEqualByComparingTo("10.1");
        assertThat(amountA.at("/evidence/0/textSpanId").asLong()).isEqualTo(801L);
        assertThat(amountA.at("/evidence/2/layoutElementId").asText())
                .isEqualTo(uuid(90).toString());
        assertThat(amountA.path("confidenceComponents").path("anchorStrength").decimalValue())
                .isEqualByComparingTo("0.9");
        assertThat(amountA.at("/schema/id").asText()).isEqualTo(SCHEMA_ID.toString());
        assertThat(amountA.at("/schema/version").asText()).isEqualTo("schema-1");

        assertThat(root.at("/provenance/stages/0/stage").asText()).isEqualTo("RENDERING");
        assertThat(root.at("/provenance/stages/1/stage").asText()).isEqualTo("PARSING");
        assertThat(root.at("/provenance/stages/1/parserVersions/pdfium").asText())
                .isEqualTo("v5");
        assertThat(root.at("/provenance/stages/2/workerVersion").isNull()).isTrue();
        assertThat(root.at("/provenance/stages/2/parserVersions").isNull()).isTrue();
        assertThat(first.provenanceSha256())
                .isEqualTo(canonical.write(root.path("provenance")).sha256());
        assertThat(first.envelopeSha256()).isEqualTo(sha256(first.bytes()));

        Set<String> forbidden =
                Set.of(
                        "filename",
                        "originalFilename",
                        "storageKey",
                        "storageKeyOriginal",
                        "signedUrl",
                        "rawDocumentText",
                        "correction",
                        "correctionReason",
                        "reviewStatus",
                        "actor",
                        "createdBy",
                        "resultId",
                        "createdAt",
                        "timestamp");
        assertThat(allPropertyNames(root)).doesNotContainAnyElementsOf(forbidden);
    }

    /**
     * Acceptance 19, asserted rather than argued.
     *
     * <p>The canonical envelope is FROZEN at {@code 1.0.0} / {@code DOCENGINE-C14N-1}, and design
     * decision D2 says L1 and L2 never enter it at any future version. Downstream
     * {@code requireExactMembers} makes ANY added member a hard reject of every result for every
     * consumer — so an accidental addition is not a diff to be reviewed later, it is an outage.
     *
     * <p>Nothing else in this repository can catch that. The byte comparison above proves
     * order-INDEPENDENCE (two assemblies in one JVM from one snapshot), not stability across code
     * versions; {@code EngineResultApiIT} pins the metadata DTO and the audit index, not the envelope
     * body. This test is the missing half: the exact member set at every level a future phase is
     * likely to touch, plus the literal digest of a fixed snapshot. It has already happened once,
     * when {@code textProvenance} was added to a read model and then vanished from the committed
     * OpenAPI document — that drift was invisible to a green suite.
     *
     * <p>If this test fails, the envelope changed. Do not re-record it: change the code back, or
     * change the envelope version deliberately, with the consumers told first.
     */
    @Test
    void theFrozenEnvelopeCarriesExactlyTheseMembersAndTheseBytes() throws Exception {
        AssembledEnvelope assembled = assembler.assemble(request(), completeSnapshot());
        JsonNode root = JSON.readTree(assembled.bytes());

        assertThat(members(root))
                .as("the envelope's top level")
                .containsExactlyInAnyOrder(
                        "envelopeVersion",
                        "canonicalizationVersion",
                        "package",
                        "generation",
                        "sources",
                        "pages",
                        "documents",
                        "unassignedPageIds",
                        "provenance");
        assertThat(members(root.path("generation")))
                .containsExactlyInAnyOrder(
                        "processingJobId",
                        "parseGeneration",
                        "packageRevision",
                        "sourceSetSha256",
                        "reuseEligibility");
        assertThat(members(root.at("/documents/0")))
                .containsExactlyInAnyOrder("id", "documentTypeCode", "ordinal", "pageIds", "fields");
        assertThat(members(root.at("/documents/0/fields/0")))
                .as("no textProvenance, no span, no L1 anything — D2")
                .containsExactlyInAnyOrder(
                        "name",
                        "groupKey",
                        "status",
                        "dataType",
                        "displayedText",
                        "rawValue",
                        "normalized",
                        "schema",
                        "method",
                        "extractorVersion",
                        "confidence",
                        "confidenceComponents",
                        "validationStatus",
                        "sensitive",
                        "evidence");
        assertThat(members(root.at("/documents/0/fields/0/evidence/0")))
                .as("the L3-to-L1 join keys, and nothing beside them")
                .containsExactlyInAnyOrder(
                        "pageId", "layoutElementId", "textSpanId", "role", "ordinal", "box");
        // confidenceComponents is deliberately NOT enumerated here: the assembler copies the stored
        // JSON verbatim, so its members are a property of the extractor that wrote them, not of this
        // envelope. Whatever it holds must never become a FOURTH factor in the product, and that is
        // the confidence calculator's contract, asserted where the product is computed.

        assertThat(root.path("envelopeVersion").asText()).isEqualTo("1.0.0");
        assertThat(root.path("canonicalizationVersion").asText()).isEqualTo("DOCENGINE-C14N-1");
        assertThat(assembled.envelopeSha256())
                .as("the frozen bytes of a fixed snapshot")
                .isEqualTo(FROZEN_ENVELOPE_SHA256);
    }

    @Test
    void sourceSetDigestIgnoresDatabaseIdsButChangesForSourceOrderOrMachineContent() {
        MachineResultSnapshot baseline = completeSnapshot();
        String expected = assembler.assemble(request(), baseline).sourceSetSha256();

        MachineResultSnapshot changedIds =
                mapSources(
                        baseline,
                        source ->
                                new Source(
                                        uuid(800 + source.ordinal()),
                                        source.ordinal(),
                                        source.contentSha256(),
                                        source.sizeBytes(),
                                        source.contentType()));
        MachineResultSnapshot changedOrder =
                mapSources(
                        baseline,
                        source ->
                                new Source(
                                        source.id(),
                                        1 - source.ordinal(),
                                        source.contentSha256(),
                                        source.sizeBytes(),
                                        source.contentType()));
        MachineResultSnapshot changedContent =
                mapSources(
                        baseline,
                        source ->
                                source.ordinal() == 0
                                        ? new Source(
                                                source.id(),
                                                source.ordinal(),
                                                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                                                source.sizeBytes(),
                                                source.contentType())
                                        : source);

        assertThat(assembler.assemble(request(), changedIds).sourceSetSha256()).isEqualTo(expected);
        assertThat(assembler.assemble(request(), changedOrder).sourceSetSha256())
                .isNotEqualTo(expected);
        assertThat(assembler.assemble(request(), changedContent).sourceSetSha256())
                .isNotEqualTo(expected);
    }

    @Test
    void ordersOccurrenceKeysByUnicodeCodePointWithNullFirst() throws Exception {
        String supplementary = "\uD800\uDC00";
        String privateUseBmp = "\uE000";
        MachineResultSnapshot base = completeSnapshot();
        List<Field> fields =
                List.of(
                        field(uuid(101), "unicode", supplementary, "ANCHOR_LABEL"),
                        field(uuid(102), "unicode", null, "ANCHOR_LABEL"),
                        field(uuid(103), "unicode", privateUseBmp, "ANCHOR_LABEL"));
        MachineResultSnapshot snapshot = replaceFieldsAndEvidence(base, fields, List.of());

        JsonNode root = JSON.readTree(assembler.assemble(request(), snapshot).bytes());

        assertThat(root.at("/documents/0/fields/0/groupKey").isNull()).isTrue();
        assertThat(root.at("/documents/0/fields/1/groupKey").asText()).isEqualTo(privateUseBmp);
        assertThat(root.at("/documents/0/fields/2/groupKey").asText()).isEqualTo(supplementary);
        assertThat(root.at("/documents/0/fields/0/groupKey").isNull()).isTrue();
    }

    @Test
    void rejectsDuplicateSemanticIdentitiesAndStatusMethodContradictions() {
        MachineResultSnapshot base = completeSnapshot();
        List<Field> duplicate = new ArrayList<>(base.fields());
        duplicate.add(
                field(uuid(999), "amount", "A", "ANCHOR_LABEL"));
        MachineResultSnapshot duplicateOccurrence =
                replaceFieldsAndEvidence(base, duplicate, base.evidence());

        assertThatThrownBy(() -> assembler.assemble(request(), duplicateOccurrence))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");

        List<Field> invalidMissing = new ArrayList<>(base.fields());
        invalidMissing.set(0, field(FIELD_TEXT, "text", null, "NONE"));
        Field contradictory = invalidMissing.get(0);
        invalidMissing.set(
                0,
                new Field(
                        contradictory.id(),
                        contradictory.logicalDocumentId(),
                        contradictory.schemaId(),
                        contradictory.schemaVersion(),
                        contradictory.fieldName(),
                        contradictory.groupKey(),
                        contradictory.dataType(),
                        "must-not-be-present",
                        null,
                        "must-not-be-present",
                        null,
                        null,
                        null,
                        contradictory.extractionMethod(),
                        contradictory.extractorVersion(),
                        contradictory.confidence(),
                        contradictory.confidenceComponents(),
                        contradictory.validationStatus(),
                        contradictory.sensitive()));

        assertThatThrownBy(
                        () ->
                                assembler.assemble(
                                        request(),
                                        replaceFieldsAndEvidence(
                                                base, invalidMissing, base.evidence())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    @Test
    void rejectsTwoPagesClaimingTheSameSourcePageCoordinate() {
        MachineResultSnapshot base = completeSnapshot();
        List<Page> pages = new ArrayList<>(base.pages());
        pages.add(
                new Page(
                        uuid(998),
                        SOURCE_0,
                        0,
                        4,
                        bd("612"),
                        bd("792"),
                        0,
                        200,
                        "NATIVE",
                        false,
                        false));
        MachineResultSnapshot duplicateSourcePage =
                new MachineResultSnapshot(
                        base.packageId(),
                        base.processingJobId(),
                        base.parseGeneration(),
                        base.sources(),
                        pages,
                        base.classifications(),
                        base.documents(),
                        base.memberships(),
                        base.fields(),
                        base.evidence(),
                        base.successfulStages());

        assertThatThrownBy(() -> assembler.assemble(request(), duplicateSourcePage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    @Test
    void rejectsTwoSuccessfulAttemptsClaimingTheSameStageIdentity() {
        MachineResultSnapshot base = completeSnapshot();
        List<StageProvenance> stages = new ArrayList<>(base.successfulStages());
        stages.add(
                new StageProvenance(
                        "PARSING", 3, "different-digest", "worker-4", object("pdfium", "v6")));
        MachineResultSnapshot duplicateStage =
                new MachineResultSnapshot(
                        base.packageId(),
                        base.processingJobId(),
                        base.parseGeneration(),
                        base.sources(),
                        base.pages(),
                        base.classifications(),
                        base.documents(),
                        base.memberships(),
                        base.fields(),
                        base.evidence(),
                        stages);

        assertThatThrownBy(() -> assembler.assemble(request(), duplicateStage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    @Test
    void acceptsAiExtractionProvenanceAfterDeterministicExtraction() throws Exception {
        MachineResultSnapshot base = completeSnapshot();
        List<StageProvenance> stages = new ArrayList<>(base.successfulStages());
        stages.add(new StageProvenance("EXTRACTING", 1, "digest-extract", null, null));
        stages.add(new StageProvenance("AI_EXTRACTION", 1, "digest-ai", null, null));
        MachineResultSnapshot withAi =
                new MachineResultSnapshot(
                        base.packageId(),
                        base.processingJobId(),
                        base.parseGeneration(),
                        base.sources(),
                        base.pages(),
                        base.classifications(),
                        base.documents(),
                        base.memberships(),
                        base.fields(),
                        base.evidence(),
                        stages);

        JsonNode root = JSON.readTree(assembler.assemble(request(), withAi).bytes());

        assertThat(root.path("provenance").path("stages").findValuesAsText("stage"))
                .endsWith("EXTRACTING", "AI_EXTRACTION");
    }

    @Test
    void rejectsEvidenceOnAPageOwnedByAnotherLogicalDocument() {
        MachineResultSnapshot base = completeSnapshot();
        List<Evidence> crossDocumentEvidence =
                base.evidence().stream()
                        .map(
                                evidence ->
                                        evidence.extractedFieldId().equals(FIELD_NUMBER_A)
                                                ? new Evidence(
                                                        evidence.id(),
                                                        evidence.extractedFieldId(),
                                                        PAGE_2,
                                                        evidence.layoutElementId(),
                                                        evidence.textSpanId(),
                                                        evidence.x(),
                                                        evidence.y(),
                                                        evidence.width(),
                                                        evidence.height(),
                                                        evidence.role(),
                                                        evidence.ordinal())
                                                : evidence)
                        .toList();
        MachineResultSnapshot crossDocument =
                replaceFieldsAndEvidence(base, base.fields(), crossDocumentEvidence);

        assertThatThrownBy(() -> assembler.assemble(request(), crossDocument))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    @Test
    void rejectsInvalidClassificationEvidenceIdentifiers() {
        MachineResultSnapshot base = completeSnapshot();
        Classification original = base.classifications().get(0);
        ClassificationAnchor invalid =
                new ClassificationAnchor(
                        "raw borrower text",
                        "pack-2",
                        "z-anchor",
                        BigDecimal.ONE,
                        List.of(701L),
                        List.of(
                                new ClassificationBox(
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        BigDecimal.ONE)),
                        new ClassificationRange(0, 1));
        Classification changed =
                new Classification(
                        original.pageId(),
                        original.documentTypeCode(),
                        original.confidence(),
                        original.method(),
                        original.rulePackVersion(),
                        new RuleAnchorEvidence(List.of(invalid), ruleAnchor(original).scores()));

        assertThatThrownBy(
                        () ->
                                assembler.assemble(
                                        request(),
                                        replaceClassifications(
                                                base,
                                                List.of(
                                                        changed,
                                                        base.classifications().get(1)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Machine snapshot is inconsistent");
    }

    /**
     * A model-typed page publishes the model's own evidence and NOTHING of the rule packs'. The
     * empty {@code anchors}/{@code scores} arrays a shared envelope would have required are a
     * fabrication — the packs never ran an anchor over this page — and the digest must not attest
     * to them.
     */
    @Test
    void publishesTheModelsOwnEvidenceMembersForAnLlmClassifiedPage() throws Exception {
        MachineResultSnapshot base = completeSnapshot();
        Classification original = base.classifications().get(0);
        Classification llm = llmClassification(original, runnerUp());

        JsonNode root =
                JSON.readTree(
                        assembler
                                .assemble(
                                        request(),
                                        replaceClassifications(
                                                base,
                                                List.of(llm, base.classifications().get(1))))
                                .bytes());

        JsonNode evidence = null;
        for (JsonNode page : root.path("pages")) {
            if (page.path("id").asText().equals(original.pageId().toString())) {
                evidence = page.path("classification").path("evidence");
            }
        }
        assertThat(evidence).isNotNull();
        assertThat(propertyNames(evidence))
                .containsExactlyInAnyOrder(
                        "source",
                        "model",
                        "promptVersion",
                        "matchedSpanIds",
                        "offsets",
                        "deterministicRunnerUp");
        assertThat(evidence.path("source").asText()).isEqualTo("AI");
        assertThat(evidence.path("model").asText()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(evidence.path("promptVersion").asText()).isEqualTo("page-classification-1");
        assertThat(evidence.at("/matchedSpanIds/0").asLong()).isEqualTo(701L);
        assertThat(evidence.at("/offsets/start").asInt()).isEqualTo(10);
        assertThat(evidence.at("/offsets/end").asInt()).isEqualTo(40);
        assertThat(evidence.at("/deterministicRunnerUp/type").asText()).isEqualTo("PAYSTUB");
        assertThat(evidence.at("/deterministicRunnerUp/score").decimalValue())
                .isEqualByComparingTo("0.55");
    }

    @Test
    void omitsTheRunnerUpMemberWhenTheSupersededRowRecordedNoScore() throws Exception {
        MachineResultSnapshot base = completeSnapshot();
        Classification original = base.classifications().get(0);

        JsonNode root =
                JSON.readTree(
                        assembler
                                .assemble(
                                        request(),
                                        replaceClassifications(
                                                base,
                                                List.of(
                                                        llmClassification(original, null),
                                                        base.classifications().get(1))))
                                .bytes());

        for (JsonNode page : root.path("pages")) {
            if (page.path("id").asText().equals(original.pageId().toString())) {
                assertThat(page.at("/classification/evidence").has("deterministicRunnerUp"))
                        .isFalse();
            }
        }
    }

    @Test
    void rejectsLlmEvidenceThatIsIncompleteOrCarriesSomethingOtherThanIdentifiers() {
        MachineResultSnapshot base = completeSnapshot();
        Classification original = base.classifications().get(0);
        List<LlmEvidence> invalid =
                List.of(
                        new LlmEvidence(
                                "AI",
                                "borrower name on the page",
                                "page-classification-1",
                                List.of(701L),
                                new ClassificationRange(10, 40),
                                runnerUp()),
                        llmEvidence(List.of(0L), new ClassificationRange(10, 40), runnerUp()),
                        llmEvidence(List.of(701L), new ClassificationRange(40, 10), runnerUp()),
                        llmEvidence(
                                List.of(701L),
                                new ClassificationRange(10, 40),
                                new DeterministicRunnerUp("raw borrower text", bd("0.55"))),
                        llmEvidence(
                                List.of(701L),
                                new ClassificationRange(10, 40),
                                new DeterministicRunnerUp("PAYSTUB", null)));

        for (LlmEvidence evidence : invalid) {
            Classification changed =
                    new Classification(
                            original.pageId(),
                            original.documentTypeCode(),
                            original.confidence(),
                            "LLM",
                            null,
                            evidence);

            assertThatThrownBy(
                            () ->
                                    assembler.assemble(
                                            request(),
                                            replaceClassifications(
                                                    base,
                                                    List.of(
                                                            changed,
                                                            base.classifications().get(1)))))
                    .as("invalid LLM evidence %s", evidence.model())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Machine snapshot is inconsistent")
                    .satisfies(
                            failure ->
                                    assertThat(failure.getMessage())
                                            .doesNotContain("borrower"));
        }
    }

    @Test
    void publishesCoQualifyingTypesOnlyWhereTheClassifierWroteThem() throws Exception {
        MachineResultSnapshot base = completeSnapshot();
        Classification original = base.classifications().get(0);
        Classification coQualified =
                new Classification(
                        original.pageId(),
                        original.documentTypeCode(),
                        original.confidence(),
                        original.method(),
                        original.rulePackVersion(),
                        new RuleAnchorEvidence(
                                ruleAnchor(original).anchors(),
                                ruleAnchor(original).scores(),
                                List.of("SCHEDULE_C", "TAX_RETURN")));
        MachineResultSnapshot snapshot =
                replaceClassifications(
                        base, List.of(coQualified, base.classifications().get(1)));

        JsonNode root = JSON.readTree(assembler.assemble(request(), snapshot).bytes());

        JsonNode published = null;
        for (JsonNode page : root.path("pages")) {
            JsonNode evidence = page.path("classification").path("evidence");
            if (page.path("id").asText().equals(original.pageId().toString())) {
                published = evidence.path("coQualifyingTypes");
            } else {
                assertThat(evidence.has("coQualifyingTypes"))
                        .as("page %s did not co-qualify", page.path("id").asText())
                        .isFalse();
            }
        }
        assertThat(published).isNotNull();
        assertThat(List.of(published.get(0).asText(), published.get(1).asText()))
                .containsExactly("SCHEDULE_C", "TAX_RETURN");
        assertThat(assembler.assemble(request(), base).bytes())
                .as("an envelope without co-qualification is byte-identical to before")
                .isEqualTo(assembler.assemble(request(), completeSnapshot()).bytes());
    }

    @Test
    void rejectsCoQualifyingTypesThatAreNotDistinctTypeIdentifiers() {
        MachineResultSnapshot base = completeSnapshot();
        Classification original = base.classifications().get(0);
        for (List<String> invalid :
                List.of(List.of("raw borrower text"), List.of("W2", "W2"), List.of("w2"))) {
            Classification changed =
                    new Classification(
                            original.pageId(),
                            original.documentTypeCode(),
                            original.confidence(),
                            original.method(),
                            original.rulePackVersion(),
                            new RuleAnchorEvidence(
                                    ruleAnchor(original).anchors(),
                                    ruleAnchor(original).scores(),
                                    invalid));

            assertThatThrownBy(
                            () ->
                                    assembler.assemble(
                                            request(),
                                            replaceClassifications(
                                                    base,
                                                    List.of(
                                                            changed,
                                                            base.classifications().get(1)))))
                    .as("coQualifyingTypes %s", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Machine snapshot is inconsistent: coQualifyingTypes")
                    .satisfies(
                            failure ->
                                    assertThat(failure.getMessage())
                                            .doesNotContain("raw borrower text"));
        }
    }

    private static EnvelopeAssemblyRequest request() {
        return new EnvelopeAssemblyRequest(
                PACKAGE_ID,
                JOB_ID,
                3,
                7,
                "1.0.0",
                "DOCENGINE-C14N-1",
                ReuseEligibility.PARSE_ONCE_CURRENT_PACKAGE);
    }

    private static MachineResultSnapshot completeSnapshot() {
        List<Source> sources =
                List.of(
                        new Source(SOURCE_0, 0, "a".repeat(64), 100L, "application/pdf"),
                        new Source(SOURCE_1, 1, "b".repeat(64), 200L, "image/png"));
        List<Page> pages =
                List.of(
                        new Page(PAGE_0, SOURCE_0, 0, 0, bd("612"), bd("792"), 0, 200, "NATIVE", false, false),
                        new Page(PAGE_1, SOURCE_0, 1, 1, bd("612.50"), bd("792"), 90, 300, "MIXED", false, true),
                        new Page(PAGE_2, SOURCE_1, 0, 2, bd("500"), bd("700"), 0, null, "NATIVE", false, false),
                        new Page(PAGE_3, SOURCE_1, 1, 3, bd("500"), bd("700"), 0, null, "NONE", true, false));
        List<Classification> classifications =
                List.of(
                        new Classification(
                                PAGE_1,
                                "SCHEDULE_E",
                                bd("0.80"),
                                "RULE_ANCHOR",
                                "pack-2",
                                classificationEvidence(
                                        701L, "3.00000000000000000000000000001")),
                        new Classification(
                                PAGE_0,
                                "SCHEDULE_E",
                                bd("0.95"),
                                "RULE_ANCHOR",
                                "pack-2",
                                classificationEvidence(
                                        700L, "1.00000000000000000000000000001")));
        List<Document> documents =
                List.of(
                        new Document(DOCUMENT_1, 1, "PAYSTUB"),
                        new Document(DOCUMENT_0, 0, "SCHEDULE_E"));
        List<Membership> memberships =
                List.of(
                        new Membership(DOCUMENT_0, PAGE_1, 1),
                        new Membership(DOCUMENT_0, PAGE_0, 0),
                        new Membership(DOCUMENT_1, PAGE_2, 0));
        List<Field> fields =
                List.of(
                        new Field(
                                FIELD_TEXT,
                                DOCUMENT_1,
                                SCHEMA_ID,
                                "schema-1",
                                "text",
                                null,
                                "STRING",
                                "Stable Text Value",
                                "Stable Text Value",
                                "stable text value",
                                null,
                                null,
                                null,
                                "ANCHOR_LABEL",
                                "extractor-1",
                                bd("0.91"),
                                object("anchorStrength", bd("0.9")),
                                "VALID",
                                false),
                        new Field(
                                FIELD_NUMBER_B,
                                DOCUMENT_0,
                                SCHEMA_ID,
                                "schema-1",
                                "amount",
                                "B",
                                "MONEY",
                                "($25.00)",
                                "($25.00)",
                                null,
                                bd("-25.0000"),
                                null,
                                null,
                                "OCR_LINE",
                                "extractor-1",
                                bd("0.88"),
                                object("spanConfidence", bd("0.8")),
                                "WARNING",
                                true),
                        new Field(
                                FIELD_NUMBER_A,
                                DOCUMENT_0,
                                SCHEMA_ID,
                                "schema-1",
                                "amount",
                                "A",
                                "MONEY",
                                "$1,234.50",
                                "$1,234.50",
                                null,
                                bd("1234.5000"),
                                null,
                                null,
                                "TABLE_CLUSTER",
                                "extractor-1",
                                bd("0.93"),
                                object("anchorStrength", bd("0.9"), "spanConfidence", bd("0.95")),
                                "VALID",
                                true),
                        new Field(
                                FIELD_DATE,
                                DOCUMENT_1,
                                SCHEMA_ID,
                                "schema-1",
                                "date",
                                null,
                                "DATE",
                                "08/15/2026",
                                "08/15/2026",
                                null,
                                null,
                                LocalDate.of(2026, 8, 15),
                                null,
                                "REGEX",
                                "extractor-1",
                                bd("0.90"),
                                object("normalizerCertainty", 1),
                                "VALID",
                                false),
                        new Field(
                                FIELD_JSON,
                                DOCUMENT_0,
                                SCHEMA_ID,
                                "schema-1",
                                "json",
                                null,
                                "STRING",
                                "address",
                                "address",
                                null,
                                null,
                                null,
                                object("address", object("city", "Testville", "zip", "00000")),
                                "FORM_FIELD",
                                "extractor-1",
                                bd("0.77"),
                                object("normalizerCertainty", bd("0.7")),
                                "NOT_VALIDATED",
                                false),
                        new Field(
                                FIELD_MISSING,
                                DOCUMENT_0,
                                SCHEMA_ID,
                                "schema-1",
                                "missing",
                                null,
                                "NUMBER",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "NONE",
                                "extractor-1",
                                BigDecimal.ZERO,
                                object("reason", "not-found"),
                                "MANUAL_REVIEW_REQUIRED",
                                false),
                        new Field(
                                FIELD_SIGNATURE,
                                DOCUMENT_0,
                                SCHEMA_ID,
                                "schema-1",
                                "signature",
                                null,
                                "ENUM",
                                null,
                                null,
                                "UNSIGNED",
                                null,
                                null,
                                null,
                                "FORM_FIELD",
                                "extractor-1",
                                bd("0.65"),
                                object("labelOnly", true),
                                "VALID",
                                false));
        List<Evidence> evidence =
                List.of(
                        evidence(uuid(70), FIELD_NUMBER_A, PAGE_0, "CONTEXT", 0, 804L, null, "40.4"),
                        evidence(uuid(71), FIELD_NUMBER_A, PAGE_0, "VALUE", 1, 802L, null, "20.2"),
                        evidence(uuid(72), FIELD_NUMBER_A, PAGE_0, "LABEL", 0, 803L, uuid(90), "30.3"),
                        evidence(uuid(73), FIELD_NUMBER_A, PAGE_0, "VALUE", 0, 801L, null, "10.1"),
                        evidence(uuid(74), FIELD_SIGNATURE, PAGE_1, "LABEL", 0, 901L, uuid(91), "50.5"));
        List<StageProvenance> stages =
                List.of(
                        new StageProvenance(
                                "PARSING",
                                2,
                                "digest-parsing",
                                "worker-3",
                                object("pdfium", "v5")),
                        new StageProvenance("RENDERING", 1, "digest-render", "worker-1", object()),
                        new StageProvenance("CLASSIFYING", 1, "digest-classify", null, null));
        return new MachineResultSnapshot(
                PACKAGE_ID,
                JOB_ID,
                3,
                sources,
                pages,
                classifications,
                documents,
                memberships,
                fields,
                evidence,
                stages);
    }

    private static Evidence evidence(
            UUID id,
            UUID fieldId,
            UUID pageId,
            String role,
            int ordinal,
            Long spanId,
            UUID layoutId,
            String x) {
        return new Evidence(
                id,
                fieldId,
                pageId,
                layoutId,
                spanId,
                bd(x),
                bd("2.2"),
                bd("3.3"),
                bd("4.4"),
                role,
                ordinal);
    }

    private static Field field(UUID id, String name, String group, String method) {
        boolean missing = "NONE".equals(method);
        return new Field(
                id,
                DOCUMENT_0,
                SCHEMA_ID,
                "schema-1",
                name,
                group,
                "STRING",
                missing ? null : "display",
                missing ? null : "raw",
                missing ? null : "normalized",
                null,
                null,
                null,
                method,
                "extractor-1",
                missing ? BigDecimal.ZERO : BigDecimal.ONE,
                object(),
                missing ? "MANUAL_REVIEW_REQUIRED" : "VALID",
                false);
    }

    private static MachineResultSnapshot shuffleEveryCollection(MachineResultSnapshot source) {
        return new MachineResultSnapshot(
                source.packageId(),
                source.processingJobId(),
                source.parseGeneration(),
                reversed(source.sources()),
                reversed(source.pages()),
                reversed(source.classifications()),
                reversed(source.documents()),
                reversed(source.memberships()),
                reversed(source.fields()),
                reversed(source.evidence()),
                reversed(source.successfulStages()));
    }

    private static MachineResultSnapshot mapSources(
            MachineResultSnapshot source, Function<Source, Source> transform) {
        List<Source> transformed = source.sources().stream().map(transform).toList();
        java.util.Map<UUID, UUID> idsByOldId = new java.util.HashMap<>();
        for (Source old : source.sources()) {
            transformed.stream()
                    .filter(candidate -> candidate.ordinal() == old.ordinal())
                    .findFirst()
                    .ifPresent(candidate -> idsByOldId.put(old.id(), candidate.id()));
        }
        List<Page> remappedPages =
                source.pages().stream()
                        .map(
                                page ->
                                        new Page(
                                                page.id(),
                                                idsByOldId.getOrDefault(
                                                        page.sourceFileId(), page.sourceFileId()),
                                                page.sourcePageIndex(),
                                                page.packagePageIndex(),
                                                page.widthPt(),
                                                page.heightPt(),
                                                page.rotation(),
                                                page.renderDpi(),
                                                page.textLayer(),
                                                page.blank(),
                                                page.duplicate()))
                        .toList();
        return new MachineResultSnapshot(
                source.packageId(),
                source.processingJobId(),
                source.parseGeneration(),
                transformed,
                remappedPages,
                source.classifications(),
                source.documents(),
                source.memberships(),
                source.fields(),
                source.evidence(),
                source.successfulStages());
    }

    private static MachineResultSnapshot replaceFieldsAndEvidence(
            MachineResultSnapshot source, List<Field> fields, List<Evidence> evidence) {
        return new MachineResultSnapshot(
                source.packageId(),
                source.processingJobId(),
                source.parseGeneration(),
                source.sources(),
                source.pages(),
                source.classifications(),
                source.documents(),
                source.memberships(),
                fields,
                evidence,
                source.successfulStages());
    }

    private static MachineResultSnapshot replaceClassifications(
            MachineResultSnapshot source, List<Classification> classifications) {
        return new MachineResultSnapshot(
                source.packageId(),
                source.processingJobId(),
                source.parseGeneration(),
                source.sources(),
                source.pages(),
                classifications,
                source.documents(),
                source.memberships(),
                source.fields(),
                source.evidence(),
                source.successfulStages());
    }

    private static RuleAnchorEvidence classificationEvidence(long spanId, String x) {
        return new RuleAnchorEvidence(
                List.of(
                        new ClassificationAnchor(
                                "SCHEDULE_E",
                                "pack-2",
                                "z-anchor",
                                bd("5.12345678901234567890123456789"),
                                List.of(spanId, spanId + 10),
                                List.of(
                                        new ClassificationBox(
                                                bd(x), bd("2.2"), bd("3.3"), bd("4.4"))),
                                new ClassificationRange(10, 20)),
                        new ClassificationAnchor(
                                "PAYSTUB",
                                "pack-1",
                                "a-anchor",
                                bd("1"),
                                List.of(spanId + 1),
                                List.of(),
                                new ClassificationRange(30, 40))),
                List.of(
                        new ClassificationScore(
                                "SCHEDULE_E", "pack-2", bd("0.95"), bd("0.6"), bd("5")),
                        new ClassificationScore(
                                "PAYSTUB", "pack-1", bd("0.25"), bd("0.6"), bd("4"))));
    }

    private static <T> List<T> reversed(List<T> values) {
        ArrayList<T> reversed = new ArrayList<>(values);
        Collections.reverse(reversed);
        return reversed;
    }

    private static JsonNode field(List<JsonNode> fields, String name, String group) {
        return fields.stream()
                .filter(field -> field.path("name").asText().equals(name))
                .filter(
                        field ->
                                group == null
                                        ? field.path("groupKey").isNull()
                                        : field.path("groupKey").asText().equals(group))
                .findFirst()
                .orElseThrow();
    }

    private static String occurrence(JsonNode field) {
        return field.path("name").asText()
                + "#"
                + (field.path("groupKey").isNull() ? "" : field.path("groupKey").asText());
    }

    /** The member names of ONE object, not the whole subtree — an added member must show up here. */
    private static List<String> members(JsonNode node) {
        assertThat(node.isObject()).as("members() needs an object, got %s", node.getNodeType()).isTrue();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> allPropertyNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        collectPropertyNames(node, names);
        return names;
    }

    private static void collectPropertyNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            node.properties()
                    .forEach(
                            property -> {
                                names.add(property.getKey());
                                collectPropertyNames(property.getValue(), names);
                            });
        } else if (node.isArray()) {
            node.forEach(child -> collectPropertyNames(child, names));
        }
    }

    private static ObjectNode object(Object... keyValues) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int index = 0; index < keyValues.length; index += 2) {
            String key = (String) keyValues[index];
            Object value = keyValues[index + 1];
            if (value == null) {
                node.putNull(key);
            } else if (value instanceof JsonNode json) {
                node.set(key, json);
            } else if (value instanceof String text) {
                node.put(key, text);
            } else if (value instanceof Integer number) {
                node.put(key, number);
            } else if (value instanceof Long number) {
                node.put(key, number);
            } else if (value instanceof BigDecimal number) {
                node.put(key, number);
            } else if (value instanceof Boolean bool) {
                node.put(key, bool);
            } else {
                throw new IllegalArgumentException("unsupported test JSON value");
            }
        }
        return node;
    }

    private static RuleAnchorEvidence ruleAnchor(Classification classification) {
        return (RuleAnchorEvidence) classification.evidence();
    }

    /** A page the packs left UNKNOWN and the model retyped: method LLM, no rule pack version. */
    private static Classification llmClassification(
            Classification original, DeterministicRunnerUp runnerUp) {
        return new Classification(
                original.pageId(),
                original.documentTypeCode(),
                original.confidence(),
                "LLM",
                null,
                llmEvidence(List.of(701L), new ClassificationRange(10, 40), runnerUp));
    }

    private static LlmEvidence llmEvidence(
            List<Long> matchedSpanIds,
            ClassificationRange offsets,
            DeterministicRunnerUp runnerUp) {
        return new LlmEvidence(
                "AI", "gemini-2.5-flash-lite", "page-classification-1", matchedSpanIds, offsets,
                runnerUp);
    }

    private static DeterministicRunnerUp runnerUp() {
        return new DeterministicRunnerUp("PAYSTUB", bd("0.55"));
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

}
