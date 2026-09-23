/**
 * The left pane: the document itself, with the evidence overlay on top.
 *
 * The invariant worth stating once, because everything here depends on it: the
 * SURFACE is sized by `viewportSize()` and the BOXES are placed by
 * `pdfBoxToViewport()`, both from `coordinates.ts`, both from the same
 * `{ page, scale, extraRotation }`. Whatever paints inside the surface — pdf.js
 * canvas, server PNG, or nothing at all — cannot move the boxes. That is why
 * zoom and rotation stay aligned (acceptance criterion 3) without a single
 * re-measurement.
 */

import { useEffect, useId, useRef, useState } from 'react';
import EvidenceOverlay, { EvidenceLegend } from './EvidenceOverlay.tsx';
import { normalizeRotation, viewportSize, type Rotation } from './coordinates.ts';
import { px } from './css.ts';
import { renderPdfPage, type PdfPageRenderer } from './pdfRenderer.ts';
import { fileContentUrl, pageRenderUrl } from '../../lib/api/client.ts';
import type { EvidenceView, PageView, TextLayer } from '../../lib/api/types.ts';

/** Zoom stops. Coarse on purpose — a reviewer wants one click, not a slider. */
const ZOOM_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4] as const;
const DEFAULT_ZOOM_INDEX = 2; // 1.0

export type PageViewerProps = {
  /** Every page in the package, in `packagePageIndex` order. */
  pages: readonly PageView[];
  /** Which page is on screen, by `packagePageIndex`. */
  currentPageIndex: number;
  onSelectPage: (packagePageIndex: number) => void;
  /** The selected occurrence's evidence chain. Empty when nothing is selected. */
  evidence: readonly EvidenceView[];
  /** The selected occurrence's coordinate, passed through for box attribution. */
  selectedOccurrence?: string;
  /** Short badge per page id — document type, or BLANK / DUPLICATE. */
  pageBadges?: Readonly<Record<string, string>>;
  /**
   * Injection seam for pdf.js. `null` skips it entirely and uses the
   * server-rendered PNG — which is what the component tests do, since neither
   * a canvas nor a Worker exists under happy-dom.
   */
  pdfRenderer?: PdfPageRenderer | null;
};

/**
 * Whether the page's original bytes are something pdf.js can open. Undefined means an
 * older API response with no `sourceContentType`; treating that as a PDF keeps the
 * previous behaviour (attempt, and fall back if it fails) rather than silently
 * downgrading every page to the raster.
 */
function isPdfSource(contentType: string | undefined): boolean {
  return contentType === undefined || contentType === 'application/pdf';
}

/** Human-readable reason a pdf.js render failed. Never an exception message. */
function failureReason(error: unknown): string {
  if (error instanceof Error && error.message.includes('canvas 2d context')) {
    return 'this browser did not provide a canvas';
  }
  return 'the PDF could not be rendered here';
}

