# Parser Evaluation — Pragmatic DS Document Engine

**Status:** Design approved 2026-07-30 · Spec 1
**Companion:** [`ARCHITECTURE.md`](ARCHITECTURE.md) · [`LICENSING.md`](LICENSING.md)

---

> **On the numbers in this document.** Performance figures below are **expectations drawn from
> library documentation, architecture, and published community benchmarks — not measurements taken
> on this project.** Nothing here has been benchmarked on real fixtures yet. Section 10 defines the
> harness that produces real numbers, and it runs in Phase 2 before any engine is locked in. Where a
> claim is an estimate it is marked *(est.)*.

## 1. What is actually being selected

This is not a general-purpose PDF-parsing bake-off. The engine has one non-negotiable requirement
that eliminates most of the field immediately:

> Every extracted value must be traceable back to the exact source page and location it came from.

That single constraint means:

- **Any tool that emits markdown or plain text without coordinates is disqualified as a primary
  path**, however good its output reads.
- **Per-word confidence is required, not optional** — it drives the OCR quality gates and the
  confidence surfaced to reviewers.
- **A tool that owns its whole pipeline end-to-end is a poor fit**, because stage-level resume and
  golden-file diffing both need seams between stages.

Two further hard constraints:

- **Licence must be permissive.** The engine is intended for Apache-2.0 release, so GPL, LGPL, and
  AGPL are blockers rather than trade-offs. See [`LICENSING.md`](LICENSING.md).
- **Must run on linux/arm64 in Docker.** Development is on Apple Silicon. A library without arm64
  wheels means QEMU emulation, and emulated OCR is unusable for iteration.

## 2. Native PDF text extraction

| Library | Licence | Boxes | Reading order | Tables | arm64 | Maintenance | Verdict |
|---|---|---|---|---|---|---|---|
| **pdfplumber** (pdfminer.six) | **MIT** | Char + word level | Positional, configurable | `rects` / `lines` / `curves` exposed | Pure Python ✅ | Active | **Chosen** |
| pypdf | **BSD-3** | ✗ (text only) | Content-stream order | ✗ | Pure Python ✅ | Active | **Chosen — for page ops, not text** |
| pypdfium2 | **Apache-2.0 / BSD-3** | Char boxes via `FPDFText_GetCharBox` | Good | ✗ | Prebuilt wheels ✅ | Active | **Chosen — for render; text is the contingency path** |
| PyMuPDF | ⛔ **AGPL-3.0** | Excellent, span level | Excellent | Good | ✅ | Very active | **Rejected — licence** |
| pdftotext / poppler | ⛔ GPL-2 | Layout mode only | Good | ✗ | ✅ | Active | **Rejected — licence** |
| Apache PDFBox (Java) | Apache-2.0 | Via `PDFTextStripperByArea` | `sortByPosition` | ✗ | ✅ | Active | **Not chosen** — already used in host-app, but the parsing work belongs in the Python worker, not the API |
| Tika | Apache-2.0 | ✗ | — | ✗ | ✅ | Active | **Rejected** — no coordinates |

**Decision: pdfplumber for text with boxes, pypdf for page operations.**

pdfplumber is the only permissive library that gives word-level boxes *and* exposes vector `rects`
and `lines`. Those primitives are what make ruled-table detection and checkbox detection tractable
later without a model. pypdfium2 could replace it (char boxes are available), but word grouping and
the vector primitives would have to be rebuilt by hand — so it stays the documented contingency.

**Cost of avoiding PyMuPDF:** pdfplumber is roughly **2–3× slower** on text extraction *(est.)*.
Irrelevant at our volume, and the render path more than compensates.

## 3. Page rendering

| Library | Licence | Backend | System deps | arm64 | Speed | Verdict |
|---|---|---|---|---|---|---|
| **pypdfium2** | **Apache-2.0 / BSD-3** | PDFium (Google/Chrome) | **None** — binary in the wheel | ✅ | Fastest of the three *(est.)* | **Chosen** |
| pdf2image | MIT wrapper | ⛔ **poppler, GPL-2** | poppler-utils | ✅ | Moderate | Rejected — licence |
| PyMuPDF | ⛔ AGPL-3.0 | MuPDF | None | ✅ | Fast | Rejected — licence |

**Decision: pypdfium2.** It is the rare case where the permissive option is also the technically
better one — PDFium is Chrome's production PDF renderer, ships as a prebuilt wheel with no system
binary, and removes poppler from the Docker image entirely.

