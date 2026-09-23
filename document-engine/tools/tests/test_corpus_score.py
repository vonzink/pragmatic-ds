"""Report-formatting tests for tools/corpus_score.py — canned JSON, no network.

The HTTP half of the script is the Spec 2 smoke pattern (stdlib urllib against a
locally running stack) and is exercised by actually running it; everything that
TRANSFORMS a response into report text is pure and pinned here, so a wire-shape
drift breaks a test before it silently breaks a pack-merge review.
"""

import json

from tools import corpus_score
from tools.corpus_score import (
    FIELD_LABEL_MAPS,
    KeyMatcher,
    VerdictCounts,
    anchor_ids,
    document_report,
    evidence_by_page,
    field_line,
    flatten_key,
    load_answer_key,
    package_report,
    page_line,
    shape_hint,
    values_equivalent,
    verdict_of,
)


def test_main_forwards_timeout_and_continues_after_one_document_times_out(
        monkeypatch, tmp_path, capsys):
    slow = tmp_path / "slow.pdf"
    healthy = tmp_path / "healthy.pdf"
    slow.write_bytes(b"%PDF-synthetic-slow")
    healthy.write_bytes(b"%PDF-synthetic-healthy")
    calls = []

    def fake_score(api, pdf, timeout=300.0, totals=None):
        calls.append((api, pdf.name, timeout))
        if pdf == slow:
            raise TimeoutError("synthetic timeout")
        return "== healthy.pdf -> COMPLETED"

    monkeypatch.setattr(corpus_score, "score", fake_score)

    result = corpus_score.main([
        str(slow),
        str(healthy),
        "--api",
        "http://engine.test",
        "--timeout",
        "17",
    ])

    captured = capsys.readouterr()
    assert result == 1
    assert calls == [
        ("http://engine.test", "slow.pdf", 17.0),
        ("http://engine.test", "healthy.pdf", 17.0),
    ]
    assert "slow.pdf -> NOT SCORED (TimeoutError: synthetic timeout)" in captured.out
    assert "slow.pdf -> NOT SCORED (TimeoutError: synthetic timeout)" in captured.err
    assert "healthy.pdf -> COMPLETED" in captured.out


def test_main_continues_after_one_document_has_a_network_failure(
        monkeypatch, tmp_path, capsys):
    unavailable = tmp_path / "unavailable.pdf"
    healthy = tmp_path / "healthy.pdf"
    unavailable.write_bytes(b"%PDF-synthetic-unavailable")
    healthy.write_bytes(b"%PDF-synthetic-healthy")
    attempted = []

    def fake_score(api, pdf, timeout=300.0, totals=None):
        attempted.append((api, pdf.name, timeout))
        if pdf == unavailable:
            raise corpus_score.urllib.error.URLError("synthetic network failure")
        return "== healthy.pdf -> COMPLETED"

    monkeypatch.setattr(corpus_score, "score", fake_score)

    result = corpus_score.main([
        str(unavailable),
        str(healthy),
        "--api",
        "http://engine.test",
        "--timeout",
        "17",
    ])

    captured = capsys.readouterr()
    assert result == 1
    assert attempted == [
        ("http://engine.test", "unavailable.pdf", 17.0),
        ("http://engine.test", "healthy.pdf", 17.0),
    ]
    expected = "unavailable.pdf -> NOT SCORED (URLError: <urlopen error synthetic network failure>)"
    assert expected in captured.out
    assert expected in captured.err
    assert "healthy.pdf -> COMPLETED" in captured.out


def test_score_forwards_its_non_default_timeout_to_job_waiting(
        monkeypatch, tmp_path):
    pdf = tmp_path / "healthy.pdf"
    pdf.write_bytes(b"%PDF-synthetic-healthy")
    wait_calls = []

    monkeypatch.setattr(
        corpus_score,
        "upload",
        lambda api, uploaded_pdf: {"jobId": "job-7", "packageId": "package-7"},
    )

    def fake_wait(api, job_id, timeout=300.0):
        wait_calls.append((api, job_id, timeout))
        return "COMPLETED"

    def fake_req(api, method, path, data=None, ctype=None):
        if path == "/v1/packages/package-7/documents":
            return {
                "packageId": "package-7",
                "documents": [],
                "unassignedPages": [],
            }
        if path == "/v1/packages/package-7/classification":
            return {"packageId": "package-7", "pages": []}
        raise AssertionError(f"unexpected request: {method} {path}")

    monkeypatch.setattr(corpus_score, "wait", fake_wait)
    monkeypatch.setattr(corpus_score, "req", fake_req)

    report = corpus_score.score("http://engine.test", pdf, timeout=17.0)

    assert wait_calls == [("http://engine.test", "job-7", 17.0)]
    assert report == ("== healthy.pdf -> COMPLETED\n"
                      "  (no answer key — capture-only report)")

# The classification_result.evidence shape (PageClassifier.evidenceJson), served
# verbatim by GET /v1/packages/{id}/classification: anchors carry ids/weights/
# span ids/boxes/offsets — never matched text. The duplicate form-1040 entry
# below must not double-print: one anchor is one piece of evidence.
EVIDENCE = {
    "anchors": [
        {"packType": "TAX_RETURN", "packVersion": "1.0.0", "anchorId": "form-1040",
         "weight": 4, "spanIds": [11, 12], "boxes": [], "range": {"start": 0, "end": 9}},
        {"packType": "TAX_RETURN", "packVersion": "1.0.0", "anchorId": "treasury-irs",
         "weight": 3, "spanIds": [13], "boxes": [], "range": {"start": 12, "end": 55}},
        {"packType": "TAX_RETURN", "packVersion": "1.0.0", "anchorId": "form-1040",
         "weight": 4, "spanIds": [11], "boxes": [], "range": {"start": 0, "end": 9}},
    ],
    "scores": [
        {"packType": "TAX_RETURN", "packVersion": "1.0.0", "score": 0.9,
         "minConfidence": 0.6, "targetScore": 10},
    ],
}


def test_anchor_ids_are_pack_qualified_deduplicated_and_ordered():
    assert anchor_ids(EVIDENCE) == ["TAX_RETURN:form-1040", "TAX_RETURN:treasury-irs"]


def test_anchor_ids_of_empty_or_anchorless_evidence():
    assert anchor_ids({}) == []
    assert anchor_ids({"anchors": [], "scores": []}) == []


def test_evidence_by_page_reads_the_endpoint_shape():
    payload = {"packageId": "pkg1", "pages": [
        {"pageId": "p0", "packagePageIndex": 0, "documentTypeCode": "TAX_RETURN",
         "confidence": 0.9, "rulePackVersion": "1.0.0", "evidence": EVIDENCE},
        {"pageId": "p1", "packagePageIndex": 1, "documentTypeCode": "UNKNOWN",
         "confidence": 0.2, "rulePackVersion": None, "evidence": {}},
    ]}
    by_page = evidence_by_page(payload)
    assert set(by_page) == {0, 1}
    assert anchor_ids(by_page[0]) == ["TAX_RETURN:form-1040", "TAX_RETURN:treasury-irs"]
    assert anchor_ids(by_page[1]) == []


def test_evidence_by_page_of_an_absent_endpoint_is_empty():
    # classification_of() returns None on a pre-Spec-3 stack (no GET route);
    # the report must degrade to type+confidence, not crash.
    assert evidence_by_page(None) == {}
    assert evidence_by_page({}) == {}


