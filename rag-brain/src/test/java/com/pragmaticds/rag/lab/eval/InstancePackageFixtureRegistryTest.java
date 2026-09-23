package com.pragmaticds.rag.lab.eval;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.eval.InstancePackageFixtureRegistry.PackageFixture;
import com.pragmaticds.rag.lab.eval.InstancePackageFixtureRegistry.PackageFixtureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fixture must be a parse the engine could actually have produced.
 *
 * <p>The dangerous failure here is not a fixture that fails to load — it is one that loads while
 * being internally impossible. A document referencing a page that does not exist still renders
 * <em>something</em>, and the gate would then judge a release against a parse no engine would ever
 * emit, while recording a verdict that says otherwise.
 */
class InstancePackageFixtureRegistryTest {

    private final InstancePackageFixtureRegistry registry = new InstancePackageFixtureRegistry();

    @Test
    void theShippedFixtureSatisfiesTheSameParserARealEngineResponseGoesThrough() {
        PackageFixture fixture = registry.require("fixture-paystub-single");

        // Loaded through EngineEnvelopeParser, not a lenient object mapper: whatever that parser
        // demands of a genuine envelope, this file satisfied.
        assertEquals("fixture-paystub-single", fixture.name());
        assertEquals("1.0.0", fixture.envelope().envelopeVersion());
        assertEquals("DOCENGINE-C14N-1", fixture.envelope().canonicalizationVersion());
        assertEquals(1, fixture.sourceIds().size());
    }

    @Test
    void theArtifactDigestIsTakenOverTheBytesThatWereActuallyRead() {
        PackageFixture fixture = registry.require("fixture-paystub-single");

        assertTrue(fixture.artifact().sha256().matches("[0-9a-f]{64}"));
        assertTrue(fixture.artifact().byteCount() > 0);
    }

    @Test
    void theDeclaredParseDigestIsDerivedFromTheFixturesOwnContent() {
        PackageFixture fixture = registry.require("fixture-paystub-single");

        // Declared in the file, because the engine stamps it into a real envelope — but checked
        // here rather than believed. A digest unrelated to the content would be exactly the
        // invented content hash this system refuses everywhere else, and every provenance record
        // downstream of an evaluation would inherit it.
        assertEquals(InstancePackageFixtureRegistry.sourceSetDigest(fixture.envelope()),
                fixture.envelope().generation().sourceSetSha256());
    }

    @Test
    void everyPageBelongsToASourceAndEveryDocumentPageExists() {
        EngineResultEnvelope envelope = registry.require("fixture-paystub-single").envelope();

        Set<java.util.UUID> sourceIds = envelope.sources().stream()
                .map(EngineResultEnvelope.SourceFile::id).collect(Collectors.toSet());
        Set<java.util.UUID> pageIds = envelope.pages().stream()
                .map(EngineResultEnvelope.EnginePage::id).collect(Collectors.toSet());

        assertTrue(envelope.pages().stream()
                .allMatch(page -> sourceIds.contains(page.sourceFileId())));
        for (EngineResultEnvelope.LogicalDocument document : envelope.documents()) {
            assertFalse(document.pageIds().isEmpty());
            assertTrue(pageIds.containsAll(document.pageIds()));
        }
    }

    @Test
    void theFixtureCarriesFoundFieldsSoAnAnalyzerHasSomethingToGround() {
        EngineResultEnvelope envelope = registry.require("fixture-paystub-single").envelope();
        EngineResultEnvelope.LogicalDocument paystub = envelope.documents().get(0);

        // A fixture of nothing but MISSING fields cannot exercise the positive scenarios: the
        // analyzer would have no borrower value to report and no evidence to cite, so a case
        // requiring facts would fail for the fixture's reasons rather than the release's.
        assertEquals("PAYSTUB", paystub.documentTypeCode());
        assertTrue(paystub.fields().stream().anyMatch(field ->
                field.status() == EngineResultEnvelope.FieldStatus.FOUND));
        assertTrue(paystub.fields().stream()
                .filter(field -> field.status() == EngineResultEnvelope.FieldStatus.FOUND)
                .allMatch(field -> field.normalized() != null),
                "a FOUND occurrence must carry a normalized value");
    }

    @Test
    void everyEvidenceSpanPointsAtAPageThatExists() {
        EngineResultEnvelope envelope = registry.require("fixture-paystub-single").envelope();
        Set<java.util.UUID> pageIds = envelope.pages().stream()
                .map(EngineResultEnvelope.EnginePage::id).collect(Collectors.toSet());

        // The quietest way a fixture goes wrong: a field cites a page nobody has, and the rendered
        // handle set no longer matches what a citation may name.
        for (EngineResultEnvelope.LogicalDocument document : envelope.documents()) {
            for (EngineResultEnvelope.FieldOccurrence field : document.fields()) {
                for (EngineResultEnvelope.EvidenceSpan span : field.evidence()) {
                    assertTrue(pageIds.contains(span.pageId()),
                            "evidence must point at a page in this parse");
                }
            }
        }
    }

