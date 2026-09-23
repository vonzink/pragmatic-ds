"""Benchmark M4 — Phase 3 turns the M4 cell from SKIPPED into a measurement:
the layout engine over NATIVE text spans of the two table fixtures (layout is
what M4 grades; OCR engines stay out of it). The OCR-engine cells keep their
per-cell placeholder — an OCR cell has no layout of its own — and the run-level
skip note now points at the measured layout cells."""

import importlib.util
import json
from pathlib import Path

import pytest

BENCH_PATH = Path(__file__).resolve().parents[2] / "bench" / "benchmark.py"


@pytest.fixture(scope="module")
def bench():
    spec = importlib.util.spec_from_file_location("pds_bench_layout", BENCH_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TestLayoutM4Cells:
    def test_ruled_table_m4_is_measured_and_exact(self, bench):
        cell = bench.measure_layout_m4("ruled_table", runs=1)

        metrics = cell["metrics"]["M4_table_alignment"]
        assert metrics["status"] == "MEASURED"
        assert metrics["value"] == 1.0
        assert metrics["graded_words"] == 25
        assert cell["table"] == {"rows": 5, "cols": 5, "ruled": True}
        assert cell["engine"] == "clustering-layout"
        assert cell["spans"] == "NATIVE"

    def test_unruled_table_m4_is_measured(self, bench):
        cell = bench.measure_layout_m4("unruled_table", runs=1)

        metrics = cell["metrics"]["M4_table_alignment"]
        assert metrics["status"] == "MEASURED"
        assert metrics["value"] >= 0.9  # the published floor; report reality
        assert cell["table"]["ruled"] is False

    def test_results_json_gains_layout_cells_without_touching_ocr_cells(
        self, bench, tmp_path
    ):
        output = tmp_path / "results.json"

        bench.run_benchmark(
            engine_names=["tesseract"],
            fixture_names=["scanned_200"],
            runs=1,
            output_path=output,
        )

        data = json.loads(output.read_text())
        assert len(data["layout_cells"]) == 2
        fixtures = {cell["fixture"] for cell in data["layout_cells"]}
        assert fixtures == {"ruled_table", "unruled_table"}
        # The OCR cell keeps its placeholder shape (pinned by the ocr suite).
        assert data["cells"][0]["metrics"]["M4_table_alignment"] == {
            "status": "SKIPPED",
            "reason": "LAYOUT_MEASURED_SEPARATELY_SEE_layout_cells",
        }
        # The run-level note now says where M4 really lives.
        m4_notes = [e for e in data["skipped"] if "M4" in e["cell"]]
        assert len(m4_notes) == 1
        assert "layout_cells" in m4_notes[0]["reason"]