def test_page_line_carries_index_type_confidence_and_anchors():
    line = page_line(6, "TAX_RETURN", 0.9, ["TAX_RETURN:form-1040"])
    assert "page   6" in line
    assert "TAX_RETURN" in line
    assert "0.90" in line
    assert "[TAX_RETURN:form-1040]" in line


def test_page_line_shows_a_dash_when_no_anchors_are_known():
    assert "[-]" in page_line(0, "UNKNOWN", 0.2, [])


def test_field_line_passes_the_server_masked_value_through_untouched():
    line = field_line({
        "fieldName": "primarySsn", "displayedText": "•••-••-6789",
        "extractionMethod": "ANCHOR_LABEL", "confidence": 0.81, "sensitive": True,
    })
    assert "primarySsn" in line
    assert "•••-••-6789" in line
    assert "(sensitive, masked by server)" in line
    assert "conf=0.81" in line


def test_field_line_shows_a_missing_field_as_missing():
    line = field_line({
        "fieldName": "spouseName", "displayedText": None,
        "extractionMethod": "NONE", "confidence": 0, "sensitive": False,
    })
    assert "(missing)" in line
    assert "NONE" in line
    assert "conf=0.00" in line


def test_field_line_names_the_occurrence_of_a_grouped_field():
    # Spec 5a: three rents under one name are indistinguishable in a report keyed by name.
    # The key printed on the form is what makes the line actionable — a reviewer reads
    # "property B" and finds column B on the page.
    line = field_line({
        "fieldName": "rentsReceived", "groupKey": "B", "displayedText": "29,700",
        "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9, "sensitive": False,
    })
    assert "rentsReceived[B]" in line
    assert "29,700" in line


def test_field_line_of_an_ungrouped_field_is_unchanged():
    # Every pre-Spec-5a field reports groupKey null, and its line must not grow a bracket.
    line = field_line({
        "fieldName": "netPay", "groupKey": None, "displayedText": "3,105.87",
        "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9, "sensitive": False,
    })
    assert "netPay " in line
    assert "[" not in line


def test_field_line_survives_a_response_with_no_group_key_at_all():
    # A stack older than Spec 5a serves no groupKey property; the report degrades, never crashes.
    line = field_line({
        "fieldName": "netPay", "displayedText": "3,105.87",
        "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9, "sensitive": False,
    })
    assert "netPay " in line
    assert "[" not in line


def test_document_report_merges_documents_view_evidence_and_fields():
    document = {
        "id": "d1", "ordinal": 0, "documentTypeCode": "TAX_RETURN",
        "classificationConfidence": 0.9,
        "pages": [{"pageId": "p1", "packagePageIndex": 6,
                   "classification": {"type": "TAX_RETURN", "confidence": 0.9,
                                      "rulePackVersion": "1.0.0"}}],
    }
    classification = {"packageId": "pkg1",
                      "pages": [{"packagePageIndex": 6, "evidence": EVIDENCE}]}
    fields_view = {"fields": [
        {"fieldName": "filingStatus", "displayedText": "MARRIED_FILING_JOINTLY",
         "extractionMethod": "CHECKBOX_STATE", "confidence": 0.86, "sensitive": False},
    ]}

    lines = document_report(document, classification, fields_view)

    assert lines[0] == "document 1: TAX_RETURN (0.90)"
    assert "TAX_RETURN:form-1040" in lines[1]  # the page line right after the header
    assert any("filingStatus" in line and "CHECKBOX_STATE" in line for line in lines)


def test_document_report_of_a_human_shaped_document_says_human():
    # Spec 2: a human-regrouped document has NULL confidence — print it honestly.
    document = {"id": "d1", "ordinal": 1, "documentTypeCode": "W2",
                "classificationConfidence": None, "pages": []}
    assert document_report(document, None, None)[0] == "document 2: W2 (human)"


def test_package_report_lists_every_document_and_unassigned_page():
    documents_view = {
        "packageId": "pkg1",
        "documents": [
            {"id": "d1", "ordinal": 0, "documentTypeCode": "W2",
             "classificationConfidence": 1.0,
             "pages": [{"pageId": "p0", "packagePageIndex": 0,
                        "classification": {"type": "W2", "confidence": 1.0,
                                           "rulePackVersion": "1.1.0"}}]},
        ],
        "unassignedPages": [
            {"pageId": "p7", "packagePageIndex": 7, "reason": "BLANK"},
            {"pageId": "p8", "packagePageIndex": 8, "reason": "DUPLICATE"},
        ],
    }

    report = package_report("combined_package_v2.pdf", "HUMAN_REVIEW_REQUIRED",
                            documents_view, None, {"d1": None})

    lines = report.splitlines()
    assert lines[0] == "== combined_package_v2.pdf -> HUMAN_REVIEW_REQUIRED"
    assert lines[1] == "document 1: W2 (1.00)"
    assert "unassigned (BLANK)" in report
    assert "unassigned (DUPLICATE)" in report


# ── answer-key verification (all data synthetic — no corpus values) ──────────
#
# Two documented failures motivate this section: the original script never read
# the answer keys at all (capture looked like correctness), and the one ad-hoc
# checker that did read them keyed leaves by bare name, so same-named leaves
# overwrote each other and a clean merge was called a REGRESSION.


def test_flatten_key_keeps_the_same_leaf_name_under_three_parents_distinct():
    # The coordinator's bug, pinned: three "name" leaves under three parents
    # must stay three entries with three values — never collapse to one.
    key = {
        "borrower": {"name": "Alpha Person"},
        "coBorrower": {"name": "Beta Person"},
        "employer": {"name": "Gamma Fabrication LLC"},
    }
    leaves = flatten_key(key)
    assert leaves[("borrower", "name")] == "Alpha Person"
    assert leaves[("coBorrower", "name")] == "Beta Person"
    assert leaves[("employer", "name")] == "Gamma Fabrication LLC"
    assert len(leaves) == 3


def test_flatten_key_keys_array_elements_by_index():
    key = {"rows": [{"amount": "10"}, {"amount": "20"}]}
    leaves = flatten_key(key)
    assert leaves[("rows", 0, "amount")] == "10"
    assert leaves[("rows", 1, "amount")] == "20"


def test_same_leaf_name_under_three_parents_scores_each_against_its_own_value():
    # End-to-end over the bug: a bare-name walk would bless coBorrowerName with
    # whichever "name" survived the overwrite. Path-correct scoring must not.
    key = {
        "borrower": {"name": "Alpha Person"},
        "coBorrower": {"name": "Beta Person"},
        "employer": {"name": "Gamma Fabrication LLC"},
    }
    matcher = KeyMatcher(key)
    verdicts = [verdict_of(field, matcher)[0] for field in (
        {"fieldName": "employerName", "displayedText": "Gamma Fabrication LLC"},
        {"fieldName": "borrowerName", "displayedText": "Alpha Person"},
        {"fieldName": "coBorrowerName", "displayedText": "Alpha Person"},
    )]
    assert verdicts == ["MATCH", "MATCH", "MISMATCH"]


