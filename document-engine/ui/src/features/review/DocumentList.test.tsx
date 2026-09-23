import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import DocumentList, { type DocumentListProps } from './DocumentList.tsx';
import * as client from '../../lib/api/client.ts';
import {
  DOCUMENT_ID,
  PACKAGE_DOCUMENTS,
  PACKAGE_ID,
  PAGE_1_ID,
  PAGE_2_ID,
} from '../../test/fixtures.ts';
import type { UnassignedPageView } from '../../lib/api/types.ts';

const UNASSIGNED: UnassignedPageView[] = [
  { pageId: PAGE_1_ID, packagePageIndex: 8, reason: 'BLANK' },
  { pageId: PAGE_2_ID, packagePageIndex: 9, reason: 'DUPLICATE' },
];

/** The full prop surface, so a select-and-act test overrides only what it asserts on. */
const BASE_ACT_PROPS: DocumentListProps = {
  documents: PACKAGE_DOCUMENTS.documents,
  unassignedPages: [],
  selectedDocumentId: DOCUMENT_ID,
  onSelectDocument: () => {},
  onSelectPage: () => {},
  packageId: PACKAGE_ID,
  onRegrouped: () => {},
  onError: () => {},
};

function actProps(overrides: Partial<DocumentListProps> = {}): DocumentListProps {
  return { ...BASE_ACT_PROPS, ...overrides };
}

describe('DocumentList', () => {
  it('shows each document with its type and classification confidence', () => {
    render(
      <DocumentList
        documents={PACKAGE_DOCUMENTS.documents}
        unassignedPages={[]}
        selectedDocumentId={null}
        onSelectDocument={vi.fn()}
        onSelectPage={vi.fn()}
      />,
    );

    const row = screen.getByTestId('document-row');
    expect(row).toHaveTextContent('PAYSTUB');
    expect(within(row).getByTestId('confidence')).toHaveAttribute('data-confidence', '0.97');
    expect(row).toHaveTextContent('2 pages');
  });

  it('flags the pages the splitter absorbed without a type, and stays quiet at zero', () => {
    // Issue #60: a document with many absorbed untyped pages is probably two documents glued
    // together, and the count is how a reviewer sees that without opening every page.
    const [document] = PACKAGE_DOCUMENTS.documents;
    render(
      <DocumentList
        documents={[
          { ...document, absorbedUntypedPages: 30 },
          { ...document, id: 'doc-one', ordinal: 1, absorbedUntypedPages: 1 },
          { ...document, id: 'doc-clean', ordinal: 2, absorbedUntypedPages: 0 },
          { ...document, id: 'doc-legacy', ordinal: 3, absorbedUntypedPages: null },
        ]}
        unassignedPages={[]}
        selectedDocumentId={null}
        onSelectDocument={vi.fn()}
        onSelectPage={vi.fn()}
      />,
    );

    expect(screen.getByTestId(`absorbed-untyped-${document.id}`)).toHaveTextContent(
      '30 untyped pages absorbed',
    );
    expect(screen.getByTestId('absorbed-untyped-doc-one')).toHaveTextContent(
      '1 untyped page absorbed',
    );
    expect(screen.queryByTestId('absorbed-untyped-doc-clean')).toBeNull();
    expect(screen.queryByTestId('absorbed-untyped-doc-legacy')).toBeNull();
  });

  it('selects a document when clicked', async () => {
    const onSelectDocument = vi.fn();
    render(
      <DocumentList
        documents={PACKAGE_DOCUMENTS.documents}
        unassignedPages={[]}
        selectedDocumentId={null}
        onSelectDocument={onSelectDocument}
        onSelectPage={vi.fn()}
      />,
    );

    await userEvent.click(screen.getByTestId('document-row'));
    expect(onSelectDocument).toHaveBeenCalledWith(DOCUMENT_ID);
  });

  it('jumps the viewer to a page from its chip', async () => {
    const onSelectPage = vi.fn();
    render(
      <DocumentList
        documents={PACKAGE_DOCUMENTS.documents}
        unassignedPages={[]}
        selectedDocumentId={DOCUMENT_ID}
        onSelectDocument={vi.fn()}
        onSelectPage={onSelectPage}
      />,
    );

    await userEvent.click(screen.getAllByTestId('document-page-chip')[1]);
    expect(onSelectPage).toHaveBeenCalledWith(1);
  });

  it('accounts for every page that landed in no document, and why', () => {
    // A reviewer who cannot account for a page cannot trust the page count.
    render(
      <DocumentList
        documents={PACKAGE_DOCUMENTS.documents}
        unassignedPages={UNASSIGNED}
        selectedDocumentId={null}
        onSelectDocument={vi.fn()}
        onSelectPage={vi.fn()}
      />,
    );

    const rows = screen.getAllByTestId('unassigned-page');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveAttribute('data-reason', 'BLANK');
    expect(rows[0]).toHaveTextContent(/page 9/i);
    expect(rows[0]).toHaveTextContent(/blank/i);
    expect(rows[1]).toHaveTextContent(/duplicate/i);
  });

  it('reads the export endpoint’s 1-based pageNumber when that is what arrived', () => {
    // openapi.json collides the two UnassignedPageView shapes into one schema;
    // the UI copes with either rather than rendering a blank cell.
    render(
      <DocumentList
        documents={[]}
        unassignedPages={[{ pageId: PAGE_1_ID, pageNumber: 9, reason: 'BLANK' }]}
        selectedDocumentId={null}
        onSelectDocument={vi.fn()}
        onSelectPage={vi.fn()}
      />,
    );

    expect(screen.getByTestId('unassigned-page')).toHaveTextContent(/page 9/i);
  });

  it('says so plainly when splitting has produced nothing yet', () => {
    render(
      <DocumentList
        documents={[]}
        unassignedPages={[]}
        selectedDocumentId={null}
        onSelectDocument={vi.fn()}
        onSelectPage={vi.fn()}
      />,
    );

    expect(screen.getByTestId('no-documents')).toBeInTheDocument();
  });
});

