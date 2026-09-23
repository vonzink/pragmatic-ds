package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.domain.LabDocumentRegistration;
import com.pragmaticds.rag.lab.domain.LabEnginePackageBinding;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SourceFile;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver.ExistingParseRequest;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver.ParsedDataException;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver.VerifiedParsedInput;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabDocumentRegistrationRepository;
import com.pragmaticds.rag.lab.repository.LabDocumentRegistrationSourceRepository;
import com.pragmaticds.rag.lab.repository.LabEnginePackageBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verification of a caller's claim about an existing parse.
 *
 * <p>The claim is data, not authority. These tests pin that the engine's descriptor decides what
 * the revision is, that the exact returned bytes must match it, and that nothing durable is
 * written until both hold.
 */
class ParsedDataResolverTest {

    private static final UUID BRAIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID OTHER_BRAIN = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID PACKAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SOURCE_A = UUID.fromString("11111111-1111-4111-8111-11111111110a");
    private static final UUID SOURCE_B = UUID.fromString("11111111-1111-4111-8111-11111111110b");
    private static final UUID PAGE_A = UUID.fromString("22222222-2222-4222-8222-22222222220a");
    private static final UUID PAGE_B = UUID.fromString("22222222-2222-4222-8222-22222222220b");
    private static final UUID REGISTRATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String SOURCE_SET = "b2".repeat(32);
    private static final String INCOME = "income";

    private DocumentEngineClient engine;
    private ObjectProvider<DocumentEngineClient> engineProvider;
    private LabEnginePackageBindingRepository bindings;
    private LabDocumentRegistrationRepository registrations;
    private LabDocumentRegistrationSourceRepository registrationSources;
    private ParsedDataResolver resolver;

