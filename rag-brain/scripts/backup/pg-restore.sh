#!/usr/bin/env bash
#
# Restore a rag-brain pg_dump (custom format) into a target Postgres database.
# Refuses to clobber a database that already has application tables unless --force.
#
# Connection to the TARGET (standard libpq): PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
#
# Usage:  PGDATABASE=pds_rag_brain ./pg-restore.sh <dump-file> [--force]
set -euo pipefail

DUMP="${1:?usage: pg-restore.sh <dump-file> [--force]  (target set via PGDATABASE)}"
FORCE="${2:-}"
[ -f "$DUMP" ] || { echo "ERROR: dump not found: $DUMP" >&2; exit 1; }

# Restoring (especially --clean, or into a fresh DR database) needs owner/admin
# rights; the app's role is intentionally least-privilege. Use an admin role when set.
export PGUSER="${RESTORE_PGUSER:-${PGUSER:-}}"
export PGPASSWORD="${RESTORE_PGPASSWORD:-${PGPASSWORD:-}}"

# Guard: refuse to overwrite a populated database unless explicitly forced.
existing="$(psql -tAqc "SELECT count(*) FROM information_schema.tables WHERE table_name='brains'" 2>/dev/null || echo 0)"
if [ "${existing:-0}" != "0" ] && [ "$FORCE" != "--force" ]; then
  echo "REFUSING: target database '${PGDATABASE:-?}' already has a 'brains' table." >&2
  echo "          Pass --force to overwrite it (DESTRUCTIVE), or point PGDATABASE at an empty DB." >&2
  exit 1
fi

echo "[restore] pg_restore -> ${PGDATABASE:-target}"
# --clean --if-exists: drop-then-recreate objects so a re-restore is idempotent.
pg_restore --clean --if-exists --no-owner --no-privileges --dbname="${PGDATABASE:?set PGDATABASE}" "$DUMP"
echo "[restore] OK. Start the app against this DB (ddl-auto=validate confirms the schema) and smoke-test an /ask."
