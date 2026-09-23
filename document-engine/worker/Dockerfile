# parser-worker — Python 3.12, FastAPI. Phase 2: the real parsing stack.
#
# Stateless by construction: no database driver, no cloud SDK, no credentials.
# Bytes in, JSON out, nothing persisted. See ARCHITECTURE.md 7.
#
# ── Two stages, one environment ──────────────────────────────────────────────
# `base` owns the OS, the OCR binaries and the pinned Python runtime. `runtime`
# hardens it and ships; `test` adds the dev tools and is what CI runs the suite
# in. They therefore cannot disagree about a library version — which is not
# housekeeping, it is the reason the golden files mean anything.
#
# The first CI run proved the point. The workflow installed tesseract from the
# runner's own apt (5.3.4) while this image carries Debian's (5.5.0), and the OCR
# golden diverged between them — output pinned to a sha256 cannot survive two
# OCR engines. Goldens are now generated and checked in this image alone; see
# worker/tests/conftest.py, which refuses to rewrite them anywhere else.
#
# Licence notes (docs/LICENSING.md): tesseract-ocr is Apache-2.0, invoked via
# pytesseract as a subprocess. rapidocr-onnxruntime is installed with --no-deps
# because it DECLARES non-headless opencv-python; only opencv-python-headless
# (the 5.1 exemption: bundled FFmpeg LGPL notice) may ship in this image. Its
# real runtime deps are pinned in requirements.txt. pi-heif (5.4) brings libheif
# and libde265 in its own wheel — no apt package, and no x265, so no GPL binary
# enters this image.

FROM python:3.12-slim AS base

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# tesseract: OSD + fallback OCR engine. libglib: opencv-headless runtime link.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN grep -v '^rapidocr-onnxruntime' requirements.txt > /tmp/requirements-base.txt \
 && pip install --no-cache-dir -r /tmp/requirements-base.txt \
 && pip install --no-cache-dir --no-deps \
      "$(grep '^rapidocr-onnxruntime' requirements.txt)" \
 && python -c "import cv2, rapidocr_onnxruntime, pytesseract, pypdfium2, pdfplumber, pi_heif" \
 && pip list --format=freeze | grep -c '^opencv-python==' | grep -q '^0$' \
 || (echo 'FATAL: non-headless opencv-python present in image' && exit 1)

# ── Test image: the same environment plus the tools to exercise it ───────────
# Not built by a plain `docker build` — `runtime` below is the final stage, and
# BuildKit skips stages the target does not depend on. CI selects it explicitly
# with `--target test` and mounts the repo, so no source is copied here.
#
# requirements-dev.txt begins with `-r requirements.txt`, which would reinstall
# rapidocr WITH its declared non-headless opencv and undo the care taken above.
# base has already installed that file, so only the dev-only lines are wanted;
# the opencv guard is repeated because a dev tool must not smuggle it back in.
FROM base AS test

ENV PDS_GOLDEN_ENV=worker-image

COPY requirements-dev.txt ./
RUN grep -v -e '^-r ' -e '^#' -e '^[[:space:]]*$' requirements-dev.txt > /tmp/requirements-only-dev.txt \
 && pip install --no-cache-dir -r /tmp/requirements-only-dev.txt \
 && pip list --format=freeze | grep -c '^opencv-python==' | grep -q '^0$' \
 || (echo 'FATAL: a dev dependency pulled in non-headless opencv-python' && exit 1)

WORKDIR /repo
CMD ["pytest", "-q"]

# ── Runtime image: what actually ships. Must stay the LAST stage ─────────────
FROM base AS runtime

ENV PYTHONPATH=/app/src

COPY src ./src

# Non-root, and no writable application directory: the worker has nowhere to
# persist even if a future change tried to. NOTE: pytesseract stages rasters
# through $TMPDIR internally — deployments must mount /tmp as tmpfs (compose
# does; ECS task definitions must too) so page images never touch disk.
RUN useradd --system --create-home --home-dir /home/worker worker \
 && chown -R root:root /app && chmod -R a-w /app
USER worker

EXPOSE 9091
CMD ["uvicorn", "pragmaticds_docengine_worker.api:app", "--host", "0.0.0.0", "--port", "9091"]
