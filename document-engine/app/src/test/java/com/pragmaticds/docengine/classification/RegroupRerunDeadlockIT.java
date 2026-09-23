package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Lock ORDER between the two writers on one package (issue #71).
 *
 * <p>Regroup and the per-document AI re-run both touch the job row and a logical document, and
 * before the fix they took them in opposite orders:
 *
 * <ul>
 *   <li>regroup: {@code logical_document} (the DELETE in {@code deleteRequestedDocuments}) → the
 *       {@code processing_job} row ({@code claimForReExtract}, a plain UPDATE, so it WAITS);
 *   <li>re-run: the {@code processing_job} row ({@code FOR UPDATE NOWAIT}) → {@code extracted_field}
 *       inserts, whose FK takes {@code FOR KEY SHARE} on the parent {@code logical_document}.
 * </ul>
 *
 * <p>That is an ABBA cycle and Postgres resolves it by killing one side with {@code 40P01} — a 500
 * for a pair of legitimate concurrent actions. Once every writer takes the job row FIRST, the two
 * simply serialize.
 *
 * <p>The DELETE is what closes the cycle, and nothing weaker does: a non-key UPDATE of the document
 * takes {@code FOR NO KEY UPDATE}, which does NOT conflict with the re-run's {@code FOR KEY SHARE}.
 * So this test regroups with a {@code deletedDocumentIds} entry, and it is the only regroup shape
 * that can deadlock.
 *
 * <p>The re-run side is driven as raw SQL on its own connection rather than through
 * {@code AiExtractionStageService}: the point under test is regroup's ordering, the re-run's
 * (job → child rows) is already what its own IT pins, and a second connection is what lets the two
 * transactions be interleaved deterministically. The connection is opened straight against the
 * container, NOT from the pool — these ITs cap Hikari at two.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class RegroupRerunDeadlockIT extends AbstractExtractionIT {

    private static final String DEADLOCK = "40P01";

    @Test
    void a_regroup_that_deletes_a_document_does_not_deadlock_against_a_concurrent_rerun()
            throws Exception {
        UUID packageId = insertPackage("regroup-rerun-deadlock-it");
        insertFixturePages(packageId, "combined_package");
        insertFixtureLayout(packageId, "combined_package");
        runPipelineToExtraction(packageId);
        seedCompletedJob(packageId);

        List<UUID> documents =
                jdbc.queryForList(
                        "SELECT id FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        UUID.class,
                        packageId);
        assertThat(documents).as("fixture must split into at least two documents").hasSizeGreaterThan(1);
        UUID target = documents.get(0);
        UUID victim = documents.get(documents.size() - 1);
        List<UUID> victimPages =
                jdbc.queryForList(
                        "SELECT page_id FROM logical_document_page WHERE logical_document_id = ?"
                                + " ORDER BY ordinal",
                        UUID.class,
                        victim);
        assertThat(victimPages).isNotEmpty();

        // Empty the victim (a deleted document must end empty), then delete it: the DELETE is the
        // lock that conflicts with the re-run's FK insert.
        String body =
                """
                {"intent":"MERGE",
                 "moves":%s,
                 "newDocuments":[],
                 "deletedDocumentIds":["%s"],
                 "reason":"the split was wrong"}
                """
                        .formatted(movesJson(victimPages, target), victim);

        UUID schemaId =
                jdbc.queryForObject("SELECT id FROM extraction_schema LIMIT 1", UUID.class);

        AtomicReference<Throwable> regroupFailure = new AtomicReference<>();
        AtomicInteger regroupStatus = new AtomicInteger();
        CountDownLatch regroupDone = new CountDownLatch(1);

        try (Connection rerun = container();
                Connection observer = container()) {
            rerun.setAutoCommit(false);

            // ── re-run, step 1: the job row, exactly as lockSettledJob takes it.
            try (PreparedStatement lock =
                    rerun.prepareStatement(
                            "SELECT id FROM processing_job WHERE package_id = ? FOR UPDATE")) {
                lock.setObject(1, packageId);
                try (ResultSet rs = lock.executeQuery()) {
                    assertThat(rs.next()).as("seeded job row").isTrue();
                }
            }

            // ── regroup, on its own thread: deletes the document, then claims the job row and
            // blocks on the lock held above.
            Thread regroup =
                    new Thread(
                            () -> {
                                try {
                                    regroupStatus.set(
                                            mockMvc.perform(
                                                            post(
                                                                            "/v1/packages/{id}/regroup",
                                                                            packageId)
                                                                    .contentType(
                                                                            MediaType
                                                                                    .APPLICATION_JSON)
                                                                    .content(body))
                                                    .andReturn()
                                                    .getResponse()
                                                    .getStatus());
                                } catch (Throwable failure) {
                                    regroupFailure.set(failure);
                                } finally {
                                    regroupDone.countDown();
                                }
                            },
                            "regroup-under-test");
            regroup.start();

            // Wait for regroup to actually be blocked on a lock — otherwise the insert below could
            // win the race and the test would prove nothing.
            assertThat(awaitBlockedBackend(observer))
                    .as("regroup should be parked waiting for the job row lock")
                    .isTrue();

            // ── re-run, step 2: a field row for the document regroup is deleting. Its FK takes
            // FOR KEY SHARE on that document — the second edge of the cycle.
            String insertError = null;
            try (PreparedStatement insert =
                    rerun.prepareStatement(
                            """
                            INSERT INTO extracted_field (org_id, logical_document_id, schema_id,
                                field_name, data_type, extraction_method, extractor_version,
                                confidence)
                            VALUES (?, ?, ?, 'employerName', 'STRING', 'LLM', 'it', 0.9)
                            """)) {
                insert.setObject(1, ORG_DEV);
                insert.setObject(2, victim);
                insert.setObject(3, schemaId);
                insert.executeUpdate();
            } catch (SQLException deadlocked) {
                insertError = deadlocked.getSQLState();
            }
            rerun.commit();

            assertThat(regroupDone.await(60, TimeUnit.SECONDS))
                    .as("regroup finished rather than hanging")
                    .isTrue();

            assertThat(insertError)
                    .as("the re-run's field insert must not be chosen as the deadlock victim")
                    .isNotEqualTo(DEADLOCK);
        }

        Throwable failure = regroupFailure.get();
        if (failure != null) {
            assertThat(rootMessage(failure))
                    .as("regroup must not be chosen as the deadlock victim")
                    .doesNotContain(DEADLOCK)
                    .doesNotContain("deadlock detected");
            throw new AssertionError("regroup failed unexpectedly", failure);
        }
        assertThat(regroupStatus.get()).as("regroup succeeds once the two serialize").isEqualTo(200);
    }

    /** A connection straight to the container — the Hikari pool here holds only two. */
    private static Connection container() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** True once some backend is waiting on a lock (regroup, parked on the held job row). */
    private static boolean awaitBlockedBackend(Connection observer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Statement statement = observer.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    """
                                    SELECT count(*) FROM pg_stat_activity
                                     WHERE wait_event_type = 'Lock'
                                       AND datname = current_database()
                                    """)) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return false;
    }

    private static String movesJson(List<UUID> pageIds, UUID target) {
        return pageIds.stream()
                .map(id -> "{\"pageId\":\"" + id + "\",\"toDocumentId\":\"" + target + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t).append('\n');
        }
        return all.toString();
    }
}
