import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import PageViewer from './PageViewer.tsx';
import { pdfBoxToViewport, viewportSize, type PageGeometry } from './coordinates.ts';
import { IMAGE_PAGE, PAGES, PAGE_1_ID, PAGE_2_ID, NET_PAY } from '../../test/fixtures.ts';
import type { PdfPageRenderer } from './pdfRenderer.ts';

/*
 * pdf.js itself is never loaded here — there is no canvas and no Worker under
 * happy-dom, so the renderer is injected. What IS tested is everything around
 * it: which page is shown, how the surface is sized, and that a renderer
 * failure degrades to the server-rendered image rather than to a blank pane.
 */

const LETTER: PageGeometry = { widthPt: 612, heightPt: 792, rotation: 0 };

function renderViewer(overrides: Partial<React.ComponentProps<typeof PageViewer>> = {}) {
  const props = {
    pages: PAGES,
    currentPageIndex: 0,
    onSelectPage: vi.fn(),
    evidence: [],
    pdfRenderer: null as PdfPageRenderer | null,
    ...overrides,
  };
  return { ...render(<PageViewer {...props} />), props };
}

describe('PageViewer', () => {
  it('sizes the page surface exactly as viewportSize says', () => {
    renderViewer();

    const surface = screen.getByTestId('page-surface');
    const expected = viewportSize(LETTER, 1);
    expect(Number.parseFloat(surface.style.width)).toBeCloseTo(expected.width, 1);
    expect(Number.parseFloat(surface.style.height)).toBeCloseTo(expected.height, 1);
    expect(surface).toHaveAttribute('data-page-id', PAGE_1_ID);
  });

  it('renders a thumbnail per page and lets one be picked', async () => {
    const onSelectPage = vi.fn();
    renderViewer({ onSelectPage });

    const thumbnails = screen.getAllByTestId('page-thumbnail');
    expect(thumbnails).toHaveLength(PAGES.length);

    await userEvent.click(thumbnails[1]);
    expect(onSelectPage).toHaveBeenCalledWith(1);
  });

  it('badges a thumbnail with its document type', () => {
    renderViewer({ pageBadges: { [PAGE_1_ID]: 'PAYSTUB', [PAGE_2_ID]: 'PAYSTUB' } });

    expect(screen.getAllByTestId('page-thumbnail')[0]).toHaveTextContent('PAYSTUB');
  });

  it('steps forward and back, and stops at the ends', async () => {
    const onSelectPage = vi.fn();
    const { rerender } = render(
      <PageViewer
        pages={PAGES}
        currentPageIndex={0}
        onSelectPage={onSelectPage}
        evidence={[]}
        pdfRenderer={null}
      />,
    );

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(onSelectPage).toHaveBeenCalledWith(1);

    rerender(
      <PageViewer
        pages={PAGES}
        currentPageIndex={1}
        onSelectPage={onSelectPage}
        evidence={[]}
        pdfRenderer={null}
      />,
    );
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('resizes the surface when the reviewer rotates the page', async () => {
    renderViewer();

    await userEvent.click(screen.getByTestId('rotate-button'));

    const surface = screen.getByTestId('page-surface');
    const expected = viewportSize(LETTER, 1, 90);
    await waitFor(() => {
      expect(Number.parseFloat(surface.style.width)).toBeCloseTo(expected.width, 1);
    });
    expect(Number.parseFloat(surface.style.height)).toBeCloseTo(expected.height, 1);
  });

  it('shows the role legend only when there is evidence to explain', () => {
    const { rerender } = render(
      <PageViewer
        pages={PAGES}
        currentPageIndex={0}
        onSelectPage={vi.fn()}
        evidence={[]}
        pdfRenderer={null}
      />,
    );
    expect(screen.queryByTestId('evidence-legend')).not.toBeInTheDocument();

    rerender(
      <PageViewer
        pages={PAGES}
        currentPageIndex={0}
        onSelectPage={vi.fn()}
        evidence={NET_PAY.evidence}
        pdfRenderer={null}
      />,
    );
    expect(screen.getByTestId('evidence-legend')).toBeInTheDocument();
  });

  it('uses the server-rendered image when pdf.js is not in play', () => {
    renderViewer();

    expect(screen.getByTestId('page-image')).toHaveAttribute(
      'src',
      `/v1/pages/${PAGE_1_ID}/render`,
    );
    expect(screen.queryByTestId('pdf-canvas')).not.toBeInTheDocument();
  });

  it('falls back to the image — and says why — when pdf.js cannot render', async () => {
    // A viewer that shows nothing is worse than one that shows a lower-fidelity
    // page and admits it.
    const failing: PdfPageRenderer = vi.fn().mockRejectedValue(new Error('worker blocked'));
    renderViewer({ pdfRenderer: failing });

    expect(await screen.findByTestId('page-image')).toBeInTheDocument();
    expect(screen.getByTestId('pdf-fallback-notice')).toHaveTextContent(/could not be rendered/i);
  });

  it('never hands an image source to pdf.js', async () => {
    // A photographed paystub is the commonest upload there is. Handing its JPEG to
    // pdf.js costs a fetch and a parse, then apologises for a PDF that never
    // existed — the page raster IS the document, so go straight to it.
    const pdfRenderer: PdfPageRenderer = vi.fn().mockResolvedValue(undefined);
    renderViewer({ pages: [IMAGE_PAGE], pdfRenderer });

    expect(await screen.findByTestId('page-image')).toBeInTheDocument();
    expect(pdfRenderer).not.toHaveBeenCalled();
    expect(screen.queryByTestId('pdf-canvas')).not.toBeInTheDocument();
    // Nothing FAILED, so there is nothing to apologise for.
    expect(screen.queryByTestId('pdf-fallback-notice')).not.toBeInTheDocument();
  });

  it('places evidence boxes over an image page from its geometry alone', () => {
    // The overlay never learns what produced the surface. An image page's canonical
    // frame comes from its pixel count (612x792 pt for a 1224x1584 photo at the
    // derived 144 DPI), and a box in it lands exactly where the same box on a PDF
    // page would.
    const box = { x: 28.5, y: 286, width: 58.1, height: 20 };
    renderViewer({
      pages: [IMAGE_PAGE],
      evidence: [
        {
          role: 'VALUE',
          ordinal: 0,
          pageId: IMAGE_PAGE.pageId,
          packagePageIndex: 0,
          ...box,
          textSpanId: null,
          layoutElementId: null,
        },
      ],
    });

    const rendered = screen.getAllByTestId('evidence-box')[0];
    const expected = pdfBoxToViewport(box, LETTER, 1);
    expect(Number.parseFloat(rendered.style.left)).toBeCloseTo(expected.left, 1);
    expect(Number.parseFloat(rendered.style.top)).toBeCloseTo(expected.top, 1);
    expect(Number.parseFloat(rendered.style.width)).toBeCloseTo(expected.width, 1);
    expect(Number.parseFloat(rendered.style.height)).toBeCloseTo(expected.height, 1);
  });

  it('says so when a page has no rendered image at all', () => {
    renderViewer({ pages: [{ ...PAGES[0], hasRender: false }] });

    expect(screen.getByTestId('page-unavailable')).toBeInTheDocument();
  });

  it('reports an out-of-range page instead of rendering an empty frame', () => {
    renderViewer({ currentPageIndex: 7 });

    expect(screen.getByTestId('page-viewer')).toHaveTextContent(/no page 8/i);
  });
});
