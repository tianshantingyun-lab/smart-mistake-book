# -*- coding: utf-8 -*-
"""把直接挂在"章"层的知识点下移到其所属"主题"层。

## 它消灭的失败

规范 §2.1 规定章只作空壳分组、知识点只挂主题/子主题。但成品包 167 个知识点直接挂在章层
（化学 157 + 生物 10），其中「有机化学基础」章一个就挂了 108 个——章被当成知识点容器用，
既破坏渐进式披露（I-02），也让"一个知识点挂几条材料"失去统一基准（I-03）。

## 为什么不走 build.py 的 chapter_map 通道

build.py 的 `node_actions` 表与成品包 slug **不同坐标系**（实测 324 个 delete 的 slug 一个
都不在成品里），跑全流程会产出错乱的 staging（章层从 167 恶化到 352）。所以本模块**直接
对成品包做转换**，独立、幂等、有门——和 `shorten_topic_names` 同一模式，不复用 build。

## 只移动，不删不改

一次操作 = 把点从"章 topic"挪到"章下、name=theme 的主题 topic"（没有就建）。
- 点的 `slug` 一个字节不动（它是身份，材料/前置/检索特征都引用它）。
- 材料绑定按 `point_id` 走，挪点不影响。
- 无损 = 知识点总数守恒、每个 slug 仍在、只是 `parentSlug` 变了。
- 残渣/坏名字点（题干、"基本概念"这类）**不在本表**——它们走 rename/delete 通道，
  不是"下移"能解决的，硬塞进某主题等于把垃圾也归了位。

## 用法

    # 报告（会移动几个）
    PYTHONPATH=tools python -m kb_build.relocate_chapter_points
    # 写回成品
    PYTHONPATH=tools python -m kb_build.relocate_chapter_points --write
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from kb_build import pack_io

RELOCATION = "chapter_point_relocation.csv"
TABLES = Path(__file__).resolve().parent / "tables"
COLUMNS = ("subject", "slug", "theme")


def load_relocations(path: Path | None = None) -> dict[tuple[str, str], str]:
    """(subject, slug) -> theme。表必须引用真实存在的点，否则报错（防 name/slug 混用）。"""
    from kb_build import tables
    path = path or (TABLES / RELOCATION)
    if not path.exists():
        return {}
    rows = tables._read(path)
    tables._require_columns(RELOCATION, rows, COLUMNS)
    pack = pack_io.load_json(pack_io.pack_path())
    known = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    out: dict[tuple[str, str], str] = {}
    for r in rows:
        key = (r["subject"].strip(), r["slug"].strip())
        if key not in known:
            raise ValueError(f"{RELOCATION}: {key} 不在知识包中（可能写成了 name）")
        if not r["theme"].strip():
            raise ValueError(f"{RELOCATION}: {key} 缺 theme")
        out[key] = r["theme"].strip()
    return out


def _ensure_theme(topics: list[dict], chapter_slug: str, theme: str,
                  subject: str, taken: set[str]) -> str:
    """章下找/建 name=theme 的主题 topic，返回其 slug。"""
    for t in topics:
        if t.get("parentSlug") == chapter_slug and t["name"] == theme:
            return t["slug"]
    base = "".join(theme.split())[:80] or f"{subject.lower()}-topic"
    slug, n = base, 2
    while slug in taken:
        slug = f"{base}-{n}"
        n += 1
    topics.append({
        "slug": slug, "name": theme,
        "sourceLocator": "定位：待补章表。",
        "parentSlug": chapter_slug,
        "knowledgePoints": [],
    })
    taken.add(slug)
    return slug


def relocate(pack: dict, relocations: dict[tuple[str, str], str]) -> int:
    """就地把章层点下移到主题层。返回移动条数。幂等。

    表里只应列"章层"的点——即当前所在 topic 就是章（有父、且点直接挂它）。
    """
    moved = 0
    not_found: list[tuple[str, str]] = []
    for subject in pack["subjects"]:
        subj = subject["subject"]
        topics = subject["topics"]
        taken = {t["slug"] for t in topics}
        # (subject, slug) -> (所在 topic, 点对象)
        owner: dict[tuple[str, str], tuple[dict, dict]] = {}
        for t in topics:
            for p in t.get("knowledgePoints") or []:
                owner[(subj, p["slug"])] = (t, p)
        for (s, slug), theme in relocations.items():
            if s != subj:
                continue
            if (s, slug) not in owner:
                not_found.append((s, slug))
                continue
            topic, point = owner[(s, slug)]
            if topic["name"] == theme:      # 幂等：已在该主题下
                continue
            theme_slug = _ensure_theme(topics, topic["slug"], theme, subj, taken)
            topic["knowledgePoints"].remove(point)
            next(t for t in topics if t["slug"] == theme_slug)["knowledgePoints"].append(point)
            moved += 1
    if not_found:
        raise ValueError(f"relocate: {len(not_found)} 个 slug 不在知识包中：{not_found[:5]}")
    return moved


def chapter_layer_count(pack: dict) -> int:
    """章层（深度 1）直接挂的知识点总数。门指标同源。"""
    total = 0
    for subject in pack["subjects"]:
        by = {t["slug"]: t for t in subject["topics"]}
        memo: dict[str, int] = {}

        def depth(t: dict) -> int:
            if t["slug"] in memo:
                return memo[t["slug"]]
            par = t.get("parentSlug")
            memo[t["slug"]] = 0 if par not in by else 1 + depth(by[par])
            return memo[t["slug"]]

        for t in subject["topics"]:
            if depth(t) == 1:
                total += len(t.get("knowledgePoints") or [])
    return total


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="章层知识点下移到主题层")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    reloc = load_relocations()

    before = chapter_layer_count(pack)
    # 无损基线：点数与 slug 集合
    before_pts = sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])
    before_slugs = {p["slug"] for s, _t, p in pack_io.iter_points(pack)}
    moved = relocate(pack, reloc)
    after = chapter_layer_count(pack)
    after_pts = sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])
    after_slugs = {p["slug"] for s, _t, p in pack_io.iter_points(pack)}

    print(f"章层挂点：{before} → {after}（移动 {moved}）")
    if after_pts != before_pts or after_slugs != before_slugs:
        print(f"无损校验失败：点 {before_pts}→{after_pts}，slug 差 "
              f"{len(before_slugs ^ after_slugs)}")
        return 1
    print(f"无损校验通过：点数 {after_pts} 守恒，slug 集合不变（仅 parentSlug 变）")

    if args.write:
        pack_io.dump_json(pack, path)
        print(f"→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
