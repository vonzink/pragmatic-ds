package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real Postgres coverage for the transaction-scoped idempotency advisory-lock protocol. */
@SpringBootTest(properties = {"ragbrain.instances.enabled=true", "ragbrain.lab.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LabIdempotencyServiceTxIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired LabIdempotencyService service;
    @SpyBean JdbcTemplate jdbc;
    @Autowired BrainRepository brains;
    @Autowired InstanceRegistryService registry;
    @Autowired LabInstanceRepository instances;
    @Autowired PlatformTransactionManager transactions;

    private UUID brainId;

    @BeforeEach
    void setUp() {
        brainId = brains.save(new Brain(UUID.randomUUID(), "idempotency-" + UUID.randomUUID(), "Idempotency")).getId();
        jdbc.execute("CREATE TABLE IF NOT EXISTS idempotency_tx_probe (id UUID PRIMARY KEY, value TEXT NOT NULL)");
    }

    @Test
    void concurrentTransactionsRunOneActionPersistOneReceiptAndReplayTheWinner() throws Exception {
        UUID resultId = UUID.randomUUID();
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger advisoryLockCalls = new AtomicInteger();
        CountDownLatch ownerActionEntered = new CountDownLatch(1);
        CountDownLatch followerLockAttempted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (advisoryLockCalls.incrementAndGet() == 2) {
                followerLockAttempted.countDown();
            }
            return invocation.callRealMethod();
        }).when(jdbc).queryForObject(contains("pg_advisory_xact_lock"), eq(Object.class), anyInt(), anyInt());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> owner = pool.submit(() -> service.execute(command("concurrent", resultId, () -> {
                actions.incrementAndGet();
                ownerActionEntered.countDown();
                assertTrue(await(releaseOwner));
                jdbc.update("INSERT INTO idempotency_tx_probe (id, value) VALUES (?, 'winner')", resultId);
                return "result:" + resultId;
            })));
            assertTrue(ownerActionEntered.await(10, TimeUnit.SECONDS), "owner must hold the advisory lock");
            Future<String> follower = pool.submit(() -> service.execute(command("concurrent", resultId,
                    () -> { throw new AssertionError("follower action must not run"); })));
            assertTrue(followerLockAttempted.await(10, TimeUnit.SECONDS),
                    "follower must reach advisory lock while owner transaction remains open");
            releaseOwner.countDown();
            List<Future<String>> results = List.of(owner, follower);
            assertEquals("result:" + resultId, results.get(0).get(30, TimeUnit.SECONDS));
            assertEquals("result:" + resultId, results.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            reset(jdbc);
            pool.shutdownNow();
        }
        assertEquals(1, actions.get());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lab_idempotency_record WHERE brain_id = ?", Integer.class, brainId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_tx_probe WHERE id = ?", Integer.class, resultId));
    }

    @Test
    void actionIntegrityFailurePropagatesAndLeavesNoReceipt() {
        UUID probe = UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_tx_probe (id, value) VALUES (?, 'first')", probe);

        assertThrows(DataIntegrityViolationException.class, () -> service.execute(command("failure", UUID.randomUUID(), () -> {
            jdbc.update("INSERT INTO idempotency_tx_probe (id, value) VALUES (?, 'duplicate')", probe);
            return "never";
        })));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_idempotency_record WHERE brain_id = ?", Integer.class, brainId));
    }

    @Test
    void concurrentDisableWinsAndAPatchCannotReactivateTheLockedInstance() throws Exception {
        InstanceKey key = new InstanceKey(brainId, "race-income");
        instances.saveAndFlush(new LabInstance(brainId, key.slug(), "Income", "Before race"));
        CountDownLatch disableHasLock = new CountDownLatch(1);
        CountDownLatch commitDisable = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> disable = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                registry.disable(key); // REQUIRED joins this outer transaction and retains SELECT FOR UPDATE.
                disableHasLock.countDown();
                assertTrue(await(commitDisable));
                return null;
            }));
            assertTrue(disableHasLock.await(10, TimeUnit.SECONDS));
            Future<Boolean> patch = pool.submit(() -> {
                try {
                    registry.updateMetadata(key, "Stale PATCH", "Must not win");
                    return true;
                } catch (InstanceRegistryService.InstanceException expected) {
                    assertEquals(InstanceRegistryService.InstanceException.Code.INSTANCE_DISABLED, expected.code());
                    return false;
                }
            });
            commitDisable.countDown();
            disable.get(20, TimeUnit.SECONDS);
            assertFalse(patch.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        LabInstance finalState = instances.findByBrainIdAndSlug(brainId, key.slug()).orElseThrow();
        assertEquals(LabInstance.State.DISABLED, finalState.getState());
        assertEquals("Income", finalState.getDisplayName());
    }

    private LabIdempotencyService.IdempotentCommand<String> command(String key, UUID resultId,
                                                                       java.util.function.Supplier<String> action) {
        return new LabIdempotencyService.IdempotentCommand<>(brainId, "instance-update", key,
                "a".repeat(64), action,
                ignored -> new LabIdempotencyService.IdempotencyResult("probe", resultId, 1L),
                result -> "result:" + result.id());
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", interrupted);
        }
    }
}
