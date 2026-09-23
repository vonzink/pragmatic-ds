package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.lab.domain.LabIdempotencyRecord;
import com.pragmaticds.rag.lab.repository.LabIdempotencyRecordRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Persists only canonical command hashes and result identities for idempotent Lab mutations. */
public interface LabIdempotencyService {
    /**
     * Executes a command once for concurrent callers with the same brain, operation, key, and
     * request hash, or replays its immutable result identity.
     *
     * <p>The command {@linkplain IdempotentCommand#action() action} runs inside this service's
     * new database transaction. The exactly-once concurrency guarantee applies only to durable
     * state enlisted in that transaction, including the receipt. HTTP/model calls, file writes,
     * message publication, and other external or non-transactional effects are outside this
     * guarantee and require their own provider idempotency or an outbox protocol. If the process
     * crashes before commit, PostgreSQL rolls back/release the claim and a later caller may retry
     * the action.
     */
    <T> T execute(IdempotentCommand<T> command);

    /**
     * Rejects a key already bound to a different request, before the caller spends anything.
     *
     * <p>{@link #execute(IdempotentCommand)} makes the same comparison authoritatively, under the
     * receipt lock, and is what actually protects durable state. This is the cheap read that lets
     * a caller make it <em>early</em>: a route whose work includes an outbound call can bind the
     * canonical request hash to the key first, so a client reusing a key for a different body is
     * refused before that call goes out rather than after.
     *
     * <p>Advisory by construction. It takes no lock, so a receipt written between this check and
     * the command still exists; that race is caught at commit. Never treat a clean return as
     * permission to skip {@code execute}.
     *
     * @throws IdempotencyException {@code IDEMPOTENCY_KEY_REUSED} when a receipt exists for this
     *     brain, operation, and key under a different request hash
     */
    void requireUnusedOrMatching(UUID brainId, String operation, String key, String requestSha256);

    /**
     * One command protected by a brain/operation/key receipt.
     *
     * <p>{@code action} must perform its durable mutation using the transaction established by
     * {@link LabIdempotencyService#execute(IdempotentCommand)}. Only that transaction-enlisted
     * state has the exactly-once concurrency property; do not use this generic Supplier as the
     * sole guard for external effects without their own idempotency/outbox protocol. A crash before
     * receipt commit permits the next caller to run the action again.
     */
    record IdempotentCommand<T>(UUID brainId, String operation, String key, String requestSha256,
                                Supplier<T> action, Function<T, IdempotencyResult> result,
                                Function<IdempotencyResult, T> replay) {}
    record IdempotencyResult(String kind, UUID id, Long version) {}

    final class IdempotencyException extends RuntimeException {
        public enum Code { IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_RECEIPT_CONFLICT, IDEMPOTENCY_COMMAND_INVALID }
        private final Code code;
        public IdempotencyException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }
        public Code code() { return code; }
    }
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultLabIdempotencyService implements LabIdempotencyService {
    private static final String RECEIPT_UNIQUE_CONSTRAINT = "uq_lab_idempotency_operation_key";
    private final LabIdempotencyRecordRepository records;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate isolatedWrite;

    DefaultLabIdempotencyService(LabIdempotencyRecordRepository records,
                                 JdbcTemplate jdbc,
                                 PlatformTransactionManager transactionManager) {
        this.records = Objects.requireNonNull(records, "records");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.isolatedWrite = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "transactionManager"));
        this.isolatedWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T execute(IdempotentCommand<T> command) {
        require(command);
        var existing = records.findByBrainIdAndOperationAndIdempotencyKey(
                command.brainId(), command.operation(), command.key());
        if (existing.isPresent()) {
            return replay(command, existing.get());
        }
        try {
            return isolatedWrite.execute(status -> createOrReplay(command));
        } catch (DataIntegrityViolationException lostRace) {
            if (!isReceiptUniqueViolation(lostRace)) {
                throw lostRace;
            }
            return replay(command, records.findByBrainIdAndOperationAndIdempotencyKey(
                    command.brainId(), command.operation(), command.key()).orElseThrow(
                    () -> new IdempotencyException(IdempotencyException.Code.IDEMPOTENCY_RECEIPT_CONFLICT)));
        }
    }

    @Override
    public void requireUnusedOrMatching(
            UUID brainId, String operation, String key, String requestSha256) {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestSha256, "requestSha256");
        records.findByBrainIdAndOperationAndIdempotencyKey(brainId, operation, key)
                .filter(receipt -> !requestSha256.equals(receipt.getRequestSha256()))
                .ifPresent(reused -> {
                    throw new IdempotencyException(IdempotencyException.Code.IDEMPOTENCY_KEY_REUSED);
                });
    }

    private <T> T createOrReplay(IdempotentCommand<T> command) {
        lock(command);
        var existing = records.findByBrainIdAndOperationAndIdempotencyKey(
                command.brainId(), command.operation(), command.key());
        if (existing.isPresent()) {
            return replay(command, existing.get());
        }
        T value = command.action().get();
        IdempotencyResult result = Objects.requireNonNull(command.result().apply(value), "result");
        records.saveAndFlush(new LabIdempotencyRecord(command.brainId(), command.operation(), command.key(),
                command.requestSha256(), result.kind(), result.id(), result.version()));
        return value;
    }

    private <T> T replay(IdempotentCommand<T> command, LabIdempotencyRecord receipt) {
        if (!command.requestSha256().equals(receipt.getRequestSha256())) {
            throw new IdempotencyException(IdempotencyException.Code.IDEMPOTENCY_KEY_REUSED);
        }
        return command.replay().apply(new IdempotencyResult(receipt.getResultKind(),
                receipt.getResultId(), receipt.getResultVersion()));
    }

    private static void require(IdempotentCommand<?> command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.brainId(), "brainId");
        String operation = Objects.requireNonNull(command.operation(), "operation");
        String key = Objects.requireNonNull(command.key(), "key");
        String requestSha256 = Objects.requireNonNull(command.requestSha256(), "requestSha256");
        Objects.requireNonNull(command.action(), "action");
        Objects.requireNonNull(command.result(), "result");
        Objects.requireNonNull(command.replay(), "replay");
        if (operation.isBlank() || operation.length() > 80 || key.isBlank() || key.length() > 200
                || !requestSha256.matches("[0-9a-f]{64}")) {
            throw new IdempotencyException(IdempotencyException.Code.IDEMPOTENCY_COMMAND_INVALID);
        }
    }

    private void lock(IdempotentCommand<?> command) {
        byte[] brain = command.brainId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] operation = command.operation().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] key = command.key().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
        digest.update(brain);
        digest.update((byte) 0);
        digest.update(operation);
        digest.update((byte) 0);
        byte[] hash = digest.digest(key);
        int first = java.nio.ByteBuffer.wrap(hash, 0, 4).getInt();
        int second = java.nio.ByteBuffer.wrap(hash, 4, 4).getInt();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class, first, second);
    }

    private static boolean isReceiptUniqueViolation(DataIntegrityViolationException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraint
                    && RECEIPT_UNIQUE_CONSTRAINT.equals(constraint.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
