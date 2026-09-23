"""Smoke test for the PARSER_EVALUATION.md §10 benchmark harness.

Full matrix runs are manual (they take minutes); this proves the harness runs end to
end on ONE fixture with ONE engine and emits the committed output shape — including
the honesty rules: unavailable model sets are SKIPPED with a reason, never
substituted. Per-OCR-cell M4 stays SKIPPED (layout-over-OCR-spans is unbenchmarked);
layout M4 lives in the layout_cells section, measured over NATIVE spans.
"""

import importlib.util
import json
from pathlib import Path

import pytest

BENCH_PATH = Path(__file__).resolve().parents[2] / "bench" / "benchmark.py"


@pytest.fixture(scope="module")
def bench():
    spec = importlib.util.spec_from_file_location("pds_bench", BENCH_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TestModelSetDetection:
    def test_installed_rapidocr_exposes_v4_only(self, bench):
        available = bench.available_rapidocr_model_sets()
        assert "PP-OCRv4" in available
        assert "PP-OCRv5" not in available
        assert "PP-OCRv6" not in available


@pytest.fixture(scope="module")
def results(bench, tmp_path_factory):
    output = tmp_path_factory.mktemp("bench") / "results.json"
    summary = bench.run_benchmark(
        engine_names=["rapidocr-ppocrv4"],
        fixture_names=["scanned_200"],
        runs=1,
        output_path=output,
    )
    return json.loads(output.read_text()), summary


class TestSmokeRun:
    def test_results_json_written_with_cells_and_skips(self, results):
        data, _ = results
        assert data["schema"] == 1
        assert len(data["cells"]) == 1
        skipped_names = {entry["cell"] for entry in data["skipped"]}
        assert any("PP-OCRv5" in name for name in skipped_names)
        assert any("PP-OCRv6" in name for name in skipped_names)
        for entry in data["skipped"]:
            assert entry["reason"]

    def test_cell_carries_every_metric(self, results):
        data, _ = results
        cell = data["cells"][0]
        assert cell["engine"] == "rapidocr-ppocrv4"
        assert cell["fixture"] == "scanned_200"
        metrics = cell["metrics"]
        assert 0.0 <= metrics["M1_field_accuracy"] <= 1.0
        assert 0.0 <= metrics["M2_numeric_accuracy"]["char_accuracy"] <= 1.0
        assert metrics["M2_numeric_accuracy"]["digit_transpositions"] >= 0
        assert 0.0 <= metrics["M3_mean_iou"] <= 1.0
        assert -1.0 <= metrics["M5_kendall_tau"] <= 1.0
        assert metrics["M6_runtime_ms"]["p50"] > 0
        assert metrics["M6_runtime_ms"]["p95"] >= metrics["M6_runtime_ms"]["p50"]
        assert metrics["M7_peak_rss_bytes"] > 0
        assert metrics["M8_model_load_ms"] >= 0
        assert metrics["M4_table_alignment"] == {
            "status": "SKIPPED",
            "reason": "LAYOUT_MEASURED_SEPARATELY_SEE_layout_cells",
        }

    def test_markdown_summary_contains_the_matrix(self, results):
        _, summary = results
        assert "| engine" in summary.lower()
        assert "rapidocr-ppocrv4" in summary
        assert "SKIPPED" in summary  # v5/v6 cells reported, not hidden

    def test_a_clean_scan_scores_high_on_numeric_accuracy(self, results):
        """Sanity: the M2 gate metric is meaningful, not a constant."""
        data, _ = results
        m2 = data["cells"][0]["metrics"]["M2_numeric_accuracy"]
        assert m2["char_accuracy"] > 0.8
