# -*- coding: utf-8 -*-
"""章节归属提案：把每个知识点对到教材的（册, 章）。

依据：`kb_tools/chapter_structure_full.json` 给出 册 → 章 → 节 的权威结构。
做法：节点的名称与哪些节名共用实义字最多，就归到那一节所在的章。
产出按"来源定位单元"聚合——同一专题的节点通常落同一章，聚合后人工只需审单元级
结论，只在单元内部有分歧时才逐节点看。

为什么不能沿用现有归属：现有 boundary 的定位串是关键词硬映射的产物（kb_tools/
kw_chapter_map），已证实把圆周运动/抛体/磁场都挂到了"运动的描述"下。

用法：PYTHONPATH=tools python -m kb_build.propose_chapter_by_node [--write]
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io, tables

TOC = pack_io.REPO / "kb_tools" / "chapter_structure_full.json"
_SUBJECT_OF_BOOK = (("数学", "MATH"), ("物理", "PHYSICS"), ("化学", "CHEMISTRY"), ("生物", "BIOLOGY"))
# 节名前的编号：`1.1 `、`1 `、`第一节 ` 等
_NUMBERING = re.compile(r"^(第[一二三四五六七八九十百]+[章节]|[0-9]+(\.[0-9]+)?)\s*")
# 通用字不参与相似度，否则"的定义/规律"这类词尾会主导匹配
_STOP = set("的与和及等其了是有在为对用把从到以之上下中应用分析理解掌握认识了解初步简单常见典型")


def load_toc() -> list[dict[str, str]]:
    """返回 [{subject, book, chapter, section, core}]。"""
    raw = json.loads(TOC.read_text(encoding="utf-8"))
    rows: list[dict[str, str]] = []
    for book, chapters in raw.items():
        subject = next((s for key, s in _SUBJECT_OF_BOOK if key in book), "")
        if not subject:
            continue
        for chapter in chapters:
            chapter_name = chapter.get("name") or ""
            for section in chapter.get("secs") or []:
                core = _NUMBERING.sub("", str(section)).strip()
                rows.append({
                    "subject": subject, "book": book,
                    "chapter": chapter_name, "section": str(section), "core": core,
                })
    return rows


def _content_chars(text: str) -> set[str]:
    return {c for c in text if c not in _STOP and not c.isspace()
            and not c.isascii() and c not in "（）()、，。；：—－"}


def best_section(name: str, candidates: list[dict[str, str]]) -> tuple[dict[str, str] | None, float]:
    """按实义字重合度找最匹配的节。"""
    target = _content_chars(_NUMBERING.sub("", name))
    if not target:
        return None, 0.0
    best, best_score = None, 0.0
    for row in candidates:
        section_chars = _content_chars(row["core"])
        if not section_chars:
            continue
        score = len(target & section_chars) / len(target | section_chars)
        if score > best_score:
            best, best_score = row, score
    return best, best_score


def _source_unit(text: str) -> str:
    match = re.search(r"《(.+?)》", text or "")
    title = match.group(1) if match else (text or "").strip()
    title = re.sub(r"（[^）]*(学生版|教师版|全国通用|知识清单|讲义|复习讲义)[^）]*）", "", title)
    return re.sub(r"\.docx?$", "", title).strip()


def build() -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    toc = load_toc()
    by_subject: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in toc:
        by_subject[row["subject"]].append(row)

    pack = pack_io.load_json(pack_io.pack_path())
    actions = tables.load_node_actions()

    def final_name(subject: str, slug: str, original: str) -> str:
        row = actions.get((subject, slug))
        if row and row["action"] == "rename" and row["new_name"]:
            return row["new_name"]
        return original

    units: dict[str, list[dict[str, str]]] = defaultdict(list)
    for subject, _topic, point in pack_io.iter_points(pack):
        if subject not in by_subject:
            continue
        name = final_name(subject, point["slug"], point["name"])
        section, score = best_section(name, by_subject[subject])
        units[_source_unit(point.get("sourceLocator", ""))].append({
            "subject": subject,
            "slug": point["slug"],
            "name": name,
            "book": section["book"] if section else "",
            "chapter": section["chapter"] if section else "",
            "section": section["section"] if section else "",
            "score": f"{score:.2f}",
        })

    unit_rows: list[dict[str, str]] = []
    node_rows: list[dict[str, str]] = []
    for unit, items in units.items():
        combos = Counter((i["book"], i["chapter"]) for i in items if i["book"])
        if len(combos) == 1 and all(i["book"] for i in items):
            book, chapter = combos.most_common(1)[0][0]
            unit_rows.append({
                "source_unit": unit, "nodes": str(len(items)),
                "book": book, "chapter": chapter,
                "agreement": f"{combos.most_common(1)[0][1]}/{len(items)}",
                "decision": "proposed",
            })
        else:
            unit_rows.append({
                "source_unit": unit, "nodes": str(len(items)),
                "book": "", "chapter": "",
                "agreement": f"{combos.most_common(1)[0][1] if combos else 0}/{len(items)}",
                "decision": "needs_split",
            })
            node_rows.extend(items)
    return unit_rows, node_rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    unit_rows, node_rows = build()
    unanimous = [r for r in unit_rows if r["decision"] == "proposed"]
    split = [r for r in unit_rows if r["decision"] != "proposed"]
    print(f"来源单元 {len(unit_rows)} 个：一致 {len(unanimous)}，需拆分逐节点定章 {len(split)}")
    print(f"需逐节点定章的节点数 {len(node_rows)}")
    print()
    print("需拆分的单元（按节点数降序，前 20）：")
    for row in sorted(split, key=lambda r: -int(r["nodes"]))[:20]:
        print(f"   {row['nodes']:>4} 一致度{row['agreement']:>9}  {row['source_unit'][:52]}")

    if args.write:
        for name, rows, fields in (
            ("chapter_by_source.csv", unit_rows,
             ["source_unit", "nodes", "book", "chapter", "agreement", "decision"]),
            ("chapter_by_node.csv", node_rows,
             ["subject", "slug", "name", "book", "chapter", "section", "score"]),
        ):
            path = tables.TABLES_DIR / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("w", encoding="utf-8", newline="") as fh:
                writer = csv.DictWriter(fh, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)
            print(f"已写入 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
