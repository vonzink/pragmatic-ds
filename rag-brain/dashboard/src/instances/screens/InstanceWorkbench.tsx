import { useEffect, useRef, useState } from "react";
import { instanceApi } from "../api";
import RunInputsSection from "../components/RunInputsSection";
import RunPreflightCard from "../components/RunPreflightCard";
import RunResultPanel from "../components/RunResultPanel";
import RunDiscussionPanel from "../components/RunDiscussionPanel";
import { useRunGroupPolling } from "../hooks/useRunGroupPolling";
import { useRunInputs } from "../hooks/useRunInputs";
import type {
  CancellationView,
  CreateRunGroupRequest,
  CreatedRunGroupView,
  InstanceDetail,
  PreflightView,
} from "../types";

/**
 * Running one instance, once, against one parse.
 *
 * The screen is a sequence because the backend is: a run pins a release, a parsed revision and a
 * corpus snapshot at submission, and every one of those is required. Rather than collect them in a
 * form and report four failures at once, each step produces a fact the next step needs, and Run
 * only appears once a priced estimate says the submission would be accepted.
 *
 * ### Where the release comes from
 *
 * The live release, and nowhere else. The workbench is for exercising what production answers
 * with; running a candidate against a parse is a comparison, and Compare is where that belongs.
 * An instance with no live release cannot run here at all — the submission would be refused with
 * `MEMBER_REQUEST_INVALID`, so the screen says so first rather than letting someone assemble a
 * whole run to find out.
 *
 * ### Keys
 *
 * Run and Cancel each mint one idempotency key on their first attempt and reuse it through every
 * retry, releasing it only once the action has landed — so the retry after a timeout carries the
 * key the timed-out attempt did, and resolves to the group that attempt already created rather
 * than starting a second. Upload, pin and freeze follow the same rule inside
 * {@link useRunInputs}, which owns them for every screen that starts a run.
 *
 * Preflight has no key because it creates nothing and calls no provider — which is also why it can
 * run automatically as soon as its inputs exist rather than hiding behind a button.
 */
