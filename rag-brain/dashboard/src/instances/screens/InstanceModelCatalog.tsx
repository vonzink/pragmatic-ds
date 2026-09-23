import { useEffect, useState } from "react";
import { instanceApi } from "../api";
import type { CatalogModelView } from "../types";

/**
 * What this deployment can actually run.
 *
 * Read-only, and that is the design rather than a shortcut. The catalog is derived from
 * configuration and filtered by which provider credentials exist, so a model missing here is a
 * model that would fail at dispatch. An editable free-text model field would let someone type a
 * name that looks right and discover at run time — after paying for the attempt — that it is not
 * configured. There are no keys on this screen and nothing to submit.
 *
 * Rates are shown per million tokens exactly as the catalog states them, next to the ceilings that
 * bound a run. Together they are what makes a preflight estimate checkable by hand.
 */
export default function InstanceModelCatalog() {
  const [models, setModels] = useState<CatalogModelView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    // Deployment-wide: this endpoint takes no brain, and none is appended to it.
    instanceApi
      .get<CatalogModelView[]>("/api/ai/admin/instances/model-catalog")
      .then((rows) => { if (!cancelled) { setModels(rows); setLoading(false); } })
      .catch((e) => { if (!cancelled) { setError((e as Error).message); setLoading(false); } });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="screen">
      <header className="screen-head">
        <h1>Models and providers</h1>
      </header>

      <p className="muted screen-intro">
        Every model configured for this deployment whose provider credential is present. A model
        absent from this list cannot be selected, because a run pinned to it would fail at dispatch.
      </p>

      {loading && <p className="muted">Loading catalog…</p>}
      {error && <div className="error-note" role="alert">Could not load the catalog. {error}</div>}

      {!loading && !error && models.length === 0 && (
        <div className="empty-note">
          <p>No models are configured.</p>
          <p className="muted">
            Until a provider credential is present and a model is declared in configuration, no
            instance can be run.
          </p>
        </div>
      )}

      {models.length > 0 && (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="sr-only">Configured models and their rates</caption>
            <thead>
              <tr>
                <th scope="col">Provider</th>
                <th scope="col">Model</th>
                <th scope="col">Context ceiling</th>
                <th scope="col">Output ceiling</th>
                <th scope="col">Input $/M</th>
                <th scope="col">Cached input $/M</th>
                <th scope="col">Output $/M</th>
                <th scope="col">Token estimate</th>
              </tr>
            </thead>
            <tbody>
              {models.map((model) => (
                <tr key={`${model.provider}:${model.model}`}>
                  <td>{model.provider}</td>
                  <td className="mono">{model.model}</td>
                  <td className="num">{model.contextTokenCeiling.toLocaleString()}</td>
                  <td className="num">{model.outputTokenCeiling.toLocaleString()}</td>
                  <td className="num">{model.inputUsdPerMillion}</td>
                  <td className="num">{model.cachedInputUsdPerMillion}</td>
                  <td className="num">{model.outputUsdPerMillion}</td>
                  {/* The tokenizer strategy is what decides whether an estimate is exact or a
                      conservative range, so it belongs beside the rates it multiplies. */}
                  <td>{model.tokenizerStrategy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
