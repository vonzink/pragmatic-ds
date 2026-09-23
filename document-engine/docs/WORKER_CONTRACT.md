# Worker Wire Contract — v1

**Status:** Phase 2 · This document is the contract between `engine-api` (Java) and
`parser-worker` (Python). Both sides test against it independently; neither may change it
unilaterally. Golden files pin the Python side; MockWebServer fixtures pin the Java side.

---

## Invariants (apply to every endpoint)

1. **Coordinate space:** every box is `{x, y, width, height}` in **PDF points, top-left origin,
   rotation-0**, rounded to **0.1pt**. Never pixels. The worker's `geometry/` module is the single
   conversion point; the Java side stores values verbatim.
2. **Stateless:** the worker receives bytes and returns JSON. It persists nothing, logs no document
   content, and holds no credentials.
3. **Auth:** every request carries `X-Worker-Secret: <shared secret>`. Wrong or missing secret →
   `401 {"error": "UNAUTHORIZED"}` with no other detail.
4. **Errors:** `4xx/5xx` bodies are `{"error": "<STABLE_CODE>", "detail": {<non-sensitive params>}}`.
   Codes: `UNAUTHORIZED`, `INVALID_REQUEST`, `CORRUPT_PDF`, `CORRUPT_IMAGE`, `PAGE_OUT_OF_RANGE`,
   `RENDER_FAILED`, `TEXT_EXTRACTION_FAILED`, `OCR_FAILED`, `BURST_FAILED`, `INTERNAL`,
   `CALLER_GONE`. `BURST_FAILED` has no local twin in the Java taxonomy and collapses conservatively
   to `WORKER_UNAVAILABLE`, per the mapping rule in `WorkerClient.mapWorkerCode`. Detail never
   contains document text, file names, or byte content.
   `499 CALLER_GONE` is emitted when the caller disconnected while the request waited its turn
   (see invariant 8): nobody receives it, but it is in the access log so a dropped request is
   countable.
8. **One at a time, never for a dead caller:** stage work (`/v1/render`, `/v1/text`, `/v1/ocr`,
   `/v1/layout`, `/v1/burst`) runs off the event loop through a gate of
   `DOCENGINE_WORKER_CONCURRENCY` (default 1) requests. `/health` answers while work runs. A
   request whose caller hung up while queued is dropped at the gate and never executed — the
   engine's per-call timeout must therefore cover the QUEUE WAIT as well as the work, which is why
   the engine runs `max-concurrent-jobs` no higher than this gate.
7. **Source kinds:** the `file` part is a PDF **or an image** (`image/png`, `image/jpeg`,
   `image/tiff`, `image/heic` — exactly what ingestion accepts). The worker decides which from the
   MAGIC BYTES, never from a declared content type, and only those magics take the image path:
   anything else keeps reporting `CORRUPT_PDF` exactly as before. Undecodable image bytes are
   `CORRUPT_IMAGE`, never `CORRUPT_PDF`.

   HEIC is ISO-BMFF, so its magic is not a prefix: `ftyp` at bytes 4..8 and one of the MAJOR brands
   `heic`, `heix`, `hevc`, `mif1` at 8..12. Compatible brands are not scanned, which keeps AVIF —
   whose compatible list names `mif1` — off a path with no AVIF decoder behind it.

   **HEIC is the one format the worker alone can validate.** The JVM has no HEIF reader, so
   ingestion accepts a HEIC on its magic bytes without decoding it; a truncated or empty one
   therefore reaches this contract and must fail here as `CORRUPT_IMAGE`. For every other format
   ingestion has already refused such a file at upload.

   An image needs no rendering — it already IS the page raster — so its canonical page box is
   derived from the pixel count: `dpi = clamp(round(long_edge_px * 72 / 792), 72, 600)` and
   `widthPt/heightPt = px * 72 / dpi`, which recovers the true resolution of any full-page scan
   exactly (2550x3300 → 300 DPI, 612.0 x 792.0 pt). `rotation` is always `0`: EXIF orientation is
   applied to the pixels at decode, so the raster is the rotation-0 frame by construction. A
   multi-frame TIFF is a multi-page document, one page per frame; every other format is one page.
