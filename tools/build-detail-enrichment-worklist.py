"""Generate a third-party detail enrichment worklist from the knowledge-point
alignment. For every HIGH-confidence module it lists the matched textbook
sections and the detail fields that third-party teaching materials should
enrich (methods, confusions, worked-example structure).

Output is a planning artifact (DRAFT_UNREVIEWED), not content.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
ALIGN = PROJECT_ROOT / "knowledge-production" / "knowledge-point-alignment-2026-v1.json"
OUT = PROJECT_ROOT / "knowledge-production" / "knowledge-detail-enrichment-2026-v1.json"

DETAIL_FIELDS = [
    "methodModels",
    "commonConfusions",
    "workedExampleStructure",
    "prerequisiteRelations",
    "aliases",
    "typicalQuestionShapes",
]


def main() -> int:
    align = json.load(open(ALIGN, encoding="utf-8"))
    out = {
        "schemaVersion": 1,
        "artifactId": "knowledge-detail-enrichment-2026-v1",
        "sourceKind": "THIRD_PARTY_DETAIL_ENRICHMENT_WORKLIST",
        "reviewState": "DRAFT_UNREVIEWED",
        "updatedAtEpochMillis": 1784908800000,
        "subjects": [],
    }
    for s in align["subjects"]:
        enriched = []
        for m in s["modules"]:
            if m["matchConfidence"] != "HIGH" or m["matchedSectionCount"] == 0:
                continue
            enriched.append(
                {
                    "moduleSlug": m["moduleSlug"],
                    "moduleName": m["moduleName"],
                    "sectionCount": m["matchedSectionCount"],
                    "sections": m["matchedSections"],
                    "detailFieldsToEnrich": DETAIL_FIELDS,
                    "sourceStrategy": "REVIEWED_SYNTHESIS_ONLY",
                }
            )
        out["subjects"].append({"subject": s["subject"], "modules": enriched})

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print("wrote", OUT)
    total_modules = sum(len(s["modules"]) for s in out["subjects"])
    total_sections = sum(
        sum(m["sectionCount"] for m in s["modules"]) for s in out["subjects"]
    )
    for s in out["subjects"]:
        secs = sum(m["sectionCount"] for m in s["modules"])
        print(f"  {s['subject']}: {len(s['modules'])} modules, {secs} sections")
    print(f"TOTAL: {total_modules} modules, {total_sections} sections")
    return 0


if __name__ == "__main__":
    sys.exit(main())
