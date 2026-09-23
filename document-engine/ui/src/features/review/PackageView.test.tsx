import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PackageView from './PackageView.tsx';
import { pdfBoxToViewport, viewportSize, type PageGeometry } from './coordinates.ts';
import type { JobResponse } from '../../lib/api/types.ts';
import {
  BORROWER_NAME,
  COMPLETED_JOB,
  DOCUMENT_FIELDS,
  DOCUMENT_ID,
  FAILED_JOB,
  JOB_ID,
  MISSING_FIELD,
  PACKAGE_DOCUMENTS,
  PACKAGE_ID,
  PACKAGE_USAGE,
  PAGE_1_ID,
  PAGES,
  PAY_FREQUENCY,
  SCHEDULE_E_DOCUMENTS,
  SCHEDULE_E_FIELDS,
} from '../../test/fixtures.ts';

/*
 * The whole point of Phase 6, exercised end to end short of a browser:
 * "clicking a field shows exactly where it came from."
 *
 * The API is mocked at the client boundary — the seam every component already
 * goes through — with fixture-shaped responses. `coordinates.ts` is NOT mocked;
 * where the overlay must land, the assertions ask it.
 *
 * pdf.js is switched off (`pdfRenderer={null}`) because there is no canvas and
 * no Worker under happy-dom. That is a real, stated limit: the pixels of the
 * page are not verified here, only the geometry of the boxes over them.
 */

vi.mock('../../lib/api/client.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api/client.ts')>();
  return {
    ...actual,
    getJob: vi.fn(),
    getPackagePages: vi.fn(),
    getPackageDocuments: vi.fn(),
    getPackageUsage: vi.fn(),
    getDocumentFields: vi.fn(),
    uploadPackage: vi.fn(),
    resumeJob: vi.fn(),
    regroup: vi.fn(),
    correctField: vi.fn(),
    markDocumentReviewed: vi.fn(),
  };
});

const client = await import('../../lib/api/client.ts');
const getJob = vi.mocked(client.getJob);
const getPackagePages = vi.mocked(client.getPackagePages);
const getPackageDocuments = vi.mocked(client.getPackageDocuments);
const getPackageUsage = vi.mocked(client.getPackageUsage);
const getDocumentFields = vi.mocked(client.getDocumentFields);
const uploadPackage = vi.mocked(client.uploadPackage);
const resumeJob = vi.mocked(client.resumeJob);
const regroup = vi.mocked(client.regroup);
const correctField = vi.mocked(client.correctField);

const LETTER: PageGeometry = { widthPt: 612, heightPt: 792, rotation: 0 };

const FINALIZING_JOB: JobResponse = {
  ...COMPLETED_JOB,
  status: 'FINALIZING',
  currentStage: 'FINALIZING',
  finishedAt: null,
};

/** netPay's VALUE word, from `fixtures/truth/paystub_twopage.json`. */
const NET_PAY_VALUE_BOX = { x: 124.7, y: 313.4, width: 53.4, height: 11.1 };

beforeEach(() => {
  getJob.mockResolvedValue(COMPLETED_JOB);
  getPackagePages.mockResolvedValue({ packageId: PACKAGE_ID, pages: PAGES });
  getPackageDocuments.mockResolvedValue(PACKAGE_DOCUMENTS);
  getPackageUsage.mockResolvedValue(PACKAGE_USAGE);
  getDocumentFields.mockResolvedValue(DOCUMENT_FIELDS);
  regroup.mockResolvedValue(PACKAGE_DOCUMENTS);
});

function renderWorkspace() {
  return render(
    <PackageView initialPackageId={PACKAGE_ID} initialJobId={JOB_ID} pdfRenderer={null} />,
  );
}

/**
 * Waits for the fields of the auto-selected document to arrive.
 *
 * Not `findByTestId('field-panel')`: the panel renders its "select a document"
 * state under the same test id, so waiting on it would let a test run against
 * an empty pane and pass for the wrong reason.
 */
function waitForFields() {
  return screen.findAllByTestId('field-row');
}

function fieldRow(fieldName: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(
    `[data-testid="field-row"][data-field-name="${fieldName}"]`,
  );
}

/** Clicks a field row by its wire name, which is what the overlay keys on too. */
async function selectField(fieldName: string) {
  const target = await waitFor(() => {
    const element = fieldRow(fieldName)?.querySelector<HTMLElement>('[data-testid="field-select"]');
    if (!element) throw new Error(`no selectable row for ${fieldName}`);
    return element;
  });
  await userEvent.click(target);
}

