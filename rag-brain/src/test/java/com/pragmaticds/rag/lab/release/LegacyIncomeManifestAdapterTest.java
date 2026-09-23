package com.pragmaticds.rag.lab.release;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyIncomeManifestAdapterTest {

    private final InstanceManifestCodec codec = InstanceManifestCodec.strict(new LabManifestWriter());
    private final LegacyIncomeManifestAdapter adapter = new LegacyIncomeManifestAdapter();

    @Test
    void decodesTheCurrentIncomeManifestAsReadOnlyV1WithoutRewritingIt() {
        LabReleaseManifest income = currentIncomeManifest();
        Map<String, Object> stored = income.toCanonicalMap();

        DecodedInstanceManifest.V1Income decoded = assertInstanceOf(DecodedInstanceManifest.V1Income.class,
                codec.decode(stored));

        assertEquals(income, decoded.manifest());
        assertEquals(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                decoded.manifest().prototypeLimitations().code());
        assertEquals("1.0.0", ((Map<?, ?>) stored).get("manifestVersion"));
        assertEquals(income.toCanonicalMap(), stored, "v1 reads must not rewrite the stored contract");
        assertFalse(codec.decode(stored) instanceof DecodedInstanceManifest.V2);
    }

    @Test
    void adaptsLegacyIncomeForDisplayButMarksEveryUnpinnedDependencyPromotionIneligible() {
        LegacyIncomeManifestAdapter.LegacyIncomeDisplay display = adapter.adapt(currentIncomeManifest());

        assertEquals("income", display.instanceSlug());
        assertEquals("income-v2", display.analyzerSlug());
        assertTrue(display.liveDependencies().contains(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE));
        assertFalse(display.modelPinned());
        assertFalse(display.corpusPinned());
        assertFalse(display.toolsPinned());
        assertFalse(display.promotionEligible());
        assertThrows(UnsupportedOperationException.class,
                () -> display.liveDependencies().add("changed"));
    }

    private LabReleaseManifest currentIncomeManifest() {
        return new LabReleaseManifest(
                LabReleaseManifest.MANIFEST_VERSION,
                LabManifestWriter.CANONICALIZATION_VERSION,
                "income",
                "income-v2",
                new LabReleaseManifest.Pinned(
                        new LabReleaseManifest.Analyzer("Income", "v2", "base", "schema", "schema.json",
                                "a".repeat(64)),
                        new LabReleaseManifest.Retrieval("income", "income", 4),
                        new LabReleaseManifest.EngineContract("application/json", List.of("1.0.0"),
                                List.of("DOCENGINE-C14N-1"), List.of("PAYSTUB"), List.of("WARNING")),
                        new LabReleaseManifest.Calculator(List.of("income.calculate"))),
                new LabReleaseManifest.ObservedInference(LabReleaseManifest.SelectionSource.ANALYZE_LANE,
                        "openai", "gpt-test", null, 800, 1,
                        new LabReleaseManifest.ResolutionInputs(null, null, null, null, "openai", "gpt-test",
                                null, null, "openai", "gpt-test", false)),
                new LabReleaseManifest.PrototypeLimitations(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                        LabReleaseManifest.LIVE_DEPENDENCIES));
    }
}
