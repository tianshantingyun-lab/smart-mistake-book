# -*- coding: utf-8 -*-
"""覆盖总账：每个**规范章**上，"库里有什么" 对 "五三给了什么"。

它回答的是这次炼化最上面的那个问题——**全量覆盖还差在哪里**——而不是某一页
该怎么拆。前面几个脚本各自只看得见一面（转录件、候选名、归位、绑定），
没有一张表把它们按章并排放，于是"哪一章其实一个节点都没有""哪一章有节点但
一个材料都没绑""哪一章五三根本讲了而库里连章节点都没建"只能靠零散印象。

三类缺口分开报，因为处理方式不同：
- `库无此章`：五三讲了、知识库连章节点都没有 → 先补章节点（规范第 40 行）。
- `无节点`：章在、但库里一个知识点都没有 → 整章要新建。
- `节点无材料`：节点在、材料一条都没绑 → 该节点在 App 里检索不到，等于不存在。

用法：
  PYTHONPATH=tools python -m kb_build.wusan_plan            # 报告
  PYTHONPATH=tools python -m kb_build.wusan_plan --write    # 写 tables/
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io, wusan_route

OUT_NAME = "wusan_plan.csv"
STAGING = pack_io.REPO / "build" / "kb-staging"


class Chapter:
    __slots__ = ("subject", "book", "chapter", "nodes", "nodes_with_material",
                 "pages", "candidates", "in_pack")

    def __init__(self, subject: str, book: str, chapter: str) -> None:
        self.subject, self.book, self.chapter = subject, book, chapter
        self.nodes = 0
        self.nodes_with_material = 0
        self.pages = 0
        self.candidates = 0
        self.in_pack = False

    @property
    def gap(self) -> str:
        if not self.in_pack:
            return "库无此章"
        if not self.nodes:
            return "无节点"
        if not self.nodes_with_material:
            return "全章无材料"
        if self.nodes_with_material < self.nodes:
            return "部分无材料"
        return "有节点有材料"


def _pack_rows() -> tuple[dict[tuple[str, str, str], int],
                          dict[tuple[str, str, str], int], dict[str, str]]:
    """返回 (章→节点数, 章→有材料的节点数, 节点id→章key)。"""
    pack_path = STAGING / pack_io.PACK_NAME
    if not pack_path.exists():
        raise SystemExit(f"缺少 {pack_path}；先跑 PYTHONPATH=tools python -m kb_build.build --write")
    pack = json.loads(pack_path.read_text(encoding="utf-8"))

    bound: set[str] = set()
    for path in sorted(STAGING.glob("moe-2025-teaching-support-v2-*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        for material in doc["materials"]:
            for binding in material.get("bindings") or []:
                bound.add(binding["knowledgeNodeId"])

    count: Counter = Counter()
    with_material: Counter = Counter()
    for subject in pack["subjects"]:
        name = subject["subject"]
        by_slug = {t["slug"]: t for t in subject["topics"]}
        for topic in subject["topics"]:
            chain = []
            cursor = topic
            while cursor:
                chain.append(cursor["name"])
                cursor = by_slug.get(cursor.get("parentSlug"))
            chain.reverse()                       # 册 / 章 / 主题
            if len(chain) < 2:
                continue
            key = (name, chain[0], chain[1])
            for point in topic.get("knowledgePoints") or []:
                count[key] += 1
                node_id = f"kb:{pack['packId']}:{name.lower()}:atomic:{point['slug']}"
                if node_id in bound:
                    with_material[key] += 1
    return count, with_material, pack


def collect() -> list[Chapter]:
    known = wusan_route.canonical_pairs()
    count, with_material, _pack = _pack_rows()

    chapters: dict[tuple[str, str, str], Chapter] = {}
    missing_chapters: Counter = Counter()
    for subject, pairs in known.items():
        for book, chapter in pairs:
            chapters[(subject, book, chapter)] = Chapter(subject, book, chapter)

    for key, nodes in count.items():
        entry = chapters.get(key)
        if entry is None:
            entry = chapters[key] = Chapter(*key)
        entry.nodes = nodes
        entry.nodes_with_material = with_material.get(key, 0)
        entry.in_pack = True

    # 五三侧：页数按归位表，候选按页挂载
    routed, missed = wusan_route.run()
    pages: Counter = Counter()
    for record in routed:
        if record["book"] and record["chapter"]:
            pages[(record["subject"], record["book"], record["chapter"])] += 1

    # 未归位的页里，"库内根本没有这一章"的章要显式带出来——这是结构性缺口，
    # 归位那一步会把它判成"匹配不上"而让它从报告里消失。
    by_page: dict[tuple[str, str], tuple[str, str]] = {
        (r["subject"], r["pdf_page"]): (r["book"], r["chapter"]) for r in routed}
    for record in missed:
        for book, chapter in wusan_route.raw_pairs(
                record["subject"], record["raw"], known.get(record["subject"], set())):
            key = (record["subject"], book, chapter)
            if key in known.get(record["subject"], set()):
                continue
            missing_chapters[key] += 1
            # 这一页的候选也要挂到这个（库内不存在的）章上：否则报告里它的
            # 候选数是 0，读起来像"这一章五三没讲东西"，而真相是"这一章库还没建"。
            by_page.setdefault((record["subject"], record["pdf_page"]), (book, chapter))

    candidates: Counter = Counter()
    points_path = pack_io.REPO / "tools" / "kb_build" / "tables" / "wusan_page_points.csv"
    with points_path.open(encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            place = by_page.get((row["subject"], row["pdf_page"]))
            if place and place[0]:
                candidates[(row["subject"], place[0], place[1])] += 1

    for key, hits in pages.items():
        entry = chapters.get(key)
        if entry is None:
            entry = chapters[key] = Chapter(*key)
        entry.pages = hits
    for key, hits in missing_chapters.items():
        entry = chapters.get(key)
        if entry is None:
            entry = chapters[key] = Chapter(*key)
        entry.pages = max(entry.pages, hits)
    for key, hits in candidates.items():
        entry = chapters.get(key)
        if entry is None:
            entry = chapters[key] = Chapter(*key)
        entry.candidates = hits

    return sorted(chapters.values(), key=lambda c: (c.subject, c.book, c.chapter))


def write_table(chapters: list[Chapter]) -> Path:
    path = pack_io.REPO / "tools" / "kb_build" / "tables" / OUT_NAME
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["subject", "book", "chapter", "kb_nodes", "kb_nodes_with_material",
                         "wusan_pages", "wusan_candidates", "gap"])
        for chapter in chapters:
            writer.writerow([chapter.subject, chapter.book, chapter.chapter,
                             chapter.nodes, chapter.nodes_with_material,
                             chapter.pages, chapter.candidates, chapter.gap])
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--sample", type=int, default=14)
    args = parser.parse_args(argv)

    chapters = collect()
    gaps = Counter(c.gap for c in chapters)
    print(f"规范章 {len(chapters)} 个")
    for gap, hits in gaps.most_common():
        print(f"  {gap:<12} {hits}")

    print()
    print("节点无材料的章（按缺口大小，五三有内容者优先）：")
    rows = [c for c in chapters if c.in_pack and c.nodes_with_material < c.nodes and c.pages]
    rows.sort(key=lambda c: (-(c.nodes - c.nodes_with_material), -c.pages))
    for chapter in rows[:args.sample]:
        print(f"  {chapter.nodes - chapter.nodes_with_material:>4} 个无材料 / {chapter.nodes:>3} 节点"
              f" 五三 {chapter.pages:>2} 页 {chapter.candidates:>3} 候选"
              f"  [{chapter.subject}] {chapter.book} {chapter.chapter}")

    missing = [c for c in chapters if not c.in_pack]
    if missing:
        print()
        print(f"五三讲了但库内连章节点都没有的章 {len(missing)} 个：")
        for chapter in missing[:args.sample]:
            print(f"  五三 {chapter.pages:>2} 页 {chapter.candidates:>3} 候选"
                  f"  [{chapter.subject}] {chapter.book} {chapter.chapter}")

    if args.write:
        path = write_table(chapters)
        print()
        print(f"已写出 {path.relative_to(pack_io.REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
