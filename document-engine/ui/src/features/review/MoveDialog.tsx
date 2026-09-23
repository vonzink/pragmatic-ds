/**
 * The one place a reviewer names a *target* for the selected pages: an existing
 * document to move them into, or a type for the new document they become.
 *
 * Two modes, one dialog, because they answer the same question — "where do these
 * pages go?" — and differ only in what the answer names. `move` lists the
 * documents already in the view; `new` lists the known document types.
 *
 * Type source: the union of a small hardcoded known-types list with whatever
 * `documentTypeCode`s the current view already contains. As of Spec 3 the
 * engine classifies every seeded type, and the known list carries all of them;
 * regroup only ever assigns among types the engine classifies, so a fixed list
 * plus whatever is on screen is the whole universe — and it guarantees a
 * promotion target even for a package whose documents are all UNKNOWN.
 */

import { useState } from 'react';
import type { DocumentView } from '../../lib/api/types.ts';

export type MoveDialogMode = 'move' | 'new';

export type MoveDialogProps = {
  mode: MoveDialogMode;
  /** Existing documents, the targets a `move` can pick. */
  documents: readonly DocumentView[];
  /** The document types a `new` can pick. Non-empty. */
  knownTypes: readonly string[];
  onSubmitMove: (documentId: string) => void;
  onSubmitNew: (documentTypeCode: string) => void;
  onCancel: () => void;
};

export default function MoveDialog({
  mode,
  documents,
  knownTypes,
  onSubmitMove,
  onSubmitNew,
  onCancel,
}: MoveDialogProps) {
  // Default to the first option so a submit is always meaningful, even if the
  // reviewer never touches the select.
  const [targetDocumentId, setTargetDocumentId] = useState<string>(documents[0]?.id ?? '');
  const [typeCode, setTypeCode] = useState<string>(knownTypes[0] ?? '');

  const moving = mode === 'move';
  const canSubmit = moving ? targetDocumentId !== '' : typeCode !== '';

  const submit = () => {
    if (moving) {
      if (targetDocumentId !== '') onSubmitMove(targetDocumentId);
    } else if (typeCode !== '') {
      onSubmitNew(typeCode);
    }
  };

  return (
    <div
      data-testid="move-dialog"
      role="dialog"
      aria-modal="true"
      aria-label={moving ? 'Move pages to a document' : 'New document from pages'}
      className="mt-2 rounded-md border border-sky-300 bg-sky-50 p-3"
    >
      <h4 className="text-xs font-semibold text-slate-700">
        {moving ? 'Move the selected pages to…' : 'New document from the selected pages'}
      </h4>

      {moving ? (
        <label className="mt-2 flex items-center gap-2 text-xs text-slate-600">
          <span>Document</span>
          <select
            data-testid="move-dialog-target"
            value={targetDocumentId}
            onChange={(event) => { setTargetDocumentId(event.target.value); }}
            className="rounded-sm border border-slate-300 bg-white px-1.5 py-0.5 text-xs"
          >
            {documents.map((document) => (
              <option key={document.id} value={document.id}>
                {document.documentTypeCode} · document {String(document.ordinal + 1)}
              </option>
            ))}
          </select>
        </label>
      ) : (
        <label className="mt-2 flex items-center gap-2 text-xs text-slate-600">
          <span>Type</span>
          <select
            data-testid="move-dialog-type"
            value={typeCode}
            onChange={(event) => { setTypeCode(event.target.value); }}
            className="rounded-sm border border-slate-300 bg-white px-1.5 py-0.5 text-xs"
          >
            {knownTypes.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </label>
      )}

      <div className="mt-3 flex items-center gap-2">
        <button
          type="button"
          data-testid="move-dialog-submit"
          disabled={!canSubmit}
          onClick={submit}
          className="rounded-sm bg-sky-600 px-2 py-0.5 text-[11px] font-medium text-white hover:bg-sky-700 disabled:opacity-40"
        >
          {moving ? 'Move' : 'Create'}
        </button>
        <button
          type="button"
          data-testid="move-dialog-cancel"
          onClick={onCancel}
          className="rounded-sm border border-slate-300 px-2 py-0.5 text-[11px] text-slate-600 hover:border-slate-400"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}
