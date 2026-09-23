package com.pragmaticds.docengine.retention;

import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.platform.security.ActorType;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled retention sweep: finds every package past its {@code purge_after} and permanently
 * purges it (delegating one package at a time to {@link PackagePurger}).
 *
 * <h2>Tenancy of an off-request, cross-org job</h2>
 *
 * The sweep runs on a scheduler thread with NO principal and NO tenant. Both {@code @TenantId} and
 * Postgres RLS then return nothing, so the job cannot see any package until it binds an org. It
 * therefore discovers WHICH orgs have due packages with a single deliberately cross-tenant native
 * read ({@link DocumentPackageRepository#findOrgsWithDuePackages}), then for EACH org binds
 * {@link TenantContext} and a SYSTEM {@link AuthPrincipal} and does all package selection and
 * deletion tenant-scoped — so a delete can never cross an org, and the purge audit is attributed to
 * the system sweep under the right org. Both bindings are restored in a finally (fail-closed, the
 * {@code JobService.submit} pattern).
 *
 * <p>⚠️ DEPLOYMENT: under a non-owner role, RLS forces even this maintenance read to the bound org,
 * so the cross-org discovery query must run on a connection permitted to read {@code document_package}
 * across orgs (e.g. a dedicated purge role). Under the Testcontainers superuser it simply sees all
 * orgs. Everything after discovery is strictly per-org and RLS-safe.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);
    private static final String SYSTEM_SUBJECT = "system:retention-purge";

    private final DocumentPackageRepository packages;
    private final PackagePurger purger;

    public RetentionPurgeJob(DocumentPackageRepository packages, PackagePurger purger) {
        this.packages = packages;
        this.purger = purger;
    }

    /** Hourly by default (docengine.retention.purge-cron); set the cron to {@code -} to disable. */
    @Scheduled(cron = "${docengine.retention.purge-cron:0 0 * * * *}")
    public void scheduledSweep() {
        int purged = purgeDuePackages(Instant.now());
        if (purged > 0) {
            log.info("retention purge complete packages={}", purged);
        }
    }

    /**
     * Purges every package whose {@code purge_after <= cutoff}. Exposed so a test can run the sweep
     * synchronously without waiting on the scheduler.
     *
     * @return the number of packages actually purged
     */
    public int purgeDuePackages(Instant cutoff) {
        int purged = 0;
        for (UUID orgId : packages.findOrgsWithDuePackages(cutoff)) {
            purged += purgeOrg(orgId, cutoff);
        }
        return purged;
    }

    private int purgeOrg(UUID orgId, Instant cutoff) {
        Optional<UUID> previousTenant = TenantContext.current();
        Optional<AuthPrincipal> previousAuth = AuthContext.current();
        TenantContext.set(orgId);
        AuthContext.set(systemPrincipal(orgId));
        int purged = 0;
        try {
            for (DocumentPackage pkg :
                    packages.findByDeletedAtIsNotNullAndPurgeAfterLessThanEqual(cutoff)) {
                try {
                    if (purger.purge(pkg.getId(), orgId).rows() > 0) {
                        purged++;
                    }
                } catch (RuntimeException e) {
                    // Ids and codes only — a message could quote content. One bad package does not
                    // abort the sweep; it is retried next run.
                    log.error(
                            "retention purge failed package={} org={} exception={}",
                            pkg.getId(),
                            orgId,
                            e.getClass().getSimpleName());
                }
            }
        } finally {
            previousAuth.ifPresentOrElse(AuthContext::set, AuthContext::clear);
            previousTenant.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
        return purged;
    }

    /** A SYSTEM principal (no user, no role) so the purge audit is attributed to the sweep. */
    private static AuthPrincipal systemPrincipal(UUID orgId) {
        return new AuthPrincipal(
                orgId, null, SYSTEM_SUBJECT, null, ActorType.SYSTEM, Set.of());
    }
}
