# -*- coding: utf-8 -*-
"""W7 执行器：校验补好的 boundary，写进权威表 `boundary_map.csv`。

## 它消灭的失败

子代理补好的 20 条 boundary 若直接手改成品包，就同时破坏两件事：① 权威源（`boundary_map.csv`
才是知识点的边界来源，`build.py` 读它、`promote` 才写成品目录）；② 可复核性（谁改了什么
无从重放）。本工具是这份裁定的**常驻执行器**：校验通过才写权威表，成品包由 `build` + `promote`
按既有生成链重放。

## 校验（任一不过即整批拒绝，不写任何表）

按裁定表的 `action` 分两类，各自有硬判据：

| action | 判据 |
|---|---|
| 修定界符（正文完整，`$` 错位） | 去掉全部 `$` 后与现行正文**逐字相同**（只许动 `$`）；修好后 `field_text_defects` 为空 |
| 补全尾/收尾 | 新正文以**应用时现行值**算出的 `safe_prefix` 开头（前缀逐字不动，只许往后补）；`field_text_defects` 为空；**补出来的尾巴必须能在该知识点已绑定材料里找到**（去掉空白后做子串匹配）——找不到就整批拒绝（防凭印象补公式） |

「收尾」类（无材料证据）允许新正文等于前缀本身（把没写完的半句去掉）。

## 幂等

重跑：已经是修好值的行跳过（不重写）；写出的 `boundary_map.csv` 排序稳定、逐字节可复现。

用法：
    PYTHONPATH=tools python -m kb_build.apply_boundary_fixes                    # 报告+校验
    PYTHONPATH=tools python -m kb_build.apply_boundary_fixes --write            # 写权威表
    PYTHONPATH=tools python -m kb_build.apply_boundary_fixes --dir <裁定表目录>  # 指定裁定表来源
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
from kb_build import gate, make_boundary_fix_slices as mk  # noqa: E402

TABLES = REPO / "tools/kb_build/tables"
BOUNDARY_MAP = TABLES / "boundary_map.csv"
FIX_GLOB = "boundary_fixes_*.csv"
COLUMNS = ("subject", "slug", "action", "boundary", "evidence")
MAP_COLUMNS = ("subject", "slug", "boundary")


def _norm(text: str) -> str:
    """去空白后比较：材料里的换行/空格与包里的写法不必逐字一致。"""
    return "".join(text.split())


def load_fixes(dirs: list[Path]) -> list[dict]:
    rows: list[dict] = []
    for d in dirs:
        for f in sorted(glob.glob(str(d / FIX_GLOB))):
            with open(f, encoding="utf-8-sig", newline="") as fh:
                got = list(csv.DictReader(fh))
            for r in got:
                r["_file"] = f"{Path(f).parent.name}/{Path(f).name}"
                rows.append(r)
    return rows


def validate(rows: list[dict], pack: dict, materials: dict[str, list[dict]]) -> tuple[list[dict], list[str]]:
    pts: dict[tuple[str, str], dict] = {}
    for subj in pack.get("subjects", []):
        for t in subj.get("topics", []):
            for kp in t.get("knowledgePoints", []):
                pts[(subj.get("subject"), kp.get("slug"))] = kp

    problems: list[str] = []
    out: list[dict] = []
    for r in rows:
        key = ((r.get("subject") or "").strip(), (r.get("slug") or "").strip())
        new = (r.get("boundary") or "").strip()
        where = f"{r.get('_file')} {key[0]}/{key[1]}"
        kp = pts.get(key)
        if kp is None:
            problems.append(f"{where} 知识点不存在")
            continue
        cur = kp.get("boundary") or ""
        mats = materials.get(key[1], [])
        action = (r.get("action") or "").strip()
        if not new:
            problems.append(f"{where} boundary 为空")
            continue
        added = ""
        defects = gate.field_text_defects(new)
        if defects:
            problems.append(f"{where} 修好后仍有残迹 {defects}")
            continue
        if action.startswith("修定界符"):
            if _norm(new.replace("$", "")) != _norm(cur.replace("$", "")):
                problems.append(f"{where} 定界符类改动动了正文（去掉 $ 后不再逐字相同）")
                continue
            if new.count("$") % 2:
                problems.append(f"{where} 定界符类改完 $ 仍不成对")
                continue
        else:
            prefix = mk.safe_prefix(cur)
            if not new.startswith(prefix):
                problems.append(f"{where} 没有逐字保留前缀（前缀必须原样，只许往后补）")
                continue
            added = new[len(prefix):]
            if added.strip():
                haystack = _norm("".join(
                    (m.get("summaryMarkdown") or "") + (m.get("contentMarkdown") or "")
                    + (m.get("boundaryMarkdown") or "") + (m.get("applicabilityMarkdown") or "")
                    for m in mats))
                if not mats:
                    problems.append(f"{where} 无绑定材料却补了内容（{added[:24]!r}）——收尾类不许新增")
                    continue
                if _norm(added) not in haystack:
                    problems.append(f"{where} 补出的尾巴在绑定材料里找不到：{added[:40]!r}")
                    continue
        evidence = (r.get("evidence") or "").strip()
        if added.strip() and not evidence:
            problems.append(f"{where} 缺 evidence（补出来的内容要说清依据出自哪条材料的哪句）")
            continue
        out.append({"subject": key[0], "slug": key[1], "boundary": new,
                    "action": action, "evidence": evidence, "_current": cur, "_file": f"{r.get('_file')}"})
    return out, problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--dir", type=Path, action="append",
                    help="裁定表目录（可重复；默认 tables/ 与 tables/boundary_fix_slices/）")
    args = ap.parse_args(argv)

    dirs = args.dir or [TABLES, TABLES / "boundary_fix_slices"]
    rows = load_fixes(dirs)
    if not rows:
        print(f"没有裁定表（{FIX_GLOB}）：放好后再跑。未写盘。")
        return 0
    fixed, problems = validate(rows, mk.load_pack(), mk.load_materials())
    skipped = [r for r in fixed if r["boundary"] == r["_current"]]
    print(f"裁定表 {len(rows)} 行 → 校验通过 {len(fixed)} 行（其中与现行相同跳过 {len(skipped)} 行）")
    for r in fixed:
        mark = "＝" if r["boundary"] == r["_current"] else "→"
        print(f"   {r['subject']:<10s} {r['slug'][:20]:20s} {mark} 现行 {len(r['_current'])} → 新 {len(r['boundary'])} 字"
              f"  [{r['action'].split('（')[0]}]")
    if problems:
        print(f"\n★ 校验失败 {len(problems)} 条（整批拒绝，不写表）：")
        for p in problems[:12]:
            print("   !", p)
        return 1

    if args.write:
        # 权威表：只放"当前包里有值且这次要覆盖"的行；按 (subject, slug) 排序保证逐字节可复现
        rows_out = [{"subject": r["subject"], "slug": r["slug"], "boundary": r["boundary"]}
                    for r in fixed if r["boundary"] != r["_current"]]
        rows_out.sort(key=lambda r: (r["subject"], r["slug"]))
        TABLES.mkdir(parents=True, exist_ok=True)
        with BOUNDARY_MAP.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(MAP_COLUMNS), lineterminator="\n")
            w.writeheader()
            w.writerows(rows_out)
        print(f"\n→ 已写 {BOUNDARY_MAP}（{len(rows_out)} 行覆盖）。"
              f"接下来：build.py --write（写 staging）→ promote（跑门后落成品）")
    else:
        print("\n（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
