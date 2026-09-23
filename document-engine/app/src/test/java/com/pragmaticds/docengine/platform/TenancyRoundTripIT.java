package com.pragmaticds.docengine.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.tenancy.Loan;
import com.pragmaticds.docengine.platform.tenancy.LoanRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pragmaticds.docengine.platform.tenancy.TenantContext;

/**
 * Proves the Hibernate {@code @TenantId} wiring end to end — the application-layer half of tenant
 * isolation (the RLS half is proven in V1MigrationIT). If this wiring is broken, every entity the
 * Phase 1 agents build on top of {@code TenantScopedEntity} is silently cross-tenant.
 */
class TenancyRoundTripIT extends AbstractPostgresIT {

    @Autowired LoanRepository loans;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void writes_are_stamped_with_the_current_org() {
        TenantContext.set(ORG_DEV);

        Loan saved = loans.save(new Loan(UUID.randomUUID(), "LN-1001"));

        assertThat(saved.getOrgId()).isEqualTo(ORG_DEV);
    }

    @Test
    void reads_are_filtered_to_the_current_org() {
        TenantContext.set(ORG_DEV);
        loans.save(new Loan(UUID.randomUUID(), "LN-DEV"));

        TenantContext.set(ORG_OTHER);
        assertThat(loans.findByOrgId(ORG_DEV)).isEmpty();
        assertThat(loans.findAll()).noneMatch(loan -> "LN-DEV".equals(loan.getLoanNumber()));
    }

    @Test
    void no_bound_tenant_reads_nothing_rather_than_everything() {
        TenantContext.set(ORG_DEV);
        loans.save(new Loan(UUID.randomUUID(), "LN-UNBOUND-PROBE"));

        TenantContext.clear();
        assertThat(loans.findAll()).isEmpty();
    }

    @Test
    void find_by_id_and_org_misses_across_tenants() {
        TenantContext.set(ORG_DEV);
        Loan saved = loans.save(new Loan(UUID.randomUUID(), "LN-CROSS"));

        assertThat(loans.findByIdAndOrgId(saved.getId(), ORG_OTHER)).isEmpty();
        assertThat(loans.findByIdAndOrgId(saved.getId(), ORG_DEV)).isPresent();
    }
}
