package com.pragmaticds.docengine.security;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Data access for API-key authentication. Two operations, both deliberately narrow:
 *
 * <ul>
 *   <li>{@link #resolveByHash(String)} — calls the {@code api_key_authenticate} SECURITY DEFINER
 *       function (V22), the ONE lookup allowed to bypass RLS so the org can be learned from the key
 *       before any tenant is bound. It resolves exactly one hash and cannot enumerate keys.
 *   <li>{@link #touchLastUsed(UUID)} — a normal, RLS-scoped {@code UPDATE}. It runs only AFTER the
 *       filter has bound {@code TenantContext}, so the connection is stamped with the key's org and
 *       {@code api_key_isolation} governs the write like any other tenant statement.
 * </ul>
 *
 * <p>A plain {@link JdbcTemplate} rather than Spring Data: {@code api_key} is intentionally not
 * mapped as a JPA entity (the engine does not manage keys as a domain aggregate), and this mirrors
 * the JdbcTemplate access already used for infrastructure SQL (see {@code LocalDevBootstrap}).
 *
 * <p>Bound to the non-local/test profiles like the rest of the service-auth path; local/test use
 * {@code DevAuthFilter} and never authenticate a key.
 */
@Repository
@Profile("!local & !test")
public class ApiKeyRepository {

    private static final String RESOLVE_SQL =
            "SELECT id, org_id, scopes, expires_at, revoked_at FROM api_key_authenticate(?)";

    private static final String TOUCH_SQL = "UPDATE api_key SET last_used_at = now() WHERE id = ?";

    private static final RowMapper<ApiKeyRecord> MAPPER =
            (ResultSet rs, int rowNum) -> {
                Array scopesArray = rs.getArray("scopes");
                List<String> scopes =
                        scopesArray == null
                                ? List.of()
                                : List.of((String[]) scopesArray.getArray());
                Timestamp expires = rs.getTimestamp("expires_at");
                Timestamp revoked = rs.getTimestamp("revoked_at");
                return new ApiKeyRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        scopes,
                        expires == null ? null : expires.toInstant(),
                        revoked == null ? null : revoked.toInstant());
            };

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Resolve the key whose {@code key_hash} equals {@code keyHash}, bypassing RLS via the SECURITY
     * DEFINER function. The unique index on {@code key_hash} guarantees at most one row.
     *
     * @return the row, or empty when no key has that exact hash
     */
    public Optional<ApiKeyRecord> resolveByHash(String keyHash) {
        return jdbc.query(RESOLVE_SQL, MAPPER, keyHash).stream().findFirst();
    }

    /** Best-effort {@code last_used_at} stamp. Runs RLS-scoped under the now-bound tenant. */
    public void touchLastUsed(UUID id) {
        jdbc.update(TOUCH_SQL, id);
    }
}
