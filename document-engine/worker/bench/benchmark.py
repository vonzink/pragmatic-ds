#!/usr/bin/env python3
"""PARSER_EVALUATION.md §10 benchmark harness — the only source of real numbers.

    .venv/bin/python worker/bench/benchmark.py            # full matrix
    .venv/bin/python worker/bench/benchmark.py --runs 5 --output worker/bench/results.json

Matrix: every RapidOCR model set the INSTALLED package actually exposes (detected
from its bundled .onnx files — rapidocr-onnxruntime 1.4.4 ships PP-OCRv4 only), plus
the Tesseract 5 baseline, over rendered-native, scanned, rotated, and degraded
fixtures. Honesty rules from §10:

  * A requested model set the installed package does not ship (PP-OCRv5, PP-OCRv6)
    is recorded as SKIPPED with the reason — never silently substituted.
  * M4 (table alignment) is a LAYOUT metric, not an OCR one: measured in the
    separate `layout_cells` section by running the Phase 3 ClusteringLayoutEngine
    over NATIVE text spans of the two table fixtures (OCR engines stay out of
    it). The per-OCR-cell placeholder stays SKIPPED — an OCR cell has no layout
    of its own.
  * Ground truth comes from fixtures/truth/*.json — boxes known by construction.

Metrics: M1 field accuracy (exact text match against truth words), M2 numeric
accuracy (char-level over digit/currency tokens, digit transpositions counted
separately — the primary selection gate), M3 mean IoU, M5 reading-order Kendall
tau, M6 p50/p95 runtime, M7 peak RSS, M8 model load time.

Rotated fixtures go through osd.detect_rotation + de-rotation first — the same path
production uses — so the cell measures the engine on the raster it would really see.
"""

import argparse
import json
import re
import resource
import statistics
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from platform import machine, python_version, system

BENCH_DIR = Path(__file__).resolve().parent
REPO_ROOT = BENCH_DIR.parents[1]
sys.path.insert(0, str(REPO_ROOT / "worker" / "src"))

import pypdfium2 as pdfium  # noqa: E402
from PIL import Image  # noqa: E402

from pragmaticds_docengine_worker.geometry import Box, px_box_to_pt, px_to_pt, rotate_box  # noqa: E402
from pragmaticds_docengine_worker.layout.engine import (  # noqa: E402
    ClusteringLayoutEngine,
    PageSpans,
)
from pragmaticds_docengine_worker.layout.model import LayoutSpan  # noqa: E402
from pragmaticds_docengine_worker.layout.rulings import extract_rulings  # noqa: E402
from pragmaticds_docengine_worker.ocr.engines import (  # noqa: E402
    RapidOcrEngine,
    TesseractOcrEngine,
)
from pragmaticds_docengine_worker.text import extract_text  # noqa: E402
from pragmaticds_docengine_worker.ocr.gates import OcrConfig, is_numeric_token  # noqa: E402
from pragmaticds_docengine_worker.ocr.osd import detect_rotation  # noqa: E402
from pragmaticds_docengine_worker.ocr.reconcile import AttributedSpan, reading_order  # noqa: E402

FIXTURES_DIR = REPO_ROOT / "fixtures"

#: §10 asks for v4, v5, and any stable v6 — availability is DETECTED, never assumed.
REQUESTED_MODEL_SETS = ("PP-OCRv4", "PP-OCRv5", "PP-OCRv6")


@dataclass(frozen=True)
class FixtureSpec:
    """One benchmark input: either a pre-rendered PNG or a PDF rendered at run time."""

    name: str
    truth_file: str
    dpi: int
    png: str | None = None  # pre-rendered raster
    pdf: str | None = None  # rendered fresh at `dpi` (the native-rendered cells)


