# -*- coding: utf-8 -*-
"""按章列出空白节点（无任何材料绑定）与本章的五三页，供逐章录入时取活。

空白节点在 App 里检索不到——相当于不存在。逐章录入前先用它把「要补哪些节点、
该读哪几页」一次取全，避免读完整章再回头找节点。

  用法：
    PYTHONPATH=tools python -m kb_build.blank_nodes                 # 全部章，按缺口排序
    PYTHONPATH=tools python -m kb_build.blank_nodes --chapter 烃的衍生物
    PYTHONPATH=tools python -m kb_build.blank_nodes --subject CHEMISTRY --limit 8
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
ROUTED = pack_io.REPO / "tools" / "kb_build" / "tables" / "wusan_routed.csv"


def load_staging():
    pack = json.loads((STAGING / pack_io.PACK_NAME).read_text(encoding="utf-8"))
    bound: set[str] = set()
    for path in sorted(STAGING.glob("moe-2025-teaching-support-v2-*.json")):
        for m in json.loads(path.read_text(encoding="utf-8"))["materials"]:
            for b in m.get("bindings") or []:
                bound.add(b["knowledgeNodeId"])
    return pack, bound


def collect(pack, bound, subject_filter: str | None):
    rows: dict[tuple[str, str, str], list[dict]] = defaultdict(list)
    for subject in pack["subjects"]:
        name = subject["subject"]
        if subject_filter and name != subject_filter:
            continue
        by_slug = {t["slug"]: t for t in subject["topics"]}
        for topic in subject["topics"]:
            chain, cursor = [], topic
            while cursor is not None:
                chain.append(cursor["name"])
                parent = cursor.get("parentSlug")
                cursor = by_slug.get(parent) if parent else None
            chain.reverse()
            if len(chain) < 2:
                continue
            key = (name, chain[0], chain[1])
            for point in topic.get("knowledgePoints") or []:
                nid = f"kb:{pack['packId']}:{name.lower()}:atomic:{point['slug']}"
                if nid not in bound:
                    rows[key].append({"topic": topic["name"], **point})
    return rows


def routed_pages() -> dict[tuple[str, str], list[str]]:
    pages: dict[tuple[str, str], list[str]] = defaultdict(list)
    if not ROUTED.exists():
        return pages
    with ROUTED.open(encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            pages[(row["subject"], row["chapter"])].append(row["pdf_page"])
    return pages


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chapter", default=None, help="章名子串")
    parser.add_argument("--subject", default=None)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--slugs", action="store_true", help="同时打印 slug（录入时要绑它）")
    args = parser.parse_args(argv)

    pack, bound = load_staging()
    rows = collect(pack, bound, args.subject)
    pages = routed_pages()

    picked = []
    for key, items in rows.items():
        if args.chapter and args.chapter not in key[2]:
            continue
        picked.append((key, items))
    picked.sort(key=lambda kv: (-len(kv[1]), kv[0]))

    total = sum(len(v) for _k, v in picked)
    print(f"空白节点合计 {total} 个，分布 {len(picked)} 章")
    if args.limit:
        picked = picked[:args.limit]
    for (subject, book, chapter), items in picked:
        pages_here = sorted(set(pages.get((subject, chapter), [])))
        span = f"{pages_here[0]}–{pages_here[-1]}（{len(pages_here)} 页）" if pages_here else "无五三页"
        print()
        print(f"■ [{subject}] {book} {chapter} —— {len(items)} 个空白；五三 {span}")
        kinds = Counter(p["kind"] for p in items)
        print("   kind 分布：" + "、".join(f"{k}×{n}" for k, n in kinds.most_common()))
        for p in items:
            slug = f"  <{p['slug']}>" if args.slugs else ""
            print(f"    {p['kind']:<12}| {p['topic'][:12]:<13}| {p['name']}{slug}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
