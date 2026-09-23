"""OCR subpackage: the PARSER_EVALUATION.md §4 selection ladder behind /v1/ocr.

engines.py    OcrEngine protocol + RapidOCR / Tesseract adapters (px-space spans)
osd.py        Tesseract OSD rotation + projection-profile arbitration
gates.py      quality gates G1–G4, G6 (G5 is Java-side and never appears here)
reconcile.py  per-region engine reconciliation + reading order
service.py    the ladder itself; the only module that assembles the wire response
"""
