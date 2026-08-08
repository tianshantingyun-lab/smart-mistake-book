"""Audit content depth of all knowledge-detail modules.

The structural audit (audit-knowledge-detail-module.py) proves every field
exists. This audit goes one step further: it rejects terse placeholders
("见上", "按步骤", "代入" alone), short concepts, and skeletal sections that
carry a section count but almost no teachable content.
"""

from __future__ import annotations

import argparse
import glob
import json
import re
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KNOWLEDGE_DIR = PROJECT_ROOT / "knowledge-production"
DEFAULT_OUT = KNOWLEDGE_DIR / "knowledge-detail-content-audit-2026-v1.json"

EXCLUDE_PARTS = (
    "content-audit",
    "enrichment",
    "alignment",
    "directory",
    "ledger",
    "size-audit",
    "index",
)

HARD_PLACEHOLDER = re.compile(r"见上|同上|如上|见方法模型|待补充|待完善|TODO|占位", re.I)
GENERIC_ONLY = {
    "按步骤",
    "代入",
    "略",
    "见方法模型",
    "见上",
    "同上",
    "如上",
    "待定",
    "...",
    "……",
}


def detail_files() -> list[Path]:
    out = []
    for path in glob.glob(str(KNOWLEDGE_DIR / "knowledge-detail-*.json")):
        if not any(part in Path(path).name for part in EXCLUDE_PARTS):
            out.append(Path(path))
    return sorted(out)


def text_issues(text: str, *, min_len: int, label: str) -> list[str]:
    text = (text or "").strip()
    issues: list[str] = []
    if not text:
        issues.append(f"{label}: empty")
    elif len(text) < min_len:
        issues.append(f"{label}: too short ({len(text)} chars)")
    if text in GENERIC_ONLY:
        issues.append(f"{label}: generic placeholder")
    if HARD_PLACEHOLDER.search(text):
        issues.append(f"{label}: placeholder reference")
    return issues


def audit_section(kp: dict) -> tuple[str, list[str]]:
    issues: list[str] = []
    section = kp.get("section", "")

    issues.extend(text_issues(kp.get("conceptSummary"), min_len=15, label="concept"))

    props = kp.get("properties", [])
    if not props:
        issues.append("properties: empty")
    for prop in props:
        issues.extend(text_issues(prop, min_len=6, label="property"))

    confusions = kp.get("commonConfusions", [])
    if not confusions:
        issues.append("confusions: empty")
    for conf in confusions:
        issues.extend(text_issues(conf, min_len=6, label="confusion"))

    methods = kp.get("methodModels", [])
    if not methods:
        issues.append("methods: empty")
    for method in methods:
        name = method.get("name", "")
        issues.extend(text_issues(name, min_len=4, label="method-name"))
        steps = method.get("steps", [])
        joined_steps = " ".join(s.strip() for s in steps if str(s).strip())
        if not steps:
            issues.append("method: no steps")
        elif len(joined_steps) < 10:
            issues.append("method: steps too terse")
        for step in steps:
            issues.extend(text_issues(step, min_len=3, label="method-step"))
        issues.extend(text_issues(method.get("example"), min_len=8, label="method-example"))

    worked = kp.get("workedExampleStructure", [])
    if not worked:
        issues.append("worked: empty")
    for we in worked:
        issues.extend(text_issues(we.get("questionShape"), min_len=4, label="worked-shape"))
        issues.extend(text_issues(we.get("method"), min_len=4, label="worked-method"))
        issues.extend(text_issues(we.get("example"), min_len=8, label="worked-example"))

    evidence = kp.get("evidenceSources", [])
    if not evidence:
        issues.append("evidence: empty")

    # Depth gate: the section must contain a real teaching payload, not just
    # section-count scaffolding.
    depth = sum(
        len(str(v).strip())
        for v in (
            kp.get("conceptSummary", ""),
            props,
            confusions,
            [m.get("name", "") + " " + " ".join(m.get("steps", [])) + " " + m.get("example", "") for m in methods],
            [w.get("questionShape", "") + " " + w.get("method", "") + " " + w.get("example", "") for w in worked],
        )
    )
    if depth < 180:
        issues.append(f"section too shallow ({depth} chars)")

    return section, sorted(set(issues))


def audit_file(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    slug = data.get("moduleSlug", path.stem)
    sections = []
    for kp in data.get("knowledgePoints", []):
        section, issues = audit_section(kp)
        sections.append(
            {
                "section": section,
                "decision": "NEEDS_REVISION" if issues else "PASS",
                "issues": issues,
            }
        )
    passed = sum(1 for s in sections if s["decision"] == "PASS")
    return {
        "moduleSlug": slug,
        "subject": data.get("subject"),
        "moduleName": data.get("moduleName"),
        "totalSections": len(sections),
        "passedSections": passed,
        "needsRevisionSections": len(sections) - passed,
        "sections": sections,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    modules = [audit_file(f) for f in detail_files()]
    issue_counter: dict[str, int] = {}
    for mod in modules:
        for sec in mod["sections"]:
            for issue in sec["issues"]:
                issue_counter[issue] = issue_counter.get(issue, 0) + 1

    total = sum(m["totalSections"] for m in modules)
    passed = sum(m["passedSections"] for m in modules)
    result = {
        "schemaVersion": 1,
        "generatedAtEpochMillis": int(time.time() * 1000),
        "summary": {
            "modules": len(modules),
            "totalSections": total,
            "passedSections": passed,
            "needsRevisionSections": total - passed,
            "issues": dict(sorted(issue_counter.items(), key=lambda kv: -kv[1])),
        },
        "modules": modules,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    if args.check and result["summary"]["needsRevisionSections"] > 0:
        print(f"CONTENT AUDIT FAILED: {result['summary']['needsRevisionSections']} sections need revision")
        return 1
    print(f"CONTENT AUDIT PASSED: {passed}/{total} sections substantive")
    return 0


if __name__ == "__main__":
    sys.exit(main())
