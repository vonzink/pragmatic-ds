# Licensing Policy — Pragmatic DS Document Engine

**Status:** Design approved 2026-07-30
**Intended release licence:** Apache-2.0 *(pending confirmation — see [Open questions](#8-open-questions))*

---

## 1. Why this document exists

The engine is intended for open-source release. That changes the licence calculus in one direction
only: **it tightens it.**

Under a private SaaS deployment, GPL-family obligations are arguable — you are not distributing.
Under an open-source release you **are** distributing, so GPL, LGPL, and AGPL move from "arguable" to
**hard blockers** incompatible with an Apache-2.0 project.

A second, less obvious point: **permissive is not obligation-free.** Apache-2.0 §4, BSD-3 clause 2,
and MIT all carry attribution requirements. Shipping a Docker image built from permissive
dependencies still obliges you to carry their notices.

## 2. Allowlist

Dependencies under these licences are permitted without review:

`MIT` · `BSD-2-Clause` · `BSD-3-Clause` · `Apache-2.0` · `ISC` · `MIT-CMU` / `HPND` ·
`PostgreSQL` · `Python-2.0` · `Zlib` · `Unlicense` · `CC0-1.0` · `FTL`

## 3. Denylist — the build fails

`AGPL-*` · `GPL-*` · `LGPL-*` · `SSPL` · `BUSL` · `Elastic License` · `CC-BY-NC` · any
"source available" or non-commercial licence · any dependency with **no** declared licence.

An unlicensed dependency is denied, not defaulted. "No LICENSE file" means all rights reserved, not
public domain.

## 4. Documented exceptions

Three, each narrow and deliberate.

### 4.1 Eclipse Temurin JDK 21 — GPL-2.0 **with Classpath Exception**

The Classpath Exception exists precisely to permit linking without copyleft propagation. Every
OpenJDK build — Temurin, Corretto, Zulu, Oracle — is GPLv2+CE; **there is no permissive JDK.**

Non-negotiable and non-problematic. Allowed for the JDK and JDK-derived base images only.

### 4.2 Hibernate ORM 6.x — LGPL-2.1-or-later

Arrives transitively via Spring Data JPA.

> **Correction (2026-07-31).** An earlier draft recorded Hibernate as "Apache-2.0 OR LGPL-2.1 dual"
> and elected Apache-2.0. **That was wrong.** The gate, run against the real dependency report,
> showed `hibernate-core 6.6.15.Final` declares LGPL-2.1-or-later only — the Apache-2.0 relicense
> lands in **Hibernate ORM 7**, which Spring Boot has not yet adopted.

The exemption as actually granted: hibernate-core is consumed as an **unmodified jar** over a public
API. LGPL linking obligations are satisfied by carrying the notice and preserving relinking (it is a
separate jar in the image — relinking is inherent). Same reasoning as the FFmpeg case in §5.1.

**Exit path:** upgrade to Hibernate ORM 7 (Apache-2.0) when Spring Boot adopts it, then delete this
exemption. Tracked, not permanent.

### 4.4 EPL-1.0 / EPL-2.0 — weak copyleft, allowlisted for unmodified use

The gate's first run against the real Java tree surfaced what every JVM stack carries: logback
(EPL-1.0), aspectjweaver (EPL-2.0), and the `jakarta.*` API jars (EPL-2.0, mostly dual with
BSD-3-style EDL or GPLv2+CE). EPL obligations attach to **modifications of EPL-licensed files**, not
to an independent work that links them — the Apache Foundation's "Category B" treatment.

> **Condition:** these are consumed as unmodified binaries. If Pragmatic DS ever patches an EPL file, that
> file's source must be published.

EPL-1.0 and EPL-2.0 are therefore on the allowlist itself rather than per-package exemptions; the
Eclipse **Distribution** License (EDL 1.0), despite the similar name, is plain BSD-3-Clause and is
aliased to it.

**MPL-2.0** joins them under the identical rationale — file-level copyleft attaching only to
modifications. Surfaced by `certifi`, which exists in effectively every Python environment.
**PSF-2.0** (typing_extensions) is simply permissive and was an allowlist omission.

### 4.3 FreeType, bundled inside PDFium — FTL **OR** GPL-2.0 (dual)

Arrives transitively inside the `pypdfium2` wheel.

> **Pragmatic DS elects FTL** (BSD-style, with an attribution requirement).

Satisfied by `THIRD-PARTY-NOTICES.md`.

### 4.6 Blue Oak Model License 1.0.0 — permissive, allowlisted outright

`minimatch`, `lru-cache`, `glob` and their siblings were relicensed from ISC to **BlueOak-1.0.0**,
so it now arrives transitively in almost any JS dependency tree — via eslint, typescript-eslint,
jsdom, and more.

BlueOak-1.0.0 is permissive and OSI-approved. It grants copyright **and patent** rights, imposes no
copyleft, and requires notice preservation only in source copies. It was written to be *clearer*
than MIT, not more or less permissive.

**Why the main allowlist and not a development-only tier.** A dev-only tier exists to hold licences
whose obligations you escape by not distributing them — the reason a GPL build tool does not infect
a binary. BlueOak has no such obligations to escape, so quarantining it would build a containment
boundary around something that needs no containing, while the version pins required to avoid it
block security updates on the linting and test toolchain. That is accepting a live risk to avoid a
theoretical one.

**This is not a precedent for a runtime/development split.** That split is still worth building
(Phase 7) and is motivated by a genuine *copyleft* development dependency, should one appear. When
it is built, the shipped tier must be derived from the ARTIFACT — the Rollup bundle for the UI,
`runtimeClasspath` for Gradle — never from `dependencies` vs `devDependencies`, which is an author's
declaration that a bundler is free to contradict.

## 5. Known obligations we do carry

### 5.1 `opencv-python-headless` — bundled FFmpeg (LGPL-2.1)

OpenCV itself is Apache-2.0. The PyPI wheels bundle FFmpeg under LGPL-2.1.

We use OpenCV only for deskew, adaptive binarization, and blank detection — **no video codecs are
exercised.** LGPL-2.1 dynamic-linking obligations are satisfied by carrying the notice and shipping
the unmodified wheel, which preserves relinking.

**Escape hatch:** replace with `scikit-image` (BSD-3) for zero obligation, at some loss of
thresholding and deskew quality. Documented so the decision stays reversible.

### 5.4 `pi-heif` — bundled libheif + libde265 (LGPL-3.0)

An iPhone writes HEIC unless the owner has changed a camera setting, so borrowers photographing
paystubs and bank statements send HEIC files. Rejecting them at upload was a real defect, not a
gap in coverage.

There is no permissive way to decode one. HEIC is HEVC-coded, and every HEVC decoder in practical
distribution is copyleft: libheif and libde265 are LGPL-3.0, and the encoder side (x265) is GPL-2.0.
Java has no option at all — ImageIO ships no HEIF plugin and no permissive one exists — which is why
ingestion accepts a HEIC on its magic bytes without decoding it and the worker is its only decode
gate.

`pi-heif` is the **decode-only** build of `pillow-heif`: its wheel carries libheif and libde265 and
**no encoder at all**. LGPL-3.0 dynamic-linking obligations are satisfied the same way as 5.1 — the
wheel is unmodified, relinking is preserved, and the notice is carried. No Pragmatic DS source is derived
from either library.

Two consequences worth stating, because both look like oversights otherwise:

- **`pillow-heif` is rejected, not exempted** (see 6). Its wheel bundles x265, and the project
  declares those wheels GPLv2 itself. There is no arm to elect.
- **No test in this repo can generate a HEIC**, because a generator would need an encoder and CI
  runs `pip-licenses` over the whole installed environment — so an encoder cannot hide in a
  test-only tier. The worker's HEIC fixtures are therefore committed constants with their recipe
  recorded; see `worker/tests/image/image_fixtures.py`.

**Escape hatch:** if the LGPL-3.0 exposure ever becomes unacceptable, drop the dependency and have
`MimeSniffer` keep recognising the `ftyp` brands while `UploadService` rejects them by name. The
borrower then gets "we cannot read HEIC, send a JPEG" instead of a parsed document — worse, but
honest, and it is a one-file change because the brand knowledge stays put.

### 5.2 Attribution across all three ecosystems

`THIRD-PARTY-NOTICES.md` is **generated** — Python, Java, and npm — from the same dependency-licence
reports the CI gate checks, so it cannot drift from what actually ships. It is never hand-edited; a
hand-maintained notices file is a notices file that is wrong.

Generator: `tools/third_party_notices.py`. Regenerate (from the repo root, with the three reports
the gate already produces, and the richer pip flags that carry the licence *text*):

```bash
./gradlew generateLicenseReport
pip-licenses --format=json --with-license-file --with-authors --with-urls > /tmp/pip-notices.json
npx --yes license-checker --json --start ui > /tmp/npm-licences.json
python -m tools.third_party_notices \
    --gradle build/reports/dependency-license/index.json \
    --pip /tmp/pip-notices.json --npm /tmp/npm-licences.json \
    --out THIRD-PARTY-NOTICES.md
```

Each component is listed with its version and SPDX licence; where the tool exposed per-package
licence text (Python and npm) the full text is reproduced in an appendix. Java components cite the
SPDX id + licence URL — the jk1 report JSON carries the URL in place of the text, a known limitation
noted in the file itself.

**The committed `THIRD-PARTY-NOTICES.md` is a snapshot; the release build regenerates it from the
actually-shipped tree.** CI proves the generator runs (and uploads the artifact) on every build, but
does **not** drift-check the committed file against a fresh regen: `worker/requirements-dev.txt`
pins only its direct dependencies, so 50+ transitives float and an exact-version-plus-text file
would legitimately differ between resolutions. Before a drift *gate* is worth enabling — and before
release notices are reproducible — the Python **runtime** dependencies must be pinned (a lock file).
That is the one remaining pre-distribution task here; see §8. The `--check` mode of the generator
exists for that day.

**This is attribution, not compliance.** It discharges the copyright/notice-preservation obligation
of the permissive licences; it is not a legal review of the release.

### 5.3 `caniuse-lite` — CC-BY-4.0 (data, not code)

Every React/Vite/Tailwind build pulls `caniuse-lite` transitively: `browserslist` requires it, and
both Vite (build-target resolution) and `autoprefixer` (Tailwind's PostCSS pipeline) require
`browserslist`. It is browser-support **data**, published under CC-BY-4.0.

CC-BY-4.0 imposes **no copyleft on our source** — the obligation is attribution, discharged by the
notices file in §5.2. It is allowlisted on that basis in `licenses/policy.toml`.

**Condition:** this entry exists for *data* dependencies. A CC-BY-licensed **code** dependency is a
different question — CC licences are not designed for software and the Creative Commons foundation
says so — and must be re-reviewed rather than waved through on this precedent.

**Escape hatch:** pinning `browserslist` config to explicit targets does not remove the dependency;
only dropping autoprefixer and Vite's target resolution would, which is not a trade worth making
for an attribution-only data file.

## 6. Rejected dependencies and why

| Dependency | Licence | Would have been used for |
|---|---|---|
| PyMuPDF | AGPL-3.0 | Render + text with boxes (the technically strongest single option) |
| poppler / pdf2image | GPL-2 | Rendering |
| Surya | GPL-3.0 | OCR |
| Marker | GPL-3.0 | Layout + reading order |
| Ghostscript (via Camelot) | AGPL-3.0 | Table extraction |
| `pillow-heif` | GPL-2.0 | HEIC decode **and encode** |

Each was rejected on licence alone, with a permissive replacement identified. See
[`PARSER_EVALUATION.md`](PARSER_EVALUATION.md).

`pillow-heif` is the one entry whose replacement is not permissive: `pi-heif`, the same project's
decode-only build, is LGPL-3.0 and carries the 5.4 exemption. The GPL arrives with x265, which
exists only to *encode*, and the engine never encodes a HEIC. Dropping the encoder drops the GPL —
and with it the ability to generate a HEIC in a test, which is a cost paid knowingly.

## 7. CI enforcement

Policy without enforcement decays. An AGPL dependency must not be able to arrive later through a
transitive upgrade.

| Ecosystem | Tool | Gate |
|---|---|---|
| Python | `pip-licenses` | Fails on any licence outside `licenses/allowed.txt` |
| Java | Gradle `com.github.jk1.dependency-license-report` | Fails on any licence outside the allowed config |
| npm | `license-checker` | `--onlyAllow` against the same list |

Additional gates in the same job:

- Generate `THIRD-PARTY-NOTICES.md` and fail if it is stale relative to lockfiles
- Secret scanning across the tree
- **Fixture provenance:** fail if any file under `fixtures/` lacks a generator provenance marker —
  the mechanical guarantee that no real borrower document is ever committed

This runs in **Phase 0**, before dependencies are added in anger, so the first violation is caught by
the first build rather than discovered at release.

## 8. Open questions

| # | Question | Default if unanswered |
|---|---|---|
| Q1 | Confirm **Apache-2.0** (express patent grant) over MIT | Apache-2.0 |
| Q2 | Confirm the open/private split: engine open, domain rule packs and extraction schemas private | Split as designed |
| Q3 | DCO sign-off vs. CLA for contributors | DCO — lighter and sufficient for Apache-2.0 |
| Q4 | Pin the worker **runtime** dependencies (a lock file) so release notices are reproducible and a notices drift-gate can be enabled (§5.2) | Pin before first distribution |

Nothing is published until Q1 and Q2 are confirmed, and **no code depends on the answer.** The
architecture already loads rule packs and extraction schemas as versioned data rather than code, so
the split needs a separate private repository and a loader path — not a restructuring.

## 9. Adding a dependency

1. Check its licence **and its transitive tree** against §2 and §3.
2. On the allowlist → add it; CI verifies.
3. On the denylist → find a permissive replacement, or make the case for a §4-style documented
   exception. Dual-licensed dependencies require an explicit recorded election.
4. Paid or commercial dependency → **must be identified explicitly** in the PR description and in
   this document. It is never introduced silently.
