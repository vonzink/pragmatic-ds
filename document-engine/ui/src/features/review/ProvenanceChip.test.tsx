import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import FieldPanel from './FieldPanel.tsx';
import PageViewer from './PageViewer.tsx';
import ProvenanceChip from './ProvenanceChip.tsx';
import { provenanceLabel } from './provenance.ts';
import { DOCUMENT_FIELDS, PAGES, SCHEDULE_E_FIELDS } from '../../test/fixtures.ts';
import type { PageView } from '../../lib/api/types.ts';

/**
 * PARSED vs OCR, on screen.
 *
 * The product owner's ask was for "a label that shows parsed vs OCR", and the
 * risk this file guards is subtler than whether a badge renders: it is that the
 * badge renders the SAME WORDS the server prints into `fields.md`. Two surfaces
 * describing one fact in two vocabularies is how a reviewer ends up asking which
 * one to believe, so `provenanceLabel` is pinned against the server's own
 * `TextProvenanceView.label()` strings, verbatim.
 */
describe('provenanceLabel', () => {
  // These four strings are the server's, character for character. If
  // `TextProvenanceView.label()` changes, this list changes with it — never one
  // without the other.
  it('spells the source, then the engine, exactly as the server does', () => {
    expect(provenanceLabel({ source: 'NATIVE', ocrEngine: null })).toBe('NATIVE');
    expect(provenanceLabel({ source: 'OCR', ocrEngine: 'RAPIDOCR' })).toBe('OCR RAPIDOCR');
    expect(provenanceLabel({ source: 'MIXED', ocrEngine: 'TESSERACT' })).toBe('MIXED TESSERACT');
    expect(provenanceLabel({ source: 'OCR', ocrEngine: 'RAPIDOCR+TESSERACT' })).toBe(
      'OCR RAPIDOCR+TESSERACT',
    );
    expect(provenanceLabel({ source: 'UNKNOWN', ocrEngine: null })).toBe('UNKNOWN');
  });

  it('falls back to UNKNOWN rather than rendering an empty badge', () => {
    expect(provenanceLabel(null)).toBe('UNKNOWN');
    expect(provenanceLabel(undefined)).toBe('UNKNOWN');
  });
});

describe('ProvenanceChip', () => {
  it('flags a recognised value and leaves a read one neutral', () => {
    render(
      <>
        <ProvenanceChip provenance={{ source: 'OCR', ocrEngine: 'RAPIDOCR' }} testId="ocr" />
        <ProvenanceChip provenance={{ source: 'MIXED', ocrEngine: 'RAPIDOCR' }} testId="mixed" />
        <ProvenanceChip provenance={{ source: 'NATIVE', ocrEngine: null }} testId="native" />
      </>,
    );

    expect(screen.getByTestId('ocr').dataset.recognised).toBe('true');
    expect(screen.getByTestId('mixed').dataset.recognised).toBe('true');
    expect(screen.getByTestId('native').dataset.recognised).toBe('false');
  });

  /**
   * Colour is redundant with the text, never a substitute for it: the label
   * already says OCR, so a reviewer who cannot distinguish amber from slate
   * loses nothing.
   */
  it('states the source in text, not only in colour', () => {
    render(<ProvenanceChip provenance={{ source: 'OCR', ocrEngine: 'TESSERACT' }} />);

    expect(screen.getByTestId('text-provenance')).toHaveTextContent('OCR TESSERACT');
    expect(screen.getByTestId('text-provenance')).toHaveAttribute(
      'title',
      expect.stringContaining('TESSERACT'),
    );
  });
});

