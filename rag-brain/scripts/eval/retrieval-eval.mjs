#!/usr/bin/env node
/**
 * Retrieval eval against the REAL pipeline.
 *
 * Drives `GET /api/ai/documents/test-retrieval` on a running engine, so every
 * case exercises what production actually does: embeddings, hybrid vector+FTS
 * merge, vocabulary expansion, program/authority ranking, learned source
 * weights, and the LLM reranker. It then joins each hit back to the case's
 * `expected_document_id` via the chunk's `externalDocId` (the corpus front-matter
 * `document_id:`, persisted at ingestion).
 *
 * This is the gate for any change to retrieval ordering or corpus content: run
 * it, then diff against a committed baseline.
 *
 * Contrast with `corpus/<brain>/tests/run-retrieval.js`, a hermetic BM25 proxy
 * that needs no engine and no spend but cannot see any of the above. Use the
 * proxy for fast corpus-authoring feedback; use this for real verdicts.
 *
 * COST: each case costs one embedding call, plus one utility-model call when the
 * reranker is enabled (it is, by default). A 33-case run is cheap but not free —
 * it is not something to put on every push.
 *
 * Usage:
 *   ADMIN_API_KEY=… node scripts/eval/retrieval-eval.mjs \
 *     --cases corpus/suite/tests/retrieval-cases.yaml --brain suite
 *
 *   # gate against a committed baseline (exit 1 on regression)
 *   ADMIN_API_KEY=… npm run eval:retrieval -- --brain suite \
 *     --baseline scripts/eval/baselines/suite.json
 *
 *   # accept the current numbers as the new baseline
 *   ADMIN_API_KEY=… npm run eval:retrieval -- --brain suite \
 *     --baseline scripts/eval/baselines/suite.json --update-baseline
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
let yaml;
try {
  yaml = require('js-yaml');
} catch {
  die('js-yaml not installed. Run `npm ci` in the repo root.');
}

// ── args ──────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const out = {
    cases: 'corpus/suite/tests/retrieval-cases.yaml',
    base: process.env.RAG_BRAIN_BASE_URL || 'http://localhost:9093',
    brain: 'suite',
    key: process.env.ADMIN_API_KEY || '',
    visibility: '',
    scope: '',
    ks: [1, 3, 5],
    gateK: 3,
    concurrency: 4,
    baseline: '',
    updateBaseline: false,
    json: '',
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) die(`${a} needs a value`);
      return v;
    };
    switch (a) {
      case '--cases': out.cases = next(); break;
      case '--base': out.base = next().replace(/\/+$/, ''); break;
      case '--brain': out.brain = next(); break;
      case '--key': out.key = next(); break;
      case '--visibility': out.visibility = next(); break;
      case '--scope': out.scope = next(); break;
      case '--k': out.ks = next().split(',').map((n) => parseInt(n, 10)).filter((n) => n > 0); break;
      case '--gate-k': out.gateK = parseInt(next(), 10); break;
      case '--concurrency': out.concurrency = Math.max(1, parseInt(next(), 10)); break;
      case '--baseline': out.baseline = next(); break;
      case '--update-baseline': out.updateBaseline = true; break;
      case '--json': out.json = next(); break;
      case '-h': case '--help': usage(); process.exit(0); break;
      default: die(`unknown argument: ${a}`);
    }
  }
  if (!out.ks.includes(out.gateK)) out.ks.push(out.gateK);
  out.ks.sort((a, b) => a - b);
  return out;
}

function usage() {
  console.log(`retrieval-eval — real-pipeline retrieval eval

  --cases <path>        cases YAML (default corpus/suite/tests/retrieval-cases.yaml)
  --base <url>          engine base URL (default $RAG_BRAIN_BASE_URL or http://localhost:9093)
  --brain <slug>        brain slug (default suite)
  --key <key>           admin API key (default $ADMIN_API_KEY)
  --visibility <V>      PUBLIC|INTERNAL|SECURE — omit for the admin view
  --scope <slug>        analyzer scope
  --k 1,3,5             report recall at these cutoffs
  --gate-k 3            the cutoff a pass/fail verdict uses
  --concurrency 4       parallel requests
  --baseline <path>     compare against a committed baseline; exit 1 on regression
  --update-baseline     write current results to --baseline instead of comparing
  --json <path>         also write the full result set as JSON`);
}

function die(msg) {
  console.error(`✖ ${msg}`);
  process.exit(2);
}

// ── engine ────────────────────────────────────────────────────────────────────

async function retrieve(opts, question) {
  const url = new URL(`${opts.base}/api/ai/documents/test-retrieval`);
  url.searchParams.set('question', question);
  if (opts.brain) url.searchParams.set('brain', opts.brain);
  if (opts.visibility) url.searchParams.set('visibility', opts.visibility);
  if (opts.scope) url.searchParams.set('analyzerScope', opts.scope);

  const res = await fetch(url, { headers: { 'X-Admin-Api-Key': opts.key } });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText} — ${(await res.text()).slice(0, 200)}`);
  }
  return res.json();
}

/** Distinct externalDocIds in rank order — the doc-level ranking a case asserts on. */
function rankedDocIds(chunks) {
  const seen = [];
  for (const c of chunks || []) {
    const id = c.externalDocId;
    if (id && !seen.includes(id)) seen.push(id);
  }
  return seen;
}

