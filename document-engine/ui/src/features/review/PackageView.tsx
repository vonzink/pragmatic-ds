/**
 * The shell: upload a package, watch it process, then review it — document on
 * the left, data on the right, and one selection joining them.
 *
 * All the state that two panes must agree about lives here and nowhere else:
 * which document, which field, which page. That is deliberate. The moment the
 * viewer keeps its own idea of the current page, "click a field on page 2" and
 * "the overlay is on page 2" become two facts that can drift apart, and the
 * drift is invisible — the boxes still draw, just on the wrong page.
 *
 * Everything else is presentation. No confidence is computed here, no
 * classification decided, no value normalised: `docs/ARCHITECTURE.md` puts all
 * of that in the engine, and this component only ever renders what it was told.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import DocumentList from './DocumentList.tsx';
import type { FieldDecision } from './FieldDecisionControls.tsx';
import FieldPanel from './FieldPanel.tsx';
import JobStatus from './JobStatus.tsx';
import PageViewer from './PageViewer.tsx';
import UsageSummary from './UsageSummary.tsx';
import { occurrenceLabel } from './occurrences.ts';
import type { PdfPageRenderer } from './pdfRenderer.ts';
import {
  ApiError,
  correctField,
  getDocumentFields,
  getJob,
  getPackageDocuments,
  getPackagePages,
  getPackageUsage,
  markDocumentReviewed,
  resumeJob,
  uploadPackage,
} from '../../lib/api/client.ts';
import type {
  DocumentFieldsView,
  JobResponse,
  PackageDocumentsView,
  PackageUsageView,
  PageView,
  Uuid,
} from '../../lib/api/types.ts';

/** How often the job is re-read while it is still moving. */
const POLL_INTERVAL_MS = 1500;

/** How many consecutive poll failures before the UI stops claiming it is watching. */
const MAX_POLL_FAILURES = 6;

/**
 * What the file picker offers. A HINT ONLY — the server ignores every word of it and decides
 * from magic bytes (`MimeSniffer.java`), so this list is about not greying out a file the
 * engine would happily accept.
 *
 * HEIC is listed by EXTENSION as well as by type on purpose: the attribute is matched against
 * the type the operating system reports for the file, and only some platforms map `.heic` to
 * `image/heic` — on the rest, a type-only list hides the borrower's photo from the picker.
 */
const UPLOAD_ACCEPT = [
  'application/pdf',
  'image/png',
  'image/jpeg',
  'image/tiff',
  'image/heic',
  'image/heif',
  '.heic',
  '.heif',
].join(',');

/**
 * Has the pipeline stopped working on this job?
 *
 * HUMAN_REVIEW_REQUIRED belongs here: it is the engine's NORMAL end state — every stage ran, and
 * a person is now expected (StageRunner.markJobAwaitingReview). Omitting it meant every
 * successfully processed package polled forever, one request per interval per open tab, for work
 * that was already finished. Phase 6 review finding.
 */
function isTerminal(job: JobResponse | null): boolean {
  return (
    job?.status === 'COMPLETED' ||
    job?.status === 'FAILED' ||
    job?.status === 'HUMAN_REVIEW_REQUIRED'
  );
}

/**
 * An abort is a cleanup, not a failure — it must never reach the error banner.
 *
 * The signal is the whole test. `fetch` rejects an aborted request with an
 * `AbortError`, which the client has already flattened into an `ApiError` with
 * code NETWORK; there is no way to tell that apart from a genuine offline
 * failure by looking at the error, and no need to, because only the caller
 * knows whether it did the aborting.
 */
function isAbort(signal: AbortSignal): boolean {
  return signal.aborted;
}

export type PackageViewProps = {
  /** Skips the upload step. For deep links, and for tests. */
  initialPackageId?: Uuid;
  initialJobId?: Uuid;
  /** Passed straight through to {@link PageViewer}; `null` uses server-rendered PNGs. */
  pdfRenderer?: PdfPageRenderer | null;
};

