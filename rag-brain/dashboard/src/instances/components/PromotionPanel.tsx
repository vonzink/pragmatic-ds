import { useEffect, useState } from "react";
import { instanceApi } from "../api";
import ConfirmLiveChangeDialog from "./ConfirmLiveChangeDialog";
import type {
  PointerHistoryView,
  PointerStateView,
  PromotionDecisionView,
  ReleaseSummary,
} from "../types";

/**
 * Moving what production answers with.
 *
 * ### The gate is the server's
 *
 * Every reason a release may not ship is re-checked at promotion time, because a release is
 * immutable but the world around it is not: a collection disabled, a credential withdrawn, or a
 * scenario set revised since authoring all have to stop it. This panel shows the codes the server
 * returned and disables the action until it returns none. It never forms its own opinion about
 * whether a release looks promotable — a screen that decided that locally would eventually
 * disagree with the server, and the disagreement would be in the direction of shipping.
 *
 * ### Compare-and-set, and what to do when it fails
 *
 * A move states which release it believes is live and at which pointer version. Both must still
 * hold. When they do not, the server answers `LIVE_POINTER_CHANGED` and the pointer does not move —
 * somebody else changed production while this page was open.
 *
 * That is deliberately **not** retried. Retrying with a refreshed version would apply a decision
 * made against a world that no longer exists, which is the exact failure the check was added to
 * prevent. Instead the panel refreshes, shows what is live now, and requires the confirmation to be
 * given again against the new reality.
 *
 * ### Four answers, not one
 *
 * "Promotable", "the gate refused this release", "promotion is switched off for this deployment"
 * and "the gate could not be reached" are kept apart, because each sends an operator somewhere
 * different: to the confirmation, to the release, to the deployment's configuration, and to
 * whoever runs the deployment. Collapsing an unreachable gate into the refusal list is the
 * costliest of those confusions — it reads as though the release was examined and found wanting,
 * so somebody re-evaluates a release when nothing was ever wrong with it.
 *
 * Nothing here fabricates a pointer version. When the pointer cannot be read the panel says so and
 * stays shut: zero is a value the pointer can genuinely hold, never a stand-in for unknown, and a
 * made-up version either fails the compare-and-set or succeeds against a state nobody saw.
 */
