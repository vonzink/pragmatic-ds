import { useCallback, useEffect, useState } from "react";
import { instanceApi } from "../api";
import PromotionPanel from "../components/PromotionPanel";
import ReleaseHistoryPanel from "../components/ReleaseHistoryPanel";
import type { EvaluationView, PointerHistoryView, ReleaseSummary } from "../types";

/**
 * The immutable record, and the two actions that move production across it.
 *
 * Promotion and rollback are the same operation in opposite directions and go through the same
 * gate, the same compare-and-set and the same confirmation. Rollback is not the safe one: a
 * historical release can be exactly as unpromotable as a new one if a collection it pins was
 * disabled or its model's credential was withdrawn, so nothing here is labelled rollback-safe
 * until the server's own gate says it is executable.
 *
 * Evaluation lives here too, because a release's evaluation verdict is the main gate on promoting
 * it and running one is the only way past that gate.
 */
export default function InstanceReleases(
  { brainId, instanceSlug }: { brainId: string; instanceSlug: string },
) {
  const [releases, setReleases] = useState<ReleaseSummary[]>([]);
  const [pointer, setPointer] = useState<PointerHistoryView | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [evaluation, setEvaluation] = useState<EvaluationView | null>(null);
  const [evaluating, setEvaluating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Why the pointer is unknown. Separate from `error` because it disables different claims. */
  const [pointerError, setPointerError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  const base = `/api/ai/admin/instances/${encodeURIComponent(instanceSlug)}`;
  const brainQuery = `brain=${encodeURIComponent(brainId)}`;
  const reload = useCallback(() => setNonce((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    setPointerError(null);

    // Read separately rather than together. The two answer different questions, and failing both
    // because one of them failed would replace a readable release list with a blank screen — or,
    // worse, leave the pointer log looking empty because the *releases* call was the one that
    // broke.
    instanceApi.get<ReleaseSummary[]>(`${base}/releases?${brainQuery}`)
      .then((history) => {
        if (cancelled) return;
        // An empty or unparseable 2xx body resolves to undefined through the shared transport, and
        // production does produce those, so what arrives is checked rather than assumed.
        const rows = Array.isArray(history) ? history : [];
        setReleases(rows);
        // Default to the newest candidate, which is what someone opening this tab is usually
        // here to promote. Falls back to whatever exists.
        setSelected((current) => current
          ?? rows.find((r) => !r.live)?.releaseId
          ?? rows[0]?.releaseId
          ?? null);
      })
      .catch((e) => { if (!cancelled) { setReleases([]); setError((e as Error).message); } });

    instanceApi.get<PointerHistoryView>(`${base}/pointer?${brainQuery}`)
      .then((state) => { if (!cancelled) setPointer(state ?? null); })
      .catch((e) => {
        if (cancelled) return;
        // Null, not an empty history: the panel must not be able to say "nothing was ever
        // promoted" on the strength of a read that never landed.
        setPointer(null);
        setPointerError((e as Error).message);
      });

    return () => { cancelled = true; };
  }, [base, brainQuery, nonce]);

  async function evaluate(releaseId: string) {
    setEvaluating(true);
    setError(null);
    try {
      const view = await instanceApi.post<EvaluationView>(
        `${base}/releases/${encodeURIComponent(releaseId)}/evaluate?${brainQuery}`);
      setEvaluation(view);
      // An evaluation verdict is a promotion gate, so the gate is worth re-asking after one.
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setEvaluating(false);
    }
  }

  const live = releases.find((r) => r.live) ?? null;
  const chosen = releases.find((r) => r.releaseId === selected) ?? null;

  return (
    <div className="workbench">
      <h1>Releases</h1>

      {error && <div className="error-note" role="alert">{error}</div>}

      <ReleaseHistoryPanel
        releases={releases}
        events={Array.isArray(pointer?.events) ? pointer.events : []}
        eventsError={pointerError}
        selectedReleaseId={selected}
        onSelect={(releaseId) => { setSelected(releaseId); setEvaluation(null); }}
      />

      {chosen && (
        <section className="card workbench-card">
          <h3>Evaluation of r{chosen.releaseNumber}</h3>
          <p className="muted">
            Runs this release against its own scenario set and records the verdict. The result is
            what the promotion gate reads.
          </p>

          {evaluation && evaluation.releaseId === chosen.releaseId && (
            <dl className="parsed-facts">
              <div>
                <dt>Verdict</dt>
                <dd>
                  <span className={`badge ${evaluation.passed ? "badge-live" : "badge-failed"}`}>
                    {evaluation.passed ? "Passed" : "Failed"}
                  </span>
                </dd>
              </div>
              <div><dt>Score</dt><dd>{evaluation.score}</dd></div>
              <div>
                <dt>Scenarios</dt>
                <dd>{evaluation.scenariosPassed} of {evaluation.scenariosRun}</dd>
              </div>
              <div>
                <dt>Scenario set</dt>
                <dd>{evaluation.scenarioSetId} v{evaluation.scenarioSetVersion}</dd>
              </div>
              <div>
                {/* Pinned so a set edited after a pass cannot keep vouching for this release. */}
                <dt>Report digest</dt>
                <dd className="mono" title={evaluation.reportSha256}>
                  {evaluation.reportSha256.slice(0, 12)}
                </dd>
              </div>
            </dl>
          )}

          <button type="button" className="btn" disabled={evaluating}
                  onClick={() => void evaluate(chosen.releaseId)}>
            {evaluating ? "Evaluating..." : `Evaluate r${chosen.releaseNumber}`}
          </button>
        </section>
      )}

      {chosen && !chosen.live && (
        <PromotionPanel
          // Keyed by the release alone, deliberately not by this screen's reload counter. The
          // panel refreshes itself after a move, and including the counter would remount it —
          // discarding, among other things, the explanation of why a lost race changed nothing.
          key={chosen.releaseId}
          brainId={brainId}
          instanceSlug={instanceSlug}
          candidate={chosen}
          live={live}
          // A release that is not live goes live by promotion when it is newer than what is live,
          // and by rollback when it is older. Same gate, same check; the word differs because the
          // record of what happened should say which one it was.
          action={live && chosen.releaseNumber < live.releaseNumber ? "rollback" : "promote"}
          onMoved={reload}
        />
      )}

      {chosen?.live && (
        <p className="ok-note">
          r{chosen.releaseNumber} is what production answers with now.
        </p>
      )}
    </div>
  );
}