describe('FieldPanel provenance', () => {
  function rowFor(fieldName: string): HTMLElement {
    const row = document.querySelector<HTMLElement>(
      `[data-testid="field-row"][data-field-name="${fieldName}"]`,
    );
    if (!row) throw new Error(`no field row for ${fieldName}`);
    return row;
  }

  it('badges every flat row, so "no badge" can never mean "native"', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    expect(screen.getAllByTestId('text-provenance')).toHaveLength(DOCUMENT_FIELDS.fields.length);
  });

  it('names the engine on an OCR value and says nothing extra on a native one', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    const ocr = within(rowFor('employerName')).getByTestId('text-provenance');
    expect(ocr).toHaveTextContent('OCR RAPIDOCR');
    expect(ocr.dataset.provenance).toBe('OCR');
    expect(ocr.dataset.ocrEngine).toBe('RAPIDOCR');

    const native = within(rowFor('borrowerName')).getByTestId('text-provenance');
    expect(native).toHaveTextContent('NATIVE');
    expect(native.dataset.ocrEngine).toBe('');
  });

  /**
   * The case a page-level verdict cannot express. `payFrequency` was captured
   * from both a text layer and a scan; the badge must say so rather than pick
   * the more comfortable half.
   */
  it('says MIXED when one value straddles both sources, and still names the engine', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    const chip = within(rowFor('payFrequency')).getByTestId('text-provenance');
    expect(chip).toHaveTextContent('MIXED RAPIDOCR');
    expect(chip.dataset.recognised).toBe('true');
  });

  it('says UNKNOWN on a missing field rather than implying it was read', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    expect(within(rowFor('ytdGrossPay')).getByTestId('text-provenance')).toHaveTextContent(
      'UNKNOWN',
    );
  });

  /**
   * Provenance is not a confidence component. The confidence badge and the
   * three-component strip must be untouched by it — a reviewer has to be able
   * to multiply the components back to the score.
   */
  it('never appears inside the confidence badge', () => {
    render(<FieldPanel fields={DOCUMENT_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    const row = rowFor('employerName');
    const confidence = within(row).getByTestId('confidence');
    expect(confidence).not.toHaveTextContent(/OCR/);
    expect(within(confidence).queryByTestId('text-provenance')).toBeNull();
  });
});

describe('GroupTable provenance', () => {
  it('badges each occurrence cell, because provenance is per value not per table', () => {
    render(<FieldPanel fields={SCHEDULE_E_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    const found = document.querySelectorAll('[data-testid="occurrence-cell"][data-missing="false"]');
    expect(found.length).toBeGreaterThan(0);
    for (const cell of found) {
      expect(cell.querySelector('[data-testid="text-provenance"]')).not.toBeNull();
      expect((cell as HTMLElement).dataset.provenance).toBe('NATIVE');
    }
  });

  /** A missing cell cites no span, so it shows no badge — as the Markdown cell does not. */
  it('leaves a missing cell unbadged and marks it UNKNOWN in the data attribute', () => {
    render(<FieldPanel fields={SCHEDULE_E_FIELDS} selectedFieldId={null} onSelectField={vi.fn()} />);

    const missing = document.querySelector<HTMLElement>(
      '[data-testid="occurrence-cell"][data-missing="true"][data-field-id]',
    );
    expect(missing).not.toBeNull();
    expect(missing?.dataset.provenance).toBe('UNKNOWN');
    expect(missing?.querySelector('[data-testid="text-provenance"]')).toBeNull();
  });

  it('carries the badge into the detail strip of the selected occurrence', async () => {
    const occurrence = SCHEDULE_E_FIELDS.fields.find(
      (field) => field.fieldName === 'rentsReceived' && field.groupKey === 'A',
    );
    expect(occurrence).toBeDefined();

    render(
      <FieldPanel
        fields={SCHEDULE_E_FIELDS}
        selectedFieldId={occurrence?.id ?? null}
        onSelectField={vi.fn()}
      />,
    );

    const detail = screen.getByTestId('occurrence-detail');
    expect(within(detail).getByTestId('text-provenance')).toHaveTextContent('NATIVE');
    // ...and the three components are still exactly three, beside it.
    expect(within(detail).getByTestId('confidence-components')).toHaveTextContent(/·.*·/);
    await userEvent.click(within(detail).getByTestId('text-provenance'));
  });
});

describe('PageViewer text layer', () => {
  const props = {
    pages: PAGES,
    currentPageIndex: 0,
    onSelectPage: vi.fn(),
    evidence: [],
    // No canvas and no Worker under happy-dom, so the server-rendered PNG path.
    pdfRenderer: null,
  };

  /**
   * The whole-page verdict, where the page is chosen. It is a different fact
   * from any one field's provenance — a SCANNED page has no text layer at all,
   * so everything on it was recognised — and it is worth knowing before reading
   * a single value.
   */
  it('shows the selected page text layer and flags a scanned one', () => {
    const scanned: PageView[] = [{ ...PAGES[0], textLayer: 'SCANNED' }, PAGES[1]];
    const { rerender } = render(<PageViewer {...props} pages={scanned} />);

    const chip = screen.getByTestId('page-text-layer');
    expect(chip).toHaveTextContent('SCANNED');
    expect(chip.dataset.textLayer).toBe('SCANNED');

    rerender(<PageViewer {...props} pages={PAGES} />);
    expect(screen.getByTestId('page-text-layer')).toHaveTextContent('NATIVE');
  });
});