    private final EngineArtifactDescriptor artifact =
            EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        engine = mock(DocumentEngineClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DocumentEngineClient> provider = mock(ObjectProvider.class);
        engineProvider = provider;
        when(engineProvider.getIfAvailable()).thenReturn(engine);
        bindings = mock(LabEnginePackageBindingRepository.class);
        registrations = mock(LabDocumentRegistrationRepository.class);
        registrationSources = mock(LabDocumentRegistrationSourceRepository.class);
        // A pass-through transaction manager: the resolver only wraps its durable writes, and
        // these tests are about what it writes, not about how the write is bounded.
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        resolver = new DefaultParsedDataResolver(engineProvider, bindings, registrations,
                registrationSources, new ParsedDataCompatibilityService(), transactions);

        when(bindings.findById(PACKAGE)).thenReturn(Optional.empty());
        when(registrations.findByBrainIdAndInstanceSlugAndEnginePackageIdAndSelectedRevision(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(registrations.saveAndFlush(any())).thenAnswer(call -> {
            LabDocumentRegistration saved = call.getArgument(0);
            saved.setId(REGISTRATION);
            return saved;
        });
    }

    @Test
    void aVerifiedSelectionIsPinnedToTheParseItWasCheckedAgainst() {
        engineReturns(descriptor(3), envelope());

        VerifiedParsedInput input = resolver.selectExisting(
                new ExistingParseRequest(BRAIN, INCOME, PACKAGE, 3, List.of(SOURCE_A)),
                contract(Set.of("PAYSTUB"), Set.of(), 1));

        assertEquals(REGISTRATION, input.registrationId());
        assertEquals(3, input.revision());
        assertEquals(JOB, input.processingJobId());
        assertEquals(artifact.sha256(), input.envelopeSha256());
        assertEquals(SOURCE_SET, input.sourceSetSha256());
        assertEquals(List.of(SOURCE_A), input.selectedSourceIds());
        assertTrue(input.compatibility().compatible());

        // The whole parse is retained for provenance while analysis sees only the selection.
        assertEquals(2, input.envelope().sources().size());
        assertEquals(1, input.selectedEnvelope().sources().size());

        verify(bindings).saveAndFlush(any(LabEnginePackageBinding.class));
        verify(registrationSources).saveAll(any());
    }

    @Test
    void aPackageOwnedByAnotherBrainIsRefusedBeforeAnyEngineRead() {
        when(bindings.findById(PACKAGE))
                .thenReturn(Optional.of(new LabEnginePackageBinding(PACKAGE, OTHER_BRAIN)));

        assertEquals(ParsedDataException.Code.PARSE_SCOPE_MISMATCH, assertThrows(
                ParsedDataException.class,
                () -> resolver.selectExisting(
                        new ExistingParseRequest(BRAIN, INCOME, PACKAGE, 3, List.of(SOURCE_A)),
                        contract(Set.of("PAYSTUB"), Set.of(), 1))).code());

        // Cross-brain refusal must cost nothing: no history read, no envelope fetch.
        verifyNoInteractions(engine);
    }

    @Test
    void aRevisionTheEngineDoesNotHaveIsNotInvented() {
        when(engine.revisionHistory(PACKAGE)).thenReturn(List.of(descriptor(2)));

        assertEquals(ParsedDataException.Code.PARSE_REVISION_NOT_FOUND, assertThrows(
                ParsedDataException.class,
                () -> resolver.selectExisting(
                        new ExistingParseRequest(BRAIN, INCOME, PACKAGE, 3, List.of(SOURCE_A)),
                        contract(Set.of("PAYSTUB"), Set.of(), 1))).code());
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    void bytesThatDoNotMatchTheDescriptorWriteNothing() {
        // The descriptor claims a different parse generation than the envelope actually carries.
        DocumentEngineClient.RevisionDescriptor mismatched = new DocumentEngineClient.RevisionDescriptor(
                3, JOB, 9, 1, "1.0.0", SOURCE_SET, "d4".repeat(32), artifact.sha256(),
                artifact.byteCount(), "REUSABLE", Instant.parse("2026-01-01T00:00:00Z"));
        engineReturns(mismatched, envelope());

        assertEquals(ParsedDataException.Code.PARSE_DESCRIPTOR_MISMATCH, assertThrows(
                ParsedDataException.class,
                () -> resolver.selectExisting(
                        new ExistingParseRequest(BRAIN, INCOME, PACKAGE, 3, List.of(SOURCE_A)),
                        contract(Set.of("PAYSTUB"), Set.of(), 1))).code());

        verify(bindings, never()).saveAndFlush(any());
        verify(registrations, never()).saveAndFlush(any());
        verify(registrationSources, never()).saveAll(any());
    }

    @Test
    void aSourceOutsideTheParseWritesNothing() {
        engineReturns(descriptor(3), envelope());

        assertEquals(ParsedDataException.Code.PARSE_SCOPE_MISMATCH, assertThrows(
                ParsedDataException.class,
                () -> resolver.selectExisting(
                        new ExistingParseRequest(
                                BRAIN, INCOME, PACKAGE, 3, List.of(UUID.randomUUID())),
                        contract(Set.of("PAYSTUB"), Set.of(), 1))).code());

        verify(bindings, never()).saveAndFlush(any());
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    void reselectingTheSameRevisionAdoptsTheRegistrationThatExists() {
        engineReturns(descriptor(3), envelope());
        LabDocumentRegistration existing = new LabDocumentRegistration();
        existing.setId(REGISTRATION);
        existing.setBrainId(BRAIN);
        existing.setInstanceSlug(INCOME);
        existing.setEnginePackageId(PACKAGE);
        existing.setSelectedRevision(3);
        when(registrations.findByBrainIdAndInstanceSlugAndEnginePackageIdAndSelectedRevision(
                BRAIN, INCOME, PACKAGE, 3)).thenReturn(Optional.of(existing));

        VerifiedParsedInput input = resolver.selectExisting(
                new ExistingParseRequest(BRAIN, INCOME, PACKAGE, 3, List.of(SOURCE_A)),
                contract(Set.of("PAYSTUB"), Set.of(), 1));

        assertEquals(REGISTRATION, input.registrationId());
        verify(registrations, never()).saveAndFlush(any());
        verify(registrationSources, never()).saveAll(any());
    }

    @Test
    void anIncompatibleParseStillResolvesAndReportsWhyRatherThanThrowing() {
        engineReturns(descriptor(3), envelope());

        VerifiedParsedInput input = resolver.selectExisting(
                new ExistingParseRequest(BRAIN, INCOME, PACKAGE, 3, List.of(SOURCE_A)),
                contract(Set.of("BANK_STATEMENT"), Set.of(), 1));

        // A rejection keeps a value-free registration for review; execution is what refuses.
        assertFalse(input.compatibility().compatible());
        assertEquals(ParsedDataCompatibilityService.RejectionCode.NO_SUPPORTED_DOCUMENT,
                input.compatibility().rejection());
        assertEquals(REGISTRATION, input.registrationId());
    }

    @Test
    void withoutAConfiguredEngineNothingCanBeVerifiedSoNothingIsWritten() {
        // The admin surface loads with the Lab disabled, which is when no engine client exists.
        // A parsed-input request must then fail closed rather than the context refusing to start.
        when(engineProvider.getIfAvailable()).thenReturn(null);

        assertEquals(ParsedDataException.Code.PARSE_ENGINE_UNAVAILABLE, assertThrows(
                ParsedDataException.class,
                () -> resolver.selectExisting(
                        new ExistingParseRequest(BRAIN, INCOME, PACKAGE, 3, List.of(SOURCE_A)),
                        contract(Set.of("PAYSTUB"), Set.of(), 1))).code());

        verify(registrations, never()).saveAndFlush(any());
        verify(bindings, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- fixtures

    private void engineReturns(
            DocumentEngineClient.RevisionDescriptor descriptor, EngineResultEnvelope envelope) {
        when(engine.revisionHistory(PACKAGE)).thenReturn(List.of(descriptor));
        when(engine.envelopeRevision(PACKAGE, descriptor.revision()))
                .thenReturn(new DocumentEngineClient.VerifiedEnvelope(
                        artifact, envelope, descriptor.revision()));
    }

    private DocumentEngineClient.RevisionDescriptor descriptor(int revision) {
        return new DocumentEngineClient.RevisionDescriptor(
                revision, JOB, 1, 1, "1.0.0", SOURCE_SET, "d4".repeat(32),
                artifact.sha256(), artifact.byteCount(), "REUSABLE",
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static InstanceReleaseManifest.ParsedDataContract contract(
            Set<String> allowed, Set<String> requireAny, int minimum) {
        return new InstanceReleaseManifest.ParsedDataContract(
                "1.0.0", "DOCENGINE-C14N-1", allowed, requireAny, minimum,
                InstanceReleaseManifest.ReviewPolicy.WARN,
                InstanceReleaseManifest.MissingFieldPolicy.PRESERVE);
    }

    private static EngineResultEnvelope envelope() {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                PACKAGE,
                new Generation(JOB, 1, 1, SOURCE_SET, "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE_A, 0, "a1".repeat(32), 48211L, "application/pdf"),
                        new SourceFile(SOURCE_B, 1, "a2".repeat(32), 51200L, "application/pdf")),
                List.of(page(PAGE_A, SOURCE_A, 0), page(PAGE_B, SOURCE_B, 1)),
                List.of(new LogicalDocument(new UUID(0x5555, 0), "PAYSTUB", 0,
                                List.of(PAGE_A), List.of()),
                        new LogicalDocument(new UUID(0x5555, 1), "W2", 1,
                                List.of(PAGE_B), List.of())),
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