FIXTURE_SPECS = {
    spec.name: spec
    for spec in (
        FixtureSpec("native_200", "native_paystub.json", 200, pdf="native_paystub.pdf"),
        FixtureSpec("native_300", "native_paystub.json", 300, pdf="native_paystub.pdf"),
        FixtureSpec("scanned_200", "scanned_paystub.json", 200, png="scanned_paystub_page0.png"),
        FixtureSpec("rot90_200", "scanned_rot90.json", 200, png="scanned_rot90_page0.png"),
        FixtureSpec("degraded_200", "degraded_paystub.json", 200,
                    png="degraded_paystub_page0.png"),
    )
}


def available_rapidocr_model_sets() -> dict[str, list[str]]:
    """Model sets the installed rapidocr package actually ships, from its .onnx files.

    A set counts as available only when BOTH its detector and recogniser are present.
    """
    import rapidocr_onnxruntime

    models_dir = Path(rapidocr_onnxruntime.__file__).resolve().parent / "models"
    found: dict[str, set[str]] = {}
    for model in models_dir.glob("*.onnx") if models_dir.is_dir() else ():
        match = re.search(r"PP-OCRv(\d+)_(det|rec)", model.name)
        if match:
            found.setdefault(f"PP-OCRv{match.group(1)}", set()).add(match.group(2))
    return {
        name: sorted(roles) for name, roles in found.items() if {"det", "rec"} <= roles
    }


def _engine_catalog() -> tuple[dict[str, object], list[dict]]:
    """(engine name -> factory, skipped cells). Skips are recorded, never substituted."""
    available = available_rapidocr_model_sets()
    engines: dict[str, object] = {}
    skipped: list[dict] = []
    for model_set in REQUESTED_MODEL_SETS:
        name = f"rapidocr-{model_set.replace('PP-OCR', 'ppocr').lower()}"
        if model_set in available:
            engines[name] = RapidOcrEngine
        else:
            skipped.append(
                {
                    "cell": f"{name} ({model_set})",
                    "reason": (
                        f"installed rapidocr-onnxruntime ships no {model_set} model set; "
                        "cell skipped rather than substituting another model"
                    ),
                }
            )
    engines["tesseract"] = TesseractOcrEngine
    return engines, skipped


def _load_raster(spec: FixtureSpec) -> Image.Image:
    if spec.png:
        return Image.open(FIXTURES_DIR / spec.png).convert("RGB")
    document = pdfium.PdfDocument((FIXTURES_DIR / spec.pdf).read_bytes())
    try:
        return document[0].render(scale=spec.dpi / 72.0).to_pil().convert("RGB")
    finally:
        document.close()


def _truth_words(spec: FixtureSpec) -> list[dict]:
    truth = json.loads((FIXTURES_DIR / "truth" / spec.truth_file).read_text())
    return truth["pages"][0]["words"]


def _iou(a: Box, b: dict) -> float:
    ax1, ay1 = a.x + a.width, a.y + a.height
    bx1, by1 = b["x"] + b["width"], b["y"] + b["height"]
    ix = max(0.0, min(ax1, bx1) - max(a.x, b["x"]))
    iy = max(0.0, min(ay1, by1) - max(a.y, b["y"]))
    intersection = ix * iy
    union = a.width * a.height + b["width"] * b["height"] - intersection
    return intersection / union if union > 0 else 0.0