Render target: **200 DPI PNG**. High enough for reliable OCR of 8–10pt paystub text, low enough to
keep page images and memory reasonable. 300 DPI is available per-request for a reprocess when a page
fails its quality gates.

## 4. OCR

The most consequential choice in the stack, and the one where licence, hardware, and accuracy pull
in different directions.

| Engine | Licence | Word boxes | Per-word conf. | Orientation | arm64 Docker | Image size | Scanned accuracy | Verdict |
|---|---|---|---|---|---|---|---|---|
| **RapidOCR** (PP-OCR on ONNX Runtime) | **Apache-2.0** / ONNX Runtime MIT | ✅ | ✅ | ✗ | ✅ native wheels | **~100 MB** *(est.)* | High — PP-OCR lineage | **Chosen — primary** |
| **Tesseract 5** (pytesseract) | **Apache-2.0** | ✅ `image_to_data` | ✅ | ✅ **OSD** | ✅ apt package | ~50 MB | Moderate; degrades on noise/skew | **Chosen — fallback + OSD** |
| PaddleOCR | Apache-2.0 | ✅ | ✅ | Partial | ⛔ **no linux/aarch64 wheels on PyPI** | ~3 GB *(est.)* | High | **Deferred to GPU/AWS adapter** |
| docTR (Mindee) | Apache-2.0 | ✅ | ✅ | ✗ | ✅ (PyTorch has arm64) | ~1–2 GB *(est.)* | High | **Roadmapped — GPU adapter** |
| EasyOCR | Apache-2.0 | ✅ | ✅ | ✗ | ✅ | ~1 GB *(est.)* | Moderate–high | Not chosen — no advantage over RapidOCR at higher cost |
| **Surya** | ⛔ **GPL-3.0** | ✅ | ✅ | ✅ | ✅ | ~1 GB | Very high | **Rejected — licence** |
| AWS Textract / Azure DI / Google DocAI | Commercial, paid | ✅ | ✅ | ✅ | n/a | n/a | Very high | **Rejected for MVP** — paid third-party, and sends unredacted borrower documents off-platform |

### Why not PaddleOCR in the MVP, despite being the named preference

The licence is fine — Apache-2.0, models included. **The blocker is packaging.** PaddlePaddle
publishes wheels for linux `x86_64`, macOS `arm64`, and Windows, but **not linux `aarch64`**, which
is what a Docker worker on Apple Silicon is. The options are `--platform linux/amd64` under QEMU
(roughly **5–15× slowdown** *(est.)*, unusable for iteration) or building PaddlePaddle from source.
Add a ~3 GB image and it is the wrong MVP trade.

**RapidOCR resolves this without compromise:** it runs the same PP-OCR model family, converted to
ONNX, on ONNX Runtime. Same accuracy lineage, roughly 1/30th the image size, native arm64 wheels,
Apache-2.0 throughout. A native/GPU `PaddleOcrEngine` adapter remains on the roadmap for AWS, where
`x86_64` and GPU are both available.

### Why Tesseract stays

Not as a consolation prize. Two concrete jobs:

1. **Orientation detection.** `image_to_osd` returns page rotation and an orientation confidence.
   **RapidOCR has no equivalent.** Without it, a 90°-rotated scan produces garbage from any engine.
2. **Independent fallback.** When a quality gate trips, the value of a second opinion depends on it
   failing *differently*. Tesseract's classical pipeline fails on different inputs than a neural
   detector-recognizer does, which is exactly what makes it useful here.

### The selection ladder

Both engines sit behind `OcrEngine`. **They are never run routinely together.**

```
NATIVE page ──────────────────────────────────────▶ OCR skipped entirely
SCANNED / MIXED page
    │
    ├─▶ 1. Tesseract OSD ──▶ rotation + confidence ──▶ de-rotate raster
    │       (low OSD confidence → NumPy projection-profile arbitration)
    │
    ├─▶ 2. RapidOCR (primary)
    │
    ├─▶ 3. Quality gates G1–G6
    │         │
    │         ├─ none trip ──────────────▶ done, single engine, single cost
    │         │
    │         └─ any trips ──▶ 4. Tesseract fallback
    │                              │
    │                              └─▶ 5. Reconcile per region by confidence + geometry validity
    │                                     ocr_engine recorded PER SPAN
    │
    └─▶ 6. both fail ──▶ page OCR_LOW_CONFIDENCE ──▶ package HUMAN_REVIEW_REQUIRED
```