describe('DocumentList — select and act', () => {
  it('gives every document page a checkbox and keeps the toolbar inert until one is selected', () => {
    render(<DocumentList {...actProps()} />);

    expect(screen.getByTestId(`page-checkbox-${PAGE_1_ID}`)).toBeInTheDocument();
    expect(screen.getByTestId(`page-checkbox-${PAGE_2_ID}`)).toBeInTheDocument();
    // Nothing is selected, so every action is disabled — a regroup of no pages
    // is not a thing the reviewer can express.
    expect(screen.getByTestId('regroup-unassign')).toBeDisabled();
    expect(screen.getByTestId('regroup-move')).toBeDisabled();
  });

  it('counts the selection as checkboxes are toggled', async () => {
    render(<DocumentList {...actProps()} />);

    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_1_ID}`));
    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_2_ID}`));
    expect(screen.getByTestId('regroup-selection-count')).toHaveTextContent('2');

    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_1_ID}`));
    expect(screen.getByTestId('regroup-selection-count')).toHaveTextContent('1');
  });

  it('selecting a page and choosing Unassign calls regroup with the right delta', async () => {
    const regroup = vi.spyOn(client, 'regroup').mockResolvedValue(PACKAGE_DOCUMENTS);
    const onRegrouped = vi.fn();
    render(<DocumentList {...actProps({ onRegrouped })} />);

    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_2_ID}`));
    await userEvent.click(screen.getByTestId('regroup-unassign'));

    expect(regroup).toHaveBeenCalledWith(
      PACKAGE_ID,
      expect.objectContaining({
        intent: 'UNASSIGN',
        moves: [{ pageId: PAGE_2_ID, toDocumentId: null }],
      }),
    );
    // A successful regroup asks the shell to refetch and watch the re-extraction.
    await waitFor(() => {
      expect(onRegrouped).toHaveBeenCalled();
    });
  });

  it('promotes the selection into a new document of the type chosen in the dialog', async () => {
    const regroup = vi.spyOn(client, 'regroup').mockResolvedValue(PACKAGE_DOCUMENTS);
    render(<DocumentList {...actProps()} />);

    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_1_ID}`));
    await userEvent.click(screen.getByTestId('regroup-new'));
    await userEvent.selectOptions(screen.getByTestId('move-dialog-type'), 'BANK_STATEMENT');
    await userEvent.click(screen.getByTestId('move-dialog-submit'));

    expect(regroup).toHaveBeenCalledWith(
      PACKAGE_ID,
      expect.objectContaining({
        intent: 'NEW_DOCUMENT',
        newDocuments: [{ tempId: 'n1', documentTypeCode: 'BANK_STATEMENT', pageIds: [PAGE_1_ID] }],
      }),
    );
  });

  it('offers every engine-classifiable type as a New-document target', async () => {
    render(<DocumentList {...actProps()} />);

    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_1_ID}`));
    await userEvent.click(screen.getByTestId('regroup-new'));

    const options = within(screen.getByTestId('move-dialog-type'))
      .getAllByRole('option')
      .map((option) => (option as HTMLOptionElement).value);
    // KNOWN_DOCUMENT_TYPES ∪ view types (PAYSTUB), localeCompare-sorted by knownTypes.
    expect(options).toEqual([
      'BANK_STATEMENT',
      'DRIVERS_LICENSE',
      'HOI_DECLARATION',
      'MORTGAGE_STATEMENT',
      'PAYSTUB',
      'PURCHASE_CONTRACT',
      'TAX_RETURN',
      'UNKNOWN',
      'W2',
    ]);
  });

  it('surfaces a regroup failure through onError rather than throwing', async () => {
    vi.spyOn(client, 'regroup').mockRejectedValue(
      new client.ApiError({ status: 409, code: 'CONFLICT' }),
    );
    const onError = vi.fn();
    render(<DocumentList {...actProps({ onError })} />);

    await userEvent.click(screen.getByTestId(`page-checkbox-${PAGE_2_ID}`));
    await userEvent.click(screen.getByTestId('regroup-unassign'));

    await waitFor(() => {
      expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'CONFLICT' }));
    });
  });
});

