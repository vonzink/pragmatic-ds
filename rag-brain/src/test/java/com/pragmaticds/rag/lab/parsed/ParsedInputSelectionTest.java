package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SourceFile;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;
import com.pragmaticds.rag.lab.parsed.ParsedInputSelection.FailureCode;
import com.pragmaticds.rag.lab.parsed.ParsedInputSelection.SelectionException;
import com.pragmaticds.rag.lab.parsed.ParsedInputSelection.SelectionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Narrowing a verified parse to part of its sources.
 *
 * <p>The refusals carry the weight here. Every one of them exists because the alternative is
 * silently analyzing something the caller did not select, or citing evidence that no longer
 * exists in the narrowed envelope.
 */
class ParsedInputSelectionTest {

    private static final UUID SOURCE_A = UUID.fromString("11111111-1111-4111-8111-11111111110a");
    private static final UUID SOURCE_B = UUID.fromString("11111111-1111-4111-8111-11111111110b");
    private static final UUID PAGE_A = UUID.fromString("22222222-2222-4222-8222-22222222220a");
    private static final UUID PAGE_B = UUID.fromString("22222222-2222-4222-8222-22222222220b");
    private static final UUID SCHEMA = UUID.fromString("66666666-6666-4666-8666-666666666660");
    private static final String SOURCE_SET = "b2".repeat(32);

    @Test
    void narrowingKeepsTheSelectedParseAndTheWholePackageIdentity() {
        EngineResultEnvelope full = envelope(
                List.of(document(0, "PAYSTUB", PAGE_A), document(1, "W2", PAGE_B)));

        SelectionResult result = ParsedInputSelection.select(full, List.of(SOURCE_A));

        EngineResultEnvelope narrowed = result.selectedEnvelope();
        assertEquals(List.of(SOURCE_A),
                narrowed.sources().stream().map(SourceFile::id).toList());
        assertEquals(List.of(PAGE_A), narrowed.pages().stream().map(EnginePage::id).toList());
        assertEquals(List.of("PAYSTUB"),
                narrowed.documents().stream().map(LogicalDocument::documentTypeCode).toList());

        // Identity is the whole package's, not the subset's: a run narrowed to one source is still
        // pinned to the same immutable parse.
        assertEquals(full.artifact(), narrowed.artifact());
        assertEquals(full.packageId(), narrowed.packageId());
        assertEquals(full.generation(), narrowed.generation());
        assertEquals(full.provenance(), narrowed.provenance());
        assertEquals(SOURCE_SET, result.sourceSetSha256());

        assertEquals(List.of(SOURCE_A),
                result.selectedSources().stream()
                        .map(ParsedInputSelection.SelectedSource::sourceId).toList());
        assertEquals("a1".repeat(32), result.selectedSources().getFirst().contentSha256());
        assertEquals(0, result.selectedSources().getFirst().position());
    }

    @Test
    void selectionOrderIsTheParsesOwnOrderNotTheCallers() {
        EngineResultEnvelope full = envelope(
                List.of(document(0, "PAYSTUB", PAGE_A), document(1, "W2", PAGE_B)));

        SelectionResult forward = ParsedInputSelection.select(full, List.of(SOURCE_A, SOURCE_B));
        SelectionResult reversed = ParsedInputSelection.select(full, List.of(SOURCE_B, SOURCE_A));

        // The same selection expressed two ways must produce byte-identical child rows.
        assertEquals(forward.selectedSources(), reversed.selectedSources());
        assertEquals(List.of(SOURCE_A, SOURCE_B), forward.selectedSources().stream()
                .map(ParsedInputSelection.SelectedSource::sourceId).toList());
    }

    @Test
    void aSourceTheParseDoesNotContainIsRefused() {
        EngineResultEnvelope full = envelope(List.of(document(0, "PAYSTUB", PAGE_A)));

        assertEquals(FailureCode.SELECTION_SOURCE_NOT_IN_PARSE, assertThrows(
                SelectionException.class,
                () -> ParsedInputSelection.select(full, List.of(UUID.randomUUID()))).code());
    }

