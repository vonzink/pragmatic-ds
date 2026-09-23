import { useState } from "react";
import { useModalDialog } from "./useModalDialog";
import type { ReleaseSummary } from "../types";

const MINIMUM_REASON = 12;
const MAXIMUM_REASON = 400;

/**
 * The last thing between a click and what customers get.
 *
 * Everything here exists because this is the only irreversible-feeling action in the product. It
 * names both sides by number and by model, so the difference is visible rather than assumed; it
 * says in words that production callers will switch; and it requires a reason long enough to be a
 * reason, because the pointer history is the record of every change to what customers were
 * answered with and "update" tells whoever reads it in six months nothing at all.
 *
 * The reason is bounded at both ends. The floor stops a keystroke from passing for an explanation.
 * The ceiling is the column's, and a reason truncated by the database would be a record that says
 * something other than what its author wrote.
 *
 * Focus moves to the dialog on open, stays inside it, and returns to whatever opened it on close,
 * because a confirmation a keyboard user has to hunt for is a confirmation they will learn to
 * skip. It lands on the dialog element and never on the confirm button: an operator who presses
 * Enter out of habit on a dialog that has just appeared must not thereby move production.
 */
export default function ConfirmLiveChangeDialog(
  { open, action, candidate, current, busy, error, onConfirm, onCancel }: {
    open: boolean;
    action: "promote" | "rollback";
    candidate: ReleaseSummary;
    /** What is live now. Null when nothing has ever been promoted. */
    current: ReleaseSummary | null;
    busy: boolean;
    error: string | null;
    onConfirm: (reason: string) => void;
    onCancel: () => void;
  },
) {
  const [reason, setReason] = useState("");
  // Escape does exactly what Cancel does, and Cancel is disabled while the move is in flight.
  // Passing null then keeps Escape from being a hidden second dismissal that can do more than the
  // buttons on screen — dismissing mid-flight would drop the idempotency key the attempt retries
  // under, turning one interrupted promotion into two.
  const dialog = useModalDialog<HTMLDivElement>({
    active: open,
    onEscape: busy ? null : onCancel,
    initialFocus: "dialog",
  });

  if (!open) return null;

  const trimmed = reason.trim();
  const usable = trimmed.length >= MINIMUM_REASON && trimmed.length <= MAXIMUM_REASON;
  const verb = action === "promote" ? "Apply to live" : "Roll back";

  return (
    <div className="dialog-backdrop">
      <div
        className="card dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="live-change-title"
        tabIndex={-1}
        ref={dialog}
      >
        <h2 id="live-change-title">
          {action === "promote" ? "Apply this release to live?" : "Roll live back to this release?"}
        </h2>

        <p>
          Production callers will switch to this release as soon as this succeeds. Runs already in
          flight finish against the release they started with.
        </p>

        <dl className="parsed-facts">
          <div>
            <dt>Live now</dt>
            <dd>
              {current
                ? <>r{current.releaseNumber}
                  <span className="muted"> {current.provider} / {current.model}</span></>
                : <span className="qualifier">Nothing is live</span>}
            </dd>
          </div>
          <div>
            <dt>{action === "promote" ? "Becomes live" : "Rolling back to"}</dt>
            <dd>
              r{candidate.releaseNumber}
              <span className="muted"> {candidate.provider} / {candidate.model}</span>
            </dd>
          </div>
        </dl>

        <label>
          Change reason
          <textarea
            rows={2}
            value={reason}
            maxLength={MAXIMUM_REASON}
            onChange={(e) => setReason(e.target.value)}
          />
          <span className="field-hint">
            Recorded permanently against this movement. {MINIMUM_REASON}-{MAXIMUM_REASON}{" "}
            characters; {trimmed.length} so far.
          </span>
        </label>

        {error && <div className="error-note" role="alert">{error}</div>}

        <div className="workbench-actions">
          <button type="button" className="btn" disabled={busy} onClick={onCancel}>Cancel</button>
          <button
            type="button"
            className="btn-primary"
            disabled={!usable || busy}
            onClick={() => onConfirm(trimmed)}
          >
            {busy ? "Applying..." : verb}
          </button>
        </div>
      </div>
    </div>
  );
}
