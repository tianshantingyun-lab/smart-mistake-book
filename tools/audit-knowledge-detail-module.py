"""Audit a knowledge-detail module file for structure and source integrity.

Checks: every knowledgePoint has conceptSummary + evidenceSources; every
methodModel/workedExample has name+steps+example; commonConfusions non-empty;
evidenceSources either all valid sourceIds or DOMAIN_COMMON_KNOWLEDGE.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
DEFAULT = PROJECT_ROOT / "knowledge-production" / "knowledge-detail-math-required-preparatory-2026-v1.json"


def audit(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    valid_source_ids = {s["sourceId"] for s in data.get("sources", [])}
    errors: list[str] = []

    if data.get("reviewState") not in ("DRAFT_UNREVIEWED", "REVIEWED_AI_FIRST_PASS"):
        errors.append("reviewState must be DRAFT_UNREVIEWED or REVIEWED_AI_FIRST_PASS")
    if data.get("synthesisPolicy") != "REVIEWED_SYNTHESIS_ONLY":
        errors.append("synthesisPolicy must be REVIEWED_SYNTHESIS_ONLY")
    if data.get("reviewState") == "REVIEWED_AI_FIRST_PASS":
        if not data.get("reviewedBy") or not data.get("reviewedAtEpochMillis"):
            errors.append("REVIEWED_AI_FIRST_PASS requires reviewedBy + reviewedAtEpochMillis")
        if data.get("reviewKind") != "AUTOMATED_CONTENT_VERIFICATION_FIRST_PASS":
            errors.append("reviewKind must be AUTOMATED_CONTENT_VERIFICATION_FIRST_PASS")

    for kp in data.get("knowledgePoints", []):
        sec = kp.get("section", "<no-section>")
        if not kp.get("conceptSummary"):
            errors.append(f"{sec}: missing conceptSummary")
        ev = kp.get("evidenceSources", [])
        if not ev:
            errors.append(f"{sec}: empty evidenceSources")
        for src in ev:
            if src != "DOMAIN_COMMON_KNOWLEDGE" and src not in valid_source_ids:
                errors.append(f"{sec}: unknown source {src}")
        if not kp.get("methodModels"):
            errors.append(f"{sec}: no methodModels")
        for m in kp.get("methodModels", []):
            if not m.get("name") or not m.get("steps"):
                errors.append(f"{sec}: methodModel missing name/steps")
        if not kp.get("commonConfusions"):
            errors.append(f"{sec}: no commonConfusions")
        if not kp.get("workedExampleStructure"):
            errors.append(f"{sec}: no workedExampleStructure")

    return {
        "valid": not errors,
        "errors": errors,
        "moduleSlug": data.get("moduleSlug"),
        "knowledgePointCount": len(data.get("knowledgePoints", [])),
        "sourceCount": len(data.get("sources", [])),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("file", nargs="?", type=Path, default=DEFAULT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = audit(args.file)
    if args.check:
        if not result["valid"]:
            print("AUDIT FAILED:")
            for e in result["errors"]:
                print(" -", e)
            return 1
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print("AUDIT PASSED")
        return 0
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
