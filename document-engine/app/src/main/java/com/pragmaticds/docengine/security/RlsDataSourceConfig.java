package com.pragmaticds.docengine.security;

import com.pragmaticds.docengine.platform.tenancy.TenantConnectionStampingDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the application's autoconfigured datasource so every borrowed connection carries the
 * caller's org in the {@code app.current_org} GUC — the runtime half of RLS. Active in ALL
 * profiles: under local/test the role is a superuser that bypasses RLS, so stamping is a harmless
 * no-op that keeps the mechanism uniformly exercised; under a deployed non-owner role it is what
 * makes RLS actually engage.
 *
 * <p>Flyway is unaffected: it connects through its own (owner) datasource, not this bean, so
 * migrations still run as the schema owner.
 */
@Configuration
public class RlsDataSourceConfig {

    /**
     * A {@code static} bean post-processor (so it is instantiated early, before the datasource) that
     * replaces the {@code dataSource} bean with the tenant-stamping wrapper. Wrapping here rather
     * than defining a {@code @Bean DataSource} preserves all of Spring Boot's Hikari property
     * binding (pool size, etc.).
     */
    @Bean
    static BeanPostProcessor rlsStampingDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("dataSource".equals(beanName)
                        && bean instanceof DataSource dataSource
                        && !(bean instanceof TenantConnectionStampingDataSource)) {
                    return new TenantConnectionStampingDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
