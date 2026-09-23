import { useEffect, useState } from "react";
import { instanceApi } from "../api";
import type { WizardOptions } from "../types";

/**
 * What this deployment can author, straight from the deployment.
 *
 * Nothing here is remembered between sessions and nothing has a client-side default. A wizard that
 * filled in an envelope version or a schema digest from memory would author releases that validate
 * today and are refused after the next change to either — with a digest mismatch that names no
 * file and no field.
 *
 * A failure leaves `options` null, which every step reads as "the parser contract has not been
 * loaded" and refuses to advance on. Failing closed is the point: the alternative is authoring
 * against guesses.
 */
export function useWizardOptions() {
  const [options, setOptions] = useState<WizardOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    instanceApi
      .get<WizardOptions>("/api/ai/admin/instances/wizard-options")
      .then((view) => { if (!cancelled) { setOptions(view); setLoading(false); } })
      .catch((e) => {
        if (cancelled) return;
        setOptions(null);
        setError((e as Error).message);
        setLoading(false);
      });
    return () => { cancelled = true; };
  }, []);

  return { options, loading, error };
}
