package com.pragmaticds.docengine.ai;

import java.util.Map;
import java.util.Optional;

/**
 * The AI enrichment seam described as a parse-once behavior-fingerprint input.
 *
 * <p>Implemented by {@link AiExtractionStageService} because that is the component that HOLDS every
 * value involved — the gates, the budgets, the adapters, the dialects. Reading those keys a second
 * time inside the fingerprint service would be a copy that silently drifts the day a default
 * changes on one side only, which is the failure this interface exists to make impossible.
 *
 * <p>Flat string map rather than a typed record: {@code CanonicalJsonWriter} owns key order, so the
 * composer can embed the entries in any order and still hash deterministically, and a new key needs
 * no plumbing anywhere else.
 */
public interface AiBehaviorIdentity {

    /**
     * What the AI seam would do to a parse started now, or EMPTY when a run under it must never be
     * stamped and therefore never reused.
     *
     * <p>Empty means one thing only: the seam is switched ON but the adapter that would answer has
     * declined to identify itself — the fail-closed stub standing in for a misconfigured provider.
     * See {@code AiExtractionPort.behaviorIdentity}.
     */
    Optional<Map<String, String>> aiBehaviorIdentity();
}