export default function InstanceWorkbench(
  { brainId, instanceSlug, instance }: {
    brainId: string;
    instanceSlug: string;
    instance: InstanceDetail | null;
  },
) {
  const inputs = useRunInputs(brainId, instanceSlug);

  const [preflight, setPreflight] = useState<PreflightView | null>(null);
  const [preflightLoading, setPreflightLoading] = useState(false);
  const [preflightError, setPreflightError] = useState<string | null>(null);

  const [groupId, setGroupId] = useState<string | null>(null);
  const [runBusy, setRunBusy] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [cancelNote, setCancelNote] = useState<string | null>(null);

  const runKey = useRef<string | null>(null);
  const cancelKey = useRef<string | null>(null);

  const releaseId = instance?.liveRelease?.releaseId ?? null;
  const base = `/api/ai/admin/instances`;
  const brainQuery = `brain=${encodeURIComponent(brainId)}`;

  const request: CreateRunGroupRequest | null =
    releaseId && inputs.parsed && inputs.snapshot && inputs.parsed.compatibility.compatible
      ? {
        mode: "INDEPENDENT",
        comparisonDimension: null,
        members: [{
          instanceSlug,
          releaseId,
          registrationId: inputs.parsed.registrationId,
          corpusSnapshotId: inputs.snapshot.snapshotId,
        }],
      }
      : null;

  // Priced as soon as there is something to price. Safe to do without asking because preflight
  // writes no row and calls no provider; the whole point of it is to be free.
  //
  // Serialized for the dependency array rather than compared by identity: `request` is rebuilt
  // every render, so depending on the object would re-price on every keystroke elsewhere.
  const requestKey = request ? JSON.stringify(request) : null;
  useEffect(() => {
    if (!requestKey) { setPreflight(null); return; }
    let cancelled = false;
    setPreflightLoading(true);
    setPreflightError(null);

    instanceApi
      .post<PreflightView>(`${base}/run-groups/preflight?${brainQuery}`, JSON.parse(requestKey))
      .then((view) => { if (!cancelled) { setPreflight(view); setPreflightLoading(false); } })
      .catch((e) => {
        if (cancelled) return;
        // An estimate that could not be produced is not an estimate of zero, and Run stays shut.
        setPreflight(null);
        setPreflightError((e as Error).message);
        setPreflightLoading(false);
      });

    return () => { cancelled = true; };
  }, [requestKey, base, brainQuery]);

  const polling = useRunGroupPolling(brainId, groupId);
  const member = polling.detail?.members[0] ?? null;

  async function run() {
    if (!request || runBusy) return;
    if (runKey.current === null) runKey.current = crypto.randomUUID();
    setRunBusy(true);
    setRunError(null);
    try {
      const created = await instanceApi.postIdempotent<CreatedRunGroupView>(
        `${base}/run-groups?${brainQuery}`, request, runKey.current);
      setGroupId(created.groupId);
      runKey.current = null;
    } catch (e) {
      // Key retained on purpose. If the response was lost rather than the request, the retry
      // resolves to the group the first attempt already created instead of starting a second.
      setRunError((e as Error).message);
    } finally {
      setRunBusy(false);
    }
  }

  async function cancel() {
    if (!groupId) return;
    // A key of its own, on the same terms as the rest: one Cancel click, retried under one key.
    // Minting per attempt would send a second cancel command whose answer — nothing left to
    // cancel — reads as "the cancellation did not work" when it worked the first time.
    if (cancelKey.current === null) cancelKey.current = crypto.randomUUID();
    setCancelNote(null);
    try {
      const view = await instanceApi.postIdempotent<CancellationView>(
        `${base}/run-groups/${encodeURIComponent(groupId)}/cancel?${brainQuery}`,
        {}, cancelKey.current);
      // Cancelling and cancelled are different facts: a member already inside a provider call
      // cannot be recalled, and saying "cancelled" over it would be a lie the bill contradicts.
      setCancelNote(view.stillProcessingMembers > 0
        ? `Cancellation requested. ${view.stillProcessingMembers} member`
          + `${view.stillProcessingMembers === 1 ? " is" : "s are"} already past the point of `
          + `being stopped and will finish.`
        : `Cancelled ${view.cancelledMembers} member`
          + `${view.cancelledMembers === 1 ? "" : "s"}.`);
      cancelKey.current = null;
    } catch (e) {
      setCancelNote((e as Error).message);
    }
  }

  function startAnother() {
    // A new run is a new action all the way down, so every key is released rather than reused.
    setGroupId(null);
    setRunError(null);
    setCancelNote(null);
    runKey.current = null;
    cancelKey.current = null;
  }

  return (
    <div className="workbench">
      <h1>Workbench</h1>

      {instance && !releaseId && (
        <div className="error-note" role="alert">
          This instance has no live release, so there is nothing for a run to be pinned to. Promote
          a release before running.
        </div>
      )}

      <RunInputsSection brainId={brainId} inputs={inputs} />

      {preflightError && (
        <div className="error-note" role="alert">
          This run could not be estimated, so it cannot be started. {preflightError}
        </div>
      )}

      {request && <RunPreflightCard preflight={preflight} loading={preflightLoading} />}

      {!groupId && (
        <div className="workbench-actions">
          <button
            type="button"
            className="btn-primary"
            // Gated on the server's own verdict rather than on this screen's opinion of it.
            disabled={!preflight?.acceptable || runBusy}
            onClick={() => void run()}
          >
            {runBusy ? "Starting…" : runError ? "Retry run" : "Run"}
          </button>
          {runError && <span className="error-note" role="alert">{runError}</span>}
        </div>
      )}

      {groupId && (
        <section className="card workbench-card">
          <h2>Run</h2>
          <p className="result-status">
            <span className={`badge badge-${(polling.detail?.group.status ?? "queued").toLowerCase()}`}>
              {polling.detail?.group.status ?? "Submitted"}
            </span>
          </p>
          {polling.transientError && (
            // Explicitly not an error about the run. The run is still there; the asking failed.
            <p className="warn-note" role="status">
              Could not refresh just now ({polling.transientError}). Still watching.
            </p>
          )}
          {cancelNote && <p role="status">{cancelNote}</p>}
          {!polling.settled && (
            <button type="button" className="btn" onClick={() => void cancel()}>
              Cancel run
            </button>
          )}
          {polling.settled && (
            <button type="button" className="btn" onClick={startAnother}>
              Start another run
            </button>
          )}
        </section>
      )}

      {member && <RunResultPanel member={member} />}

      {member && member.status === "SUCCEEDED" && polling.detail && (
        // Addressed by the group the run is actually in rather than by the id this screen
        // submitted: the routes scope a member by its group, so a run reached through the wrong
        // one resolves to nothing rather than to somebody else's transcript.
        <RunDiscussionPanel
          brainId={brainId} groupId={polling.detail.group.groupId} runId={member.runId} />
      )}
    </div>
  );
}
