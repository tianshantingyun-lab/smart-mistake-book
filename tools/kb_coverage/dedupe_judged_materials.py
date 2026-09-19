# -*- coding: utf-8 -*-
"""去掉"同一块被判两次"产生的重复材料（判定表 + 侧车同时收口）。

## 它消灭的失败

切片重切后，同一个块可能被两个代理各判一次。合并工具按出现顺序重排 `midx`（''/b/c/d），
两条行的内容若完全相同，就写成了两条**载荷逐字相同**的材料（slug 不同、指纹相同）——
App 契约明令"材料内容指纹必须唯一"，于是整包被拒（实测 11 组）。

处理：按 `(chunk_id, node_slug, type, title, content)` 判重，保留第一条；
判定表删多余行、侧车删对应材料（slug 由 chunk_id + midx 推出）。幂等、可复核。

用法：
    PYTHONPATH=tools python -m kb_coverage.dedupe_judged_materials --dry-run
    PYTHONPATH=tools python -m kb_coverage.dedupe_judged_materials --write
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

from kb_build import pack_io

REPO = Path(pack_io.REPO).resolve()
JUDGMENTS = REPO / "tools" / "kb_coverage" / "tables" / "material_judgments.csv"
SUBJ3 = {"MATH": "mat", "PHYSICS": "phy", "CHEMISTRY": "che", "BIOLOGY": "bio"}


def subject_of(rel: str) -> str | None:
    for key, subj in (("化学", "CHEMISTRY"), ("生物", "BIOLOGY"), ("物理", "PHYSICS"), ("数学", "MATH")):
        if key in rel:
            return subj
    return None


def slug_of(rel: str, chunk_id: str, midx: str) -> str | None:
    subj = subject_of(rel)
    if subj is None:
        return None
    h, _, idx = chunk_id.partition("-")
    return f"ext-{SUBJ3[subj]}-{h}-{idx}{(midx or '').strip()}"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)

    rows = list(csv.DictReader(JUDGMENTS.open(encoding="utf-8-sig")))
    cols = list(rows[0].keys())
    seen: dict[tuple, str] = {}
    keep: list[dict] = []
    dup_slugs: set[str] = set()
    for r in rows:
        if r["action"] != "MATERIAL":
            keep.append(r)
            continue
        payload = (r["chunk_id"], r["node_slug"], r["type"], r["title"], r["content"])
        if payload in seen:
            slug = slug_of(r["chunk_rel"], r["chunk_id"], r["midx"])
            if slug:
                dup_slugs.add(slug)
            continue
        seen[payload] = r["midx"]
        keep.append(r)
    print(f"判定表 {len(rows)} → {len(keep)}（去重 {len(rows) - len(keep)} 行，涉及材料 slug {len(dup_slugs)} 条）")

    # 侧车：删掉重复 slug
    removed = 0
    changed = {}
    for sp in pack_io.sidecar_paths():
        doc = pack_io.load_json(sp)
        before = len(doc["materials"])
        doc["materials"] = [m for m in doc["materials"] if m["slug"] not in dup_slugs]
        if len(doc["materials"]) != before:
            changed[sp.name] = before - len(doc["materials"])
            removed += before - len(doc["materials"])
            if args.write:
                pack_io.dump_json(doc, sp)
    print(f"侧车删除重复材料 {removed} 条：{changed}")
    if args.write:
        with JUDGMENTS.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=cols)
            w.writeheader()
            w.writerows(keep)
        print("→ 判定表已写回")
    else:
        print("（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
