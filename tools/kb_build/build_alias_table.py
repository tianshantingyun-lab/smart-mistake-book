# -*- coding: utf-8 -*-
"""生成 `tables/alias_map.csv`：**别名 = 该节点当前绑定材料的标题**。

为什么需要这张表：`Builder._apply_alias_and_boundary` 的语义是"表里没有就清空"
（`build.py:167` 的 `self.aliases.get(key, [])`），而这张表此前**根本不存在**，
于是每次生成都把 2546 个节点的别名一起抹掉。代价在端到端基准上是可测的：
成品包 Recall@5 0.95 / MRR 0.842，暂存包 0.85 / 0.752——掉的两题
（`已知 sinα=3/5 求 tanα` 丢 `同角三角函数的基本关系`、`物体自由下落5秒末速度`
丢 `自由落体运动`，后者的别名里正好有 `自由下落`）都是别名通道没了造成的。

判据选"当前绑定材料的标题"，而不是照抄成品包那 8514 条：
- **自洽**：门禁 `ghost_aliases` 判的是"既不等于主名、也不来自当前绑定材料的标题"，
  按这个判据生成的表**必然过门**，不需要为它开口子；
- **继承修正**：材料标题是绑定的下游，绑定改正了别名跟着正；
  照抄旧表会把旧绑定的错误一起带回来（实测例子：`动量守恒定律` 的别名里有
  `人船模型`，而 4 条"人船模型"材料曾经也绑在它身上）。

用法： PYTHONPATH=tools python tools/kb_build/build_alias_table.py [--write]

依赖**暂存区**（`build/kb-staging`）而不是成品包：侧车里的标题在
`_repair_material_text` 里会被再修一遍，而别名是在那之后才应用的；
读暂存区拿到的就是修完之后的标题，与下一次生成时门禁看到的一致。
"""

from __future__ import annotations

import argparse
import collections
import csv
from pathlib import Path

from kb_build import pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
OUT = pack_io.REPO / "tools" / "kb_build" / "tables" / "alias_map.csv"


def _surviving_nodes() -> dict[tuple[str, str], str]:
    pack = pack_io.load_json(STAGING / pack_io.PACK_NAME)
    out: dict[tuple[str, str], str] = {}
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                out[(subject["subject"], point["slug"])] = point["name"]
    return out


def build_rows() -> list[tuple[str, str, str]]:
    # 侧车也要读暂存区那份：里面的标题是 `_repair_material_text` 修完之后的，
    # 而别名在流水线里是在那之后才应用的。
    pack_io.use_directory(STAGING)
    nodes = _surviving_nodes()
    rows: list[tuple[str, str, str]] = []
    seen: set[tuple[str, str, str]] = set()
    materials = bindings = 0
    for path in pack_io.sidecar_paths():
        for material in pack_io.load_json(path)["materials"]:
            materials += 1
            # 用**原样**标题（只 strip 首尾）：门禁判的是"别名是否等于某条绑定材料的标题"，
            # 而抽取出来的标题里有全角空格和嵌入换行（如 `动物细胞有丝分裂·不\n同\n点`）。
            # 折叠空白会让这边的写法与门禁看到的不一致，实测造出 7 条幽灵别名。
            title = (material.get("title") or "").strip()
            if not title:
                continue
            for binding in material.get("bindings") or []:
                bindings += 1
                slug = binding["knowledgeNodeId"].split(":")[-1]
                key = (material["subject"], slug)
                name = nodes.get(key)
                if name is None:
                    # 绑到已删/已并节点的材料会被解绑，这类绑定不该再产别名
                    continue
                if title == name:
                    continue  # 等于主名，加了也不增加召回
                row = (key[0], slug, title)
                if row in seen:
                    continue
                seen.add(row)
                rows.append(row)
    rows.sort()
    covered = len({(s, sl) for s, sl, _ in rows})
    print(f"读 {materials} 条材料、{bindings} 条绑定 → 别名 {len(rows)} 条，"
          f"覆盖 {covered}/{len(nodes)} 个节点")
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)
    rows = build_rows()
    if not args.write:
        print("（未写回；加 --write）")
        return 0
    with OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["subject", "slug", "alias"])
        writer.writerows(rows)
    print(f"已写入 {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