| Gate | Trips when | Why |
|---|---|---|
| G1 Coverage | OCR'd text area ÷ detected ink area < 0.60 | Whole regions missed |
| G2 Word yield | detected words < 0.5 × expected for ink density | Suspiciously sparse |
| G3 Confidence floor | median word conf. < 0.70, or p10 < 0.40 | Broad degradation |
| G4 **Numeric integrity** | any currency/decimal token below 0.75 conf. | **A wrong digit is worse than a wrong word.** The mortgage-specific gate |
| G5 Anchor miss | zero classification anchors on a page whose neighbours classified confidently | OCR failure, not a genuinely unknown page |
| G6 Geometry | > 15% of boxes zero-area, negative, or out of bounds | Coordinate corruption |

Both engines' raw outputs persist to `parser_output` regardless of which wins reconciliation, and
`ocr_engine` is recorded **per text span** so reconciliation never destroys traceability.

Reconciliation's per-region rule is mean confidence among geometry-valid readings — with one
exception, made because confidence cannot compare a reading of the LINES with a reading of the
WORDS. When G2 trips on either pass, that engine read too few tokens for the ink; on a low-resolution
prose scan that means it merged each line into one space-less token whose confidence stayed high. So
under a G2 trip a region first goes to the markedly denser reading (the other engine read fewer than
0.5 × its tokens there) when that reading looks like the sparse one's lines split into words — at
least half the sparse reading's characters sit in tokens longer than the dense reading's longest
token, and the dense reading holds ≥ 0.6 × the sparse one's character mass — and its mean confidence
is at the 0.70 G3 median threshold; a merely denser reading (leader dots, rules, logo scraps — the
same words plus fragments, so nothing out-lengths them) and comparable yields fall back to confidence. Measured on SSA's sample benefit letter
(`docs/reference-forms/SSAL.pdf`, a 72-DPI image-only PDF): 63 line tokens vs 220 words; by
confidence the merged reading won every region and the page classified UNKNOWN
(`worker/src/pragmaticds_docengine_worker/ocr/reconcile.py`).

## 5. Layout detection

| Option | Licence | Boxes | Reading order | Hardware | arm64 | Integration | Verdict |
|---|---|---|---|---|---|---|---|
| **Coordinate clustering** (custom, NumPy) | n/a — ours | ✅ exact | ✅ derived | CPU, negligible | ✅ | Full control, fully debuggable | **Chosen for MVP** |
| **Docling** (IBM) | **MIT** | ✅ `ProvenanceItem.bbox` | ✅ | CPU slow, GPU good | ✅ | End-to-end pipeline | **Production candidate behind `LayoutEngine`** |
| LayoutParser | Apache-2.0 | ✅ | Partial | GPU preferred | Detectron2 build pain | High friction | Rejected |
| Table Transformer (Microsoft) | MIT | ✅ | Tables only | GPU | ✅ | Moderate | **Roadmapped — GPU** |
| Marker | ⛔ GPL-3.0 | ✅ | ✅ | GPU | ✅ | — | **Rejected — licence** |
| unstructured.io | Apache-2.0 core | Partial | ✅ | CPU/GPU | ✅ | Heavy dep tree, mixed-licence extras | Rejected — dependency sprawl |

### Correction on Docling

An earlier draft of this evaluation stated that Docling "emits markdown and destroys coordinates."
**That was wrong** — it conflated Docling with Marker. `DoclingDocument` carries a `ProvenanceItem`
with `bbox` and `page_no` on every item, and Docling is MIT-licensed. Both objections were incorrect.

The real reason it is not in the MVP is narrower: Docling is an **end-to-end pipeline that runs its
own OCR and layout**. Adopting it means adopting its stages wholesale, which works against two design
commitments — stage-level resume (there is no seam to resume from mid-pipeline) and golden-file
diffing (a pipeline-level diff is coarse and hard to attribute). It also introduces a second
coordinate system to reconcile against our own.

It remains a **strong production candidate** behind the `LayoutEngine` interface, particularly for
TableFormer table reconstruction, and is included in the Section 10 benchmark.

### MVP approach: clustering, not a model

Layout elements are derived from `text_span` geometry:

