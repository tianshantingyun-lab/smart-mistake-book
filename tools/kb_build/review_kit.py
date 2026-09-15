# -*- coding: utf-8 -*-
"""生成审校包：把待定稿的节点连同它的上下文证据一起列出来。

定名不能靠猜。每个待定节点给出三类证据：
  1. 该节点的原始 boundary 摘录（剥离前，是抽取时留下的原文线索）
  2. 它的来源定位单元（哪本教辅的哪一讲）
  3. 同一来源单元里的材料标题（同专题的相邻条目，用于判断它属于哪个概念）

用法：PYTHONPATH=tools python -m kb_build.review_kit [--action review] [--offset 0] [--limit 40]
"""

from __future__ import annotations

import argparse
import re
from collections import defaultdict

from kb_build import pack_io, tables

_EXAM_MARK = re.compile(r"【典例|【变式|下列说法|正确的是|不正确的是|如图所示|填序号|_{3,}")


def source_unit(text: str) -> str:
    """把来源串规范到《…》内的标题。"""
    match = re.search(r"《(.+?)》", text or "")
    if not match:
        return (text or "").strip()
    title = match.group(1)
    title = re.sub(r"（[^）]*(学生版|教师版|全国通用|知识清单|讲义|复习讲义)[^）]*）", "", title)
    return re.sub(r"\.docx?$", "", title).strip()


def build_kit(action: str) -> list[dict[str, str]]:
    pack = pack_io.load_json(pack_io.pack_path())
    rows = tables.load_node_actions()
    by_point = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}

    # 同来源单元的材料标题，用于判断节点归属
    titles_by_unit: dict[str, list[str]] = defaultdict(list)
    for _path, material in pack_io.load_materials():
        titles_by_unit[source_unit(material.get("sourceLocator", ""))].append(
            material.get("title", ""))

    kit = []
    for (subject, slug), row in rows.items():
        if row["action"] != action:
            continue
        point = by_point.get((subject, slug))
        if point is None:
            continue
        boundary = point.get("boundary") or ""
        position, rest = "", boundary
        match = re.match(r"^定位：(?P<pos>.+?)。", boundary)
        if match:
            position, rest = match.group("pos"), boundary[match.end():]
        unit = source_unit(point.get("sourceLocator", ""))
        neighbours = [t for t in titles_by_unit.get(unit, [])
                      if t and t != point["name"]][:6]
        kit.append({
            "subject": subject,
            "slug": slug,
            "name": point["name"],
            "kind": point.get("kind", ""),
            "position": position,
            "excerpt": rest.strip()[:200],
            "looks_like_exam": "是" if _EXAM_MARK.search(rest) else "",
            "unit": unit,
            "neighbours": " | ".join(t[:26] for t in neighbours),
        })
    return kit


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action", default="review")
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--limit", type=int, default=40)
    args = parser.parse_args(argv)

    kit = build_kit(args.action)
    window = kit[args.offset:args.offset + args.limit]
    print(f"待定稿 {len(kit)} 条；本次显示第 {args.offset + 1}..{args.offset + len(window)} 条")
    print("=" * 100)
    for item in window:
        print(f"\n[{item['subject']}] {item['name']}")
        print(f"   定位: {item['position']}")
        if item["excerpt"]:
            print(f"   原文线索: {item['excerpt']}")
        if item["looks_like_exam"]:
            print("   ⚠ 含例题/题干标记")
        print(f"   同专题材料: {item['neighbours'][:120]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
