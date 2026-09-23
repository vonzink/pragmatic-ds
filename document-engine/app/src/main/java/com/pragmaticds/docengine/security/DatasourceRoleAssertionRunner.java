package com.pragmaticds.docengine.security;

import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Boot-time enforcement of risk R2: outside {@code local}/{@code test}, refuse to start if the
 * datasource role would silently bypass RLS (a superuser, or the schema owner). Under
 * {@code local}/{@code test} it logs a warning at most — local development intentionally connects
 * as the owner, where {@code @TenantId} still isolates and the RLS backstop is dormant.
 *
 * <p>The decision is {@code auto} by default (enforce iff not local/test) but overridable via
 * {@code docengine.security.owner-assertion} = {@code enforce}|{@code warn}|{@code auto} so a
 * focused security IT can run the JWT chain against a superuser test container without tripping.
 * The check itself is {@link DatasourceOwnershipCheck} — pure and directly tested.
 */
@Component
public class DatasourceRoleAssertionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasourceRoleAssertionRunner.class);

    private final DataSource dataSource;
    private final Environment environment;
    private final String mode;

    public DatasourceRoleAssertionRunner(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
        this.mode = environment.getProperty("docengine.security.owner-assertion", "auto");
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = DatasourceOwnershipCheck.inspect(dataSource);
        if (problems.isEmpty()) {
            log.info("datasource role verified: non-superuser, non-owner — RLS will engage");
            return;
        }
        String joined = String.join("; ", problems);
        if (enforce()) {
            // PII-free by construction: DatasourceOwnershipCheck emits role names and the
            // misconfiguration, never a password or connection secret.
            throw new IllegalStateException(
                    "refusing to start — datasource role bypasses tenant isolation (R2): " + joined);
        }
        log.warn("RLS datasource-role warning (tolerated under local/test): {}", joined);
    }

    private boolean enforce() {
        return switch (mode) {
            case "enforce" -> true;
            case "warn" -> false;
            default -> !isLocalOrTest();
        };
    }

    private boolean isLocalOrTest() {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equals(profile) || "test".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}
