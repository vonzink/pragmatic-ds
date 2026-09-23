"""Layout clustering (Phase 3): spans in, elements out — canonical space only.

The pipeline is engine.py's ClusteringLayoutEngine: lines -> tables -> headers ->
paragraphs -> reading order. Spans arrive ON the /v1/layout request (never
re-derived), so the same code lays out native and OCR'd pages; the optional PDF
part only ever CONFIRMS ruled tables, it never contributes geometry.
"""