export default function PackageView({
  initialPackageId,
  initialJobId,
  pdfRenderer,
}: PackageViewProps) {
  const [packageId, setPackageId] = useState<Uuid | null>(initialPackageId ?? null);
  const [jobId, setJobId] = useState<Uuid | null>(initialJobId ?? null);
  const [job, setJob] = useState<JobResponse | null>(null);
  const [pages, setPages] = useState<readonly PageView[]>([]);
  const [documents, setDocuments] = useState<PackageDocumentsView | null>(null);
  /** What this package cost to parse. Null until it loads, and null if it fails. */
  const [usage, setUsage] = useState<PackageUsageView | null>(null);
  /** Null until the reviewer picks one; the first document is used until then. */
  const [chosenDocumentId, setChosenDocumentId] = useState<Uuid | null>(null);
  const [loadedFields, setLoadedFields] = useState<DocumentFieldsView | null>(null);
  /**
   * The selected OCCURRENCE, by its row id — not a field name.
   *
   * A field name stopped identifying anything the moment a document could
   * return one entry per occurrence: `.find(f => f.fieldName === name)` over a
   * Schedule E answers with whichever occurrence sorts first, which for a
   * grouped field is a null-keyed one if it exists. The overlay would then draw
   * property A's boxes for a click on property B — silently, because every box
   * still lands on the page.
   */
  const [selectedFieldId, setSelectedFieldId] = useState<Uuid | null>(null);
  const [currentPageIndex, setCurrentPageIndex] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);
  const [uploading, setUploading] = useState(false);
  /**
   * Bumped to restart the poller after a resume.
   *
   * Needed because the job id has not changed — re-setting it to the same value
   * is a no-op React correctly skips, so without a distinct dependency the
   * poller would stay parked on the terminal state it stopped at and the resumed
   * job would never appear to progress.
   */
  const [pollGeneration, setPollGeneration] = useState(0);
  // True only while a poll timer is actually armed. Derived from the snapshot before, which
  // made the "refreshing…" indicator lie whenever the chain had stopped.
  const [pollActive, setPollActive] = useState(false);
  /**
   * Bumped to force a documents refetch after a regroup.
   *
   * A regroup returns the updated view, but re-extraction of the affected
   * documents then runs asynchronously through the stage machine, so the fields
   * change again shortly after. Re-reading — and restarting the poll below — is
   * how the reviewer sees that settle without any new job-watching of its own.
   */
  const [refreshGeneration, setRefreshGeneration] = useState(0);

  // --- job polling -------------------------------------------------------
  useEffect(() => {
    if (!jobId) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    let failures = 0;

    const tick = async () => {
      setPollActive(true);
      try {
        const next = await getJob(jobId, controller.signal);
        if (controller.signal.aborted) return;
        setJob(next);
        // A success means the banner is stale — a transient blip must not haunt the screen.
        failures = 0;
        setError(null);
        setPollActive(!isTerminal(next));
        // Re-arming only after a response lands means a slow API cannot pile up
        // overlapping requests, which a bare setInterval would happily do.
        if (!isTerminal(next)) timer = setTimeout(() => void tick(), POLL_INTERVAL_MS);
      } catch (caught) {
        if (isAbort(controller.signal)) return;
        if (caught instanceof ApiError) setError(caught);
        // Re-arm on ANY failure, not just ApiError. Before this, one dropped fetch ended the
        // chain for the session: the job finished server-side, the UI never noticed, and it
        // kept claiming "refreshing…" over a poller that had stopped. A reviewer's wifi
        // blinking for two seconds froze the whole review. Phase 6 review finding.
        failures += 1;
        if (failures <= MAX_POLL_FAILURES) {
          timer = setTimeout(() => void tick(), POLL_INTERVAL_MS * Math.min(failures, 8));
        } else {
          // Give up honestly rather than spin forever behind a lying indicator.
          setPollActive(false);
        }
      }
    };

    void tick();
    return () => {
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [jobId, pollGeneration]);

  // --- pages and documents ----------------------------------------------
  // Re-read on every job status change: pages appear after RENDERING, documents
  // after SPLITTING, and a reviewer should see each as soon as it exists rather
  // than at the end.
  const jobStatus = job?.status ?? null;
  useEffect(() => {
    if (!packageId) return;
    const controller = new AbortController();

    void (async () => {
      try {
        const [pageList, documentList] = await Promise.all([
          getPackagePages(packageId, controller.signal),
          getPackageDocuments(packageId, controller.signal),
        ]);
        if (controller.signal.aborted) return;
        setPages(pageList.pages);
        setDocuments(documentList);
      } catch (caught) {
        if (isAbort(controller.signal)) return;
        if (caught instanceof ApiError) setError(caught);
      }
    })();

    return () => {
      controller.abort();
    };
  }, [packageId, jobStatus, refreshGeneration]);

  // --- what it cost -----------------------------------------------------
  // Its OWN effect, not folded into the Promise.all above, and it swallows its
  // failure instead of raising the banner. A cost summary is context, not the
  // work: if the usage read fails, the reviewer should still get the document.
  // Sharing the effect above would have let a telemetry hiccup blank the pane.
  useEffect(() => {
    if (!packageId) return;
    const controller = new AbortController();

    void (async () => {
      try {
        const next = await getPackageUsage(packageId, controller.signal);
        if (controller.signal.aborted) return;
        setUsage(next);
      } catch {
        // Deliberately silent: the bar simply does not render.
        if (!controller.signal.aborted) setUsage(null);
      }
    })();

    return () => {
      controller.abort();
    };
  }, [packageId, jobStatus, refreshGeneration]);

  // The first document is shown until the reviewer picks another, so they land
  // on data rather than on an empty pane they have to click to fill. Derived
  // rather than synchronised into state by an effect: a "default" that is
  // really a copy goes stale the moment the list it was copied from reloads.
  const selectedDocumentId = chosenDocumentId ?? documents?.documents[0]?.id ?? null;

  // --- fields for the selected document ---------------------------------
  // Re-read not only when the selection changes but also when a regroup lands
  // (`refreshGeneration`) and as the re-extraction it triggers moves the job
  // through its stages (`jobStatus`). Regrouping the OPEN document re-extracts
  // it in place — its id never changes — so without those dependencies the pane
  // would keep showing the stale, pre-re-extraction field values.
  useEffect(() => {
    if (!selectedDocumentId) return;
    const controller = new AbortController();

    void (async () => {
      try {
        const next = await getDocumentFields(selectedDocumentId, controller.signal);
        if (controller.signal.aborted) return;
        setLoadedFields(next);
      } catch (caught) {
        if (isAbort(controller.signal)) return;
        if (caught instanceof ApiError) setError(caught);
      }
    })();

    return () => {
      controller.abort();
    };
  }, [selectedDocumentId, refreshGeneration, jobStatus]);

  /**
   * The fields on screen — but only if they belong to the document on screen.
   *
   * The guard is not paranoia: between selecting a document and its fields
   * arriving, the previous document's fields are still in state, and rendering
   * them under the new document's heading would attribute one document's data
   * to another. Better a blank moment than a confident wrong answer.
   */
  const fields = loadedFields?.documentId === selectedDocumentId ? loadedFields : null;

  const selectedField = useMemo(
    () => fields?.fields.find((field) => field.id === selectedFieldId) ?? null,
    [fields, selectedFieldId],
  );

  /**
   * Selecting an occurrence navigates to the page its evidence is on.
   *
   * VALUE first, then anything: the value is what the reviewer clicked to see.
   * An occurrence with no evidence — a missing one — leaves the page where it
   * is and contributes no boxes, which is the correct answer to "show me where
   * this came from" when it came from nowhere.
   */
  const handleSelectField = useCallback(
    (fieldId: Uuid) => {
      setSelectedFieldId(fieldId);
      const field = fields?.fields.find((candidate) => candidate.id === fieldId);
      const anchor =
        field?.evidence.find((item) => item.role === 'VALUE') ?? field?.evidence[0] ?? null;
      if (anchor) setCurrentPageIndex(anchor.packagePageIndex);
    },
    [fields],
  );

  const handleSelectDocument = useCallback((documentId: Uuid) => {
    setChosenDocumentId(documentId);
    // The old selection is an occurrence of the old document. Keeping it would
    // leave boxes on screen belonging to data no longer displayed.
    setSelectedFieldId(null);
  }, []);

  /**
   * A regroup landed. Refetch the documents to show the new grouping, and
   * restart the poll so the re-extraction it triggered is seen to run and settle
   * — reusing the existing machinery rather than watching the job a second way.
   */
  const handleRegrouped = useCallback(() => {
    setSelectedFieldId(null);
    // Drop the on-screen fields: a regroup re-extracts the affected documents, so the values in
    // hand are about to be superseded. Clearing them empties the pane until the refetch below
    // (driven by the bumped refreshGeneration) repopulates it — an honest blank beats a confident
    // stale answer while re-extraction runs (design §8-9).
    setLoadedFields(null);
    setRefreshGeneration((generation) => generation + 1);
    setPollGeneration((generation) => generation + 1);
  }, []);

  // Labeling (gold set): the selected document's pages, document-relative, so the correct
  // dialog can name the page a value is printed on the way the harness's truth does.
  const selectedDocument =
    documents?.documents.find((document) => document.id === selectedDocumentId) ?? null;
  const documentPageIndexes = useMemo(
    () => (selectedDocument?.pages ?? []).map((page) => page.packagePageIndex).sort((a, b) => a - b),
    [selectedDocument],
  );
  const currentDocumentPageIndex = Math.max(0, documentPageIndexes.indexOf(currentPageIndex));

  const handleDecide = useCallback(async (fieldId: Uuid, decision: FieldDecision) => {
    try {
      await correctField(
        fieldId,
        decision.action === 'CORRECT'
          ? { action: 'CORRECT', value: decision.value, pageIndex: decision.pageIndex }
          : { action: decision.action },
      );
      setRefreshGeneration((generation) => generation + 1);
    } catch (caught) {
      if (caught instanceof ApiError) setError(caught);
    }
  }, []);

  const handleMarkReviewed = useCallback(async () => {
    if (!selectedDocumentId) return;
    try {
      await markDocumentReviewed(selectedDocumentId);
      setRefreshGeneration((generation) => generation + 1);
    } catch (caught) {
      if (caught instanceof ApiError) setError(caught);
    }
  }, [selectedDocumentId]);

  const handleUpload = useCallback(async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setUploading(true);
    setError(null);
    try {
      const result = await uploadPackage([...files]);
      setPackageId(result.packageId);
      setJobId(result.jobId);
      setChosenDocumentId(null);
      setLoadedFields(null);
      setSelectedFieldId(null);
      setCurrentPageIndex(0);
    } catch (caught) {
      if (caught instanceof ApiError) setError(caught);
    } finally {
      setUploading(false);
    }
  }, []);

  const handleResume = useCallback(() => {
    if (!jobId) return;
    void (async () => {
      try {
        setError(null);
        setJob(await resumeJob(jobId));
        setPollGeneration((generation) => generation + 1);
      } catch (caught) {
        if (caught instanceof ApiError) setError(caught);
      }
    })();
  }, [jobId]);

  /** Short per-page badges for the thumbnail strip: the classified type wins. */
  const pageBadges = useMemo(() => {
    const badges: Record<string, string> = {};
    for (const document of documents?.documents ?? []) {
      for (const page of document.pages) {
        badges[page.pageId] = page.classification?.type ?? document.documentTypeCode;
      }
    }
    for (const page of documents?.unassignedPages ?? []) {
      badges[page.pageId] = page.reason.toLowerCase();
    }
    return badges;
  }, [documents]);

  const evidence = selectedField?.evidence ?? [];

  return (
    <div data-testid="package-view" className="flex h-full min-h-0 flex-col">
      <header className="flex shrink-0 flex-wrap items-center gap-3 border-b border-slate-200 bg-white px-4 py-3">
        <h1 className="text-base font-semibold tracking-tight text-slate-900">Review</h1>
        {packageId ? (
          <span data-testid="package-id" className="font-mono text-xs text-slate-500">
            {packageId}
          </span>
        ) : null}

        <label className="ml-auto flex items-center gap-2 text-sm">
          <span className="sr-only">Upload documents</span>
          <input
            type="file"
            multiple
            accept={UPLOAD_ACCEPT}
            data-testid="upload-input"
            disabled={uploading}
            onChange={(event) => { void handleUpload(event.target.files); }}
            className="text-xs file:mr-2 file:rounded-sm file:border file:border-slate-300 file:bg-white file:px-2 file:py-1 file:text-xs"
          />
        </label>
        {uploading ? (
          <span role="status" className="text-xs text-slate-500">
            Uploading…
          </span>
        ) : null}
      </header>

      {error ? (
        <p
          data-testid="api-error"
          data-code={error.code}
          role="alert"
          className="shrink-0 border-b border-red-200 bg-red-50 px-4 py-2 text-sm text-red-900"
        >
          <span className="font-mono font-semibold">{error.code}</span> — the request failed
          {error.status > 0 ? ` with HTTP ${String(error.status)}` : ' before reaching the server'}.
        </p>
      ) : null}

      {/* Directly under the header, above both panes: what this package cost to
          parse. It sits here rather than inside the right-hand pane because it
          describes the WHOLE package, and the right pane is scoped to one
          document. Renders nothing until the usage loads, and collapses to a
          single line after that. */}
      <UsageSummary usage={usage} />

      {!packageId ? (
        <div className="flex flex-1 items-center justify-center p-8 text-sm text-slate-500">
          Upload one or more documents to begin.
        </div>
      ) : (
        <main className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <section aria-label="Document" className="min-h-0 overflow-hidden border-r border-slate-200">
            <PageViewer
              pages={pages}
              currentPageIndex={currentPageIndex}
              onSelectPage={setCurrentPageIndex}
              evidence={evidence}
              selectedOccurrence={selectedField ? occurrenceLabel(selectedField) : undefined}
              pageBadges={pageBadges}
              pdfRenderer={pdfRenderer}
            />
          </section>

          {/* overflow-HIDDEN, not auto: FieldPanel's list owns the scrolling. When this section
              scrolled too, the nested containers meant `flex-1` never constrained the list — the
              status trail simply grew and pushed the fields, the point of this pane, below the
              fold where they could not even be clicked. Caught by the browser e2e; no unit test
              could see it. */}
          <section
            aria-label="Extracted data"
            className="flex min-h-0 flex-col overflow-hidden bg-white">
            <div className="max-h-[45%] shrink-0 space-y-3 overflow-y-auto border-b border-slate-200 p-3">
              <JobStatus job={job} polling={pollActive} onResume={handleResume} />
              <DocumentList
                documents={documents?.documents ?? []}
                unassignedPages={documents?.unassignedPages ?? []}
                selectedDocumentId={selectedDocumentId}
                onSelectDocument={handleSelectDocument}
                onSelectPage={setCurrentPageIndex}
                packageId={packageId ?? undefined}
                onRegrouped={handleRegrouped}
                onError={setError}
              />
            </div>
            <div className="min-h-0 flex-1">
              {/* key on the document: remounting drops FieldPanel's reveal set. The set is
                  keyed by occurrence row id now, which does not repeat across documents — but
                  the remount stays, because it is what guarantees the reveal is scoped to the
                  subject on screen rather than to whatever ids happen not to collide. A masking
                  control that leaks across subjects is not a control. Phase 6 review finding. */}
              <FieldPanel
                key={selectedDocumentId ?? 'no-document'}
                fields={fields}
                selectedFieldId={selectedFieldId}
                onSelectField={handleSelectField}
                onDecide={handleDecide}
                documentPageCount={documentPageIndexes.length}
                currentDocumentPageIndex={currentDocumentPageIndex}
                onMarkReviewed={handleMarkReviewed}
                documentReviewStatus={selectedDocument?.reviewStatus}
              />
            </div>
          </section>
        </main>
      )}
    </div>
  );
}
