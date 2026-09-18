# -*- coding: utf-8 -*-
"""在成品包新增知识点节点（生物缺节点时用它，配合 rebind_materials 改绑）。

## 它消灭的失败

生物只有 338 个节点，肥节点上 987 条材料不命中任何节点——因为对应知识点根本没建。
`rebind` 只能绑到**已存在**的节点；建节点得靠本模块。两者配合完成绑定修复：
先 create 出缺失的知识点，再 rebind 把材料指过去。

## 约束
- `slug` 科内唯一、`parent_topic_slug` 必须真实存在
- 幂等：slug 已存在则跳过
- 无损：只增点，不删不改既有节点
- 新点 `kind` 用枚举值（CONCEPT/PROCEDURE/...），`boundary` 给定位串

## 用法
    PYTHONPATH=tools python -m kb_build.create_points            # 报告
    PYTHONPATH=tools python -m kb_build.create_points --write    # 写回
"""

from __future__ import annotations

import argparse
from pathlib import Path

from kb_build import pack_io, tables

TABLE = "new_points_manual.csv"
COLUMNS = ("subject", "slug", "name", "kind", "parent_topic_slug", "boundary", "source_locator")
# 必须与 App 侧的知识点类型枚举逐字一致：
# core/model/src/main/kotlin/com/tingyun/smartmistakebook/core/model/ProblemCatalog.kt 的
# KnowledgeNodeKind。MISCONCEPTION_GUIDE 曾经在这里出现过，但它是**材料类型**
# （KnowledgeTeachingMaterialType）的取值，不是节点类型 —— 越界的节点会让内置包在
# 解析时直接崩（2026-09-19 由 BundledTeachingMaterialsContractTest 抓住）。
KINDS = {"CONCEPT", "PROCEDURE", "REASONING", "REPRESENTATION", "EXPERIMENT", "EXPRESSION"}


def load_new_points(path: Path | None = None) -> list[dict]:
    path = path or (tables.TABLES_DIR / TABLE)
    if not path.exists():
        return []
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    for r in rows:
        if r["kind"].strip() not in KINDS:
            raise ValueError(f"{TABLE}: 非法 kind {r['kind']!r}")
    return rows


def create_points(pack: dict, rows: list[dict]) -> dict:
    stats = {"created": 0, "skipped": 0, "errors": []}
    for subject in pack["subjects"]:
        subj = subject["subject"]
        myrows = [r for r in rows if r["subject"].strip() == subj]
        if not myrows:
            continue
        topics = {t["slug"]: t for t in subject["topics"]}
        existing = {k["slug"] for t in subject["topics"] for k in t.get("knowledgePoints") or []}
        for r in myrows:
            slug, name = r["slug"].strip(), r["name"].strip()
            parent = r["parent_topic_slug"].strip()
            if slug in existing:
                stats["skipped"] += 1
                continue
            if parent not in topics:
                stats["errors"].append(f"[{subj}] 父主题 {parent} 不存在")
                continue
            topics[parent]["knowledgePoints"].append({
                "slug": slug, "name": name, "aliases": [], "kind": r["kind"].strip(),
                "boundary": r["boundary"].strip() or "定位：待补章表。",
                # sourceLocator 决定章节门（source_unit）的归属，必须是已被章表覆盖的
                # 来源单元（如 `人教版高中教材（2019）`），不能用 boundary 定位串顶替。
                "sourceLocator": (r.get("source_locator") or "").strip() or "人教版高中教材（2019）",
                "prerequisiteSlugs": [],
            })
            existing.add(slug)
            stats["created"] += 1
    return stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="新增知识点节点")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    rows = load_new_points()
    stats = create_points(pack, rows)
    print(f"新增 {stats['created']}；幂等跳过 {stats['skipped']}")
    if stats["errors"]:
        print(f"错误 {len(stats['errors'])}：")
        for e in stats["errors"][:10]:
            print(f"   {e}")
        return 1
    if args.write:
        pack_io.dump_json(pack, path)
        print(f"→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
