# Issuing an engine API key

A machine consumer authenticates to the engine with a key in `X-DocEngine-Api-Key`.
This is the supported way to mint one.

There is deliberately **no HTTP endpoint that mints keys**. Issuing a credential happens
a handful of times per deployment, and a route that does it would be permanent attack
surface needing its own authentication, authorization, rate limiting and log hygiene —
to save an operator who already holds the database and the salt from computing one HMAC.
The tool below does the HMAC; a human still runs the `INSERT`, so granting access stays a
deliberate, reviewable act.

## Before you start

You need the **server's salt** — the `DOCENGINE_APIKEY_SALT` the target deployment runs
with. `api_key.key_hash` is `HMAC-SHA256(salt, rawKey)`, so a key hashed under the wrong
salt inserts cleanly, looks correct in the table, and authenticates nowhere. If the salt
is unset on the server, API-key auth is disabled entirely and every key is rejected
(`ApiKeyHasher` logs one WARN at startup saying so).

## Choosing scopes

Scopes are a `text[]` on the row. Two kinds, and the difference matters:

| Scope | Effect |
|---|---|
| `READONLY`, `PROCESSOR`, `REVIEWER`, `ADMIN` | Named after a `Role`; grants `ROLE_*`, which is what the RBAC matrix is written in. |
| `ENGINE_RESULT_READ` | Grants `SCOPE_ENGINE_RESULT_READ`, which appears on **four GET matchers and nowhere else**: `/v1/packages/*/engine-result`, `/v1/packages/*/engine-results/*`, `/v1/pages/*/spans`, `/v1/pages/*/structure`. |
| `ACT_AS_USER` | Unlocks the delegated `X-DocEngine-Acting-User` path. Grants no authority of its own — a delegated call carries the named person's role. |

**A key must name at least one Role scope**, or `ApiKeyAuthFilter` refuses to bind it and
every request 401s. That is on purpose: a principal that can authorize nothing should not
be authenticated, and it stops `ENGINE_RESULT_READ` alone from becoming a second, quieter
way in. The issuance tool refuses such a key rather than let you discover it in production.

### The read-only consumer recipe

A service that only ever **GETs parsed envelopes** — RAG Brain's instance control plane is
the motivating case — wants exactly:

```
READONLY,ENGINE_RESULT_READ
```

`READONLY` authenticates it and grants the ordinary `GET /v1/**` reads; `ENGINE_RESULT_READ`
adds the four raw-text reads and nothing else. It can read; it cannot upload, correct,
review, regroup, override a page verdict, or delete.

**Do not give such a service an `ADMIN` key.** Before `ENGINE_RESULT_READ` existed that was
the only way to reach an engine result, and it also grants every `/v1/**` write. That
over-grant is the reason this scope exists.

## Procedure

**1. Generate.** The raw key is printed once and stored nowhere:

```bash
export DOCENGINE_APIKEY_SALT='<the target deployment's salt>'

python3 tools/issue_api_key.py \
    --org 3fa85f64-5717-4562-b3fc-2c963f66afa6 \
    --name 'rag-brain instance control' \
    --scopes READONLY,ENGINE_RESULT_READ \
    --expires 2027-01-01T00:00:00Z
```

`--expires` is optional; omit it for a non-expiring key. Prefer an expiry — an expired key
fails closed, an eternal one fails only when somebody remembers it exists.

**2. Review, then insert.** The tool prints an `INSERT` and does not run it. Read it before
you do: check the org UUID is the tenant you mean and the scope list is what you intended.
Run it as a role that may write `api_key` for that org.

**3. Verify without the key.** The tool also prints a `SELECT` keyed on the hash, so you can
confirm the row landed as intended without handling the secret again.

**4. Hand it over out of band.** Give the raw key to the consuming service through whatever
secret channel you already use — a secret manager, not a ticket, not chat, not email. Then
discard your copy. It is not recoverable: only the HMAC is stored.

**5. Confirm from the consumer's side.** One authenticated read is the whole check:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H "X-DocEngine-Api-Key: $KEY" \
  https://<engine-host>/v1/packages/<a-real-package-id>/engine-result
```

`200` is success. `401` means the key is unknown, revoked, expired, or hashed under a
different salt — the engine deliberately does not say which. `403` means it authenticated
but lacks the scope: check the row's `scopes` includes `ENGINE_RESULT_READ` **exactly** (the
authority is granted verbatim, so `engine_result_read` will not match).

## Rotation and revocation

Rotation is issue-then-revoke, in that order, so the consumer is never without a working
key: mint the new one, deploy it, confirm a `200` with it, then revoke the old.

Revocation is immediate — the filter reads `revoked_at` on every request:

```sql
UPDATE api_key SET revoked_at = now() WHERE key_hash = '<hash>';
```

Revoke, never delete. The row is what `last_used_at` and any future audit reference hangs
off, and a deleted row makes a past request unexplainable.

## Things not to do

- **Do not hand-compute the hash.** Use the tool; it is pinned to `ApiKeyHasher` by a
  known-answer vector asserted on both sides (`--self-test` here,
  `the_hash_matches_the_vector_the_issuance_tool_pins` in `ApiKeyHasherTest`).
- **Do not store the raw key anywhere you control** after handing it over.
- **Do not reuse one key across consumers.** One key per consumer is what makes
  `last_used_at` meaningful and revocation surgical.
- **Do not add `ACT_AS_USER`** unless the consumer genuinely acts on behalf of named humans.
  A machine reading results acts as itself.
