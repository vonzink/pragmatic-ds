import { CSSProperties, Fragment, useCallback, useEffect, useState } from "react";
import { Search, X } from "lucide-react";
import { api, brainsApi, learningApi } from "../api";
import { AuditDetail, AuditPage, BrainAdminDto, SourceWeightDto, SourceWeightEventDto } from "../types";
import { ErrorNote, Pill } from "../components";

function confClass(confidence: number | null): string {
  if (confidence == null) return "cell-num";
  return confidence >= 0.8 ? "cell-num good" : "cell-num warn";
}

export default function Audit() {
  const [page, setPage] = useState(0);
  const [escalatedOnly, setEscalatedOnly] = useState(false);
  const [q, setQ] = useState("");
  // What is typed vs what is queried: the input debounces into q so the server
  // is asked once per pause, not once per keystroke.
  const [qInput, setQInput] = useState("");
  const [data, setData] = useState<AuditPage | null>(null);
  const [open, setOpen] = useState<Record<string, AuditDetail>>({});
  const [error, setError] = useState<string | null>(null);
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [brainId, setBrainId] = useState<string>("");
  const [brainSlug, setBrainSlug] = useState<string>("");
  const [pending, setPending] = useState<SourceWeightEventDto[]>([]);
  const [weights, setWeights] = useState<SourceWeightDto[]>([]);
  const [learningBusy, setLearningBusy] = useState(false);

  const load = useCallback(() => {
    const params = new URLSearchParams({ page: String(page), size: "20",
      escalatedOnly: String(escalatedOnly) });
    if (q.trim()) params.set("q", q.trim());
    api.get<AuditPage>(`/api/ai/admin/audit?${params}`)
      .then(setData).catch((e) => setError(e.message));
  }, [page, escalatedOnly, q]);

  useEffect(load, [load]);

  useEffect(() => {
    const t = window.setTimeout(() => {
      setPage(0);
      setQ(qInput);
    }, 300);
    return () => window.clearTimeout(t);
  }, [qInput]);

  useEffect(() => {
    brainsApi.list()
      .then((list) => {
        setBrains(list);
        const selected = list.find((b) => b.isDefault) || list[0];
        setBrainId((current) => current || selected?.id || "");
        setBrainSlug((current) => current || selected?.slug || "");
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const loadLearning = useCallback(() => {
    if (!brainSlug) return;
    Promise.all([learningApi.pending(brainSlug), learningApi.weights(brainSlug)])
      .then(([events, ws]) => { setPending(events); setWeights(ws); })
      .catch((e) => setError((e as Error).message));
  }, [brainSlug]);

  useEffect(loadLearning, [loadLearning]);

  async function toggle(id: string) {
    if (open[id]) {
      setOpen(({ [id]: _gone, ...rest }) => rest);
      return;
    }
    try {
      const detail = await api.get<AuditDetail>(`/api/ai/admin/audit/${id}`);
      setOpen((current) => ({ ...current, [id]: detail }));
    } catch (e) { setError((e as Error).message); }
  }

  async function approve(eventId: string) {
    if (!brainSlug) return;
    setLearningBusy(true); setError(null);
    try { await learningApi.approve(eventId, brainSlug); loadLearning(); }
    catch (e) { setError((e as Error).message); }
    finally { setLearningBusy(false); }
  }

  async function reject(eventId: string) {
    if (!brainSlug) return;
    setLearningBusy(true); setError(null);
    try { await learningApi.reject(eventId, brainSlug); loadLearning(); }
    catch (e) { setError((e as Error).message); }
    finally { setLearningBusy(false); }
  }

  async function resetWeights() {
    if (!brainSlug) return;
    if (!window.confirm("Reset all learned source weights for this brain back to neutral (1.0)? This cannot be undone.")) return;
    setLearningBusy(true); setError(null);
    try { await learningApi.reset(brainSlug); loadLearning(); }
    catch (e) { setError((e as Error).message); }
    finally { setLearningBusy(false); }
  }

  function selectBrain(id: string) {
    setBrainId(id);
    setBrainSlug(brains.find((b) => b.id === id)?.slug ?? "");
  }

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Audit</h1>
          <p>The compliance record — every question, the model that answered, and the outcome.</p>
        </div>
      </header>
      <ErrorNote message={error} />
      <div className="toolbar">
        <div className="searchbox">
          <Search size={13} strokeWidth={1.5} aria-hidden="true" />
          <input type="search" placeholder="Search questions…" aria-label="Search questions"
                 value={qInput} onChange={(e) => setQInput(e.target.value)} />
        </div>
        <button className={escalatedOnly ? "filter on warn" : "filter"}
                aria-pressed={escalatedOnly}
                onClick={() => { setPage(0); setEscalatedOnly(!escalatedOnly); }}>
          Escalated only
          {escalatedOnly && <X size={12} strokeWidth={1.5} aria-hidden="true" />}
        </button>
        <span className="toolbar-note">
          {data ? `${data.total.toLocaleString()} matching` : "—"}
        </span>
      </div>
      <div className="tbl-wrap" role="table" aria-label="Audit log"
           style={{ "--cols": "150px 1fr 56px 130px 150px" } as CSSProperties}>
        <div className="tbl-head" role="row">
          <span role="columnheader">Time</span>
          <span role="columnheader">Question</span>
          <span role="columnheader">Conf.</span>
          <span role="columnheader">Model</span>
          <span role="columnheader">Outcome</span>
        </div>
        {data?.items.map((row) => (
          <Fragment key={row.id}>
            <div className="tbl-row clickable" role="row" onClick={() => toggle(row.id)}>
              <span role="cell" className="cell-num">{new Date(row.createdAt).toLocaleString()}</span>
              <span role="cell" className="cell-title" title={row.question}>{row.question}</span>
              <span role="cell" className={confClass(row.confidence)}>
                {row.confidence == null ? "—" : row.confidence.toFixed(2)}
              </span>
              <span role="cell" className="cell-sub">{row.modelName ?? "classifier"}</span>
              <span role="cell" className="chips" style={{ margin: 0 }}>
                <Pill tone={row.escalated ? "amber" : "green"}>
                  {row.escalated ? "escalated" : "grounded"}</Pill>
                {row.fallbackUsed && <Pill tone="purple">fallback</Pill>}
              </span>
            </div>
            {open[row.id] && (
              <div className="tbl-detail">
                {open[row.id].answer && <p className="answer">{open[row.id].answer}</p>}
                <p className="muted">
                  {open[row.id].sources.length} source chunk{open[row.id].sources.length === 1 ? "" : "s"} retrieved
                  {open[row.id].rewrittenQuestion ? ` · rewritten: ${open[row.id].rewrittenQuestion}` : ""}
                </p>
              </div>
            )}
          </Fragment>
        ))}
        {data && data.items.length === 0 && <div className="tbl-foot">No audit entries match.</div>}
      </div>
      <div className="pager">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
        <span className="muted">page {page + 1} of {pages}</span>
        <button disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Older</button>
      </div>

      <div className="card">
        <header className="screen-head">
          <h2>Learning — review queue &amp; weights</h2>
          <div className="actions">
            <label className="check">brain&nbsp;
              <select value={brainId} onChange={(e) => selectBrain(e.target.value)}>
                {brains.map((b) => <option key={b.id} value={b.id}>{b.displayName} ({b.slug})</option>)}
              </select>
            </label>
            <button onClick={resetWeights} disabled={learningBusy || !brainSlug}>Reset weights</button>
          </div>
        </header>

        <h3>Pending changes ({pending.length})</h3>
        {pending.length === 0 && <p className="muted">No changes waiting for review.</p>}
        {pending.length > 0 && (
          <table className="tbl">
            <thead>
              <tr><th>Document</th><th>Current</th><th>Proposed</th><th>Evidence</th><th>Reason</th><th></th></tr>
            </thead>
            <tbody>
              {pending.map((ev) => (
                <tr key={ev.id}>
                  <td><code>{ev.documentId.slice(0, 8)}</code></td>
                  <td>{ev.oldWeight == null ? "—" : ev.oldWeight.toFixed(3)}</td>
                  <td>{ev.proposedWeight == null ? "—" : ev.proposedWeight.toFixed(3)}</td>
                  <td>{ev.evidenceCount}</td>
                  <td className="muted">{ev.reason ?? "—"}</td>
                  <td className="row-actions">
                    <button onClick={() => approve(ev.id)} disabled={learningBusy || !brainSlug}>Approve</button>
                    <button onClick={() => reject(ev.id)} disabled={learningBusy || !brainSlug}>Reject</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <h3>Current source weights ({weights.length})</h3>
        {weights.length === 0 && <p className="muted">No learned weights — retrieval is at the neutral baseline.</p>}
        {weights.length > 0 && (
          <table className="tbl">
            <thead>
              <tr><th>Document</th><th>Weight</th><th>Feedback</th><th>Updated by</th><th>Updated</th></tr>
            </thead>
            <tbody>
              {weights.map((w) => (
                <tr key={w.documentId}>
                  <td><code>{w.documentId.slice(0, 8)}</code></td>
                  <td>
                    <Pill tone={w.weight > 1 ? "green" : w.weight < 1 ? "amber" : "gray"}>{w.weight.toFixed(3)}</Pill>
                  </td>
                  <td>{w.feedbackCount}</td>
                  <td className="muted">{w.updatedBy}</td>
                  <td className="muted">{w.updatedAt ? new Date(w.updatedAt).toLocaleString() : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