| Element | Method |
|---|---|
| Line | y-band grouping with font-size-relative tolerance |
| Paragraph | Consecutive lines with consistent left edge and line spacing |
| Header | Font size above page median, or bold, plus positional isolation |
| Table | Column detection via x-coordinate histogram; row bands via y-clustering; pdfplumber `rects` / `lines` promote confidence where ruling exists |
| Image | pdfplumber embedded-image objects |
| Checkbox / Signature | **Not implemented in Spec 1.** Returns an explicit `not_implemented` reason, never a bare empty result |

This is deliberately unglamorous. It is deterministic, it is debuggable when a paystub grid comes out
wrong, and **every box is preserved exactly** — which a model-based approach makes harder to
guarantee. Table Transformer and Docling are the upgrades once GPU is available and the golden
fixtures can prove they are better.

## 6. Table extraction

| Option | Licence | Ruled tables | Unruled tables | Deps | Verdict |
|---|---|---|---|---|---|
| **Coordinate clustering** | ours | Good | **Moderate — the MVP's weak point** | None | **Chosen for MVP** |
| Camelot | MIT | Good (`lattice`) | Poor (`stream`) | ⛔ Ghostscript (AGPL) | Rejected — licence + deps |
| Tabula | MIT | Good | Moderate | JVM | Rejected — JVM in the Python worker |
| Table Transformer | MIT | Very good | **Very good** | GPU | **Roadmapped** |
| Docling TableFormer | MIT | Very good | **Very good** | Models, CPU-slow | **Roadmapped** |

Paystub earnings and deductions grids are frequently **unruled** — whitespace-aligned columns with no
drawn lines. This is where clustering is weakest and where Table Transformer or Docling will earn
their place. It is named here as a known MVP limitation rather than discovered later: the M4 table
alignment metric in Section 10 measures exactly this, and the ten MVP paystub fields were chosen so
that only two (`currentGrossPay`, `ytdGrossPay`) depend on grid reconstruction at all.

## 7. Reading order

pdfplumber's positional sort plus our own line/paragraph banding. Multi-column layouts are handled by
x-histogram column detection before y-sorting within a column — the same problem host-app already
solved in `PdfTextExtractor` by pinning PDFBox's `sortByPosition`.

Measured by **Kendall tau against ground-truth span order** (M5), which generated fixtures know by
construction.

## 8. Bounding boxes and the coordinate contract

Every source disagrees. All are normalized at the worker boundary into **PDF points, top-left origin,
rotation-0**.

| Source | Native space | Conversion |
|---|---|---|
| pdfplumber / pdfminer | bottom-left origin, points | `top` attribute, verified against `height_pt` |
| pypdfium2 render | top-left, pixels at DPI | `pt = px × 72 ÷ dpi` |
| RapidOCR | top-left, pixels of input raster | `pt = px × 72 ÷ dpi`, then un-rotate by OSD angle |
| Tesseract | top-left, pixels of input raster | same |

This conversion has its own dedicated test against a fixture whose boxes are known by construction.
It is the highest-risk silent-failure surface in the entire system: a coordinate bug produces
plausible-looking output that is simply wrong, with no exception raised anywhere.

## 9. Licensing summary

| Library | Licence | Status |
|---|---|---|
| pypdfium2 | Apache-2.0 / BSD-3 (PDFium BSD-3) | ✅ |
| pdfplumber / pdfminer.six | MIT | ✅ |
| pypdf | BSD-3 | ✅ |
| RapidOCR | Apache-2.0 | ✅ |
| ONNX Runtime | MIT | ✅ |
| PP-OCR models | Apache-2.0 | ✅ |
| Tesseract 5 / pytesseract | Apache-2.0 | ✅ |
| Pillow | MIT-CMU | ✅ |
| NumPy | BSD-3 | ✅ |
| FastAPI / Starlette / Pydantic | MIT | ✅ |
| opencv-python-headless | Apache-2.0 core, **bundles FFmpeg (LGPL-2.1)** | ⚠️ Documented election — see below |
| FreeType (inside PDFium) | FTL **or** GPL-2 (dual) | ⚠️ **Pragmatic DS elects FTL** |
| PyMuPDF | AGPL-3.0 | ⛔ Rejected |
| poppler | GPL-2 | ⛔ Rejected |
| Surya / Marker | GPL-3.0 | ⛔ Rejected |
| Ghostscript (via Camelot) | AGPL-3.0 | ⛔ Rejected |

