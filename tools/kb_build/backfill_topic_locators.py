# -*- coding: utf-8 -*-
"""补录占位主题的定位串（`定位：待补章表。` → 其节点的真实册/章）。

## 它消灭的失败

历轮工具建主题时给不出章表定位的就写占位 `定位：待补章表。`（`relocate_chapter_points._ensure_theme`、
`merge_topics` 的 create 都是这个惯例）。占位串留在成品里 = 导航层少了"这个主题讲哪一册哪一章"，
也是"章表补录"欠账的可见标记。2026-09-19 现存 23 个。

## 取值来源

**该主题下节点的定位串**（它们已被 `align_chapter_locators` 对齐到人工定稿的章表）——
取众数（册, 章）；众数不足 50% 的**跳过并报出**（跨章主题不该硬写一个章）。
不改任何节点，只改主题自己的 `sourceLocator` 字段。

## 无损

- 只动占位主题的 `sourceLocator`，其余字段逐字节不变；
- 点数/slug 集合/主题集合不变；
- 幂等：补过一遍后重跑 0 改动。

## 用法

    PYTHONPATH=tools python -m kb_build.backfill_topic_locators            # 报告
    PYTHONPATH=tools python -m kb_build.backfill_topic_locators --write    # 写回
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from kb_build import pack_io

PLACEHOLDER = "待补章表"


def _book_chapter(body: str) -> tuple[str, str]:
    """从定位串 body 里取（册, 章号 章名）。与 gate._place_of 同口径的放宽版。"""
    segs = [x.strip() for x in body.split("·") if x.strip()]
    if not segs:
        return "", ""
    if len(segs) >= 2:
        return segs[0], segs[1]
    book, _, tail = segs[0].partition(" ")
    return (book or segs[0]), tail


def collect(pack: dict) -> list[dict]:
    out = []
    for s in pack["subjects"]:
        for t in s["topics"]:
            loc = t.get("sourceLocator") or ""
            if PLACEHOLDER not in loc:
                continue
            votes = Counter()
            for p in t.get("knowledgePoints") or []:
                body = (p.get("boundary") or "").split("定位：", 1)[-1].split("。", 1)[0]
                book, chap = _book_chapter(body)
                if book and chap and "未归类" not in book:
                    votes[(book, chap)] += 1
            total = sum(votes.values())
            if not total:
                out.append({"topic": t, "vote": None, "share": 0.0, "votes": votes})
                continue
            (book, chap), n = votes.most_common(1)[0]
            out.append({"topic": t, "vote": (book, chap), "share": n / total, "votes": votes})
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    items = collect(pack)
    print(f"占位主题 {len(items)} 个：")
    applied = skipped = 0
    for it in items:
        t = it["topic"]
        print(f"  [{next(s['subject'] for s in pack['subjects'] if t in s['topics'])}] {t['slug']}")
        if it["vote"] is None:
            print(f"     ! 跳过：无节点或节点定位串不可解析（{len(t.get('knowledgePoints') or [])} 点）")
            skipped += 1
            continue
        if it["share"] < 0.5:
            print(f"     ! 跳过：众数占比 {it['share']:.0%} < 50%，跨章主题不硬写：{it['votes'].most_common(3)}")
            skipped += 1
            continue
        book, chap = it["vote"]
        new_loc = f"定位：{book}·{chap}。"
        print(f"     ⇒ {new_loc}（众数 {it['share']:.0%}）")
        if args.write:
            t["sourceLocator"] = new_loc
            applied += 1

    if not args.write:
        print("\n（未写盘；加 --write 生效）")
        return 0
    pack_io.dump_json(pack, path)
    if applied:
        print(f"\n→ 已补录 {applied} 个，跳过 {skipped} 个；写回 {path}")
    else:
        print(f"\n无改动（跳过 {skipped}）")
    # 幂等：剩余占位必须恰好就是"跳过"的那些（跨章/无节点），重跑不得再改已补录的
    still = [t for s in pack_io.load_json(path)["subjects"] for t in s["topics"]
             if PLACEHOLDER in (t.get("sourceLocator") or "")]
    if len(still) != skipped:
        print(f"幂等校验失败：剩余占位 {len(still)} ≠ 跳过 {skipped}")
        return 1
    print(f"幂等校验通过：剩余占位 {len(still)} 个均为跳过项")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
