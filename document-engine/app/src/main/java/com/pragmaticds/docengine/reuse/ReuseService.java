package com.pragmaticds.docengine.reuse;

import com.pragmaticds.docengine.ingestion.ReuseProbePort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.canonical.SourceSetIdentity;
import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import com.pragmaticds.docengine.results.service.EngineResultQueryService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The parse-once reuse probe (app-layer adapter for ingestion's {@link ReuseProbePort} — the same
 * seam pattern as {@code ProcessingSeamConfig}).
 *
 * <p>Reuse key: org (implicit — org-scoped repositories under FORCE RLS; never a query param) +
 * source-set digest (the prospective recomputation of {@code engine_result.source_set_sha256} via
 * {@link SourceSetIdentity} — one algorithm with the assembler) + behavior fingerprint
 * ({@link ReuseFingerprintService}). A candidate serves only when ALL of these hold:
 *
 * <ol>
 *   <li>its package is live in this org (tombstoned and purged priors never serve);
 *   <li>it is the package's CURRENT result by the exact {@link EngineResultQueryService#current}
 *       resolution — one job, exactly one SUCCEEDED FINALIZING whose output digest matches, and
 *       the stored blob byte/sha-verified, so an unverifiable prior can never be served;
 *   <li>that job ended in terminal success (HUMAN_REVIEW_REQUIRED or COMPLETED); and
 *   <li>the job's stamped {@code behavior_fingerprint} equals the prospective one. The stamp is
 *       written at FINALIZING and describes what the prior parse ACTUALLY EXECUTED under, so this
 *       equality means "the behavior that produced those rows is the behavior that would run now"
 *       — not merely "the two uploads were admitted under matching configuration". NULL never
 *       matches, so pre-V19 parses, stub parses, undescribable parses, and regrouped or resumed
 *       generations are permanently non-reusable.
 * </ol>
 *
 * <p>Newest surviving candidate wins. Every failure — of a candidate or of the whole scan — fails
 * OPEN to a fresh parse: a wrong (stale) value is worse than a missing (recomputed) one.
 */
@Service
public class ReuseService implements ReuseProbePort {

    private static final Logger log = LoggerFactory.getLogger(ReuseService.class);

    private static final Set<ProcessingStatus> TERMINAL_SUCCESS =
            Set.of(ProcessingStatus.HUMAN_REVIEW_REQUIRED, ProcessingStatus.COMPLETED);

    private final boolean enabled;
    private final int candidateScanLimit;
    private final ReuseFingerprintService fingerprints;
    private final EngineResultRepository results;
    private final ProcessingJobRepository jobs;
    private final EngineResultQueryService currentResults;
    private final org.springframework.transaction.support.TransactionTemplate candidateCheck;
    private final java.util.concurrent.Semaphore secondConnectionPermits;

    public ReuseService(
            @Value("${docengine.reuse.enabled:true}") boolean enabled,
            @Value("${docengine.reuse.max-concurrent-probes:4}") int maxConcurrentProbes,
            @Value("${docengine.reuse.max-candidate-scan:50}") int maxCandidateScan,
            ReuseFingerprintService fingerprints,
            EngineResultRepository results,
            ProcessingJobRepository jobs,
            EngineResultQueryService currentResults,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.enabled = enabled;
        // Bounds the DEPTH of a single upload's candidate scan (see findReusable): the semaphore
        // bounds how many uploads probe at once, this bounds how much work each probe may do, so a
        // pathological duplicate count for one org+digest cannot make an upload arbitrarily slow.
        this.candidateScanLimit = Math.max(1, maxCandidateScan);
        this.fingerprints = fingerprints;
        this.results = results;
        this.jobs = jobs;
        this.currentResults = currentResults;
        // The nested candidate check below runs while the CALLER's upload transaction still holds
        // a connection, so an upload inside the probe wants two at once and N concurrent uploads
        // finding candidates want 2N. Left unbounded, the optimisation can exhaust the pool that
        // the uploads themselves need — the reuse path taking down the write path.
        //
        // The permits bound the second borrow, and an upload that cannot get one does not QUEUE
        // for it: tryAcquire fails immediately and the upload parses fresh. That is the whole
        // point of the direction — a missed reuse hit costs work, a queued upload costs the
        // upload.
        this.secondConnectionPermits =
                new java.util.concurrent.Semaphore(Math.max(1, maxConcurrentProbes));
        // REQUIRES_NEW: each candidate check runs (and, on a rejected candidate, rolls back) in
        // its OWN transaction. current() throwing inside the caller's upload transaction would
        // mark IT rollback-only, and a caught exception would still abort the whole upload at
        // commit (UnexpectedRollbackException) — the exact fail-open violation this isolates.
        this.candidateCheck =
                new org.springframework.transaction.support.TransactionTemplate(
                        transactionManager);
        this.candidateCheck.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.candidateCheck.setReadOnly(true);
    }

