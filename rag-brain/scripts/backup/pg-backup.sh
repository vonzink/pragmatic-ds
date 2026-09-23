#!/usr/bin/env bash
#
# Timestamped logical backup of a rag-brain Postgres database, plus (optionally) the
# local document/pack blobs. Portable across the docker-compose DB, a self-hosted
# server, and RDS — it uses standard libpq env vars, so it works anywhere psql works.
#
# On RDS/Aurora, prefer MANAGED automated backups + point-in-time recovery (see
# README.md) as the primary DR mechanism; run this as a belt-and-suspenders logical
# export you can restore into any Postgres.
#
# Connection (standard libpq):
#   PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
# Options (env):
#   BACKUP_DIR   where dumps are written      (default ./backups)
#   RETAIN_DAYS  local pruning window          (default 14)
#   DOCUMENT_STORAGE_PATH  local doc blobs to tar (skip if using the S3 corpus source)
#   GENERATED_PACKS_PATH   generated packs dir to tar
#
# Usage:  PGHOST=... PGUSER=... PGPASSWORD=... PGDATABASE=pds_rag_brain ./pg-backup.sh
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETAIN_DAYS="${RETAIN_DAYS:-14}"
DB="${PGDATABASE:-pds_rag_brain}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$BACKUP_DIR"
dump="$BACKUP_DIR/${DB}_${ts}.dump"

echo "[backup] pg_dump ${DB} -> ${dump}"
# --format=custom: compressed + supports selective/parallel restore.
pg_dump --format=custom --no-owner --no-privileges --file="$dump" "$DB"

# Document originals + generated packs (skip when using the S3 corpus source — S3 is
# already durable). Chunks/embeddings live in Postgres and are covered by the dump.
if [ -n "${DOCUMENT_STORAGE_PATH:-}" ] && [ -d "${DOCUMENT_STORAGE_PATH}" ]; then
  tar czf "$BACKUP_DIR/documents_${ts}.tgz" -C "$DOCUMENT_STORAGE_PATH" .
  echo "[backup] documents -> $BACKUP_DIR/documents_${ts}.tgz"
fi
if [ -n "${GENERATED_PACKS_PATH:-}" ] && [ -d "${GENERATED_PACKS_PATH}" ]; then
  tar czf "$BACKUP_DIR/packs_${ts}.tgz" -C "$GENERATED_PACKS_PATH" .
  echo "[backup] packs -> $BACKUP_DIR/packs_${ts}.tgz"
fi

# Prune local copies older than the window (off-box storage keeps the long tail).
find "$BACKUP_DIR" -type f \( -name '*.dump' -o -name '*.tgz' \) -mtime +"$RETAIN_DAYS" -print -delete || true

echo "[backup] OK. Now push OFF-BOX, e.g.:"
echo "         aws s3 sync \"$BACKUP_DIR\" s3://YOUR-BUCKET/rag-brain-backups/${DB}/ --storage-class STANDARD_IA"