**The OpenCV caveat, stated plainly.** OpenCV itself is Apache-2.0, but the `opencv-python` PyPI
wheels bundle FFmpeg under LGPL-2.1. For a private service that is noise; for a **redistributed
open-source Docker image** it is a real, small obligation (dynamic linking, notice, relink
permission). We use OpenCV for deskew, adaptive binarization, and blank detection — **zero video
codecs**. Two acceptable resolutions:

1. `opencv-python-headless` with the LGPL-2.1 notice carried in `THIRD-PARTY-NOTICES.md` — **chosen**,
   since OpenCV is genuinely the best tool for adaptive thresholding and deskew.
2. scikit-image (BSD-3) instead, for zero obligation at some loss of capability — the documented
   escape hatch if the notice obligation ever becomes unwelcome.

Full policy, allowlist, denylist, and CI enforcement: [`LICENSING.md`](LICENSING.md).

## 10. Benchmark harness

Runs in Phase 2, **before any engine is locked in**. It is the only thing in this document that
produces real numbers.

### Matrix

| Axis | Values |
|---|---|
| OCR engine | RapidOCR **PP-OCRv4**, **PP-OCRv5**, **any stable v6 model set exposed by RapidOCR at build time**; Tesseract 5 baseline |
| Layout engine | Clustering; Docling (informational, CPU) |
| DPI | 200, 300 |
| Fixture | All 14 fixture classes |

**v4 is not assumed optimal.** If RapidOCR publishes no stable v6 model set when the harness runs, it
**records that fact and skips the cell** rather than substituting a different model and reporting it
as v6.

### Metrics — mortgage accuracy, not OCR confidence

| # | Metric | Definition |
|---|---|---|
| M1 | Field accuracy | Exact-match rate across the 10 paystub fields |
| M2 | **Numeric accuracy** | Character accuracy over digit / decimal / currency tokens, **with digit-transposition count reported separately** |
| M3 | Box quality | Mean IoU of predicted word boxes vs. ground truth |
| M4 | Table alignment | Share of earnings/deduction cells assigned the correct (row, column) |
| M5 | Reading order | Kendall tau vs. ground-truth span order |
| M6 | Runtime | p50 / p95 ms per page, CPU, arm64 |
| M7 | Memory | Peak RSS per page |
| M8 | Cold start | Model load time and image-size delta |

Ground truth is free: generated fixtures know their own values and boxes by construction. This is a
direct payoff of the decision to generate fixtures rather than collect them.

### Selection rule

**M2 is the primary gate.** An engine that transposes a digit in a YTD gross figure is worse than one
that is slow — the slow one costs compute, the wrong one costs an underwriting decision. Ties break
to M1, then M3, then M6.

**OCR confidence is an input to the quality gates, never a selection metric on its own.** A confident
wrong answer is the failure mode that matters, and confidence cannot detect it.

### First measured results — 2026-08-01, arm64 Darwin, worker/bench/results.json

The harness ran per protocol. **PP-OCRv5 and PP-OCRv6 cells: SKIPPED** — the installed
`rapidocr-onnxruntime 1.4.4` ships PP-OCRv4 models only; recorded rather than substituted, per the
protocol's explicit rule.

**M4 (table alignment) — measured in Phase 3** via the clustering layout engine over NATIVE text
spans (`results.json` `layout_cells`; per-OCR-engine M4 remains SKIPPED — layout over noisy OCR
boxes is a different, unbenchmarked measurement):

| Engine | Fixture | M4 cell alignment |
|---|---|---|
| clustering-layout | ruled_table | **1.00** (25/25) |
| clustering-layout | unruled_table | **1.00** (25/25) |

**Read the 1.00s with the caveat they deserve:** both fixtures are CONSTRUCTED grids — perfectly
x-aligned columns, uniform leading, clean fonts. The unruled test floor is deliberately asserted at
0.9, not 1.0, because real-world unruled paystub grids (ragged alignment, merged headers, wrapped
cells) are exactly where clustering degrades — the declared MVP weak point (§6). These numbers say
the machinery is correct, not that real unruled tables are solved. Table Transformer / Docling on
GPU remain the roadmapped upgrade, to be judged against harder fixtures when they exist.