function boxes(): HTMLElement[] {
  return screen.queryAllByTestId('evidence-box');
}

function positionOf(element: HTMLElement) {
  return {
    left: Number.parseFloat(element.style.left),
    top: Number.parseFloat(element.style.top),
    width: Number.parseFloat(element.style.width),
    height: Number.parseFloat(element.style.height),
  };
}

describe('PackageView — selection drives the overlay', () => {
  it('draws no boxes until a field is selected', async () => {
    renderWorkspace();
    await waitForFields();

    expect(boxes()).toHaveLength(0);
  });

  it('draws exactly the selected field’s evidence', async () => {
    renderWorkspace();
    await waitForFields();

    await selectField('borrowerName');

    await waitFor(() => {
      expect(boxes()).toHaveLength(BORROWER_NAME.evidence.length);
    });
    expect(boxes().every((box) => box.dataset.occurrence === 'borrowerName')).toBe(true);
  });

  it('replaces them when another field is selected', async () => {
    renderWorkspace();
    await waitForFields();

    await selectField('borrowerName');
    await waitFor(() => { expect(boxes()).toHaveLength(4); });

    await selectField('netPay');
    await waitFor(() => { expect(boxes()).toHaveLength(3); });
    expect(boxes().every((box) => box.dataset.occurrence === 'netPay')).toBe(true);
  });

  it('distinguishes the value box from the label box that justified it', async () => {
    renderWorkspace();
    await waitForFields();

    await selectField('netPay');
    await waitFor(() => { expect(boxes()).toHaveLength(3); });

    const roles = boxes().map((box) => box.dataset.role);
    expect(roles).toContain('VALUE');
    expect(roles).toContain('LABEL');
  });

  it('navigates to page 2 when that is where the evidence is', async () => {
    // paystub_twopage's payFrequency: the per-page attribution case.
    renderWorkspace();
    await waitForFields();
    expect(screen.getByTestId('page-position')).toHaveTextContent('Page 1 of 2');

    await selectField('payFrequency');

    await waitFor(() => {
      expect(screen.getByTestId('page-position')).toHaveTextContent('Page 2 of 2');
    });
    expect(screen.getByTestId('page-surface')).toHaveAttribute('data-page-id', PAGES[1].pageId);
    expect(boxes()).toHaveLength(PAY_FREQUENCY.evidence.length);
  });

  it('draws nothing when the page showing is not the page the evidence is on', async () => {
    renderWorkspace();
    await waitForFields();

    await selectField('payFrequency');
    await waitFor(() => { expect(boxes()).toHaveLength(3); });

    // Walk back to page 1 by hand: the field stays selected, the boxes do not
    // follow, because they belong to page 2.
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
    await waitFor(() => { expect(boxes()).toHaveLength(0); });
  });

  it('renders a missing field as a result, and gives it no boxes', async () => {
    renderWorkspace();
    await waitForFields();

    const row = fieldRow(MISSING_FIELD.fieldName);
    expect(row?.dataset.missing).toBe('true');
    expect(within(row as HTMLElement).getByTestId('field-missing')).toHaveTextContent(/not found/i);

    await selectField(MISSING_FIELD.fieldName);
    await waitFor(() => {
      expect(row?.dataset.selected).toBe('true');
    });
    expect(boxes()).toHaveLength(0);
  });
});

