"""Independent depth verification for knowledge-detail modules.

This is deliberately stricter than the model's own self-check: it counts
sentences, minimum list sizes, concrete steps, and rejects every known
placeholder phrasing ("见上", "按步骤", "代入" alone, "见方法模型").
"""

from __future__ import annotations

import glob
import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KNOWLEDGE_DIR = PROJECT_ROOT / "knowledge-production"

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


def bad_text(text: str, *, min_len: int, label: str) -> list[str]:
    text = (text or "").strip()
    issues: list[str] = []
    if not text:
        issues.append(f"{label} empty")
    elif len(text) < min_len:
        issues.append(f"{label} too short ({len(text)}<{min_len})")
    if text in GENERIC_ONLY:
        issues.append(f"{label} generic placeholder")
    if HARD_PLACEHOLDER.search(text):
        issues.append(f"{label} placeholder reference")
    return issues


def verify_section(kp: dict) -> list[str]:
    issues: list[str] = []

    concept = (kp.get("conceptSummary") or "").strip()
    if len(re.findall(r"[。！？]", concept)) < 2:
        issues.append(f"concept fewer than 2 sentences ({len(re.findall(r'[。！？]', concept))})")
    issues.extend(bad_text(concept, min_len=40, label="concept"))

    props = kp.get("properties") or []
    if len(props) < 5:
        issues.append(f"properties {len(props)}<5")
    for prop in props:
        issues.extend(bad_text(prop, min_len=8, label="property"))

    methods = kp.get("methodModels") or []
    if len(methods) < 2:
        issues.append(f"methods {len(methods)}<2")
    for i, method in enumerate(methods):
        prefix = f"method[{i}]"
        name = (method.get("name") or "").strip()
        issues.extend(bad_text(name, min_len=4, label=f"{prefix} name"))
        if re.fullmatch(r"[A-Za-z0-9_ 　]+", name):
            issues.append(f"{prefix} name has no Chinese")
        steps = method.get("steps") or []
        if len(steps) < 4:
            issues.append(f"{prefix} steps {len(steps)}<4")
        for j, step in enumerate(steps):
            issues.extend(bad_text(step, min_len=6, label=f"{prefix} step[{j}]"))
        issues.extend(bad_text(method.get("example"), min_len=12, label=f"{prefix} example"))

    worked = kp.get("workedExampleStructure") or []
    if len(worked) < 2:
        issues.append(f"worked {len(worked)}<2")
    for i, we in enumerate(worked):
        prefix = f"worked[{i}]"
        issues.extend(bad_text(we.get("questionShape"), min_len=4, label=f"{prefix} shape"))
        issues.extend(bad_text(we.get("method"), min_len=4, label=f"{prefix} method"))
        issues.extend(bad_text(we.get("example"), min_len=20, label=f"{prefix} example"))

    confusions = kp.get("commonConfusions") or []
    if len(confusions) < 3:
        issues.append(f"confusions {len(confusions)}<3")
    for confusion in confusions:
        issues.extend(bad_text(confusion, min_len=10, label="confusion"))

    evidence = kp.get("evidenceSources") or []
    if not evidence:
        issues.append("evidence empty")

    return sorted(set(issues))


def main() -> int:
    modules: list[dict] = []
    total_sections = 0
    total_pass = 0
    total_issues = 0

    for path in detail_files():
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        slug = data.get("moduleSlug", path.stem)
        sections = []
        for kp in data.get("knowledgePoints", []):
            issues = verify_section(kp)
            sections.append({"section": kp.get("section", ""), "issues": issues})
        passed = sum(1 for s in sections if not s["issues"])
        total_sections += len(sections)
        total_pass += passed
        total_issues += sum(len(s["issues"]) for s in sections)
        modules.append(
            {
                "moduleSlug": slug,
                "moduleName": data.get("moduleName"),
                "subject": data.get("subject"),
                "totalSections": len(sections),
                "passedSections": passed,
                "sections": sections,
            }
        )

    print(f"modules={len(modules)} sections={total_sections} pass={total_pass} fail={total_sections - total_pass} issue_count={total_issues}")
    for mod in modules:
        if mod["passedSections"] != mod["totalSections"]:
            print(
                f"FAIL {mod['moduleSlug']}: {mod['passedSections']}/{mod['totalSections']}"
            )
            for sec in mod["sections"]:
                if sec["issues"]:
                    print(f"  {sec['section']}: {sec['issues']}")
    if total_pass != total_sections:
        print(f"DEPTH VERIFY FAILED: {total_sections - total_pass} sections need revision")
        return 1
    print("DEPTH VERIFY PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
