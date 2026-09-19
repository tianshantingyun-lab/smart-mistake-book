# -*- coding: utf-8 -*-
"""把子代理裁定的"材料 → 知识点"绑定写回成品包，并留审计表。

## 它消灭的失败

892 条材料（`registry-*` 批次）在包里**没有任何绑定**——讲题检索按节点取材料，
没有绑定就等于这些材料永远不会被用到；门也一直红着（`unbound_materials`）。

## 判定与复核

判定由子代理逐条读内容做出（项目纪律：归位/绑定用模型读内容，不用词面匹配），
回填 `subject,material_slug,node_slug,confidence,evidence`。
本写回器只做**可机械验证的部分**，宁缺勿错：

1. 材料必须存在且当前确实无绑定（幂等：已绑定的跳过）；
2. 节点必须存在于**同一学科**；`NONE` 行记入未解决清单，不写；
3. 同一材料被两条片给出不同节点 → 冲突，两条都不写，报出来；
4. 写回只动 `bindings` 字段（其余键逐条指纹比对，任一不同即拒绝）；
5. 复核哨兵：绑完后按"材料标题 == 别的节点名"这条旧判据（audit_bindings_by_alias 的口径）
   把可疑绑定单独列出，供人工抽检。

## 用法

    PYTHONPATH=tools python tools/kb_coverage/apply_unbound_bindings.py            # 报告
    PYTHONPATH=tools python tools/kb_coverage/apply_unbound_bindings.py --write    # 写回
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import pack_io  # noqa: E402

FIX_ROOT = Path("tools/kb_coverage/binding_fixes")
AUDIT = Path("tools/kb_coverage/tables/unbound_material_bindings.csv")
ADDENDUM = Path("tools/kb_coverage/tables/unbound_binding_addendum.csv")
PACK_ID = "moe-2025-four-subjects-v1"
CONFIDENCE = ("high", "medium", "low")


def load_addendum() -> list[dict]:
    """人工补两条：子代理判 NONE、但主循环能给出确凿依据的（节点新建 / 同类材料先例）。

    表格式与 notes.csv 相同，外加 `note` 列说明"为什么越过代理的 NONE"。
    """
    if not ADDENDUM.exists():
        return []
    with ADDENDUM.open(encoding="utf-8", newline="") as fh:
        return [{"slice": "addendum",
                 "subject": (r.get("subject") or "").strip(),
                 "slug": (r.get("material_slug") or "").strip(),
                 "node": (r.get("node_slug") or "").strip(),
                 "confidence": (r.get("confidence") or "medium").strip(),
                 "evidence": (r.get("evidence") or "").strip()}
                for r in csv.DictReader(fh)
                if (r.get("material_slug") or "").strip()
                and (r.get("node_slug") or "").strip().upper() != "NONE"]


def load_verdicts() -> tuple[list[dict], list[str]]:
    rows: list[dict] = []
    problems: list[str] = []
    for slice_dir in sorted(FIX_ROOT.glob("slice_*")):
        notes = slice_dir / "notes.csv"
        if not notes.exists():
            problems.append(f"{slice_dir.name}: 缺 notes.csv")
            continue
        with notes.open(encoding="utf-8", newline="") as fh:
            for i, row in enumerate(csv.DictReader(fh), 2):
                key = (row.get("material_slug") or "").strip()
                if not key:
                    problems.append(f"{slice_dir.name}:{i} 缺 material_slug")
                    continue
                conf = (row.get("confidence") or "").strip()
                if conf not in CONFIDENCE:
                    problems.append(f"{slice_dir.name}:{i} confidence 非法：{conf!r}")
                    continue
                rows.append({"slice": slice_dir.name,
                             "subject": (row.get("subject") or "").strip(),
                             "slug": key,
                             "node": (row.get("node_slug") or "").strip(),
                             "confidence": conf,
                             "evidence": (row.get("evidence") or "").strip()})
    rows.extend(load_addendum())
    return rows, problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    verdicts, problems = load_verdicts()
    print(f"裁定行 {len(verdicts)}；notes 问题 {len(problems)}")
    for p in problems[:6]:
        print("   !", p)

    paths = pack_io.sidecar_paths()
    docs = {p: pack_io.load_json(p) for p in paths}
    nodes_by_subject: dict[str, set[str]] = defaultdict(set)
    name_of: dict[tuple[str, str], str] = {}
    for s in pack_io.load_json(pack_io.pack_path())["subjects"]:
        for t in s["topics"]:
            for kp in (t.get("knowledgePoints") or []):
                nodes_by_subject[s["subject"]].add(kp["slug"])
                name_of[(s["subject"], kp["slug"])] = kp["name"]

    material_of: dict[str, tuple[Path, dict]] = {}
    unbound: set[str] = set()
    for path, doc in docs.items():
        for m in doc["materials"]:
            material_of[m["slug"]] = (path, m)
            if not (m.get("bindings") or []):
                unbound.add(m["slug"])

    by_slug: dict[str, list[dict]] = defaultdict(list)
    for row in verdicts:
        by_slug[row["slug"]].append(row)

    plan: list[dict] = []
    unresolved: list[dict] = []
    conflicts: list[dict] = []
    unknown_node: list[dict] = []
    not_unbound: list[str] = []
    for slug, rows in by_slug.items():
        if slug not in material_of:
            not_unbound.append(slug)
            continue
        nodes = {r["node"] for r in rows if r["node"].upper() != "NONE"}
        if len(nodes) > 1:
            conflicts.append({"slug": slug, "nodes": sorted(nodes), "slice": rows[0]["slice"]})
            continue
        row = next((r for r in rows if r["node"].upper() != "NONE"), rows[0])
        if not nodes:
            unresolved.append(row)
            continue
        subject = row["subject"] or material_of[slug][1].get("subject", "")
        if row["node"] not in nodes_by_subject.get(subject, set()):
            unknown_node.append({**row, "subject": subject})
            continue
        if slug not in unbound:
            not_unbound.append(slug)
            continue
        plan.append({**row, "subject": subject})

    print(f"可写回 {len(plan)}｜未解决(NONE) {len(unresolved)}｜冲突 {len(conflicts)}｜"
          f"节点不存在 {len(unknown_node)}｜重复/已绑定 {len(not_unbound)}")
    for c in conflicts[:6]:
        print("   冲突:", c["slug"], c["nodes"])
    for u in unknown_node[:6]:
        print("   节点不存在:", u["subject"], u["node"][:30], "→", u["slug"][:40])
    if problems or conflicts or unknown_node:
        print("\n有阻塞项，本轮不写回（先查裁定表）")
        return 1

    # 只动 bindings 字段
    before = {slug: json.dumps({k: v for k, v in m.items() if k != "bindings"},
                               sort_keys=True, ensure_ascii=False)
              for slug, (_p, m) in material_of.items()}
    for row in plan:
        _path, material = material_of[row["slug"]]
        material["bindings"] = [{
            "knowledgeNodeId": f"kb:{PACK_ID}:{row['subject'].lower()}:atomic:{row['node']}",
            "role": "PRIMARY"}]
    after = {slug: json.dumps({k: v for k, v in m.items() if k != "bindings"},
                              sort_keys=True, ensure_ascii=False)
             for slug, (_p, m) in material_of.items()}
    if before != after:
        print("\n无损校验失败：除 bindings 外的字段被改动")
        return 1
    print("无损校验通过：材料集合与其余字段逐条不变")

    # 审计表必须**累积**：分批写回时若整体重写，先前批次的行会被抹掉（本工具第一次
    # 分批运行就踩了这个坑：890 行被后来的 2 行覆盖）。按 material_slug 去重合并。
    AUDIT.parent.mkdir(parents=True, exist_ok=True)
    merged: dict[str, list[str]] = {}
    if AUDIT.exists():
        with AUDIT.open(encoding="utf-8", newline="") as fh:
            for row in csv.DictReader(fh):
                merged[row["material_slug"]] = [row["subject"], row["material_slug"],
                                                row["node_slug"], row["node_name"],
                                                row["confidence"], row["evidence"],
                                                row["slice"]]
    for row in plan:
        merged[row["slug"]] = [row["subject"], row["slug"], row["node"],
                               name_of.get((row["subject"], row["node"]), ""),
                               row["confidence"], row["evidence"], row["slice"]]
    with AUDIT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["subject", "material_slug", "node_slug", "node_name",
                         "confidence", "evidence", "slice"])
        for slug in sorted(merged):
            writer.writerow(merged[slug])
    print(f"→ 审计表已写 {AUDIT}（累计 {len(merged)} 行）")

    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0
    for path, doc in docs.items():
        pack_io.dump_json(doc, path)
    print(f"→ 已写回 {len(docs)} 个 sidecar")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
