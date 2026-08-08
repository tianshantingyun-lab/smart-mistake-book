"""Build an addressable fine-grained node catalog from knowledge-detail modules.

Each knowledge point is exposed as one section node, and every property,
method, method step, worked example, and confusion becomes an atomic node with
a stable id and a parent link. The catalog is rebuilt from the same modules the
audits verify, so it never invents content.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KNOWLEDGE_DIR = PROJECT_ROOT / "knowledge-production"
CATALOG_OUT = KNOWLEDGE_DIR / "knowledge-point-node-catalog-2026-v1.json"

EXCLUDE_PARTS = (
    "content-audit",
    "enrichment",
    "alignment",
    "directory",
    "ledger",
    "size-audit",
    "index",
    "node-catalog",
)


def detail_files(knowledge_dir: Path) -> list[Path]:
    out = []
    for path in sorted(knowledge_dir.glob("knowledge-detail-*.json")):
        if not any(part in path.name for part in EXCLUDE_PARTS):
            out.append(path)
    return out


def _slugify(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z\u4e00-\u9fff]+", "-", value.strip()).strip("-")[:48]


def build_catalog(knowledge_dir: Path) -> dict:
    files = detail_files(knowledge_dir)
    nodes: list[dict] = []
    per_subject: dict[str, dict] = {}

    for path in files:
        data = json.loads(path.read_text(encoding="utf-8"))
        subject = data.get("subject")
        module_slug = data.get("moduleSlug")
        subject_stats = per_subject.setdefault(
            subject,
            {"subject": subject, "sectionCount": 0, "itemCount": 0, "nodeCount": 0},
        )
        for section_index, kp in enumerate(data.get("knowledgePoints", [])):
            section = kp.get("section", "")
            section_slug = _slugify(section) or f"section-{section_index}"
            section_node_id = f"kb-detail-v1:{module_slug}:{section_index:04d}:{section_slug}:section"
            nodes.append(
                {
                    "nodeId": section_node_id,
                    "subject": subject,
                    "moduleSlug": module_slug,
                    "section": section,
                    "sectionIndex": section_index,
                    "nodeType": "SECTION",
                    "itemIndex": None,
                    "text": kp.get("conceptSummary", ""),
                    "parentNodeId": None,
                }
            )
            subject_stats["sectionCount"] += 1

            def add_item(
                node_type: str,
                item_index: int,
                text: str,
                parent: str | None,
                extra: dict | None = None,
                id_suffix: str | None = None,
            ) -> None:
                item_slug = id_suffix or f"{node_type.lower()}-{item_index}"
                node_id = f"{section_node_id}:{item_slug}"
                record = {
                    "nodeId": node_id,
                    "subject": subject,
                    "moduleSlug": module_slug,
                    "section": section,
                    "sectionIndex": section_index,
                    "nodeType": node_type,
                    "itemIndex": item_index,
                    "text": text,
                    "parentNodeId": parent,
                }
                if extra:
                    record.update(extra)
                nodes.append(record)
                subject_stats["itemCount"] += 1

            for index, prop in enumerate(kp.get("properties", [])):
                add_item("PROPERTY", index, prop, section_node_id)
            for method_index, method in enumerate(kp.get("methodModels", [])):
                method_node_id = (
                    f"{section_node_id}:method-{method_index}"
                )
                add_item(
                    "METHOD",
                    method_index,
                    method.get("name", ""),
                    section_node_id,
                    {"methodIndex": method_index},
                )
                for step_index, step in enumerate(method.get("steps", [])):
                    add_item(
                        "METHOD_STEP",
                        step_index,
                        step,
                        method_node_id,
                        {
                            "methodIndex": method_index,
                            "methodName": method.get("name", ""),
                        },
                        id_suffix=f"method-{method_index}:step-{step_index}",
                    )
                if method.get("example"):
                    add_item(
                        "METHOD_EXAMPLE",
                        0,
                        method.get("example", ""),
                        method_node_id,
                        {"methodIndex": method_index},
                        id_suffix=f"method-{method_index}:example",
                    )
            for index, worked in enumerate(kp.get("workedExampleStructure", [])):
                add_item("WORKED_EXAMPLE", index, worked.get("example", ""), section_node_id)
            for index, confusion in enumerate(kp.get("commonConfusions", [])):
                add_item("CONFUSION", index, confusion, section_node_id)

    for stats in per_subject.values():
        stats["nodeCount"] = stats["sectionCount"] + stats["itemCount"]

    total_sections = sum(stats["sectionCount"] for stats in per_subject.values())
    total_items = sum(stats["itemCount"] for stats in per_subject.values())
    return {
        "schemaVersion": 1,
        "artifactId": "knowledge-point-node-catalog-2026-v1",
        "generatedFrom": "knowledge-detail-*.json",
        "totalSections": total_sections,
        "totalItemNodes": total_items,
        "totalNodes": total_sections + total_items,
        "perSubject": sorted(per_subject.values(), key=lambda item: item["subject"]),
        "nodes": nodes,
    }


def verify_catalog(path: Path, knowledge_dir: Path) -> tuple[bool, list[str]]:
    expected = build_catalog(knowledge_dir)
    actual = json.loads(path.read_text(encoding="utf-8"))
    errors: list[str] = []
    if actual.get("totalNodes") != expected["totalNodes"]:
        errors.append(
            f"totalNodes mismatch: catalog={actual.get('totalNodes')} "
            f"modules={expected['totalNodes']}"
        )
    actual_ids = [node["nodeId"] for node in actual.get("nodes", [])]
    if len(actual_ids) != len(set(actual_ids)):
        errors.append("catalog contains duplicate node ids")
    expected_ids = {node["nodeId"] for node in expected["nodes"]}
    if set(actual_ids) != expected_ids:
        errors.append("catalog node ids differ from freshly built catalog")
    return not errors, errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    if args.check:
        if not CATALOG_OUT.is_file():
            print("CATALOG MISSING; run without --check to build it")
            return 1
        ok, errors = verify_catalog(CATALOG_OUT, KNOWLEDGE_DIR)
        if not ok:
            print("CATALOG CHECK FAILED")
            for error in errors:
                print(" -", error)
            return 1
        catalog = json.loads(CATALOG_OUT.read_text(encoding="utf-8"))
        print(
            f"CATALOG CHECK PASSED: {catalog['totalNodes']} nodes "
            f"({catalog['totalSections']} sections + {catalog['totalItemNodes']} items)"
        )
        return 0

    catalog = build_catalog(KNOWLEDGE_DIR)
    CATALOG_OUT.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"wrote {CATALOG_OUT} ({catalog['totalNodes']} nodes, "
        f"{catalog['totalSections']} sections, {catalog['totalItemNodes']} items)"
    )
    for stats in catalog["perSubject"]:
        print(
            f"  {stats['subject']}: sections={stats['sectionCount']} "
            f"items={stats['itemCount']} nodes={stats['nodeCount']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
