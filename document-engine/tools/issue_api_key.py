#!/usr/bin/env python3
"""Mint an engine API key: print the raw key ONCE, and the row to insert.

WHY THIS IS A SCRIPT AND NOT AN ENDPOINT
----------------------------------------
Issuing a credential is a rare, privileged, auditable act. An HTTP route that mints
keys is a permanent piece of attack surface that must itself be authenticated,
authorized, rate-limited, and kept out of every log — to serve an operation that
happens a handful of times per deployment. A script run by whoever already holds the
database and the salt adds no surface at all: it can do nothing an operator could not
already do by hand, it just stops them doing the HMAC by hand and getting it wrong.

WHAT IT DOES NOT DO
-------------------
It does not touch the database. It prints an INSERT for a human to review and run,
so the act of granting access stays a deliberate, reviewable step rather than a side
effect of running a script. It never writes the raw key to a file, and it never logs
it — the one copy goes to stdout, once, for the operator to hand to the consumer
through whatever secret channel they already use.

THE HASH CONTRACT
-----------------
``api_key.key_hash`` is ``HMAC-SHA256(salt, rawKey)`` in lower-case hex, where the
salt is the server's ``DOCENGINE_APIKEY_SALT``. This file is a SECOND implementation
of that contract (``ApiKeyHasher.java`` is the first), so the two are pinned to one
another by a known-answer vector: ``--self-test`` here and
``the_hash_matches_the_vector_the_issuance_tool_pins`` in ``ApiKeyHasherTest``
assert the SAME triple. If either implementation drifts, one of those two fails.

USAGE
-----
    export DOCENGINE_APIKEY_SALT='...'          # the running server's salt
    python3 tools/issue_api_key.py \\
        --org 3fa85f64-5717-4562-b3fc-2c963f66afa6 \\
        --name 'rag-brain instance control' \\
        --scopes READONLY,ENGINE_RESULT_READ \\
        --expires 2027-01-01T00:00:00Z

    python3 tools/issue_api_key.py --self-test   # verify the hash contract only
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import re
import secrets
import sys
import uuid

#: Distinguishes an engine key at a glance in a secret store or an incident. Not a
#: security property — the entropy below is.
KEY_PREFIX = "pds_live_"

#: 32 bytes from the OS CSPRNG, hex-encoded. Far beyond guessing, and the stored form
#: is an HMAC, so a database leak alone does not yield the key.
KEY_ENTROPY_BYTES = 32

#: The known-answer vector. ApiKeyHasherTest pins the SAME three values in Java; if
#: these two ever disagree, one of the implementations has drifted and a key minted
#: here would authenticate nowhere.
VECTOR_SALT = "a-server-side-salt"
VECTOR_RAW = "pds_live_deadbeefcafef00d"
VECTOR_HASH = "bba5b41eb9141d793eaed928d60605dc56d412492533d1e6f11ad1ff8f2c91b9"

#: Scopes the engine actually understands. A typo like ENGINE_RESULTS_READ would mint
#: a key that authenticates (it holds a Role scope) and then 403s on the one route it
#: was created for — a failure that looks like a server bug from the consumer's side.
#: Anything outside this set needs --allow-unknown-scope, so the typo path is loud.
ROLE_SCOPES = {"READONLY", "PROCESSOR", "REVIEWER", "ADMIN"}
CAPABILITY_SCOPES = {"ENGINE_RESULT_READ", "ACT_AS_USER"}
KNOWN_SCOPES = ROLE_SCOPES | CAPABILITY_SCOPES


def key_hash(salt: str, raw_key: str) -> str:
    """HMAC-SHA256(salt, rawKey), lower-case hex — ApiKeyHasher.hash's exact contract."""
    return hmac.new(
        salt.encode("utf-8"), raw_key.encode("utf-8"), hashlib.sha256
    ).hexdigest()


def generate_raw_key() -> str:
    return KEY_PREFIX + secrets.token_hex(KEY_ENTROPY_BYTES)


def self_test() -> int:
    actual = key_hash(VECTOR_SALT, VECTOR_RAW)
    if actual != VECTOR_HASH:
        print(
            "SELF-TEST FAILED: the hash contract has drifted.\n"
            f"  salt     {VECTOR_SALT}\n"
            f"  key      {VECTOR_RAW}\n"
            f"  expected {VECTOR_HASH}\n"
            f"  actual   {actual}",
            file=sys.stderr,
        )
        return 1
    print(f"self-test OK — HMAC-SHA256 lower-case hex, 64 chars: {actual}")
    return 0


