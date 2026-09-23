import { useCallback, useEffect, useMemo, useState } from "react";
import { api, brainsApi } from "../api";
import { ErrorNote, Pill, relTime } from "../components";
import {
  AnalyzerAssemblyDto, AnalyzerPromptRevisionDto, AnalyzerPromptState, BrainAdminDto, PromptSectionDto,
} from "../types";

const BASE = "/api/ai/admin/analyzers";

// ── read-only section card ────────────────────────────────────────────────────

function SectionCard({ section }: { section: PromptSectionDto }) {
  const [open, setOpen] = useState(section.kind === "STATIC");
  const dynamic = section.kind === "DYNAMIC";
  return (
    <div className="card" style={{ marginBottom: 10, opacity: dynamic ? 0.85 : 1 }}>
      <div className="tier-head" style={{ cursor: "pointer" }} onClick={() => setOpen((o) => !o)}>
        <span className="t">{section.title}</span>
        {dynamic ? <Pill tone="gray">filled at run time</Pill> : <Pill tone="gray">engine text</Pill>}
        <span className="when">{open ? "hide" : "show"}</span>
      </div>
      {open && (
        <pre style={{
          fontFamily: "var(--mono)", fontSize: 12, lineHeight: 1.6,
          whiteSpace: "pre-wrap", wordBreak: "break-word", margin: 0,
          color: dynamic ? "var(--muted)" : undefined,
        }}>
          {section.text.trimEnd()}
        </pre>
      )}
    </div>
  );
}

// ── editor for one analyzer ───────────────────────────────────────────────────

interface EditorProps {
  state: AnalyzerPromptState;
  brainParam: string;
  onChanged: (fresh: AnalyzerPromptState) => void;
}

