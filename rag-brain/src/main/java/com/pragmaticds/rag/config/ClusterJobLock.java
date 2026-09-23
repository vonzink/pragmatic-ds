package com.pragmaticds.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Runs a job body only on the one instance that wins a Postgres session-level
 * advisory lock, so {@code @Scheduled} jobs execute once cluster-wide instead of
 * once per replica.
 *
 * <p>The lock is acquired, the job run, and the lock released ALL ON THE SAME pooled
 * connection (via {@link ConnectionCallback}). A session advisory lock is bound to the
 * connection that took it, so acquiring with {@code JdbcTemplate} and releasing with a
 * second call — which may borrow a different pooled connection — would silently leak
 * the lock. On a single instance the lock is always uncontended, so this is a
 * transparent no-op wrapper.
 */
@Component
public class ClusterJobLock {

    private static final Logger log = LoggerFactory.getLogger(ClusterJobLock.class);

    private final JdbcTemplate jdbc;

    public ClusterJobLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs {@code job} iff this instance acquires the advisory lock for {@code key}.
     *
     * @return {@code true} if this instance won the lock and ran the job; {@code false}
     *         if another instance holds it (the job is skipped here).
     */
    public boolean runIfLeader(long key, String name, Runnable job) {
        Boolean ran = jdbc.execute((ConnectionCallback<Boolean>) conn -> {
            if (!tryAdvisoryLock(conn, key)) {
                log.debug("Skipping scheduled job '{}': another instance holds the lock", name);
                return false;
            }
            try {
                job.run();
                return true;
            } finally {
                advisoryUnlock(conn, key);
            }
        });
        return Boolean.TRUE.equals(ran);
    }

    private static boolean tryAdvisoryLock(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void advisoryUnlock(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }
}
