#!/usr/bin/env python3
"""
crawl-ingest — turn a list of web pages into clean, provenance-stamped Markdown
that drops straight into the existing S3 -> brain sync pipeline (scripts/s3-ingest).

This is a BATCH PRODUCER. It does not touch the running engine, borrower documents,
or any request path. It crawls PUBLIC web pages you list, and writes:

    out/<fileName>.md          one file per source, fit-markdown + a provenance header
    out/manifest.partial.json  {"files": {...}} in the exact schema scripts/s3-ingest reads

Then you publish those to s3://<bucket>/<prefix>/ (see publish.sh) and run the existing
scripts/s3-ingest/sync.mjs to ingest. No Java changes; no new engine endpoints.

⚠️  Run this OFF the production box. It launches headless Chromium (300MB-1GB+ per page)
    and will OOM a small shared host. Run it on a laptop / CI runner / ephemeral task.

Usage:
    python -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt
    crawl4ai-setup                      # installs the headless browser (one time)
    python crawl.py --sources sources.json --out out
    python crawl.py --only console-getting-started.md   # re-crawl a single source

See README.md for the full flow and the regulatory-corpus caveat.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    from crawl4ai import AsyncWebCrawler, BrowserConfig, CacheMode, CrawlerRunConfig
    from crawl4ai.content_filter_strategy import PruningContentFilter
    from crawl4ai.markdown_generation_strategy import DefaultMarkdownGenerator
except ImportError:  # pragma: no cover - friendly hint, not a real code path
    sys.exit(
        "crawl4ai is not installed. Run:\n"
        "  python -m venv .venv && source .venv/bin/activate\n"
        "  pip install -r requirements.txt && crawl4ai-setup"
    )

# Kept in lockstep with scripts/s3-ingest/plan.mjs ALLOWED_EXT + sourceType enum.
ALLOWED_SOURCE_TYPES = {"AGENCY_GUIDELINE", "INTERNAL_POLICY", "INVESTOR_OVERLAY", "EDUCATIONAL"}
DEFAULT_PRUNE_THRESHOLD = 0.48
MIN_USEFUL_CHARS = 200  # below this, the page probably didn't render (JS wall / block)
SAFE_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 ._-]*\.(md|markdown)$")


def iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def load_sources(path: Path) -> dict:
    if not path.exists():
        sys.exit(f"sources file not found: {path}")
    doc = json.loads(path.read_text("utf-8"))
    if "sources" not in doc or not isinstance(doc["sources"], list):
        sys.exit(f"{path} must be an object with a 'sources' array")
    return doc


def resolve(src: dict, defaults: dict) -> dict:
    """Merge one source entry with defaults; validate the fields sync.mjs needs."""
    file_name = src.get("fileName")
    url = src.get("url")
    if not url or not file_name:
        sys.exit(f"each source needs 'url' and 'fileName'; got: {json.dumps(src)}")
    if not SAFE_FILENAME.match(file_name):
        sys.exit(f"unsafe/invalid fileName '{file_name}' — use a plain *.md name, no path separators")
    source_type = src.get("sourceType") or defaults.get("sourceType") or "AGENCY_GUIDELINE"
    if source_type not in ALLOWED_SOURCE_TYPES:
        sys.exit(f"sourceType '{source_type}' not in {sorted(ALLOWED_SOURCE_TYPES)} (fileName={file_name})")
    return {
        "url": url,
        "fileName": file_name,
        "title": src.get("title") or file_name.rsplit(".", 1)[0].replace("_", " ").replace("-", " ").strip(),
        "sourceName": src.get("sourceName") or defaults.get("sourceName") or "Knowledge Base",
        "sourceType": source_type,
        "effectiveDate": src.get("effectiveDate"),  # optional, keep as-is if provided
        "pruneThreshold": float(src.get("pruneThreshold", defaults.get("pruneThreshold", DEFAULT_PRUNE_THRESHOLD))),
        "ignoreLinks": bool(src.get("ignoreLinks", defaults.get("ignoreLinks", True))),
    }


def provenance_header(meta: dict, fetched_at: str) -> str:
    """A small human- + machine-readable provenance block prepended to the body.

    The brain's /documents/upload has no source-URL field, so provenance rides in the
    document body (and travels with every retrieved chunk). documentVersion in the
    manifest also carries the fetch/effective date for the brain's own metadata.
    """
    return (
        f"<!-- provenance | source_url: {meta['url']} | fetched_at: {fetched_at} "
        f"| publisher: {meta['sourceName']} | source_type: {meta['sourceType']} -->\n\n"
        f"> **Source:** [{meta['title']}]({meta['url']}) — {meta['sourceName']}. "
        f"Retrieved {fetched_at} by crawl-ingest. "
        f"_Verify against the official publication before relying on it for a decision._\n\n"
        f"---\n\n"
    )


def extract_markdown(result) -> str:
    """Prefer pruned fit_markdown; fall back to raw. Tolerant of API shape drift."""
    md = getattr(result, "markdown", None)
    if md is None:
        return ""
    fit = getattr(md, "fit_markdown", None)
    raw = getattr(md, "raw_markdown", None)
    text = (fit or "").strip()
    if len(text) < MIN_USEFUL_CHARS and raw:  # fit over-pruned a short page → use raw
        text = raw.strip()
    if not text and isinstance(md, str):  # very old API returned a bare string
        text = md.strip()
    return text


async def crawl_all(sources: list[dict], out_dir: Path, verbose: bool) -> tuple[dict, int]:
    out_dir.mkdir(parents=True, exist_ok=True)
    files: dict[str, dict] = {}
    failures = 0
    browser = BrowserConfig(headless=True, verbose=verbose)

    async with AsyncWebCrawler(config=browser) as crawler:
        for meta in sources:
            fetched_at = iso_now()
            cfg = CrawlerRunConfig(
                cache_mode=CacheMode.BYPASS,  # always fetch fresh for a corpus refresh
                markdown_generator=DefaultMarkdownGenerator(
                    content_filter=PruningContentFilter(
                        threshold=meta["pruneThreshold"], threshold_type="fixed", min_word_threshold=0
                    ),
                    options={"ignore_links": meta["ignoreLinks"]},
                ),
            )
            try:
                result = await crawler.arun(url=meta["url"], config=cfg)
            except Exception as e:  # network/browser blow-up on one page must not kill the batch
                print(f"  FAILED  {meta['fileName']}  <- {meta['url']}  ({e})")
                failures += 1
                continue

            if not getattr(result, "success", False):
                print(f"  FAILED  {meta['fileName']}  <- {meta['url']}  ({getattr(result, 'error_message', 'crawl unsuccessful')})")
                failures += 1
                continue

            body = extract_markdown(result)
            if len(body) < MIN_USEFUL_CHARS:
                print(f"  EMPTY   {meta['fileName']}  <- {meta['url']}  (rendered <{MIN_USEFUL_CHARS} chars — JS wall or block? not written)")
                failures += 1
                continue

            (out_dir / meta["fileName"]).write_text(provenance_header(meta, fetched_at) + body + "\n", "utf-8")

            entry = {
                "title": meta["title"],
                "sourceName": meta["sourceName"],
                "sourceType": meta["sourceType"],
                # documentVersion pins the corpus snapshot; effectiveDate is the doc's own date if known.
                "documentVersion": meta["effectiveDate"] or today(),
            }
            if meta["effectiveDate"]:
                entry["effectiveDate"] = meta["effectiveDate"]
            files[meta["fileName"]] = entry
            print(f"  OK      {meta['fileName']}  ({len(body):,} chars)  <- {meta['url']}")

    return files, failures


def main() -> int:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description="Crawl web pages into brain-ready Markdown + a partial manifest.")
    ap.add_argument("--sources", default=str(here / "sources.json"), help="sources JSON (default: ./sources.json)")
    ap.add_argument("--out", default=str(here / "out"), help="output staging dir (default: ./out)")
    ap.add_argument("--only", default=None, help="crawl only the source with this fileName")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    doc = load_sources(Path(args.sources))
    defaults = doc.get("defaults", {})
    resolved = [resolve(s, defaults) for s in doc["sources"]]
    if args.only:
        resolved = [m for m in resolved if m["fileName"] == args.only]
        if not resolved:
            sys.exit(f"--only {args.only!r} matched no source in {args.sources}")

    print(f"crawl-ingest: {len(resolved)} source(s) -> {args.out}")
    files, failures = asyncio.run(crawl_all(resolved, Path(args.out), args.verbose))

    # Partial manifest = ONLY the crawled files. publish.sh deep-merges this into the
    # live s3 _manifest.json so existing corpus entries are preserved (no defaults here,
    # so a merge can never clobber the live defaults).
    manifest_path = Path(args.out) / "manifest.partial.json"
    manifest_path.write_text(json.dumps({"files": files}, indent=2) + "\n", "utf-8")

    print(f"\nwrote {len(files)} file(s) + {manifest_path.name}. failures={failures}")
    print("next: ./publish.sh   (stages to S3 + merges the manifest)   then   cd ../s3-ingest && node sync.mjs")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
