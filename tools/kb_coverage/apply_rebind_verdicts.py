# -*- coding: utf-8 -*-
"""把错绑裁定的结果落成两张表：改绑表（喂 `rebind_materials`）与已复核表（供审计豁免）。

## 它消灭的失败

`audit_bindings_by_alias` 的判据是启发式（材料标题 == 别的节点名），**判过的 KEEP 每轮还会再报一遍**——
没有豁免表，审计就成了"年年审同一批"的噪声源，真嫌疑会被淹没。

## 校验（宁缺勿错，全部机械可验）

1. `verdict ∈ {REBIND, KEEP, NONE}`；REBIND 必须有 `node_slug` 且该节点在同科存在；
2. 材料必须存在，且**当前绑定的确实是嫌疑表里那一行**（否则说明表已过期 → 冲突不写，报出来）；
3. REBIND 目标不得是现挂节点（空操作）；
4. 幂等：重复跑只增量追加，不重写已有行。

## 产出

- `tools/kb_build/tables/material_rebind.csv` 追加 REBIND 行（再跑 `rebind_materials.py --write` 生效）；
- `tools/kb_build/tables/binding_suspects_reviewed.csv`：全部裁定（含 KEEP/NONE），审计工具读它做豁免。

用法：
    PYTHONPATH=tools python tools/kb_coverage/apply_rebind_verdicts.py          # 报告
    PYTHONPATH=tools python tools/kb_coverage/apply_rebind_verdicts.py --write  # 落表
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import pack_io  # noqa: E402

FIX_ROOT = Path("tools/kb_coverage/rebind_fixes")
SUSPECTS = Path("tools/kb_build/tables/binding_suspects.csv")
REBIND = Path("tools/kb_build/tables/material_rebind.csv")
REVIEWED = Path("tools/kb_build/tables/binding_suspects_reviewed.csv")
VERDICTS = ("REBIND", "KEEP", "NONE")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    nodes: dict[tuple[str, str], str] = {}
    for s in pack_io.load_json(pack_io.pack_path())["subjects"]:
        for t in s["topics"]:
            for kp in (t.get("knowledgePoints") or []):
                nodes[(s["subject"], kp["slug"])] = kp["name"]

    bound: dict[str, tuple[str, str]] = {}
    for sp in pack_io.sidecar_paths():
        for m in pack_io.load_json(sp)["materials"]:
            for b in m.get("bindings") or []:
                bound[m["slug"]] = (m["subject"], b["knowledgeNodeId"].split(":")[-1])
                break

    with SUSPECTS.open(encoding="utf-8", newline="") as fh:
        suspects = {(r["subject"], r["material"]): r for r in csv.DictReader(fh)}

    rows: list[dict] = []
    problems: list[str] = []
    for slice_dir in sorted(FIX_ROOT.glob("slice_*")):
        notes = slice_dir / "notes.csv"
        if not notes.exists():
            problems.append(f"{slice_dir.name}: 缺 notes.csv")
            continue
        with notes.open(encoding="utf-8", newline="") as fh:
            for i, r in enumerate(csv.DictReader(fh), 2):
                subj = (r.get("subject") or "").strip()
                slug = (r.get("material_slug") or "").strip()
                verdict = (r.get("verdict") or "").strip().upper()
                target = (r.get("node_slug") or "").strip()
                evidence = (r.get("evidence") or "").strip()
                if verdict not in VERDICTS:
                    problems.append(f"{slice_dir.name}:{i} verdict 非法：{verdict!r}")
                    continue
                key = (subj, slug)
                if key not in suspects:
                    problems.append(f"{slice_dir.name}:{i} 不在嫌疑表里：{subj}/{slug}")
                    continue
                if slug not in bound:
                    problems.append(f"{slice_dir.name}:{i} 材料不存在或无绑定：{slug}")
                    continue
                cur_subject, cur_node = bound[slug]
                if cur_node != suspects[key]["current_node_slug"]:
                    problems.append(f"{slice_dir.name}:{i} 表已过期（现挂 {cur_node}）：{slug}")
                    continue
                if verdict == "REBIND":
                    if not target:
                        target = suspects[key]["title_matches_node"]
                    if (subj, target) not in nodes:
                        problems.append(f"{slice_dir.name}:{i} 目标节点不存在：{subj}/{target}")
                        continue
                    if target == cur_node:
                        problems.append(f"{slice_dir.name}:{i} 目标是现挂节点（空操作）：{slug}")
                        continue
                rows.append({"subject": subj, "material": slug, "current": cur_node,
                             "verdict": verdict, "target": target, "evidence": evidence,
                             "slice": slice_dir.name})

    by_material = defaultdict(list)
    for r in rows:
        by_material[r["material"]].append(r)
    conflicts = {k: v for k, v in by_material.items() if len(v) > 1}
    print(f"裁定行 {len(rows)}；REBIND {sum(1 for r in rows if r['verdict'] == 'REBIND')}｜"
          f"KEEP {sum(1 for r in rows if r['verdict'] == 'KEEP')}｜"
          f"NONE {sum(1 for r in rows if r['verdict'] == 'NONE')}；"
          f"notes 问题 {len(problems)}；同一材料多行 {len(conflicts)}")
    for p in problems[:8]:
        print("   !", p)
    for k, v in list(conflicts.items())[:5]:
        print("   冲突:", k, [(x["verdict"], x["slice"]) for x in v])
    if problems or conflicts:
        print("\n有阻塞项，本轮不落表")
        return 1

    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0

    # 1) 改绑表：追加（幂等）
    exist = set()
    if REBIND.exists():
        with REBIND.open(encoding="utf-8", newline="") as fh:
            exist = {(r["material_slug"], r["to_node_slug"]) for r in csv.DictReader(fh)}
    added = 0
    with REBIND.open("a", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["material_slug", "from_node_slug",
                                           "to_node_slug", "evidence"],
                           lineterminator="\r\n")
        for r in sorted(rows, key=lambda x: x["material"]):
            if r["verdict"] != "REBIND" or (r["material"], r["target"]) in exist:
                continue
            w.writerow({"material_slug": r["material"], "from_node_slug": r["current"],
                        "to_node_slug": r["target"],
                        "evidence": f"错绑审计（{r['slice']}）：{r['evidence'][:120]}"})
            added += 1
    print(f"→ 改绑表追加 {added} 行")

    # 2) 已复核表：整体重写（它就是"审计结论"本身，按 material 去重）
    REVIEWED.parent.mkdir(parents=True, exist_ok=True)
    merged = {r["material"]: r for r in rows}
    if REVIEWED.exists():
        with REVIEWED.open(encoding="utf-8", newline="") as fh:
            for r in csv.DictReader(fh):
                merged.setdefault(r["material"], r)
    with REVIEWED.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["subject", "material", "current_node_slug",
                                           "verdict", "node_slug", "evidence", "slice"],
                           lineterminator="\r\n")
        w.writeheader()
        for slug in sorted(merged):
            r = merged[slug]
            w.writerow({"subject": r["subject"], "material": slug,
                        "current_node_slug": r["current"], "verdict": r["verdict"],
                        "node_slug": r["target"], "evidence": r["evidence"],
                        "slice": r["slice"]})
    print(f"→ 已复核表已写 {REVIEWED}（{len(merged)} 行）")
    print("下一步：PYTHONPATH=tools python tools/kb_build/rebind_materials.py --write")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
