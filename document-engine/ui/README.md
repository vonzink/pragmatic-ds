# review-ui

The reviewer-facing front end for the Pragmatic DS Document Engine: the document on the left, the extracted
data on the right, and a click on any field highlighting exactly where that value came from.

**Presentation only.** Classification, extraction, normalisation, confidence and masking decisions
belong to the engine and the worker (see `docs/ARCHITECTURE.md`). The UI renders what the API says
and sends back what the reviewer did. The one piece of real logic that lives here is
`src/features/review/coordinates.ts`, and it is here because the conversion into the browser's
viewport can only happen in the browser.

## Running it

The UI talks to the engine API, so bring the stack up first, from the repository root:

```bash
docker compose up -d          # postgres, worker, api
```

Then, in `ui/`:

```bash
nvm use                       # honours the repository-root .nvmrc (Node 22)
npm ci
npm run dev                   # http://localhost:6173
```

`engines.node` is `>=22.13` rather than a bare `>=22`: `.nvmrc` pins the Node 22 line, and 22.13 is
the floor Vite 8 and `pdfjs-dist` 6 actually declare. Anything `nvm use` installs for `22` today
satisfies it.

`npm run dev` proxies `/v1/*` to `http://localhost:9090`, so the browser sees a single origin and no
CORS preflight happens at all. If you point the UI at the API directly instead (see
`VITE_API_BASE` below), the engine's `local` and `test` profiles already allow `http://localhost:6173`
as a CORS origin, so that works too — belt and braces.

### Ports

Every port in this stack is offset so it can coexist with the other local stacks on one machine.

| Port | Service | Why this number |
|---|---|---|
| 6173 | this UI (Vite dev server) | Vite's default; it is what the engine's dev CORS allowlist names |
| 9090 | engine API | 8080 is host-app's |
| 9091 | worker (FastAPI) | sits next to the API it serves |
| 6433 | Postgres | 5432 is host-app's, 6435 is rag-brain's |

The dev-server port is `strictPort`: if 6173 is taken, Vite fails loudly rather than silently moving
to 6174 and falling outside the CORS allowlist.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `VITE_API_BASE` | `/v1` | Base URL every API call is built on. Relative by default, so the dev proxy and a production build both just work. Set it to e.g. `http://localhost:9090/v1` to bypass the proxy. |
| `VITE_API_PROXY_TARGET` | `http://localhost:9090` | Where the dev server forwards `/v1`. Build-time only — it does not reach the browser. |
| `E2E_BASE_URL` | *(unset)* | If set, Playwright tests that URL instead of starting its own dev server. |

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server on 6173 with the `/v1` proxy |
| `npm run build` | Typecheck (`tsc -b`) then production build into `dist/` |
| `npm run preview` | Serve `dist/` on 6173 |
| `npm test` | Vitest, once |
| `npm run test:watch` | Vitest, watching |
| `npm run test:e2e` | Playwright (`e2e/`) |
| `npm run lint` | ESLint |
| `npm run typecheck` | `tsc -b`, no emit |

## Layout

```
src/
  App.tsx                     mounts PackageView; ?packageId= deep-links a package
  index.css                   Tailwind entry, minimal base layer
  lib/api/
    config.ts                 VITE_API_BASE
    types.ts                  wire types, hand-written from docs/api/openapi.json
    client.ts                 one fetch call site, one ApiError, RFC 9457 handling
  features/review/
    PackageView.tsx           the shell: upload, job polling, the shared selection
    PageViewer.tsx            left pane: pdf.js canvas, thumbnails, zoom, rotation
    EvidenceOverlay.tsx       the boxes; VALUE and LABEL distinguished
    FieldPanel.tsx            right pane: values, confidence, status, masking
    DocumentList.tsx          logical documents, and pages that fell out of them
    JobStatus.tsx             stage trail with error codes and skip reasons
    coordinates.ts            PDF points <-> viewport CSS px. The critical module.
    coordinates.test.ts       hand-derived expectations at 5 zooms x 4 rotations
    masking.ts                sensitive-value masking (presentation only)
    fields.ts                 "missing" and "low confidence", out of the JSX
    pdfRenderer.ts            the only module that imports pdf.js
    css.ts                    px()
  test/
    setup.ts                  jest-dom matchers + Testing Library cleanup
    fixtures.ts               API-shaped data from fixtures/truth/paystub_twopage.json
e2e/                          Playwright specs — need a live stack; CI does not run them
```