function AnalyzerEditor({ state, brainParam, onChanged }: EditorProps) {
  const savedDraft = state.draft?.content ?? null;
  const [text, setText] = useState(savedDraft ?? state.published.content);
  const [busy, setBusy] = useState<null | "save" | "discard" | "publish" | "revert">(null);
  const [error, setError] = useState<string | null>(null);
  const [assembly, setAssembly] = useState<AnalyzerAssemblyDto | null>(null);
  const [histOpen, setHistOpen] = useState(false);
  const [history, setHistory] = useState<AnalyzerPromptRevisionDto[] | null>(null);

  useEffect(() => {
    setText(state.draft?.content ?? state.published.content);
    setHistOpen(false);
    setHistory(null);
    setError(null);
  }, [state.slug, state.draft?.content, state.published.content]);

  const loadAssembly = useCallback(async () => {
    try {
      const source = state.draft ? "draft" : "published";
      const sep = brainParam ? "&" : "?";
      setAssembly(await api.get<AnalyzerAssemblyDto>(
        `${BASE}/${state.slug}/assembly${brainParam}${sep}source=${source}`));
    } catch (e) {
      setError((e as Error).message);
    }
  }, [state.slug, state.draft, brainParam]);

  useEffect(() => { loadAssembly(); }, [loadAssembly]);

  const dirty = text !== (savedDraft ?? state.published.content);
  const canPublish = savedDraft !== null && !dirty;

  async function run(kind: NonNullable<typeof busy>, call: () => Promise<AnalyzerPromptState>) {
    setBusy(kind);
    setError(null);
    try {
      const fresh = await call();
      setHistory(null);
      onChanged(fresh);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const saveDraft = () => run("save", () =>
    api.put<AnalyzerPromptState>(`${BASE}/${state.slug}/prompt/draft${brainParam}`, { content: text }));
  const discardDraft = () => run("discard", () =>
    api.del<AnalyzerPromptState>(`${BASE}/${state.slug}/prompt/draft${brainParam}`));
  const publish = () => {
    if (!window.confirm(`Publish the draft prompt for ${state.displayName}? Folder runs will use it within about 10 seconds.`)) return;
    run("publish", () => api.post<AnalyzerPromptState>(`${BASE}/${state.slug}/prompt/publish${brainParam}`, {}));
  };
  const revert = () => run("revert", () =>
    api.post<AnalyzerPromptState>(`${BASE}/${state.slug}/prompt/revert${brainParam}`, {}));

  async function toggleHistory() {
    if (histOpen) { setHistOpen(false); return; }
    setHistOpen(true);
    if (history !== null) return;
    try {
      setHistory(await api.get<AnalyzerPromptRevisionDto[]>(`${BASE}/${state.slug}/prompt/history${brainParam}`));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const sections = assembly?.sections ?? [];
  const baseIndex = sections.findIndex((s) => s.kind === "BASE_PROMPT");
  const after = baseIndex >= 0 ? sections.slice(baseIndex + 1) : sections;

  return (
    <div>
      <div className="card" style={{ marginBottom: 12 }}>
        <div className="tier-head">
          <span className="t">{state.displayName}</span>
          <Pill tone="gray">{state.slug}</Pill>
          <Pill tone="gray">{state.v2 ? "envelope v2" : "envelope v1"}</Pill>
        </div>
        <p className="tier-note">
          Scope: {state.corpusScope ?? "whole corpus"} · Retrieval: {state.retrievalQueryTemplate
            ? `"${state.retrievalQueryTemplate}" (top-k ${state.retrievalTopK})` : "none"}
        </p>
        <p className="tier-note">Instance releases carry their own prompt and are not affected by edits here.</p>
      </div>

      <div className="card tier-must" style={{ marginBottom: 10 }}>
        <div className="tier-head">
          <span className="t">Analyzer base prompt</span>
          {state.published.source === "custom"
            ? <Pill tone="amber">custom</Pill>
            : <Pill tone="gray">pack default</Pill>}
          {state.draft && <Pill tone="amber">draft saved {relTime(state.draft.savedAt)}</Pill>}
          {state.published.updatedBy && (
            <span className="when">
              published by {state.published.updatedBy}
              {state.published.updatedAt ? ` · ${relTime(state.published.updatedAt)}` : ""}
            </span>
          )}
        </div>
        <textarea
          className="rulebox"
          aria-label="Base prompt"
          value={text}
          onChange={(e) => setText(e.target.value)}
          style={{ minHeight: 320 }}
        />
        <ErrorNote message={error} />
        <div className="editor-actions">
          <button className="btn-primary" disabled={!dirty || !text.trim() || busy !== null} onClick={saveDraft}>
            {busy === "save" ? "Saving…" : "Save draft"}
          </button>
          <button disabled={!canPublish || busy !== null} onClick={publish}>
            {busy === "publish" ? "Publishing…" : "Publish"}
          </button>
          {state.draft && (
            <button disabled={busy !== null} onClick={discardDraft}>
              {busy === "discard" ? "Discarding…" : "Discard draft"}
            </button>
          )}
          {state.published.source === "custom" && (
            <button disabled={busy !== null} onClick={revert}>
              {busy === "revert" ? "Reverting…" : "Revert to pack default"}
            </button>
          )}
          <button onClick={toggleHistory}>{histOpen ? "Hide history" : "History"}</button>
          <span className="state">
            {text.length.toLocaleString()} chars
            {dirty && <span className="dirty"> · unsaved changes</span>}
            {!dirty && savedDraft !== null && <span className="dirty"> · draft not published</span>}
          </span>
        </div>
        {histOpen && (
          <div style={{ marginTop: 12 }}>
            {history === null && <p className="muted">Loading…</p>}
            {history !== null && history.length === 0 && <p className="muted">No published revisions yet.</p>}
            {history !== null && history.map((rev) => (
              <div className="hist-row" key={rev.revision}>
                <span className="rev">rev {rev.revision}</span>
                <span className="what">{rev.reverted ? "revert to pack default" : "published"}</span>
                <span className="who">{rev.createdBy} · {relTime(rev.createdAt)}</span>
                <button
                  style={{ marginLeft: "auto" }}
                  disabled={rev.content === null}
                  onClick={() => rev.content !== null && setText(rev.content)}
                >
                  Load into editor
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {after.map((s) => <SectionCard key={s.id} section={s} />)}
    </div>
  );
}

// ── screen ────────────────────────────────────────────────────────────────────

export default function Analyzers() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [selectedSlug, setSelectedSlug] = useState("");
  const [states, setStates] = useState<AnalyzerPromptState[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    brainsApi.list()
      .then((list) => {
        setBrains(list);
        setSelectedSlug((current) => {
          if (current && list.some((b) => b.slug === current)) return current;
          return list.find((b) => b.isDefault)?.slug ?? list[0]?.slug ?? "";
        });
      })
      .catch((e) => setLoadError((err) => err ?? (e as Error).message));
  }, []);

  const brainParam = useMemo(
    () => selectedSlug ? `?brain=${encodeURIComponent(selectedSlug)}` : "",
    [selectedSlug],
  );

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const data = await api.get<AnalyzerPromptState[]>(`${BASE}${brainParam}`);
      setStates(data);
      setSelected((cur) => (cur && data.some((s) => s.slug === cur)) ? cur : data[0]?.slug ?? null);
    } catch (e) {
      setLoadError((e as Error).message);
    }
  }, [brainParam]);

  useEffect(() => { load(); }, [load]);

  function handleChanged(fresh: AnalyzerPromptState) {
    setStates((list) => list === null ? list : list.map((s) => s.slug === fresh.slug ? fresh : s));
  }

  const current = states?.find((s) => s.slug === selected) ?? null;

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Analyzer prompts</h1>
          <p>What each folder analyzer is told, in the order the model reads it. Edits are drafts until you publish.</p>
        </div>
        <div className="actions">
          <select aria-label="Brain" value={selectedSlug} onChange={(e) => setSelectedSlug(e.target.value)}>
            {brains.length === 0 && <option value="">Default brain</option>}
            {brains.map((b) => (
              <option key={b.id} value={b.slug}>{b.displayName} ({b.slug})</option>
            ))}
          </select>
        </div>
      </header>

      <ErrorNote message={loadError} />
      {states === null && !loadError && <p className="muted">Loading…</p>}

      {states !== null && (
        <div className="split">
          <div>
            {current && (
              <AnalyzerEditor key={current.slug} state={current} brainParam={brainParam} onChanged={handleChanged} />
            )}
          </div>
          <aside className="split-rail">
            <div className="card">
              <span className="section-label">Analyzers</span>
              <ul aria-label="Analyzers" style={{ listStyle: "none", padding: 0, margin: 0 }}>
                {states.map((s) => (
                  <li key={s.slug} style={{ padding: "6px 0", borderBottom: "1px solid var(--line)" }}>
                    <button
                      style={{ width: "100%", textAlign: "left", fontWeight: s.slug === selected ? 600 : 400 }}
                      onClick={() => setSelected(s.slug)}
                    >
                      {s.displayName}
                    </button>
                    <div style={{ display: "flex", gap: 6, marginTop: 4 }}>
                      {s.published.source === "custom" ? <Pill tone="amber">custom</Pill> : <Pill tone="gray">pack</Pill>}
                      {s.draft && <Pill tone="amber">draft</Pill>}
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </aside>
        </div>
      )}
    </>
  );
}
