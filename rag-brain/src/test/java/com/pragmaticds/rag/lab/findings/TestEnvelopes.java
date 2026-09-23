package com.pragmaticds.rag.lab.findings;

import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Shared {@link EngineResultEnvelope} construction for tests that only care about the documents
 * inside it.
 *
 * <p>Extracted from {@code InstanceToolRegistryTest}'s inline nine-argument constructor so a test
 * that needs a specific set of documents does not have to restate the envelope scaffolding
 * (artifact descriptor, generation, provenance) around them.
 */
public final class TestEnvelopes {

    private TestEnvelopes() {}

    /** The scaffold envelope with no documents, pages, or sources. */
    public static EngineResultEnvelope envelope() {
        return withDocuments(List.of());
    }

    /** The scaffold envelope carrying exactly the given documents. */
    public static EngineResultEnvelope withDocuments(List<LogicalDocument> documents) {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                new Generation(UUID.fromString("44444444-4444-4444-8444-444444444444"), 1, 1,
                        "b2".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(), List.of(), documents, List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt(
                                "EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }
}
