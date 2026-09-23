package com.pragmaticds.rag.lab.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives the dispatcher on a fixed interval, and sweeps expired leases alongside it.
 *
 * <p><b>One claim per tick, executed off the poll thread.</b> Claiming is cheap and serialized
 * across the cluster; executing is slow and must not block the next tick. Handing the claimed
 * member to a bounded executor keeps the poll loop responsive while the concurrency limits, not
 * the executor's size, remain the thing that decides how much runs at once.
 *
 * <p>This bean exists only while {@code ragbrain.instances.execution.enabled} is true, so a
 * deployment with execution off constructs no poller and no dispatcher at all.
 * {@link RunGroupRecoveryService} is the one thing that outlives the execution switch: it settles
 * members already in flight rather than dispatching new ones, so it is gated on the parent alone
 * and schedules its own sweep while dispatch is off. It stands that sweep down while this poller
 * exists, so recovery still happens exactly once per tick here, ahead of the claim.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances.execution", name = "enabled",
        havingValue = "true")
public class RunGroupPoller {

    private static final Logger log = LoggerFactory.getLogger(RunGroupPoller.class);

    private final RunGroupDispatcher dispatcher;
    private final RunGroupRecoveryService recovery;
    private final TaskExecutor executor;

    public RunGroupPoller(RunGroupDispatcher dispatcher,
                          RunGroupRecoveryService recovery,
                          @org.springframework.beans.factory.annotation.Qualifier(
                                  "instanceRunExecutor") TaskExecutor executor) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * One tick: reclaim what crashed, then claim at most one member.
     *
     * <p>Recovery first, so a node that died holding slots gives them back before this tick tries
     * to decide whether any are free.
     */
    @Scheduled(fixedDelayString = "${ragbrain.instances.execution.poll-interval:PT5S}")
    public void tick() {
        try {
            recovery.recoverExpired();
        } catch (RuntimeException failure) {
            // Class name only, and the tick continues: a recovery failure must not stop dispatch.
            log.warn("Instance run recovery sweep failed ({})",
                    failure.getClass().getSimpleName());
        }
        try {
            Optional<UUID> claimed = dispatcher.claimNext();
            claimed.ifPresent(runId -> executor.execute(() -> {
                try {
                    dispatcher.executeClaimed(runId);
                } catch (RuntimeException failure) {
                    // executeClaimed already moves the member to a terminal state; anything
                    // escaping it is a bug, logged without a message that could quote a body.
                    log.error("Instance run {} escaped its own error handling ({})",
                            runId, failure.getClass().getSimpleName());
                }
            }));
        } catch (RuntimeException failure) {
            log.warn("Instance run claim failed ({})", failure.getClass().getSimpleName());
        }
    }
}
