import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import FieldPanel from './FieldPanel.tsx';
import { DOCUMENT_FIELDS, MISSING_FIELD, NET_PAY, SCHEDULE_E_FIELDS } from '../../test/fixtures.ts';
import type { DocumentFieldsView, FieldView } from '../../lib/api/types.ts';

/**
 * ⚠️ SYNTHETIC. No shipped PAYSTUB field is `sensitive: true` — this object
 * exists only to exercise the masking path, and the production schema must NOT
 * be changed to match it. The value is invented; it is not an account number
 * belonging to anyone.
 */
const SYNTHETIC_SENSITIVE_FIELD: FieldView = {
  ...NET_PAY,
  fieldName: 'accountNumber',
  dataType: 'STRING',
  displayedText: '000123456789',
  rawValue: '000123456789',
  normalized: { text: '000123456789', number: null, date: null },
  sensitive: true,
};

const SENSITIVE_DOCUMENT: DocumentFieldsView = {
  ...DOCUMENT_FIELDS,
  fields: [SYNTHETIC_SENSITIVE_FIELD],
};

function rowFor(fieldName: string): HTMLElement {
  const row = document.querySelector<HTMLElement>(`[data-testid="field-row"][data-field-name="${fieldName}"]`);
  if (!row) throw new Error(`no field row for ${fieldName}`);
  return row;
}

/** The one occurrence of a field name — for the flat paystub, which has no repeats. */
function idOf(fieldName: string): string {
  const field = DOCUMENT_FIELDS.fields.find((candidate) => candidate.fieldName === fieldName);
  if (!field) throw new Error(`no fixture field ${fieldName}`);
  return field.id;
}

describe('FieldPanel', () => {
  it('renders every field, including the one that was not found', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    expect(screen.getAllByTestId('field-row')).toHaveLength(DOCUMENT_FIELDS.fields.length);
  });

  it('gives a missing field a first-class row that says it was not found', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    const row = rowFor(MISSING_FIELD.fieldName);
    expect(row.dataset.missing).toBe('true');
    expect(within(row).getByTestId('field-missing')).toHaveTextContent(/not found/i);
    // Not hidden, not blank, and still carrying its status — a missing field is
    // a result, and MANUAL_REVIEW_REQUIRED is what the engine concluded.
    expect(within(row).getByTestId('validation-status')).toHaveAttribute(
      'data-status',
      'MANUAL_REVIEW_REQUIRED',
    );
    expect(within(row).getByTestId('evidence-summary')).toHaveTextContent(/no evidence/i);
  });

  it('reports the selection by occurrence id, not by field name', async () => {
    const onSelectField = vi.fn();
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={onSelectField} />);

    await userEvent.click(within(rowFor('netPay')).getByTestId('field-select'));

    expect(onSelectField).toHaveBeenCalledTimes(1);
    expect(onSelectField).toHaveBeenCalledWith(idOf('netPay'));
  });

  it('marks the selected field', () => {
    render(
      <FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={idOf('netPay')} onSelectField={vi.fn()} />,
    );

    expect(rowFor('netPay').dataset.selected).toBe('true');
    expect(rowFor('borrowerName').dataset.selected).toBe('false');
  });

  it('flags a low-confidence field and leaves a confident one unflagged', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    // employerName is 0.62 in the fixture; borrowerName is 0.94.
    expect(within(rowFor('employerName')).getByTestId('confidence')).toHaveAttribute(
      'data-low',
      'true',
    );
    expect(within(rowFor('borrowerName')).getByTestId('confidence')).toHaveAttribute(
      'data-low',
      'false',
    );
  });

  it('summarises which page each piece of evidence is on', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    expect(within(rowFor('payFrequency')).getByTestId('evidence-summary')).toHaveTextContent(
      /page 2/,
    );
  });

  it('carries each row’s occurrence coordinate, which for a flat document is its bare name', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    expect(rowFor('netPay').dataset.occurrence).toBe('netPay');
  });
});

