import { useEffect, useRef, useState } from "react";
import { instanceApi } from "../api";
import { TERMINAL_GROUP_STATUSES } from "../types";
import type { RunGroupDetailView } from "../types";

/**
 * Watches one run group until it stops changing.
 *
 * Three properties matter more than the polling itself.
 *
 * **It stops.** A terminal group never transitions again, so continuing to ask is pure cost —
 * against the admin API, against the operator's battery, and against the log. Unmount stops it
 * too, which is what keeps a closed tab from polling forever.
 *
 * **It backs off.** A group that has been queued for ten minutes does not deserve the same
 * attention as one submitted two seconds ago. The interval grows toward a ceiling, so a workbench
 * left open overnight settles to one request a minute rather than thousands.
 *
 * **A failed poll is not a failed run.** The admin API can 503, a laptop can sleep, a proxy can
 * drop one request. None of that means the run went wrong, so the last good response is retained
 * and shown while retries continue. Blanking the panel on a transient error would tell the
 * operator their run vanished.
 */

const FIRST_DELAY_MS = 1_000;
const MAX_DELAY_MS = 60_000;
const BACKOFF_FACTOR = 1.6;

export interface RunGroupPolling {
  /** The last response that arrived, retained across transient failures. */
  detail: RunGroupDetailView | null;
  /** True until the first response, successful or not. */
  loading: boolean;
  /** Set when the most recent poll failed. `detail` may still hold the last good value. */
  transientError: string | null;
  /** True once the group reached a status it cannot leave, at which point polling has stopped. */
  settled: boolean;
}

export function useRunGroupPolling(
  brainId: string,
  groupId: string | null,
): RunGroupPolling {
  const [detail, setDetail] = useState<RunGroupDetailView | null>(null);
  const [loading, setLoading] = useState(false);
  const [transientError, setTransientError] = useState<string | null>(null);
  const [settled, setSettled] = useState(false);

  // A ref, not state: the timer callback must see the current value without the effect
  // re-subscribing on every tick and resetting the backoff it just grew.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!groupId) {
      setDetail(null);
      setLoading(false);
      setSettled(false);
      return;
    }

    let cancelled = false;
    let delay = FIRST_DELAY_MS;
    setLoading(true);
    setSettled(false);
    setTransientError(null);

    async function poll() {
      if (cancelled) return;
      try {
        const next = await instanceApi.get<RunGroupDetailView>(
          `/api/ai/admin/instances/run-groups/${encodeURIComponent(groupId!)}`
          + `?brain=${encodeURIComponent(brainId)}`);
        if (cancelled) return;

        setDetail(next);
        setLoading(false);
        setTransientError(null);

        if (TERMINAL_GROUP_STATUSES.includes(next.group.status)) {
          // Nothing will change again. Asking anyway is cost with no possible answer.
          setSettled(true);
          return;
        }
      } catch (e) {
        if (cancelled) return;
        // Deliberately does not clear `detail`: one dropped request is not a lost run.
        setTransientError((e as Error).message);
        setLoading(false);
      }

      if (cancelled) return;
      timer.current = setTimeout(poll, delay);
      delay = Math.min(Math.round(delay * BACKOFF_FACTOR), MAX_DELAY_MS);
    }

    void poll();

    return () => {
      cancelled = true;
      if (timer.current) clearTimeout(timer.current);
    };
  }, [brainId, groupId]);

  return { detail, loading, transientError, settled };
}