def test_money_comparison_is_numeric_and_sign_aware():
    assert values_equivalent("( 18,470 )", "-18470")
    assert values_equivalent("-18,470.00", "( 18,470 )")
    assert values_equivalent("$1,234.56", "1234.56")
    assert values_equivalent("29,700", "29700.00")
    assert not values_equivalent("18,470", "-18,470")
    assert not values_equivalent("29,700", "29,701")


def test_date_comparison_is_iso_normalized():
    assert values_equivalent("01/05/2026", "2026-01-05")
    assert values_equivalent("Jan 5, 2026", "2026-01-05")
    assert not values_equivalent("01/05/2026", "2026-05-01")


def test_string_comparison_collapses_whitespace_but_preserves_case():
    assert values_equivalent("Acme  Widgets\n LLC", "Acme Widgets LLC")
    assert not values_equivalent("ACME WIDGETS LLC", "Acme Widgets LLC")


def test_shape_hint_reveals_shape_never_characters():
    assert shape_hint("29,700") == "99,999"
    assert shape_hint("Acme LLC") == "AAAA AAA"
    assert shape_hint("( 18,470 )") == "( 99,999 )"
    assert len(shape_hint("A" * 80)) <= 25  # truncated — a hint, not a transcript


def test_masked_values_skip_never_mismatch():
    key = {"fields": [{"label": "Primary SSN", "value": "999-88-7777", "page": 1}]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "primarySsn", "displayedText": "•••-••-7777", "sensitive": True},
        matcher)
    assert verdict == "MASKED_SKIP"
    # the key entry is consumed: a masked field must not resurface as MISSING_EXPECTED
    assert matcher.leftovers() == []


def test_masked_looking_text_skips_even_without_the_sensitive_flag():
    key = {"fields": [{"label": "Account number", "value": "12345678", "page": 1}]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "accountNumber", "displayedText": "****5678"}, matcher)
    assert verdict == "MASKED_SKIP"


def test_grouped_field_matches_the_key_entry_for_its_own_occurrence():
    key = {"fields": [
        {"label": "Rents received", "line": "3", "value": "1,100", "page": 1,
         "propertyColumn": "A"},
        {"label": "Rents received", "line": "3", "value": "2,200", "page": 1,
         "propertyColumn": "B"},
    ]}
    matcher = KeyMatcher(key)
    right_column, _ = verdict_of(
        {"fieldName": "rentsReceived", "groupKey": "B", "displayedText": "2,200"}, matcher)
    wrong_column, _ = verdict_of(
        {"fieldName": "rentsReceived", "groupKey": "A", "displayedText": "2,200"}, matcher)
    assert right_column == "MATCH"      # B's capture against B's key entry
    assert wrong_column == "MISMATCH"   # B's value in column A is WRONG, not a match


def test_zero_padded_row_ordinals_map_to_numeric_key_rows():
    key = {"fields": [
        {"label": "Partner name", "value": "Delta Partner", "page": 1, "row": "1"},
        {"label": "Partner name", "value": "Epsilon Partner", "page": 1, "row": "2"},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "partnerName", "groupKey": "02",
         "displayedText": "Epsilon Partner"}, matcher)
    assert verdict == "MATCH"


def test_group_summary_layout_maps_occurrences_by_column_discriminator():
    # The keys' other shape: a top-level array of per-occurrence dicts whose
    # discriminator property carries the printed column letter.
    key = {
        "form": "Synthetic Schedule",
        "properties": [
            {"column": "A", "rents": "1,100"},
            {"column": "B", "rents": "2,200"},
        ],
    }
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "propertiesRents", "groupKey": "B", "displayedText": "2,200"},
        matcher)
    assert verdict == "MATCH"


def test_missing_expected_captured_unexpected_unmapped_capture_and_not_in_key():
    # Coordinator's spec correction: a FOUND value the key cannot account for is
    # the phantom-occurrence signature — LOUD (UNMAPPED_CAPTURE, exit-failing).
    # NOT_IN_KEY, the neutral class, is reserved for MISSING occurrences of
    # uncovered fields, which assert nothing.
    key = {"fields": [
        {"label": "Wages", "value": "55,000", "page": 1},
        {"label": "Tips", "value": "", "page": 1},
    ]}
    matcher = KeyMatcher(key)
    missing, missing_detail = verdict_of(
        {"fieldName": "wages", "displayedText": None}, matcher)
    unexpected, _ = verdict_of({"fieldName": "tips", "displayedText": "1,000"}, matcher)
    loud, _ = verdict_of({"fieldName": "bonus", "displayedText": "500"}, matcher)
    silent, _ = verdict_of({"fieldName": "extraBonus", "displayedText": None}, matcher)
    assert missing == "MISSING_EXPECTED"
    assert "55,000" not in (missing_detail or "")   # shape and length only, never the value
    assert unexpected == "CAPTURED_UNEXPECTED"
    assert loud == "UNMAPPED_CAPTURE"
    assert silent == "NOT_IN_KEY"


def test_uncaptured_key_entries_are_left_over_as_missing_expected():
    key = {"fields": [
        {"label": "Wages", "value": "55,000", "page": 1},
        {"label": "Federal tax withheld", "value": "4,200", "page": 1},
    ]}
    matcher = KeyMatcher(key)
    verdict_of({"fieldName": "wages", "displayedText": "55,000"}, matcher)
    leftovers = matcher.leftovers()
    assert len(leftovers) == 1
    assert leftovers[0].name == "Federal tax withheld"


