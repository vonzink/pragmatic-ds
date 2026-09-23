package com.pragmaticds.docengine.orchestration;

import java.util.Optional;
import java.util.Set;

/**
 * Port through which a run that has just FINALIZED asks for the fingerprint describing the
 * behavior it ACTUALLY EXECUTED under — the value stamped onto {@code processing_job}.
 *
 * <p>The stamp is not written at job creation, and that is the whole point. An admission-time
 * stamp describes what the loaders WOULD have said when the upload arrived; the pipeline then runs
 * asynchronously, from caches, possibly minutes later. Any gap between those two lets one
 * fingerprint describe two different outputs — and a reuse hit under such a fingerprint serves a
 * parse that the current behavior would not produce, which is the wrong-value failure this whole
 * feature exists to avoid. Composing the stamp here, at the end, from what the run recorded as it
 * ran, removes the gap instead of narrowing it.
 *
 * <p>Empty is a legitimate and common answer: no adapter identity (a stub parse), a behavior view
 * the run never consulted or consulted twice differently, a view the loader has since replaced, a
 * worker whose version disagrees with the stage rows, a probe failure. Every one of them leaves
 * the column NULL, and NULL never matches a reuse probe.
 */
public interface BehaviorFingerprintPort {

    /**
     * The fingerprint for a run that executed the entire pipeline itself, or empty ⇒ stamp NULL.
     *
     * @param workerVersionsUsed every distinct worker version this job's SUCCEEDED stage rows
     *     recorded. The fingerprint's worker identity comes from a {@code /version} probe, and a
     *     probe answers for NOW; these are the versions that answered DURING the parse. They must
     *     agree, or the run straddles a worker upgrade and cannot be described by either.
     */
    Optional<String> fingerprintForCompletedRun(Set<String> workerVersionsUsed);
}
