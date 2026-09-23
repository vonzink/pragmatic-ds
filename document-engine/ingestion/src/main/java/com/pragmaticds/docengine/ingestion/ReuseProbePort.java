package com.pragmaticds.docengine.ingestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port through which the upload path asks whether an already-parsed, provably same-bytes
 * same-behavior package can be served instead of parsing again (parse-once reuse).
 *
 * <p>Ingestion depends only on {@code platform}; the candidate scan needs results, orchestration,
 * and the behavior fingerprint at once, so the app module supplies the adapter — the same seam
 * pattern as {@link ProcessingStarter}.
 *
 * <p>Contract: BOTH methods fail open. A fingerprint that cannot be computed honestly is
 * {@link Optional#empty()} (reuse disabled, parse normally), and a probe that cannot decide finds
 * nothing. The governing principle is that serving a stale parse as current is the wrong-value
 * failure mode: when in doubt, parse again.
 */
public interface ReuseProbePort {

    /**
     * The current org's prospective behavior fingerprint — computed ONCE per upload, used for the
     * candidate match AND stamped through {@link ProcessingStarter#startJob} so the stored value
     * describes the view that admitted the parse. Empty when the reuse switch is off or any input
     * (engine release, worker /version, loader views) is unavailable.
     */
    Optional<String> behaviorFingerprint();

    /**
     * The newest live package whose CURRENT verified engine result matches this exact source set
     * under this exact fingerprint, or empty. Never throws into the upload path.
     */
    Optional<ReuseHit> findReusable(List<FileIdentity> files, String behaviorFingerprint);

    /** One validated file's identity contribution ({@code contentType} is the SNIFFED type). */
    record FileIdentity(int ordinal, String sha256, long sizeBytes, String contentType) {}

    /** The prior package a reuse hit serves. */
    record ReuseHit(UUID packageId, UUID jobId, int engineResultRevision) {}
}