describe('PackageView — zoom and rotation keep the overlay aligned', () => {
  it('agrees with pdfBoxToViewport at the default zoom', async () => {
    renderWorkspace();
    await waitForFields();
    await selectField('netPay');
    await waitFor(() => { expect(boxes()).toHaveLength(3); });

    const expected = pdfBoxToViewport(NET_PAY_VALUE_BOX, LETTER, 1);
    const actual = positionOf(boxes()[0]);
    expect(actual.left).toBeCloseTo(expected.left, 1);
    expect(actual.top).toBeCloseTo(expected.top, 1);
  });

  it('agrees again after zooming in', async () => {
    renderWorkspace();
    await waitForFields();
    await selectField('netPay');
    await waitFor(() => { expect(boxes()).toHaveLength(3); });

    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    await waitFor(() => {
      expect(screen.getByTestId('zoom-level')).toHaveAttribute('data-scale', '1.25');
    });

    const expected = pdfBoxToViewport(NET_PAY_VALUE_BOX, LETTER, 1.25);
    const actual = positionOf(boxes()[0]);
    expect(actual.left).toBeCloseTo(expected.left, 1);
    expect(actual.top).toBeCloseTo(expected.top, 1);
    expect(actual.width).toBeCloseTo(expected.width, 1);
  });

  it('keeps the page surface and the boxes on the same geometry at every zoom', async () => {
    renderWorkspace();
    await waitForFields();

    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    await waitFor(() => {
      expect(screen.getByTestId('zoom-level')).toHaveAttribute('data-scale', '1.5');
    });

    const surface = screen.getByTestId('page-surface');
    const expected = viewportSize(LETTER, 1.5);
    expect(Number.parseFloat(surface.style.width)).toBeCloseTo(expected.width, 1);
    expect(Number.parseFloat(surface.style.height)).toBeCloseTo(expected.height, 1);
  });

  it('agrees after a quarter turn — the surface swaps axes and so do the boxes', async () => {
    renderWorkspace();
    await waitForFields();
    await selectField('netPay');
    await waitFor(() => { expect(boxes()).toHaveLength(3); });

    await userEvent.click(screen.getByTestId('rotate-button'));
    await waitFor(() => {
      expect(screen.getByTestId('rotate-button')).toHaveAttribute('data-extra-rotation', '90');
    });

    const surface = screen.getByTestId('page-surface');
    const expectedSurface = viewportSize(LETTER, 1, 90);
    expect(Number.parseFloat(surface.style.width)).toBeCloseTo(expectedSurface.width, 1);
    expect(Number.parseFloat(surface.style.height)).toBeCloseTo(expectedSurface.height, 1);

    const expected = pdfBoxToViewport(NET_PAY_VALUE_BOX, LETTER, 1, 90);
    const actual = positionOf(boxes()[0]);
    expect(actual.left).toBeCloseTo(expected.left, 1);
    expect(actual.top).toBeCloseTo(expected.top, 1);
    expect(actual.width).toBeCloseTo(expected.width, 1);
    expect(actual.height).toBeCloseTo(expected.height, 1);
  });
});

/**
 * The Schedule E half: the same claim — "clicking a value shows exactly where
 * it came from" — but over a document where the field name no longer
 * identifies anything. Every assertion here fails if selection resolves by
 * name, because `rentsReceived` names three different occurrences.
 */