5. **Versioning:** every success response carries `"worker": {"version": "<worker semver>",
   "libraries": {<name>: <version>}}` — persisted by the Java side into
   `processing_stage.parser_versions`. **Rule:** any change under `ocr/` that can alter the
   served text (engine order, gates, reconciliation, preprocessing) bumps `__version__` in the
   same change. The engine's parse-once reuse fingerprint (`ReuseFingerprintService`) hashes
   this version; without the bump, every package already parsed under the old behavior is a
   verified reuse hit forever and the fix never reaches a re-upload.
6. **Requests** are `multipart/form-data` with a `file` part (the bytes) and a `request` part
   (JSON, shapes below). Responses are JSON except `/v1/render` and `/v1/burst`, which return
   multipart (binary payloads are large; base64 would be a 33% tax). `/v1/burst` is the one
   endpoint whose sources arrive as SEVERAL parts (`file-0`..`file-N`) rather than one `file`
   part — a burst may span source files.

## POST /v1/render

Renders pages of a PDF to PNG.

Request JSON part:
```json
{ "pages": [0, 1, 2], "dpi": 200 }
```
- `pages`: 0-based indices; empty or absent = all pages. Out-of-range → `PAGE_OUT_OF_RANGE`.
- `dpi`: 72–600, default 200. 300 is the reprocess escalation (PARSER_EVALUATION.md §3).

Response: `multipart/mixed`. First part `metadata` (JSON):
```json
{
  "worker": { "version": "0.2.0", "libraries": {"pypdfium2": "5.12.1"} },
  "pages": [
    {
      "pageIndex": 0,
      "widthPt": 612.0,
      "heightPt": 792.0,
      "rotation": 0,
      "dpi": 200,
      "widthPx": 1700,
      "heightPx": 2200,
      "pngPart": "page-0"
    }
  ]
}
```
Then one part per page, `Content-Type: image/png`, part name = `pngPart` value. Part names ride
in `Content-Disposition: form-data; name="..."` (Content-ID accepted as a fallback by the Java
parser); the boundary parameter MAY be quoted per RFC 2045 — parsers must accept both.
- `rotation` is the PDF's declared /Rotate (0/90/180/270). Pixels are rendered AT that rotation
  (what a viewer shows); `widthPt`/`heightPt` are the **rotation-0** box — the canonical space.

## POST /v1/text

Native text extraction with word boxes, plus the per-page text-layer verdict.

Request JSON part:
```json
{ "pages": [] }
```

Response JSON:
```json
{
  "worker": { "version": "0.2.0", "libraries": {"pdfplumber": "0.11.10"} },
  "pages": [
    {
      "pageIndex": 0,
      "widthPt": 612.0,
      "heightPt": 792.0,
      "rotation": 0,
      "verdict": "NATIVE",
      "spans": [
        {
          "ordinal": 0,
          "text": "YTD Gross",
          "x": 112.3, "y": 84.0, "width": 58.2, "height": 10.5,
          "fontSize": 10.5,
          "fontName": "Helvetica-Bold"
        }
      ]
    }
  ]
}
```
- `verdict`: `NATIVE` (usable text layer covers the page ink), `SCANNED` (no meaningful text layer),
  `MIXED` (partial coverage — e.g. a native form with a pasted scan), `NONE` (blank/no ink and no
  text). Decision inputs: span count, span area vs. page area, embedded-image coverage. A page
  with a meaningful text layer is `MIXED` only when its embedded images cover at least 10% of the
  page AND the image area not under any word totals at least 1% of the page AND, on a coarse
  render, at least one such region looks like print (many small same-height ink blobs owning
  most of the ink) rather than a graphic (a chart, a banner, a logo block, a photo, a background).
  Only the regions that look like print are published, and the 1% floor is re-applied to those.
  A browser-printed statement with a logo and a balance chart stays `NATIVE`; a pasted scan or a
  photographed page goes `MIXED`. The classifier errs toward print — a dense chart with many
  labels may go to OCR; a page that cannot be rendered for the check keeps every region.
