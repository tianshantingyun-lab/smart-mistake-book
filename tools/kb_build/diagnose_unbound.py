# -*- coding: utf-8 -*-
"""诊断：无绑定教学材料的分层与归属候选。

1350 条无绑定材料不是一类东西，混在一起看会得出错的处置：

- **被解除**：绑定原本存在，指向的节点被 `node_actions` 纯删除（无合并目标）后
  绑定被移除。这一层是**我们自己的流程**造成的孤儿，原目标是明确的。
- **原生无绑定**：输入包里的 bindings 本来就是空的，从未指向任何节点。

两类要分开处置：前者要判断"删节点删对没有"，后者要判断"这条材料该归谁"。

  用法： PYTHONPATH=tools python tools/kb_build/diagnose_unbound.py
"""

from __future__ import annotations

import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io, tables
from kb_build.build import Builder

OUT = pack_io.REPO / "build" / "kb-staging"


def main() -> int:
    builder = Builder()
    merge_map, deleted = builder._apply_node_actions()
    actions = tables.load_node_actions()

    released: list[dict] = []
    native: list[dict] = []
    for path, doc in builder.sidecars:
        for material in doc["materials"]:
            bindings = material.get("bindings") or []
            if not bindings:
                native.append({"sidecar": path.name, "subject": material["subject"],
                               "slug": material["slug"], "title": material["title"]})
                continue
            for binding in bindings:
                parts = binding["knowledgeNodeId"].split(":")
                key = (parts[-3].upper(), parts[-1])
                if key in deleted:
                    released.append({
                        "sidecar": path.name, "subject": material["subject"],
                        "slug": material["slug"], "title": material["title"],
                        "was_bound_to": key[1],
                        "delete_reason": actions.get(key, {}).get("reason", ""),
                    })

    print(f"无绑定材料 {len(native) + len(released)} 条 = 被解除 {len(released)} + 原生 {len(native)}")
    print()
    print("== 被解除：按科目 ==")
    for k, v in Counter(r["subject"] for r in released).most_common():
        print(f"  {k}: {v}")
    print()
    print("== 被解除：原目标节点的删除原因（前 15 类）==")
    reasons = Counter()
    for r in released:
        reason = r["delete_reason"]
        reasons[reason.split(" -> ")[0][:40] or "(无理由)"] += 1
    for k, v in reasons.most_common(15):
        print(f"  {v:>4}  {k}")
    print()
    print("== 原生无绑定：按科目 ==")
    for k, v in Counter(r["subject"] for r in native).most_common():
        print(f"  {k}: {v}")

    def dump(name: str, rows: list[dict]) -> None:
        if not rows:
            return
        path = OUT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        print(f"已写 {path}（{len(rows)} 行）")

    dump("unbound_released.csv", released)
    dump("unbound_native.csv", native)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
