#!/usr/bin/env python3
"""File-size gate for a branch's own changes, not the whole backlog.

The point of this gate is to stop a *newly added* megafile from landing,
not to punish the existing backlog (51 main files are already over 600
lines and 17 over 1000). So it inspects only the files this branch/tag
changed relative to its base, and errors when one of those crosses a hard
line.

Baseline
--------
  - PR context (pull_request):   git diff main...HEAD
  - push context (to main):      no diff against itself -> skip (nothing to gate)
  Running locally on a release branch, pass --base <ref> to compare against a
  specific ref (defaults to `origin/main`, then falls back to `main`).

Levels are per source kind (main source is held tighter than tests):
  main source (path contains /src/main/):  warning 600, error 1000
  test       (src/test | src/androidTest): warning 900, error 1500
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

SRC_EXTS = {".kt", ".java"}
THRESHOLDS = {
    "main": (600, 1000),
    "test": (900, 1500),
}


def classify(path: Path) -> str | None:
    s = path.as_posix()
    if "/src/main/" in s:
        return "main"
    if "/src/test/" in s or "/src/androidTest/" in s:
        return "test"
    return None


def git(args: list[str]) -> str:
    return subprocess.run(
        ["git", *args], cwd=REPO, capture_output=True, text=True, check=False
    ).stdout


def base_ref(args: argparse.Namespace) -> str:
    if args.base:
        return args.base
    for candidate in ("origin/main", "main"):
        ok = git(["rev-parse", "--verify", "--quiet", candidate])
        if ok.strip():
            return candidate
    return "HEAD"


def changed_files(base: str) -> list[Path]:
    names = git(["diff", "--name-only", f"{base}...HEAD"]).splitlines()
    return [REPO / n for n in names if n]


def line_count(path: Path) -> int:
    try:
        with path.open(encoding="utf-8", errors="replace") as fh:
            return sum(1 for _ in fh)
    except OSError:
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="ref to diff against (default origin/main)")
    parser.add_argument("--all", action="store_true",
                        help="scan the whole tree instead of only changed files")
    args = parser.parse_args()

    if args.all:
        raw = git(["ls-files"])
        paths = [REPO / n for n in raw.splitlines() if n]
    else:
        base = base_ref(args)
        paths = changed_files(base)
        if not paths:
            # Nothing to compare (e.g. push to main) -> nothing to gate.
            print("no changed .kt/.java files vs base; nothing to gate")
            return 0

    errors, warnings = [], []
    for path in paths:
        if path.suffix not in SRC_EXTS:
            continue
        kind = classify(path)
        if kind is None:
            continue
        lines = line_count(path)
        soft, hard = THRESHOLDS[kind]
        if lines > hard:
            errors.append((lines, kind, path))
        elif lines > soft:
            warnings.append((lines, kind, path))

    for rows, label in ((warnings, "warning (advisory)"),
                        (errors, "error (build gate)")):
        if not rows:
            continue
        print(f"\n## oversized files: {label} ({len(rows)})")
        for lines, kind, path in sorted(rows, reverse=True):
            print(f"- {lines:>5} lines  [{kind}]  {path.relative_to(REPO)}")

    print(f"\nchecked {len(paths)} changed file(s); "
          f"{len(errors)} error, {len(warnings)} warning "
          "(thresholds: main 600/1000, test 900/1500)")

    if errors:
        print("\nFile-size gate FAILED: this branch introduces a hard-line ",
              "oversized file. Split cohesive blocks into their own file or ",
              "component before merging.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