| Engine | Fixture | M1 field | M2 char | M2 transpositions | M3 IoU | M6 p50 ms |
|---|---|---|---|---|---|---|
| RapidOCR v4 | native 200dpi | 0.837 | 0.923 | 0 | 0.644 | 563 |
| RapidOCR v4 | native 300dpi | 0.954 | **1.000** | 0 | 0.690 | 545 |
| RapidOCR v4 | scanned 200dpi | 0.837 | 0.923 | 0 | 0.644 | 509 |
| RapidOCR v4 | rotated-90 | 0.837 | 0.923 | 0 | 0.644 | 507 |
| RapidOCR v4 | **degraded** | 0.326 | **0.247** | 0 | 0.430 | 521 |
| Tesseract 5 | native 200dpi | **1.000** | **1.000** | 0 | 0.796 | **328** |
| Tesseract 5 | native 300dpi | **1.000** | **1.000** | 0 | 0.799 | 489 |
| Tesseract 5 | scanned 200dpi | **1.000** | **1.000** | 0 | 0.796 | 328 |
| Tesseract 5 | rotated-90 | **1.000** | **1.000** | 0 | 0.796 | 327 |
| Tesseract 5 | **degraded** | **0.000** | **0.000** | 0 | 0.000 | 1328 |

**Honest reading, including what surprised us:**

1. **On clean input, Tesseract wins everything and is faster.** Expected the other way around. Two
   caveats before over-rotating on that: the fixtures are crisp synthetic Helvetica renders —
   Tesseract's best possible world — and RapidOCR's M1/M3 deficit is substantially **box
   granularity, not recognition**: v4 emits line-level quads that the engine splits into words by
   proportional character width, which costs IoU and merges unspaced words. Its M2 *character*
   accuracy is 0.923 at 200 DPI and perfect at 300.
2. **On degraded input the engines invert violently.** Tesseract returns literally nothing (M2
   0.000) and takes 4× as long doing it; RapidOCR still recovers a quarter of the numeric
   characters. Real mortgage scans — photocopies, faxes, phone photos — resemble the degraded
   fixture far more than the clean ones.
3. **Decision: the ladder stands as designed** — RapidOCR primary, Tesseract fallback + OSD. The
   degraded row is the mortgage-critical case, and the inversion proves the ladder's premise: the
   fallback is only worth running because it fails *differently*. The clean-fixture result is
   recorded as an open question, not suppressed: if Phase 3's more realistic fixture classes
   (varied fonts, photocopy-style artifacts) still show Tesseract dominating the common case, the
   primary/fallback order deserves a revisit — it is a one-line config change behind `OcrEngine`.
4. **300 DPI materially helps RapidOCR** (M2 0.923 → 1.000). The contract already allows per-request
   DPI; wiring gate-tripped pages to a 300 DPI re-render is a cheap Phase 3 improvement.
5. Zero digit transpositions anywhere — the failure mode M2 exists to catch has not yet appeared;
   the degraded failures are omissions, not substitutions. Omissions are reviewable; substitutions
   are dangerous. Worth tracking as fixtures get harder.

## 11. Recommended stacks

### MVP — CPU, arm64, fully permissive

| Role | Library |
|---|---|
| Render | **pypdfium2** |
| Native text + boxes | **pdfplumber** / pdfminer.six |
| PDF operations | **pypdf** |
| OCR primary | **RapidOCR** (PP-OCR on ONNX Runtime) |
| OCR fallback + orientation | **Tesseract 5** |
| Image ops | **opencv-python-headless**, **Pillow**, **NumPy** |
| Layout + tables | **Coordinate clustering** (ours) |
| Service | **FastAPI** |

No GPL, no AGPL, no GPU, native arm64 throughout, and an image measured in hundreds of megabytes
rather than gigabytes. The one carried obligation is FFmpeg's LGPL-2.1 inside the OpenCV wheel, which
is documented and has a BSD-3 escape hatch — see [`LICENSING.md`](LICENSING.md) §5.1.

### Future production — GPU on AWS

| Role | Library | Change |
|---|---|---|
| Render | pypdfium2 | Unchanged |
| Native text | pdfplumber | Unchanged |
| OCR primary | **PaddleOCR** (native x86_64 + GPU) or **docTR** | New `OcrEngine` adapter |
| OCR fallback + orientation | Tesseract 5 | Unchanged |
| Tables | **Table Transformer** or **Docling TableFormer** | New `LayoutEngine` adapter |
| Layout | **Docling** | New `LayoutEngine` adapter |

Every change is an adapter swap behind an existing interface. **No API change, no schema change, no
worker contract change** — which is the entire reason those interfaces exist.

Promotion from MVP to production stack is gated on the Section 10 harness demonstrating improvement
on M2 and M4 against the committed golden fixtures. Not on the newer thing being newer.
