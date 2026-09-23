"""The key-issuance tool: the hash contract, and the guardrails around minting.

The hash assertion here is half of a pair — ``ApiKeyHasherTest`` pins the SAME triple in
Java. Two implementations of one contract is the arrangement that lets keys be minted
without a JVM, and it is also exactly where a contract drifts silently: a key hashed
under a changed algorithm inserts cleanly, reads back plausibly, and then authenticates
nowhere. Whichever side moves, one of these two tests fails.
"""

import hashlib
import hmac
import re

import pytest

from tools import issue_api_key


def test_the_hash_is_hmac_sha256_of_the_key_under_the_salt_in_lowercase_hex():
    salt, raw = "a-server-side-salt", "pds_live_deadbeefcafef00d"
    expected = hmac.new(salt.encode(), raw.encode(), hashlib.sha256).hexdigest()

    assert issue_api_key.key_hash(salt, raw) == expected
    assert re.fullmatch(r"[0-9a-f]{64}", issue_api_key.key_hash(salt, raw))


def test_the_pinned_vector_matches_the_one_apikeyhashertest_asserts():
    # Change this only to change the contract — and change ApiKeyHasherTest with it.
    assert (
        issue_api_key.key_hash(issue_api_key.VECTOR_SALT, issue_api_key.VECTOR_RAW)
        == issue_api_key.VECTOR_HASH
    )


def test_self_test_passes():
    assert issue_api_key.self_test() == 0


def test_generated_keys_are_prefixed_and_unique():
    keys = {issue_api_key.generate_raw_key() for _ in range(64)}
    assert len(keys) == 64, "a repeat in 64 draws would mean the CSPRNG is not being used"
    for key in keys:
        assert key.startswith(issue_api_key.KEY_PREFIX)
        assert re.fullmatch(r"[0-9a-f]{64}", key[len(issue_api_key.KEY_PREFIX) :])


def test_a_scope_set_with_no_role_is_refused(capsys):
    # ApiKeyAuthFilter refuses to bind a key naming no Role, so such a key 401s on every
    # request. Catching it here turns a confusing runtime 401 into an argument error.
    with pytest.raises(SystemExit):
        issue_api_key.main(
            ["--org", "3fa85f64-5717-4562-b3fc-2c963f66afa6", "--name", "x",
             "--scopes", "ENGINE_RESULT_READ", "--salt", "s"]
        )
    assert "no Role scope" in capsys.readouterr().err


def test_an_unrecognized_scope_is_refused_unless_explicitly_allowed(capsys):
    args = ["--org", "3fa85f64-5717-4562-b3fc-2c963f66afa6", "--name", "x",
            "--scopes", "READONLY,ENGINE_RESULTS_READ", "--salt", "s"]
    with pytest.raises(SystemExit):
        issue_api_key.main(args)
    assert "unrecognized scope" in capsys.readouterr().err

    # Deliberate is still possible — the guard is against typos, not against new scopes.
    assert issue_api_key.main(args + ["--allow-unknown-scope"]) == 0


def test_a_missing_salt_is_refused_rather_than_defaulted(monkeypatch, capsys):
    monkeypatch.delenv("DOCENGINE_APIKEY_SALT", raising=False)
    with pytest.raises(SystemExit):
        issue_api_key.main(
            ["--org", "3fa85f64-5717-4562-b3fc-2c963f66afa6", "--name", "x",
             "--scopes", "READONLY"]
        )
    assert "no salt" in capsys.readouterr().err


def test_the_output_carries_the_raw_key_once_and_the_hash_never_together_with_it(capsys):
    assert (
        issue_api_key.main(
            ["--org", "3fa85f64-5717-4562-b3fc-2c963f66afa6", "--name", "svc",
             "--scopes", "READONLY,ENGINE_RESULT_READ", "--salt", "demo-salt"]
        )
        == 0
    )
    out = capsys.readouterr().out
    raw = re.search(rf"^{issue_api_key.KEY_PREFIX}[0-9a-f]{{64}}$", out, re.MULTILINE)
    assert raw, "the raw key is printed on its own line for the operator to copy"
    # Printed exactly once: a second occurrence would mean it also landed in the SQL.
    assert out.count(raw.group(0)) == 1
    digest = issue_api_key.key_hash("demo-salt", raw.group(0))
    assert digest in out, "the INSERT carries the hash"
    assert "INSERT INTO api_key" in out
    assert "revoked_at = now()" in out, "the revoke statement travels with the grant"


def test_an_operator_supplied_name_cannot_break_out_of_its_sql_literal():
    # The name reaches an INSERT the operator will run, so a stray apostrophe must not be able
    # to close the literal early. The property is a ROUND TRIP: strip the outer quotes, undouble
    # the inner ones, and the original name comes back — which can only hold if every apostrophe
    # in between was doubled, i.e. if the payload never terminated the literal.
    hostile = "o'brien's service'); DROP TABLE api_key; --"
    literal = issue_api_key.sql_literal(hostile)

    assert literal.startswith("'") and literal.endswith("'")
    body = literal[1:-1]
    assert body.replace("''", "'") == hostile
    # No lone apostrophe survives in the body: every one is part of a doubled pair.
    assert "'" not in body.replace("''", "")