def sql_literal(value: str) -> str:
    """A single-quoted SQL literal with quotes doubled. Names are operator-supplied."""
    return "'" + value.replace("'", "''") + "'"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Mint an engine API key and print the row to insert.",
        epilog="The raw key is printed ONCE and is not recoverable afterwards.",
    )
    parser.add_argument("--org", help="tenant UUID the key belongs to")
    parser.add_argument("--name", help="human label for the key, e.g. the consuming service")
    parser.add_argument(
        "--scopes",
        help="comma-separated scopes; must include a Role scope or the key cannot authenticate",
    )
    parser.add_argument(
        "--expires",
        default=None,
        help="optional expiry as a timestamptz literal (e.g. 2027-01-01T00:00:00Z); "
        "omit for a non-expiring key",
    )
    parser.add_argument(
        "--salt",
        default=os.environ.get("DOCENGINE_APIKEY_SALT"),
        help="server salt; defaults to $DOCENGINE_APIKEY_SALT",
    )
    parser.add_argument(
        "--allow-unknown-scope",
        action="store_true",
        help="permit a scope this engine does not model (rejected by default, to catch typos)",
    )
    parser.add_argument(
        "--self-test", action="store_true", help="verify the hash contract and exit"
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    missing = [f for f in ("org", "name", "scopes") if not getattr(args, f)]
    if missing:
        parser.error("missing required argument(s): " + ", ".join("--" + m for m in missing))
    if not args.salt:
        parser.error(
            "no salt: pass --salt or set DOCENGINE_APIKEY_SALT to the value the server runs with. "
            "A key hashed under the wrong salt authenticates nowhere."
        )

    try:
        org = uuid.UUID(str(args.org))
    except ValueError:
        parser.error(f"--org is not a UUID: {args.org!r}")

    scopes = [s.strip() for s in str(args.scopes).split(",") if s.strip()]
    if not scopes:
        parser.error("--scopes is empty")
    unknown = [s for s in scopes if s not in KNOWN_SCOPES]
    if unknown and not args.allow_unknown_scope:
        parser.error(
            f"unrecognized scope(s): {', '.join(unknown)}. "
            f"Known: {', '.join(sorted(KNOWN_SCOPES))}. "
            "Pass --allow-unknown-scope if this is deliberate."
        )
    if not any(s in ROLE_SCOPES for s in scopes):
        parser.error(
            "no Role scope among "
            + ", ".join(scopes)
            + ". ApiKeyAuthFilter refuses to bind a key that names no Role, so this key would "
            "401 on every request. A read-only consumer wants READONLY,ENGINE_RESULT_READ."
        )
    if args.expires is not None and not re.fullmatch(r"[0-9T:+\-. Zz]{4,40}", args.expires):
        parser.error(f"--expires does not look like a timestamp literal: {args.expires!r}")

    raw_key = generate_raw_key()
    digest = key_hash(args.salt, raw_key)
    expires_sql = "NULL" if args.expires is None else sql_literal(args.expires) + "::timestamptz"
    scopes_sql = "ARRAY[" + ", ".join(sql_literal(s) for s in scopes) + "]::text[]"

    print("=" * 78)
    print("RAW KEY — shown once, stored nowhere. Hand it over out of band, then discard.")
    print("=" * 78)
    print(raw_key)
    print()
    print("Insert this row (review it first; this tool does not touch the database):")
    print()
    print("INSERT INTO api_key (org_id, name, key_hash, scopes, expires_at)")
    print(
        f"VALUES ({sql_literal(str(org))}::uuid, {sql_literal(args.name)}, "
        f"{sql_literal(digest)}, {scopes_sql}, {expires_sql});"
    )
    print()
    print("Then verify, WITHOUT the raw key, that the row is the one you meant:")
    print(
        f"  SELECT id, name, scopes, expires_at, revoked_at FROM api_key "
        f"WHERE key_hash = {sql_literal(digest)};"
    )
    print()
    print("To revoke later (revocation is immediate — the filter reads revoked_at per request):")
    print(
        f"  UPDATE api_key SET revoked_at = now() WHERE key_hash = {sql_literal(digest)};"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