describe('PackageView — occurrence selection over a repeating document', () => {
  beforeEach(() => {
    getPackageDocuments.mockResolvedValue(SCHEDULE_E_DOCUMENTS);
    getDocumentFields.mockResolvedValue(SCHEDULE_E_FIELDS);
  });

  function occurrenceCell(occurrence: string): HTMLElement | null {
    return document.querySelector<HTMLElement>(
      `[data-testid="occurrence-cell"][data-occurrence="${occurrence}"]`,
    );
  }

  async function selectOccurrence(occurrence: string) {
    const target = await waitFor(() => {
      const element = occurrenceCell(occurrence)?.querySelector<HTMLElement>(
        '[data-testid="occurrence-select"]',
      );
      if (!element) throw new Error(`no selectable cell for ${occurrence}`);
      return element;
    });
    await userEvent.click(target);
  }

  function waitForClusters() {
    return screen.findAllByTestId('group-cluster');
  }

  it('highlights occurrence B’s boxes, and none of A’s', async () => {
    renderWorkspace();
    await waitForClusters();

    await selectOccurrence('rentsReceived#B');

    await waitFor(() => { expect(boxes().length).toBeGreaterThan(0); });
    expect(boxes().every((box) => box.dataset.occurrence === 'rentsReceived#B')).toBe(true);

    // A's VALUE box is at x=386pt and B's at x=456pt on the same line: the two
    // are distinguishable by position, so "only B's" is a claim about geometry
    // and not merely about a label.
    const valueA = pdfBoxToViewport({ x: 386, y: 281.3, width: 24.5, height: 7.4 }, LETTER, 1);
    const valueB = pdfBoxToViewport({ x: 456, y: 281.3, width: 24.5, height: 7.4 }, LETTER, 1);
    const drawn = boxes()
      .filter((box) => box.dataset.role === 'VALUE')
      .map(positionOf);
    expect(drawn).toHaveLength(1);
    expect(drawn[0].left).toBeCloseTo(valueB.left, 1);
    expect(drawn[0].left).not.toBeCloseTo(valueA.left, 1);
  });

  it('swaps to A’s boxes when A is selected, leaving none of B’s', async () => {
    renderWorkspace();
    await waitForClusters();

    await selectOccurrence('rentsReceived#B');
    await waitFor(() => { expect(boxes().length).toBeGreaterThan(0); });

    await selectOccurrence('rentsReceived#A');
    await waitFor(() => {
      expect(boxes().every((box) => box.dataset.occurrence === 'rentsReceived#A')).toBe(true);
    });
    expect(boxes().some((box) => box.dataset.occurrence === 'rentsReceived#B')).toBe(false);
  });

  it('lights every span of a multi-span value, sign glyphs included', async () => {
    // `( 18,470 )` is THREE VALUE spans in the committed fixture — the two
    // parenthesis glyphs are their own words — plus one LABEL. A highlight that
    // covered only the digits would leave the reviewer looking at 18,470 while
    // the engine read -18,470.
    renderWorkspace();
    await waitForClusters();

    const occurrence = SCHEDULE_E_FIELDS.fields.find(
      (field) => field.fieldName === 'incomeOrLoss' && field.groupKey === 'B',
    );
    expect(occurrence?.displayedText).toBe('( 18,470 )');
    const valueSpans = occurrence?.evidence.filter((item) => item.role === 'VALUE') ?? [];
    expect(valueSpans).toHaveLength(3);

    await selectOccurrence('incomeOrLoss#B');

    // EVERY box of the occurrence, not a subset — asserted against the
    // occurrence's own evidence array so the claim survives the engine
    // recording more or fewer LABEL spans than it does today.
    await waitFor(() => {
      expect(boxes()).toHaveLength(occurrence?.evidence.length ?? 0);
    });
    expect(boxes().filter((box) => box.dataset.role === 'VALUE')).toHaveLength(3);
    expect(boxes().every((box) => box.dataset.occurrence === 'incomeOrLoss#B')).toBe(true);
  });

  it('selects a missing occurrence, draws no boxes, and does not move the page', async () => {
    renderWorkspace();
    await waitForClusters();

    await selectOccurrence('partnershipName#A');
    await waitFor(() => {
      expect(screen.getByTestId('page-position')).toHaveTextContent('Page 2 of 2');
    });

    await selectOccurrence('rentsReceived#C');

    await waitFor(() => {
      expect(occurrenceCell('rentsReceived#C')?.dataset.selected).toBe('true');
    });
    expect(boxes()).toHaveLength(0);
    // Still on page 2: "show me where this came from" has no answer, and the
    // honest response is to leave the reviewer where they were rather than
    // jump them somewhere arbitrary.
    expect(screen.getByTestId('page-position')).toHaveTextContent('Page 2 of 2');
  });

  it('navigates to the page the selected occurrence’s evidence is on', async () => {
    renderWorkspace();
    await waitForClusters();
    expect(screen.getByTestId('page-position')).toHaveTextContent('Page 1 of 2');

    await selectOccurrence('estateOrTrustName#B');

    await waitFor(() => {
      expect(screen.getByTestId('page-position')).toHaveTextContent('Page 2 of 2');
    });
  });

  it('keeps the never-read region out of the field list and out of every table', async () => {
    renderWorkspace();
    await waitForClusters();

    // The T9 assertion, at the DOM: `remicExcessInclusion` arrives as
    // `groupKind: ROW, groupKey: null, method: NONE`. It is a statement about a
    // table that was never read, so it is a banner — never an ungrouped field
    // row, never a cell of the Remic table.
    const banner = await screen.findByTestId('region-not-read');
    expect(banner.dataset.fieldName).toBe('remicExcessInclusion');

    expect(
      document.querySelector('[data-testid="field-row"][data-field-name="remicExcessInclusion"]'),
    ).toBeNull();
    expect(
      document.querySelector(
        '[data-testid="occurrence-cell"][data-field-name="remicExcessInclusion"]',
      ),
    ).toBeNull();
  });

  it('shows the selected occurrence’s three confidence components as text', async () => {
    renderWorkspace();
    await waitForClusters();

    await selectOccurrence('incomeOrLoss#B');

    const detail = await screen.findByTestId('occurrence-detail');
    expect(detail.dataset.occurrence).toBe('incomeOrLoss#B');
    expect(within(detail).getByTestId('confidence-components')).toHaveTextContent('1 · 0.9 · 0.9');
  });
});

