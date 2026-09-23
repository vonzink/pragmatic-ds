import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import UsageSummary from './UsageSummary.tsx';
import { PACKAGE_USAGE, SCANNED_PACKAGE_USAGE } from '../../test/fixtures.ts';

function stat(name: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(`[data-testid="usage-stat"][data-stat="${name}"]`);
  if (!element) throw new Error(`no usage stat named ${name}`);
  return element;
}

describe('UsageSummary', () => {
  it('renders nothing before the usage has loaded', () => {
    const { container } = render(<UsageSummary usage={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('leads with pages and the OCR share, because OCR is the only variable that moves the bill', () => {
    render(<UsageSummary usage={SCANNED_PACKAGE_USAGE} />);

    expect(stat('pages')).toHaveTextContent('26');
    const ocr = stat('ocr');
    expect(ocr).toHaveTextContent('26');
    // The share is what makes the number mean something: 26 of 26 is a different
    // document from 26 pages with 1 scan in it.
    expect(ocr).toHaveTextContent('100%');
  });

  it('states the money as an exact zero AND says why, so the zero is not read as "unknown"', () => {
    render(<UsageSummary usage={PACKAGE_USAGE} />);

    const cost = stat('cost');
    expect(cost).toHaveTextContent('$0.00');
    expect(cost).toHaveTextContent(/no model calls/i);
  });

  it('shows retries only when there were retries — a zero retry count is noise', () => {
    render(<UsageSummary usage={PACKAGE_USAGE} />);
    expect(document.querySelector('[data-stat="retries"]')).toBeNull();

    render(<UsageSummary usage={SCANNED_PACKAGE_USAGE} />);
    expect(stat('retries')).toHaveTextContent('2');
  });

  it('labels elapsed as PACKAGE scope, because there is no per-document timing to report', () => {
    // The read model says perDocumentElapsedAvailable: false. The bar must not quietly
    // present a package number as if it were this document's.
    render(<UsageSummary usage={PACKAGE_USAGE} />);

    expect(stat('elapsed')).toHaveTextContent(/package/i);
  });

  it('keeps the per-stage breakdown one click away rather than on screen by default', async () => {
    render(<UsageSummary usage={PACKAGE_USAGE} />);

    expect(screen.queryAllByTestId('usage-stage-row')).toHaveLength(0);

    await userEvent.click(screen.getByTestId('usage-detail-toggle'));

    expect(screen.getAllByTestId('usage-stage-row')).toHaveLength(PACKAGE_USAGE.elapsed.stages.length);
    const parsing = screen
      .getAllByTestId('usage-stage-row')
      .find((row) => row.dataset.stage === 'PARSING');
    expect(parsing).toBeDefined();
    expect(parsing).toHaveTextContent('1.2 s');
  });

  it('names the wasted compute in the detail, not just the retry count', async () => {
    render(<UsageSummary usage={SCANNED_PACKAGE_USAGE} />);
    await userEvent.click(screen.getByTestId('usage-detail-toggle'));

    const wasted = screen.getByTestId('usage-wasted');
    expect(wasted).toHaveTextContent('8.0 s');
    expect(wasted).toHaveTextContent('WORKER_UNAVAILABLE');
  });

  it('lists which spenders the $0.00 actually covers, and which it cannot see', async () => {
    render(<UsageSummary usage={PACKAGE_USAGE} />);
    await userEvent.click(screen.getByTestId('usage-detail-toggle'));

    const producers = screen.getByTestId('usage-producers');
    expect(within(producers).getByText(/ENGINE/)).toBeInTheDocument();
    // The honest half: rag-brain spends elsewhere, and a total that silently excluded it
    // would read as "nothing was spent anywhere".
    expect(producers).toHaveTextContent(/RAG_BRAIN/);
    expect(producers).toHaveTextContent(/not counted|not visible|not recorded/i);
  });

  it('shows the per-document OCR split, which IS derivable even though elapsed is not', async () => {
    render(<UsageSummary usage={SCANNED_PACKAGE_USAGE} />);
    await userEvent.click(screen.getByTestId('usage-detail-toggle'));

    const rows = screen.getAllByTestId('usage-document-row');
    expect(rows).toHaveLength(SCANNED_PACKAGE_USAGE.documents.length);
    expect(rows[0]).toHaveTextContent('TAX_RETURN');
    expect(rows[0]).toHaveTextContent('26');
  });
});
