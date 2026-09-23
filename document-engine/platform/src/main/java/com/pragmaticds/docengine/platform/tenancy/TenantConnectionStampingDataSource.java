package com.pragmaticds.docengine.platform.tenancy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Makes Postgres RLS engage at runtime by stamping {@code app.current_org} on every connection as
 * it is borrowed, and clearing it as the connection is returned to the pool.
 *
 * <p>Why a DataSource wrapper and not a Hibernate {@code MultiTenantConnectionProvider}: this app
 * uses {@code @TenantId} (DISCRIMINATOR) multitenancy, and Hibernate 6.6 does NOT invoke a
 * connection provider under that strategy — it filters in SQL on a single connection. Verified
 * empirically by {@code RlsRuntimeIT}, which fails closed (sees zero rows) until this wrapper
 * stamps the GUC. So the stamp must happen at the JDBC layer instead.
 *
 * <p>Leak-safety is structural, not hopeful. The borrow-time stamp writes an EXPLICIT value every
 * time — the caller's org, or the empty string when no tenant is bound — so a connection can never
 * carry a previous borrower's org into the next borrow. The reset-on-release is belt-and-braces on
 * top of that guarantee. {@code RlsRuntimeIT.the_guc_does_not_leak_across_pooled_connections}
 * proves both directions on a pool of size one.
 *
 * <p>Fail-closed: an unbound thread stamps {@code ''}, which {@code current_org()} collapses to
 * NULL, so an unstamped-intent connection sees nothing rather than everything — matching the
 * migration's documented RLS contract.
 */
public class TenantConnectionStampingDataSource extends DelegatingDataSource {

    private static final String SET_ORG = "SELECT set_config('app.current_org', ?, false)";

    public TenantConnectionStampingDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stamp(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stamp(super.getConnection(username, password));
    }

    private Connection stamp(Connection real) throws SQLException {
        String org = TenantContext.current().map(UUID::toString).orElse("");
        try {
            setOrg(real, org);
        } catch (SQLException e) {
            // Never hand back a connection we could not scope — that would be fail-OPEN.
            real.close();
            throw e;
        }
        return wrap(real);
    }

    private static void setOrg(Connection connection, String org) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_ORG)) {
            statement.setString(1, org);
            statement.execute();
        }
    }

    /** Wraps the connection so {@code close()} resets the GUC before returning it to the pool. */
    private static Connection wrap(Connection real) {
        return (Connection)
                Proxy.newProxyInstance(
                        TenantConnectionStampingDataSource.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            if ("close".equals(method.getName())
                                    && (args == null || args.length == 0)) {
                                try {
                                    setOrg(real, "");
                                } catch (SQLException ignored) {
                                    // A broken connection cannot be reset; close it regardless.
                                    // The next borrow re-stamps unconditionally, so nothing leaks.
                                }
                                return invoke(method, real, args);
                            }
                            return invoke(method, real, args);
                        });
    }

    private static Object invoke(java.lang.reflect.Method method, Connection real, Object[] args)
            throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
