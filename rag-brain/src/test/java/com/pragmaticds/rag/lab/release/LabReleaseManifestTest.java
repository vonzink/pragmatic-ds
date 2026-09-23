package com.pragmaticds.rag.lab.release;

import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The optional read-model gate on {@link LabReleaseManifest.EngineContract}: absent on a release
 * means envelope values only, present means the release overlays the engine's read model.
 */
class LabReleaseManifestTest {

    @Test
    void theReadModelBlockRoundTripsThroughTheStoredMapAndIsOptional() {
        LabReleaseManifest.EngineContract withReadModel = new LabReleaseManifest.EngineContract(
                EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE,
                List.of("1.0.0"), List.of("DOCENGINE-C14N-1"), List.of("PAYSTUB"),
                List.of("MANUAL_REVIEW_REQUIRED"),
                new LabReleaseManifest.ReadModelContract(
                        LabReleaseManifest.FIELDS_CONTRACT,
                        List.of("CORRECTED", "MACHINE", "REJECTED")));
        LabReleaseManifest manifest = manifestWith(withReadModel);

        LabReleaseManifest restored = LabReleaseManifest.fromMap(manifest.toCanonicalMap());
        assertEquals(withReadModel, restored.pinned().engineContract());

        LabReleaseManifest legacy = manifestWith(new LabReleaseManifest.EngineContract(
                EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE,
                List.of("1.0.0"), List.of("DOCENGINE-C14N-1"), List.of("PAYSTUB"),
                List.of("MANUAL_REVIEW_REQUIRED")));
        Map<String, Object> legacyMap = legacy.toCanonicalMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> engine = (Map<String, Object>)
                ((Map<String, Object>) legacyMap.get("pinned")).get("engineContract");
        assertFalse(engine.containsKey("readModel"), "a legacy contract writes no readModel key");
        assertNull(LabReleaseManifest.fromMap(legacyMap).pinned().engineContract().readModel());
    }

    private static LabReleaseManifest manifestWith(LabReleaseManifest.EngineContract engineContract) {
        return new LabReleaseManifest(
                LabReleaseManifest.MANIFEST_VERSION,
                LabManifestWriter.CANONICALIZATION_VERSION,
                IncomeLabReleaseService.INCOME_INSTANCE_SLUG,
                IncomeLabReleaseService.INCOME_ANALYZER_SLUG,
                new LabReleaseManifest.Pinned(
                        new LabReleaseManifest.Analyzer("Income", "v2", "prompt", "{}",
                                IncomeLabReleaseService.OUTPUT_ENVELOPE_SCHEMA_RESOURCE,
                                "e5".repeat(32)),
                        new LabReleaseManifest.Retrieval("query", "income", 8),
                        engineContract,
                        new LabReleaseManifest.Calculator(
                                IncomeCalcService.SUPPORTED_METHODS.stream().sorted().toList())),
                new LabReleaseManifest.ObservedInference(
                        LabReleaseManifest.SelectionSource.GLOBAL_ANSWER_LANE,
                        "anthropic", "claude-x", "openai", 20000, 2,
                        new LabReleaseManifest.ResolutionInputs(null, null, null, null, null, null,
                                null, null, "anthropic", "claude-x", false)),
                new LabReleaseManifest.PrototypeLimitations(
                        LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                        LabReleaseManifest.LIVE_DEPENDENCIES));
    }
}
