package com.pragmaticds.rag.lab.analyze;

import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LlmEvidence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ConfidenceComponents;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.NormalizedValue;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.PageClassification;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.RuleAnchorEvidence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SourceFile;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.engine.LabContractException;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renderer contract tests. Every envelope here is synthetic — invented UUIDs, invented employer
 * text, invented amounts — and is built directly from the parsed model so the renderer is
 * exercised as the LAST gate before the prompt rather than through the parser's own guards.
 */
class ParsedDocumentPromptRendererTest {

    private static final UUID PACKAGE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID JOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SOURCE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PAGE = UUID.fromString("44444444-4444-4444-8444-444444444440");
    private static final UUID PAGE_TWO = UUID.fromString("44444444-4444-4444-8444-444444444441");
    private static final UUID SCHEMA = UUID.fromString("66666666-6666-4666-8666-666666666660");
    private static final UUID DOCUMENT = UUID.fromString("77777777-7777-4777-8777-777777777770");
    private static final UUID LAYOUT = UUID.fromString("88888888-8888-4888-8888-888888888880");

    private final ParsedDocumentPromptRenderer renderer = new ParsedDocumentPromptRenderer();
    private final IncomeEnvelopeCompatibility compatibility = new IncomeEnvelopeCompatibility();

    // ------------------------------------------------------------------ fixtures

    private static FieldOccurrence field(String name, String groupKey, FieldStatus status,
                                         String dataType, String displayedText, String rawValue,
                                         NormalizedValue normalized, String validationStatus,
                                         boolean sensitive, List<EvidenceSpan> evidence) {
        return new FieldOccurrence(
                name,
                groupKey,
                status,
                dataType,
                displayedText,
                rawValue,
                normalized,
                new SchemaRef(SCHEMA, "2024.1"),
                status == FieldStatus.FOUND ? "ANCHOR_LABEL" : "NONE",
                "5.1.0",
                status == FieldStatus.FOUND ? new BigDecimal("0.97") : BigDecimal.ZERO,
                status == FieldStatus.FOUND
                        ? new ConfidenceComponents(
                                new BigDecimal("0.97"), BigDecimal.ONE, BigDecimal.ONE)
                        : null,
                validationStatus,
                sensitive,
                evidence);
    }

    private static EvidenceSpan evidence(UUID pageId) {
        return new EvidenceSpan(
                pageId,
                LAYOUT,
                4210L,
                "VALUE",
                0,
                new Box(new BigDecimal("72.00"), new BigDecimal("600.00"),
                        new BigDecimal("180.00"), new BigDecimal("12.00")));
    }

    private static EnginePage page(UUID id, int index, int rotation, PageClassification cls) {
        return new EnginePage(
                id,
                SOURCE,
                index,
                index,
                new BigDecimal("612"),
                new BigDecimal("792"),
                rotation,
                null,
                "NATIVE",
                false,
                false,
                cls);
    }

    private static PageClassification classification() {
        return new PageClassification(
                "PAYSTUB",
                new BigDecimal("0.98"),
                "RULE_PACK",
                "v10",
                new RuleAnchorEvidence(List.of(), List.of(), List.of()));
    }

