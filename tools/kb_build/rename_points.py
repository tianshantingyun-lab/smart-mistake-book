# -*- coding: utf-8 -*-
"""按权威表给知识点改名（只改 name，不动 slug）。

## 它消灭的失败

一批知识点是被"题目的题干"命名了（`写出下列原子核外电子排布的表示方法。`、
`丙酮()是最简单的酮，常用作有机溶剂。关于丙酮的说法正确的是`），或名字残留了
难度星号 / 用了"基本概念"这种无主语的泛名。这样的名字：
- 污染检索（`knowledge_search_feature` 从 name 派生，题干词会把无关题面召回过来）
- 学生端掌握度列表显示"下列说法正确的是(　　)"——不可读

## 只改 name，不动 slug

slug 是身份：材料绑定（`knowledgeNodeId` 含 slug）、前置引用、检索稳定码都引用它。
改 name 不碰 slug → 材料、绑定、前置全部不受影响，检索特征在运行时按新 name 重建。
这是"改名"与"换身份"的关键区别：本模块只做前者。

## 边界
- 表里只列**确实是个知识点、只是名字坏了**的点。名字坏了但其实该删/该重绑材料的，
  不在本表（走 `delete_points` / 材料重绑）。
- 幂等：name 已是目标则 no-op。无损：点数、slug 集合不变。

## 用法
    PYTHONPATH=tools python -m kb_build.rename_points            # 报告
    PYTHONPATH=tools python -m kb_build.rename_points --write    # 写回
"""

from __future__ import annotations

import argparse
from pathlib import Path

from kb_build import pack_io, tables

TABLE = "point_rename.csv"
COLUMNS = ("subject", "slug", "new_name", "reason")


def load_renames(path: Path | None = None) -> dict[tuple[str, str], str]:
    path = path or (tables.TABLES_DIR / TABLE)
    if not path.exists():
        return {}
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    out: dict[tuple[str, str], str] = {}
    for r in rows:
        key = (r["subject"].strip(), r["slug"].strip())
        if not r["new_name"].strip():
            raise ValueError(f"{TABLE}: {key} 缺 new_name")
        out[key] = r["new_name"].strip()
    return out


def rename(pack: dict, renames: dict[tuple[str, str], str]) -> int:
    """只改 name。返回实际改动条数。幂等。"""
    changed = 0
    for subject in pack["subjects"]:
        subj = subject["subject"]
        for t in subject["topics"]:
            for p in t.get("knowledgePoints") or []:
                new = renames.get((subj, p["slug"]))
                if new and p["name"] != new:
                    p["name"] = new
                    changed += 1
    return changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="按表给坏名知识点改名（只改 name）")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    renames = load_renames()

    changed = rename(pack, renames)
    print(f"改名 {changed} 个（幂等跳过 {len(renames) - changed}）；slug 全部不变")
    if args.write:
        pack_io.dump_json(pack, path)
        print(f"→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