    @Test
    void aDocumentCannotBeSplitAcrossTheSelectionBoundary() {
        // One logical document spanning both sources: keeping it would analyze a page the caller
        // did not select, and dropping it would silently discard a document the selection reached.
        EngineResultEnvelope full = envelope(
                List.of(document(0, "TAX_RETURN", PAGE_A, PAGE_B)));

        assertEquals(FailureCode.SELECTION_SPLITS_DOCUMENT, assertThrows(
                SelectionException.class,
                () -> ParsedInputSelection.select(full, List.of(SOURCE_A))).code());
    }

    @Test
    void evidencePointingAtARemovedPageIsRefused() {
        // The document sits entirely on the kept source, but one field cites the dropped one.
        FieldOccurrence citesDroppedPage = new FieldOccurrence(
                "grossPay", "current", FieldStatus.FOUND, "MONEY", "1,234.50", "1,234.50", null,
                new SchemaRef(SCHEMA, "2024.1"), "ANCHOR_LABEL", "5.1.0", new BigDecimal("0.97"),
                null, "VALIDATED", false,
                List.of(new EvidenceSpan(PAGE_B, null, null, "VALUE", 0, box())));
        EngineResultEnvelope full = envelope(List.of(new LogicalDocument(
                new UUID(0x5555, 0), "PAYSTUB", 0, List.of(PAGE_A), List.of(citesDroppedPage))));

        assertEquals(FailureCode.SELECTION_BREAKS_EVIDENCE, assertThrows(
                SelectionException.class,
                () -> ParsedInputSelection.select(full, List.of(SOURCE_A))).code());
    }

    @Test
    void aSelectionThatReachesNoDocumentIsRefused() {
        EngineResultEnvelope full = envelope(List.of(document(0, "PAYSTUB", PAGE_A)));

        assertEquals(FailureCode.SELECTION_SELECTS_NO_DOCUMENT, assertThrows(
                SelectionException.class,
                () -> ParsedInputSelection.select(full, List.of(SOURCE_B))).code());
    }

    @Test
    void anEmptyOrRepeatedSelectionIsRefusedBeforeAnythingElse() {
        EngineResultEnvelope full = envelope(List.of(document(0, "PAYSTUB", PAGE_A)));

        assertEquals(FailureCode.SELECTION_EMPTY, assertThrows(SelectionException.class,
                () -> ParsedInputSelection.select(full, List.of())).code());
        assertEquals(FailureCode.SELECTION_DUPLICATE_SOURCE, assertThrows(SelectionException.class,
                () -> ParsedInputSelection.select(full, List.of(SOURCE_A, SOURCE_A))).code());
    }

    // ---------------------------------------------------------------- fixtures

    private static EngineResultEnvelope.Box box() {
        return new EngineResultEnvelope.Box(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static LogicalDocument document(int ordinal, String typeCode, UUID... pageIds) {
        return new LogicalDocument(
                new UUID(0x5555, ordinal), typeCode, ordinal, List.of(pageIds), List.of());
    }

    private static EngineResultEnvelope envelope(List<LogicalDocument> documents) {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                new Generation(UUID.fromString("44444444-4444-4444-8444-444444444444"), 1, 1,
                        SOURCE_SET, "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE_A, 0, "a1".repeat(32), 48211L, "application/pdf"),
                        new SourceFile(SOURCE_B, 1, "a2".repeat(32), 51200L, "application/pdf")),
                List.of(page(PAGE_A, SOURCE_A, 0), page(PAGE_B, SOURCE_B, 1)),
                documents,
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    private static EnginePage page(UUID id, UUID sourceId, int packageIndex) {
        return new EnginePage(id, sourceId, 0, packageIndex, new BigDecimal("612"),
                new BigDecimal("792"), 0, null, "NATIVE", false, false, null);
    }
}