async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let cursor = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const i = cursor++;
      results[i] = await fn(items[i], i);
    }
  }));
  return results;
}

// ── metrics ───────────────────────────────────────────────────────────────────

function summarize(results, ks) {
  const scored = results.filter((r) => !r.error);
  const metrics = {};
  for (const k of ks) {
    const hits = scored.filter((r) => r.rank !== null && r.rank <= k).length;
    metrics[`recall@${k}`] = scored.length ? round(hits / scored.length) : 0;
  }
  const mrr = scored.reduce((sum, r) => sum + (r.rank ? 1 / r.rank : 0), 0);
  metrics.mrr = scored.length ? round(mrr / scored.length) : 0;
  return metrics;
}

const round = (n) => Math.round(n * 1000) / 1000;

// ── baseline ──────────────────────────────────────────────────────────────────

function loadBaseline(file) {
  if (!fs.existsSync(file)) return null;
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function writeBaseline(file, opts, results, metrics) {
  const cases = {};
  for (const r of results) {
    if (r.error) continue;
    cases[r.query] = { expected: r.expected, rank: r.rank, hit: r.rank !== null && r.rank <= opts.gateK };
  }
  const payload = {
    _comment: `Baseline for ${path.basename(opts.cases)} at gate-k=${opts.gateK}. `
      + 'CI fails when a hit here becomes a miss. Regenerate with --update-baseline '
      + 'once an intentional change is reviewed.',
    brain: opts.brain,
    cases: opts.cases,
    gateK: opts.gateK,
    metrics,
    caseResults: cases,
  };
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(payload, null, 2)}\n`);
}

/** @returns {{regressions: object[], improvements: object[], newMisses: object[]}} */
function diffBaseline(baseline, results, gateK) {
  const prior = baseline.caseResults || {};
  const regressions = [];
  const improvements = [];
  const newMisses = [];
  for (const r of results) {
    if (r.error) continue;
    const hit = r.rank !== null && r.rank <= gateK;
    const was = prior[r.query];
    if (!was) {
      if (!hit) newMisses.push(r);
      continue;
    }
    if (was.hit && !hit) regressions.push({ ...r, priorRank: was.rank });
    if (!was.hit && hit) improvements.push(r);
  }
  return { regressions, improvements, newMisses };
}

// ── main ──────────────────────────────────────────────────────────────────────

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts.key) die('no admin key — pass --key or set ADMIN_API_KEY');
  if (!fs.existsSync(opts.cases)) die(`cases file not found: ${opts.cases}`);

  const suite = yaml.load(fs.readFileSync(opts.cases, 'utf8'));
  const cases = (suite?.cases || []).filter((c) => c.query && c.expected_document_id);
  if (!cases.length) die(`no usable cases in ${opts.cases}`);

  console.log(`Retrieval eval — ${cases.length} cases · brain "${opts.brain}" · ${opts.base}`);
  console.log(`(real pipeline: embeddings + hybrid + rerank; each case costs a model call)\n`);

  const results = await mapLimit(cases, opts.concurrency, async (c) => {
    const base = { query: c.query, expected: c.expected_document_id };
    try {
      const body = await retrieve(opts, c.query);
      const ranked = rankedDocIds(body.chunks);
      const idx = ranked.indexOf(c.expected_document_id);
      return {
        ...base,
        rank: idx === -1 ? null : idx + 1,
        ranked: ranked.slice(0, 5),
        confidence: body.confidence,
        sufficientEvidence: body.sufficientEvidence,
      };
    } catch (e) {
      return { ...base, error: e.message, rank: null, ranked: [] };
    }
  });

  // An expected id that never appears in ANY result set is very likely not
  // ingested (or is a typo) rather than merely mis-ranked — a different bug.
  const everSeen = new Set(results.flatMap((r) => r.ranked));
  const unknownIds = [...new Set(results
    .filter((r) => !r.error && r.rank === null && !everSeen.has(r.expected))
    .map((r) => r.expected))];

  const errors = results.filter((r) => r.error);
  const metrics = summarize(results, opts.ks);

  for (const r of results.filter((r) => !r.error && (r.rank === null || r.rank > opts.gateK))) {
    console.log(`✖ "${r.query}"`);
    console.log(`    expected ${r.expected} — ${r.rank ? `ranked #${r.rank}` : 'not retrieved'}`);
    console.log(`    got: ${r.ranked.join(', ') || '(nothing)'}`);
    if (r.sufficientEvidence === false) console.log('    ⚠ evidence judged INSUFFICIENT — this question would refuse');
  }
  for (const r of errors) console.log(`✖ "${r.query}" — request failed: ${r.error}`);

  if (unknownIds.length) {
    console.log('\n⚠ expected ids never seen in any result — likely not ingested, or a typo:');
    unknownIds.forEach((id) => console.log(`    ${id}`));
  }

  console.log('\n── metrics ──');
  for (const k of opts.ks) console.log(`  recall@${k}  ${metrics[`recall@${k}`].toFixed(3)}`);
  console.log(`  MRR       ${metrics.mrr.toFixed(3)}`);
  const refusals = results.filter((r) => r.sufficientEvidence === false).length;
  if (refusals) console.log(`  would-refuse: ${refusals}/${results.length - errors.length}`);
  if (errors.length) console.log(`  request errors: ${errors.length}`);

  if (opts.json) {
    fs.mkdirSync(path.dirname(path.resolve(opts.json)), { recursive: true });
    fs.writeFileSync(opts.json, `${JSON.stringify({ opts: { ...opts, key: '***' }, metrics, results }, null, 2)}\n`);
    console.log(`\nwrote ${opts.json}`);
  }

  // Request failures are infrastructure problems, not eval verdicts — never let
  // an unreachable engine read as "retrieval got worse".
  if (errors.length && errors.length === results.length) {
    console.error('\n✖ every request failed — is the engine running and the admin key right?');
    process.exit(2);
  }

  if (opts.baseline && opts.updateBaseline) {
    writeBaseline(opts.baseline, opts, results, metrics);
    console.log(`\n✔ baseline written to ${opts.baseline}`);
    process.exit(0);
  }

  if (opts.baseline) {
    const baseline = loadBaseline(opts.baseline);
    if (!baseline) die(`baseline not found: ${opts.baseline} (create it with --update-baseline)`);
    const { regressions, improvements, newMisses } = diffBaseline(baseline, results, opts.gateK);

    console.log(`\n── vs baseline (gate-k=${opts.gateK}) ──`);
    for (const r of regressions) {
      console.log(`✖ REGRESSION "${r.query}" — was #${r.priorRank}, now ${r.rank ? `#${r.rank}` : 'absent'}`);
    }
    for (const r of newMisses) {
      console.log(`✖ NEW CASE MISSES "${r.query}" — expected ${r.expected}`);
    }
    for (const r of improvements) {
      console.log(`ℹ improved: "${r.query}" now #${r.rank} — re-run with --update-baseline to lock it in`);
    }
    if (!regressions.length && !newMisses.length) {
      console.log(`✔ no regressions${improvements.length ? ` (${improvements.length} improved)` : ''}`);
    }
    process.exit(regressions.length || newMisses.length ? 1 : 0);
  }

  const misses = results.filter((r) => !r.error && (r.rank === null || r.rank > opts.gateK)).length;
  console.log(`\n${misses ? '✖' : '✔'} ${results.length - errors.length - misses} hit@${opts.gateK}, ${misses} missed`);
  process.exit(misses ? 1 : 0);
}

main().catch((e) => {
  console.error(`✖ ${e.stack || e.message}`);
  process.exit(2);
});