- Spans are **words** (pdfplumber `extract_words`), ordinal = reading order (top-to-bottom,
  left-to-right within line bands). Two measured producer defects are repaired at the source, on
  rotation-0 pages only, before anything downstream reads a box:
  - **Degenerate boxes**: a word whose reported box is under 1.5 pt tall while its own per-char
    advance implies an em ≥ 3× taller (a metricless embedded font — measured `size 0.24`,
    `fontname unknown`, a baseline stripe) is rebuilt from the advance: bottom kept, height :=
    mean char advance, top raised. `fontSize` keeps the raw reported value as the breadcrumb.
    Consistent tiny print (small box AND small advance) is never touched.
  - **Punctuation drawn as images**: same-row digit runs separated by sub-em holes merge into ONE
    money span only when every hole carries a declared sub-glyph image whose rendered ink is a
    single filled baseline-anchored mark, and the positional assignment (`,` between groups, `.`
    before a 2-digit cents group) yields canonical money. Any gate failing leaves the words
    split — holes with no ink, hollow or mid-height marks, and non-money shapes (`61 · 400`)
    never merge. The merge is **all-or-none per printed row**: a row carrying an amount the
    repair will not publish — one whose ink failed, or one wearing a decoration (a leading
    minus, a trailing footnote asterisk) that no gate verified — merges NOTHING, because
    repairing only its neighbour hands "occurrence 0" the wrong column.
- `MIXED` pages also return `"uncoveredRegions": [{x, y, width, height}]` — the areas OCR must
  handle. `NATIVE` and `SCANNED` omit the field.
- Every page also carries `"inkFraction": 0.083` — the fraction of dark pixels at a coarse render
  (the blank-page signal; the Java side persists it as `page.blank_score` and derives `is_blank`).
  `null` when the page could not be rendered for the check.

## POST /v1/layout

Layout elements clustered from spans the caller already extracted (Phase 3), plus the Spec 3
pixel-path plumbing. The worker stays stateless: spans come IN on the request — they are not
re-derived — so layout works identically for native and OCR'd pages. The `file` part (the
source PDF) is optional ON THE WIRE, but the engine now ALWAYS attaches it: pdfplumber
`rects`/`lines` on native pages confirm ruled tables and raise their confidence, and the
worker renders each requested page internally (pypdfium2, 200 DPI) for the Spec 3 pixel
detectors (checkbox and signature). No rasters ever ride the wire in either direction.

Request: multipart with optional `file` (PDF) and a `request` JSON part:
```json
{
  "pages": [
    {
      "pageIndex": 0,
      "widthPt": 612.0,
      "heightPt": 792.0,
      "spans": [
        { "ordinal": 0, "text": "Earnings", "x": 72.0, "y": 178.5, "width": 47.9, "height": 12.8,
          "fontSize": 11.0, "fontName": "Helvetica-Bold" }
      ]
    }
  ]
}
```
- `fontSize`/`fontName` are optional (OCR spans have neither).
- Span `ordinal` is the caller's identifier: element→span links refer to it.

Response JSON:
```json
{
  "worker": { "version": "0.3.0", "libraries": {"pdfplumber": "0.11.10", "pypdfium2": "5.12.1"} },
  "pages": [
    {
      "pageIndex": 0,
      "elements": [
        {
          "elementId": "e0",
          "parentElementId": null,
          "elementType": "TABLE",
          "ordinal": 4,
          "x": 66.0, "y": 168.0, "width": 494.0, "height": 110.0,
          "confidence": 0.95,
          "attributes": { "rows": 5, "cols": 5, "ruled": true },
          "spanOrdinals": []
        },
        {
          "elementId": "e1",
          "parentElementId": "e0",
          "elementType": "TABLE_ROW",
          "ordinal": 5,
          "x": 66.0, "y": 168.0, "width": 494.0, "height": 22.0,
          "confidence": 0.95,
          "attributes": { "row": 0 },
          "spanOrdinals": []
        },
        {
          "elementId": "e2",
          "parentElementId": "e1",
          "elementType": "TABLE_CELL",
          "ordinal": 6,
          "x": 72.0, "y": 178.5, "width": 47.9, "height": 12.8,
          "confidence": 0.95,
          "attributes": { "row": 0, "col": 0 },
          "spanOrdinals": [12]
        },
        {
          "elementId": "e3",
          "parentElementId": null,
          "elementType": "CHECKBOX",
          "ordinal": 7,
          "x": 71.3, "y": 305.0, "width": 10.1, "height": 10.1,
          "confidence": 0.93,
          "attributes": { "checked": true, "fillRatio": 0.31 },
          "spanOrdinals": [],
          "detector": "checkbox-cv",
          "detectorVersion": "0.1.0"
        },
        {
          "elementId": "e4",
          "parentElementId": null,
          "elementType": "SIGNATURE",
          "ordinal": 8,
          "x": 306.0, "y": 640.3, "width": 121.7, "height": 27.4,
          "confidence": 0.9,
          "attributes": { "inkFraction": 0.11 },
          "spanOrdinals": [],
          "detector": "signature-cv",
          "detectorVersion": "0.1.0"
        }
      ],
      "notImplemented": []
    }
  ]
}
```
- `elementType`: `PARAGRAPH` · `HEADER` · `TABLE` · `TABLE_ROW` · `TABLE_CELL` · `IMAGE` ·
  `LINE` · `CHECKBOX` · `SIGNATURE` (Spec 3 pixel detection).