    /** Every name the registry ships, so a fixture cannot be added without being parsed here. */
    private static final String[] SHIPPED = {
            "fixture-paystub-single", "fixture-paystub-w2",
            "fixture-paystub-unreadable", "fixture-unsupported-only",
            "fixture-bank-statement-single"};

    @ParameterizedTest
    @ValueSource(strings = {"fixture-paystub-single", "fixture-paystub-w2",
            "fixture-paystub-unreadable", "fixture-unsupported-only",
            "fixture-bank-statement-single"})
    void everyShippedFixtureLoadsAndDeclaresADigestDerivedFromItsOwnContent(String name) {
        PackageFixture fixture = registry.require(name);

        assertEquals(name, fixture.name());
        assertEquals("1.0.0", fixture.envelope().envelopeVersion());
        assertEquals("DOCENGINE-C14N-1", fixture.envelope().canonicalizationVersion());
        assertTrue(fixture.artifact().sha256().matches("[0-9a-f]{64}"));
        assertEquals(InstancePackageFixtureRegistry.sourceSetDigest(fixture.envelope()),
                fixture.envelope().generation().sourceSetSha256());
    }

    @Test
    void noTwoFixturesShareAnIdentity() {
        // Two parses that reused a package, source, page, or document id would be describable as
        // one another. A scenario naming one fixture would then be indistinguishable from a
        // scenario naming the other in any record keyed by those ids.
        List<UUID> identities = new ArrayList<>();
        for (String name : SHIPPED) {
            EngineResultEnvelope envelope = registry.require(name).envelope();
            identities.add(envelope.packageId());
            identities.add(envelope.generation().processingJobId());
            envelope.sources().forEach(source -> identities.add(source.id()));
            envelope.pages().forEach(page -> identities.add(page.id()));
            envelope.documents().forEach(document -> identities.add(document.id()));
        }

        assertEquals(identities.size(), new HashSet<>(identities).size(),
                "two fixtures share an identifier");
    }

    @Test
    void theTwoDocumentFixtureCarriesBothParsesAndKeepsThemOnTheirOwnSources() {
        EngineResultEnvelope envelope = registry.require("fixture-paystub-w2").envelope();

        assertEquals(2, envelope.sources().size());
        assertEquals(List.of("PAYSTUB", "W2"), envelope.documents().stream()
                .map(EngineResultEnvelope.LogicalDocument::documentTypeCode).toList());

        // A W-2 and a paystub arriving as one PDF is a different parse from two files, and a
        // scenario about corroborating two documents should be given the two-file case.
        Set<UUID> sourcesBehindDocuments = envelope.documents().stream()
                .flatMap(document -> document.pageIds().stream())
                .map(pageId -> envelope.pages().stream()
                        .filter(page -> page.id().equals(pageId)).findFirst().orElseThrow())
                .map(EngineResultEnvelope.EnginePage::sourceFileId)
                .collect(Collectors.toSet());
        assertEquals(2, sourcesBehindDocuments.size());
    }

    @Test
    void theUnsupportedFixtureContainsNothingAnIncomeContractCouldAccept() {
        EngineResultEnvelope envelope = registry.require("fixture-unsupported-only").envelope();

        // This is the whole content of the refusal case. If the fixture ever gained a PAYSTUB or
        // a W2, the release would analyze it, produce a report, and the scenario forbidding that
        // report would start failing for a reason that has nothing to do with the release.
        assertTrue(envelope.documents().stream().noneMatch(document ->
                Set.of("PAYSTUB", "W2").contains(document.documentTypeCode())));
        assertFalse(envelope.documents().isEmpty(),
                "a package with no documents at all would refuse for a different reason");
    }

    @Test
    void theUnreadableFixtureIsASupportedDocumentWithNothingInIt() {
        EngineResultEnvelope envelope = registry.require("fixture-paystub-unreadable").envelope();
        EngineResultEnvelope.LogicalDocument paystub = envelope.documents().get(0);

        // The one fixture that can catch fabrication. It must stay compatible — a supported type,
        // so the release is actually asked the question — while containing no value the answer
        // could legitimately be grounded in. Anything that shows up under findings came from
        // nowhere, which is precisely what the negative scenario forbids.
        assertEquals("PAYSTUB", paystub.documentTypeCode());
        assertFalse(paystub.fields().isEmpty());
        assertTrue(paystub.fields().stream().allMatch(field ->
                field.status() == EngineResultEnvelope.FieldStatus.MISSING),
                "a single FOUND field here would give a fabricated finding something to hide behind");
    }

    @Test
    void aFixtureNobodyShipsIsNotAGateThatCanApproveAnything() {
        assertFalse(registry.has("fixture-invented"));
        assertTrue(registry.find("fixture-invented").isEmpty());
        assertTrue(registry.find(null).isEmpty());
        assertTrue(registry.find("  ").isEmpty());

        assertEquals(PackageFixtureException.Code.FIXTURE_NOT_ALLOWLISTED,
                assertThrows(PackageFixtureException.class,
                        () -> registry.require("fixture-invented")).code());
    }

    @Test
    void aLoadedFixtureIsCachedSoRepeatedScenariosReadOneParse() {
        assertTrue(registry.require("fixture-paystub-single")
                == registry.require("fixture-paystub-single"));
    }
}
