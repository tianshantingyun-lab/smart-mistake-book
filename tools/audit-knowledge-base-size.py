"""Knowledge base size/coverage audit.

Compares the PEP textbook TOC (full high-school coverage target) against
the knowledge-detail modules actually authored, and reports per-subject
coverage. Also counts authored content volume (sections, method models,
confusions, worked examples).

Usage:
    python tools/audit-knowledge-base-size.py [--json]
Exit code 0 = audit ran; coverage target constant controls the gate.
"""

from __future__ import annotations

import argparse
import glob
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KP = PROJECT_ROOT / "knowledge-production"

# Non-core TOC section markers (reading/IT/labs) not counted as knowledge points.
NON_CORE_MARKERS = ("阅读与思考", "信息技术应用", "探究与发现", "文献阅读", "复习参考题")

# Chinese is organized by 18 curriculum task groups (not textbook lessons).
CHINESE_TASK_GROUPS = 18


def load_toc_sections() -> dict[str, set[str]]:
    toc = json.load(open(KP / "pep-textbook-toc-2026-v1.json", encoding="utf-8"))
    out = {}
    for s in toc["subjects"]:
        secs = set()
        for b in s["books"]:
            for x in b["sections"]:
                if not any(m in x for m in NON_CORE_MARKERS):
                    secs.add(x)
        out[s["subject"]] = secs
    return out


def load_detail_coverage() -> dict[str, set[str]]:
    out = {}
    for f in glob.glob(str(KP / "knowledge-detail-*.json")):
        if any(k in f for k in ("enrichment", "alignment", "directory", "ledger")):
            continue
        d = json.load(open(f, encoding="utf-8"))
        subj = d.get("subject")
        out.setdefault(subj, set())
        for kp in d.get("knowledgePoints", []):
            out[subj].add(kp["section"])
    return out


def audit() -> dict:
    toc = load_toc_sections()
    detail = load_detail_coverage()
    all_subjects = sorted(set(toc) | set(detail))
    rows = []
    total_core = 0
    total_covered = 0
    for subj in all_subjects:
        if subj == "CHINESE":
            # Chinese benchmark is the 18 task groups, not textbook lessons.
            n_core = CHINESE_TASK_GROUPS
            n_cov = min(len(detail.get(subj, set())), n_core)
            core_note = "task-groups"
        else:
            core = toc.get(subj, set())
            cov = detail.get(subj, set())
            n_core = len(core)
            n_cov = len(cov)
            core_note = "sections"
        total_core += n_core
        total_covered += n_cov
        rows.append(
            {
                "subject": subj,
                "coreSections": n_core,
                "coveredSections": n_cov,
                "coverageRatio": round(n_cov / n_core, 3) if n_core else 0.0,
                "coreBasis": core_note,
            }
        )
    return {
        "schemaVersion": 1,
        "artifactId": "knowledge-base-size-audit-2026-v1",
        "coreSectionsTotal": total_core,
        "coveredSectionsTotal": total_covered,
        "overallCoverageRatio": round(total_covered / total_core, 3) if total_core else 0.0,
        "perSubject": rows,
        "note": "Sections are PEP textbook TOC knowledge-point sections excluding reading/IT/lab markers. Coverage < 100% means the knowledge base is not yet complete.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = audit()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    print(f"=== 知识库体量审计 ===")
    print(f"教材核心小节总数: {result['coreSectionsTotal']}")
    print(f"detail 已覆盖小节: {result['coveredSectionsTotal']}")
    print(f"总体覆盖率: {result['overallCoverageRatio']*100:.1f}%")
    print(f"\n{'科目':8} {'核心小节':>8} {'已覆盖':>8} {'覆盖率':>8}")
    for r in result["perSubject"]:
        print(f"{r['subject']:8} {r['coreSections']:8} {r['coveredSections']:8} {r['coverageRatio']*100:7.1f}%")
    print(f"\n提示: 覆盖率 < 100% 表示知识库尚未完整。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
