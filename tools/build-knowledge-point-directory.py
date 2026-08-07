"""Build the complete knowledge-point directory by merging:
  1. curriculum candidate modules + statements (knowledge-coverage-candidates)
  2. PEP textbook chapters/sections (pep-textbook-toc)
  3. module<->section alignment (knowledge-point-alignment)

Produces a three-level directory: module -> curriculum statements + matched
textbook sections, with per-module coverage counts and a detail-enrichment
flag. Output stays DRAFT_UNREVIEWED.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
CAND = PROJECT_ROOT / "knowledge-production" / "knowledge-coverage-candidates-2025-v1.json"
TOC = PROJECT_ROOT / "knowledge-production" / "pep-textbook-toc-2026-v1.json"
ALIGN = PROJECT_ROOT / "knowledge-production" / "knowledge-point-alignment-2026-v1.json"
OUT = PROJECT_ROOT / "knowledge-production" / "knowledge-point-directory-2026-v1.json"


def main() -> int:
    cand = json.load(open(CAND, encoding="utf-8"))
    toc = json.load(open(TOC, encoding="utf-8"))
    align = json.load(open(ALIGN, encoding="utf-8"))

    cand_by = {s["subject"]: s for s in cand["subjects"]}
    toc_by = {s["subject"]: s for s in toc["subjects"]}
    align_by = {s["subject"]: s for s in align["subjects"]}

    out = {
        "schemaVersion": 1,
        "artifactId": "knowledge-point-directory-2026-v1",
        "targetBaselineId": "moe-high-school-2017-2025",
        "textbookBaselineId": "pep-high-school-textbooks",
        "reviewState": "DRAFT_UNREVIEWED",
        "updatedAtEpochMillis": 1784908800000,
        "subjects": [],
    }

    grand_total = {
        "curriculumStatements": 0,
        "textbookSections": 0,
        "matchedModules": 0,
    }

    for subject in cand_by:
        cand_subj = cand_by[subject]
        toc_subj = toc_by.get(subject)
        align_subj = align_by.get(subject)
        books = toc_subj["books"] if toc_subj else []
        align_mods = {m["moduleSlug"]: m for m in align_subj["modules"]} if align_subj else {}

        # Build section lookup: (book title, section) -> section
        section_lookup: dict[tuple[str, str], None] = {}
        all_sections = []
        for b in books:
            for sec in b["sections"]:
                section_lookup[(b["title"], sec)] = None
                all_sections.append(sec)

        modules_out = []
        for m in cand_subj["modules"]:
            slug = m["slug"]
            statements = m["candidateStatements"]
            a = align_mods.get(slug)
            matched_sections = a["matchedSections"] if a else []
            book_cover = len({s["book"] for s in matched_sections})
            grand_total["curriculumStatements"] += len(statements)
            grand_total["matchedModules"] += 1 if a and a["matchConfidence"] == "HIGH" else 0
            modules_out.append(
                {
                    "moduleSlug": slug,
                    "moduleName": m["name"],
                    "curriculumStatementCount": len(statements),
                    "curriculumStatements": [
                        {"slug": c["slug"], "text": c.get("name", c.get("text", ""))}
                        for c in statements
                    ],
                    "matchedSectionCount": len(matched_sections),
                    "matchedBookCount": book_cover,
                    "matchConfidence": a["matchConfidence"] if a else "NO_MATCH",
                    "matchedSections": matched_sections,
                    "detailEnrichmentNeeded": (
                        a["matchConfidence"] == "HIGH" and len(matched_sections) >= 3
                    ),
                }
            )

        dir_secs = sum(len(b["sections"]) for b in books)
        grand_total["textbookSections"] += dir_secs
        out["subjects"].append(
            {
                "subject": subject,
                "textbookCount": len(books),
                "textbookSectionCount": dir_secs,
                "modules": modules_out,
            }
        )

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print("wrote", OUT)
    print("grand totals:", json.dumps(grand_total, ensure_ascii=False))
    for s in out["subjects"]:
        hi = sum(1 for m in s["modules"] if m["matchConfidence"] == "HIGH")
        enrich = sum(1 for m in s["modules"] if m["detailEnrichmentNeeded"])
        stmts = sum(m["curriculumStatementCount"] for m in s["modules"])
        print(
            f"  {s['subject']}: {len(s['modules'])} modules, {stmts} statements, "
            f"{s['textbookSectionCount']} sections, {hi} HIGH, {enrich} enrich-needed"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
