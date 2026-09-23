package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.model.InstanceUsageService;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.ops.InstanceControlMetrics;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.LabSpendReservation;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.run.repository.LabSpendReservationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Rolls a group up from its members, and cancels the ones that have not started.
 *
 * <p><b>A member never decides for its siblings.</b> One failure does not cancel the rest of a
 * comparison, because a comparison where one model errored still tells you about the others.
 * Mixed outcomes roll up to {@code PARTIAL}, which is a real result and not a euphemism for
 * failure.
 *
 * <p><b>Only queued members can be cancelled.</b> A PROCESSING member has a provider call in
 * flight that nothing here can recall, and marking it cancelled would claim knowledge the process
 * does not have. It keeps the existing crash-honest semantics: its lease expires and it becomes
 * INTERRUPTED, which says exactly what is true — nobody knows whether the provider ran.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class RunGroupStatusService {

    private final LabRunGroupRepository groups;
    private final LabRunRepository runs;
    private final LabSpendReservationRepository reservations;
    private final InstanceUsageService usage;
    private final InstanceControlMetrics metrics;
    private final TransactionTemplate isolated;

    public RunGroupStatusService(LabRunGroupRepository groups,
                                 LabRunRepository runs,
                                 LabSpendReservationRepository reservations,
                                 InstanceUsageService usage,
                                 InstanceControlMetrics metrics,
                                 PlatformTransactionManager transactionManager) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.isolated = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Recomputes a group's status from its members, once they have all finished.
     *
     * <p>Idempotent and safe to call after every member transition: while any member is still
     * live the group stays live, and a group that has already reached a terminal state is left
     * alone rather than rewritten.
     */
    public Optional<LabRunGroup.Status> rollUp(UUID groupId) {
        return Optional.ofNullable(isolated.execute(status -> {
            LabRunGroup group = groups.findById(groupId).orElse(null);
            if (group == null || isTerminal(group.getStatus())) {
                return null;
            }
            List<LabRun> members = runs.findByRunGroupIdOrderByMemberIndexAsc(groupId);
            if (members.isEmpty()) {
                return null;
            }
            boolean anyLive = members.stream().anyMatch(member ->
                    member.getStatus() == LabRun.Status.QUEUED
                            || member.getStatus() == LabRun.Status.PROCESSING);
            if (anyLive) {
                // One member starting is what moves a group out of QUEUED; the rest of the roll-up
                // waits until nothing is still running.
                if (group.getStatus() == LabRunGroup.Status.QUEUED
                        && members.stream().anyMatch(m -> m.getStatus() != LabRun.Status.QUEUED)) {
                    group.setStatus(LabRunGroup.Status.PROCESSING);
                    groups.saveAndFlush(group);
                    return LabRunGroup.Status.PROCESSING;
                }
                return null;
            }

            LabRunGroup.Status rolled = terminalFor(members);
            group.setStatus(rolled);
            group.setTerminalAt(OffsetDateTime.now());
            groups.saveAndFlush(group);
            return rolled;
        }));
    }

    /**
     * Cancels every member that has not been claimed yet, and releases what they reserved.
     *
     * <p>Members already PROCESSING are left running and reported back, so a caller can see that
     * cancellation was partial rather than being told it succeeded.
     */
    public CancellationOutcome cancel(UUID brainId, UUID groupId) {
        CancellationOutcome outcome = isolated.execute(status -> {
            LabRunGroup group = groups.findByIdAndBrainId(groupId, brainId).orElse(null);
            if (group == null) {
                throw new IllegalArgumentException("RUN_GROUP_NOT_FOUND");
            }
            if (group.getCancellationRequestedAt() == null) {
                group.setCancellationRequestedAt(OffsetDateTime.now());
            }

            int cancelled = 0;
            int stillRunning = 0;
            List<LabRun> members = runs.findByRunGroupIdOrderByMemberIndexAsc(groupId);
            for (LabRun member : members) {
                if (member.getStatus() == LabRun.Status.QUEUED) {
                    member.setStatus(LabRun.Status.CANCELLED);
                    member.setTerminalAt(OffsetDateTime.now());
                    runs.saveAndFlush(member);
                    metrics.runTerminal(member, group.getMode());
                    // Money held for work that will never happen goes back to the brain's budget
                    // immediately, rather than waiting for a sweeper.
                    releaseReservation(member.getId(), brainId);
                    cancelled++;
                } else if (member.getStatus() == LabRun.Status.PROCESSING) {
                    stillRunning++;
                }
            }
            if (!isTerminal(group.getStatus())) {
                // Rolled up here, in this same transaction, not via rollUp(): the dispatcher and
                // the recovery sweep are the only other roll-up callers and both live behind the
                // execution flag, so a cancellation that empties the group of live members must
                // conclude the group itself — otherwise, on a deployment that never dispatches,
                // a fully-cancelled group would sit QUEUED forever: unpollable as finished,
                // unsweepable by retention, unpurgeable by hand.
                if (stillRunning == 0 && !members.isEmpty()) {
                    group.setStatus(terminalFor(members));
                    group.setTerminalAt(OffsetDateTime.now());
                }
                groups.saveAndFlush(group);
            }
            return new CancellationOutcome(cancelled, stillRunning);
        });
        // After the cancellation is committed: a member cancelled before any call happened must
        // not hold a PENDING usage row forever. Settled outside the transaction so a rollback
        // cannot leave a live member's measurement already closed; report() is idempotent past
        // PENDING, so members cancelled by an earlier call are left exactly as they are.
        for (LabRun member : runs.findByRunGroupIdOrderByMemberIndexAsc(groupId)) {
            if (member.getStatus() == LabRun.Status.CANCELLED) {
                usage.report(member.getId(), brainId,
                        new ModelEstimate.ProviderUsage(null, null, null));
            }
        }
        return outcome;
    }

    /** Releases one member's reservation, if it still holds one. */
    public void releaseReservation(UUID runId, UUID brainId) {
        reservations.findByRunIdAndBrainId(runId, brainId)
                .filter(held -> held.getStatus() == LabSpendReservation.Status.RESERVED)
                .ifPresent(held -> {
                    held.settle(LabSpendReservation.Status.RELEASED);
                    reservations.saveAndFlush(held);
                    metrics.reservation(LabSpendReservation.Status.RELEASED);
                });
    }

    /** Marks one member's reservation spent. */
    public void consumeReservation(UUID runId, UUID brainId) {
        reservations.findByRunIdAndBrainId(runId, brainId)
                .filter(held -> held.getStatus() == LabSpendReservation.Status.RESERVED)
                .ifPresent(held -> {
                    held.settle(LabSpendReservation.Status.CONSUMED);
                    reservations.saveAndFlush(held);
                    metrics.reservation(LabSpendReservation.Status.CONSUMED);
                });
    }

    /** How much of a cancellation actually took effect. */
    public record CancellationOutcome(int cancelledMembers, int stillProcessingMembers) {}

    /**
     * The group's terminal state.
     *
     * <p>All succeeded is SUCCEEDED, all cancelled is CANCELLED, nothing succeeded is FAILED, and
     * anything mixed is PARTIAL. PARTIAL exists because a comparison where one model errored is
     * still worth reading; collapsing it to FAILED would throw away the members that worked.
     */
    private static LabRunGroup.Status terminalFor(List<LabRun> members) {
        boolean anySucceeded = members.stream()
                .anyMatch(member -> member.getStatus() == LabRun.Status.SUCCEEDED);
        boolean allSucceeded = members.stream()
                .allMatch(member -> member.getStatus() == LabRun.Status.SUCCEEDED);
        boolean allCancelled = members.stream()
                .allMatch(member -> member.getStatus() == LabRun.Status.CANCELLED);
        if (allSucceeded) {
            return LabRunGroup.Status.SUCCEEDED;
        }
        if (allCancelled) {
            return LabRunGroup.Status.CANCELLED;
        }
        return anySucceeded ? LabRunGroup.Status.PARTIAL : LabRunGroup.Status.FAILED;
    }

    private static boolean isTerminal(LabRunGroup.Status status) {
        return status == LabRunGroup.Status.SUCCEEDED || status == LabRunGroup.Status.PARTIAL
                || status == LabRunGroup.Status.FAILED || status == LabRunGroup.Status.CANCELLED;
    }
}
