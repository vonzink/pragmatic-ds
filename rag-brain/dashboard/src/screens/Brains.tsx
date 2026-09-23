import { useCallback, useEffect, useState } from "react";
import { api, brainsApi, learningApi } from "../api";
import { BrainAdminDto, BrainCreateRequest, BrainUpdateRequest, SettingsResponse, SyncReport } from "../types";
import { ErrorNote, Pill, Status } from "../components";

function sourceSummary(b: BrainAdminDto): string {
  if (b.sourceType === "local") return `local: ${b.localPath ?? "—"}`;
  if (b.sourceType === "s3") return `s3: ${b.s3Bucket ?? "—"}${b.s3Prefix ? "/" + b.s3Prefix : ""}`;
  return "—";
}

export default function Brains() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [report, setReport] = useState<SyncReport | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [providers, setProviders] = useState<string[]>([]);
  const blankForm: BrainCreateRequest = {
    slug: "", displayName: "", packRef: "", disclaimer: "", sourceType: "local",
    s3Bucket: null, s3Prefix: null, s3Region: null, localPath: "",
    answerProvider: "anthropic", answerModel: "", utilityProvider: "openai", utilityModel: "",
  };
  const [form, setForm] = useState<BrainCreateRequest>(blankForm);
  const [generatePack, setGeneratePack] = useState(true);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<BrainAdminDto | null>(null);
  const [budgetStr, setBudgetStr] = useState("");

  const reload = useCallback(() => {
    brainsApi.list().then(setBrains).catch((e) => setError((e as Error).message));
  }, []);

  useEffect(reload, [reload]);

  useEffect(() => {
    api.get<SettingsResponse>("/api/ai/admin/settings")
      .then((s) => setProviders(s.providers.filter((p) => p.configured).map((p) => p.name)))
      .catch(() => setProviders([]));
  }, []);

  function set<K extends keyof BrainCreateRequest>(k: K, v: BrainCreateRequest[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  async function create() {
    setCreating(true); setError(null);
    try {
      const body: BrainCreateRequest = generatePack
        ? { ...form, packRef: undefined, disclaimer: form.disclaimer?.trim() || undefined }
        : { ...form, disclaimer: undefined };
      await brainsApi.create(body);
      setForm(blankForm); setGeneratePack(true);
      reload();
    } catch (e) { setError((e as Error).message); }
    finally { setCreating(false); }
  }

  function startEdit(b: BrainAdminDto) {
    setEditing(b);
    setGeneratePack(false);
    setBudgetStr(b.dailyCostBudgetUsd == null ? "" : String(b.dailyCostBudgetUsd));
    setForm({
      slug: b.slug, displayName: b.displayName, packRef: b.packRef ?? "", disclaimer: "",
      sourceType: b.sourceType === "s3" ? "s3" : "local",
      s3Bucket: b.s3Bucket, s3Prefix: b.s3Prefix, s3Region: b.s3Region, localPath: b.localPath,
      answerProvider: b.answerProvider ?? "", answerModel: b.answerModel ?? "",
      utilityProvider: b.utilityProvider ?? "", utilityModel: b.utilityModel ?? "",
    });
    setError(null);
  }

  function cancelEdit() {
    setEditing(null); setForm(blankForm); setGeneratePack(true); setBudgetStr("");
  }

  async function saveEdit() {
    if (!editing) return;
    const t = budgetStr.trim();
    const budget = t === "" ? null : Number(t);
    if (budget !== null && (!Number.isFinite(budget) || budget < 0)) {
      setError("Daily budget must be a non-negative number (or blank for the global default)");
      return;
    }
    setCreating(true); setError(null);
    try {
      const body: BrainUpdateRequest = {
        slug: form.slug, displayName: form.displayName, packRef: form.packRef ?? "",
        sourceType: form.sourceType,
        s3Bucket: form.s3Bucket, s3Prefix: form.s3Prefix, s3Region: form.s3Region,
        localPath: form.localPath,
        answerProvider: form.answerProvider, answerModel: form.answerModel,
        utilityProvider: form.utilityProvider, utilityModel: form.utilityModel,
        dailyCostBudgetUsd: budget,
      };
      await brainsApi.update(editing.id, body);
      cancelEdit();
      reload();
    } catch (e) { setError((e as Error).message); }
    finally { setCreating(false); }
  }

  async function removeBrain(b: BrainAdminDto) {
    if (!window.confirm(`Delete "${b.displayName}"? It stops answering and shows as disabled. `
        + `Its documents and history are kept — Restore re-enables it.`)) {
      return;
    }
    setBusy(b.id); setError(null);
    try { await brainsApi.remove(b.id); reload(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  async function restoreBrain(b: BrainAdminDto) {
    setBusy(b.id); setError(null);
    try { await brainsApi.restore(b.id); reload(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  const providerOptions = (current: string) =>
    providers.includes(current) || !current ? providers : [current, ...providers];

  async function setActive(b: BrainAdminDto) {
    setBusy(b.id); setError(null);
    try { await brainsApi.activate(b.id); reload(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  async function toggleLearning(b: BrainAdminDto) {
    setBusy(b.id); setError(null);
    try {
      const updated = await learningApi.toggle(b.id, !b.learningEnabled);
      setBrains((prev) => prev.map((x) => (x.id === updated.id ? updated : x)));
    }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  async function sync(b: BrainAdminDto) {
    setBusy(b.id); setError(null); setReport(null);
    try { setReport(await brainsApi.sync(b.id, false)); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  const summaryTone = (k: string): "green" | "amber" | "gray" =>
    k === "upload" || k === "update" ? "green" : k === "deactivate" ? "amber" : "gray";

  return (
    <>
      <header className="screen-head">
        <h1>Brains</h1>
        <span className="muted">create a brain, point it at a folder or bucket, sync, then set active</span>
      </header>
      <ErrorNote message={error} />
      <div className="card">
        <h2>{editing ? `Edit brain — ${editing.displayName}` : "Create brain"}</h2>
        <div className="setting-row">
          <label>Display name</label>
          <input value={form.displayName} onChange={(e) => set("displayName", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Slug (lowercase, a–z 0–9 -)</label>
          <input value={form.slug} onChange={(e) => set("slug", e.target.value)}
                 placeholder="lending" />
        </div>
        {!editing && (
          <div className="setting-row">
            <label>Pack</label>
            <div className="mode-toggle">
              <button className={generatePack ? "on" : ""}
                      onClick={() => setGeneratePack(true)}>Generate a starter pack (recommended)</button>
              <button className={!generatePack ? "on" : ""}
                      onClick={() => setGeneratePack(false)}>Use an existing pack</button>
            </div>
          </div>
        )}
        {generatePack && !editing ? (
          <div className="setting-row">
            <label>Disclaimer (optional)</label>
            <input value={form.disclaimer ?? ""} onChange={(e) => set("disclaimer", e.target.value)}
                   placeholder="Educational use only — verify against the source documents." />
          </div>
        ) : (
          <>
            <div className="setting-row">
              <label>Pack ref</label>
              <input value={form.packRef ?? ""} onChange={(e) => set("packRef", e.target.value)} />
            </div>
            <p className="muted">The pack's internal <code>slug</code> must equal this brain's slug. Copy <code>packs/_template</code> to a new folder, set its <code>slug</code>, and point here.</p>
          </>
        )}
        <div className="setting-row">
          <label>Source</label>
          <div className="mode-toggle">
            <button className={form.sourceType === "local" ? "on" : ""}
                    onClick={() => set("sourceType", "local")}>Local folder</button>
            <button className={form.sourceType === "s3" ? "on" : ""}
                    onClick={() => set("sourceType", "s3")}>S3</button>
          </div>
        </div>
        {form.sourceType === "local" ? (
          <div className="setting-row">
            <label>Folder path</label>
            <input value={form.localPath ?? ""} onChange={(e) => set("localPath", e.target.value)}
                   placeholder="/Users/you/corpora/lending" />
          </div>
        ) : (
          <>
            <div className="setting-row">
              <label>S3 bucket</label>
              <input value={form.s3Bucket ?? ""} onChange={(e) => set("s3Bucket", e.target.value)} />
            </div>
            <div className="setting-row">
              <label>S3 prefix</label>
              <input value={form.s3Prefix ?? ""} onChange={(e) => set("s3Prefix", e.target.value)} />
            </div>
            <div className="setting-row">
              <label>S3 region</label>
              <input value={form.s3Region ?? ""} onChange={(e) => set("s3Region", e.target.value)} />
            </div>
          </>
        )}
        <div className="setting-row">
          <label>Answer provider</label>
          <select value={form.answerProvider} onChange={(e) => set("answerProvider", e.target.value)}>
            <option value="">(inherit global default)</option>
            {providerOptions(form.answerProvider).map((p) => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Answer model</label>
          <input value={form.answerModel} onChange={(e) => set("answerModel", e.target.value)}
                 placeholder="blank = provider default" />
        </div>
        <div className="setting-row">
          <label>Utility provider</label>
          <select value={form.utilityProvider} onChange={(e) => set("utilityProvider", e.target.value)}>
            <option value="">(inherit global default)</option>
            {providerOptions(form.utilityProvider).map((p) => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Utility model</label>
          <input value={form.utilityModel} onChange={(e) => set("utilityModel", e.target.value)}
                 placeholder="blank = provider default" />
        </div>
        {editing && (
          <div className="setting-row">
            <label>Daily cost budget (USD)</label>
            <input value={budgetStr} onChange={(e) => setBudgetStr(e.target.value)}
                   placeholder="blank = global default" />
          </div>
        )}
        <div className="setting-row">
          <button className="btn-primary" onClick={editing ? saveEdit : create}
                  disabled={creating || !form.slug.trim() || !form.displayName.trim()
                            || (form.sourceType === "local" ? !form.localPath?.trim() : !form.s3Bucket?.trim())
                            || ((editing || !generatePack) && !form.packRef?.trim())}>
            {creating ? (editing ? "Saving…" : "Creating…") : (editing ? "Save brain" : "Create brain")}
          </button>
          {editing && <button onClick={cancelEdit} disabled={creating}>Cancel</button>}
        </div>
      </div>
      {report && (
        <div className="card sync-report">
          <div className="sync-summary">
            <strong>Sync finished</strong>
            {Object.entries(report.summary).map(([k, v]) => (
              <Pill key={k} tone={summaryTone(k)}>{v} {k}</Pill>
            ))}
          </div>
          {report.results.filter((r) => r.action !== "SKIP" || r.error).map((r) => (
            <div key={`${r.action}-${r.fileName}`} className="diff-line">
              <code>{r.action.toLowerCase()}</code>
              <span>{r.fileName}{r.reason ? ` — ${r.reason}` : ""}{r.error ? ` — FAILED: ${r.error}` : ""}</span>
            </div>
          ))}
        </div>
      )}
      <ul className="brain-list">
        {brains.map((b) => (
          <li key={b.id}
              className={`card brain-card${b.isDefault ? " current" : ""}${b.isActive ? "" : " paused"}`}>
            <div className="brain-card-head">
              <span className="mark" aria-hidden="true">
                {b.displayName.trim().charAt(0).toUpperCase() || "?"}
              </span>
              <div style={{ minWidth: 0 }}>
                <span className="brain-card-name">
                  {b.displayName}
                  {b.isDefault && <Pill tone="accent">current</Pill>}
                </span>
                <span className="brain-card-id">{b.slug} · {b.id.slice(0, 8)}</span>
              </div>
              {b.isDefault && <Status kind="active">active</Status>}
              {!b.isActive && <Status kind="inactive">disabled</Status>}
              {b.isActive && !b.isDefault && <Status kind="inactive">idle</Status>}
              <Status kind={b.learningEnabled ? "ok" : "inactive"}>
                {b.learningEnabled ? "learning on" : "learning off"}
              </Status>
              <div className="brain-card-actions">
                <button onClick={() => sync(b)} disabled={busy === b.id || !b.isActive}>
                  {busy === b.id ? "Working…" : "Sync now"}
                </button>
                <button onClick={() => setActive(b)} disabled={busy === b.id || b.isDefault || !b.isActive}>
                  Set active
                </button>
                <button onClick={() => toggleLearning(b)} disabled={busy === b.id || !b.isActive}>
                  {b.learningEnabled ? "Disable learning" : "Enable learning"}
                </button>
                <button onClick={() => startEdit(b)} disabled={busy === b.id}>
                  Edit
                </button>
                {b.isActive ? (
                  <button onClick={() => removeBrain(b)}
                          disabled={busy === b.id || b.isDefault}
                          title={b.isDefault ? "Set another brain active first" : "Soft delete — documents and history are kept"}>
                    Delete
                  </button>
                ) : (
                  <button onClick={() => restoreBrain(b)} disabled={busy === b.id}>
                    Restore
                  </button>
                )}
              </div>
            </div>
            <dl className="brain-metrics">
              <div><dt>Source</dt><dd>{sourceSummary(b)}</dd></div>
              <div>
                <dt>Answer model</dt>
                <dd>{b.answerProvider ?? "inherit"}{b.answerModel ? ` / ${b.answerModel}` : ""}</dd>
              </div>
              <div>
                <dt>Utility model</dt>
                <dd>{b.utilityProvider ?? "inherit"}{b.utilityModel ? ` / ${b.utilityModel}` : ""}</dd>
              </div>
              <div><dt>Pack</dt><dd>{b.packRef ?? "—"}</dd></div>
              <div>
                <dt>Daily budget</dt>
                <dd>{b.dailyCostBudgetUsd == null ? "global default" : `$${b.dailyCostBudgetUsd}`}</dd>
              </div>
            </dl>
          </li>
        ))}
      </ul>
    </>
  );
}
