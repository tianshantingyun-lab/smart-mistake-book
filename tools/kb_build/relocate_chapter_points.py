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
TOPIC_RENAME = "topic_rename.csv"
TABLES = Path(__file__).resolve().parent / "tables"
COLUMNS = ("subject", "slug", "theme", "to_topic_slug")
RENAME_COLUMNS = ("subject", "slug", "new_name")


def load_relocations(path: Path | None = None) -> dict[tuple[str, str], tuple[str, str]]:
    """(subject, slug) -> (theme, to_topic_slug)。

    `to_topic_slug` 为空＝章内下移（在同章下建/找 theme 主题）；
    非空＝跨章移动（在 to_topic_slug 指向的**章**下建/找 theme 主题）。
    表必须引用真实存在的点，否则报错（防 name/slug 混用）。
    """
    from kb_build import tables
    path = path or (TABLES / RELOCATION)
    if not path.exists():
        return {}
    rows = tables._read(path)
    tables._require_columns(RELOCATION, rows, COLUMNS)
    pack = pack_io.load_json(pack_io.pack_path())
    known = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    out: dict[tuple[str, str], tuple[str, str]] = {}
    for r in rows:
        key = (r["subject"].strip(), r["slug"].strip())
        if key not in known:
            raise ValueError(f"{RELOCATION}: {key} 不在知识包中（可能写成了 name）")
        if not r["theme"].strip():
            raise ValueError(f"{RELOCATION}: {key} 缺 theme")
        out[key] = (r["theme"].strip(), r.get("to_topic_slug", "").strip())
    return out


def load_topic_renames(path: Path | None = None) -> dict[tuple[str, str], str]:
    """topic 改名表（只改 name，slug 不动——parentSlug 引用它）。"""
    from kb_build import tables
    path = path or (TABLES / TOPIC_RENAME)
    if not path.exists():
        return {}
    rows = tables._read(path)
    tables._require_columns(TOPIC_RENAME, rows, RENAME_COLUMNS)
    return {(r["subject"].strip(), r["slug"].strip()): r["new_name"].strip() for r in rows}


def rename_topics(pack: dict, renames: dict[tuple[str, str], str]) -> int:
    """只改 topic 的 name。返回改动数。幂等。"""
    changed = 0
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            new = renames.get((subject["subject"], topic["slug"]))
            if new and topic["name"] != new:
                topic["name"] = new
                changed += 1
    return changed


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


def _theme_holds_point(topics: list[dict], base_slug: str, theme: str, slug: str) -> bool:
    """base_slug 下名为 theme 的主题是否已包含 slug 这个点——是则该行已是完成态。"""
    for t in topics:
        if t.get("parentSlug") == base_slug and t["name"] == theme:
            if any(p["slug"] == slug for p in t.get("knowledgePoints") or []):
                return True
    return False


def relocate(pack: dict, relocations: dict[tuple[str, str], tuple[str, str]]) -> int:
    """把点下移到主题层（章内）或跨章移动到指定章的主题下。返回移动条数。幂等。

    - `to_topic_slug` 为空：在同章下建/找 theme 主题（章内下移）。
    - 非空：在 to_topic_slug（必须真实存在）下建/找 theme 主题（跨章移动）。
    """
    moved = 0
    not_found: list[tuple[str, str]] = []
    for subject in pack["subjects"]:
        subj = subject["subject"]
        topics = subject["topics"]
        taken = {t["slug"] for t in topics}
        by_slug = {t["slug"]: t for t in topics}
        # (subject, slug) -> (所在 topic, 点对象)
        owner: dict[tuple[str, str], tuple[dict, dict]] = {}
        for t in topics:
            for p in t.get("knowledgePoints") or []:
                owner[(subj, p["slug"])] = (t, p)
        for (s, slug), (theme, to_topic) in relocations.items():
            if s != subj:
                continue
            if (s, slug) not in owner:
                not_found.append((s, slug))
                continue
            topic, point = owner[(s, slug)]
            if to_topic:
                base_slug = to_topic
            else:
                # 章内模式：点若已在某主题下（有父），以父为章——否则第二次跑会把
                # base_slug 解析成主题自己的 slug，幂等判定失效。
                base_slug = topic.get("parentSlug") or topic["slug"]
            if base_slug not in by_slug:
                raise ValueError(f"relocate: 目标 topic {base_slug} 不存在（[{subj}] {slug}）")
            if _theme_holds_point(topics, base_slug, theme, slug):
                continue              # 幂等：目标主题已含此点
            theme_slug = _ensure_theme(topics, base_slug, theme, subj, taken)
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
    renames = load_topic_renames()
    renamed = rename_topics(pack, renames)
    after = chapter_layer_count(pack)
    after_pts = sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])
    after_slugs = {p["slug"] for s, _t, p in pack_io.iter_points(pack)}

    print(f"章层挂点：{before} → {after}（移动 {moved}；topic 改名 {renamed}）")
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