    private static EngineResultEnvelope envelope(List<EnginePage> pages,
                                                 List<LogicalDocument> documents,
                                                 List<UUID> unassigned) {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                PACKAGE,
                new Generation(JOB, 1, 1, "b2".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE, 0, "a1".repeat(32), 48211L, "application/pdf")),
                pages,
                documents,
                unassigned,
                new Provenance(
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    /** One paystub, one FOUND text field with evidence, one MISSING grouped money field. */
    private static EngineResultEnvelope goldenEnvelope() {
        LogicalDocument document = new LogicalDocument(
                DOCUMENT,
                "PAYSTUB",
                1,
                List.of(PAGE),
                List.of(
                        field("employer.name", null, FieldStatus.FOUND, "TEXT",
                                "ACME WIDGETS", "ACME WIDGETS",
                                new NormalizedValue("ACME WIDGETS", null, null, null),
                                "VALIDATED", false, List.of(evidence(PAGE))),
                        field("wages.ytd", "A", FieldStatus.MISSING, "MONEY",
                                null, null, null, "NOT_VALIDATED", false, List.of())));
        return envelope(List.of(page(PAGE, 0, 0, classification())), List.of(document), List.of());
    }

    private ParsedDocumentPromptRenderer.Rendered render(EngineResultEnvelope envelope) {
        return renderer.render(envelope, compatibility.evaluate(envelope));
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void rendersTheExactDeterministicFactsBlock() {
        String expected = String.join("\n",
                "PARSED DOCUMENT FACTS (Pragmatic DS Document Engine immutable result)",
                "envelopeVersion=1.0.0 canonicalization=DOCENGINE-C14N-1",
                "package=11111111-1111-4111-8111-111111111111 packageRevision=1 parseGeneration=1"
                        + " processingJob=22222222-2222-4222-8222-222222222222",
                "sourceSetSha256=" + "b2".repeat(32) + " reuseEligibility=PARSE_ONCE_CURRENT_PACKAGE",
                "No document image, page image, or page text is attached to this request. The"
                        + " machine-extracted facts below are the only borrower evidence available;"
                        + " never infer, estimate, or restate a value shown as MISSING. A value"
                        + " marked reviewState=CORRECTED is a named reviewer's correction and takes"
                        + " precedence over anything you might infer; a field marked"
                        + " reviewState=REJECTED must be treated as absent.",
                "",
                "SOURCES (1)",
                "  S1 contentSha256=" + "a1".repeat(32)
                        + " sizeBytes=48211 contentType=application/pdf",
                "",
                "PAGES (1)",
                "  P1 source=S1 sourcePageIndex=0 widthPt=612 heightPt=792 rotation=0"
                        + " textLayer=NATIVE blank=false duplicate=false"
                        + " classification=PAYSTUB confidence=0.98 method=RULE_PACK",
                "",
                "DOCUMENTS (1)",
                "  doc-1 type=PAYSTUB engineDocumentId=77777777-7777-4777-8777-777777777770"
                        + " pages=[P1]",
                "    name=employer.name group=- status=FOUND dataType=TEXT confidence=0.97"
                        + " method=ANCHOR_LABEL extractorVersion=5.1.0 validationStatus=VALIDATED"
                        + " sensitive=false reviewState=UNREVIEWED_SOURCE",
                "      displayedText=\"ACME WIDGETS\" rawValue=\"ACME WIDGETS\""
                        + " normalized.text=\"ACME WIDGETS\"",
                "      evidence: page=P1 role=VALUE ordinal=0"
                        + " box=[x=72.00,y=600.00,w=180.00,h=12.00]"
                        + " layoutElementId=88888888-8888-4888-8888-888888888880 textSpanId=4210",
                "    name=wages.ytd group=A status=MISSING dataType=MONEY confidence=0"
                        + " method=NONE extractorVersion=5.1.0 validationStatus=NOT_VALIDATED"
                        + " sensitive=false reviewState=UNREVIEWED_SOURCE",
                "      value=MISSING (no value was extracted; do not infer one)",
                "      evidence: none",
                "",
                "UNASSIGNED PAGES: none",
                "",
                "PARSE WARNINGS (1)",
                "  FIELD_MISSING doc-1 type=PAYSTUB name=wages.ytd group=A",
                "",
                "CITATION HANDLES",
                "  doc-1 -> PAYSTUB",
                "Cite every borrower value with a BORROWER_DOC citation whose documentId is exactly"
                        + " one of these handles; never invent another handle.",
                "");
        assertEquals(expected, render(goldenEnvelope()).text());
    }

    @Test
    void aRejectedOccurrenceRendersAsMissingWithItsReasonAndNoValue() {
        FieldOccurrence rejected = new FieldOccurrence("wages.ytd", null, FieldStatus.MISSING, "MONEY",
                null, null, null, new SchemaRef(SCHEMA, "2025.5a"), "NONE", "5.1.0",
                new BigDecimal("0.97"), null, "VALID", false, List.of(),
                EngineResultEnvelope.ReviewState.REJECTED);
        LogicalDocument document = new LogicalDocument(DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                List.of(rejected));
        String text = render(envelope(List.of(page(PAGE, 0, 0, classification())),
                List.of(document), List.of())).text();

        assertTrue(text.contains(" status=MISSING dataType=MONEY confidence=0.97 method=NONE"
                + " extractorVersion=5.1.0 validationStatus=VALID sensitive=false"
                + " reviewState=REJECTED\n      value=(rejected by reviewer)\n"), text);
    }

    @Test
    void repeatedRendersOfOneEnvelopeAreByteIdentical() {
        EngineResultEnvelope envelope = goldenEnvelope();
        assertEquals(render(envelope).text(), render(envelope).text());
    }

    @Test
    void handlesAreStableAndDerivedFromDocumentOrdinal() {
        ParsedDocumentPromptRenderer.Rendered rendered = render(goldenEnvelope());
        assertEquals(1, rendered.handles().size());
        ParsedDocumentPromptRenderer.DocumentHandle handle = rendered.handles().getFirst();
        assertEquals("doc-1", handle.handle());
        assertEquals(DOCUMENT, handle.documentId());
        assertEquals("PAYSTUB", handle.documentTypeCode());
        assertEquals(java.util.Set.of("doc-1"), rendered.handleIds());
        assertThrows(UnsupportedOperationException.class,
                () -> rendered.handles().add(handle));
    }

    // ------------------------------------------------------------------ value fidelity

    @Test
    void normalizedNumbersKeepTheirExactDecimalText() {
        BigDecimal exact = new BigDecimal("12345678901234567890.123456789");
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "W2", 1, List.of(PAGE),
                List.of(field("wages.box1", null, FieldStatus.FOUND, "MONEY",
                        "12,345,678,901,234,567,890.123456789",
                        "12345678901234567890.123456789",
                        new NormalizedValue(null, exact, null, null),
                        "VALIDATED", false, List.of())));
        String text = render(envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of()))
                .text();

        assertTrue(text.contains("normalized.number=12345678901234567890.123456789"), text);
        assertFalse(text.contains("E+"), text);
        assertFalse(text.contains("1.2345678901234567E19"), text);
    }

