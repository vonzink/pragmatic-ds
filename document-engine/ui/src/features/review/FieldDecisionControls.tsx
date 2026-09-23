import { useState } from 'react';
import { isMissingField } from './fields.ts';
import type { FieldView, Uuid } from '../../lib/api/types.ts';

/** One human decision on one occurrence, in the shape `PATCH /v1/fields/{id}` takes. */
export type FieldDecision =
  | { action: 'CONFIRM' }
  | { action: 'REJECT' }
  | { action: 'CORRECT'; value: string; pageIndex: number };

export type FieldDecisionControlsProps = {
  field: FieldView;
  /** Pages in the selected document; the correct dialog offers 0..count-1. */
  documentPageCount: number;
  /** The document-relative page currently in the viewer — the dialog's default. */
  currentDocumentPageIndex: number;
  onDecide: (fieldId: Uuid, decision: FieldDecision) => Promise<void>;
};

/**
 * Confirm / Correct / Reject for one occurrence. The meanings are the gold set's
 * (design §4): Confirm = the engine's value is exactly what is printed; Correct =
 * this value, on this page; Reject = the document does not print this field.
 * A MISSING field cannot be confirmed — there is nothing to confirm — so it
 * offers only Correct (supply the printed value) and Reject (it is not printed).
 */
export default function FieldDecisionControls({
  field,
  documentPageCount,
  currentDocumentPageIndex,
  onDecide,
}: FieldDecisionControlsProps) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState('');
  const [pageIndex, setPageIndex] = useState(currentDocumentPageIndex);
  const [busy, setBusy] = useState(false);
  const missing = isMissingField(field);

  const decide = async (decision: FieldDecision) => {
    setBusy(true);
    try {
      await onDecide(field.id, decision);
    } finally {
      setBusy(false);
    }
  };

  const openDialog = () => {
    setValue('');
    setPageIndex(currentDocumentPageIndex);
    setOpen(true);
  };

  const pageOptions = Array.from({ length: Math.max(1, documentPageCount) }, (_, index) => index);
  const buttonClass = 'rounded border px-2 py-0.5 text-xs disabled:opacity-50';

  return (
    <div data-testid="field-decision" className="flex flex-wrap items-center gap-1.5 px-4 pb-2">
      {missing ? null : (
        <button
          type="button"
          disabled={busy}
          className={`${buttonClass} border-emerald-300 text-emerald-800`}
          onClick={() => { void decide({ action: 'CONFIRM' }); }}
        >
          Confirm
        </button>
      )}
      <button
        type="button"
        disabled={busy}
        className={`${buttonClass} border-sky-300 text-sky-800`}
        onClick={openDialog}
      >
        Correct
      </button>
      <button
        type="button"
        disabled={busy}
        className={`${buttonClass} border-rose-300 text-rose-800`}
        onClick={() => { void decide({ action: 'REJECT' }); }}
      >
        Reject
      </button>

      {open ? (
        <form
          role="dialog"
          aria-label={`Correct ${field.fieldName}`}
          className="mt-1 w-full space-y-2 rounded border border-slate-200 bg-slate-50 p-2"
          onSubmit={(event) => {
            event.preventDefault();
            setOpen(false);
            void decide({ action: 'CORRECT', value: value.trim(), pageIndex });
          }}
        >
          <label className="block text-xs text-slate-600">
            Value as printed
            <input
              autoFocus
              className="mt-0.5 w-full rounded border border-slate-300 px-2 py-1 font-mono text-sm"
              value={value}
              onChange={(event) => { setValue(event.target.value); }}
            />
          </label>
          <label className="block text-xs text-slate-600">
            Page
            <select
              className="mt-0.5 rounded border border-slate-300 px-2 py-1 text-sm"
              value={String(pageIndex)}
              onChange={(event) => { setPageIndex(Number(event.target.value)); }}
            >
              {pageOptions.map((index) => (
                <option key={index} value={String(index)}>
                  {index}
                </option>
              ))}
            </select>
          </label>
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={value.trim() === ''}
              className={`${buttonClass} border-sky-400 bg-sky-600 text-white`}
            >
              Save correction
            </button>
            <button type="button" className={buttonClass} onClick={() => { setOpen(false); }}>
              Cancel
            </button>
          </div>
        </form>
      ) : null}
    </div>
  );
}
