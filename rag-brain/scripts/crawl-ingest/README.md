# crawl-ingest — web → Markdown corpus producer

Turns a list of public web pages into clean, provenance-stamped Markdown that drops
straight into the **existing** `../s3-ingest` pipeline. A batch producer only — **zero
engine/Java changes**, no new endpoints. It never touches borrower documents or any
request path; it crawls the public pages you list.

```
crawl.py  ──▶  out/*.md + out/manifest.partial.json
publish.sh ──▶ s3://example-bucket/rag-brain/  (+ deep-merge into _manifest.json)
../s3-ingest/sync.mjs ──▶ POST /api/ai/documents/upload   (the proven, safety-valved ingester)
```

## ⚠️ Two hard rules

1. **Run it OFF the production box.** It launches headless Chromium (300 MB–1 GB+ per
   page). The prod box is ~1.9 GB with near-zero headroom and has OOM'd before — a
   crawler there will kill the engine. Run on a laptop, a CI runner, or an ephemeral
   Fargate/Lambda task that crawls, writes to S3, and dies.
2. **Regulatory corpus ≠ scrape-and-hope.** For Fannie/Freddie/HUD guidelines, prefer
   the **official published file** (the current `../s3-ingest` corpus does this) so an
   audit can prove provenance and version. Use crawling for your **own** docs/console
   help and educational/explainer content (e.g. CFPB). Every crawled file gets a
   provenance header (source URL + fetch time) and a `documentVersion` = fetch date, but
   that is traceability, not a substitute for an authoritative source. Mind each site's ToS.

## Setup (one time, off-box)

```bash
cd scripts/crawl-ingest
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
crawl4ai-setup            # installs the headless browser
```

## Run

```bash
cp sources.example.json sources.json     # edit: add your URLs + metadata
python crawl.py                          # crawls all sources -> out/
python crawl.py --only cfpb-fha-loans.md # re-crawl a single source
```

Output: one `out/<fileName>.md` per source (fit-markdown + provenance header) and
`out/manifest.partial.json` = `{"files": {…}}` in the schema `../s3-ingest` reads.
Both `out/` and your edited `sources.json` are gitignored.

## Publish + ingest

```bash
./publish.sh                 # DRY RUN — shows what it would stage/merge, writes nothing
./publish.sh --apply         # copies out/*.md to S3 + deep-merges into _manifest.json
cd ../s3-ingest && node sync.mjs --dry-run    # preview the brain ingest plan
node sync.mjs                                 # apply
```

`publish.sh` deep-merges (`jq '.[0] * .[1]'`) so existing corpus entries are preserved —
your crawled files are added, nothing is clobbered. `sync.mjs` is idempotent by fileName
and has a mass-deactivation safety valve, so re-running is safe. Requires `aws` + `jq`.

## sources.json

| field | required | notes |
|---|---|---|
| `url` | ✅ | page to crawl |
| `fileName` | ✅ | plain `*.md` name; becomes the S3 object + manifest key |
| `title` | – | defaults to a title-cased fileName |
| `sourceName` | – | publisher; inherits `defaults.sourceName` |
| `sourceType` | – | `AGENCY_GUIDELINE \| INTERNAL_POLICY \| INVESTOR_OVERLAY \| EDUCATIONAL` |
| `effectiveDate` | – | the doc's own date if known; also sets `documentVersion` |
| `pruneThreshold` | – | 0–1, higher = more aggressive boilerplate removal (default 0.48) |
| `ignoreLinks` | – | strip links from markdown (default true) |

## Refreshing a changed page

`sync.mjs` treats a same-named file as already-ingested (no content-hash diff). To force
a refresh after re-crawling: deactivate/delete it in the brain (or call
`POST /api/ai/documents/{id}/reindex`), then re-run `sync.mjs`. Same limitation as
`../s3-ingest`.

## Which brain?

This feeds the **S3-backed corpus** that `../s3-ingest/sync.mjs` targets (the mortgage
brain). To stage content for a different brain, point `--bucket/--prefix` (and the
matching sync target) at that brain's corpus source. The suite Ask-AI brain uses a local
pack corpus, not this S3 path — stage its markdown the same way, publish to its source.
