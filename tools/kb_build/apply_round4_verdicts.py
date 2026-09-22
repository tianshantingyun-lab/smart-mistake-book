# -*- coding: utf-8 -*-
"""执行 2026-09-19 的两份内容裁定（前置审计 + 边界正文），幂等可重放。

## 它消灭的失败

两份裁定（`prereq_verdicts.csv` 1246 行、`boundary_verdicts.csv` 1254 行）是 47 个
子代理逐条读内容做出的判定，但"判定落盘"与"判定进包"之间如果靠一次性脚本，就会重演
M-05 的失败形态：决定有了、执行者没了，下次共享工作树被并发改动后无从重放。
本工具是这两份裁定的**常驻执行器**：

1. 前置：valid 边保留、inverted 边翻转（P←Q 变 Q←P）、unrelated 边删除；
   翻转目标不存在的边丢弃并计数；按学科做环检查，成环边删除并报告。
   `prereq_map.csv` 由应用后的最终图**整体重建**（门 m7 的口径：包内每条边都必须被声明）。
2. 边界：新边界 = 当前定位串前缀（到第一个 `。`，含）+ 裁定正文。定位串前缀**取应用时
   的现行值**（不取裁定时刻的快照）——两轮之间定位串被对齐过也不冲突。
   应用前逐条复验 `has_verbatim_excerpt` / `is_locator_only` 必须双双通过，
   任何一条不合格即整批拒绝（防裁定表被手改坏）。

## 幂等

重跑时：边界正文已接在该点后面的行跳过、前置集合已一致的点跳过；
`apply` 之后立即按成品复算门口径并报数，重放应显示 0 改动。

## 用法

    PYTHONPATH=tools python -m kb_build.apply_round4_verdicts            # 报告
    PYTHONPATH=tools python -m kb_build.apply_round4_verdicts --write    # 写回
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

from kb_build import pack_io, tables, textfix


def load_verdicts() -> tuple[list[dict], list[dict]]:
    prereq = tables._read(tables.TABLES_DIR / "prereq_verdicts.csv")
    tables._require_columns("prereq_verdicts.csv", prereq,
                            ("subject", "point_slug", "prereq_slug", "verdict"))
    boundary = tables._read(tables.TABLES_DIR / "boundary_verdicts.csv")
    tables._require_columns("boundary_verdicts.csv", boundary, ("subject", "slug", "boundary_body"))
    for r in prereq:
        if r["verdict"].strip() not in ("valid", "inverted", "unrelated"):
            raise ValueError(f"prereq_verdicts: 非法 verdict {r['verdict']!r}")
    return prereq, boundary


def apply(pack: dict, prereq_rows: list[dict], boundary_rows: list[dict]) -> dict:
    pts = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}
    final: dict[tuple[str, str], set[str]] = defaultdict(set)
    inverted_missing = 0
    for r in prereq_rows:
        s, p, q, v = r["subject"].strip(), r["point_slug"].strip(), r["prereq_slug"].strip(), r["verdict"].strip()
        if v == "valid":
            if (s, p) in pts and (s, q) in pts:
                final[(s, p)].add(q)
        elif v == "inverted":
            if (s, q) in pts:
                final[(s, q)].add(p)
            else:
                inverted_missing += 1
    cycle_dropped = 0
    for subj in {k[0] for k in final}:
        g = defaultdict(set)
        for (s, p), qs in final.items():
            if s == subj:
                g[p] |= qs
        color = {}
        def dfs(u):
            nonlocal cycle_dropped
            color[u] = 1
            for v in list(g[u]):
                if color.get(v) == 1:
                    g[u].discard(v)
                    cycle_dropped += 1
                    continue
                if not color.get(v):
                    dfs(v)
            color[u] = 2
        for u in list(g):
            if not color.get(u):
                dfs(u)

    prereq_points = 0
    for (s, slug), pt in pts.items():
        new = sorted(final.get((s, slug), set()))
        if pt.get("prerequisiteSlugs", []) != new:
            pt["prerequisiteSlugs"] = new
            prereq_points += 1

    boundary_applied = boundary_skipped = 0
    for r in boundary_rows:
        key = (r["subject"].strip(), r["slug"].strip())
        pt = pts.get(key)
        if pt is None:
            continue  # 幂等：点已不在包里（后续轮次删/并）
        body = r["boundary_body"].strip()
        cur = pt.get("boundary") or ""
        prefix = cur.split("。", 1)[0] + "。" if "。" in cur else ""
        if cur.endswith(body):
            boundary_skipped += 1
            continue
        full = prefix + body
        if textfix.has_verbatim_excerpt(full) or textfix.is_locator_only(full):
            raise ValueError(f"boundary_verdicts: {key[1][:40]} 的正文复验不合格（摘录/占位判据）")
        pt["boundary"] = full
        boundary_applied += 1

    return {"final_edges": sum(len(v) for v in final.values()),
            "inverted_missing": inverted_missing, "cycle_dropped": cycle_dropped,
            "prereq_points": prereq_points,
            "boundary_applied": boundary_applied, "boundary_skipped": boundary_skipped,
            "final": final}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    prereq_rows, boundary_rows = load_verdicts()
    before_points = len({(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)})
    stats = apply(pack, prereq_rows, boundary_rows)
    after_points = len({(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)})
    if after_points != before_points:
        print("无损校验失败：点数变了")
        return 1

    print(f"前置：最终边 {stats['final_edges']}；改写点 {stats['prereq_points']}；"
          f"反转边目标缺失弃 {stats['inverted_missing']}；成环删边 {stats['cycle_dropped']}")
    print(f"边界：应用 {stats['boundary_applied']}；已在位跳过 {stats['boundary_skipped']}")
    # 门口径复算
    declared = {(s, p, q) for (s, p), qs in stats["final"].items() for q in qs}
    und = sum(1 for s, _t, p in pack_io.iter_points(pack)
              for q in p.get("prerequisiteSlugs") or [] if (s, p["slug"], q) not in declared)
    exc = sum(1 for _s, _t, p in pack_io.iter_points(pack)
              if textfix.has_verbatim_excerpt(p.get("boundary") or ""))
    loc = sum(1 for _s, _t, p in pack_io.iter_points(pack)
              if textfix.is_locator_only(p.get("boundary") or ""))
    print(f"复算门口径：未声明前置 {und} / 摘录边界 {exc} / 占位边界 {loc}")
    if und or exc or loc:
        print("（仍有残余项，不写回——先查裁定表）")
        return 1

    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0
    with (tables.TABLES_DIR / "prereq_map.csv").open("w", encoding="utf-8", newline="") as fh:
        w = __import__("csv").writer(fh)
        w.writerow(["subject", "slug", "prerequisite"])
        for (s, p), qs in sorted(stats["final"].items()):
            for q in sorted(qs):
                w.writerow([s, p, q])
    pack_io.dump_json(pack, path)
    print("→ prereq_map.csv 已重建；已写回 staging 包（内容戳由晋升时刷新）")
    # 幂等复放
    reloaded = pack_io.load_json(path)
    stats2 = apply(reloaded, prereq_rows, boundary_rows)
    if stats2["prereq_points"] or stats2["boundary_applied"]:
        print("幂等校验失败：重放仍要改", stats2)
        return 1
    print("幂等校验通过：重放 0 改动")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
