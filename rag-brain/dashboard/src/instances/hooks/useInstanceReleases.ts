import { useEffect, useState } from "react";
import { instanceApi } from "../api";
import { asRows } from "../rows";
import type { ReleaseSummary } from "../types";

/**
 * Every release of one instance, live and candidate alike.
 *
 * This is the only place a comparison gets a release id, and that is deliberate. A release is
 * immutable and already validated; naming one is naming an exact set of prompts, tools, corpus and
 * model that something was checked against. There is no transient "run this with a different
 * model" path anywhere in this UI, because there is none on the server either — varying a model
 * means building a candidate release that carries it, which is the wizard's job.
 */
export function useInstanceReleases(brainId: string, instanceSlug: string) {
  const [releases, setReleases] = useState<ReleaseSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!instanceSlug) { setReleases(null); return; }
    let cancelled = false;
    setError(null);

    instanceApi
      .get<ReleaseSummary[]>(
        `/api/ai/admin/instances/${encodeURIComponent(instanceSlug)}/releases`
        + `?brain=${encodeURIComponent(brainId)}`)
      .then((rows) => { if (!cancelled) setReleases(asRows(rows)); })
      .catch((e) => { if (!cancelled) { setReleases([]); setError((e as Error).message); } });

    return () => { cancelled = true; };
  }, [brainId, instanceSlug]);

  return { releases, error };
}
