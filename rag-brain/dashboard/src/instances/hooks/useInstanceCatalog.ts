import { useCallback, useEffect, useState } from "react";
import { brainsApi } from "../../api";
import { instanceApi } from "../api";
import type { BrainAdminDto } from "../../types";
import type { InstanceSummary } from "../types";

/**
 * Every instance the operator can reach, across every brain.
 *
 * The backend has no cross-brain listing — `GET /instances` requires one explicit brain UUID —
 * so this fans out: list the brains, then ask each one for its instances. That is the shape the
 * landing page needs, because the whole point of the page is that you choose an instance without
 * first choosing a brain.
 *
 * **One brain failing must not empty the page.** A deployment can carry brains an operator has no
 * business in, or one whose row is mid-migration, and losing the other nine instances because the
 * tenth errored would make the screen useless exactly when someone is trying to find out what is
 * wrong. So failures are collected per brain and reported beside the results that did load.
 */

export interface InstanceWithBrain extends InstanceSummary {
  /** Secondary text on the card. The instance is the subject; this is provenance. */
  brainDisplayName: string;
}

export interface InstanceCatalog {
  instances: InstanceWithBrain[];
  /** Display names of brains whose listing failed. Empty when everything loaded. */
  unavailableBrains: string[];
  loading: boolean;
  /** Set only when the brain list itself failed, which leaves nothing to show at all. */
  error: string | null;
  reload: () => void;
}

export function useInstanceCatalog(): InstanceCatalog {
  const [instances, setInstances] = useState<InstanceWithBrain[]>([]);
  const [unavailableBrains, setUnavailable] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    (async () => {
      let brains: BrainAdminDto[];
      try {
        brains = await brainsApi.list();
      } catch (e) {
        // Nothing to partially succeed at: without the brain list there is no fan-out to make.
        if (!cancelled) {
          setError((e as Error).message);
          setInstances([]);
          setUnavailable([]);
          setLoading(false);
        }
        return;
      }

      // allSettled, not all: one rejection must not discard the brains that answered.
      const results = await Promise.allSettled(
        brains.map((brain) =>
          instanceApi
            .get<InstanceSummary[]>(
              `/api/ai/admin/instances?brain=${encodeURIComponent(brain.id)}`)
            .then((rows) => rows.map((row) => ({
              ...row,
              brainDisplayName: brain.displayName,
            })))),
      );

      if (cancelled) return;

      const loaded: InstanceWithBrain[] = [];
      const failed: string[] = [];
      results.forEach((result, index) => {
        if (result.status === "fulfilled") loaded.push(...result.value);
        else failed.push(brains[index].displayName);
      });

      // Stable order so the page does not reshuffle between loads: brain, then instance.
      loaded.sort((a, b) =>
        a.brainDisplayName.localeCompare(b.brainDisplayName)
        || a.displayName.localeCompare(b.displayName));

      setInstances(loaded);
      setUnavailable(failed);
      setLoading(false);
    })();

    return () => { cancelled = true; };
  }, [nonce]);

  return { instances, unavailableBrains, loading, error, reload };
}
