#!/usr/bin/env python3
"""Flag com.pragmaticds class names that are used in a .java file but neither imported,
same-package, declared in-file, nor accessed as a qualified nested name.

This is the blind spot of both the classpath-free javac parse-check (which must filter
"cannot find symbol" because it runs without dependencies) and an import resolver
(which validates imports that exist, not imports that are missing). In this repository's
build environment there is no local Java build — CI is the only compiler with a
classpath — so this check is what stands between an edit and a wasted CI round.

Usage:
    scripts/check_missing_imports.py [FILE...]

With no arguments, checks every .java file changed against origin's default branch
plus uncommitted edits. Exits non-zero when a problem is found.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC_ROOTS = [ROOT / "src/main/java", ROOT / "src/test/java"]

# Every simple class name that exists as a com.pragmaticds source file, mapped to
# the set of packages that define it.
simple_names: dict[str, set[str]] = {}
for src in SRC_ROOTS:
    for f in src.rglob("*.java"):
        pkg = str(f.parent.relative_to(src)).replace("/", ".")
        simple_names.setdefault(f.stem, set()).add(pkg)


def strip_comments_and_strings(text: str) -> str:
    # ONE leftmost-first pass, not sequential substitutions: stripping line comments
    # before strings truncates any string literal containing "//" (e.g. a URL fixture),
    # unbalancing quotes so the string pass then swallows real code — which hides
    # missing imports in every file after such a literal.
    pattern = re.compile(
        r'"(?:\\.|[^"\\])*"'      # string literal (may contain // or /*)
        r"|'(?:\\.|[^'\\])*'"     # char literal
        r"|/\*.*?\*/"             # block comment/javadoc (may contain quotes)
        r"|//[^\n]*",             # line comment
        re.S)

    def repl(m: re.Match) -> str:
        s = m.group(0)
        if s.startswith('"'):
            return '""'
        if s.startswith("'"):
            return "''"
        return " "

    return pattern.sub(repl, text)


def check(path: Path) -> list[str]:
    raw = path.read_text()
    pkg_m = re.search(r"^package\s+([\w.]+);", raw, flags=re.M)
    pkg = pkg_m.group(1) if pkg_m else ""
    imports = re.findall(r"^import\s+(?:static\s+)?([\w.]+);", raw, flags=re.M)
    # A name is covered by an import if any import path contains it as a segment
    # (covers `import a.b.Outer` used as Outer.Nested, and static imports).
    imported_segments = {seg for imp in imports for seg in imp.split(".")}
    declared = set(re.findall(r"\b(?:class|interface|record|enum)\s+(\w+)", raw))

    body = strip_comments_and_strings(raw)
    body = re.sub(r"^import\s+[^\n]*$", " ", body, flags=re.M)
    body = re.sub(r"^package\s+[^\n]*$", " ", body, flags=re.M)

    problems = []
    seen = set()
    # Capitalized identifiers NOT preceded by a dot (qualified access resolves
    # through its qualifier).
    for m in re.finditer(r"(?<![\w.$])([A-Z]\w*)", body):
        name = m.group(1)
        if name in seen:
            continue
        seen.add(name)
        if name not in simple_names:
            continue                       # not a com.pragmaticds top-level class
        if name in declared or name in imported_segments:
            continue
        if pkg in simple_names[name]:
            continue                       # same package, no import needed
        pkgs = ", ".join(sorted(simple_names[name]))
        problems.append(f"{path}: uses {name} (defined in {pkgs}) with no import")
    return problems


def changed_files() -> list[str]:
    base = subprocess.run(
        ["git", "-C", str(ROOT), "merge-base", "HEAD", "origin/main"],
        capture_output=True, text=True)
    ref = base.stdout.strip() if base.returncode == 0 else "HEAD~1"
    out = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "--name-only", f"{ref}..HEAD"],
        capture_output=True, text=True, check=True).stdout
    files = [line for line in out.splitlines() if line.endswith(".java")]
    out2 = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "--name-only"],
        capture_output=True, text=True, check=True).stdout
    files += [line for line in out2.splitlines()
              if line.endswith(".java") and line not in files]
    return files


if __name__ == "__main__":
    files = sys.argv[1:] or changed_files()
    all_problems = []
    for rel in files:
        p = Path(rel) if Path(rel).is_absolute() else ROOT / rel
        if p.exists():
            all_problems.extend(check(p))
    if all_problems:
        print("\n".join(all_problems))
        sys.exit(1)
    print(f"OK: {len(files)} files, no used-but-unimported com.pragmaticds classes")