describe('DocumentList — verdict override in the tray', () => {
  const dupTray: UnassignedPageView[] = [
    { pageId: 'pDup', packagePageIndex: 10, reason: 'DUPLICATE' },
  ];
  const blankTray: UnassignedPageView[] = [
    { pageId: 'pBlank', packagePageIndex: 9, reason: 'BLANK' },
  ];

  it('a backend-CLEARED page is selectable immediately — the durable truth survives reloads', () => {
    // Audit C5: a cleared, still-unassigned page used to exist only in session-local state, so a
    // reload orphaned it — in no document AND not selectable. The backend now reports it with
    // reason CLEARED, and the tray must honour that without any local click.
    const clearedTray: UnassignedPageView[] = [
      { pageId: 'pCleared', packagePageIndex: 4, reason: 'CLEARED' },
    ];
    render(<DocumentList {...actProps({ documents: [], unassignedPages: clearedTray })} />);

    const row = screen.getByTestId('unassigned-page');
    expect(row).toHaveAttribute('data-reason', 'CLEARED');
    expect(row).toHaveAttribute('data-cleared', 'true');
    expect(screen.getByTestId('page-checkbox-pCleared')).toBeEnabled();
  });

  it('offers Not a duplicate on a duplicate page — and only that verdict', async () => {
    const verdict = vi.spyOn(client, 'overridePageVerdict').mockResolvedValue(undefined);
    render(<DocumentList {...actProps({ documents: [], unassignedPages: dupTray })} />);

    // The matching verdict only: a duplicate page is not blank.
    expect(screen.queryByTestId('verdict-not-blank-pDup')).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId('verdict-not-duplicate-pDup'));

    expect(verdict).toHaveBeenCalledWith('pDup', 'NOT_DUPLICATE', expect.anything());
  });

  it('offers Not blank on a blank page — and only that verdict', async () => {
    const verdict = vi.spyOn(client, 'overridePageVerdict').mockResolvedValue(undefined);
    render(<DocumentList {...actProps({ documents: [], unassignedPages: blankTray })} />);

    expect(screen.queryByTestId('verdict-not-duplicate-pBlank')).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId('verdict-not-blank-pBlank'));

    expect(verdict).toHaveBeenCalledWith('pBlank', 'NOT_BLANK', expect.anything());
  });

  it('makes the page selectable once its verdict is overridden', async () => {
    vi.spyOn(client, 'overridePageVerdict').mockResolvedValue(undefined);
    render(<DocumentList {...actProps({ documents: [], unassignedPages: dupTray })} />);

    // Not assignable while it still reads as a duplicate — the backend would 409.
    expect(screen.getByTestId('page-checkbox-pDup')).toBeDisabled();
    await userEvent.click(screen.getByTestId('verdict-not-duplicate-pDup'));

    await waitFor(() => {
      expect(screen.getByTestId('page-checkbox-pDup')).toBeEnabled();
    });
    // The override is done, so the button retires.
    expect(screen.queryByTestId('verdict-not-duplicate-pDup')).not.toBeInTheDocument();
  });

  it('surfaces a verdict failure through onError rather than throwing', async () => {
    vi.spyOn(client, 'overridePageVerdict').mockRejectedValue(
      new client.ApiError({ status: 404, code: 'NOT_FOUND' }),
    );
    const onError = vi.fn();
    render(<DocumentList {...actProps({ documents: [], unassignedPages: dupTray, onError })} />);

    await userEvent.click(screen.getByTestId('verdict-not-duplicate-pDup'));

    await waitFor(() => {
      expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'NOT_FOUND' }));
    });
  });
});
