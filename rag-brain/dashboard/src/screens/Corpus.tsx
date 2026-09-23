import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Search, X } from "lucide-react";
import { api } from "../api";
import { DocumentDto, DocumentUpdate, IngestionQuality, Stats, SyncReport } from "../types";
import { ErrorNote, Meter, Pill, RowMenu, Status } from "../components";

function sourceTypeTone(sourceType: string): "green" | "accent" | "blue" | "purple" {
  switch (sourceType) {
    case "AGENCY_GUIDELINE": return "green";
    case "INVESTOR_OVERLAY": return "accent";
    case "INTERNAL_POLICY": return "purple";
    default: return "blue";
  }
}

const SOURCE_TYPES = [
  ["AGENCY_GUIDELINE", "agency guideline"],
  ["INTERNAL_POLICY", "internal policy"],
  ["INVESTOR_OVERLAY", "investor overlay"],
  ["EDUCATIONAL", "educational"],
] as const;

const PAGE_SIZE = 25;

export default function Corpus({ stats, onCorpusChanged }:
    { stats: Stats | null; onCorpusChanged: () => void }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const brain = searchParams.get("brain") || null;
  const scope = searchParams.get("scope") || null;
  const [docs, setDocs] = useState<DocumentDto[]>([]);
  const [quality, setQuality] = useState<IngestionQuality | null>(null);
  const [report, setReport] = useState<SyncReport | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);

  // Toolbar filters. Scope lives in the URL (shared with deep links); the rest
  // are per-visit. Every filter change lands back on the first page.
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState<"" | "active" | "inactive">("");
  const [page, setPage] = useState(0);

  const [showAdd, setShowAdd] = useState(false);
  const [addBusy, setAddBusy] = useState(false);
  const [addFile, setAddFile] = useState<File | null>(null);
  const [addTitle, setAddTitle] = useState("");
  const [addSourceName, setAddSourceName] = useState("");
  const [addSourceType, setAddSourceType] = useState("AGENCY_GUIDELINE");
  const [addVisibility, setAddVisibility] = useState<"PUBLIC" | "INTERNAL" | "SECURE">("INTERNAL");
  const [addTrustLevel, setAddTrustLevel] = useState<"AUTHORITATIVE" | "APPROVED" | "REFERENCE" | "EXPERIMENTAL" | "BLOCKED">("APPROVED");
  const [addEffectiveDate, setAddEffectiveDate] = useState("");

  const withBrain = useCallback((path: string) =>
    brain ? `${path}${path.includes("?") ? "&" : "?"}brain=${encodeURIComponent(brain)}` : path,
  [brain]);

  function updateScope(next: string) {
    const params = new URLSearchParams(searchParams);
    if (next) params.set("scope", next); else params.delete("scope");
    setSearchParams(params, { replace: true });
    setPage(0);
  }

  function clearFilters() {
    setSearch("");
    setTypeFilter("");
    setStatusFilter("");
    updateScope("");
  }

  async function submitAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!addFile || !addTitle.trim() || !addSourceName.trim()) return;
    setAddBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append("file", addFile);
      form.append("title", addTitle.trim());
      form.append("sourceName", addSourceName.trim());
      form.append("sourceType", addSourceType);
      form.append("visibility", addVisibility);
      form.append("trustLevel", addTrustLevel);
      if (addEffectiveDate) form.append("effectiveDate", addEffectiveDate);
      if (brain) form.append("brain", brain);
      await api.upload("/api/ai/documents/upload", form);
      setShowAdd(false);
      setAddFile(null); setAddTitle(""); setAddSourceName(""); setAddVisibility("INTERNAL");
      setAddTrustLevel("APPROVED"); setAddEffectiveDate("");
      reload(); onCorpusChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAddBusy(false);
    }
  }

  const [editing, setEditing] = useState<DocumentDto | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const [editForm, setEditForm] = useState<DocumentUpdate>({
    title: "", sourceName: "", sourceType: "AGENCY_GUIDELINE",
    visibility: "INTERNAL", trustLevel: "APPROVED",
    documentVersion: null, effectiveDate: null, expirationDate: null,
  });

  function openEdit(d: DocumentDto) {
    setEditing(d);
    setEditForm({
      title: d.title, sourceName: d.sourceName, sourceType: d.sourceType,
      visibility: d.visibility, trustLevel: d.trustLevel,
      documentVersion: d.documentVersion, effectiveDate: d.effectiveDate,
      expirationDate: d.expirationDate,
    });
  }

  async function submitEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!editing || !editForm.title.trim() || !editForm.sourceName.trim()) return;
    setEditBusy(true);
    setError(null);
    try {
      await api.patch(`/api/ai/documents/${editing.id}`, editForm);
      setEditing(null);
      reload(); onCorpusChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setEditBusy(false);
    }
  }

  async function remove(d: DocumentDto) {
    if (!window.confirm(`Delete "${d.title}"? This removes the file, its search chunks, and the record. This cannot be undone.`)) {
      return;
    }
    setBusy(d.id);
    setError(null);
    try {
      await api.del(`/api/ai/documents/${d.id}`);
      reload(); onCorpusChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const reloadQuality = useCallback(() => {
    api.get<IngestionQuality>(withBrain("/api/ai/admin/ingestion-quality"))
      .then(setQuality)
      .catch((e) => setError(e.message));
  }, [withBrain]);

  const reload = useCallback(() => {
    api.get<DocumentDto[]>(withBrain("/api/ai/documents")).then(setDocs).catch((e) => setError(e.message));
    reloadQuality();
  }, [withBrain, reloadQuality]);

  useEffect(reload, [reload]);

  async function runSync(dryRun: boolean) {
    setBusy(dryRun ? "dry" : "sync");
    setError(null);
    try {
      setReport(await api.post<SyncReport>(withBrain(`/api/ai/documents/sync?dryRun=${dryRun}`)));
      if (!dryRun) { reload(); onCorpusChanged(); }
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  async function setActive(doc: DocumentDto, active: boolean) {
    setError(null);
    try {
      await api.post(`/api/ai/documents/${doc.id}/${active ? "activate" : "deactivate"}`);
      reload(); onCorpusChanged();
    } catch (e) { setError((e as Error).message); }
  }

  async function reindex(doc: DocumentDto) {
    setBusy(doc.id);
    setError(null);
    try { await api.post(`/api/ai/documents/${doc.id}/reindex`); reload(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  const summaryTone = (k: string) =>
    k === "upload" || k === "update" ? "green" : k === "deactivate" ? "amber" : "gray";

  const docScopes = Array.from(new Set(
    docs.map((d) => d.analyzerScope).filter((s): s is string => !!s))).sort();
  const scopeOptions = scope && scope !== "shared" && !docScopes.includes(scope)
    ? [...docScopes, scope].sort() : docScopes;

  // Filter pipeline: scope → status → source type → search, then the sort the
  // toolbar promises — newest effective date first, undated documents last.
  const filteredDocs = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const kept = docs.filter((d) => {
      if (scope && (scope === "shared" ? !!d.analyzerScope : d.analyzerScope !== scope)) return false;
      if (statusFilter && (statusFilter === "active") !== d.active) return false;
      if (typeFilter && d.sourceType !== typeFilter) return false;
      if (needle && ![d.title, d.sourceName, d.fileName]
        .some((t) => t.toLowerCase().includes(needle))) return false;
      return true;
    });
    return [...kept].sort((a, b) => {
      if (a.effectiveDate === b.effectiveDate) return 0;
      if (a.effectiveDate === null) return 1;
      if (b.effectiveDate === null) return -1;
      return a.effectiveDate < b.effectiveDate ? 1 : -1;
    });
  }, [docs, scope, statusFilter, typeFilter, search]);

  const pageCount = Math.max(1, Math.ceil(filteredDocs.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const pageStart = safePage * PAGE_SIZE;
  const visibleDocs = filteredDocs.slice(pageStart, pageStart + PAGE_SIZE);

  const anyFilter = !!(scope || search.trim() || typeFilter || statusFilter);

  // Per-document ingestion warnings light the row amber, with the first warning
  // as the sub-line — the design's reindex signal, from data that already loads.
  const docWarnings = useMemo(() => new Map(
    (quality?.documents ?? [])
      .filter((d) => d.documentId !== null && d.warnings.length > 0)
      .map((d) => [d.documentId as string, d.warnings]),
  ), [quality]);

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Corpus{brain || scope ? ` — ${[brain, scope].filter(Boolean).join(" · ")}` : ""}</h1>
          <p>
            {stats
              ? `${stats.corpus.activeDocuments} active of ${stats.corpus.totalDocuments} documents · ${stats.corpus.chunks.toLocaleString()} chunks`
              : "—"}
            {quality ? ` · ${quality.embeddedChunkCount.toLocaleString()} embedded` : ""}
          </p>
        </div>
        <div className="actions">
          <button onClick={() => setShowAdd((v) => !v)} disabled={busy !== null}>
            {showAdd ? "Cancel" : "Add document"}
          </button>
          <button onClick={() => runSync(true)} disabled={busy !== null}>
            {busy === "dry" ? "Planning…" : "Dry run"}
          </button>
          <button className="btn-primary" onClick={() => runSync(false)} disabled={busy !== null}>
            {busy === "sync" ? "Syncing…" : "Sync now"}
          </button>
        </div>
      </header>
      <ErrorNote message={error} />
      {showAdd && (
        <form className="card" onSubmit={submitAdd} style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <input type="file" required
                 onChange={(e) => setAddFile(e.target.files?.[0] ?? null)} />
          <input placeholder="Title" value={addTitle}
                 onChange={(e) => setAddTitle(e.target.value)} required />
          <input placeholder="Source name (e.g. HUD)" value={addSourceName}
                 onChange={(e) => setAddSourceName(e.target.value)} required />
          <select value={addSourceType} onChange={(e) => setAddSourceType(e.target.value)}>
            <option value="AGENCY_GUIDELINE">agency guideline</option>
            <option value="INTERNAL_POLICY">internal policy</option>
            <option value="INVESTOR_OVERLAY">investor overlay</option>
            <option value="EDUCATIONAL">educational</option>
          </select>
          <select value={addVisibility}
                  onChange={(e) => setAddVisibility(e.target.value as typeof addVisibility)}>
            <option value="INTERNAL">internal</option>
            <option value="PUBLIC">public</option>
            <option value="SECURE">secure</option>
          </select>
          <select value={addTrustLevel}
                  onChange={(e) => setAddTrustLevel(e.target.value as typeof addTrustLevel)}>
            <option value="APPROVED">approved</option>
            <option value="AUTHORITATIVE">authoritative</option>
            <option value="REFERENCE">reference</option>
            <option value="EXPERIMENTAL">experimental</option>
            <option value="BLOCKED">blocked</option>
          </select>
          <input type="date" value={addEffectiveDate}
                 onChange={(e) => setAddEffectiveDate(e.target.value)} />
          <button className="btn-primary" type="submit"
                  disabled={addBusy || !addFile || !addTitle.trim() || !addSourceName.trim()}>
            {addBusy ? "Uploading…" : "Upload & ingest"}
          </button>
        </form>
      )}
      {quality && (
        <section className="card quality-panel">
          <div className="sync-summary">
            <strong>Ingestion quality</strong>
            <Pill tone={quality.warnings.length === 0 ? "green" : "amber"}>
              {quality.warnings.length === 0 ? "ready" : `${quality.warnings.length} warnings`}
            </Pill>
          </div>
          <div className="quality-metrics">
            <div>
              <span className="stat-label">Embedded chunks</span>
              <strong>{quality.embeddedChunkCount.toLocaleString()} / {quality.chunkCount.toLocaleString()}</strong>
              <Meter ratio={quality.chunkCount > 0 ? quality.embeddedChunkCount / quality.chunkCount : 0}
                     tone={quality.chunksMissingEmbeddingCount > 0 ? "warn" : undefined} />
            </div>
            <div>
              <span className="stat-label">Parent / child</span>
              <strong>{quality.parentChunkCount.toLocaleString()} / {quality.childChunkCount.toLocaleString()}</strong>
            </div>
            <div>
              <span className="stat-label">Missing embeddings</span>
              <strong>{quality.chunksMissingEmbeddingCount.toLocaleString()}</strong>
            </div>
            <div>
              <span className="stat-label">Missing citations</span>
              <strong>{quality.chunksMissingCitationMetadata.toLocaleString()}</strong>
            </div>
          </div>
          {quality.warnings.length > 0 && (
            <div className="chips quality-warnings">
              {quality.warnings.map((warning) => (
                <Pill key={warning} tone="amber">{warning}</Pill>
              ))}
            </div>
          )}
        </section>
      )}
      {report && (
        <div className="card sync-report">
          <div className="sync-summary">
            <strong>{report.dryRun ? "Dry run plan" : "Sync finished"}</strong>
            {Object.entries(report.summary).map(([k, v]) => (
              <Pill key={k} tone={summaryTone(k)}>{v} {k}</Pill>
            ))}
          </div>
          {report.results.filter((r) => r.action !== "SKIP" || r.error || report.dryRun).map((r) => (
            <div key={`${r.action}-${r.fileName}`} className="diff-line">
              <code>{r.action.toLowerCase()}</code>
              <span>{r.fileName}{r.reason ? ` — ${r.reason}` : ""}{r.error ? ` — FAILED: ${r.error}` : ""}</span>
            </div>
          ))}
        </div>
      )}
      <div className="toolbar">
        <div className="searchbox">
          <Search size={13} strokeWidth={1.5} aria-hidden="true" />
          <input type="search" placeholder="Search documents…" aria-label="Search documents"
                 value={search}
                 onChange={(e) => { setSearch(e.target.value); setPage(0); }} />
        </div>
        <select aria-label="Analyzer scope filter" className={scope ? "on" : ""}
                value={scope ?? ""} onChange={(e) => updateScope(e.target.value)}>
          <option value="">All scopes</option>
          <option value="shared">shared</option>
          {scopeOptions.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <select aria-label="Source type filter" className={typeFilter ? "on" : ""}
                value={typeFilter}
                onChange={(e) => { setTypeFilter(e.target.value); setPage(0); }}>
          <option value="">All source types</option>
          {SOURCE_TYPES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <select aria-label="Status filter" className={statusFilter ? "on" : ""}
                value={statusFilter}
                onChange={(e) => { setStatusFilter(e.target.value as typeof statusFilter); setPage(0); }}>
          <option value="">All statuses</option>
          <option value="active">active</option>
          <option value="inactive">inactive</option>
        </select>
        {anyFilter && (
          <button className="filter on" onClick={clearFilters}>
            Clear filters <X size={12} strokeWidth={1.5} aria-hidden="true" />
          </button>
        )}
        <span className="toolbar-note">Sorted by effective date</span>
      </div>
      <div className="tbl-wrap" role="table" aria-label="Documents"
           style={{ "--cols": "1.7fr 130px 110px 96px 90px 44px" } as React.CSSProperties}>
        <div className="tbl-head" role="row">
          <span role="columnheader">Document</span>
          <span role="columnheader">Source type</span>
          <span role="columnheader">Trust</span>
          <span role="columnheader">Effective</span>
          <span role="columnheader">Status</span>
          <span role="columnheader" aria-label="Actions" />
        </div>
        {visibleDocs.map((d) => {
          const warnings = docWarnings.get(d.id);
          const variant = !d.active ? " is-muted" : warnings ? " is-warn" : "";
          return (
          <div key={d.id} className={`tbl-row${variant}`} role="row">
            <span role="cell">
              <span className="cell-title" title={d.fileName}>{d.title}</span>
              {warnings ? (
                <span className="cell-sub warn" title={warnings.join(" · ")}>{warnings[0]}</span>
              ) : (
                <span className="cell-sub">
                  {d.sourceName} · {d.visibility.toLowerCase()} · {d.analyzerScope ?? "shared"}
                </span>
              )}
            </span>
            <span role="cell"><Pill tone={sourceTypeTone(d.sourceType)}>
              {d.sourceType.replaceAll("_", " ").toLowerCase()}</Pill></span>
            <span role="cell"><Pill tone={d.trustLevel === "BLOCKED" ? "amber" : d.trustLevel === "AUTHORITATIVE" ? "green" : "blue"}>
              {d.trustLevel.replaceAll("_", " ").toLowerCase()}</Pill></span>
            <span role="cell" className="cell-num">{d.effectiveDate ?? "—"}</span>
            <span role="cell"><Status kind={d.active ? "active" : "inactive"} /></span>
            <span role="cell" className="row-menu-cell">
              <RowMenu id={d.id} openId={menuId} onToggle={setMenuId}>
                <button onClick={() => openEdit(d)}>Edit</button>
                <button onClick={() => reindex(d)} disabled={busy === d.id}>
                  {busy === d.id ? "Reindexing…" : "Reindex"}
                </button>
                <button onClick={() => setActive(d, !d.active)}>
                  {d.active ? "Deactivate" : "Activate"}
                </button>
                <button className="danger" onClick={() => remove(d)} disabled={busy === d.id}>
                  Delete
                </button>
              </RowMenu>
            </span>
          </div>
          );
        })}
        <div className="tbl-foot">
          <span>
            {filteredDocs.length === 0
              ? "No documents match."
              : `Showing ${pageStart + 1}–${pageStart + visibleDocs.length} of ${filteredDocs.length}`}
            {anyFilter && docs.length !== filteredDocs.length ? ` (${docs.length} total)` : ""}
          </span>
          {pageCount > 1 && (
            <span className="foot-pager">
              <button disabled={safePage === 0} onClick={() => setPage(safePage - 1)}>Previous</button>
              <button disabled={safePage + 1 >= pageCount} onClick={() => setPage(safePage + 1)}>Next</button>
            </span>
          )}
        </div>
      </div>
      {editing && (
        <div className="modal-overlay" onClick={() => setEditing(null)}>
          <form className="card" onClick={(e) => e.stopPropagation()} onSubmit={submitEdit}
                style={{ display: "grid", gap: 8, maxWidth: 460, margin: "10vh auto" }}>
            <h3 style={{ margin: 0 }}>Edit document</h3>
            <input placeholder="Title" value={editForm.title}
                   onChange={(e) => setEditForm({ ...editForm, title: e.target.value })} required />
            <input placeholder="Source name" value={editForm.sourceName}
                   onChange={(e) => setEditForm({ ...editForm, sourceName: e.target.value })} required />
            <select value={editForm.sourceType}
                    onChange={(e) => setEditForm({ ...editForm, sourceType: e.target.value })}>
              <option value="AGENCY_GUIDELINE">agency guideline</option>
              <option value="INTERNAL_POLICY">internal policy</option>
              <option value="INVESTOR_OVERLAY">investor overlay</option>
              <option value="EDUCATIONAL">educational</option>
            </select>
            <select value={editForm.visibility}
                    onChange={(e) => setEditForm({ ...editForm, visibility: e.target.value as DocumentUpdate["visibility"] })}>
              <option value="INTERNAL">internal</option>
              <option value="PUBLIC">public</option>
              <option value="SECURE">secure</option>
            </select>
            <select value={editForm.trustLevel}
                    onChange={(e) => setEditForm({ ...editForm, trustLevel: e.target.value as DocumentUpdate["trustLevel"] })}>
              <option value="APPROVED">approved</option>
              <option value="AUTHORITATIVE">authoritative</option>
              <option value="REFERENCE">reference</option>
              <option value="EXPERIMENTAL">experimental</option>
              <option value="BLOCKED">blocked</option>
            </select>
            <input placeholder="Version" value={editForm.documentVersion ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, documentVersion: e.target.value || null })} />
            <input type="date" value={editForm.effectiveDate ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, effectiveDate: e.target.value || null })} />
            <input type="date" value={editForm.expirationDate ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, expirationDate: e.target.value || null })} />
            <p className="muted" style={{ fontSize: 12 }}>
              Updates the document record. Existing search chunks keep their old metadata
              until you <strong>Reindex</strong> this document.
            </p>
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn-primary" type="submit"
                      disabled={editBusy || !editForm.title.trim() || !editForm.sourceName.trim()}>
                {editBusy ? "Saving…" : "Save"}
              </button>
              <button type="button" onClick={() => setEditing(null)}>Cancel</button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
