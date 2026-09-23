package com.pragmaticds.docengine.platform.ai;

/** Provider-neutral seam for text-based AI extraction. Implementations never throw. */
public interface AiExtractionPort {

    AiExtractionResult extract(AiExtractionRequest request);

    /**
     * This adapter's identity as a parse-once behavior-fingerprint input, or EMPTY when a run it
     * enriched must never be stamped and therefore never reused.
     *
     * <p>Exactly {@code ParserPort.behaviorIdentity}'s contract, and for exactly its reason. The
     * hazard that motivated it there is live here too: {@code AiExtractionConfig} fails CLOSED to
     * {@link StubAiExtractionAdapter} on any incomplete provider configuration — a missing Vertex
     * project, an unreadable credential — while {@code docengine.ai.enabled} stays true and the
     * configured provider and model still read exactly as intended. Such a boot produces parses
     * that are simply not enriched, carrying full, healthy-looking fingerprints; once the
     * credential is fixed, re-uploads of those bytes are served the un-enriched output. Asking the
     * ADAPTER rather than the config is what makes that difference visible.
     *
     * <p>Empty is the default because a new adapter must OPT IN to being reusable. An adapter that
     * did not actually put the document to a model has no honest identity to give.
     */
    default java.util.Optional<String> behaviorIdentity() {
        return java.util.Optional.empty();
    }
}
