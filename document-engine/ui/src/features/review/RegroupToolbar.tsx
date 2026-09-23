/**
 * The verbs of select-and-act. Once a reviewer has ticked one or more pages,
 * this bar is what they do with them: move into an existing document, promote
 * into a new one, split off, or unassign (design §9).
 *
 * It holds no selection state and calls no client — it reports intent upward and
 * nothing else. That keeps the one place that knows *which* pages are selected
 * (DocumentList) the one place that composes the delta, so the toolbar can never
 * disagree with the checkboxes about what "the selection" is.
 */

export type RegroupToolbarProps = {
  /** How many pages are ticked. Zero disables every action. */
  count: number;
  /** True while a regroup is in flight, so a double-submit is impossible. */
  busy?: boolean;
  onMove: () => void;
  onNew: () => void;
  onSplit: () => void;
  onUnassign: () => void;
};

export default function RegroupToolbar({
  count,
  busy = false,
  onMove,
  onNew,
  onSplit,
  onUnassign,
}: RegroupToolbarProps) {
  const disabled = count === 0 || busy;
  const buttonClass =
    'rounded-sm border border-slate-300 bg-white px-2 py-0.5 text-[11px] text-slate-700 hover:border-slate-400 disabled:cursor-not-allowed disabled:opacity-40';

  return (
    <div
      data-testid="regroup-toolbar"
      className="flex flex-wrap items-center gap-1.5 rounded-md border border-slate-200 bg-white px-2 py-1.5"
    >
      <span data-testid="regroup-selection-count" className="text-[11px] text-slate-500">
        {count === 0 ? 'Select pages to regroup' : `${String(count)} selected`}
      </span>
      <span className="ml-auto flex flex-wrap gap-1.5">
        <button
          type="button"
          data-testid="regroup-move"
          disabled={disabled}
          onClick={onMove}
          className={buttonClass}
        >
          Move to…
        </button>
        <button
          type="button"
          data-testid="regroup-new"
          disabled={disabled}
          onClick={onNew}
          className={buttonClass}
        >
          New document
        </button>
        <button
          type="button"
          data-testid="regroup-split"
          disabled={disabled}
          onClick={onSplit}
          className={buttonClass}
        >
          Split here
        </button>
        <button
          type="button"
          data-testid="regroup-unassign"
          disabled={disabled}
          onClick={onUnassign}
          className={buttonClass}
        >
          Unassign
        </button>
      </span>
    </div>
  );
}