describe('PackageView — processing status', () => {
  it('shows the stage trail of a completed job', async () => {
    renderWorkspace();
    expect(await screen.findByTestId('job-status')).toHaveAttribute('data-job-status', 'COMPLETED');
  });

  it('surfaces a failed stage’s error code', async () => {
    getJob.mockResolvedValue(FAILED_JOB);
    renderWorkspace();

    const code = await screen.findByTestId('stage-error-code');
    expect(code).toHaveAttribute('data-error-code', 'WORKER_TIMEOUT');
  });

  it('renders FINALIZING as in progress and continues polling it', async () => {
    vi.useFakeTimers();
    try {
      getJob.mockResolvedValue(FINALIZING_JOB);
      renderWorkspace();

      await act(async () => {
        await Promise.resolve();
      });

      expect(screen.getByTestId('job-status')).toHaveAttribute(
        'data-job-status',
        'FINALIZING',
      );
      expect(screen.getByText('refreshing…')).toBeInTheDocument();
      const callsBeforeNextPoll = getJob.mock.calls.length;

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1501);
      });

      expect(getJob.mock.calls.length).toBeGreaterThan(callsBeforeNextPoll);
    } finally {
      vi.useRealTimers();
    }
  });

  it('restarts polling after a resume, so the retried job is seen to move', async () => {
    // The job id does not change on resume, so re-setting it would be a no-op
    // React skips — the poller has to be restarted some other way.
    getJob.mockResolvedValue(FAILED_JOB);
    resumeJob.mockResolvedValue({ ...FAILED_JOB, status: 'OCR_PROCESSING' });
    renderWorkspace();

    await screen.findByTestId('resume-job');
    getJob.mockResolvedValue(COMPLETED_JOB);
    await userEvent.click(screen.getByTestId('resume-job'));

    await waitFor(() => {
      expect(screen.getByTestId('job-status')).toHaveAttribute('data-job-status', 'COMPLETED');
    });
  });

  it('shows the error code and no stack when a request fails', async () => {
    getPackageDocuments.mockRejectedValue(
      new client.ApiError({ status: 404, code: 'NOT_FOUND' }),
    );
    renderWorkspace();

    const banner = await screen.findByTestId('api-error');
    expect(banner).toHaveAttribute('data-code', 'NOT_FOUND');
    expect(banner.textContent).not.toMatch(/at .*\.(ts|tsx|js):/);
  });
});

describe('PackageView — shell', () => {
  it('asks for an upload when there is no package yet', () => {
    render(<PackageView pdfRenderer={null} />);
    expect(screen.getByText(/upload one or more documents/i)).toBeInTheDocument();
    expect(screen.queryByTestId('page-viewer')).not.toBeInTheDocument();
  });

  it('opens the package the upload returns', async () => {
    uploadPackage.mockResolvedValue({
      packageId: PACKAGE_ID,
      jobId: JOB_ID,
      files: [],
      warnings: [],
    });
    render(<PackageView pdfRenderer={null} />);

    await userEvent.upload(
      screen.getByTestId('upload-input'),
      new File(['%PDF-1.7'], 'paystub.pdf', { type: 'application/pdf' }),
    );

    expect(await screen.findByTestId('package-id')).toHaveTextContent(PACKAGE_ID);
    expect(await screen.findByTestId('page-viewer')).toBeInTheDocument();
  });

  it('clears the field selection when the document changes', async () => {
    renderWorkspace();
    await waitForFields();
    await selectField('netPay');
    await waitFor(() => { expect(boxes()).toHaveLength(3); });

    await userEvent.click(screen.getByTestId('document-row'));

    // Boxes belonging to data no longer displayed would be a lie about what is
    // on screen, so the selection goes with the document.
    await waitFor(() => { expect(boxes()).toHaveLength(0); });
  });
});