    @Override
    public Optional<String> behaviorFingerprint() {
        if (!enabled) {
            return Optional.empty();
        }
        return fingerprints.fingerprint();
    }

    @Override
    public Optional<ReuseHit> findReusable(
            List<FileIdentity> files, String behaviorFingerprint) {
        if (!enabled || behaviorFingerprint == null) {
            return Optional.empty();
        }
        // One permit covers the WHOLE scan, because the whole scan is the window in which this
        // upload may hold a second connection. Not acquired = not scanned = fresh parse.
        if (!secondConnectionPermits.tryAcquire()) {
            log.info("reuse probe skipped: the bounded second-connection budget is fully in use");
            return Optional.empty();
        }
        try {
            UUID orgId = TenantContext.require();
            String sourceSetSha256 =
                    SourceSetIdentity.digest(
                            files.stream()
                                    .map(
                                            file ->
                                                    new SourceSetIdentity.Entry(
                                                            file.ordinal(),
                                                            file.sha256(),
                                                            file.sizeBytes(),
                                                            file.contentType()))
                                    .toList());

            // Newest-first candidate packages; several descriptor rows of one package (regroup
            // generations) collapse onto one current() resolution. Bounded: fetch one more than the
            // limit so a full page reveals there WERE more, then scan only the newest limit. A
            // pathological duplicate count for this org+digest therefore costs a bounded scan, not
            // an unbounded one — and because rows come newest-first and reuse serves the newest
            // survivor, only candidates older than the cut are ever dropped.
            List<EngineResult> candidates =
                    results.findByOrgIdAndSourceSetSha256OrderByCreatedAtDesc(
                            orgId,
                            sourceSetSha256,
                            org.springframework.data.domain.Limit.of(candidateScanLimit + 1));
            if (candidates.size() > candidateScanLimit) {
                log.info(
                        "reuse candidate scan bounded: more than {} descriptors for this org+digest;"
                                + " scanning only the newest {} (older candidates not considered on"
                                + " this upload)",
                        candidateScanLimit,
                        candidateScanLimit);
                candidates = candidates.subList(0, candidateScanLimit);
            }
            Set<UUID> candidatePackages = new LinkedHashSet<>();
            for (EngineResult candidate : candidates) {
                candidatePackages.add(candidate.getPackageId());
            }

            for (UUID packageId : candidatePackages) {
                Optional<ReuseHit> hit =
                        verifiedHit(orgId, packageId, sourceSetSha256, behaviorFingerprint);
                if (hit.isPresent()) {
                    return hit;
                }
            }
            return Optional.empty();
        } catch (RuntimeException probeFailure) {
            // Ids and class names only; fail open to a fresh parse.
            log.warn(
                    "reuse candidate scan failed open exception={}",
                    probeFailure.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            secondConnectionPermits.release();
        }
    }

    private Optional<ReuseHit> verifiedHit(
            UUID orgId, UUID packageId, String sourceSetSha256, String behaviorFingerprint) {
        try {
            return Optional.ofNullable(
                    candidateCheck.execute(
                            status ->
                                    checkCandidate(
                                            orgId,
                                            packageId,
                                            sourceSetSha256,
                                            behaviorFingerprint)));
        } catch (RuntimeException notServable) {
            // current() rejects (tombstoned, not ready, corrupt, unverifiable blob) by throwing;
            // its transaction rolled back with it and the upload transaction is untouched.
            return Optional.empty();
        }
    }

    private ReuseHit checkCandidate(
            UUID orgId, UUID packageId, String sourceSetSha256, String behaviorFingerprint) {
        // The exact current-result resolution INCLUDING the live-package (tombstone) gate and
        // the envelope blob byte/sha verification — an unverifiable prior is never served.
        EngineResultQueryService.VerifiedContent current = currentResults.current(packageId);

        Optional<ProcessingJob> job = jobs.findByIdAndOrgId(current.processingJobId(), orgId);
        if (job.isEmpty()
                || !TERMINAL_SUCCESS.contains(job.get().getStatus())
                || job.get().getBehaviorFingerprint() == null
                || !behaviorFingerprint.equals(job.get().getBehaviorFingerprint())) {
            return null;
        }

        // The current descriptor must still describe THIS source set (defensive: candidates were
        // selected by digest, but current() may have resolved a different generation's row).
        EngineResult descriptor =
                results.findByPackageIdAndRevisionAndOrgId(packageId, current.revision(), orgId)
                        .orElse(null);
        if (descriptor == null || !sourceSetSha256.equals(descriptor.getSourceSetSha256())) {
            return null;
        }

        return new ReuseHit(packageId, current.processingJobId(), current.revision());
    }
}
