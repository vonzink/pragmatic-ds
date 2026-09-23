/**
 * The logical documents a package was split into — and, just as importantly,
 * the pages that ended up in none of them.
 *
 * `PackageDocumentsView`'s own javadoc puts it best: "a reviewer must see that
 * page 9 was blank and pages 10–11 duplicates, not wonder where they went."
 * Silently dropping unassigned pages would make the page count stop adding up,
 * and a reviewer who cannot account for every page cannot trust any of them.
 *
 * Beyond showing the split, this pane lets a reviewer *fix* it: tick pages, then
 * act — move, promote to a new document, split off, or unassign (design §9).
 * The selection lives here and the delta is composed here, so the checkboxes and
 * the regroup request can never disagree about what "the selection" is. The
 * network call goes straight to the client; on success the shell is asked to
 * refetch and let the job poll surface the re-extraction that a regroup triggers.
 */

import { useMemo, useState } from 'react';
import BoundaryChip from './BoundaryChip.tsx';
import { pageChipTitle } from './boundary.ts';
import ConfidenceBadge from './Confidence.tsx';
import MoveDialog, { type MoveDialogMode } from './MoveDialog.tsx';
import RegroupToolbar from './RegroupToolbar.tsx';
import {
  buildMoveDelta,
  buildNewDocumentDelta,
  buildSplitDelta,
  buildUnassignDelta,
} from './regroup.ts';
import { ApiError, overridePageVerdict, regroup } from '../../lib/api/client.ts';
import type {
  DocumentView,
  PageVerdict,
  RegroupRequest,
  UnassignedPageView,
  Uuid,
} from '../../lib/api/types.ts';

export type DocumentListProps = {
  documents: readonly DocumentView[];
  unassignedPages: readonly UnassignedPageView[];
  selectedDocumentId: Uuid | null;
  onSelectDocument: (documentId: Uuid) => void;
  /** Jumps the viewer to a page, by `packagePageIndex`. */
  onSelectPage: (packagePageIndex: number) => void;
  /** The package these documents belong to. Regroup actions are hidden without it. */
  packageId?: Uuid;
  /** Called after a regroup succeeds, so the shell can refetch and watch re-extraction. */
  onRegrouped?: () => void;
  /** Surfaces a regroup/verdict failure to the shell's error banner. */
  onError?: (error: ApiError) => void;
};

/** Why an unassigned page stayed out, in words a reviewer can act on. */
const UNASSIGNED_REASONS: Record<string, string> = {
  BLANK: 'blank — no text and no ink',
  DUPLICATE: 'duplicate of an earlier page',
};

/**
 * The document types a reviewer may promote a page into, before unioning with
 * whatever the current view already contains. Regroup only assigns among types
 * the engine already classifies, and as of Spec 3 that is EVERY seeded type —
 * eight real types plus UNKNOWN — so a fixed list is the whole universe, and it
 * guarantees a target even when every document is UNKNOWN.
 */
const KNOWN_DOCUMENT_TYPES = [
  'PAYSTUB',
  'BANK_STATEMENT',
  'W2',
  'DRIVERS_LICENSE',
  'MORTGAGE_STATEMENT',
  'HOI_DECLARATION',
  'PURCHASE_CONTRACT',
  'TAX_RETURN',
  'UNKNOWN',
];

/**
 * Reads whichever page index arrived.
 *
 * `GET /v1/packages/{id}/documents` sends `packagePageIndex` (0-based) while
 * the export endpoint sends `pageNumber` (1-based), and `openapi.json` collides
 * the two shapes into one schema — see the note on `UnassignedPageView` in
 * `lib/api/types.ts`. Reading both is three lines and removes the chance of a
 * blank cell if the spec is later disambiguated the other way.
 */
function packageIndexOf(page: UnassignedPageView): number | null {
  if (page.packagePageIndex !== undefined) return page.packagePageIndex;
  if (page.pageNumber !== undefined) return page.pageNumber - 1;
  return null;
}

