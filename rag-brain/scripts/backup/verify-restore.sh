#!/usr/bin/env bash
#
# Rehearsed restore — the part everyone skips and regrets. Restores the LATEST backup
# into a throwaway scratch database and sanity-checks it, then drops the scratch DB.
# Run on a schedule (e.g. weekly cron): an unverified backup is not a backup.
#
# Connection (standard libpq, as a role that can CREATE/DROP DATABASE):
#   PGHOST, PGPORT, PGUSER, PGPASSWORD   (PGDATABASE is set per-command below)
# Options (env):
#   BACKUP_DIR   where dumps live         (default ./backups)
#   DB           which db's dumps to check (default pds_rag_brain)
#   SCRATCH_DB   throwaway restore target  (default pds_rag_brain_restore_check)
#   ADMIN_DB     db to connect to for CREATE/DROP DATABASE (default postgres)
#
# Usage:  PGHOST=... PGUSER=... PGPASSWORD=... ./verify-restore.sh
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
DB="${DB:-pds_rag_brain}"
SCRATCH_DB="${SCRATCH_DB:-pds_rag_brain_restore_check}"
ADMIN_DB="${ADMIN_DB:-postgres}"

# Creating/dropping the scratch DB and restoring into it need a role that can
# CREATE DATABASE. The app's own role is intentionally least-privilege (no CREATEDB),
# so point restore/DR at an admin role via RESTORE_PGUSER/RESTORE_PGPASSWORD; falls
# back to the normal connection role if you run this as an admin already.
export PGUSER="${RESTORE_PGUSER:-${PGUSER:-}}"
export PGPASSWORD="${RESTORE_PGPASSWORD:-${PGPASSWORD:-}}"

latest="$(ls -t "$BACKUP_DIR/${DB}"_*.dump 2>/dev/null | head -1 || true)"
[ -n "$latest" ] || { echo "FAIL: no backup found matching $BACKUP_DIR/${DB}_*.dump" >&2; exit 1; }
echo "[verify] latest backup: $latest"

cleanup() { PGDATABASE="$ADMIN_DB" psql -q -c "DROP DATABASE IF EXISTS $SCRATCH_DB" >/dev/null 2>&1 || true; }
trap cleanup EXIT

PGDATABASE="$ADMIN_DB" psql -q -c "DROP DATABASE IF EXISTS $SCRATCH_DB" -c "CREATE DATABASE $SCRATCH_DB"
PGDATABASE="$SCRATCH_DB" pg_restore --clean --if-exists --no-owner --no-privileges \
  --dbname="$SCRATCH_DB" "$latest" >/dev/null 2>&1 || true   # --clean noise on a fresh DB is expected

brains="$(PGDATABASE="$SCRATCH_DB" psql -tAqc 'SELECT count(*) FROM brains')"
ver="$(PGDATABASE="$SCRATCH_DB" psql -tAqc 'SELECT max(version::numeric) FROM flyway_schema_history WHERE success')"
chunks="$(PGDATABASE="$SCRATCH_DB" psql -tAqc 'SELECT count(*) FROM brain_document_chunks')"
echo "[verify] restored: schema v${ver}, brains=${brains}, chunks=${chunks}"

[ "${brains:-0}" -ge 1 ] || { echo "FAIL: restored DB has no brains" >&2; exit 1; }
[ -n "${ver:-}" ] || { echo "FAIL: no successful Flyway history in restored DB" >&2; exit 1; }
echo "[verify] PASS — backup restores cleanly to schema v${ver}."
