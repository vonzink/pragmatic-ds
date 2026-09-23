/**
 * pdf.js, kept behind one function so nothing else in the UI imports it.
 *
 * Two reasons for the isolation, both practical:
 *
 *  1. **Testability.** `pdfjs-dist` needs a real canvas and a Worker; neither
 *     exists under happy-dom. PageViewer takes the renderer as a prop
 *     defaulting to {@link renderPdfPage}, so its page-selection, zoom and
 *     fallback logic are testable without a browser. The pixels themselves are
 *     not — that gap is stated plainly rather than faked.
 *  2. **Failure containment.** If pdf.js cannot load the document (worker
 *     blocked, encrypted PDF, byte-range fetch refused) the viewer falls back
 *     to the server-rendered PNG instead of showing a blank pane. The overlay
 *     is unaffected either way: it is positioned from page geometry, not from
 *     whatever painted underneath it.
 */

import { totalRotation, type PageGeometry } from './coordinates.ts';

export type PdfRenderRequest = {
  /** `GET /v1/files/{id}/content` — the original bytes. */
  url: string;
  /** 0-based index within that file. `PageView.pageIndex`, not `packagePageIndex`. */
  pageIndex: number;
  /** The page's own geometry, for the rotation calculation. */
  page: PageGeometry;
  scale: number;
  /** Extra rotation the reviewer applied, on top of the page's `/Rotate`. */
  extraRotation: number;
  canvas: HTMLCanvasElement;
  signal?: AbortSignal;
};

/** What PageViewer needs from a renderer. Swappable in tests. */
export type PdfPageRenderer = (request: PdfRenderRequest) => Promise<void>;

/**
 * One in-flight/loaded document per URL.
 *
 * Without this, paging through a 40-page package re-downloads and re-parses the
 * whole PDF on every arrow press. Keyed by URL because that is exactly the
 * identity that matters — a different file id is a different document.
 */
const documentCache = new Map<string, Promise<PdfDocumentLike>>();

/**
 * The slice of pdf.js's surface this module uses.
 *
 * Declared structurally rather than imported as types so that `pdfjs-dist` is
 * only ever reached through the dynamic `import()` below — a static type import
 * is erased at build time, but it is one edit away from becoming a value import
 * that drags the whole library into the main bundle.
 */
type PdfViewportLike = { width: number; height: number };
type PdfPageLike = {
  getViewport(parameters: { scale: number; rotation?: number }): PdfViewportLike;
  render(parameters: {
    canvasContext: CanvasRenderingContext2D;
    viewport: PdfViewportLike;
  }): { promise: Promise<void>; cancel(): void };
};
type PdfDocumentLike = {
  numPages: number;
  getPage(pageNumber: number): Promise<PdfPageLike>;
};

let workerConfigured = false;

async function loadDocument(url: string): Promise<PdfDocumentLike> {
  const cached = documentCache.get(url);
  if (cached) return cached;

  const loading = (async () => {
    const pdfjs = await import('pdfjs-dist');
    if (!workerConfigured) {
      // `new URL(..., import.meta.url)` is the form Vite rewrites to a hashed
      // asset URL at build time and serves directly in dev. A bare string path
      // works in dev and 404s in production, which is the trap this avoids.
      pdfjs.GlobalWorkerOptions.workerSrc = new URL(
        'pdfjs-dist/build/pdf.worker.min.mjs',
        import.meta.url,
      ).toString();
      workerConfigured = true;
    }
    return (await pdfjs.getDocument({ url }).promise) as unknown as PdfDocumentLike;
  })();

  documentCache.set(url, loading);
  loading.catch(() => {
    // A failed load must not be cached, or one transient 502 poisons the URL
    // for the rest of the session.
    documentCache.delete(url);
  });
  return loading;
}

/** Drops every cached document. For tests and for a hard reload of a package. */
export function clearPdfCache(): void {
  documentCache.clear();
}

/**
 * Renders one page onto `canvas` at `scale`, honouring rotation.
 *
 * The rotation passed to pdf.js is the ABSOLUTE one — `getViewport({rotation})`
 * replaces the page's `/Rotate` rather than adding to it — which is precisely
 * what {@link totalRotation} computes, and why the canvas comes out the same
 * size `viewportSize()` predicts for the overlay above it.
 *
 * @throws if pdf.js cannot load or render. The caller is expected to fall back.
 */
export const renderPdfPage: PdfPageRenderer = async ({
  url,
  pageIndex,
  page,
  scale,
  extraRotation,
  canvas,
  signal,
}) => {
  const context = canvas.getContext('2d');
  if (!context) throw new Error('canvas 2d context unavailable');

  const document = await loadDocument(url);
  if (signal?.aborted) return;

  const pdfPage = await document.getPage(pageIndex + 1);
  if (signal?.aborted) return;

  const viewport = pdfPage.getViewport({
    scale,
    rotation: totalRotation(page, extraRotation),
  });

  // Round up: a fractional canvas attribute is truncated by the browser, and a
  // canvas one pixel short of its CSS box stretches the render — which would
  // shift the page under a correctly-placed overlay. Ceil, then let CSS size it
  // back down to the exact viewport dimensions.
  canvas.width = Math.ceil(viewport.width);
  canvas.height = Math.ceil(viewport.height);
  canvas.style.width = `${String(viewport.width)}px`;
  canvas.style.height = `${String(viewport.height)}px`;

  const task = pdfPage.render({ canvasContext: context, viewport });
  signal?.addEventListener('abort', () => { task.cancel(); }, { once: true });
  await task.promise;
};
