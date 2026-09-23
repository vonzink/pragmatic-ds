# Backups & Disaster Recovery

The launch-gating item. rag-brain's irreplaceable state is:

- **Postgres** — brains, corpus chunks + embeddings, conversations, feedback, learned
  weights, and the compliance audit trail. This is the critical one.
- **Document originals** — uploaded files under `DOCUMENT_STORAGE_PATH` and generated
  packs under `GENERATED_PACKS_PATH`. (If you use the **S3 corpus source**, originals
  already live durably in S3 and only Postgres needs backing up.)

Chunks and embeddings are regenerable from the originals, but only by re-ingesting
(which costs embedding API calls), so back up Postgres and don't rely on re-ingest.

## Choose your mechanism

### If on RDS / Aurora (recommended for the host-app / dashboard.example.com)
Use **managed automated backups + point-in-time recovery** as the primary DR path — it
is continuous (seconds of RPO) and far better than periodic logical dumps:

- Enable automated backups, retention **≥ 14 days**, and PITR.
- Take a manual snapshot before each deploy/migration.
- Optionally also run `pg-backup.sh` on a schedule for an engine-independent logical
  export you can restore anywhere (portability / ransomware isolation).

### If self-hosted / docker-compose
Use the scripts here on a schedule:

- `pg-backup.sh` — timestamped `pg_dump` (custom format) + optional doc/pack tarballs,
  with local pruning. **Push the output off-box** (the script prints an `aws s3 sync`).
- `pg-restore.sh` — restore a dump into a target DB (refuses to clobber without `--force`).
- `verify-restore.sh` — **the rehearsal**: restores the latest backup into a scratch DB,
  sanity-checks it (schema version, brains, chunks), drops the scratch DB.

All scripts use standard libpq env vars (`PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE`),
so they work against compose, a server, or RDS.

### Zero-cron option: the backup sidecar (recommended for containerized stacks)

`docker-compose.backup.yml` (repo root) adds a `backup` service that runs a nightly
backup + weekly restore-rehearsal automatically — no host cron needed:

```bash
docker compose -f docker-compose.yml -f docker-compose.backup.yml up -d backup
```

It has been validated end-to-end against a live DB (backup → restore into a scratch DB
→ verify → drop), recovering brains, chunks, and schema version exactly. Still ship the
`rag_brain_backups` volume off-box (add an `aws s3 sync`).

### Database roles (important)

rag-brain should connect as a **least-privilege app role** (this deployment already
does — `DB_USERNAME=rag_brain_1`, no `CREATEDB`/superuser). That role is enough for
**backups** (`pg_dump` needs only read). **Restore and the rehearsal create a database**,
which needs an admin role — set `RESTORE_PGUSER` / `RESTORE_PGPASSWORD` (the sidecar
exposes `DB_ADMIN_USER` / `DB_ADMIN_PASSWORD` for this). Keep the app role least-priv;
only DR uses the admin role.

## Schedule (cron example, self-hosted)

```cron
# Nightly backup at 02:30, weekly restore rehearsal Sunday 03:30.
30 2 * * *  cd /opt/rag-brain && PGHOST=db PGUSER=pds_rag_brain PGPASSWORD=… PGDATABASE=pds_rag_brain \
             BACKUP_DIR=/var/backups/rag-brain RETAIN_DAYS=14 bash scripts/backup/pg-backup.sh \
             && aws s3 sync /var/backups/rag-brain s3://YOUR-BUCKET/rag-brain/pds_rag_brain/
30 3 * * 0  cd /opt/rag-brain && PGHOST=db PGUSER=pds_rag_brain PGPASSWORD=… \
             BACKUP_DIR=/var/backups/rag-brain bash scripts/backup/verify-restore.sh
```

## Multiple deployments (your setup)

Each rag-brain instance has its **own database**, so run one backup + one rehearsal per
DB — parameterize by `PGDATABASE`/`DB` and give each its own off-box prefix:

| Deployment | Backup target | Notes |
|---|---|---|
| host-app × N (incl. the other-local-rag replacement) | each app's DB | RDS PITR + nightly logical dump |
| dashboard.example.com | its DB | same |
| zvzsolutions.com AI | its DB | same |

If several suite apps share one DB, back that DB up once.

## Restore drill (DR runbook)

1. Provision a fresh Postgres (empty DB, pgvector available).
2. `PGDATABASE=rag_brain_new bash scripts/backup/pg-restore.sh <latest>.dump`
   (restore doc/pack tarballs to the new `DOCUMENT_STORAGE_PATH`/`GENERATED_PACKS_PATH`).
3. Point the app at it and boot — `ddl-auto=validate` proves the schema matches the code.
4. Smoke-test: `GET /actuator/health`, list brains, run one `/ask`.

**Targets:** RPO ≤ your backup interval (seconds with RDS PITR), RTO = provision +
restore time. Re-run the rehearsal after every schema migration.
