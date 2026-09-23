"""Licence gate CLI — the entry point CI invokes for each ecosystem.

    python -m tools.license_gate_cli --format pip --report pip-licenses.json

Exit code 1 on any violation, with every offender named. Reports all
violations rather than stopping at the first, so one CI run tells you the
whole story.
"""

import argparse
import sys
from pathlib import Path

from tools.license_gate import Policy, run_gate
from tools.license_reports import parse_gradle, parse_npm, parse_pip

PARSERS = {"pip": parse_pip, "gradle": parse_gradle, "npm": parse_npm}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="license-gate")
    parser.add_argument("--format", required=True, choices=sorted(PARSERS))
    parser.add_argument("--report", required=True)
    parser.add_argument("--policy", default="licenses/policy.toml")
    parser.add_argument(
        "--min-dependencies",
        type=int,
        default=0,
        help=(
            "Fail if fewer than N dependencies were inspected. Guards against a report "
            "generated from a tree that was never installed, which otherwise passes "
            "vacuously — see the module docstring."
        ),
    )
    args = parser.parse_args(argv)

    policy = Policy.load(args.policy)
    dependencies = PARSERS[args.format](Path(args.report).read_text())
    result = run_gate(dependencies, policy)

    # Violations are reported first: a denied licence is a real answer and must never be
    # masked by the size guard, which is only about whether the question was asked at all.
    if result.ok and len(result.verdicts) < args.min_dependencies:
        print(
            f"licence gate FAILED — inspected only {len(result.verdicts)} {args.format} "
            f"dependencies, expected at least {args.min_dependencies}."
        )
        print(
            "The dependency tree looks uninstalled. A gate with nothing to inspect reports "
            "success, which is indistinguishable from a gate that passed — install "
            "dependencies before generating the report."
        )
        return 1

    if result.ok:
        print(f"licence gate OK — {len(result.verdicts)} {args.format} dependencies, 0 violations")
        return 0

    print(f"licence gate FAILED — {len(result.violations)} violation(s) in {args.format}:")
    for violation in result.violations:
        dep = violation.dependency
        print(f"  {dep.name} {dep.version}: {violation.reason}")
    print("\nPolicy: docs/LICENSING.md · Allowlist: licenses/policy.toml")
    return 1


if __name__ == "__main__":
    sys.exit(main())
