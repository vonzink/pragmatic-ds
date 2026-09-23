package com.pragmaticds.rag.service.sync;

import java.util.List;
import java.util.Map;

/**
 * What POST /api/ai/documents/sync returns: the plan and what happened.
 *
 * <p>{@code refused}/{@code refusedReason} report the mass-deactivation guard
 * (see {@link SyncPlanner#massDeactivationReason}): when the guard trips and
 * {@code force=false}, nothing executes and {@code refusedReason} names the
 * branch that tripped with its counts. Dry runs still compute the guard so the
 * report warns, even though a dry run never executes anyway.
 *
 * <p>{@code refusedReason} always carries the guard's reason whenever the
 * guard tripped, even when {@code force=true} overrode it — so
 * {@code refused=false && refusedReason!=null} means "guard tripped but
 * overridden by force; the run executed". This is the audit trail of what
 * the override pushed past.
 */
public record SyncReport(boolean dryRun,
                         Map<String, Integer> summary,
                         List<Result> results,
                         boolean refused,
                         String refusedReason) {

    /** An un-refused report (guard not tripped) — the pre-guard shape. */
    public SyncReport(boolean dryRun, Map<String, Integer> summary, List<Result> results) {
        this(dryRun, summary, results, false, null);
    }

    public record Result(String fileName, String action, String reason,
                         boolean executed, boolean succeeded, String error) {}
}
