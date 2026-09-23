import { useEffect, useState } from "react";
import { api } from "../api";
import { SettingsResponse } from "../types";
import { ErrorNote, Meter, Pill, Status } from "../components";

/* Routing, retrieval tuning and limits in one place. The same settings endpoint
   and draft/save semantics as before, laid out per the redesign's screen 13.
   The design's budget cap and spend meter have no endpoint yet, so they are
   absent rather than mocked. */

const ROUTING_FIELDS = [
  { key: "answer.provider", label: "Answer provider", kind: "select" },
  { key: "answer.model", label: "Answer model", kind: "text" },
  { key: "utility.provider", label: "Utility provider", kind: "select" },
  { key: "utility.model", label: "Utility model", kind: "text" },
] as const;

export default function Settings() {
  const [data, setData] = useState<SettingsResponse | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const load = () => api.get<SettingsResponse>("/api/ai/admin/settings")
    .then((d) => { setData(d); setDraft({}); }).catch((e) => setError(e.message));

  useEffect(() => { load(); }, []);

  async function save() {
    setError(null); setSaved(false);
    try {
      setData(await api.put<SettingsResponse>("/api/ai/admin/settings", draft));
      setDraft({}); setSaved(true);
    } catch (e) { setError((e as Error).message); }
  }

  async function clearOverride(key: string) {
    setError(null);
    try { setData(await api.put<SettingsResponse>("/api/ai/admin/settings", { [key]: "" })); }
    catch (e) { setError((e as Error).message); }
  }

  if (!data) return <h1>Models &amp; providers</h1>;
  const effective = (key: string) => String(data.effective[key] ?? "");
  const value = (key: string) => draft[key] ?? effective(key);
  const overridden = Object.keys(data.overrides);
  const configuredProviders = data.providers.filter((p) => p.configured).map((p) => p.name);

  function providerOptions(selectKey: string) {
    const current = value(selectKey);
    const options = [...configuredProviders];
    if (current && !options.includes(current)) options.unshift(current);
    return options;
  }

  function overrideBadge(key: string) {
    return data!.overrides[key] !== undefined ? <Pill tone="amber">override</Pill> : null;
  }
  function resetButton(key: string) {
    return data!.overrides[key] !== undefined
      ? <button onClick={() => clearOverride(key)}>Reset</button> : null;
  }

  const inUse = new Set([value("answer.provider"), value("utility.provider")].filter(Boolean));
  const threshold = Number.parseFloat(value("retrieval.confidence-threshold"));
  const topK = Number.parseInt(value("retrieval.top-k"), 10);
  const reranking = value("rerank.enabled") === "true";

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Models &amp; providers</h1>
          <p>Changes go live within ~10 s, no restart. A model name never crosses providers.</p>
        </div>
        <div className="actions">
          {overridden.length > 0 && (
            <span className="overrides-note">
              {overridden.length} override{overridden.length > 1 ? "s" : ""} active
            </span>
          )}
          {saved && <Status kind="ok">saved</Status>}
          <button className="btn-primary" onClick={save} disabled={Object.keys(draft).length === 0}>
            Save changes
          </button>
        </div>
      </header>
      <ErrorNote message={error} />

      {data.providers.length > 0 && (
        <>
          <div className="provider-row">
            {data.providers.map((p) => (
              <div key={p.name}
                   className={`provider-card${p.configured ? "" : " off"}${inUse.has(p.name) ? " in-use" : ""}`}>
                <Status kind={p.configured ? "ok" : "inactive"}>{p.name}</Status>
                <span className="hint">
                  {!p.configured ? "no key in .env" : inUse.has(p.name) ? "in use" : "configured"}
                </span>
              </div>
            ))}
          </div>
          <p className="hint" style={{ margin: "-8px 0 16px" }}>
            To activate a provider, add its API key to .env and restart the brain (see RUNBOOK).
          </p>
        </>
      )}

      <div className="grid-2 even">
        <div className="card rows-150">
          <span className="section-label">Model routing</span>
          {ROUTING_FIELDS.map((f) => (
            <div key={f.key} className="setting-row">
              <label>{f.label}{overrideBadge(f.key)}</label>
              {f.kind === "select" ? (
                <select value={value(f.key)}
                        onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}>
                  {providerOptions(f.key).map((p) => <option key={p}>{p}</option>)}
                </select>
              ) : (
                <input className="mono" value={value(f.key)}
                       placeholder="blank = provider default"
                       onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })} />
              )}
              {resetButton(f.key)}
            </div>
          ))}
          <p className="hint" style={{ marginTop: 10 }}>
            Blank model means the provider's own default. Utility handles rewriting,
            classification and reranking.
          </p>
        </div>

        <div className="card rows-150">
          <span className="section-label">Retrieval &amp; limits</span>
          <div className="setting-row">
            <label>Confidence threshold{overrideBadge("retrieval.confidence-threshold")}</label>
            <input className="mono" style={{ maxWidth: 90, flex: "0 1 90px" }}
                   value={value("retrieval.confidence-threshold")}
                   onChange={(e) => setDraft({ ...draft, "retrieval.confidence-threshold": e.target.value })} />
            <span style={{ flex: 1, minWidth: 60 }}>
              <Meter ratio={Number.isFinite(threshold) ? threshold : 0} size="sm" />
            </span>
            {resetButton("retrieval.confidence-threshold")}
            <span className="setting-note">Below this the answer escalates instead of being returned.</span>
          </div>
          <div className="setting-row">
            <label>Top-K chunks{overrideBadge("retrieval.top-k")}</label>
            <input className="mono" style={{ maxWidth: 90, flex: "0 1 90px" }}
                   value={value("retrieval.top-k")}
                   onChange={(e) => setDraft({ ...draft, "retrieval.top-k": e.target.value })} />
            <span style={{ flex: 1, minWidth: 60 }}>
              <Meter ratio={Number.isFinite(topK) ? Math.min(topK, 20) / 20 : 0} size="sm" tone="mid" />
            </span>
            {resetButton("retrieval.top-k")}
            <span className="setting-note">More chunks means better recall and higher cost per run.</span>
          </div>
          <div className="setting-row">
            <label>LLM reranking{overrideBadge("rerank.enabled")}</label>
            <button type="button" role="switch" aria-checked={reranking}
                    aria-label="LLM reranking"
                    className={reranking ? "switch on" : "switch"}
                    onClick={() => setDraft({ ...draft, "rerank.enabled": reranking ? "false" : "true" })} />
            <span className="muted">{reranking ? "enabled" : "disabled"}</span>
            {resetButton("rerank.enabled")}
            <span className="setting-note">Second-pass rerank of retrieved chunks by the utility model.</span>
          </div>
        </div>
      </div>
    </>
  );
}
