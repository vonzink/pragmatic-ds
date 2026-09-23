package com.pragmaticds.docengine.ingestion;

import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.domain.RetentionPolicy;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.repo.RetentionPolicyRepository;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package soft delete: tombstone now, purge later. This sets {@code deleted_at} and computes
 * {@code purge_after} from the org's retention policy (or a config default when none is configured),
 * then records a PII-free {@code PACKAGE_DELETED} audit event. It does NOT touch rows or blobs — the
 * scheduled retention purge does that once {@code purge_after} passes.
 *
 * <p>Idempotency: the load excludes already-tombstoned packages, so a second delete answers 404
 * rather than re-stamping the window or double-auditing. A cross-tenant id is equally absent (the
 * org-scoped load), so another org's package can never be deleted and its existence is not
 * confirmed.
 */
@Service
public class PackageLifecycleService {

    private final DocumentPackageRepository packages;
    private final RetentionPolicyRepository policies;
    private final AuditService audit;
    private final int defaultPurgeAfterDays;

    public PackageLifecycleService(
            DocumentPackageRepository packages,
            RetentionPolicyRepository policies,
            AuditService audit,
            @Value("${docengine.retention.default-purge-after-days:30}") int defaultPurgeAfterDays) {
        this.packages = packages;
        this.policies = policies;
        this.audit = audit;
        this.defaultPurgeAfterDays = defaultPurgeAfterDays;
    }

    @Transactional
    public void softDelete(UUID packageId) {
        UUID orgId = TenantContext.require();
        DocumentPackage pkg =
                packages
                        .findByIdAndOrgIdAndDeletedAtIsNull(packageId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        int purgeAfterDays =
                policies
                        .findByOrgIdAndDocumentCategoryIsNull(orgId)
                        .map(RetentionPolicy::getPurgeAfterDays)
                        .orElse(defaultPurgeAfterDays);

        pkg.softDelete(Instant.now().plus(Duration.ofDays(purgeAfterDays)));
        packages.save(pkg);

        // PII-free: a retention-window count, never a name or filename.
        audit.record(
                AuditEvent.ACTION_PACKAGE_DELETED,
                "DOCUMENT_PACKAGE",
                packageId,
                Map.of("purgeAfterDays", purgeAfterDays));
    }
}