export default function DocumentList({
  documents,
  unassignedPages,
  selectedDocumentId,
  onSelectDocument,
  onSelectPage,
  packageId,
  onRegrouped,
  onError,
}: DocumentListProps) {
  /** The ticked pages, by id. The single source of truth the toolbar acts on. */
  const [selected, setSelected] = useState<ReadonlySet<Uuid>>(new Set());
  const [dialogMode, setDialogMode] = useState<MoveDialogMode | null>(null);
  /**
   * Pages whose blank/duplicate verdict the reviewer has overridden THIS
   * session, before the next refetch. The durable truth lives in the backend
   * now (audit C5): a cleared, still-unassigned page comes back in
   * `unassignedPages` with reason CLEARED, so it survives reloads and other
   * reviewers' refetches. This local set only bridges the moment between the
   * override succeeding and the next read.
   */
  const [cleared, setCleared] = useState<ReadonlySet<Uuid>>(new Set());
  /** True while a regroup or verdict is in flight — blocks a second submit. */
  const [busy, setBusy] = useState(false);

  const canRegroup = packageId !== undefined;

  /** Which document currently holds a page — for the type a split should inherit. */
  const documentByPage = useMemo(() => {
    const map = new Map<Uuid, DocumentView>();
    for (const document of documents) {
      for (const page of document.pages) map.set(page.pageId, document);
    }
    return map;
  }, [documents]);

  /** The known list unioned with the types already on screen, de-duplicated. */
  const knownTypes = useMemo(() => {
    const set = new Set<string>(KNOWN_DOCUMENT_TYPES);
    for (const document of documents) set.add(document.documentTypeCode);
    return [...set].sort((a, b) => a.localeCompare(b));
  }, [documents]);

  const toggle = (pageId: Uuid) => {
    setSelected((current) => {
      const next = new Set(current);
      if (!next.delete(pageId)) next.add(pageId);
      return next;
    });
  };

  const runRegroup = async (delta: RegroupRequest) => {
    if (!packageId) return;
    setBusy(true);
    try {
      await regroup(packageId, delta);
      setSelected(new Set());
      setDialogMode(null);
      onRegrouped?.();
    } catch (caught) {
      if (caught instanceof ApiError) onError?.(caught);
    } finally {
      setBusy(false);
    }
  };

  const selectedIds = [...selected];

  const handleUnassign = () => {
    if (selectedIds.length > 0) void runRegroup(buildUnassignDelta(selectedIds));
  };

  const handleSplit = () => {
    if (selectedIds.length === 0) return;
    // Inherit the type of the document the first selected page belongs to; a
    // pure tray selection has none, so UNKNOWN is the honest default.
    const inherited = documentByPage.get(selectedIds[0])?.documentTypeCode ?? 'UNKNOWN';
    void runRegroup(buildSplitDelta(selectedIds, inherited));
  };

  const handleMoveSubmit = (documentId: string) => {
    if (selectedIds.length > 0) void runRegroup(buildMoveDelta(selectedIds, documentId));
  };

  const handleNewSubmit = (documentTypeCode: string) => {
    if (selectedIds.length > 0) void runRegroup(buildNewDocumentDelta(selectedIds, documentTypeCode));
  };

  const runVerdict = async (pageId: Uuid, verdict: PageVerdict) => {
    setBusy(true);
    try {
      await overridePageVerdict(pageId, verdict, 'reviewer override');
      // Locally mark the page assignable rather than refetch — see `cleared`.
      setCleared((current) => new Set(current).add(pageId));
    } catch (caught) {
      if (caught instanceof ApiError) onError?.(caught);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-testid="document-list" className="space-y-3">
      {canRegroup ? (
        <div className="space-y-2">
          <RegroupToolbar
            count={selected.size}
            busy={busy}
            onMove={() => { setDialogMode('move'); }}
            onNew={() => { setDialogMode('new'); }}
            onSplit={handleSplit}
            onUnassign={handleUnassign}
          />
          {dialogMode ? (
            <MoveDialog
              mode={dialogMode}
              documents={documents}
              knownTypes={knownTypes}
              onSubmitMove={handleMoveSubmit}
              onSubmitNew={handleNewSubmit}
              onCancel={() => { setDialogMode(null); }}
            />
          ) : null}
        </div>
      ) : null}

      <ul className="space-y-2">
        {documents.map((document) => {
          const selectedDoc = document.id === selectedDocumentId;
          return (
            <li key={document.id}>
              <button
                type="button"
                data-testid="document-row"
                data-document-id={document.id}
                data-selected={selectedDoc ? 'true' : 'false'}
                aria-pressed={selectedDoc}
                onClick={() => { onSelectDocument(document.id); }}
                className={`w-full rounded-md border px-3 py-2 text-left ${
                  selectedDoc
                    ? 'border-sky-600 bg-sky-50 ring-1 ring-sky-300'
                    : 'border-slate-200 bg-white hover:border-slate-300'
                }`}
              >
                <span className="flex items-baseline gap-2">
                  <span className="font-medium text-slate-900">{document.documentTypeCode}</span>
                  <span className="text-xs text-slate-500">
                    document {String(document.ordinal + 1)}
                  </span>
                  <ConfidenceBadge confidence={document.classificationConfidence} className="ml-auto" />
                </span>
                <span className="mt-1 flex items-center gap-1.5 text-xs text-slate-500">
                  <span>
                    {document.pages.length === 1
                      ? '1 page'
                      : `${String(document.pages.length)} pages`}{' '}
                    · {document.reviewStatus.toLowerCase().replace(/_/g, ' ')}
                  </span>
                  {document.absorbedUntypedPages ? (
                    // Pages the splitter absorbed on faith (no type of their own). A large
                    // count is where an unrelated, untypable document was probably glued on.
                    <span
                      data-testid={`absorbed-untyped-${document.id}`}
                      title="Pages with no type of their own that joined this document as continuations"
                      className="rounded bg-amber-50 px-1.5 py-0.5 text-amber-800"
                    >
                      {document.absorbedUntypedPages === 1
                        ? '1 untyped page absorbed'
                        : `${String(document.absorbedUntypedPages)} untyped pages absorbed`}
                    </span>
                  ) : null}
                  <BoundaryChip
                    provenance={document.boundaryProvenance}
                    className="ml-auto"
                    testId={`boundary-provenance-${document.id}`}
                  />
                </span>
              </button>

              <ul className="mt-1 flex flex-wrap gap-1 pl-1">
                {document.pages.map((page) => (
                  <li key={page.pageId} className="flex items-center gap-1">
                    {canRegroup ? (
                      <input
                        type="checkbox"
                        data-testid={`page-checkbox-${page.pageId}`}
                        aria-label={`Select page ${String(page.packagePageIndex + 1)}`}
                        checked={selected.has(page.pageId)}
                        onChange={() => { toggle(page.pageId); }}
                        className="h-3 w-3"
                      />
                    ) : null}
                    <button
                      type="button"
                      data-testid="document-page-chip"
                      data-page-index={page.packagePageIndex}
                      data-multi-document={
                        (page.classification?.coQualifyingTypes.length ?? 0) > 1 ? 'true' : 'false'
                      }
                      onClick={() => { onSelectPage(page.packagePageIndex); }}
                      title={pageChipTitle(page.classification)}
                      className={`rounded-sm border px-1.5 py-0.5 text-[11px] tabular-nums hover:border-slate-400 ${
                        (page.classification?.coQualifyingTypes.length ?? 0) > 1
                          ? 'border-amber-400 bg-amber-50 text-amber-900'
                          : 'border-slate-200 bg-white text-slate-600'
                      }`}
                    >
                      p{String(page.packagePageIndex + 1)}
                      {(page.classification?.coQualifyingTypes.length ?? 0) > 1 ? '\u2026' : ''}
                    </button>
                  </li>
                ))}
              </ul>
            </li>
          );
        })}
      </ul>

      {documents.length === 0 ? (
        <p data-testid="no-documents" className="rounded-md bg-slate-50 px-3 py-2 text-sm text-slate-500">
          No documents yet. Classification and splitting may still be running.
        </p>
      ) : null}

      {unassignedPages.length > 0 ? (
        <section data-testid="unassigned-pages" className="rounded-md border border-slate-200 bg-slate-50 p-3">
          <h3 className="text-xs font-semibold tracking-wide text-slate-600 uppercase">
            Pages in no document ({String(unassignedPages.length)})
          </h3>
          <ul className="mt-2 space-y-1">
            {unassignedPages.map((page) => {
              const index = packageIndexOf(page);
              // Selectable when the backend says CLEARED (the durable truth — survives reloads
              // and refetches) OR when the reviewer cleared it this session (instant feedback
              // before the next refetch catches up).
              const isCleared = page.reason === 'CLEARED' || cleared.has(page.pageId);
              return (
                <li
                  key={page.pageId}
                  data-testid="unassigned-page"
                  data-reason={page.reason}
                  data-cleared={isCleared ? 'true' : 'false'}
                  className="flex flex-wrap items-center gap-2"
                >
                  {canRegroup ? (
                    <input
                      type="checkbox"
                      data-testid={`page-checkbox-${page.pageId}`}
                      aria-label={`Select page ${index === null ? '' : String(index + 1)}`}
                      // Not assignable until its verdict is overridden — the backend
                      // answers 409 otherwise. Clearing the verdict is what makes the
                      // page selectable (design §9).
                      disabled={!isCleared}
                      title={
                        isCleared
                          ? undefined
                          : 'Override the blank/duplicate verdict before assigning this page'
                      }
                      checked={selected.has(page.pageId)}
                      onChange={() => { toggle(page.pageId); }}
                      className="h-3 w-3"
                    />
                  ) : null}
                  <button
                    type="button"
                    disabled={index === null}
                    onClick={() => { if (index !== null) onSelectPage(index); }}
                    className="text-left text-xs text-slate-600 underline-offset-2 hover:underline disabled:no-underline"
                  >
                    Page {index === null ? '?' : String(index + 1)} ·{' '}
                    {UNASSIGNED_REASONS[page.reason] ?? page.reason}
                  </button>

                  {isCleared ? (
                    <span className="text-[11px] text-emerald-700">ready to assign</span>
                  ) : page.reason === 'DUPLICATE' ? (
                    <button
                      type="button"
                      data-testid={`verdict-not-duplicate-${page.pageId}`}
                      disabled={busy}
                      onClick={() => { void runVerdict(page.pageId, 'NOT_DUPLICATE'); }}
                      className="rounded-sm border border-slate-300 bg-white px-1.5 py-0.5 text-[11px] text-slate-700 hover:border-slate-400 disabled:opacity-40"
                    >
                      Not a duplicate
                    </button>
                  ) : page.reason === 'BLANK' ? (
                    <button
                      type="button"
                      data-testid={`verdict-not-blank-${page.pageId}`}
                      disabled={busy}
                      onClick={() => { void runVerdict(page.pageId, 'NOT_BLANK'); }}
                      className="rounded-sm border border-slate-300 bg-white px-1.5 py-0.5 text-[11px] text-slate-700 hover:border-slate-400 disabled:opacity-40"
                    >
                      Not blank
                    </button>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
