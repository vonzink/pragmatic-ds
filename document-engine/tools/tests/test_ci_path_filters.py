"""The CI path filters, exercised against synthetic diffs.

`.github/workflows/ci.yml` gates its expensive jobs on a `changes` job that
classifies the commit's diff. That classifier is shell embedded in YAML: nothing
type-checks it, and its failure mode is silent. A regex that stops matching does
not break the build — it SKIPS a job, and the run goes green without having run
it. In an engine that reads borrower documents, that is the expensive direction.

So the script is lifted out of the workflow and run here for real, with `git`
replaced by a stub that reports whatever file list a case asks for. What is
asserted is the mapping every job's `if:` depends on, and — separately, because
it is the property that makes the whole design safe — that every way the
classifier can lose confidence ends with every output true.
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

WORKFLOW = Path(".github/workflows/ci.yml")
AREAS = ("java", "python", "ui", "fixtures", "licence", "consumer")

# The stub answers the only two git commands the classifier runs. FAKE_BASE_MISSING
# drives `git cat-file -e`'s exit status, which is how the script detects a base
# commit that is not in the checkout.
FAKE_GIT = """#!/usr/bin/env bash
case "$1" in
  cat-file) exit ${FAKE_BASE_MISSING:-0} ;;
  diff)     printf '%s' "$FAKE_FILES" ;;
  *)        echo "unexpected git $*" >&2; exit 2 ;;
esac
"""


def classify_script() -> str:
    workflow = yaml.safe_load(WORKFLOW.read_text())
    steps = workflow["jobs"]["changes"]["steps"]
    return next(step for step in steps if step.get("id") == "classify")["run"]


@pytest.fixture(scope="module")
def fake_git_dir(tmp_path_factory) -> Path:
    directory = tmp_path_factory.mktemp("fakebin")
    git = directory / "git"
    git.write_text(FAKE_GIT)
    git.chmod(0o755)
    return directory


@pytest.fixture
def classify(fake_git_dir, tmp_path):
    """Run the workflow's classifier over a file list; return its outputs."""

    def run(files, *, event="pull_request", before="b" * 40, base_missing=0):
        output = tmp_path / "github_output"
        output.touch()
        environment = {
            **os.environ,
            "PATH": f"{fake_git_dir}{os.pathsep}{os.environ['PATH']}",
            "EVENT_NAME": event,
            "PR_BASE_SHA": "a" * 40,
            "PR_HEAD_SHA": "c" * 40,
            "PUSH_BEFORE": before,
            "PUSH_HEAD": "d" * 40,
            "GITHUB_OUTPUT": str(output),
            "FAKE_FILES": "\n".join(files),
            "FAKE_BASE_MISSING": str(base_missing),
        }
        result = subprocess.run(
            [shutil.which("bash"), "-c", classify_script()],
            env=environment,
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, result.stderr
        return dict(
            line.split("=", 1)
            for line in output.read_text().splitlines()
            if "=" in line
        )

    return run


# (changed path, the areas it must run). Anything not listed must be false — the
# point of the filter is what it declines to run, so the absent half is asserted.
@pytest.mark.parametrize(
    "path,expected",
    [
        # Prose and operational scripts build nothing.
        ("README.md", set()),
        ("docs/ARCHITECTURE.md", set()),
        ("deploy/check-deploy-state.sh", set()),
        # Gradle modules.
        ("app/src/main/java/com/pragmaticds/docengine/App.java", {"java"}),
        ("extraction/src/main/java/com/pragmaticds/docengine/extraction/S.java", {"java"}),
        # A build file names dependencies, so it moves the licence gate too.
        ("app/build.gradle.kts", {"java", "licence"}),
        ("gradle/libs.versions.toml", {"java", "licence"}),
        # RuntimeImageParityTest reads both Dockerfiles.
        ("Dockerfile", {"java"}),
        ("Dockerfile.runtime", {"java"}),
        # CommittedOpenApiContractTest reads the committed contract.
        ("docs/api/openapi.json", {"java"}),
        # fixtures/ feeds the extraction ITs, the provenance check and pytest.
        ("fixtures/truth/bank_statement.json", {"java", "python", "fixtures"}),
        # Python.
        ("worker/src/pragmaticds_docengine_worker/ocr.py", {"python"}),
        ("worker/requirements.txt", {"python", "licence"}),
        ("tools/license_gate.py", {"python", "fixtures", "licence", "consumer"}),
        ("licenses/policy.toml", {"python", "licence"}),
        ("pyproject.toml", {"python", "licence"}),
        # The consumer contract and the guide summarising it — a change to
        # either can put a consumer on rules the engine no longer honours.
        # ConsumerCoverageTableIT also reads the contract's §5 against the
        # migrated database, so the backend suite must see a contract edit.
        ("docs/consumer/income-extraction-contract.md", {"java", "consumer"}),
        ("docs/consumer/consumer-integration-guide.md", {"java", "consumer"}),
        # UI.
        ("ui/src/App.tsx", {"ui"}),
        ("ui/package-lock.json", {"ui", "licence"}),
        (".nvmrc", {"ui", "licence"}),
    ],
)
def test_a_changed_path_runs_exactly_the_jobs_that_read_it(classify, path, expected):
    outputs = classify([path])
    assert {area for area in AREAS if outputs[area] == "true"} == expected


def test_a_change_to_ci_itself_runs_everything(classify):
    """CI cannot take its own classifier's word about a commit that rewrites it."""
    assert classify([".github/workflows/ci.yml"]) == {area: "true" for area in AREAS}


@pytest.mark.parametrize(
    "name,files,kwargs",
    [
        ("a new branch has no before-commit", ["README.md"], {"event": "push", "before": "0" * 40}),
        ("push with an empty before", ["README.md"], {"event": "push", "before": ""}),
        ("base commit absent from the checkout", ["README.md"], {"base_missing": 1}),
        ("an event nobody wrote a rule for", ["README.md"], {"event": "schedule"}),
        ("a diff that came back empty", [], {}),
    ],
)
def test_every_loss_of_confidence_runs_everything(classify, name, files, kwargs):
    """The asymmetry the whole design rests on.

    A job run needlessly costs a minute. A job skipped wrongly merges a
    regression with CI green. So there is no path through this script that
    reaches a `false` without having actually read a diff.
    """
    assert classify(files, **kwargs) == {area: "true" for area in AREAS}, name


def test_every_gated_job_fails_open_and_the_secret_scan_is_not_gated():
    """The `if:` guards must read `!= 'false'`, never `== 'true'`.

    An output that is missing, empty, or never set — a `changes` job that died
    before writing it — must run the job. `== 'true'` would skip it instead, and
    turn one broken step into a silently unverified merge.
    """
    jobs = yaml.safe_load(WORKFLOW.read_text())["jobs"]
    gated = {name: job for name, job in jobs.items() if "if" in job}

    assert set(gated) == {"licence", "ui", "fixtures", "python", "java", "consumer"}
    for name, job in gated.items():
        assert job["if"] == f"needs.changes.outputs.{name} != 'false'", name

    # A secret can be committed to any file in the tree, so no path filter is
    # safe here — and it must not depend on `changes` either, or a failure in the
    # classifier would take the security gate down with it.
    assert "if" not in jobs["secrets"]
    assert "needs" not in jobs["secrets"]
