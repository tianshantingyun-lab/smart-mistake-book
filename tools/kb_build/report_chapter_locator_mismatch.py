# -*- coding: utf-8 -*-
"""把"定位串与章节表不一致"拆成几类，供裁定权威源。

## 它消灭的失败

门指标 `chapter_locator_mismatch` 报的是**一个数字**（1676），但那个数字里混着两种完全
不同的东西：

- **同一章、写法不同**：`铁与金属材料` vs `第三章 铁 金属材料`、`数列` vs `第四章 数列`、
  `有机化学基础` vs `第三章 烃的衍生物`——归属其实一致，只是章名的写法有两套
  （节点的 `定位：` 串用主题式简写，章表用教材正式章名带章号）；
- **归属真的不同**：`函数的概念与性质` vs `第四章 指数函数与对数函数`。

混在一起看会得出错的处置：按章表批量重写 1676 处，会把"真不同"的那些一起固化；
反过来把这些都当"格式问题"放过，又会让真正错归属的节点永远不被发现。

因此本工具只做一件事：**分类 + 出表**，不改成品。裁定权威源之后，执行动作是另一支工具。

## 分类判据（可复核，写在输出里）

对每条不一致，比较 `(册, 章)` 两对值：
1. `prefix_only`：同册、同章，只差 `第X章 ` 前缀；
2. `same_chapter_alias`：同册，去掉章号与"与/和/及/·/空格"后**互相包含**（同一章的两种写法）；
3. `same_book_other_chapter`：同册但章不同——**归属真的不同**；
4. `cross_book`：连册都不同。

## 用法

    PYTHONPATH=tools python -m kb_build.report_chapter_locator_mismatch          # 报告
    PYTHONPATH=tools python -m kb_build.report_chapter_locator_mismatch --csv    # 另出待判清单
"""

from __future__ import annotations

import argparse
import csv
import re
from collections import Counter
from pathlib import Path

from kb_build import gate, gen_chapter_table, pack_io, tables

OUT = Path(__file__).resolve().parent / "tables" / "chapter_locator_disputes.csv"
CLUSTERS = Path(__file__).resolve().parent / "tables" / "chapter_locator_clusters.csv"
CHAPTER_NO = re.compile(r"^第[一二三四五六七八九十百]+章[\s·]*")
NOISE = re.compile(r"[与和及·\s、,，]|实验|探究")
PREFIX_ONLY = re.compile(r"^第[一二三四五六七八九十百]+章[\s·]*")


def _norm(chapter: str) -> str:
    return NOISE.sub("", CHAPTER_NO.sub("", chapter or ""))


def classify(current: tuple[str, str], declared: tuple[str, str]) -> str:
    if current[0] != declared[0]:
        return "cross_book"
    if current[1] == declared[1]:
        return "same"
    cur_no, dec_no = PREFIX_ONLY.sub("", current[1]), PREFIX_ONLY.sub("", declared[1])
    if cur_no == dec_no:
        return "prefix_only"
    a, b = _norm(current[1]), _norm(declared[1])
    if a and b and (a in b or b in a):
        return "same_chapter_alias"
    return "same_book_other_chapter"


def collect(pack: dict) -> list[dict]:
    chapter_table = tables.load_chapter_by_source()
    chapter_by_node = tables.load_chapter_map()
    rows = []
    for subject, _t, point in pack_io.iter_points(pack):
        unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
        entry = chapter_table.get(unit)
        current = gate._place_of(point.get("boundary") or "")
        if not current[0] or not current[1] or "未归类" in current:
            continue
        override = chapter_by_node.get((subject, point["slug"]))
        if override:
            declared = (override[0], override[1])
        elif entry is None or entry["decision"] in ("keep_per_node", "split"):
            continue
        else:
            declared = (entry["book"], entry["chapter"])
        kind = classify(current, declared)
        if kind == "same":
            continue
        rows.append({"subject": subject, "slug": point["slug"], "name": point["name"],
                     "kind": kind, "unit": unit,
                     "current_book": current[0], "current_chapter": current[1],
                     "declared_book": declared[0], "declared_chapter": declared[1]})
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", action="store_true", help=f"把待判清单写到 {OUT.name}")
    parser.add_argument("--clusters", action="store_true",
                        help=f"把待判清单按决策单位（簇）汇总写到 {CLUSTERS.name}")
    args = parser.parse_args(argv)

    rows = collect(pack_io.load_json(pack_io.pack_path()))
    kinds = Counter(r["kind"] for r in rows)
    print(f"不一致 {len(rows)} 条，分类：")
    for kind, label in (("prefix_only", "只差『第X章』前缀"),
                        ("same_chapter_alias", "同章、两种写法（归属一致）"),
                        ("same_book_other_chapter", "同册、章不同（归属真的不同）"),
                        ("cross_book", "连册都不同")):
        print(f"   {kinds.get(kind, 0):5d}  {label}")

    pairs = Counter((r["declared_book"], r["declared_chapter"], r["current_chapter"])
                    for r in rows if r["kind"] != "prefix_only")
    print("\n章映射 Top10（章表 → 节点自己写的章）:")
    for (book, dec, cur), n in pairs.most_common(10):
        print(f"   {n:5d}  {book} / {dec}  ⇐  {cur}")

    print("\n按学科:", dict(Counter(r["subject"] for r in rows)))

    hard = [r for r in rows if r["kind"] in ("same_book_other_chapter", "cross_book")]
    print(f"\n需要逐条判归属的 {len(hard)} 条，样例：")
    for r in hard[:8]:
        print(f"   [{r['subject']}] {r['name'][:26]}: 定位于 {r['current_book']} {r['current_chapter']}"
              f"，章表为 {r['declared_book']} {r['declared_chapter']}")

    if args.csv:
        # 显式列名（不取 rows[0]）：对齐之后 rows 会是空的，取首行会 IndexError。
        fields = ["subject", "slug", "name", "kind", "unit",
                  "current_book", "current_chapter", "declared_book", "declared_chapter"]
        with OUT.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(rows)
        print(f"\n→ 已写出 {OUT}（{len(rows)} 行，含 kind 列）")

    if args.clusters:
        # 决策单位是**簇**不是条目：同一个"章表章 → 节点写法"对只需判一次。
        groups: dict[tuple, list[dict]] = {}
        for r in hard:
            key = (r["subject"], r["declared_book"], r["declared_chapter"],
                   r["current_book"], r["current_chapter"])
            groups.setdefault(key, []).append(r)
        with CLUSTERS.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh)
            writer.writerow(["subject", "count", "declared_book", "declared_chapter",
                             "current_book", "current_chapter", "samples"])
            for key, items in sorted(groups.items(), key=lambda kv: -len(kv[1])):
                writer.writerow([key[0], len(items), key[1], key[2], key[3], key[4],
                                 " / ".join(r["name"][:24] for r in items[:3])])
        print(f"→ 已写出 {CLUSTERS}（{len(groups)} 个簇，覆盖 {len(hard)} 条）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
