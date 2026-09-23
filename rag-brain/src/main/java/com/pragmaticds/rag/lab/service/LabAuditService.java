package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.repository.LabAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the Lab's append-only audit trail in a transaction of its own.
 *
 * <p><b>Why {@code REQUIRES_NEW} and not the caller's transaction.</b> An audit row records that a
 * thing was attempted. Sharing the caller's transaction would delete that record precisely when it
 * matters most — a failed or rolled-back operation would roll its own audit row back with it, so
 * the trail would contain successes only. An independent transaction keeps the record of a refusal.
 *
 * <p><b>Two write modes, chosen by what the caller is about to do.</b>
 *
 * <ul>
 *   <li>{@link #record} is best-effort: an audit failure is logged as a code and swallowed. Used
 *       for lifecycle notes whose loss does not change what the caller may see.
 *   <li>{@link #recordRequired} fails closed: if the row cannot be committed, the operation does
 *       not happen. Used for sensitive READS — envelope and parsed-value reads, run detail,
 *       discussion reads — where "we showed borrower facts but cannot prove who asked" is not an
 *       acceptable outcome.
 * </ul>
 *
 * <p><b>Actor and correlation are bounded proxies, taken from the already-sanitized request MDC</b>
 * ({@link RequestCorrelationFilter}, which validates both). This prototype authenticates with a
 * shared admin key and therefore has no user identity to record: absent a proxy-supplied user, the
 * actor is the explicit {@link LabAuditEvent#ANONYMOUS_ADMIN_ACTOR} rather than a fabricated name.
 *
 * <p>Metadata is counts and booleans. That is not a convention here — V34's
 * {@code lab_metadata_is_counts_only} check refuses anything else, so a value, filename, or prompt
 * cannot be smuggled into the trail even by a future careless caller.
 */
@Service
@ConditionalOnExpression("${ragbrain.lab.enabled:false} or ${ragbrain.instances.enabled:false}")
public class LabAuditService {

    private static final Logger log = LoggerFactory.getLogger(LabAuditService.class);

    /** Column widths from V34; a longer value is truncated rather than failing the write. */
    private static final int ACTOR_MAX = 64;
    private static final int CORRELATION_MAX = 64;
    private static final int FAILURE_CODE_MAX = 64;

    // Stable action vocabulary. Strings rather than an enum because V34 stores a VARCHAR and a
    // future action must not require a schema change to be auditable.
    public static final String INSTANCE_READ = "INSTANCE_READ";
    public static final String RELEASE_DRIFT = "RELEASE_DRIFT";
    /** One movement of a live pointer. The reason lives on the immutable pointer event. */
    public static final String RELEASE_POINTER_MOVED = "RELEASE_POINTER_MOVED";
    public static final String REGISTRATION_CREATE = "REGISTRATION_CREATE";
    public static final String DOCUMENT_STATUS_READ = "DOCUMENT_STATUS_READ";
    public static final String ENVELOPE_READ = "ENVELOPE_READ";
    public static final String RUN_START = "RUN_START";
    public static final String RUN_TERMINAL = "RUN_TERMINAL";
    public static final String RUN_READ = "RUN_READ";
    public static final String RUN_HISTORY_READ = "RUN_HISTORY_READ";
    public static final String RUN_PURGE = "RUN_PURGE";
    public static final String RUN_RECOVERY = "RUN_RECOVERY";
    public static final String DISCUSSION_WRITE = "DISCUSSION_WRITE";
    public static final String DISCUSSION_READ = "DISCUSSION_READ";
    public static final String DISCUSSION_RECOVERY = "DISCUSSION_RECOVERY";
    public static final String COLLECTION_CREATE = "COLLECTION_CREATE";
    public static final String COLLECTION_MEMBERSHIP_REPLACE = "COLLECTION_MEMBERSHIP_REPLACE";
    public static final String COLLECTION_CLONE = "COLLECTION_CLONE";
    public static final String COLLECTION_DISABLE = "COLLECTION_DISABLE";
    public static final String CORPUS_SNAPSHOT_FREEZE = "CORPUS_SNAPSHOT_FREEZE";

    private final LabAuditEventRepository events;
    private final TransactionTemplate independent;

    /**
     * Audit writes that failed since startup, and the attempts they came from.
     *
     * <p>Swallowing a best-effort audit failure is the right trade — an audit write must not fail
     * a user's operation — but on the 2026-09-01 deployment it meant every write had failed for
     * weeks behind a {@code log.warn} nobody was tailing, and {@code lab_audit_event} was empty.
     * A swallowed failure has to be visible somewhere that is actually looked at; readiness is
     * that place, so these counters are what it reports.
     *
     * <p>Counts only, never a cause: the exception's message can quote the offending row.
     */
    private final AtomicLong writeAttempts = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();

    public LabAuditService(LabAuditEventRepository events,
                           PlatformTransactionManager transactionManager) {
        this.events = events;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Best-effort audit: a write failure is logged as a code and does not fail the caller. */
    public void record(UUID brainId, String action, LabAuditEvent.Status status,
                       LabAuditEvent.SubjectType subjectType, UUID subjectId, String failureCode,
                       Map<String, Object> counts) {
        try {
            write(brainId, action, status, subjectType, subjectId, failureCode, counts);
        } catch (RuntimeException unwritable) {
            writeFailures.incrementAndGet();
            // Class name only: a constraint violation's message can quote the offending row.
            log.warn("Lab audit row for {} could not be written ({})", action,
                    unwritable.getClass().getSimpleName());
        }
    }

    /**
     * Fail-closed audit for a sensitive read: if the row cannot be committed, the caller must not
     * complete. The propagated failure is deliberately the caller's own payload-free taxonomy, so
     * no persistence message travels out with it.
     */
    public void recordRequired(UUID brainId, String action, LabAuditEvent.Status status,
                               LabAuditEvent.SubjectType subjectType, UUID subjectId,
                               String failureCode, Map<String, Object> counts) {
        try {
            write(brainId, action, status, subjectType, subjectId, failureCode, counts);
        } catch (RuntimeException unwritable) {
            writeFailures.incrementAndGet();
            log.error("Lab sensitive read refused: audit row for {} could not be committed ({})",
                    action, unwritable.getClass().getSimpleName());
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.AUDIT_WRITE_FAILED);
        }
    }

    /** Audit writes attempted since startup, whether or not they landed. */
    public long writeAttempts() {
        return writeAttempts.get();
    }

    /**
     * Audit writes that failed since startup. Any non-zero value is an incident: the trail is
     * incomplete, and audit rows cannot be back-dated or repaired afterwards.
     */
    public long writeFailures() {
        return writeFailures.get();
    }

    private void write(UUID brainId, String action, LabAuditEvent.Status status,
                       LabAuditEvent.SubjectType subjectType, UUID subjectId, String failureCode,
                       Map<String, Object> counts) {
        writeAttempts.incrementAndGet();
        independent.executeWithoutResult(ignored -> {
            LabAuditEvent event = new LabAuditEvent();
            event.setBrainId(brainId);
            event.setAction(action);
            event.setStatus(status);
            event.setSubjectType(subjectType);
            event.setSubjectId(subjectId);
            event.setActor(actor());
            event.setCorrelationId(correlationId());
            event.setFailureCode(bounded(failureCode, FAILURE_CODE_MAX));
            event.setMetadata(counts == null || counts.isEmpty() ? null : Map.copyOf(counts));
            events.save(event);
        });
    }

    /** The proxy-supplied user when one exists, else the explicit anonymous prototype actor. */
    private static String actor() {
        String user = MDC.get(RequestCorrelationFilter.USER_MDC_KEY);
        return user == null || user.isBlank()
                ? LabAuditEvent.ANONYMOUS_ADMIN_ACTOR
                : bounded(user, ACTOR_MAX);
    }

    /** The request correlation id every Lab log line and error also carries. */
    public static String correlationId() {
        String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        return requestId == null || requestId.isBlank()
                ? null
                : bounded(requestId, CORRELATION_MAX);
    }

    /** The correlation id, or a freshly minted one when the request supplied none. */
    public static String correlationIdOrNew() {
        String existing = correlationId();
        return existing == null ? UUID.randomUUID().toString() : existing;
    }

    private static String bounded(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
