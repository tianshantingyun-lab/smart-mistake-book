# -*- coding: utf-8 -*-
"""超配节点清单：一个节点挂了多于 `MAX_TEACHING_REFERENCES`(=4) 条材料。

**为什么这不是"浪费"**：运行时取材料的那条 SQL 是
`WHERE knowledge_node_id IN (:ids) … GROUP BY material_id … LIMIT :limit`，
`LIMIT` 对**整批被请求的节点**生效，另有一个全局字符预算
`MAX_TEACHING_REFERENCE_MARKDOWN_CHARS = 20_000`（先到先得、按排名）。所以：

- 一个节点堆 24 条 → 排在前面的 4 条（同类型内还只按 `title`/`material_id` 排，**与质量无关**）
  进入预算，**其余节点一条也拿不到**；
- **现状（2026-09-25 复算）**：这样的超配节点 **1,670 个**（挂着 23,825 条材料，其中 **17,145 条
  永远进不了预算**）；同一时刻零材料节点 **0 个**（3,572 个知识点全部至少有一条材料）。
  旧值「631 个超配 / 500+ 个零材料」是 **2026-09-14** 的产物，此后材料大规模入库，两处都已过期。
- **复算**（本次数的取法，逐条可重跑）：
  - 零材料 / 薄料：`PYTHONPATH=tools python -m kb_build.report_material_gaps`
    ⇒ 现输出「零材料 0、仅 1 条材料 1,043」；
  - 超配：本工具的判据（同一节点 > `LIMIT`(=4) 条绑定）不变，但**入口当前跑不通**——
    `load()` 的 `STAGING.glob("moe-2025-teaching-support-v2-*.json")` 会连带读入卷索引
    `moe-2025-teaching-support-v2-index.json`（只有 `packId`/`sidecars` 两个键）而
    `KeyError: 'materials'`；取卷改走 `pack_io.sidecar_paths()`（按索引清单取卷）即可复算。
    修 glob 属行为改动，不在本次范围（只记不改）。

本工具产出施工清单：逐节点按**运行时真实排序**（角色秩 → 重教类型优先级 → title → material_id）
列出材料，标出哪 4 条会活下来、哪些会被挤掉，供逐条裁决（留 / 改绑 / 解绑）。

  用法： PYTHONPATH=tools python -m kb_build.overfull_nodes            # 报告
        PYTHONPATH=tools python -m kb_build.overfull_nodes --write    # 写 CSV
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
OUT_NAME = "overfull_nodes.csv"
LIMIT = 4

_PUNCT = re.compile(r"[\s，。、；：（）()［］\[\]【】“”\"'·—\-_/\\|!?？！,.:;]+")


def norm(text: str) -> str:
    return _PUNCT.sub("", text or "")


def bigrams(text: str) -> set[str]:
    """2-gram 集合。**只用来做分诊，不用来做判定**——本仓已实测：零重合里约 54% 本来就是对的。"""
    t = norm(text)
    return {t[i:i + 2] for i in range(len(t) - 1)} or {t}

# 与 KnowledgeTeachingMaterialDao 的 CASE 逐值一致（权威表达在
# TutorTeachingReferenceSelector.reTeachPriority，两处漂移会让"预算给了谁"变成未定义）。
TYPE_PRIORITY = {
    "MISCONCEPTION_GUIDE": 0,
    "WORKED_EXAMPLE": 1,
    "METHOD_MODEL": 2,
    "DERIVATION": 3,
    "CONCEPT_EXPLANATION": 4,
    "REPRESENTATION_GUIDE": 5,
    "COMPLETE_SOLUTION": 6,
}
ROLE_RANK = {"PRIMARY": 0, "SUPPORTING": 1}


def load():
    pack = json.loads((STAGING / pack_io.PACK_NAME).read_text(encoding="utf-8"))
    by_node: dict[str, list[dict]] = defaultdict(list)
    for path in sorted(STAGING.glob("moe-2025-teaching-support-v2-*.json")):
        for material in json.loads(path.read_text(encoding="utf-8"))["materials"]:
            for binding in material.get("bindings") or []:
                by_node[binding["knowledgeNodeId"]].append({
                    "materialId": material["slug"],
                    "title": material["title"],
                    "type": material["type"],
                    "role": binding.get("role", "PRIMARY"),
                    "chars": sum(len(material.get(f) or "") for f in
                                 ("summaryMarkdown", "applicabilityMarkdown",
                                  "contentMarkdown", "boundaryMarkdown")),
                })
    names: dict[str, tuple[str, str, str]] = {}
    for subject in pack["subjects"]:
        name = subject["subject"]
        by_slug = {t["slug"]: t for t in subject["topics"]}
        for topic in subject["topics"]:
            chain, cursor = [], topic
            while cursor is not None:
                chain.append(cursor["name"])
                parent = cursor.get("parentSlug")
                cursor = by_slug.get(parent) if parent else None
            chain.reverse()
            chapter = chain[1] if len(chain) > 1 else ""
            for point in topic.get("knowledgePoints") or []:
                node_id = f"kb:{pack['packId']}:{name.lower()}:atomic:{point['slug']}"
                names[node_id] = (name, chapter, point["name"])
    return by_node, names


def sort_key(item: dict):
    return (ROLE_RANK.get(item["role"], 2), TYPE_PRIORITY.get(item["type"], 7),
            item["title"], item["materialId"])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--sample", type=int, default=6)
    parser.add_argument("--top", type=int, default=0, help="只看最严重的 N 个节点")
    args = parser.parse_args(argv)

    by_node, names = load()
    overfull = []
    for node_id, items in by_node.items():
        if len(items) > LIMIT:
            info = names.get(node_id, ("?", "?", node_id))
            items = sorted(items, key=sort_key)
            for item in items:
                # 只做分诊：零 2-gram 重合 = 机械上可判为"疑似错绑"，但**不能据此直接解绑**
                # ——本仓实测零重合里约 54% 本来就是对的（正确但表述不同）。
                item["weak"] = not (bigrams(item["title"]) & bigrams(info[2]))
            overfull.append((len(items), node_id, info, items))
    overfull.sort(key=lambda row: (-row[0], row[1]))

    total_materials = sum(len(v) for v in by_node.values())
    excess = sum(count - LIMIT for count, _i, _n, _s in overfull)
    held = sum(count for count, *_ in overfull)
    weak_total = sum(1 for _c, _i, _n, items in overfull for it in items if it["weak"])
    weak_excess = sum(1 for _c, _i, _n, items in overfull
                      for it in items[LIMIT:] if it["weak"])
    print(f"超配节点 {len(overfull)} 个；它们挂着 {held} 条材料，其中 {excess} 条永远不会进入预算")
    print(f"全部有绑定材料 {total_materials} 条，落在 {len(by_node)} 个节点上"
          f"——超配节点占 {held * 100 // max(total_materials, 1)}% 的材料")
    print(f"其中与节点名零 2-gram 重合（疑似错绑、可先分诊）：{weak_total} 条；"
          f"这当中被挤掉的 {weak_excess} 条是**最便宜的处置对象**（既错又永远用不上）")
    print()
    print(f"—— 最严重的 {min(args.sample, len(overfull))} 个 ——")
    rows = overfull[:args.top or args.sample]
    for count, node_id, (subject, chapter, name), items in rows:
        print(f"■ {count} 条  [{subject}] {chapter} / {name}")
        for index, item in enumerate(items):
            mark = "留" if index < LIMIT else "挤"
            flag = "疑" if item["weak"] else "  "
            print(f"    {mark}{flag}  [{TYPE_PRIORITY.get(item['type'], 7)}] {item['type'][:20]:<21}"
                  f" {item['chars']:>6} 字  {item['title'][:44]}")
        print()

    if args.write:
        path = pack_io.REPO / "build" / "kb-staging" / OUT_NAME
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh)
            writer.writerow(["node_id", "subject", "chapter", "node_name", "material_count",
                             "verdict_keep_or_move", "rank", "role", "type", "title",
                             "material_slug", "chars", "weak_match"])
            for count, node_id, (subject, chapter, name), items in overfull:
                for index, item in enumerate(items):
                    writer.writerow([node_id, subject, chapter, name, count, "", index + 1,
                                     item["role"], item["type"], item["title"],
                                     item["materialId"], item["chars"],
                                     "yes" if item["weak"] else ""])
        print(f"已写出 {path.relative_to(pack_io.REPO)}（{sum(c for c, *_ in overfull)} 行，"
              f"verdict 列留空待裁决）")

    by_chapter = Counter()
    for count, _node_id, (subject, chapter, _name), _items in overfull:
        by_chapter[f"{subject} {chapter}"] += count
    print()
    print("按章（前 10）：")
    for chapter, count in by_chapter.most_common(10):
        print(f"  {count:>5} 条  {chapter}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
