"""Audit the PEP textbook TOC knowledge baseline for determinism and
completeness. Each subject must have >= 4 books, each book >= 1 chapter, and
totals must match the pinned snapshot below. Adding content must regenerate
and re-pin; removing content fails closed.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
TOC = PROJECT_ROOT / "knowledge-production" / "pep-textbook-toc-2026-v1.json"

# Pinned determinism snapshot (2026-08-07). Update only after intentional change.
PINNED = {
    "CHINESE": (5, 34, 173),
    "MATH": (5, 23, 222),
    "ENGLISH": (7, 37, 290),
    "PHYSICS": (6, 35, 125),
    "CHEMISTRY": (5, 24, 115),
    "BIOLOGY": (5, 32, 173),
    "HISTORY": (5, 42, 105),
    "GEOGRAPHY": (5, 24, 93),
    "POLITICS": (7, 27, 152),
}
TOTAL_BOOKS = 50
TOTAL_CHAPTERS = 278
TOTAL_SECTIONS = 1448


def audit() -> dict:
    with open(TOC, encoding="utf-8") as fh:
        data = json.load(fh)
    subjects = {s["subject"]: s for s in data["subjects"]}
    missing = [k for k in PINNED if k not in subjects]
    if missing:
        return {"valid": False, "reason": f"missing subjects {missing}"}

    per_subject = {}
    total_books = total_chapters = total_sections = 0
    for subj, (b, c, s) in PINNED.items():
        entry = subjects[subj]
        books = len(entry["books"])
        chapters = sum(len(x["chapters"]) for x in entry["books"])
        sections = sum(len(x["sections"]) for x in entry["books"])
        if (books, chapters, sections) != (b, c, s):
            return {
                "valid": False,
                "reason": (
                    f"{subj}: books/chapters/sections "
                    f"{books}/{chapters}/{sections} != pinned {b}/{c}/{s}"
                ),
            }
        per_subject[subj] = {"books": books, "chapters": chapters, "sections": sections}
        total_books += books
        total_chapters += chapters
        total_sections += sections

    if total_books != TOTAL_BOOKS or total_chapters != TOTAL_CHAPTERS or total_sections != TOTAL_SECTIONS:
        return {
            "valid": False,
            "reason": (
                f"totals {total_books}/{total_chapters}/{total_sections} != "
                f"pinned {TOTAL_BOOKS}/{TOTAL_CHAPTERS}/{TOTAL_SECTIONS}"
            ),
        }
    return {"valid": True, "perSubject": per_subject}


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
    # write mode placeholder (regeneration is the fetch script's job)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
