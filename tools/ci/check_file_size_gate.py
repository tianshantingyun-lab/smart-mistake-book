#!/usr/bin/env python3
"""File-size gate for a branch's own changes, not the whole backlog.

The point of this gate is to stop a *newly added* megafile from landing,
not to punish the existing backlog (51 main files are already over 600
lines and 17 over 1000). So it inspects only the files this branch/tag
changed relative to its base, and errors when one of those *crosses* a hard
line — a file that was already over the hard line at the base is reported as
an advisory warning (it is backlog, not a new megafile), while a file that
grew past the hard line in this change, or a brand-new oversized file, fails
the gate. Before 2026-09-09 any touched file over the hard line failed, which
contradicted this docstring and blocked unrelated work.

Baseline
--------
  - PR context (pull_request):   git diff main...HEAD
  - push context (to main):      no diff against itself -> skip (nothing to gate)
  Running locally on a release branch, pass --base <ref> to compare against a
  specific ref (defaults to `origin/main`, then falls back to `main`).

Levels are per source kind (main source is held tighter than tests):
  main source (path contains /src/main/):  warning 600, error 1000
  test       (src/test | src/androidTest): warning 900, error 1500

Advisory rows carry a movement column (`+12 vs base (1040)`, or `new file`), so a
reviewer can tell "this file was always that big" apart from "this branch made it
bigger" without re-running git by hand. It is deliberately advisory and NOT a gate:
a hard no-growth rule would also block legitimate additions to already-oversized
files (audit R-12 is where that trade-off gets decided).
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


def base_line_count(base: str, path: Path) -> int:
    """Line count of the same path at the base ref; 0 when it did not exist."""
    relative = path.relative_to(REPO).as_posix()
    content = subprocess.run(
        ["git", "show", f"{base}:{relative}"],
        cwd=REPO,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if content.returncode != 0:
        return 0
    return len(content.stdout.splitlines())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="ref to diff against (default origin/main)")
    parser.add_argument("--all", action="store_true",
                        help="scan the whole tree instead of only changed files")
    args = parser.parse_args()

    if args.all:
        raw = git(["ls-files"])
        paths = [REPO / n for n in raw.splitlines() if n]
        # No base in whole-tree mode: nothing to compare against, so the
        # movement column is omitted rather than faked.
        base = None
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
        base_lines = base_line_count(base, path) if base else None
        if lines > hard:
            if base_lines is not None and base_lines <= hard:
                # Crossed the hard line in this change -> the thing the gate
                # exists for. (Whole-tree mode has no base, so everything over
                # the hard line there is backlog by definition -> warning.)
                errors.append((lines, kind, path))
            else:
                # Already over the hard line before this change: backlog, not a
                # newly introduced megafile. Keep it visible — and show which way
                # it moved, so "it was always that big" can be told apart from
                # "this branch just made it bigger" without re-running git by
                # hand. Advisory only: a hard no-growth rule would also block
                # legitimate additions to these files (audit R-12 is where that
                # trade-off gets decided, not here).
                warnings.append((lines, kind, path, base_lines))
        elif lines > soft:
            warnings.append((lines, kind, path, base_lines))

    for rows, label in ((warnings, "warning (advisory)"),
                        (errors, "error (build gate)")):
        if not rows:
            continue
        print(f"\n## oversized files: {label} ({len(rows)})")
        for row in sorted(rows, key=lambda r: r[0], reverse=True):
            lines, kind, path = row[0], row[1], row[2]
            if len(row) > 3 and row[3] is not None:
                base_lines = row[3]
                movement = ("new file" if base_lines == 0
                            else f"{lines - base_lines:+d} vs base ({base_lines})")
                print(f"- {lines:>5} lines  [{kind}]  {movement}  "
                      f"{path.relative_to(REPO)}")
            else:
                print(f"- {lines:>5} lines  [{kind}]  {path.relative_to(REPO)}")

    print(f"\nchecked {len(paths)} changed file(s); "
          f"{len(errors)} error, {len(warnings)} warning "
          "(thresholds: main 600/1000, test 900/1500)")

    if errors:
        print("\nFile-size gate FAILED: this branch pushes a file past its hard ",
              "line. Split cohesive blocks into their own file or component ",
              "before merging.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
