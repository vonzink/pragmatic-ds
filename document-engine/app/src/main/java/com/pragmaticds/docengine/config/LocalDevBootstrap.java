package com.pragmaticds.docengine.config;

import com.pragmaticds.docengine.platform.tenancy.DevTenantFilter;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guarantees the fixed dev organization exists on a {@code local} boot.
 *
 * <p>{@link DevTenantFilter} pins every request to {@code DEV_ORG}; without this row, the first
 * upload dies on the tenant foreign key — which is exactly what happened in the first real
 * end-to-end run. ITs seed their own orgs; a human running compose should not have to.
 */
@Configuration
@Profile("local")
public class LocalDevBootstrap {

    @Bean
    public ApplicationRunner seedDevTenant(DataSource dataSource) {
        return args ->
                new JdbcTemplate(dataSource)
                        .update(
                                """
                                INSERT INTO tenant (id, name, status) VALUES (?, 'Local Dev Org', 'ACTIVE')
                                ON CONFLICT (id) DO NOTHING
                                """,
                                DevTenantFilter.DEV_ORG);
    }
}