- `FORM_FIELD` element: a label and, on the same baseline, the ONE numeric run (amount, count,
  percentage or date) that follows it within a few ems — `Gross Pay` + `1,234.56` as one element.
  `spanOrdinals` lists label then value in page order; `"attributes": {"labelSpanOrdinals": [..],
  "valueSpanOrdinals": [..]}` says which is which (ordinals only — attributes never repeat page
  text). A label followed by two figures, or by words, is left as ordinary lines: an undecidable
  pairing is not guessed. Fixed 0.9 prior like `PARAGRAPH`/`HEADER`.
- `TABLE_ROW` `"attributes": {"row": 0, "header": true}` marks a column-label row the detector
  absorbed from directly above the grid (every label over exactly one column, none a figure, at
  the grid's own row pitch); data rows number on from it. Absent on every other row.
- `notImplemented` declares the element types the worker did NOT look for on this page — an
  empty result must stay distinguishable from "did not look". With no `file` part there are no
  pixels, so it is `["CHECKBOX", "SIGNATURE"]`; when the PDF is attached the worker renders the
  page internally (pypdfium2, 200 DPI) and runs both pixel detectors, so the list is `[]`. A
  page index beyond the attached PDF gets no raster and keeps declaring both.
- `CHECKBOX` element: box = the drawn square (canonical pt);
  `"attributes": {"checked": true, "fillRatio": 0.31}` — `checked` is the ink fill of the inner
  60% of the box measured against a 0.15 threshold; `"confidence"` = contour rectangularity ×
  contrast, and candidates below 0.5 are OMITTED, never guessed (degraded scans fall through to
  the missing-field contract downstream); `"detector": "checkbox-cv"`; `"spanOrdinals": []`.
  Only near-square (aspect 0.75–1.33) drawn squares in the 8–24 pt band qualify — and only ones
  that are genuinely DRAWN squares: ink must frame all four sides of the bounding rect and the
  contour must approximate to four vertices, which is what keeps bold glyphs in the size band
  (`M`, `W`, `D`) from surfacing as phantom checked boxes.
- `SIGNATURE` element: box = the ink component's bounding box (canonical pt);
  `"attributes": {"inkFraction": 0.11}` — the component's ink pixels over its bounding-box
  area. PRESENCE only, never identity. Only connected ink lying ≥ 60% outside every text-span
  box qualifies (printed words are claimed by spans; handwriting is not), and only
  handwriting-shaped components: width ≥ 54 pt, aspect ≥ 2.0, height ≤ 72 pt, with
  stroke-direction variance above threshold — a drawn rule, an empty signature line, or a
  table grid never qualifies. Those shape gates measure the DISPLAY frame (the page as
  rendered at its `/Rotate`), because "wide, flat handwriting" describes ink as READ; the box
  still comes back canonical, so on a rotated page it is tall and narrow like everything else
  in rotation-0 space. `"confidence"` = stroke-direction variance × contrast, and candidates
  below 0.5 are OMITTED, never guessed (degraded scans fall through to the missing-field
  contract downstream); `"detector": "signature-cv"`; `"spanOrdinals": []`.
- `ordinal` is READING ORDER across the page's elements — multi-column pages order column-major
  (left column's elements before the right's), the plan's Kendall-tau target.
- `elementId`/`parentElementId` are request-scoped strings expressing table → row → cell nesting.
- Nesting is always the full chain `TABLE` → `TABLE_ROW` → `TABLE_CELL` (a cell is never parented
  directly to the table).
- Confidence: table trees carry the ruling-derived confidence (0.95 ruled-confirmed / 0.75
  unruled); `PARAGRAPH`/`HEADER` carry a fixed 0.9 prior — clustering has no per-element
  probability model in Phase 3, and a made-up varying number would be worse than an honest prior.
- Every element carries `"detector"`/`"detectorVersion"` — `"clustering"` for clustered
  elements, the detector's own identity (`"checkbox-cv"`, `"signature-cv"`) for pixel
  detections. Persisted verbatim — DATA_MODEL `layout_element.detector`.

## POST /v1/ocr

OCR of a rendered page image. The `file` part is a PNG (from `/v1/render`), NOT a PDF.

Request JSON part:
```json
{
  "pageIndex": 3,
  "widthPt": 612.0,
  "heightPt": 792.0,
  "dpi": 200,
  "rotation": 0,
  "regions": []
}
```
- `regions`: optional boxes (canonical space) to restrict OCR to — used for MIXED pages. Empty =
  whole page.
- `rotation`: the page /Rotate the raster was rendered at (from `/v1/render` metadata; default 0).
  REQUIRED whenever render reported non-zero: without it the worker cannot relate the display
  raster to the rotation-0 page frame, and boxes land a quarter-turn out of frame (a confirmed
  Phase 2 review finding — the caller owns passing it faithfully).
- The worker runs the ladder internally: Tesseract OSD → de-rotate → RapidOCR → gates G1–G6 →
  Tesseract fallback on trip → per-region reconciliation (PARSER_EVALUATION.md §4).

Response JSON:
```json
{
  "worker": { "version": "0.2.0", "libraries": {"rapidocr-onnxruntime": "1.4.4", "pytesseract": "0.3.13"} },
  "pageIndex": 3,
  "detectedRotation": 90,
  "osdConfidence": 0.94,
  "engine": "RAPIDOCR",
  "fallbackReason": null,
  "confidenceMedian": 0.91,
  "spans": [
    {
      "ordinal": 0,
      "text": "$48,231.30",
      "x": 112.3, "y": 84.0, "width": 61.0, "height": 11.2,
      "engine": "RAPIDOCR",
      "confidence": 0.97
    }
  ],
  "gates": {
    "G1_COVERAGE": {"tripped": false, "value": 0.93, "threshold": 0.60},
    "G2_WORD_YIELD": {"tripped": false, "value": 1.02, "threshold": 0.50},
    "G3_CONFIDENCE": {"tripped": false, "value": 0.91, "threshold": 0.70},
    "G4_NUMERIC": {"tripped": false, "value": 0.97, "threshold": 0.75},
    "G6_GEOMETRY": {"tripped": false, "value": 0.01, "threshold": 0.15}
  },
  "raw": {
    "rapidocr": { "spans": ["..."] },
    "tesseract": null
  }
}
```
- `engine` per SPAN as well as the page-level winner — reconciliation may mix engines by region,
  and evidence must name the engine that produced it (docs/DATA_MODEL.md `text_span.ocr_engine`).
- `fallbackReason`: `null`, or the first tripped gate (`G1_COVERAGE` … `G6_GEOMETRY`).
- `gates`: every evaluated gate with its measured value and threshold, for the PRIMARY engine's
  pass (the fallback's evaluation is internal to reconciliation; its unreconciled spans ride in
  `raw`). When recognition was region-restricted, G1/G2 are scoped to the regions — ink the
  engines were told to skip cannot count against them. G5 (anchor miss) is evaluated by the JAVA
  side — it needs classification context the worker must not have — so it never appears here.
- `raw`: both engines' unreconciled outputs when both ran (`tesseract: null` when no fallback).
  The Java side persists this blob to `parser_output` for both engines.
- Boxes are mapped into rotation-0 canonical space through THREE frames before response:
  engine (content-upright) → display (`detectedRotation`) → page rotation-0 (`rotation`). Content
  upright ≠ rotation-0: a sideways-scanned letter page IS a landscape page, and its rotation-0
  frame is 792×612.
- All-gates-tripped-on-both-engines → still `200` with `"engine": "NONE"`, empty spans, and every
  gate shown — the JAVA side decides that means `OCR_LOW_CONFIDENCE`. A worker 5xx means the
  process failed, not that the page was hard.

## POST /v1/burst

Emits an ordered page selection — possibly spanning several source files — as one standalone PDF:
the byte-serving half of a logical document (`GET /v1/documents/{id}/pdf` on the engine). All PDF
manipulation stays in the worker; the engine never grows a PDF library for cross-source
concatenation. PDF sources contribute page-subset copies via pypdf, never re-rendered; image
sources are wrapped one frame per PDF page from the ORIGINAL pixels, page box from the same
nominal-dpi rule the canonical frame uses. Owner-password-only encryption opens with the empty
user password, same as every other PDF-opening stage.

Multipart request: one part per source file, names chosen by the engine (`file-0`..`file-N` —
constants, never filenames), plus the `request` JSON part:
```json
{ "pages": [ {"part": "file-0", "index": 3}, {"part": "file-1", "index": 0} ] }
```
- `pages`: the output sequence — output page N is exactly entry N's source page. REQUIRED and
  non-empty: an empty list is `INVALID_REQUEST` (`PAGES_REQUIRED`), never "all pages" — an
  accidental `[]` must not silently return an entire source file where one document was intended.
  A duplicate `(part, index)` is `INVALID_REQUEST` (`DUPLICATE_PAGE_INDEX`) —
  `logical_document_page` cannot link a page twice, so a duplicate is an engine bug. A referenced
  part absent from the body is `INVALID_REQUEST` (`FILE_PART_MISSING`, with `partName`).
  Out-of-range → `PAGE_OUT_OF_RANGE` (with `partName`).

Response: `multipart/mixed`. First part `metadata` (JSON):
```json
{
  "worker": { "version": "0.2.0", "libraries": {"pypdf": "6.14.2", "pillow": "12.3.0"} },
  "pageCount": 3,
  "pdfPart": "pdf"
}
```
Then one part, `Content-Type: application/pdf`, part name = `pdfPart` value.
- No cover page, ever (owner decision 2026-08-22): the fidelity gate is that re-ingesting a burst
  reproduces the original pages' content hashes. Document identity travels in the ENGINE's
  response headers, not in pages.
- Errors: `CORRUPT_PDF` / `CORRUPT_IMAGE` / `BURST_FAILED` (500, structurally damaged page that
  survived opening but not copying).

## GET /health, GET /version

Unchanged from Phase 0. `/version` reports real installed versions (null for absent), and is the
source for `parser_versions` stamping.

## Java-side stage mapping (for the orchestration wiring)

| Stage | Worker call | Persists |
|---|---|---|
| `RENDERING` | `/v1/render` (all pages, 200 DPI) | PNGs → blob storage; `page` rows (geometry, rotation, render key, dpi) |
| `TEXT_EXTRACTION` | `/v1/text` | `text_span` rows (source=NATIVE); `page.text_layer` verdict; raw JSON → `parser_output` |
| `OCR_PROCESSING` | `/v1/ocr` per SCANNED page + per MIXED page (with regions) | `text_span` rows (source=OCR, engine per span); page OCR columns; BOTH raws → `parser_output`; engine `NONE` on a SCANNED page → page flag `OCR_LOW_CONFIDENCE`, job continues (a MIXED page keeps its trusted native layer and records the tripped gate instead) |
| `PARSING` | `/v1/layout` per source file (spans from DB; the original PDF ALWAYS attached — rulings confirm on native ink, pages rendered worker-side at 200 DPI for the pixel detectors) | `layout_element` + `layout_element_span` rows; raw JSON → `parser_output`; then page signals: `content_hash` (sha256 of ordered span text+boxes), duplicate detection package-wide, `is_blank`/`blank_score` from the text verdict + inkFraction |

`NATIVE` pages are never sent to `/v1/ocr` — asserted by test, not assumed.