describe('FieldPanel masking', () => {
  it('never renders a sensitive value anywhere in the DOM before a reveal', () => {
    const { container } = render(
      <FieldPanel fields={SENSITIVE_DOCUMENT} selectedFieldId={null} onSelectField={vi.fn()} />,
    );

    // innerHTML, not textContent: a raw value hiding in a title=, an
    // aria-label= or a data- attribute is still a raw value on the page, and
    // is exactly the leak a text-only assertion would miss.
    expect(container.innerHTML).not.toContain('000123456789');
    expect(screen.getByTestId('field-value')).toHaveTextContent('•••• 6789');
  });

  it('masks the normalised value too, not only the displayed text', () => {
    render(<FieldPanel fields={SENSITIVE_DOCUMENT} selectedFieldId={null} onSelectField={vi.fn()} />);

    expect(screen.getByTestId('field-normalised')).toHaveTextContent('•••• 6789');
  });

  it('reveals only on a deliberate click, and only that field', async () => {
    const { container } = render(
      <FieldPanel fields={SENSITIVE_DOCUMENT} selectedFieldId={null} onSelectField={vi.fn()} />,
    );

    await userEvent.click(screen.getByTestId('reveal-toggle'));

    expect(container.innerHTML).toContain('000123456789');
    expect(screen.getByTestId('field-value')).toHaveTextContent('000123456789');
  });

  it('masks again when the reveal is toggled off', async () => {
    const { container } = render(
      <FieldPanel fields={SENSITIVE_DOCUMENT} selectedFieldId={null} onSelectField={vi.fn()} />,
    );

    await userEvent.click(screen.getByTestId('reveal-toggle'));
    await userEvent.click(screen.getByTestId('reveal-toggle'));

    expect(container.innerHTML).not.toContain('000123456789');
  });

  it('offers no reveal control for a field that is not sensitive', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    expect(screen.queryByTestId('reveal-toggle')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Grouped rendering — Spec 5b
// ---------------------------------------------------------------------------

function scheduleE(selectedFieldId: string | null = null, onSelectField = vi.fn()) {
  return render(
    <FieldPanel
      fields={SCHEDULE_E_FIELDS}
      selectedFieldId={selectedFieldId}
      onSelectField={onSelectField}
    />,
  );
}

function occurrenceIdOf(fieldName: string, groupKey: string | null): string {
  const field = SCHEDULE_E_FIELDS.fields.find(
    (candidate) => candidate.fieldName === fieldName && candidate.groupKey === groupKey,
  );
  if (!field) throw new Error(`no fixture occurrence ${fieldName}#${groupKey ?? '∅'}`);
  return field.id;
}

function cell(occurrence: string): HTMLElement {
  const found = document.querySelector<HTMLElement>(
    `[data-testid="occurrence-cell"][data-occurrence="${occurrence}"]`,
  );
  if (!found) throw new Error(`no occurrence cell for ${occurrence}`);
  return found;
}

describe('FieldPanel — group clusters', () => {
  it('renders the Schedule E as five cluster tables, not 67 flat rows', () => {
    scheduleE();

    expect(screen.getAllByTestId('group-cluster')).toHaveLength(5);
    // Only the eight fields the schema leaves ungrouped keep a flat row.
    expect(screen.getAllByTestId('field-row')).toHaveLength(8);
  });

  it('names each cluster and shows its found/missing census', () => {
    scheduleE();

    const clusters = screen.getAllByTestId('group-cluster');
    expect(clusters.map((cluster) => cluster.dataset.cluster)).toEqual([
      'Group A–C (3)',
      'Estate Or Trust A–B (2)',
      'Partnership A–D (4)',
      'Property Address 01–03 (3)',
      'Remic 01 (1)',
    ]);
    expect(within(clusters[2]).getByTestId('cluster-census')).toHaveTextContent('12 found');
    expect(within(clusters[2]).getByTestId('cluster-census')).toHaveTextContent('16 missing');
  });

  it('renders a COLUMN cluster with the occurrences as COLUMNS, as the form prints it', () => {
    scheduleE();

    const cluster = screen.getAllByTestId('group-cluster')[0];
    expect(cluster.dataset.groupKind).toBe('COLUMN');
    expect(cluster.dataset.orientation).toBe('columns');

    // The column headings are the printed keys…
    const headings = within(cluster).getAllByRole('columnheader');
    expect(headings.map((heading) => heading.textContent)).toEqual(['Field', 'A', 'B', 'C']);

    // …and one field's three occurrences sit side by side in its row.
    const row = cell('rentsReceived#A').closest('tr');
    expect(row).not.toBeNull();
    expect(
      within(row as HTMLElement)
        .getAllByTestId('occurrence-cell')
        .map((element) => element.dataset.groupKey),
    ).toEqual(['A', 'B', 'C']);
  });

  it('renders a ROW cluster with the occurrences as ROWS', () => {
    scheduleE();

    const cluster = screen.getAllByTestId('group-cluster')[2];
    expect(cluster.dataset.groupKind).toBe('ROW');
    expect(cluster.dataset.orientation).toBe('rows');

    // The column headings are the member fields — named by the part of the
    // name the cluster heading has not already said, with the wire name still
    // addressable.
    const headings = within(cluster).getAllByRole('columnheader');
    expect(headings[0].textContent).toBe('Key');
    expect(headings.slice(1).map((heading) => heading.dataset.fieldName)).toEqual([
      'partnershipEin',
      'partnershipName',
      'partnershipNonpassiveIncome',
      'partnershipNonpassiveLossAllowed',
      'partnershipPassiveIncome',
      'partnershipPassiveLossAllowed',
      'partnershipSection179Expense',
    ]);
    expect(headings[1].textContent).toBe('Ein');

    // …and one key's seven occurrences sit side by side in its row.
    const row = cell('partnershipEin#B').closest('tr');
    expect(row).not.toBeNull();
    const cells = within(row as HTMLElement).getAllByTestId('occurrence-cell');
    expect(cells).toHaveLength(7);
    expect(new Set(cells.map((element) => element.dataset.groupKey))).toEqual(new Set(['B']));
  });

  it('gives every occurrence its own React key, so nothing collapses onto a name', () => {
    scheduleE();

    const occurrences = screen
      .getAllByTestId('occurrence-cell')
      .map((element) => element.dataset.occurrence);

    // 58 keyed occurrences: 15 + 10 + 28 + 3 + 2.
    expect(occurrences).toHaveLength(58);
    expect(new Set(occurrences).size).toBe(58);
  });

  it('keeps a missing occurrence visible, labelled and selectable', async () => {
    const onSelectField = vi.fn();
    scheduleE(null, onSelectField);

    const missing = cell('rentsReceived#C');
    expect(missing.dataset.missing).toBe('true');
    expect(missing).toHaveTextContent(/missing/i);

    await userEvent.click(within(missing).getByTestId('occurrence-select'));

    expect(onSelectField).toHaveBeenCalledWith(occurrenceIdOf('rentsReceived', 'C'));
  });

  it('reports a selected occurrence by its row id and marks only that cell', async () => {
    const onSelectField = vi.fn();
    scheduleE(null, onSelectField);

    await userEvent.click(within(cell('incomeOrLoss#B')).getByTestId('occurrence-select'));

    expect(onSelectField).toHaveBeenCalledWith(occurrenceIdOf('incomeOrLoss', 'B'));
  });

  it('marks exactly the selected cell, and no sibling of the same field name', () => {
    scheduleE(occurrenceIdOf('rentsReceived', 'B'));

    expect(cell('rentsReceived#B').dataset.selected).toBe('true');
    expect(cell('rentsReceived#A').dataset.selected).toBe('false');
    expect(cell('rentsReceived#C').dataset.selected).toBe('false');
  });
});

describe('FieldPanel — the selected occurrence’s detail', () => {
  it('shows the three confidence components as text, not as a tooltip', () => {
    scheduleE(occurrenceIdOf('rentsReceived', 'B'));

    const detail = screen.getByTestId('occurrence-detail');
    expect(detail.dataset.occurrence).toBe('rentsReceived#B');
    // 0.81 = 1 · 0.9 · 0.9, and the product is shown at the scale it is stored.
    expect(within(detail).getByTestId('confidence-components')).toHaveTextContent('1 · 0.9 · 0.9');
    expect(detail).toHaveTextContent('0.8100');
  });

  it('names ROW_CELL as a method, the one the server has emitted since Spec 5a', () => {
    scheduleE(occurrenceIdOf('partnershipName', 'A'));

    const detail = screen.getByTestId('occurrence-detail');
    expect(within(detail).getByTestId('extraction-method')).toHaveTextContent('ROW_CELL');
  });

  it('says a missing occurrence has no evidence rather than showing an empty value', () => {
    scheduleE(occurrenceIdOf('rentsReceived', 'C'));

    const detail = screen.getByTestId('occurrence-detail');
    expect(detail).toHaveTextContent(/not found/i);
    expect(within(detail).getByTestId('extraction-method')).toHaveTextContent('NONE');
    expect(within(detail).getByTestId('evidence-summary')).toHaveTextContent(/no evidence/i);
  });

  it('shows no detail strip until an occurrence is selected', () => {
    scheduleE();

    expect(screen.queryByTestId('occurrence-detail')).not.toBeInTheDocument();
  });
});

describe('FieldPanel — the region that was never read', () => {
  it('renders it as a banner that says the table was not read', () => {
    scheduleE();

    const banners = screen.getAllByTestId('region-not-read');
    expect(banners).toHaveLength(1);
    expect(banners[0].dataset.fieldName).toBe('remicExcessInclusion');
    expect(banners[0].dataset.groupKind).toBe('ROW');
    expect(banners[0]).toHaveTextContent(/region/i);
    expect(banners[0]).toHaveTextContent(/manual review/i);
  });

  it('never renders it as a row of the ungrouped list', () => {
    scheduleE();

    expect(
      document.querySelector('[data-testid="field-row"][data-field-name="remicExcessInclusion"]'),
    ).toBeNull();
  });

  it('never renders it as a cell of any cluster table', () => {
    scheduleE();

    expect(
      document.querySelector('[data-testid="occurrence-cell"][data-field-name="remicExcessInclusion"]'),
    ).toBeNull();
    for (const cluster of screen.getAllByTestId('group-cluster')) {
      expect(cluster).not.toHaveTextContent('remicExcessInclusion');
    }
  });
});

describe('FieldPanel — masking across occurrences', () => {
  it('reveals only the occurrence whose control was clicked', async () => {
    // taxpayerSsn is the Schedule E's one sensitive field, and it arrives
    // already masked by the server: the reveal can only ever show the
    // server-masked string, never the digits.
    scheduleE();

    const toggles = screen.getAllByTestId('reveal-toggle');
    expect(toggles).toHaveLength(1);
    expect(toggles[0].dataset.fieldId).toBe(occurrenceIdOf('taxpayerSsn', null));

    await userEvent.click(toggles[0]);

    expect(within(rowFor('taxpayerSsn')).getByTestId('field-value')).toHaveTextContent(
      '•••-••-4321',
    );
  });

  it('never puts a raw social security number on the page, revealed or not', async () => {
    const { container } = scheduleE();

    await userEvent.click(screen.getByTestId('reveal-toggle'));

    expect(container.innerHTML).not.toContain('987-65-4321');
  });
  it('confirm all remaining confirms every untouched field that has a value and skips missing ones', async () => {
    const onDecide = vi.fn().mockResolvedValue(undefined);
    render(
      <FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()}
        onDecide={onDecide} documentPageCount={2} currentDocumentPageIndex={0}
        onMarkReviewed={vi.fn()} documentReviewStatus="NOT_REVIEWED" />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Confirm all remaining' }));

    const confirmable = DOCUMENT_FIELDS.fields.filter(
      (field) => field.reviewStatus === 'NOT_REVIEWED' && field.displayedText !== null,
    );
    expect(onDecide).toHaveBeenCalledTimes(confirmable.length);
    for (const field of confirmable) expect(onDecide).toHaveBeenCalledWith(field.id, { action: 'CONFIRM' });
    expect(onDecide).not.toHaveBeenCalledWith(MISSING_FIELD.id, expect.anything());
  });

  it('mark reviewed calls back and is disabled once the document is reviewed', async () => {
    const onMarkReviewed = vi.fn().mockResolvedValue(undefined);
    const { rerender } = render(
      <FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()}
        onDecide={vi.fn()} documentPageCount={2} currentDocumentPageIndex={0}
        onMarkReviewed={onMarkReviewed} documentReviewStatus="NOT_REVIEWED" />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Mark reviewed' }));
    expect(onMarkReviewed).toHaveBeenCalledTimes(1);

    rerender(
      <FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()}
        onDecide={vi.fn()} documentPageCount={2} currentDocumentPageIndex={0}
        onMarkReviewed={onMarkReviewed} documentReviewStatus="REVIEWED" />,
    );
    expect(screen.getByRole('button', { name: 'Mark reviewed' })).toBeDisabled();
  });

  it('renders no decision controls when no onDecide is supplied', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);
    expect(screen.queryByTestId('field-decision')).not.toBeInTheDocument();
  });
});
