package com.pragmaticds.docengine.security;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The pure logic behind the boot-time R2 assertion: is the live datasource role safe for RLS?
 *
 * <p>Two ways RLS is silently bypassed:
 *
 * <ol>
 *   <li>the role is a SUPERUSER — superusers are exempt from RLS entirely; or
 *   <li>the role has the BYPASSRLS attribute — it bypasses row security on every table, and unlike
 *       every other path this is NOT gated by FORCE ROW LEVEL SECURITY, so it is the one silent
 *       bypass FORCE cannot stop (Phase 7a review finding); or
 *   <li>the role OWNS the tenant tables — an owner bypasses its own tables' RLS unless FORCE, and
 *       even with FORCE, connecting as the schema owner is the misconfiguration risk R2 names.
 * </ol>
 *
 * <p>Returns a list of PII-free problem descriptions (empty = safe). It names the role and the
 * misconfiguration, never a password or any secret. Kept as a static, DataSource-only function so
 * it can be driven directly in tests against a superuser, a non-superuser owner, and the correct
 * non-owner role — see {@code RlsRuntimeIT}.
 */
public final class DatasourceOwnershipCheck {

    private DatasourceOwnershipCheck() {}

    public static List<String> inspect(DataSource dataSource) {
        List<String> problems = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            String role;
            boolean superuser;
            boolean bypassRls;
            try (ResultSet rs =
                    statement.executeQuery(
                            "SELECT current_user,"
                                + " rolsuper, rolbypassrls FROM pg_roles"
                                + " WHERE rolname = current_user")) {
                rs.next();
                role = rs.getString(1);
                superuser = rs.getBoolean(2);
                bypassRls = rs.getBoolean(3);
            }
            if (superuser) {
                problems.add(
                        "datasource role '" + role + "' is a SUPERUSER — superusers bypass RLS;"
                                + " the app must connect as a non-superuser role (docengine_app)");
            }
            if (bypassRls) {
                problems.add(
                        "datasource role '" + role + "' has BYPASSRLS — it bypasses row security on"
                                + " every table regardless of FORCE; the app must connect as a role"
                                + " without BYPASSRLS (docengine_app), or RLS is silently bypassed"
                                + " (risk R2)");
            }
            boolean ownsTenantTable;
            try (ResultSet rs =
                    statement.executeQuery(
                            "SELECT EXISTS (SELECT 1 FROM pg_tables"
                                + " WHERE schemaname = 'public' AND tableowner = current_user"
                                + " AND tablename <> 'flyway_schema_history')")) {
                rs.next();
                ownsTenantTable = rs.getBoolean(1);
            }
            if (ownsTenantTable) {
                problems.add(
                        "datasource role '" + role + "' OWNS tenant tables — Flyway must own the"
                                + " schema and the app must connect as a non-owner role"
                                + " (docengine_app), or RLS is silently bypassed (risk R2)");
            }
        } catch (SQLException e) {
            // A check that cannot run must not be read as "safe" — fail closed with the SQLState,
            // never the exception's message (it can echo connection details).
            problems.add(
                    "could not verify the datasource role for RLS safety (SQLState "
                            + e.getSQLState() + ")");
        }
        return problems;
    }
}