    @Test
    void trailingZeroScaleIsPreservedRatherThanNormalized() {
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                List.of(field("pay.rate", null, FieldStatus.FOUND, "MONEY", "25.50", "25.50",
                        new NormalizedValue(null, new BigDecimal("25.5000"), null, null),
                        "VALIDATED", false, List.of())));
        assertTrue(render(envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of()))
                .text().contains("normalized.number=25.5000"));
    }

    @Test
    void normalizedDateAndJsonArmsAreRenderedInIsoAndCanonicalForm() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("periodEnd", "2026-03-31");
        tree.put("units", new BigDecimal("40.00"));
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                List.of(
                        field("pay.periodEnd", null, FieldStatus.FOUND, "DATE",
                                "03/31/2026", "03/31/2026",
                                new NormalizedValue(null, null,
                                        java.time.LocalDate.of(2026, 3, 31), null),
                                "VALIDATED", false, List.of()),
                        field("pay.detail", null, FieldStatus.FOUND, "JSON", "detail", "detail",
                                new NormalizedValue(null, null, null, tree),
                                "VALIDATED", false, List.of())));
        String text = render(envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of()))
                .text();

        assertTrue(text.contains("normalized.date=2026-03-31"), text);
        assertTrue(text.contains("normalized.json={\"periodEnd\":\"2026-03-31\",\"units\":40.00}"),
                text);
    }

    @Test
    void sensitiveOccurrencesAreRenderedRedactedButStillPresent() {
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "W2", 1, List.of(PAGE),
                List.of(field("employee.taxId", null, FieldStatus.FOUND, "TEXT",
                        "000-00-0000", "000000000",
                        new NormalizedValue("000000000", null, null, null),
                        "VALIDATED", true, List.of())));
        String text = render(envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of()))
                .text();

        assertTrue(text.contains("name=employee.taxId"), text);
        assertTrue(text.contains("sensitive=true"), text);
        assertTrue(text.contains("value=<redacted:sensitive>"), text);
        assertFalse(text.contains("000-00-0000"), text);
        assertFalse(text.contains("000000000"), text);
    }

    @Test
    void groupKeysAreRenderedPerOccurrenceAndDistinguishNullFromLetters() {
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "SCHEDULE_E", 1, List.of(PAGE),
                List.of(
                        field("rent.gross", "A", FieldStatus.FOUND, "MONEY", "1", "1",
                                new NormalizedValue(null, BigDecimal.ONE, null, null),
                                "VALIDATED", false, List.of()),
                        field("rent.gross", "B", FieldStatus.FOUND, "MONEY", "2", "2",
                                new NormalizedValue(null, new BigDecimal("2"), null, null),
                                "VALIDATED", false, List.of()),
                        field("owner.name", null, FieldStatus.FOUND, "TEXT", "N", "N",
                                new NormalizedValue("N", null, null, null),
                                "VALIDATED", false, List.of())));
        String text = render(envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of()))
                .text();

        assertTrue(text.contains("name=rent.gross group=A"), text);
        assertTrue(text.contains("name=rent.gross group=B"), text);
        assertTrue(text.contains("name=owner.name group=-"), text);
    }

    @Test
    void pageRotationIsRenderedAndFlaggedWhenNonZero() {
        EngineResultEnvelope envelope = envelope(
                List.of(page(PAGE, 0, 270, classification()), page(PAGE_TWO, 1, 0, null)),
                List.of(new LogicalDocument(DOCUMENT, "PAYSTUB", 1, List.of(PAGE, PAGE_TWO),
                        List.of(field("a", null, FieldStatus.MISSING, "TEXT", null, null, null,
                                "NOT_VALIDATED", false, List.of())))),
                List.of());
        String text = render(envelope).text();

        assertTrue(text.contains("P1 source=S1 sourcePageIndex=0"), text);
        assertTrue(text.contains("rotation=270"), text);
        assertTrue(text.contains("NOTE: page P1 is rotated 270 degrees"), text);
        assertTrue(text.contains("P2 source=S1 sourcePageIndex=1"), text);
        assertTrue(text.contains("pages=[P1,P2]"), text);
    }

    @Test
    void unassignedPagesAreListedRatherThanSilentlyDropped() {
        EngineResultEnvelope envelope = envelope(
                List.of(page(PAGE, 0, 0, classification()), page(PAGE_TWO, 1, 0, null)),
                List.of(new LogicalDocument(DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                        List.of(field("a", null, FieldStatus.MISSING, "TEXT", null, null, null,
                                "NOT_VALIDATED", false, List.of())))),
                List.of(PAGE_TWO));
        assertTrue(render(envelope).text().contains("UNASSIGNED PAGES: P2"));
    }

    @Test
    void reviewRequiredAndUnsupportedDocumentWarningsReachThePrompt() {
        EngineResultEnvelope envelope = envelope(
                List.of(page(PAGE, 0, 0, classification())),
                List.of(
                        new LogicalDocument(DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                                List.of(field("wages.ytd", null, FieldStatus.FOUND, "MONEY",
                                        "1.00", "1.00",
                                        new NormalizedValue(null, new BigDecimal("1.00"), null, null),
                                        "MANUAL_REVIEW_REQUIRED", false, List.of()))),
                        new LogicalDocument(UUID.fromString("77777777-7777-4777-8777-777777777771"),
                                "BANK_STATEMENT", 2, List.of(PAGE), List.of())),
                List.of());
        String text = render(envelope).text();

        assertTrue(text.contains("PARSE WARNINGS (2)"), text);
        assertTrue(text.contains("FIELD_REVIEW_REQUIRED doc-1 type=PAYSTUB name=wages.ytd group=-"),
                text);
        assertTrue(text.contains("UNSUPPORTED_DOCUMENT_IGNORED doc-2 type=BANK_STATEMENT"), text);
    }

    @Test
    void theGeneralizedOverloadRendersByteIdenticalText() {
        // Both warning codes plus an ignored document, so this exercises every path where the
        // decision type reaches the rendered text.
        EngineResultEnvelope envelope = envelope(
                List.of(page(PAGE, 0, 0, classification())),
                List.of(
                        new LogicalDocument(DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                                List.of(field("wages.ytd", null, FieldStatus.FOUND, "MONEY",
                                        "1.00", "1.00",
                                        new NormalizedValue(null, new BigDecimal("1.00"), null, null),
                                        "MANUAL_REVIEW_REQUIRED", false, List.of()))),
                        new LogicalDocument(UUID.fromString("77777777-7777-4777-8777-777777777771"),
                                "BANK_STATEMENT", 2, List.of(PAGE), List.of())),
                List.of());

        ParsedDocumentPromptRenderer.Rendered viaIncome =
                renderer.render(envelope, compatibility.evaluate(envelope));
        ParsedDocumentPromptRenderer.Rendered viaGeneral = renderer.render(envelope,
                new ParsedDataCompatibilityService().evaluate(envelope, incomeShapedPolicy()));

        // The rendered block is hashed into the analyzer's prompt_sha256, so "equivalent" is not
        // good enough here: generalizing the decision type must not move a single byte.
        assertEquals(viaIncome.text(), viaGeneral.text());
        assertEquals(viaIncome.handleIds(), viaGeneral.handleIds());
    }

    /** Income expressed as a generalized contract: warn, preserve, one document, nothing required. */
    private static ParsedDataCompatibilityService.Policy incomeShapedPolicy() {
        return new ParsedDataCompatibilityService.Policy(
                IncomeEnvelopeCompatibility.SUPPORTED_ENVELOPE_VERSIONS,
                IncomeEnvelopeCompatibility.SUPPORTED_CANONICALIZATION_VERSIONS,
                IncomeEnvelopeCompatibility.SUPPORTED_DOCUMENT_TYPES,
                Set.of(),
                1,
                IncomeEnvelopeCompatibility.REVIEW_WARNING_VALIDATION_STATUSES,
                InstanceReleaseManifest.ReviewPolicy.WARN,
                InstanceReleaseManifest.MissingFieldPolicy.PRESERVE);
    }

    // ------------------------------------------------------------------ forbidden content

    @Test
    void aForbiddenMemberSmuggledIntoAFreeFormJsonArmIsRefused() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("storageKey", "s3://bucket/object");
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                List.of(field("pay.detail", null, FieldStatus.FOUND, "JSON", "d", "d",
                        new NormalizedValue(null, null, null, tree),
                        "VALIDATED", false, List.of())));
        EngineResultEnvelope envelope =
                envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of());

        LabContractException failure = assertThrows(LabContractException.class,
                () -> render(envelope));
        assertEquals(LabContractException.Code.ENVELOPE_FORBIDDEN_MEMBER, failure.code());
        assertFalse(failure.getMessage().contains("s3://"));
    }

    @Test
    void aNestedForbiddenMemberIsRefusedRegardlessOfCasing() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("OriginalFileName", "anything.pdf");
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("meta", List.of(nested));
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                List.of(field("pay.detail", null, FieldStatus.FOUND, "JSON", "d", "d",
                        new NormalizedValue(null, null, null, tree),
                        "VALIDATED", false, List.of())));
        EngineResultEnvelope envelope =
                envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of());

        assertEquals(LabContractException.Code.ENVELOPE_FORBIDDEN_MEMBER,
                assertThrows(LabContractException.class, () -> render(envelope)).code());
    }

    @Test
    void noForbiddenMemberNameAppearsAnywhereInARenderedPrompt() {
        String text = render(goldenEnvelope()).text().toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("filename", "storagekey", "s3key", "presignedurl",
                "rawtext", "pagetext", "ocrtext", "reviewedby", "correctedvalue", "http://",
                "https://")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
    }

    @Test
    void evidenceNamingAPageOutsideTheEnvelopeIsRefused() {
        LogicalDocument document = new LogicalDocument(
                DOCUMENT, "PAYSTUB", 1, List.of(PAGE),
                List.of(field("a", null, FieldStatus.FOUND, "TEXT", "x", "x",
                        new NormalizedValue("x", null, null, null), "VALIDATED", false,
                        List.of(evidence(PAGE_TWO)))));
        EngineResultEnvelope envelope =
                envelope(List.of(page(PAGE, 0, 0, null)), List.of(document), List.of());

        assertEquals(LabContractException.Code.PAGE_MEMBERSHIP_VIOLATION,
                assertThrows(LabContractException.class, () -> render(envelope)).code());
    }
    // ------------------------------------------------------------------ classification provenance

    @Test
    void labelsCoQualifyingTypesOnARuleAnchorPage() {
        PageClassification cls = new PageClassification(
                "TAX_RETURN",
                new BigDecimal("0.91"),
                "RULE_ANCHOR",
                "3.1.0",
                new RuleAnchorEvidence(List.of(), List.of(), List.of("SCHEDULE_C", "SCHEDULE_E")));
        String text = render(envelope(List.of(page(PAGE, 0, 0, cls)), List.of(), List.of(PAGE)))
                .text();
        assertTrue(text.contains(
                " classification=TAX_RETURN confidence=0.91 method=RULE_ANCHOR"
                        + " coQualifyingTypes=[SCHEDULE_C,SCHEDULE_E]"), text);
    }

    @Test
    void labelsAModelClassifiedPageAsWeakerProvenance() {
        PageClassification cls = new PageClassification(
                "PAYSTUB",
                new BigDecimal("0.83"),
                "LLM",
                null,
                new LlmEvidence(
                        null,
                        List.of(77L),
                        "anthropic/claude-sonnet-5",
                        new EngineResultEnvelope.CharacterOffsets(0, 24),
                        "page-classify.v3",
                        "LLM_FALLBACK"));
        String text = render(envelope(List.of(page(PAGE, 0, 0, cls)), List.of(), List.of(PAGE)))
                .text();
        assertTrue(text.contains(
                " classification=PAYSTUB confidence=0.83 method=LLM"
                        + " model=anthropic/claude-sonnet-5 promptVersion=page-classify.v3"
                        + " (model-typed page: weaker provenance than a rule-anchor match)"), text);
    }
}
