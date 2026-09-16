# -*- coding: utf-8 -*-
"""按权威表删除知识点。

## 它消灭的失败

章层里残留一批**题干/残缺句**命名的"知识点"（`下列说法正确的是`、`按要求填空`、
`mol HCHO～4 mol Cu(OH)2～2 mol Cu2O`）——它们不是知识点，是抽取事故。留着会：
- 污染检索（名称进 `knowledge_search_feature`，把无关题面召回过来）
- 污染掌握度（学生无法对一个"下列说法正确的是"记掌握度）
- 让"覆盖/粒度"统计失真

## 安全边界（本脚本只删"可安全删"的）

删除一个点会 orphan 它的材料、断它的前置引用。所以本表**只列**满足条件的点：
1. 无材料绑定（orphan 风险＝0）
2. 经语义判定确为纯题干/残渣（不是"名字写成题干的真知识点"——那些该 rename/重绑，不在本表）
3. 若它被别的点当前置引用——**那本身就是错误绑定**（题干不该是任何知识点的前置），
   删除时清掉这些引用是**修正**而非破坏；脚本会显式报告清了几处。

`relocate_chapter_points` 处理"归位"，`delete_points` 处理"删除"，`rename`（另表）
处理"坏名知识点"，材料重绑处理"题目节点上的真材料"——四者不混。

## 无损

只删表里列的 slug；其余点数守恒、slug 集合除被删外不变。删点同时清理指向被删点的
悬挂 prerequisite（双保险，正常应为 0）。

## 用法
    PYTHONPATH=tools python -m kb_build.delete_points            # 报告
    PYTHONPATH=tools python -m kb_build.delete_points --write    # 写回
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from kb_build import pack_io, tables

TABLE = "point_delete.csv"
COLUMNS = ("subject", "slug", "reason")


def load_deletes(path: Path | None = None) -> dict[tuple[str, str], str]:
    path = path or (tables.TABLES_DIR / TABLE)
    if not path.exists():
        return {}
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    return {(r["subject"].strip(), r["slug"].strip()): r["reason"].strip() for r in rows}


def _point_count(pack: dict) -> int:
    return sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])


def delete(pack: dict, to_delete: dict[tuple[str, str], str]) -> tuple[int, int]:
    """返回 (删除点数, 清理的悬挂前置引用数)。prerequisiteSlugs 保持 list（JSON 不可序列化 set）。"""
    removed = dangling = 0
    for subject in pack["subjects"]:
        subj = subject["subject"]
        topics = subject["topics"]
        valid = {p["slug"] for t in topics for p in t.get("knowledgePoints") or []}
        for t in topics:
            kept = []
            for p in t.get("knowledgePoints") or []:
                if (subj, p["slug"]) in to_delete:
                    removed += 1
                else:
                    kept.append(p)
            t["knowledgePoints"] = kept
        valid = {p["slug"] for t in topics for p in t.get("knowledgePoints") or []}
        for t in topics:
            for p in t.get("knowledgePoints") or []:
                kept_prereq = [q for q in (p.get("prerequisiteSlugs") or []) if q in valid]
                dangling += len(p.get("prerequisiteSlugs") or []) - len(kept_prereq)
                p["prerequisiteSlugs"] = kept_prereq
    return removed, dangling


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="按表删除纯题干/残缺知识点")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    deletes = load_deletes()

    before = _point_count(pack)
    removed, dangling = delete(pack, deletes)
    after = _point_count(pack)
    already = len(deletes) - removed
    print(f"删除 {removed} 个；已在包外（幂等跳过）{already} 个；清理错误前置引用 {dangling} 个")
    print(f"点数 {before} → {after}")
    if dangling:
        # 被删点若是题干残渣，别的点把它当前置本身就是错误绑定——清掉是修正，不是破坏。
        # 但这是**有副作用**的清理，必须显式报告，让调用者知道动了前置图。
        print(f"注意：清理了 {dangling} 处指向被删点的前置引用（被删点本不该当前置，属错误绑定修正）")

    if args.write:
        pack_io.dump_json(pack, path)
        print(f"→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
