# -*- coding: utf-8 -*-
"""【已弃用】别名表生成已并入 `kb_build.rebuild_aliases`；本模块只做转发。

## 为什么弃用

本模块原先从**暂存区**（`build/kb-staging`）取"每个节点当前绑定材料的标题"当别名。
当时的理由是"材料标题在 `_repair_material_text` 里会被再修一遍，读暂存区与门禁看到的一致"。
但结构修复一直是**绕过 build.py 直接改成品包**的（见 `relocate_chapter_points` 文档），
于是 staging 与成品分叉（实测 2026-09-19：staging 2,168 点 / 成品 2,443 点）——
再用 staging 生成别名表，等于拿一份过期快照覆盖成品的别名。

现在口径与实现收敛到一处：`rebuild_aliases.rebuild()` 读**成品包 + 成品侧车**，
判据为「主名 + 本节点绑定材料标题 + 形状合格（≤24 字、无逗号/句末标点、非题面开头、
虚词结尾、括号配对、无 LaTeX 定界符、过坏名检测）+ 同学科全局互斥」。

## 保留的两条历史经验（已并入新工具）

1. **标题必须与门禁看到的完全一致**：抽取出的标题里有全角空格与嵌入换行，
   折叠空白会让两边写法不一致（实测造出 7 条幽灵别名）。新工具用 `title.strip()`，
   判据取 `strip()` 后的原文。
2. **绑到已删/已并节点的材料不产别名**：新工具直接以当前绑定为准，天然满足。

用法： PYTHONPATH=tools python tools/kb_build/build_alias_table.py [--write]
"""

from __future__ import annotations

import argparse

from kb_build import rebuild_aliases


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="【已弃用】转发到 rebuild_aliases")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    print("注意：build_alias_table 已弃用，本次交由 rebuild_aliases 执行（同一判据、读成品包）。")
    return rebuild_aliases.main(["--write"] if args.write else [])


if __name__ == "__main__":
    raise SystemExit(main())
