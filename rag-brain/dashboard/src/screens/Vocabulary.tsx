import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { ErrorNote, Pill, relTime } from "../components";
import { VocabRevisionDto, VocabState } from "../types";

/* The server's expansion string, with the boolean/joining separators dimmed so
   the added terms read past the plumbing. */
function ExpandedText({ text }: { text: string }) {
  const parts = text.split(/(\bOR\b|·)/g);
  return (
    <div className="expanded-box">
      {parts.map((p, i) =>
        p === "OR" || p === "·" ? <span key={i} className="sep">{p}</span> : <span key={i}>{p}</span>)}
    </div>
  );
}

export default function Vocabulary() {
  const [state, setState] = useState<VocabState | null>(null);
  const [draft, setDraft] = useState("");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [reverting, setReverting] = useState(false);

  const [histOpen, setHistOpen] = useState(false);
  const [history, setHistory] = useState<VocabRevisionDto[] | null>(null);
  const [histError, setHistError] = useState<string | null>(null);

  const [phrase, setPhrase] = useState("owner occupied duplex");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [prevError, setPrevError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const data = await api.get<VocabState>("/api/ai/admin/vocabulary");
      setState(data);
      setDraft(data.content);
    } catch (e) {
      setLoadError((e as Error).message);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const dirty = state !== null && draft !== state.content;

  async function save() {
    setSaving(true); setError(null);
    try {
      const fresh = await api.put<VocabState>("/api/ai/admin/vocabulary", { content: draft });
      setState(fresh); setDraft(fresh.content); setHistory(null);
    } catch (e) { setError((e as Error).message); }
    finally { setSaving(false); }
  }

  async function revert() {
    setReverting(true); setError(null);
    try {
      const fresh = await api.post<VocabState>("/api/ai/admin/vocabulary/revert", {});
      setState(fresh); setDraft(fresh.content); setHistory(null);
    } catch (e) { setError((e as Error).message); }
    finally { setReverting(false); }
  }

  async function toggleHistory() {
    if (histOpen) { setHistOpen(false); return; }
    setHistOpen(true);
    if (history !== null) return;
    setHistError(null);
    try {
      setHistory(await api.get<VocabRevisionDto[]>("/api/ai/admin/vocabulary/history"));
    } catch (e) { setHistError((e as Error).message); }
  }

  async function testPhrase() {
    setPrevError(null); setExpanded(null);
    try {
      const data = await api.get<{ original: string; expanded: string }>(
        `/api/ai/admin/vocabulary/preview?q=${encodeURIComponent(phrase)}`);
      setExpanded(data.expanded);
    } catch (e) { setPrevError((e as Error).message); }
  }

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Vocabulary</h1>
          <p>
            Borrower words → guideline words. Search-time only: this changes what retrieval
            finds, never what the model is allowed to say.
          </p>
        </div>
      </header>

      <ErrorNote message={loadError} />
      {state === null && !loadError && <p className="muted">Loading…</p>}

      {state !== null && (
        <div className="split wide-rail">
          <div className="card">
            <div className="tier-head">
              <span className="t">Synonym list</span>
              {state.source === "custom"
                ? <Pill tone="amber">custom</Pill>
                : <Pill tone="gray">pack default</Pill>}
              <Pill tone="gray">{state.entries} terms</Pill>
              {state.updatedBy && (
                <span className="when">
                  {state.updatedBy}{state.updatedAt ? ` · ${relTime(state.updatedAt)}` : ""}
                </span>
              )}
            </div>
            <p className="tier-note">
              Translates the words people use ("duplex", "owner occupied") into the words the
              guidelines use ("2-unit", "principal residence") so retrieval finds the right row.
            </p>

            <textarea className="rulebox" aria-label="Synonym list" style={{ lineHeight: 1.8 }}
                      value={draft} onChange={(e) => setDraft(e.target.value)} />
            <p className="hint">one <code>term =&gt; expansion</code> per line</p>
            <ErrorNote message={error} />

            <div className="editor-actions">
              <button className="btn-primary" disabled={!dirty || !draft.trim() || saving} onClick={save}>
                {saving ? "Saving…" : "Save as new revision"}
              </button>
              {state.source === "custom" && (
                <button disabled={reverting} onClick={revert}>
                  {reverting ? "Reverting…" : "Revert to pack default"}
                </button>
              )}
              <button onClick={toggleHistory}>{histOpen ? "Hide history" : "History"}</button>
              {dirty && <span className="state"><span className="dirty">unsaved changes</span></span>}
            </div>

            {histOpen && (
              <div style={{ marginTop: 12 }}>
                <ErrorNote message={histError} />
                {history === null && !histError && <p className="muted">Loading…</p>}
                {history !== null && history.length === 0 && <p className="muted">No revisions yet.</p>}
                {history !== null && history.map((rev) => (
                  <div className="hist-row" key={rev.revision}>
                    <span className="rev">rev {rev.revision}</span>
                    <span className="what">{rev.reverted ? "revert to pack default" : "content revision"}</span>
                    <span className="who">{rev.createdBy} · {relTime(rev.createdAt)}</span>
                    <button
                      style={{ marginLeft: "auto" }}
                      disabled={rev.content === null}
                      onClick={() => rev.content !== null && setDraft(rev.content)}>
                      Restore
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Showing the effect is the point: what a borrower's phrase actually
              searches as, straight from the server's expander. */}
          <aside className="split-rail">
            <div className="card">
              <span className="section-label">Test a phrase</span>
              <div className="ask-bar" style={{ marginTop: 10, marginBottom: 0 }}>
                <input value={phrase} onChange={(e) => setPhrase(e.target.value)}
                       onKeyDown={(e) => e.key === "Enter" && testPhrase()} />
                <button className="btn-primary" onClick={testPhrase} disabled={!phrase.trim()}>Expand</button>
              </div>
              <ErrorNote message={prevError} />
              {expanded !== null && (
                <div style={{ marginTop: 12 }}>
                  <span className="section-label">Searches as</span>
                  <div style={{ marginTop: 6 }}>
                    <ExpandedText text={expanded} />
                  </div>
                </div>
              )}
              {expanded === null && !prevError && (
                <p className="hint" style={{ marginTop: 10 }}>
                  Runs the live expander — the same rewrite retrieval applies to every question.
                </p>
              )}
            </div>
          </aside>
        </div>
      )}
    </>
  );
}