describe('PackageView — Phase 6 review findings', () => {
  it('keeps polling after a transient API failure instead of freezing the review', async () => {
    // One dropped fetch used to end the poll chain for the session: the job finished
    // server-side, the UI never noticed, and it kept claiming "refreshing…" over a
    // poller that had stopped. A reviewer's wifi blinking froze the whole review.
    getJob.mockReset();
    getJob
      .mockRejectedValueOnce(new client.ApiError({ status: 0, code: 'NETWORK' }))
      .mockResolvedValue(COMPLETED_JOB);

    renderWorkspace();

    await waitFor(
      () => {
        expect(screen.getByTestId('job-status')).toHaveAttribute('data-job-status', 'COMPLETED');
      },
      { timeout: 8000 },
    );
    expect(getJob.mock.calls.length).toBeGreaterThan(1);
  });

  it('re-reads the open document’s fields after regrouping it', async () => {
    // Regrouping the OPEN document re-extracts it, so its fields change again once the
    // re-extraction settles. Before this fix the fields effect depended only on the selected
    // document id — unchanged by a regroup — so the right pane kept showing stale,
    // pre-re-extraction values. The refetch is the proof it no longer does.
    renderWorkspace();
    await waitForFields();

    const fieldReadsForOpenDocument = () =>
      getDocumentFields.mock.calls.filter((call) => call[0] === DOCUMENT_ID).length;
    const before = fieldReadsForOpenDocument();
    expect(before).toBeGreaterThanOrEqual(1);

    // Tick a page of the open document and split it off — a regroup that re-extracts it.
    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_1_ID}`));
    await userEvent.click(screen.getByTestId('regroup-split'));

    await waitFor(() => {
      expect(regroup).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(fieldReadsForOpenDocument()).toBeGreaterThan(before);
    });
  });

  it('puts what the package cost at the top, above both panes', async () => {
    renderWorkspace();

    const summary = await screen.findByTestId('usage-summary');
    // Above the document pane, not inside the right-hand one: the number describes
    // the whole package, and the right pane is scoped to a single document.
    const panes = screen.getByRole('main');
    expect(summary.compareDocumentPosition(panes)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(summary).toHaveAttribute('data-model-calls', '0');
    expect(summary).toHaveTextContent('$0.00');
  });

  it('keeps the document readable when the usage read fails', async () => {
    // A cost summary is context, not the work. Folding it into the pages/documents
    // Promise.all would have let a telemetry hiccup blank the whole pane.
    getPackageUsage.mockReset();
    getPackageUsage.mockRejectedValue(new client.ApiError({ status: 500, code: 'INTERNAL' }));

    renderWorkspace();

    await waitForFields();
    expect(screen.queryByTestId('usage-summary')).not.toBeInTheDocument();
    // And it must not raise the banner either — nothing the reviewer can act on.
    expect(screen.queryByTestId('api-error')).not.toBeInTheDocument();
  });

  it('stops polling once the job reaches HUMAN_REVIEW_REQUIRED', async () => {
    // The engine's NORMAL end state. Treating it as non-terminal polled forever, one
    // request per interval per open tab, for work that was already finished.
    getJob.mockReset();
    getJob.mockResolvedValue({ ...COMPLETED_JOB, status: 'HUMAN_REVIEW_REQUIRED' });

    renderWorkspace();

    await waitFor(() => {
      expect(screen.getByTestId('job-status')).toHaveAttribute(
        'data-job-status',
        'HUMAN_REVIEW_REQUIRED',
      );
    });
    const afterSettle = getJob.mock.calls.length;
    // MUST exceed POLL_INTERVAL_MS (1500ms): a shorter wait cannot observe the extra poll a
    // non-terminal state would fire, so the assertion would hold either way. Mutation-checked —
    // reverting HUMAN_REVIEW_REQUIRED out of isTerminal fails this test.
    await new Promise((resolve) => setTimeout(resolve, 2000));
    expect(getJob.mock.calls.length).toBe(afterSettle);
  });
  it('a confirm on a field row PATCHes the field and refreshes the fields', async () => {
    correctField.mockResolvedValue({
      fieldId: BORROWER_NAME.id,
      fieldName: BORROWER_NAME.fieldName,
      machineValue: BORROWER_NAME.displayedText,
      effectiveValue: BORROWER_NAME.displayedText,
      reviewStatus: 'CONFIRMED',
    });
    renderWorkspace();
    await screen.findAllByTestId('field-row');
    const row = document.querySelector<HTMLElement>(
      `[data-testid="field-row"][data-field-name="${BORROWER_NAME.fieldName}"]`,
    );
    if (!row) throw new Error('no row for the borrower name');
    // The mock is module-level, so count from here rather than from zero.
    const fetchesBefore = getDocumentFields.mock.calls.length;

    await userEvent.click(within(row).getByRole('button', { name: 'Confirm' }));

    await waitFor(() => {
      expect(correctField).toHaveBeenCalledWith(BORROWER_NAME.id, { action: 'CONFIRM' });
    });
    await waitFor(() => {
      expect(getDocumentFields.mock.calls.length).toBeGreaterThan(fetchesBefore);
    });
  });
});
