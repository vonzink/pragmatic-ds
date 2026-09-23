#!/usr/bin/env python3
"""Turn a real PDF into a corpus/eval case the extraction harness can score.

    .venv/bin/python tools/corpus_truth.py corpus/<file>.pdf --type W2 [--id <case>] \
        [--worker http://localhost:9091] [--secret local-dev-secret]

Writes ``corpus/eval/<TYPE>/<id>/truth.json`` (the worker's words, page by page, in
the ``fixtures/truth`` shape) and, if absent, ``case.json``. The words come from the
running worker's ``POST /v1/text`` — the same call the engine makes at upload — so
the harness scores exactly the spans production would persist, faux-bold overprint
and trailing-period amounts included. Nothing here reads the PDF itself.

``expectedFields`` is the human's half. On first run it is written EMPTY; label it
by hand (engine field names, ``displayedText`` verbatim as printed) and re-run
``./gradlew corpusEval -PcorpusEval=true``. A re-run of this script keeps the labels
you already wrote and refreshes only the words.

Everything it writes lands under the gitignored ``corpus/``. It never touches
``fixtures/`` and never writes anywhere else. stdlib only, like corpus_score.py.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CORPUS_EVAL = REPO_ROOT / "corpus" / "eval"


def worker_text(worker: str, secret: str, pdf: Path) -> dict:
    """``POST /v1/text`` — multipart ``file`` + ``request`` JSON part, per WORKER_CONTRACT.md."""
    boundary = "corpustruthboundary"
    body = (
        (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="request"\r\n'
            "Content-Type: application/json\r\n\r\n"
            '{"pages": []}\r\n'
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{pdf.name}"\r\n'
            "Content-Type: application/pdf\r\n\r\n"
        ).encode()
        + pdf.read_bytes()
        + f"\r\n--{boundary}--\r\n".encode()
    )
    request = urllib.request.Request(worker.rstrip("/") + "/v1/text", data=body, method="POST")
    request.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    request.add_header("X-Worker-Secret", secret)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read())


def truth_pages(text_response: dict, expected_type: str) -> list[dict]:
    """Worker pages → the ``fixtures/truth`` page shape the harness seeds as text spans."""
    pages = []
    for page in text_response.get("pages", []):
        words = [
            {
                "text": span["text"],
                "x": span["x"],
                "y": span["y"],
                "width": span["width"],
                "height": span["height"],
            }
            for span in page.get("spans", [])
        ]
        pages.append(
            {
                "pageIndex": page["pageIndex"],
                "widthPt": page["widthPt"],
                "heightPt": page["heightPt"],
                "contentRotation": page.get("rotation", 0),
                "expectedType": expected_type,
                "expectedVerdict": page.get("verdict", "NATIVE"),
                "words": words,
            }
        )
    return pages


def case_directory(document_type: str, case_id: str) -> Path:
    """``corpus/eval/<TYPE>/<id>`` — and nowhere else.

    Both names come from the command line (the id defaults to the PDF's stem), so
    ``--type ../../fixtures`` or a stem containing ``/`` would otherwise write a
    borrower's words outside the gitignored tree. Refused on the characters and,
    belt and braces, on the resolved path.
    """
    for label, value in (("type", document_type), ("id", case_id)):
        if not value or "/" in value or "\\" in value or ".." in value:
            raise ValueError(f"{label} must be a single directory name, not {value!r}")
    case_dir = (CORPUS_EVAL / document_type / case_id).resolve()
    if not case_dir.is_relative_to(CORPUS_EVAL.resolve()):
        raise ValueError(f"refusing to write outside {CORPUS_EVAL}: {case_dir}")
    return case_dir


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pdf", type=Path)
    parser.add_argument("--type", required=True, help="document_type_code, e.g. W2 — names the directory")
    parser.add_argument("--id", help="case id (directory name); default: the PDF's stem")
    parser.add_argument("--worker", default="http://localhost:9091")
    parser.add_argument("--secret", default=os.environ.get("DOCENGINE_WORKER_SECRET", "local-dev-secret"))
    args = parser.parse_args(argv)

    pdf: Path = args.pdf
    if not pdf.is_file():
        print(f"no such PDF: {pdf}", file=sys.stderr)
        return 2
    case_id = args.id or pdf.stem
    try:
        case_dir = case_directory(args.type, case_id)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    case_dir.mkdir(parents=True, exist_ok=True)

    try:
        response = worker_text(args.worker, args.secret, pdf)
    except urllib.error.HTTPError as error:
        print(f"worker refused /v1/text: HTTP {error.code} — is the stack up and the secret right?", file=sys.stderr)
        return 1
    except urllib.error.URLError as error:
        print(f"worker unreachable at {args.worker}: {error.reason}", file=sys.stderr)
        return 1

    truth_file = case_dir / "truth.json"
    expected_fields: list = []
    if truth_file.is_file():
        expected_fields = json.loads(truth_file.read_text()).get("expectedFields", []) or []
    truth = {
        "fixture": case_id,
        "source": pdf.name,
        "pages": truth_pages(response, args.type),
        "expectedFields": expected_fields,
    }
    truth_file.write_text(json.dumps(truth, indent=2) + "\n")

    case_file = case_dir / "case.json"
    if not case_file.is_file():
        case_file.write_text(
            json.dumps(
                {
                    "id": case_id,
                    "layout": "NONE",
                    "synthetic": False,
                    "note": f"real {args.type} from {pdf.name}; labelled by hand",
                    "documents": [{"type": args.type}],
                },
                indent=2,
            )
            + "\n"
        )

    words = sum(len(page["words"]) for page in truth["pages"])
    print(f"{truth_file.relative_to(REPO_ROOT)}: {len(truth['pages'])} pages, {words} words, "
          f"{len(expected_fields)} expectedFields" + ("" if expected_fields else " — label them, then run corpusEval"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