export default function PromotionPanel(
  { brainId, instanceSlug, candidate, live, action, onMoved }: {
    brainId: string;
    instanceSlug: string;
    candidate: ReleaseSummary;
    /** The release that is live now, for the confirmation to name. */
    live: ReleaseSummary | null;
    action: "promote" | "rollback";
    onMoved: () => void;
  },
) {
  const [pointer, setPointer] = useState<PointerHistoryView | null>(null);
  const [decision, setDecision] = useState<PromotionDecisionView | null>(null);
  /** Why the gate has no verdict, as opposed to a verdict of "no". */
  const [unreadable, setUnreadable] = useState<string | null>(null);
  const [checking, setChecking] = useState(true);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [staleNote, setStaleNote] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  const base = `/api/ai/admin/instances/${encodeURIComponent(instanceSlug)}`;
  const brainQuery = `brain=${encodeURIComponent(brainId)}`;

  useEffect(() => {
    let cancelled = false;
    setChecking(true);
    setUnreadable(null);

    // The pointer first: the gate check itself asserts a view of live, so there is nothing to ask
    // until this page knows what that view is.
    instanceApi
      .get<PointerHistoryView>(`${base}/pointer?${brainQuery}`)
      .then((state) => {
        if (cancelled) return null;
        setPointer(state);
        // Nothing to ask the gate when the deployment does not permit a move. Composing a check
        // to be told what the pointer already said would offer an action that cannot succeed.
        if (state?.promotionEnabled === false) return null;
        return instanceApi.post<PromotionDecisionView>(
          `${base}/releases/${encodeURIComponent(candidate.releaseId)}/promotion-check`
          + `?${brainQuery}`,
          {
            expectedLiveReleaseId: state.liveReleaseId,
            expectedPointerVersion: state.pointerVersion,
            actorId: "dashboard",
            changeReason: "gate check",
          });
      })
      .then((view) => {
        if (cancelled) return;
        if (view) setDecision(view);
        setChecking(false);
      })
      .catch((e) => {
        if (cancelled) return;
        // An unreachable gate has not allowed anything, so the action stays shut — but it has not
        // refused this release either, and saying it did would send someone to fix a release that
        // nothing is wrong with. The pointer stays null, so no version is invented from a failure.
        setDecision(null);
        setUnreadable((e as Error).message);
        setChecking(false);
      });

    return () => { cancelled = true; };
  }, [base, brainQuery, candidate.releaseId, nonce]);

  async function move(reason: string) {
    if (!pointer) return;
    setBusy(true);
    setError(null);
    try {
      const next = await instanceApi.postIdempotent<PointerStateView>(
        `${base}/releases/${encodeURIComponent(candidate.releaseId)}`
        + `/${action === "promote" ? "apply-to-live" : "rollback"}?${brainQuery}`,
        {
          expectedLiveReleaseId: pointer.liveReleaseId,
          expectedPointerVersion: pointer.pointerVersion,
          actorId: "dashboard",
          changeReason: reason,
        },
        crypto.randomUUID());
      setPointer({ ...pointer, liveReleaseId: next.liveReleaseId,
        pointerVersion: next.pointerVersion });
      setConfirming(false);
      onMoved();
    } catch (e) {
      const code = (e as { code?: string }).code ?? null;
      if (code === "LIVE_POINTER_CHANGED") {
        // Not retried, and not retryable: the confirmation was given against a picture of
        // production that is no longer true.
        setConfirming(false);
        setStaleNote(
          "Production changed while this page was open, so nothing moved. What is live now is "
          + "shown below; confirm again if you still want this release.");
        setNonce((n) => n + 1);
        onMoved();
      } else {
        setError((e as Error).message);
      }
    } finally {
      setBusy(false);
    }
  }

  const allowed = decision?.allowed === true;
  const codes = decision?.blockingCodes ?? [];
  // Its own answer rather than one row of a list: no amount of work on this release changes it,
  // and the thing to change is the deployment's configuration.
  // The pointer read is the first-hand answer; the gate's code is the same fact re-checked at
  // move time, and is kept as the answer for a deployment whose pointer read predates the flag.
  const promotionOff = pointer?.promotionEnabled === false || codes.includes(PROMOTION_DISABLED);
  const refusals = codes.filter((code) => code !== PROMOTION_DISABLED);
  // Deliberately not the same words as the dialog's confirm button. This one opens a
  // confirmation; that one changes production. Naming both "Apply to live" would put two
  // identically-labelled buttons on screen at the moment it matters most who is pressing what.
  const verb = action === "promote" ? "Review and apply to live" : "Review and roll back";

  return (
    <section className="card workbench-card">
      <h3>{action === "promote" ? "Promotion" : "Rollback"}</h3>

      {staleNote && <p className="warn-note" role="status">{staleNote}</p>}

      {checking && <p className="muted">Checking the promotion gate...</p>}

      {/* A failed background read, so it is announced politely rather than interrupting. */}
      {!checking && unreadable !== null && (
        <div className="warn-note" role="status">
          <p>
            The promotion gate could not be checked, so nothing can be promoted from here. This
            says nothing about the release: it was never examined.
          </p>
          <p><span className="mono">{unreadable}</span></p>
        </div>
      )}

      {!checking && promotionOff && (
        <div className="warn-note" role="status">
          <p>
            Promotion is switched off for this deployment, so no release can go live from here
            regardless of its own state.
          </p>
        </div>
      )}

      {!checking && decision && !allowed && refusals.length > 0 && (
        <div className="warn-note" role="status">
          <p>This release cannot go live yet:</p>
          <ul className="warn-list">
            {refusals.map((code) => (
              <li key={code}>
                <span className="mono">{code}</span>
                {GATE_HELP[code] && <span className="muted"> {GATE_HELP[code]}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}

      {!checking && allowed && (
        <p className="ok-note">Every gate passes. This release can go live.</p>
      )}

      <dl className="parsed-facts">
        <div>
          <dt>Live now</dt>
          <dd>{pointer?.liveReleaseId
            ? <span className="mono">{pointer.liveReleaseId.slice(0, 8)}</span>
            : <span className="qualifier">Nothing is live</span>}</dd>
        </div>
        <div>
          <dt>Pointer version</dt>
          {/* Shown because it is half of what the move asserts, and because a version that jumped
              while this page was open is the visible form of somebody else promoting. */}
          <dd>{pointer ? pointer.pointerVersion : <span className="qualifier">Unknown</span>}</dd>
        </div>
      </dl>

      <button
        type="button"
        className="btn-primary"
        disabled={!allowed || busy || !pointer}
        onClick={() => setConfirming(true)}
      >
        {verb}
      </button>

      <ConfirmLiveChangeDialog
        open={confirming}
        action={action}
        candidate={candidate}
        current={live}
        busy={busy}
        error={error}
        onConfirm={(reason) => void move(reason)}
        onCancel={() => { setConfirming(false); setError(null); }}
      />
    </section>
  );
}

/** Not a property of the release, which is why it is not listed among the release's refusals. */
const PROMOTION_DISABLED = "INSTANCE_PROMOTION_DISABLED";

/**
 * Readings of the gate's refusals.
 *
 * Each one means "something changed since this release was authored", which the code names but
 * does not explain, and each is fixed somewhere other than this panel.
 */
const GATE_HELP: Record<string, string> = {
  EVALUATION_NOT_RUN: "this release has not been evaluated against its scenario set yet",
  EVALUATION_FAILED: "the last evaluation scored below this release's own minimum",
  EVALUATION_STALE: "the scenario set changed since this release was evaluated; evaluate again",
  MODEL_NOT_CONFIGURED: "the deployment no longer has a credential for this release's model",
  CORPUS_COLLECTION_DISABLED: "a collection this release pins has been disabled",
  CORPUS_COLLECTION_VERSION_STALE:
    "a collection this release pins has changed; the release would retrieve documents it was "
    + "never evaluated against",
  INSTANCE_DISABLED: "this instance is disabled",
};
