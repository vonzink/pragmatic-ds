package com.pragmaticds.docengine.platform.tenancy;

import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link TenantContext} into Hibernate's {@code @TenantId} machinery.
 *
 * <p>When no tenant is bound (startup, schema validation), a NIL sentinel is returned rather than
 * null — Hibernate requires a value. The sentinel matches no real org, so an unbound read returns
 * nothing: fail-closed at the application layer, mirroring the RLS GUC behaviour at the database
 * layer.
 */
@Component
public class TenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    /** Matches no real organization, ever. */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    /** Registers this bean as Hibernate's resolver — the Spring Boot @TenantId wiring pattern. */
    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
