package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstanceReleaseResolverTest {
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID LIVE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final InstanceKey INCOME = new InstanceKey(BRAIN, "income");

    private LabInstanceRepository instances;
    private LabInstancePointerRepository pointers;
    private LabInstanceReleaseRepository releases;
    private VerifiedInstanceReleaseReader reader;
    private InstanceReleaseResolver resolver;

    @BeforeEach
    void setUp() {
        instances = mock(LabInstanceRepository.class);
        pointers = mock(LabInstancePointerRepository.class);
        releases = mock(LabInstanceReleaseRepository.class);
        reader = mock(VerifiedInstanceReleaseReader.class);
        resolver = new DefaultInstanceReleaseResolver(instances, pointers, releases, reader);
        when(instances.findByBrainIdAndSlug(BRAIN, "income")).thenReturn(Optional.of(instance()));
    }

    @Test
    void liveUsesBrainSlugScopedPointerAndDecodesStoredManifest() {
        LabInstanceRelease release = release(LIVE_ID, BRAIN, "income", 2);
        when(pointers.findByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.of(pointer(LIVE_ID)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income")).thenReturn(Optional.of(release));

        ResolvedInstanceRelease resolved = resolver.live(INCOME);

        assertSame(release, resolved.release());
        assertEquals(null, resolved.manifest());
        assertEquals(true, resolved.live());
        verify(releases).findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income");
    }

    @Test
    void liveFailsWhenPointerIsAbsent() {
        when(pointers.findByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.empty());

        InstanceReleaseResolver.ReleaseResolutionException exception = assertThrows(
                InstanceReleaseResolver.ReleaseResolutionException.class, () -> resolver.live(INCOME));

        assertEquals(InstanceReleaseResolver.ReleaseResolutionException.Code.LIVE_RELEASE_NOT_FOUND,
                exception.code());
    }

    @Test
    void liveFailsClosedWhenPointerReleaseIdentityDoesNotMatchRequestedInstance() {
        LabInstanceRelease release = release(LIVE_ID, OTHER_BRAIN, "income", 2);
        when(pointers.findByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.of(pointer(LIVE_ID)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income")).thenReturn(Optional.of(release));

        InstanceReleaseResolver.ReleaseResolutionException exception = assertThrows(
                InstanceReleaseResolver.ReleaseResolutionException.class, () -> resolver.live(INCOME));

        assertEquals(InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_SCOPE_MISMATCH,
                exception.code());
    }

    @Test
    void byIdDoesNotResolveCrossBrainReleaseId() {
        when(releases.findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income")).thenReturn(Optional.empty());

        InstanceReleaseResolver.ReleaseResolutionException exception = assertThrows(
                InstanceReleaseResolver.ReleaseResolutionException.class, () -> resolver.byId(INCOME, LIVE_ID));

        assertEquals(InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_NOT_FOUND,
                exception.code());
    }

    @Test
    void historyReturnsNewestFirstAndMarksOnlyPointerTargetLive() {
        LabInstanceRelease newest = release(UUID.randomUUID(), BRAIN, "income", 3);
        LabInstanceRelease live = release(LIVE_ID, BRAIN, "income", 2);
        when(pointers.findByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.of(pointer(LIVE_ID)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income")).thenReturn(Optional.of(live));
        when(releases.findAllByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(BRAIN, "income"))
                .thenReturn(List.of(newest, live));

        List<ResolvedInstanceRelease> history = resolver.history(INCOME);

        assertEquals(List.of(3, 2), history.stream().map(value -> value.release().getReleaseNumber()).toList());
        assertFalse(history.get(0).live());
        assertEquals(true, history.get(1).live());
    }

    @Test
    void resolverDecodesPersistedShapeV2ThroughTheStrictCodec() {
        LabInstanceRelease release = release(LIVE_ID, BRAIN, "income", 2);
        when(reader.read(release)).thenReturn(new com.pragmaticds.rag.lab.release.DecodedInstanceManifest.V2(v2()));
        when(pointers.findByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.of(pointer(LIVE_ID)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income")).thenReturn(Optional.of(release));

        assertInstanceOf(com.pragmaticds.rag.lab.release.DecodedInstanceManifest.V2.class,
                resolver.live(INCOME).manifest());
    }

    @Test
    void resolverDecodesPersistedShapeLegacyIncomeV1ThroughTheStrictCodec() {
        LabInstanceRelease release = release(LIVE_ID, BRAIN, "income", 1);
        when(reader.read(release)).thenReturn(new com.pragmaticds.rag.lab.release.DecodedInstanceManifest.V1Income(v1()));
        when(pointers.findByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.of(pointer(LIVE_ID)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income")).thenReturn(Optional.of(release));

        assertInstanceOf(com.pragmaticds.rag.lab.release.DecodedInstanceManifest.V1Income.class,
                resolver.live(INCOME).manifest());
    }

    @Test
    void historyRejectsPointerTargetOutsideRequestedScope() {
        when(pointers.findByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.of(pointer(LIVE_ID)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(LIVE_ID, BRAIN, "income")).thenReturn(Optional.empty());

        InstanceReleaseResolver.ReleaseResolutionException error = assertThrows(
                InstanceReleaseResolver.ReleaseResolutionException.class, () -> resolver.history(INCOME));

        assertEquals(InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_SCOPE_MISMATCH, error.code());
    }

    private static InstanceReleaseManifest v2() {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1", Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN, InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("openai", "gpt-test", InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(new InstanceReleaseManifest.CollectionRef(UUID.randomUUID(), 1))),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query", new BigDecimal("0.1")),
                List.of(new InstanceReleaseManifest.ToolContract("income.calculate", "1", "a".repeat(64), "b".repeat(64))),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1, new BigDecimal("1.0")),
                new InstanceReleaseManifest.EvaluationContract("golden", 1, new BigDecimal("0.5")));
    }

    private static LabReleaseManifest v1() {
        return new LabReleaseManifest(LabReleaseManifest.MANIFEST_VERSION, LabManifestWriter.CANONICALIZATION_VERSION,
                "income", "income-v2", new LabReleaseManifest.Pinned(
                new LabReleaseManifest.Analyzer("Income", "v2", "base", "schema", "schema.json", "a".repeat(64)),
                new LabReleaseManifest.Retrieval("income", "income", 4),
                new LabReleaseManifest.EngineContract("application/json", List.of("1.0.0"), List.of("DOCENGINE-C14N-1"), List.of("PAYSTUB"), List.of("WARNING")),
                new LabReleaseManifest.Calculator(List.of("income.calculate"))),
                new LabReleaseManifest.ObservedInference(LabReleaseManifest.SelectionSource.ANALYZE_LANE, "openai", "gpt-test", null, 800, 1,
                        new LabReleaseManifest.ResolutionInputs(null, null, null, null, "openai", "gpt-test", null, null, "openai", "gpt-test", false)),
                new LabReleaseManifest.PrototypeLimitations(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE, LabReleaseManifest.LIVE_DEPENDENCIES));
    }

    private static LabInstance instance() { return new LabInstance(BRAIN, "income", "Income", "Purpose"); }
    private static LabInstancePointer pointer(UUID id) {
        LabInstancePointer pointer = new LabInstancePointer();
        pointer.setBrainId(BRAIN); pointer.setInstanceSlug("income"); pointer.setProductionReleaseId(id); return pointer;
    }
    private static LabInstanceRelease release(UUID id, UUID brain, String slug, int number) {
        LabInstanceRelease release = new LabInstanceRelease();
        release.setId(id); release.setBrainId(brain); release.setInstanceSlug(slug); release.setReleaseNumber(number);
        release.setManifest(Map.of("manifestVersion", "income-lab-v1")); return release;
    }
}
