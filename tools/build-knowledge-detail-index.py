"""Build a compact per-module index over knowledge-detail modules.

The full detail modules contain long teaching content. Consumers that only
need module/subject/section/alias enumeration can load this one small index
instead of parsing every module. The generated index is independently
re-checked in --check mode against the source modules.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KNOWLEDGE_DIR = PROJECT_ROOT / "knowledge-production"
INDEX_OUT = KNOWLEDGE_DIR / "knowledge-detail-index-2026-v1.json"

EXCLUDE_PARTS = ("content-audit", "enrichment", "alignment", "directory", "ledger", "index")


def detail_files(knowledge_dir: Path) -> list[Path]:
    out = []
    for path in sorted(knowledge_dir.glob("knowledge-detail-*.json")):
        if not any(part in path.name for part in EXCLUDE_PARTS):
            out.append(path)
    return out


def build_index(knowledge_dir: Path) -> dict:
    files = detail_files(knowledge_dir)
    modules = []
    total_sections = 0
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8"))
        sections = []
        for kp in data.get("knowledgePoints", []):
            sections.append(
                {
                    "section": kp.get("section", ""),
                    "aliases": kp.get("aliases", []),
                    "methodModelCount": len(kp.get("methodModels", [])),
                    "workedExampleCount": len(kp.get("workedExampleStructure", [])),
                }
            )
        total_sections += len(sections)
        modules.append(
            {
                "artifactId": data.get("artifactId"),
                "subject": data.get("subject"),
                "moduleSlug": data.get("moduleSlug"),
                "moduleName": data.get("moduleName"),
                "reviewState": data.get("reviewState"),
                "sectionCount": len(sections),
                "sections": sections,
            }
        )
    return {
        "schemaVersion": 1,
        "artifactId": "knowledge-detail-index-2026-v1",
        "generatedFrom": "knowledge-detail-*.json",
        "totalModules": len(modules),
        "totalSections": total_sections,
        "modules": modules,
    }


def verify_index(index_path: Path, knowledge_dir: Path) -> tuple[bool, list[str]]:
    expected = build_index(knowledge_dir)
    actual = json.loads(index_path.read_text(encoding="utf-8"))
    errors: list[str] = []
    if actual.get("totalModules") != expected["totalModules"]:
        errors.append(
            f"totalModules mismatch: index={actual.get('totalModules')} "
            f"modules={expected['totalModules']}"
        )
    if actual.get("totalSections") != expected["totalSections"]:
        errors.append(
            f"totalSections mismatch: index={actual.get('totalSections')} "
            f"sections={expected['totalSections']}"
        )
    actual_by_id = {m.get("artifactId"): m for m in actual.get("modules", [])}
    for expected_module in expected["modules"]:
        artifact_id = expected_module["artifactId"]
        actual_module = actual_by_id.get(artifact_id)
        if actual_module is None:
            errors.append(f"index missing module {artifact_id}")
            continue
        if actual_module.get("sectionCount") != expected_module["sectionCount"]:
            errors.append(
                f"{artifact_id} sectionCount mismatch: "
                f"index={actual_module.get('sectionCount')} "
                f"modules={expected_module['sectionCount']}"
            )
        actual_sections = {s.get("section") for s in actual_module.get("sections", [])}
        expected_sections = {s["section"] for s in expected_module["sections"]}
        if actual_sections != expected_sections:
            missing = sorted(expected_sections - actual_sections)
            extra = sorted(actual_sections - expected_sections)
            errors.append(f"{artifact_id} sections differ: missing={missing} extra={extra}")
    return not errors, errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify the existing index")
    args = parser.parse_args()

    if args.check:
        if not INDEX_OUT.is_file():
            print("INDEX MISSING; run without --check to build it")
            return 1
        started = time.perf_counter()
        ok, errors = verify_index(INDEX_OUT, KNOWLEDGE_DIR)
        elapsed = (time.perf_counter() - started) * 1000
        if not ok:
            print("INDEX CHECK FAILED")
            for error in errors:
                print(" -", error)
            return 1
        index_bytes = INDEX_OUT.stat().st_size
        full_bytes = sum(p.stat().st_size for p in detail_files(KNOWLEDGE_DIR))
        print(
            f"INDEX CHECK PASSED: {INDEX_OUT.stat().st_size} bytes "
            f"vs {full_bytes} bytes full modules"
        )
        print(f"index verification took {elapsed:.1f} ms")
        return 0

    started = time.perf_counter()
    index = build_index(KNOWLEDGE_DIR)
    build_ms = (time.perf_counter() - started) * 1000
    INDEX_OUT.write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    full_bytes = sum(p.stat().st_size for p in detail_files(KNOWLEDGE_DIR))
    print(
        f"wrote {INDEX_OUT} "
        f"({index['totalModules']} modules, {index['totalSections']} sections)"
    )
    print(
        f"index size {INDEX_OUT.stat().st_size} bytes vs full {full_bytes} bytes; "
        f"built in {build_ms:.1f} ms"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
