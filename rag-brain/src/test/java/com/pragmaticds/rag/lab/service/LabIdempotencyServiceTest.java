package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.lab.domain.LabIdempotencyRecord;
import com.pragmaticds.rag.lab.repository.LabIdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LabIdempotencyServiceTest {
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RESULT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private LabIdempotencyRecordRepository records;
    private JdbcTemplate jdbc;
    private LabIdempotencyService service;

    @BeforeEach
    void setUp() {
        records = mock(LabIdempotencyRecordRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new DefaultLabIdempotencyService(records, jdbc, transactionManager());
    }

    @Test
    void sameKeyAndHashReplaysReferencedResultWithoutAction() {
        when(records.findByBrainIdAndOperationAndIdempotencyKey(BRAIN, "update", "key"))
                .thenReturn(Optional.of(new LabIdempotencyRecord(BRAIN, "update", "key", sha("a"), "instance", RESULT, 4L)));
        AtomicInteger actions = new AtomicInteger();

        String value = service.execute(command(sha("a"), () -> { actions.incrementAndGet(); return "new"; }));

        assertEquals("instance:" + RESULT + ":4", value);
        assertEquals(0, actions.get());
    }

    @Test
    void sameKeyWithDifferentHashFailsDeterministically() {
        when(records.findByBrainIdAndOperationAndIdempotencyKey(BRAIN, "update", "key"))
                .thenReturn(Optional.of(new LabIdempotencyRecord(BRAIN, "update", "key", sha("a"), "instance", RESULT, 4L)));

        LabIdempotencyService.IdempotencyException exception = assertThrows(
                LabIdempotencyService.IdempotencyException.class, () -> service.execute(command(sha("b"), () -> "new")));

        assertEquals(LabIdempotencyService.IdempotencyException.Code.IDEMPOTENCY_KEY_REUSED, exception.code());
    }

    @Test
    void rejectsMalformedCommandBeforeLookupOrAction() {
        AtomicInteger actions = new AtomicInteger();

        LabIdempotencyService.IdempotencyException exception = assertThrows(
                LabIdempotencyService.IdempotencyException.class,
                () -> service.execute(command("A".repeat(64), () -> { actions.incrementAndGet(); return "new"; })));

        assertEquals(LabIdempotencyService.IdempotencyException.Code.IDEMPOTENCY_COMMAND_INVALID,
                exception.code());
        assertEquals(0, actions.get());
    }

    @Test
    void freshCommandRunsActionAndStoresOnlyItsResultIdentity() {
        when(records.findByBrainIdAndOperationAndIdempotencyKey(BRAIN, "update", "key"))
                .thenReturn(Optional.empty());
        AtomicInteger actions = new AtomicInteger();

        String value = service.execute(command(sha("a"), () -> { actions.incrementAndGet(); return "new"; }));

        assertEquals("new", value);
        assertEquals(1, actions.get());
    }

    private static LabIdempotencyService.IdempotentCommand<String> command(String hash,
                                                                              java.util.function.Supplier<String> action) {
        return new LabIdempotencyService.IdempotentCommand<>(BRAIN, "update", "key", hash, action,
                ignored -> new LabIdempotencyService.IdempotencyResult("instance", RESULT, 4L),
                result -> result.kind() + ":" + result.id() + ":" + result.version());
    }
    private static String sha(String character) { return character.repeat(64); }

    private static AbstractPlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };
    }
}
