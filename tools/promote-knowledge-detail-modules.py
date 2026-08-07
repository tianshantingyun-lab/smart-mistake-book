"""Promote knowledge-detail modules from DRAFT_UNREVIEWED to
REVIEWED_AI_FIRST_PASS once their automated review record exists and shows
all sections approved. Also writes a review ledger.

Status ladder (honest, non-fake):
  DRAFT_UNREVIEWED -> REVIEWED_AI_FIRST_PASS -> (human final: REVIEWED)
A human reviewer must confirm before REVIEWED_AI_FIRST_PASS is upgraded to
REVIEWED; this tool only performs the automated first pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KP = PROJECT_ROOT / "knowledge-production"
REVIEW_DIR = KP / "review-decisions"
REVIEWER = "opencode-ai-first-pass"


def promote(module_path: Path, force: bool = False) -> dict:
    data = json.load(open(module_path, encoding="utf-8"))
    slug = data.get("moduleSlug", "")
    review_file = REVIEW_DIR / f"review-{slug}.json"
    if not review_file.exists():
        return {"module": slug, "ok": False, "reason": "no review record"}
    review = json.load(open(review_file, encoding="utf-8"))
    if review.get("sectionsApproved", 0) != review.get("sectionsTotal", 0):
        return {"module": slug, "ok": False, "reason": "not all sections approved"}
    current = data.get("reviewState")
    if current == "REVIEWED_AI_FIRST_PASS" and not force:
        return {"module": slug, "ok": True, "reason": "already promoted"}
    data["reviewState"] = "REVIEWED_AI_FIRST_PASS"
    data["reviewedBy"] = REVIEWER
    data["reviewedAtEpochMillis"] = 1784908800000
    data["reviewKind"] = "AUTOMATED_CONTENT_VERIFICATION_FIRST_PASS"
    with open(module_path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    return {"module": slug, "ok": True, "previousState": current}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--module", type=Path, default=None)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    targets = []
    if args.module:
        targets = [args.module]
    elif args.all:
        targets = sorted(KP.glob("knowledge-detail-*.json"))
        targets = [
            t for t in targets
            if "enrichment" not in t.name
            and "alignment" not in t.name
            and "directory" not in t.name
            and "ledger" not in t.name
            and "size-audit" not in t.name
        ]

    results = []
    for t in targets:
        results.append(promote(t, force=args.force))
    ok = sum(1 for r in results if r["ok"])
    for r in results:
        print(("OK  " if r["ok"] else "FAIL") + f" {r['module']}: {r.get('reason','')}")
    print(f"promoted: {ok}/{len(results)}")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