def test_keyed_package_report_prints_verdicts_and_never_borrower_values():
    # SCHEDULE_C has no mapping table, so this pins the fuzzy-bridge path;
    # a mapped type (W2/TAX_RETURN/SCHEDULE_E) would refuse these field names.
    documents_view = {
        "packageId": "pkg1",
        "documents": [
            {"id": "d1", "ordinal": 0, "documentTypeCode": "SCHEDULE_C",
             "classificationConfidence": 0.9,
             "pages": [{"pageId": "p0", "packagePageIndex": 0,
                        "classification": {"type": "SCHEDULE_C", "confidence": 0.9,
                                           "rulePackVersion": "1.0.0"}}]},
        ],
        "unassignedPages": [],
    }
    fields_view = {"fields": [
        {"fieldName": "rentsReceived", "groupKey": "A", "displayedText": "1,150",
         "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9, "sensitive": False},
        {"fieldName": "rentsReceived", "groupKey": "B", "displayedText": "2,200",
         "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9, "sensitive": False},
    ]}
    key = {"fields": [
        {"label": "Rents received", "value": "1,100", "page": 1, "propertyColumn": "A"},
        {"label": "Rents received", "value": "2,200", "page": 1, "propertyColumn": "B"},
        {"label": "Total income", "value": "3,300", "page": 1},
    ]}
    totals = VerdictCounts()

    report = package_report("synthetic.pdf", "COMPLETED", documents_view, None,
                            {"d1": fields_view}, answer_key=key, totals=totals)
    lines = report.splitlines()

    assert any("rentsReceived[A]" in line and " MISMATCH " in line for line in lines)
    assert any("rentsReceived[B]" in line and " MATCH " in line for line in lines)
    assert any("Total income" in line and "MISSING_EXPECTED" in line for line in lines)
    # the displayed-text column is REPLACED by the verdict: no captured or
    # expected value may appear anywhere — the corpus holds real borrower documents.
    for value in ("1,150", "1,100", "2,200", "3,300"):
        assert value not in report
    assert "len=5" in report and "shape=9,999" in report
    assert ("summary: 1 match / 1 mismatch / 0 unmapped-capture / "
            "1 missing-expected / 0 masked") in report
    assert totals.match == 1 and totals.mismatch == 1 and totals.missing_expected == 1
    assert totals.unmapped_capture == 0
    assert "no answer key" not in report


def test_package_report_without_a_key_keeps_captures_and_says_capture_only():
    documents_view = {
        "packageId": "pkg1",
        "documents": [
            {"id": "d1", "ordinal": 0, "documentTypeCode": "PAYSTUB",
             "classificationConfidence": 1.0, "pages": []},
        ],
        "unassignedPages": [],
    }
    fields_view = {"fields": [
        {"fieldName": "netPay", "displayedText": "3,105.87",
         "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9, "sensitive": False},
    ]}

    report = package_report("synthetic.pdf", "COMPLETED", documents_view, None,
                            {"d1": fields_view})

    assert "no answer key — capture-only report" in report
    assert "3,105.87" in report          # capture-only output is exactly what it was
    assert "MATCH" not in report and "summary:" not in report


def test_main_exit_is_nonzero_on_mismatch_and_prints_the_roll_up(
        monkeypatch, tmp_path, capsys):
    pdf = tmp_path / "scored.pdf"
    pdf.write_bytes(b"%PDF-synthetic")

    def fake_score(api, pdf_path, timeout=300.0, totals=None):
        totals.match += 2
        totals.mismatch += 1
        totals.documents += 1
        totals.packages += 1
        return "== scored.pdf -> COMPLETED"

    monkeypatch.setattr(corpus_score, "score", fake_score)

    assert corpus_score.main([str(pdf)]) == 1
    out = capsys.readouterr().out
    assert "roll-up" in out
    assert "2 match / 1 mismatch" in out


def test_main_missing_expected_fails_only_under_strict(monkeypatch, tmp_path):
    # Wrong is worse than missing: MISSING_EXPECTED is reported but does not
    # fail the exit code unless --strict asks it to.
    pdf = tmp_path / "scored.pdf"
    pdf.write_bytes(b"%PDF-synthetic")

    def fake_score(api, pdf_path, timeout=300.0, totals=None):
        totals.match += 1
        totals.missing_expected += 3
        totals.documents += 1
        totals.packages += 1
        return "== scored.pdf -> COMPLETED"

    monkeypatch.setattr(corpus_score, "score", fake_score)

    assert corpus_score.main([str(pdf)]) == 0
    assert corpus_score.main([str(pdf), "--strict"]) == 1


def test_load_answer_key_is_none_when_absent(tmp_path):
    assert load_answer_key(tmp_path / "orphan.pdf") is None


def test_score_reads_the_answer_key_beside_the_pdf(monkeypatch, tmp_path):
    pdf = tmp_path / "w2_synthetic.pdf"
    pdf.write_bytes(b"%PDF-synthetic")
    # W2 is a mapped type, so this key must speak the real key dialect: the
    # IRS box caption, bridged to the engine name by FIELD_LABEL_MAPS["W2"].
    (tmp_path / "w2_synthetic.answers.json").write_text(json.dumps({
        "file": "w2_synthetic.pdf",
        "fields": [{"label": "Wages, tips, other compensation",
                    "value": "55,000", "page": 1}],
    }), encoding="utf-8")

    monkeypatch.setattr(
        corpus_score, "upload",
        lambda api, uploaded: {"jobId": "job-9", "packageId": "package-9"})
    monkeypatch.setattr(
        corpus_score, "wait", lambda api, job_id, timeout=300.0: "COMPLETED")

    def fake_req(api, method, path, data=None, ctype=None):
        if path == "/v1/packages/package-9/documents":
            return {"packageId": "package-9",
                    "documents": [{"id": "d1", "ordinal": 0, "documentTypeCode": "W2",
                                   "classificationConfidence": 1.0, "pages": []}],
                    "unassignedPages": []}
        if path == "/v1/packages/package-9/classification":
            return {"packageId": "package-9", "pages": []}
        if path == "/v1/documents/d1/fields":
            return {"fields": [{"fieldName": "wagesTipsOtherComp",
                                "displayedText": "55,000",
                                "extractionMethod": "ANCHOR_LABEL", "confidence": 0.95,
                                "sensitive": False}]}
        raise AssertionError(f"unexpected request: {method} {path}")

    monkeypatch.setattr(corpus_score, "req", fake_req)

    totals = VerdictCounts()
    report = corpus_score.score("http://engine.test", pdf, timeout=17.0, totals=totals)

    assert " MATCH " in report
    assert totals.match == 1 and totals.mismatch == 0
    assert "55,000" not in report        # even a matching value is never printed
    assert "no answer key" not in report


# ── explicit field mapping, UNMAPPED_CAPTURE, NOT_DECLARED (coordinator's
#    spec correction after the first live run scored wrong values exit-0) ─────


def test_mapped_type_bridges_engine_names_to_irs_labels():
    # Label normalization cannot bridge "Mortgage interest paid to banks, etc."
    # to mortgageInterest — the explicit SCHEDULE_E table must.
    key = {"fields": [
        {"label": "Mortgage interest paid to banks, etc.", "line": "12",
         "value": "2,750", "page": 0, "propertyColumn": "A"},
        {"label": "Total expenses. Add lines 5 through 19", "line": "20",
         "value": "9,100", "page": 0, "propertyColumn": "A"},
    ]}
    matcher = KeyMatcher(key)
    label_map = FIELD_LABEL_MAPS["SCHEDULE_E"]
    bridged, _ = verdict_of(
        {"fieldName": "mortgageInterest", "groupKey": "A", "displayedText": "2,750"},
        matcher, label_map)
    still_wrong, _ = verdict_of(
        {"fieldName": "totalExpenses", "groupKey": "A", "displayedText": "9,050"},
        matcher, label_map)
    assert bridged == "MATCH"
    assert still_wrong == "MISMATCH"    # the mapping bridges names, never values


def test_tax_year_maps_to_the_keys_form_year_scalar():
    # Keys carry the year as top-level formYear, not as a fields[] entry.
    key = {"file": "synthetic.pdf", "formYear": "2031", "fields": [
        {"label": "Rents received", "value": "1,100", "page": 0, "propertyColumn": "A"},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "taxYear", "displayedText": "2031"},
        matcher, FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert verdict == "MATCH"


def test_phantom_capture_of_a_mapped_field_is_unmapped_capture_never_neutral():
    # The row-band defect's exact signature: a FOUND entity name in a part the
    # key never lists. Must be loud, never the neutral NOT_IN_KEY.
    key = {"fields": [
        {"label": "Rents received", "value": "1,100", "page": 0, "propertyColumn": "A"},
    ]}
    matcher = KeyMatcher(key)
    label_map = FIELD_LABEL_MAPS["SCHEDULE_E"]
    found, _ = verdict_of(
        {"fieldName": "estateOrTrustName", "groupKey": "01",
         "displayedText": "Phantom Trust"}, matcher, label_map)
    silent, _ = verdict_of(
        {"fieldName": "remicName", "groupKey": "01", "displayedText": None},
        matcher, label_map)
    assert found == "UNMAPPED_CAPTURE"
    assert silent == "NOT_IN_KEY"       # a MISSING occurrence asserts nothing


def test_mapped_type_never_falls_back_to_label_normalization():
    # "advertising" would norm-match the key's "Advertising" — but SCHEDULE_E is
    # a mapped type and advertising is not in its table: no fuzzy bridge, ever.
    key = {"fields": [
        {"label": "Advertising", "value": "300", "page": 0, "propertyColumn": "A"},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "advertising", "groupKey": "A", "displayedText": "300"},
        matcher, FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert verdict == "UNMAPPED_CAPTURE"
    # and the key entry it refused to consume is not "declared" either — it
    # belongs in the schema-coverage bucket, not missing-expected
    assert len(matcher.leftovers()) == 1


def test_masked_capture_suppresses_duplicate_key_entries():
    # Name/SSN appear on every page of the key; one masked capture makes the
    # field unverifiable EVERYWHERE — the page-2 copy must not resurface as
    # MISSING_EXPECTED.
    key = {"fields": [
        {"label": "Your social security number", "value": "999-88-7777", "page": 0},
        {"label": "Your social security number", "value": "999-88-7777", "page": 1},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "taxpayerSsn", "displayedText": "•••-••-7777", "sensitive": True},
        matcher, FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert verdict == "MASKED_SKIP"
    assert matcher.leftovers() == []


def test_not_declared_key_entries_split_from_missing_expected():
    documents_view = {
        "packageId": "pkg1",
        "documents": [
            {"id": "d1", "ordinal": 0, "documentTypeCode": "SCHEDULE_E",
             "classificationConfidence": 0.9, "pages": []},
        ],
        "unassignedPages": [],
    }
    key = {"fields": [
        {"label": "Physical address of each property (street, city, state, ZIP code)",
         "line": "1a", "value": "12 SYNTH ST", "page": 0, "propertyColumn": "A"},
        {"label": "Advertising", "line": "5", "value": "300", "page": 0,
         "propertyColumn": "A"},
        {"label": "Fair Rental Days", "line": "2", "value": "365", "page": 0,
         "propertyColumn": "A"},
    ]}
    totals = VerdictCounts()

    report = package_report("synthetic.pdf", "COMPLETED", documents_view, None,
                            {"d1": {"fields": []}}, answer_key=key, totals=totals)
    lines = report.splitlines()

    # the declared-but-failed field stays a prominent MISSING_EXPECTED line
    assert any("Physical address" in line and "MISSING_EXPECTED" in line
               for line in lines)
    # undeclared key entries collapse into ONE schema-coverage line
    assert any(line.strip().startswith("schema-coverage: 2 key entries")
               and "Advertising" in line and "Fair Rental Days" in line
               for line in lines)
    assert totals.missing_expected == 1 and totals.not_declared == 2
    assert "summary: 0 match / 0 mismatch / 0 unmapped-capture / "\
           "1 missing-expected / 0 masked" in report
    # values still never printed, in either bucket
    for value in ("12 SYNTH ST", "300", "365"):
        assert value not in report


def test_main_unmapped_capture_fails_exit_and_not_declared_does_not(
        monkeypatch, tmp_path):
    pdf = tmp_path / "scored.pdf"
    pdf.write_bytes(b"%PDF-synthetic")

    def phantom_score(api, pdf_path, timeout=300.0, totals=None):
        totals.match += 5
        totals.unmapped_capture += 1
        totals.documents += 1
        totals.packages += 1
        return "== scored.pdf -> COMPLETED"

    monkeypatch.setattr(corpus_score, "score", phantom_score)
    assert corpus_score.main([str(pdf)]) == 1           # loud without --strict

    def coverage_gap_score(api, pdf_path, timeout=300.0, totals=None):
        totals.match += 5
        totals.not_declared += 7
        totals.documents += 1
        totals.packages += 1
        return "== scored.pdf -> COMPLETED"

    monkeypatch.setattr(corpus_score, "score", coverage_gap_score)
    assert corpus_score.main([str(pdf)]) == 0           # a schema-coverage fact
    assert corpus_score.main([str(pdf), "--strict"]) == 0   # even under --strict


# ── W2 and TAX_RETURN maps (the first full-corpus sweep's seven false
#    alarms: correct captures scored UNMAPPED_CAPTURE because the fuzzy
#    bridge cannot map IRS box captions to engine field names) ──────────────


def test_w2_mapping_bridges_box_captions_to_engine_names():
    # norm_name("wagesTipsOtherComp") is not "wagestipsothercompensation" and
    # norm_name("medicareWages") is not "medicarewagesandtips": the fuzzy
    # bridge scored both correct captures UNMAPPED_CAPTURE on the sweep.
    key = {"fields": [
        {"label": "Wages, tips, other compensation", "line": "1",
         "value": "55,000.00", "page": 0},
        {"label": "Medicare wages and tips", "line": "5",
         "value": "57,500.00", "page": 0},
    ]}
    matcher = KeyMatcher(key)
    label_map = FIELD_LABEL_MAPS["W2"]
    bridged, _ = verdict_of(
        {"fieldName": "wagesTipsOtherComp", "displayedText": "55,000.00"},
        matcher, label_map)
    still_wrong, _ = verdict_of(
        {"fieldName": "medicareWages", "displayedText": "57,600.00"},
        matcher, label_map)
    assert bridged == "MATCH"
    assert still_wrong == "MISMATCH"    # the mapping bridges names, never values


def test_w2_tax_year_maps_to_the_keys_occurrence_less_scalar():
    # The W-2 key carries the year as a top-level scalar too (the formYear
    # precedent): the map target must claim it through the same bucket.
    key = {"file": "synthetic.pdf", "taxYear": "2031", "fields": [
        {"label": "Social security wages", "line": "3", "value": "56,000.00",
         "page": 0},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "taxYear", "displayedText": "2031"},
        matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "MATCH"


def test_w2_mapped_type_never_falls_back_to_label_normalization():
    # "socialSecurityTaxWithheld" would norm-match the key's box-4 caption —
    # but W2 is a mapped type and the schema declares no such field: no fuzzy
    # bridge, ever, and the refused entry lands in the schema-coverage bucket.
    key = {"fields": [
        {"label": "Social security tax withheld", "line": "4", "value": "3,472.00",
         "page": 0},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "socialSecurityTaxWithheld", "displayedText": "3,472.00"},
        matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "UNMAPPED_CAPTURE"
    assert len(matcher.leftovers()) == 1


def test_w2_masked_ssn_suppresses_the_keys_ssn_entry_through_the_map():
    # The sweep's quiet false alarm: fuzzy could not resolve employeeSsn to
    # "Employee's social security number", so the masked capture suppressed
    # nothing and the key entry resurfaced as MISSING_EXPECTED. Mapped, the
    # suppression reaches the entry.
    key = {"fields": [
        {"label": "Employee's social security number", "line": "a",
         "value": "999-88-7777", "page": 0},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "employeeSsn", "displayedText": "•••-••-7777",
         "sensitive": True}, matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "MASKED_SKIP"
    assert matcher.leftovers() == []


def test_tax_return_mapping_bridges_the_1040_line_captions():
    # totalTax was the sweep's TAX_RETURN false alarm: the real 1040 prints
    # "Add lines 22 and 23. This is your total tax", which no normalization
    # bridges to totalTax.
    key = {"fields": [
        {"label": "Add lines 22 and 23. This is your total tax", "line": "24",
         "value": "8,206.00", "page": 1},
        {"label": "Subtract line 10 from line 9. This is your adjusted gross income",
         "line": "11", "value": "91,000.00", "page": 0},
    ]}
    matcher = KeyMatcher(key)
    label_map = FIELD_LABEL_MAPS["TAX_RETURN"]
    bridged, _ = verdict_of(
        {"fieldName": "totalTax", "displayedText": "8,206.00"}, matcher, label_map)
    still_wrong, _ = verdict_of(
        {"fieldName": "adjustedGrossIncome", "displayedText": "19,000.00"},
        matcher, label_map)
    assert bridged == "MATCH"
    assert still_wrong == "MISMATCH"


def test_tax_return_tax_year_maps_to_the_keys_form_year_style_scalar():
    # The 1040 key has no fields[] year entry at all — only the top-level
    # scalar, exactly SCHEDULE_E's formYear precedent.
    key = {"file": "synthetic.pdf", "taxYear": "2031", "fields": [
        {"label": "Add lines 22 and 23. This is your total tax", "line": "24",
         "value": "8,206.00", "page": 1},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "taxYear", "displayedText": "2031"},
        matcher, FIELD_LABEL_MAPS["TAX_RETURN"])
    assert verdict == "MATCH"


def test_tax_return_mapped_type_never_falls_back_to_label_normalization():
    # "taxableInterest" would norm-match the key's line-2b caption — but the
    # tax_return schema declares no such field: UNMAPPED_CAPTURE, never a
    # silent fuzzy pass.
    key = {"fields": [
        {"label": "Taxable interest", "line": "2b", "value": "1,250.00", "page": 0},
    ]}
    matcher = KeyMatcher(key)
    verdict, _ = verdict_of(
        {"fieldName": "taxableInterest", "displayedText": "1,250.00"},
        matcher, FIELD_LABEL_MAPS["TAX_RETURN"])
    assert verdict == "UNMAPPED_CAPTURE"
    assert len(matcher.leftovers()) == 1


def test_w2_mapping_enumerates_every_declared_schema_field():
    # The mapping table IS the declared set; the engine's w2@1.1.0 schema
    # (V12 §2 T3) declares exactly these 10 fields.
    declared = set(FIELD_LABEL_MAPS["W2"])
    assert declared == {
        "employeeName", "employeeSsn", "employerName", "employerEin",
        "taxYear", "wagesTipsOtherComp", "federalIncomeTaxWithheld",
        "socialSecurityWages", "medicareWages", "stateWages",
    }
    targets = list(FIELD_LABEL_MAPS["W2"].values())
    normalized = [corpus_score.norm_name(target) for target in targets]
    assert len(set(normalized)) == len(normalized)


def test_tax_return_mapping_enumerates_every_declared_schema_field():
    # tax_return@1.1.0 (V12 §2 T4) declares exactly these 10 fields.
    declared = set(FIELD_LABEL_MAPS["TAX_RETURN"])
    assert declared == {
        "primaryTaxpayerName", "spouseName", "primarySsn", "taxYear",
        "filingStatus", "totalIncome", "adjustedGrossIncome",
        "taxableIncome", "totalTax", "refundAmount",
    }
    targets = list(FIELD_LABEL_MAPS["TAX_RETURN"].values())
    normalized = [corpus_score.norm_name(target) for target in targets]
    assert len(set(normalized)) == len(normalized)


# ── person-name middle-initial equivalence (coordinator policy: the page is
#    truth — the form may spell a middle name the key holds as an initial;
#    the flag lives on the map ENTRY, never in a value heuristic) ────────────


def test_person_name_middle_token_matches_its_initial_in_both_directions():
    label_map = FIELD_LABEL_MAPS["SCHEDULE_E"]
    for captured, expected in (
            ("Jordan Quinn Fixture", "Jordan Q. Fixture"),
            ("Jordan Q. Fixture", "Jordan Quinn Fixture"),
            ("Jordan Quinn Fixture", "Jordan Q Fixture"),   # bare initial, no period
    ):
        matcher = KeyMatcher({"fields": [
            {"label": "Name(s) shown on return", "value": expected, "page": 0}]})
        verdict, detail = verdict_of(
            {"fieldName": "taxpayerName", "displayedText": captured},
            matcher, label_map)
        assert verdict == "MATCH"
        assert detail == "middle-initial equivalence"   # visible, never silent


def test_person_name_exact_equality_stays_an_unannotated_match():
    matcher = KeyMatcher({"fields": [
        {"label": "Name(s) shown on return", "value": "Jordan Q. Fixture",
         "page": 0}]})
    verdict, detail = verdict_of(
        {"fieldName": "taxpayerName", "displayedText": "Jordan Q. Fixture"},
        matcher, FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert verdict == "MATCH"
    assert detail is None


def test_person_name_middle_letter_mismatch_stays_mismatch():
    # The relaxation is FORM-deep only: 'Quinn' vs 'R.' disagrees on the
    # letter and stays a real mismatch.
    matcher = KeyMatcher({"fields": [
        {"label": "Name(s) shown on return", "value": "Jordan R. Fixture",
         "page": 0}]})
    verdict, _ = verdict_of(
        {"fieldName": "taxpayerName", "displayedText": "Jordan Quinn Fixture"},
        matcher, FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert verdict == "MISMATCH"


def test_joint_person_name_applies_the_equivalence_per_person():
    label_map = FIELD_LABEL_MAPS["SCHEDULE_E"]
    matcher = KeyMatcher({"fields": [
        {"label": "Name(s) shown on return",
         "value": "Jordan Q. Fixture and Casey R. Fixture", "page": 0}]})
    verdict, detail = verdict_of(
        {"fieldName": "taxpayerName",
         "displayedText": "Jordan Q. Fixture and Casey Rowan Fixture"},
        matcher, label_map)
    assert verdict == "MATCH"
    assert detail == "middle-initial equivalence"

    # a letter disagreement on EITHER filer stays a mismatch
    matcher = KeyMatcher({"fields": [
        {"label": "Name(s) shown on return",
         "value": "Jordan Q. Fixture and Casey R. Fixture", "page": 0}]})
    verdict, _ = verdict_of(
        {"fieldName": "taxpayerName",
         "displayedText": "Jordan Quinn Fixture and Casey Sage Fixture"},
        matcher, label_map)
    assert verdict == "MISMATCH"


def test_person_shaped_value_in_a_non_person_field_stays_strict():
    # The flag, not the value's shape, gates the relaxation: partnershipName
    # holds an ENTITY name, and a person-looking capture gets no equivalence.
    matcher = KeyMatcher({"fields": [
        {"label": "Part II (a) Name", "value": "Jordan Q. Fixture", "page": 1,
         "row": "1"}]})
    verdict, _ = verdict_of(
        {"fieldName": "partnershipName", "groupKey": "1",
         "displayedText": "Jordan Quinn Fixture"},
        matcher, FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert verdict == "MISMATCH"


def test_fuzzy_fallback_fields_get_no_person_name_relaxation():
    # Unmapped document types have no flags to consult: strict, as before.
    matcher = KeyMatcher({"fields": [
        {"label": "Borrower name", "value": "Jordan Q. Fixture", "page": 0}]})
    verdict, _ = verdict_of(
        {"fieldName": "borrowerName", "displayedText": "Jordan Quinn Fixture"},
        matcher)
    assert verdict == "MISMATCH"


def test_person_name_flags_are_explicit_and_exactly_these():
    flagged = {(doc_type, field)
               for doc_type, label_map in FIELD_LABEL_MAPS.items()
               for field, target in label_map.items()
               if isinstance(target, corpus_score.MapTarget) and target.person}
    assert flagged == {
        ("SCHEDULE_E", "taxpayerName"),
        ("W2", "employeeName"),
        ("TAX_RETURN", "primaryTaxpayerName"),
        ("TAX_RETURN", "spouseName"),
    }


# ── composed and first-line expectations (coordinator policy: the W-2 prints
#    box e as TWO sub-boxes the schema reads as ONE field, and box c as one
#    multi-line cell whose FIRST line is the company name — key-granularity
#    facts, resolved by per-entry declarations, never heuristics) ─────────────


def w2_box_e_key(*extra_fields):
    """A synthetic W-2 key holding box e the way the real keys do: two entries."""
    return {"fields": [
        {"label": "Employee's first name and initial", "line": "e",
         "value": "Sam Q.", "page": 0},
        {"label": "Last name", "line": "e", "value": "Fixture", "page": 0},
        *extra_fields,
    ]}


# box c the way the real keys hold it: ONE entry, printed lines newline-joined
W2_BOX_C_ENTRY = {
    "label": "Employer's name, address, and ZIP code", "line": "c",
    "value": "Synth Fabrication LLC\n12 Synth St\nFaketown, ZZ 00000",
    "page": 0,
}


def test_w2_employee_name_matches_the_composed_first_plus_last_expectation():
    # The engine reads the whole printed name as ONE field; the key holds box
    # e as two entries. The COMPOSED expectation joins them — and says so.
    matcher = KeyMatcher(w2_box_e_key())
    verdict, detail = verdict_of(
        {"fieldName": "employeeName", "displayedText": "Sam Q. Fixture"},
        matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "MATCH"
    assert detail == "composed"                     # visible, never silent


def test_w2_composed_expectation_admits_middle_initial_equivalence_on_top():
    # The wrappers compose: employeeName is ALSO a person name, so a form
    # that spells the middle name the key holds as an initial still matches —
    # and the detail carries BOTH annotations.
    matcher = KeyMatcher(w2_box_e_key())
    verdict, detail = verdict_of(
        {"fieldName": "employeeName", "displayedText": "Sam Quinn Fixture"},
        matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "MATCH"
    assert detail == "composed, middle-initial equivalence"


def test_composed_capture_of_only_the_first_cell_is_a_mismatch():
    # The expectation IS the composition: the first sub-box alone is a wrong
    # (truncated) capture, exactly what the sweep flagged — never a match.
    matcher = KeyMatcher(w2_box_e_key())
    verdict, _ = verdict_of(
        {"fieldName": "employeeName", "displayedText": "Sam Q."},
        matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "MISMATCH"


def f1040_identity_key():
    """A synthetic 1040 key holding both identity rows the way the real key
    does: the taxpayer's surname cell labelled "Last name", the spouse's
    "Last name (spouse)"."""
    return {"fields": [
        {"label": "Your first name and middle initial", "line": None,
         "value": "Sam Q.", "page": 0},
        {"label": "Last name", "line": None, "value": "Fixture", "page": 0},
        {"label": "Your social security number", "line": None,
         "value": "987654321", "page": 0},
        {"label": "If joint return, spouse's first name and middle initial",
         "line": None, "value": "Casey R.", "page": 0},
        {"label": "Last name (spouse)", "line": None, "value": "Other",
         "page": 0},
    ]}


def test_1040_names_compose_each_row_with_its_own_last_name_cell():
    # V47 reads a 1040 name ACROSS its two cells. The key labels the two
    # surname cells distinctly, and each composition names its own row's.
    matcher = KeyMatcher(f1040_identity_key())
    verdict, detail = verdict_of(
        {"fieldName": "primaryTaxpayerName", "displayedText": "Sam Q. Fixture"},
        matcher, FIELD_LABEL_MAPS["TAX_RETURN"])
    assert (verdict, detail) == ("MATCH", "composed")
    verdict, detail = verdict_of(
        {"fieldName": "spouseName", "displayedText": "Casey R. Other"},
        matcher, FIELD_LABEL_MAPS["TAX_RETURN"])
    assert (verdict, detail) == ("MATCH", "composed")


def test_1040_first_cell_alone_is_a_mismatch_against_the_composed_row():
    matcher = KeyMatcher(f1040_identity_key())
    verdict, _ = verdict_of(
        {"fieldName": "primaryTaxpayerName", "displayedText": "Sam Q."},
        matcher, FIELD_LABEL_MAPS["TAX_RETURN"])
    assert verdict == "MISMATCH"


def test_composed_match_suppresses_both_source_entries_from_missing_accounting():
    # Both consumed key entries leave the ledgers: the primary never counts
    # MISSING_EXPECTED, and "Last name" — nobody's target — never counts
    # NOT_DECLARED once the composition consumed it.
    documents_view = {
        "packageId": "pkg1",
        "documents": [
            {"id": "d1", "ordinal": 0, "documentTypeCode": "W2",
             "classificationConfidence": 1.0, "pages": []},
        ],
        "unassignedPages": [],
    }
    fields_view = {"fields": [
        {"fieldName": "employeeName", "displayedText": "Sam Q. Fixture",
         "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9,
         "sensitive": False},
    ]}
    totals = VerdictCounts()

    report = package_report("synthetic.pdf", "COMPLETED", documents_view, None,
                            {"d1": fields_view}, answer_key=w2_box_e_key(),
                            totals=totals)

    assert totals.match == 1
    assert totals.missing_expected == 0 and totals.not_declared == 0
    assert "MISSING_EXPECTED" not in report
    assert "schema-coverage" not in report
    assert "Last name" not in report


def test_masked_composed_capture_suppresses_the_part_entry_too():
    # A masked employee name is unverifiable across BOTH of box e's cells:
    # neither the primary entry nor "Last name" may resurface.
    matcher = KeyMatcher(w2_box_e_key())
    verdict, _ = verdict_of(
        {"fieldName": "employeeName", "displayedText": "••••••",
         "sensitive": True}, matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "MASKED_SKIP"
    assert matcher.leftovers() == []


def test_w2_employer_name_matches_the_first_line_of_the_multi_line_entry():
    # The key holds box c as one multi-line string; employerName means the
    # company NAME — the first printed line. The match says so, and the
    # address/ZIP remainder never resurfaces as a phantom MISSING_EXPECTED.
    matcher = KeyMatcher({"fields": [W2_BOX_C_ENTRY]})
    verdict, detail = verdict_of(
        {"fieldName": "employerName", "displayedText": "Synth Fabrication LLC"},
        matcher, FIELD_LABEL_MAPS["W2"])
    assert verdict == "MATCH"
    assert detail == "first-line"
    assert matcher.leftovers() == []


def test_first_line_rejects_a_partial_that_is_not_exactly_the_first_line():
    # first-line is exact-line, not prefix: a truncated name and a capture
    # spilling into the address line are both real mismatches.
    for captured in ("Synth Fabrication",                   # truncated
                     "Synth Fabrication LLC 12 Synth St"):  # spills into line 2
        matcher = KeyMatcher({"fields": [W2_BOX_C_ENTRY]})
        verdict, _ = verdict_of(
            {"fieldName": "employerName", "displayedText": captured},
            matcher, FIELD_LABEL_MAPS["W2"])
        assert verdict == "MISMATCH"


def test_first_line_with_a_comma_joined_suffix_admits_the_pre_comma_name():
    # The real key's structure prints the name line "Name, Suffix"-shaped: the
    # documented prefix-to-comma rule makes the pre-comma segment a declared
    # ALTERNATE rendering of the name — the full line still matches too, and
    # an arbitrary prefix still does not.
    entry = {"label": "Employer's name, address, and ZIP code", "line": "c",
             "value": "Synth Fabrication, Inc.\n12 Synth St\nFaketown, ZZ 00000",
             "page": 0}
    cases = (("Synth Fabrication", "MATCH"),          # pre-comma name
             ("Synth Fabrication, Inc.", "MATCH"),    # full first line
             ("Synth", "MISMATCH"))                   # neither — still loud
    for captured, wanted in cases:
        matcher = KeyMatcher({"fields": [entry]})
        verdict, detail = verdict_of(
            {"fieldName": "employerName", "displayedText": captured},
            matcher, FIELD_LABEL_MAPS["W2"])
        assert verdict == wanted
        if wanted == "MATCH":
            assert detail == "first-line"
        assert matcher.leftovers() == []   # consumed either way, never phantom


def test_non_flagged_fields_keep_full_string_equality():
    # composed/first-line are per-entry DECLARATIONS, never heuristics: an
    # unflagged field capturing only the first line of a multi-line key value
    # stays a real mismatch.
    matcher = KeyMatcher({"fields": [
        {"label": "Physical address of each property "
                  "(street, city, state, ZIP code)",
         "line": "1a", "value": "12 Synth St\nFaketown, ZZ 00000", "page": 0,
         "propertyColumn": "A"}]})
    verdict, _ = verdict_of(
        {"fieldName": "propertyAddress", "groupKey": "A",
         "displayedText": "12 Synth St"},
        matcher, FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert verdict == "MISMATCH"


def test_summary_legend_counts_every_annotated_match():
    documents_view = {
        "packageId": "pkg1",
        "documents": [
            {"id": "d1", "ordinal": 0, "documentTypeCode": "W2",
             "classificationConfidence": 1.0, "pages": []},
        ],
        "unassignedPages": [],
    }
    fields_view = {"fields": [
        {"fieldName": "employeeName", "displayedText": "Sam Quinn Fixture",
         "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9,
         "sensitive": False},
        {"fieldName": "employerName", "displayedText": "Synth Fabrication LLC",
         "extractionMethod": "ANCHOR_LABEL", "confidence": 0.9,
         "sensitive": False},
    ]}
    totals = VerdictCounts()

    report = package_report("synthetic.pdf", "COMPLETED", documents_view, None,
                            {"d1": fields_view},
                            answer_key=w2_box_e_key(W2_BOX_C_ENTRY),
                            totals=totals)
    lines = report.splitlines()

    # each annotated match is visible on its own line ...
    assert any("employeeName" in line and " MATCH " in line
               and "(composed, middle-initial equivalence)" in line
               for line in lines)
    assert any("employerName" in line and " MATCH " in line
               and "(first-line)" in line for line in lines)
    # ... and counted in the summary line's legend
    assert ("summary: 2 match / 0 mismatch / 0 unmapped-capture / "
            "0 missing-expected / 0 masked (0 captured-unexpected, "
            "0 not-in-key, 0 not-declared) "
            "[annotated: 1 composed, 1 first-line, 1 middle-initial equivalence]"
            ) in report
    assert totals.composed == 1 and totals.first_line == 1
    assert totals.middle_initial == 1
    # privacy holds: no captured or expected value ever prints
    for value in ("Sam", "Quinn", "Fixture", "Synth", "Faketown"):
        assert value not in report


def test_summary_legend_is_absent_when_no_match_is_annotated_and_merges_up():
    assert "[annotated:" not in VerdictCounts(match=3).summary()

    per_doc = VerdictCounts()
    per_doc.count_annotations("MATCH", "composed, middle-initial equivalence")
    per_doc.count_annotations("MATCH", None)               # plain match: no-op
    per_doc.count_annotations(                             # a MISMATCH detail
        "MISMATCH",                                        # is a shape hint,
        "captured len=5 shape=9,999; expected len=5 shape=9,999")  # never counted
    roll_up = VerdictCounts()
    roll_up.count_annotations("MATCH", "first-line")
    roll_up.merge(per_doc)
    assert roll_up.composed == 1 and roll_up.first_line == 1
    assert roll_up.middle_initial == 1
    assert roll_up.summary().endswith(
        "[annotated: 1 composed, 1 first-line, 1 middle-initial equivalence]")


def test_composed_and_first_line_flags_are_explicit_and_exactly_these():
    composed = {(doc_type, field): target.composed_with
                for doc_type, label_map in FIELD_LABEL_MAPS.items()
                for field, target in label_map.items()
                if isinstance(target, corpus_score.MapTarget)
                and target.composed_with}
    first_line = {(doc_type, field)
                  for doc_type, label_map in FIELD_LABEL_MAPS.items()
                  for field, target in label_map.items()
                  if isinstance(target, corpus_score.MapTarget)
                  and target.first_line}
    assert composed == {
        ("W2", "employeeName"): ("Last name",),
        # V47: the 1040's identity rows are read across their cells too.
        ("TAX_RETURN", "primaryTaxpayerName"): ("Last name",),
        ("TAX_RETURN", "spouseName"): ("Last name (spouse)",),
    }
    assert first_line == {("W2", "employerName")}


def test_schedule_e_mapping_enumerates_every_declared_schema_field():
    # The mapping table IS the declared set; the engine's SCHEDULE_E schema
    # declares exactly these 29 fields (V13 seed).
    declared = set(FIELD_LABEL_MAPS["SCHEDULE_E"])
    assert declared == {
        "taxpayerName", "taxpayerSsn", "taxYear", "propertyAddress",
        "rentsReceived", "mortgageInterest", "depreciationExpense",
        "totalExpenses", "incomeOrLoss", "totalRentalRealEstateIncomeOrLoss",
        "partnershipName", "partnershipEin", "partnershipPassiveLossAllowed",
        "partnershipPassiveIncome", "partnershipNonpassiveLossAllowed",
        "partnershipSection179Expense", "partnershipNonpassiveIncome",
        "partnershipAndSCorpTotal", "estateOrTrustName",
        "estateOrTrustPassiveDeductionOrLoss", "estateOrTrustPassiveIncome",
        "estateOrTrustDeductionOrLoss", "estateOrTrustOtherIncome",
        "estateAndTrustTotal", "remicName", "remicExcessInclusion",
        "remicIncome", "remicTotal", "totalIncomeOrLoss",
    }
    # target labels must not collide once normalized — a collision would let
    # one engine field consume another field's key entries
    targets = list(FIELD_LABEL_MAPS["SCHEDULE_E"].values())
    normalized = [corpus_score.norm_name(target) for target in targets]
    assert len(set(normalized)) == len(normalized)