### Masking is not a security boundary

`masking.ts` hides values the schema marks `sensitive` behind `•••• 6789` until a reviewer clicks
to reveal. That is a shoulder-surfing control and nothing more: the unmasked value is still in the
`GET /v1/documents/{id}/fields` response the component rendered from, and
`GET /v1/packages/{id}/export` returns everything unmasked with no UI in front of it. The boundary
is the Phase 7 serializer — `docs/ARCHITECTURE.md` §14 says so, and this code must not be described
as if it says otherwise.

### pdf.js

`pdfRenderer.ts` is the only file that imports `pdfjs-dist`, and it does so through a dynamic
`import()` so the library lands in its own chunk. The worker is located with
`new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url)`, which Vite rewrites to a hashed
asset — a bare string path works in dev and 404s in production.

`PageViewer` takes the renderer as a prop. If pdf.js cannot load a file, the viewer falls back to
the server-rendered PNG (`GET /v1/pages/{id}/render`) and says why; the evidence boxes are placed
from page geometry either way, so the fallback cannot move them. Component tests pass
`pdfRenderer={null}`, because happy-dom has neither a canvas nor a Worker — which means **the
painted page itself is not covered by the unit suite**, only the geometry over it.

## coordinates.ts

Every evidence box the API returns is **PDF points, top-left origin, at rotation-0, rounded to
0.1pt** — the canonical space the worker normalises into
(`worker/src/pragmaticds_docengine_worker/geometry.py`). A browser needs CSS pixels in the *displayed*
viewport, which is scaled and possibly rotated. `coordinates.ts` is the only place those two spaces
meet.

It is pure functions with no React and no pdf.js import, because the failure mode it guards against
is the quiet one: a wrong quarter turn produces boxes that look like boxes, sit over the wrong
words, and raise nothing. The plan calls this out as the Phase 6 risk, and R1 in the overall risk
register.

Its tests derive every expected number from the geometry by hand — page dimensions, a real evidence
box out of `fixtures/truth/paystub_complete.json`, and the four rotation matrices — rather than
recording what the implementation happens to emit. One suite restates the worker's `rotate_box`
independently and asserts the two agree, so a disagreement about which way "rotation" turns fails
here rather than in production.

## Licences

CI runs the same gate over `ui/`'s dependency tree that it runs over the Python and Java trees:

```bash
npm ci --prefix ui
npx --yes license-checker --json --start ui > /tmp/npm-licences.json
python -m tools.license_gate_cli --format npm --report /tmp/npm-licences.json \
  --min-dependencies 50
```

`npm ci` is part of the check, not setup for it. `license-checker` walks `ui/node_modules`; without
it the gate inspects exactly one package — this one — and reports zero violations over a tree it
never saw. `--min-dependencies` is the backstop: the real tree is ~265 packages, so anything under
50 is a broken report rather than a lean one.

One thing about this tree is deliberate and will bite anyone who changes it casually:

- **`package.json` has no `"private": true`.** `license-checker` reports every private package as
  `UNLICENSED` regardless of its `license` field, which fails the gate on our own package. The
  declared `Apache-2.0` matches the repository's intent; a `prepublishOnly` script refuses to
  publish, which is what `private` was protecting against.

`eslint`, `typescript-eslint` and `happy-dom` are **no longer pinned for licence reasons.** They
were: `eslint@10`, `typescript-eslint@8.45+` and `jsdom@27+` pull `minimatch@10` / `lru-cache@11`,
which were relicensed to **BlueOak-1.0.0**. That licence is now on the allowlist — it is
OSI-approved, permissive, and carries an Apache-style patent grant — and the relicensing landed
mid-range (`lru-cache` 11.2.2 → 11.2.3), so excluding it meant freezing a lockfile rather than
pinning a dependency. See [`docs/LICENSING.md` §4.6](../docs/LICENSING.md). `happy-dom` stays as the
test environment on its own merits — faster and lighter than jsdom — not for licence reasons.
Upgrade these on the normal cadence; the gate, not a comment in this file, is what enforces the
policy.