def _levenshtein(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1,
                               previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def _is_adjacent_transposition(a: str, b: str) -> bool:
    if len(a) != len(b) or a == b:
        return False
    diffs = [i for i in range(len(a)) if a[i] != b[i]]
    return (
        len(diffs) == 2
        and diffs[1] == diffs[0] + 1
        and a[diffs[0]] == b[diffs[1]]
        and a[diffs[1]] == b[diffs[0]]
    )


def _peak_rss_bytes() -> int:
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return peak if sys.platform == "darwin" else peak * 1024  # linux reports KB


def _evaluate_cell(engine, spec: FixtureSpec, runs: int) -> dict:
    raster = _load_raster(spec)
    rotation, _confidence = detect_rotation(raster, OcrConfig())
    derotated = raster.rotate(rotation, expand=True) if rotation else raster

    timings_ms = []
    spans_px = []
    for _ in range(max(runs, 1)):
        started = time.perf_counter()
        spans_px = engine.recognize(derotated, [])
        timings_ms.append((time.perf_counter() - started) * 1000.0)

    # Truth speaks the PAGE frame (fixtures/generate.py maps rotated-scan truth
    # through rotate_box — the Phase 2 review's rotation-frame fix). Production
    # decides READING ORDER in the content-upright frame a reader sees, then
    # emits boxes in the page frame; the evaluation mirrors both steps: order
    # on upright boxes first, THEN map each box for the IoU match.
    upright_w_pt = px_to_pt(derotated.width, spec.dpi)
    upright_h_pt = px_to_pt(derotated.height, spec.dpi)
    ordered_upright = reading_order(
        [
            AttributedSpan(s.text, px_box_to_pt(s.box_px, spec.dpi), s.confidence, engine.name)
            for s in spans_px
        ]
    )
    predicted = [
        AttributedSpan(
            span.text,
            rotate_box(span.box, rotation, upright_w_pt, upright_h_pt)
            if rotation
            else span.box,
            span.confidence,
            span.engine,
        )
        for span in ordered_upright
    ]
    truth_words = _truth_words(spec)

    # Greedy truth->prediction matching by best IoU.
    matches: list[tuple[dict, AttributedSpan | None, float]] = []
    for word in truth_words:
        best, best_iou = None, 0.0
        for span in predicted:
            overlap = _iou(span.box, word)
            if overlap > best_iou:
                best, best_iou = span, overlap
        matches.append((word, best, best_iou))

    exact = sum(1 for word, span, _ in matches if span is not None and span.text == word["text"])
    m1 = exact / len(truth_words) if truth_words else 1.0
    m3 = (
        statistics.fmean(overlap for _, _, overlap in matches) if matches else 0.0
    )

    numeric = [(word, span) for word, span, _ in matches if is_numeric_token(word["text"])]
    if numeric:
        char_scores, transpositions = [], 0
        for word, span in numeric:
            predicted_text = span.text if span is not None else ""
            distance = _levenshtein(predicted_text, word["text"])
            char_scores.append(max(0.0, 1.0 - distance / max(len(word["text"]), 1)))
            if span is not None and _is_adjacent_transposition(predicted_text, word["text"]):
                transpositions += 1
        m2 = {"char_accuracy": round(statistics.fmean(char_scores), 4),
              "digit_transpositions": transpositions, "tokens": len(numeric)}
    else:
        m2 = {"char_accuracy": 1.0, "digit_transpositions": 0, "tokens": 0}

    # M5: Kendall tau between truth order and predicted reading order of matched words.
    ranks = [
        predicted.index(span)
        for _, span, overlap in matches
        if span is not None and overlap > 0
    ]
    if len(ranks) >= 2:
        concordant = discordant = 0
        for i in range(len(ranks)):
            for j in range(i + 1, len(ranks)):
                if ranks[i] == ranks[j]:
                    continue  # two truth words matched the same (merged) span
                concordant += ranks[i] < ranks[j]
                discordant += ranks[i] > ranks[j]
        pairs = concordant + discordant
        m5 = (concordant - discordant) / pairs if pairs else 0.0
    else:
        m5 = 0.0

    timings_ms.sort()
    p50 = timings_ms[len(timings_ms) // 2]
    p95 = timings_ms[min(len(timings_ms) - 1, int(len(timings_ms) * 0.95))]

    return {
        "M1_field_accuracy": round(m1, 4),
        "M2_numeric_accuracy": m2,
        "M3_mean_iou": round(m3, 4),
        # Layout M4 is measured in the layout_cells section (clustering over NATIVE
        # spans). The per-OCR-engine cell stays SKIPPED because layout-over-OCR-spans
        # is not benchmarked yet — a different, harder measurement (OCR box noise
        # feeds the clusterer), not a solved one.
        "M4_table_alignment": {"status": "SKIPPED", "reason": "LAYOUT_MEASURED_SEPARATELY_SEE_layout_cells"},
        "M5_kendall_tau": round(m5, 4),
        "M6_runtime_ms": {"p50": round(p50, 1), "p95": round(p95, 1)},
        "M7_peak_rss_bytes": _peak_rss_bytes(),
        "detected_rotation": rotation,
        "words_predicted": len(predicted),
        "words_truth": len(truth_words),
    }


#: M4 fixtures: the two constructed table grids, graded via their (row, col) truth.
LAYOUT_M4_FIXTURES = ("ruled_table", "unruled_table")


def measure_layout_m4(fixture_name: str, runs: int = 3) -> dict:
    """One layout_cells entry: ClusteringLayoutEngine over the fixture's NATIVE
    text spans (worker's own /v1/text extraction), PDF attached for ruling
    confirmation. M4 = fraction of truth cell-words whose best-IoU span landed
    in the TABLE_CELL with the matching (row, col)."""
    pdf_bytes = (FIXTURES_DIR / f"{fixture_name}.pdf").read_bytes()
    page = extract_text(pdf_bytes, [0])[0]
    spans = [
        LayoutSpan(
            ordinal=span.ordinal,
            text=span.text,
            box=span.box,
            font_size=span.font_size,
            font_name=span.font_name,
        )
        for span in page.spans
    ]
    page_spans = PageSpans(
        page_index=0, width_pt=page.width_pt, height_pt=page.height_pt, spans=spans
    )
    rulings = extract_rulings(pdf_bytes, [0]).get(0)
    engine = ClusteringLayoutEngine()

    timings_ms = []
    payload = None
    for _ in range(max(runs, 1)):
        started = time.perf_counter()
        payload = engine.analyze_page(page_spans, rulings)
        timings_ms.append((time.perf_counter() - started) * 1000.0)

    tables = [e for e in payload["elements"] if e["elementType"] == "TABLE"]
    cell_of_span: dict[int, tuple[int, int]] = {}
    for element in payload["elements"]:
        if element["elementType"] == "TABLE_CELL":
            key = (element["attributes"]["row"], element["attributes"]["col"])
            for ordinal in element["spanOrdinals"]:
                cell_of_span[ordinal] = key

    wire_spans = [span.payload() for span in page.spans]
    truth = json.loads(
        (FIXTURES_DIR / "truth" / f"{fixture_name}.json").read_text()
    )["pages"][0]
    graded = [word for word in truth["words"] if "cell" in word]
    correct = 0
    for word in graded:
        best, best_iou = None, 0.0
        for span in wire_spans:
            box = Box(span["x"], span["y"], span["width"], span["height"])
            overlap = _iou(box, word)
            if overlap > best_iou:
                best, best_iou = span, overlap
        if best is not None and cell_of_span.get(best["ordinal"]) == (
            word["cell"]["row"], word["cell"]["col"],
        ):
            correct += 1

    timings_ms.sort()
    return {
        "engine": "clustering-layout",
        "fixture": fixture_name,
        "spans": "NATIVE",
        "runs": max(runs, 1),
        "table": tables[0]["attributes"] if tables else None,
        "metrics": {
            "M4_table_alignment": {
                "status": "MEASURED",
                "value": round(correct / len(graded), 4) if graded else None,
                "correct_words": correct,
                "graded_words": len(graded),
            },
            "M6_runtime_ms": {
                "p50": round(timings_ms[len(timings_ms) // 2], 1),
                "p95": round(
                    timings_ms[min(len(timings_ms) - 1, int(len(timings_ms) * 0.95))], 1
                ),
            },
        },
    }


def run_benchmark(
    engine_names: list[str] | None = None,
    fixture_names: list[str] | None = None,
    runs: int = 3,
    output_path: Path | None = None,
) -> str:
    """Run the matrix; write results JSON; return the markdown summary."""
    catalog, skipped = _engine_catalog()
    engine_names = engine_names or list(catalog)
    fixture_names = fixture_names or list(FIXTURE_SPECS)
    output_path = Path(output_path) if output_path else BENCH_DIR / "results.json"

    unknown = [name for name in engine_names if name not in catalog]
    if unknown:
        raise SystemExit(f"unknown/unavailable engines {unknown}; available: {list(catalog)}")

    cells = []
    for engine_name in engine_names:
        load_started = time.perf_counter()
        engine = catalog[engine_name]()
        if hasattr(engine, "_load"):
            engine._load()  # RapidOCR defers the expensive part; charge it to M8
        load_ms = (time.perf_counter() - load_started) * 1000.0
        for fixture_name in fixture_names:
            metrics = _evaluate_cell(engine, FIXTURE_SPECS[fixture_name], runs)
            metrics["M8_model_load_ms"] = round(load_ms, 1)
            cells.append(
                {"engine": engine_name, "fixture": fixture_name,
                 "dpi": FIXTURE_SPECS[fixture_name].dpi, "runs": runs, "metrics": metrics}
            )

    layout_cells = [measure_layout_m4(name, runs) for name in LAYOUT_M4_FIXTURES]

    results = {
        "schema": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "platform": {"system": system(), "machine": machine(), "python": python_version()},
        "cells": cells,
        "layout_cells": layout_cells,
        "skipped": skipped
        + [
            {
                "cell": "M4_table_alignment (ocr engine cells)",
                "reason": (
                    "layout metric — measured in layout_cells via the Phase 3 "
                    "ClusteringLayoutEngine over native text spans; an OCR cell "
                    "has no layout of its own, so its placeholder stays"
                ),
            }
        ],
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(results, indent=2, sort_keys=True) + "\n")

    lines = [
        "| engine | fixture | M1 field | M2 numeric (transpo) | M3 IoU | M5 tau | M6 p50/p95 ms |",
        "|---|---|---|---|---|---|---|",
    ]
    for cell in cells:
        m = cell["metrics"]
        lines.append(
            f"| {cell['engine']} | {cell['fixture']} | {m['M1_field_accuracy']:.3f} "
            f"| {m['M2_numeric_accuracy']['char_accuracy']:.3f} "
            f"({m['M2_numeric_accuracy']['digit_transpositions']}) "
            f"| {m['M3_mean_iou']:.3f} | {m['M5_kendall_tau']:.3f} "
            f"| {m['M6_runtime_ms']['p50']:.0f}/{m['M6_runtime_ms']['p95']:.0f} |"
        )
    lines.append("")
    lines.append("| layout engine | fixture | M4 cell alignment | table | M6 p50/p95 ms |")
    lines.append("|---|---|---|---|---|")
    for cell in layout_cells:
        m4 = cell["metrics"]["M4_table_alignment"]
        runtime = cell["metrics"]["M6_runtime_ms"]
        lines.append(
            f"| {cell['engine']} | {cell['fixture']} "
            f"| {m4['value']:.3f} ({m4['correct_words']}/{m4['graded_words']}) "
            f"| {cell['table']} | {runtime['p50']:.1f}/{runtime['p95']:.1f} |"
        )
    for entry in results["skipped"]:
        lines.append(f"| SKIPPED: {entry['cell']} — {entry['reason']} |")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engines", nargs="*", default=None)
    parser.add_argument("--fixtures", nargs="*", default=None)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--output", type=Path, default=BENCH_DIR / "results.json")
    args = parser.parse_args()
    summary = run_benchmark(args.engines, args.fixtures, args.runs, args.output)
    print(summary)
    print(f"\nresults written to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
