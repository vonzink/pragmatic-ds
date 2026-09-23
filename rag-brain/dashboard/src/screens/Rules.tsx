import { useCallback, useEffect, useMemo, useState } from "react";
import { api, brainsApi } from "../api";
import { ErrorNote, Pill, relTime } from "../components";
import { BrainAdminDto, RuleRevisionDto, RulesResponse, RuleState, VocabState } from "../types";

function ruleCount(content: string): number {
  return content.split("\n").filter((line) => line.trim().length > 0).length;
}

// ── per-tier editor ───────────────────────────────────────────────────────────

interface TierProps {
  tier: "hard" | "guidance";
  state: RuleState;
  brainParam: string;
  onSaved: (fresh: RulesResponse) => void;
  onDraftChanged: (tier: "hard" | "guidance", draft: string) => void;
}

function TierCard({ tier, state, brainParam, onSaved, onDraftChanged }: TierProps) {
  const fullKey = tier === "hard" ? "rules.hard" : "rules.guidance";
  const [draft, setDraft]         = useState(state.content);
  const [saving, setSaving]       = useState(false);
  const [reverting, setReverting] = useState(false);
  const [error, setError]         = useState<string | null>(null);
  const [histOpen, setHistOpen]   = useState(false);
  const [history, setHistory]     = useState<RuleRevisionDto[] | null>(null);
  const [histError, setHistError] = useState<string | null>(null);

  // keep draft in sync if parent refreshes
  useEffect(() => { setDraft(state.content); }, [state.content]);
  useEffect(() => {
    setHistOpen(false);
    setHistory(null);
    setHistError(null);
  }, [brainParam]);

  const dirty = draft !== state.content;

  function edit(next: string) {
    setDraft(next);
    onDraftChanged(tier, next);
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const fresh = await api.put<RulesResponse>(
        `/api/ai/admin/rules/${fullKey}${brainParam}`, { content: draft });
      setHistory(null);
      onSaved(fresh);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function revert() {
    setReverting(true);
    setError(null);
    try {
      const fresh = await api.post<RulesResponse>(
        `/api/ai/admin/rules/${fullKey}/revert${brainParam}`, {});
      setHistory(null);
      onSaved(fresh);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setReverting(false);
    }
  }

  async function toggleHistory() {
    if (histOpen) { setHistOpen(false); return; }
    setHistOpen(true);
    if (history !== null) return; // already fetched
    setHistError(null);
    try {
      const rows = await api.get<RuleRevisionDto[]>(
        `/api/ai/admin/rules/${fullKey}/history${brainParam}`);
      setHistory(rows);
    } catch (e) {
      setHistError((e as Error).message);
    }
  }

  const isHard = tier === "hard";
  const count  = ruleCount(draft);

  return (
    <div className={`card ${isHard ? "tier-must" : "tier-should"}`} style={{ marginBottom: 16 }}>
      <div className="tier-head">
        <span className="t">{isHard ? "Hard rules" : "Strong recommendations"}</span>
        <span className={`pill ${isHard ? "must" : "should"}`}>{isHard ? "MUST" : "SHOULD"}</span>
        {state.source === "custom"
          ? <Pill tone="amber">custom</Pill>
          : <Pill tone="gray">pack default</Pill>}
        {state.updatedBy && (
          <span className="when">
            {state.updatedBy}{state.updatedAt ? ` · ${relTime(state.updatedAt)}` : ""}
          </span>
        )}
      </div>
      <p className="tier-note">
        {isHard
          ? "No wiggle room. Violating one of these blocks the answer and escalates."
          : "Shapes tone and structure. The model may depart from these when the evidence demands it."}
      </p>

      <textarea
        className="rulebox"
        aria-label={isHard ? "Hard rules" : "Strong recommendations"}
        value={draft}
        onChange={(e) => edit(e.target.value)}
      />

      <ErrorNote message={error} />

      <div className="editor-actions">
        <button
          className="btn-primary"
          disabled={!dirty || !draft.trim() || saving}
          onClick={save}
        >
          {saving ? "Saving…" : "Save as new revision"}
        </button>

        {state.source === "custom" && (
          <button disabled={reverting} onClick={revert}>
            {reverting ? "Reverting…" : "Revert to pack default"}
          </button>
        )}

        <button onClick={toggleHistory}>
          {histOpen ? "Hide history" : "History"}
        </button>

        <span className="state">
          {count} rule{count === 1 ? "" : "s"}
          {dirty && <span className="dirty"> · unsaved changes</span>}
        </span>
      </div>

      {histOpen && (
        <div style={{ marginTop: 12 }}>
          <ErrorNote message={histError} />
          {history === null && !histError && (
            <p className="muted">Loading…</p>
          )}
          {history !== null && history.length === 0 && (
            <p className="muted">No revisions yet.</p>
          )}
          {history !== null && history.map((rev) => (
            <div className="hist-row" key={rev.revision}>
              <span className="rev">rev {rev.revision}</span>
              <span className="what">{rev.reverted ? "revert to pack default" : "content revision"}</span>
              <span className="who">{rev.createdBy} · {relTime(rev.createdAt)}</span>
              <button
                style={{ marginLeft: "auto" }}
                disabled={rev.content === null}
                onClick={() => rev.content !== null && edit(rev.content)}
              >
                Restore
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── main screen ───────────────────────────────────────────────────────────────

export default function Rules() {
  const [brains, setBrains]       = useState<BrainAdminDto[]>([]);
  const [selectedSlug, setSelectedSlug] = useState("");
  const [rules, setRules]         = useState<RulesResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [preview, setPreview]     = useState<string | null>(null);
  const [prevError, setPrevError] = useState<string | null>(null);
  const [prevLoading, setPrevLoading] = useState(false);
  // Live drafts feed the assembly rail so its counts track what is being typed,
  // not only what is saved.
  const [drafts, setDrafts] = useState<{ hard: string; guidance: string } | null>(null);
  const [vocabEntries, setVocabEntries] = useState<number | null>(null);

  useEffect(() => {
    brainsApi
      .list()
      .then((list) => {
        setBrains(list);
        setSelectedSlug((current) => {
          if (current && list.some((brain) => brain.slug === current)) return current;
          return list.find((brain) => brain.isDefault)?.slug ?? list[0]?.slug ?? "";
        });
      })
      .catch((e) => setLoadError((err) => err ?? (e as Error).message));
  }, []);

  useEffect(() => {
    api.get<VocabState>("/api/ai/admin/vocabulary")
      .then((v) => setVocabEntries(v.entries))
      .catch(() => setVocabEntries(null));
  }, []);

  const brainParam = useMemo(
    () => selectedSlug ? `?brain=${encodeURIComponent(selectedSlug)}` : "",
    [selectedSlug],
  );

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const data = await api.get<RulesResponse>(`/api/ai/admin/rules${brainParam}`);
      setRules(data);
      setDrafts({ hard: data.hard.content, guidance: data.guidance.content });
    } catch (e) {
      setLoadError((e as Error).message);
    }
  }, [brainParam]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    setPreview(null);
    setPrevError(null);
  }, [brainParam]);

  function handleSaved(fresh: RulesResponse) {
    setRules(fresh);
    setDrafts({ hard: fresh.hard.content, guidance: fresh.guidance.content });
  }

  function handleDraftChanged(tier: "hard" | "guidance", draft: string) {
    setDrafts((d) => d === null ? d : { ...d, [tier]: draft });
  }

  async function fetchPreview() {
    setPrevLoading(true);
    setPrevError(null);
    setPreview(null);
    try {
      const data = await api.get<{ prompt: string }>(`/api/ai/admin/rules/preview${brainParam}`);
      setPreview(data.prompt);
    } catch (e) {
      setPrevError((e as Error).message);
    } finally {
      setPrevLoading(false);
    }
  }

  const hardDraft     = drafts?.hard ?? rules?.hard.content ?? "";
  const guidanceDraft = drafts?.guidance ?? rules?.guidance.content ?? "";
  // Rough chars-per-token estimate over the two tiers only; the full prompt
  // carries the pack prompt and retrieved context on top of this.
  const estRuleTokens = Math.round((hardDraft.length + guidanceDraft.length) / 4);

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Rules &amp; guardrails</h1>
          <p>Two prompt tiers with different force. Changes go live within ~10 s.</p>
        </div>
        <div className="actions">
          <select aria-label="Brain" value={selectedSlug} onChange={(e) => setSelectedSlug(e.target.value)}>
            {brains.length === 0 && <option value="">Default brain</option>}
            {brains.map((brain) => (
              <option key={brain.id} value={brain.slug}>
                {brain.displayName} ({brain.slug})
              </option>
            ))}
          </select>
        </div>
      </header>

      <ErrorNote message={loadError} />

      {rules === null && !loadError && (
        <p className="muted">Loading…</p>
      )}

      {rules !== null && (
        <div className="split">
          <div>
            <TierCard tier="hard"     state={rules.hard}     brainParam={brainParam}
                      onSaved={handleSaved} onDraftChanged={handleDraftChanged} />
            <TierCard tier="guidance" state={rules.guidance} brainParam={brainParam}
                      onSaved={handleSaved} onDraftChanged={handleDraftChanged} />

            {(preview !== null || prevError) && (
              <div className="card">
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                  <span style={{ fontWeight: 600, fontSize: 14, color: "var(--text)" }}>Full prompt preview</span>
                  <button
                    style={{ height: 28, padding: "0 10px", fontSize: 12 }}
                    onClick={() => { setPreview(null); setPrevError(null); }}
                  >
                    Close
                  </button>
                </div>
                <ErrorNote message={prevError} />
                {preview !== null && (
                  <pre style={{
                    fontFamily: "var(--mono)",
                    fontSize: 12,
                    lineHeight: 1.6,
                    whiteSpace: "pre-wrap",
                    wordBreak: "break-word",
                    margin: 0,
                  }}>
                    {preview}
                  </pre>
                )}
              </div>
            )}
          </div>

          {/* What the system prompt is assembled from, in order — the thing that
              makes the two-tier model legible. */}
          <aside className="split-rail">
            <div className="card">
              <span className="section-label">Prompt assembly</span>
              <ol className="timeline">
                <li>
                  <span className="dot" />
                  <span className="t">Pack system prompt</span>
                  <span className="s">from pack</span>
                </li>
                <li>
                  <span className="dot warn" />
                  <span className="t">Hard rules</span>
                  <span className="s">{ruleCount(hardDraft)} · {rules.hard.source === "custom" ? "custom" : "pack default"}</span>
                </li>
                <li>
                  <span className="dot" />
                  <span className="t">Guidance</span>
                  <span className="s">{ruleCount(guidanceDraft)} · {rules.guidance.source === "custom" ? "custom" : "pack default"}</span>
                </li>
                <li>
                  <span className="dot" />
                  <span className="t">Vocabulary expansion</span>
                  <span className="s">{vocabEntries !== null ? `${vocabEntries} terms` : "—"}</span>
                </li>
                <li>
                  <span className="dot good" />
                  <span className="t">Retrieved context</span>
                  <span className="s">at ask time</span>
                </li>
              </ol>
              <div className="rail-foot">Est. rule tokens ≈ {estRuleTokens.toLocaleString()}</div>
              <button style={{ width: "100%", marginTop: 10 }} onClick={fetchPreview} disabled={prevLoading}>
                {prevLoading ? "Loading preview…" : "Preview full prompt"}
              </button>
            </div>
          </aside>
        </div>
      )}
    </>
  );
}
