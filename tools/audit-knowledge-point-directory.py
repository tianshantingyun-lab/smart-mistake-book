"""Determinism audit for the knowledge-point directory. Rejects any drift
from the pinned per-subject snapshot; also verifies the directory stays
internally consistent (all curriculum statements present, counts match)."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
DIR = PROJECT_ROOT / "knowledge-production" / "knowledge-point-directory-2026-v1.json"

PINNED = {
    "CHINESE": (18, 62, 173, 0),
    "MATH": (24, 305, 222, 7),
    "ENGLISH": (6, 282, 290, 2),
    "POLITICS": (10, 121, 152, 7),
    "HISTORY": (6, 58, 105, 4),
    "GEOGRAPHY": (14, 141, 93, 4),
    "PHYSICS": (9, 138, 125, 5),
    "CHEMISTRY": (11, 73, 115, 7),
    "BIOLOGY": (27, 142, 173, 5),
}


def audit() -> dict:
    with open(DIR, encoding="utf-8") as fh:
        data = json.load(fh)
    subjects = {s["subject"]: s for s in data["subjects"]}
    if set(subjects) != set(PINNED):
        return {"valid": False, "reason": "subject set mismatch"}
    total_stmt = total_sec = total_hi = 0
    for subj, (mods, stmts, secs, hi) in PINNED.items():
        s = subjects[subj]
        n_mods = len(s["modules"])
        n_stmts = sum(m["curriculumStatementCount"] for m in s["modules"])
        n_secs = s["textbookSectionCount"]
        n_hi = sum(1 for m in s["modules"] if m["matchConfidence"] == "HIGH")
        if (n_mods, n_stmts, n_secs, n_hi) != (mods, stmts, secs, hi):
            return {
                "valid": False,
                "reason": (
                    f"{subj}: modules/stmts/secs/hi "
                    f"{n_mods}/{n_stmts}/{n_secs}/{n_hi} != pinned "
                    f"{mods}/{stmts}/{secs}/{hi}"
                ),
            }
        # internal consistency: every statement slug unique within module
        for m in s["modules"]:
            slugs = [c["slug"] for c in m["curriculumStatements"]]
            if len(slugs) != len(set(slugs)):
                return {"valid": False, "reason": f"dup statement slugs in {m['moduleSlug']}"}
        total_stmt += n_stmts
        total_sec += n_secs
        total_hi += n_hi
    if total_stmt != 1322 or total_sec != 1448 or total_hi != 41:
        return {
            "valid": False,
            "reason": f"totals {total_stmt}/{total_sec}/{total_hi} != 1322/1448/41",
        }
    return {"valid": True, "totals": {"statements": total_stmt, "sections": total_sec, "high": total_hi}}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = audit()
    if args.check:
        if not result["valid"]:
            print("AUDIT FAILED:", result["reason"])
            return 1
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print("AUDIT PASSED")
        return 0
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