export default function PageViewer({
  pages,
  currentPageIndex,
  onSelectPage,
  evidence,
  selectedOccurrence,
  pageBadges = {},
  pdfRenderer = renderPdfPage,
}: PageViewerProps) {
  const [zoomIndex, setZoomIndex] = useState(DEFAULT_ZOOM_INDEX);
  const [extraRotation, setExtraRotation] = useState<Rotation>(0);
  /**
   * Source files pdf.js could not handle, and why. Keyed by file rather than by
   * page: if the worker is blocked, it is blocked for every page of that file,
   * and retrying on each arrow press would just stall the viewer repeatedly.
   */
  const [pdfFailures, setPdfFailures] = useState<Record<string, string>>({});
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const zoomLabelId = useId();

  const scale = ZOOM_STEPS[zoomIndex];
  const page = pages.find((candidate) => candidate.packagePageIndex === currentPageIndex);

  const sourceFileId = page?.sourceFileId;
  const pdfFailure = sourceFileId === undefined ? undefined : pdfFailures[sourceFileId];
  // An image source has no PDF to render. Discovering that by handing a JPEG to pdf.js
  // and catching the failure would work, but it costs a fetch and a parse per page and
  // then apologises for a PDF that never existed — on the commonest upload there is.
  // The server-rendered PNG is not a fallback here; it IS the document.
  const usingPdf =
    pdfRenderer !== null && pdfFailure === undefined && isPdfSource(page?.sourceContentType);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!page || !usingPdf || !pdfRenderer || !canvas) return;

    const controller = new AbortController();
    void pdfRenderer({
      url: fileContentUrl(page.sourceFileId),
      pageIndex: page.pageIndex,
      page,
      scale,
      extraRotation,
      canvas,
      signal: controller.signal,
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setPdfFailures((previous) => ({ ...previous, [page.sourceFileId]: failureReason(error) }));
    });

    return () => {
      controller.abort();
    };
  }, [page, usingPdf, pdfRenderer, scale, extraRotation]);

  if (!page) {
    return (
      <div
        data-testid="page-viewer"
        className="flex h-full items-center justify-center p-8 text-sm text-slate-500"
      >
        No page {String(currentPageIndex + 1)} in this package.
      </div>
    );
  }

  // The surface: total rotation applied. The overlay is positioned against this.
  const surface = viewportSize(page, scale, extraRotation);
  // The server PNG: already rendered AT the page's own /Rotate (see
  // worker/src/pragmaticds_docengine_worker/render.py), so it needs only the reviewer's
  // EXTRA turn applied in CSS — hence extraRotation 0 here.
  const imageSize = viewportSize(page, scale, 0);
  const turn = normalizeRotation(extraRotation);

  const position = pages.findIndex((candidate) => candidate.packagePageIndex === currentPageIndex);
  const atFirst = position <= 0;
  const atLast = position < 0 || position >= pages.length - 1;

  const step = (delta: number) => {
    const next = pages[position + delta];
    if (next) onSelectPage(next.packagePageIndex);
  };

  return (
    <div data-testid="page-viewer" className="flex h-full min-h-0 flex-col bg-slate-100">
      <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-slate-200 bg-white px-3 py-2">
        <button
          type="button"
          className="rounded-sm border border-slate-300 px-2 py-1 text-sm disabled:opacity-40"
          onClick={() => { step(-1); }}
          disabled={atFirst}
        >
          Previous
        </button>
        <span data-testid="page-position" className="text-sm tabular-nums text-slate-700">
          Page {String(currentPageIndex + 1)} of {String(pages.length)}
        </span>
        {/* The WHOLE-PAGE verdict, which is a different fact from any one
            field's provenance and useful in its own right: a page the parser
            called SCANNED has no text layer at all, so every value on it was
            recognised and the reviewer should read the whole page with that in
            mind. It lives here, beside the page number, because this is where
            the page is chosen — and it never substitutes for the per-value
            badges, which are the only thing that can be right on a MIXED page. */}
        <TextLayerChip textLayer={page.textLayer} />
        <button
          type="button"
          className="rounded-sm border border-slate-300 px-2 py-1 text-sm disabled:opacity-40"
          onClick={() => { step(1); }}
          disabled={atLast}
        >
          Next
        </button>

        <span className="mx-2 h-4 w-px bg-slate-200" aria-hidden="true" />

        <button
          type="button"
          aria-label="Zoom out"
          className="rounded-sm border border-slate-300 px-2 py-1 text-sm disabled:opacity-40"
          onClick={() => { setZoomIndex((index) => Math.max(0, index - 1)); }}
          disabled={zoomIndex === 0}
        >
          −
        </button>
        <span
          id={zoomLabelId}
          data-testid="zoom-level"
          data-scale={scale}
          className="w-14 text-center text-sm tabular-nums text-slate-700"
        >
          {String(Math.round(scale * 100))}%
        </span>
        <button
          type="button"
          aria-label="Zoom in"
          className="rounded-sm border border-slate-300 px-2 py-1 text-sm disabled:opacity-40"
          onClick={() => {
            setZoomIndex((index) => Math.min(ZOOM_STEPS.length - 1, index + 1));
          }}
          disabled={zoomIndex === ZOOM_STEPS.length - 1}
        >
          +
        </button>

        <button
          type="button"
          aria-label="Rotate page clockwise"
          data-testid="rotate-button"
          data-extra-rotation={turn}
          className="rounded-sm border border-slate-300 px-2 py-1 text-sm"
          onClick={() => {
            setExtraRotation((current) => normalizeRotation(current + 90));
          }}
        >
          Rotate
        </button>

        {evidence.length > 0 ? <EvidenceLegend className="ml-auto" /> : null}
      </div>

      <div className="flex min-h-0 flex-1">
        <nav
          aria-label="Pages"
          data-testid="thumbnails"
          className="w-28 shrink-0 overflow-y-auto border-r border-slate-200 bg-white p-2"
        >
          <ol className="space-y-2">
            {pages.map((thumbnail) => {
              const selected = thumbnail.packagePageIndex === currentPageIndex;
              const badge = pageBadges[thumbnail.pageId] ?? badgeForPage(thumbnail);
              return (
                <li key={thumbnail.pageId}>
                  <button
                    type="button"
                    data-testid="page-thumbnail"
                    data-page-index={thumbnail.packagePageIndex}
                    aria-current={selected ? 'page' : undefined}
                    onClick={() => { onSelectPage(thumbnail.packagePageIndex); }}
                    className={`block w-full rounded-sm border p-1 text-left ${
                      selected ? 'border-sky-600 ring-2 ring-sky-200' : 'border-slate-200'
                    }`}
                  >
                    {thumbnail.hasRender ? (
                      <img
                        src={pageRenderUrl(thumbnail.pageId)}
                        alt={`Page ${String(thumbnail.packagePageIndex + 1)}`}
                        loading="lazy"
                        className="block w-full bg-white"
                      />
                    ) : (
                      <span className="flex h-24 items-center justify-center bg-slate-100 text-[10px] text-slate-500">
                        no render
                      </span>
                    )}
                    <span className="mt-1 block text-[10px] leading-tight text-slate-600">
                      {String(thumbnail.packagePageIndex + 1)}
                      {badge ? <span className="ml-1 text-slate-400">{badge}</span> : null}
                    </span>
                  </button>
                </li>
              );
            })}
          </ol>
        </nav>

        <div data-testid="page-scroll" className="min-h-0 flex-1 overflow-auto p-6">
          <div
            data-testid="page-surface"
            data-page-id={page.pageId}
            className="relative mx-auto bg-white shadow-md"
            style={{ width: px(surface.width), height: px(surface.height) }}
          >
            {usingPdf ? (
              <canvas ref={canvasRef} data-testid="pdf-canvas" className="block" />
            ) : page.hasRender ? (
              <img
                data-testid="page-image"
                src={pageRenderUrl(page.pageId)}
                alt={`Page ${String(page.packagePageIndex + 1)}`}
                className="absolute block max-w-none"
                style={{
                  width: px(imageSize.width),
                  height: px(imageSize.height),
                  // Centre, then turn about that centre: the exact geometric
                  // equivalent of the quarter-turn the surface already accounts
                  // for, so the image lands inside it at every rotation.
                  left: px((surface.width - imageSize.width) / 2),
                  top: px((surface.height - imageSize.height) / 2),
                  transform: `rotate(${String(turn)}deg)`,
                  transformOrigin: 'center center',
                }}
              />
            ) : (
              <div
                data-testid="page-unavailable"
                className="flex h-full items-center justify-center p-4 text-center text-sm text-slate-500"
              >
                This page has no rendered image.
              </div>
            )}

            <EvidenceOverlay
              evidence={evidence}
              pageId={page.pageId}
              page={page}
              scale={scale}
              extraRotation={extraRotation}
              occurrence={selectedOccurrence}
            />
          </div>

          {pdfFailure ? (
            <p
              data-testid="pdf-fallback-notice"
              role="status"
              className="mx-auto mt-3 max-w-prose text-center text-xs text-amber-700"
            >
              Showing the server-rendered image because {pdfFailure}. Evidence boxes are placed
              from page geometry and are unaffected.
            </p>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/**
 * How the SELECTED page's text was obtained — the page-level companion to the
 * per-value provenance badges in the field panel.
 *
 * `SCANNED` and `MIXED` are amber for the same reason an OCR'd value is: they
 * are the pages where at least some characters were guessed. `NONE` is amber
 * too — a page with no text layer and no OCR yielded nothing to extract from,
 * which is a stronger warning still, not a milder one.
 */
function TextLayerChip({ textLayer }: { textLayer: TextLayer }) {
  const scanned = textLayer !== 'NATIVE';
  const title =
    textLayer === 'NATIVE'
      ? 'This page has its own text layer'
      : textLayer === 'SCANNED'
        ? 'This page has no text layer — everything on it was recognised by OCR'
        : textLayer === 'MIXED'
          ? 'Part of this page has a text layer; the rest was recognised by OCR'
          : 'No text was obtained from this page at all';
  return (
    <span
      data-testid="page-text-layer"
      data-text-layer={textLayer}
      title={title}
      className={`rounded-sm px-1.5 py-0.5 font-mono text-[11px] font-medium ${
        scanned ? 'bg-amber-100 text-amber-900' : 'bg-slate-100 text-slate-600'
      }`}
    >
      {textLayer}
    </span>
  );
}

/** The badge a page earns on its own, before classification has anything to say. */
function badgeForPage(page: PageView): string | null {
  if (page.duplicateOfPageId) return 'dup';
  if (page.blank) return 'blank';
  return null;
}
