# -*- coding: utf-8 -*-
"""审核子代理交回的草稿：契约 / 绑定 / 重复 / 报告完整性。

**这里刻意不做"内容重合度"打分**——它是先被实现、再被自己的基线否掉的：

  原本想用"草稿正文的 6-gram 有多少出现在该章转录件里"当编造检测。跑基线（主代理自己
  逐条对照过页图的 117 条材料）得到的分布是**中位数 0.03、102/117 低于 0.25**——
  因为转录件写的是 LaTeX（`$\mathrm{CH_3}$`）而材料写的是 Unicode 下标（CH₃），
  归一化跨不过这一层。一个对"已确认正确"的样本都恒低的判据，只会制造噪音，
  不能当门（这正是本仓审计点名的"看起来有门、其实不响"那一类）。

**所以内容审核走人读**：每章派一个**独立复核代理**（adversarial），拿着页图逐条读草稿，
凡"页面上找不到依据"的条目一律报出来；主代理再据此逐条决定收／改／退。
本工具负责的是**机器能判的那部分**：契约、绑定、重复、报告是否逐页交代过。

用法：
  PYTHONPATH=tools python -m kb_build.review_draft scratch/draft_chem_b1_ch1.jsonl
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from kb_build import new_content, pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
JSONL = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"


def known_slugs() -> set[tuple[str, str]]:
    pack = json.loads((STAGING / pack_io.PACK_NAME).read_text(encoding="utf-8"))
    slugs = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    points, _p, _b = new_content.load_new_points()
    return slugs | set(points)


def review(draft: Path, pages: set[str] | None) -> int:
    rows = [json.loads(ln) for ln in draft.read_text(encoding="utf-8").splitlines() if ln.strip()]
    known = known_slugs()
    existing = {json.loads(ln)["slug"] for ln in JSONL.read_text(encoding="utf-8").splitlines()
                if ln.strip()} if JSONL.exists() else set()

    print(f"草稿 {draft.name}：{len(rows)} 条")
    problems: list[str] = []
    seen: set[str] = set()
    by_type: dict[str, int] = {}
    for row in rows:
        slug = row.get("slug", "?")
        by_type[row.get("type", "?")] = by_type.get(row.get("type", "?"), 0) + 1
        try:
            new_content._validate_material(row, 0, known, seen)
        except Exception as exc:
            problems.append(f"契约 {slug}: {exc}")
        if slug in existing:
            problems.append(f"撞 slug（已入库）{slug}")
        node = (row.get("bindings") or [{}])[0].get("knowledgeNodeId", "")
        if node.split(":")[-1] not in {s for _sub, s in known}:
            problems.append(f"绑定悬空 {slug} -> {node}")

    report = draft.with_suffix(".md")
    if not report.exists():
        problems.append(f"缺报告文件 {report.name}")
    else:
        text = report.read_text(encoding="utf-8")
        if pages:
            missing = sorted(p for p in pages if p not in text)
            if missing:
                problems.append("报告未逐页交代：" + "、".join(missing))
        for keyword in ("未覆盖", "校对", "缺失节点"):
            if keyword not in text:
                problems.append(f"报告缺少「{keyword}」一节")

    print(f"  类型分布 {by_type}")
    print(f"  绑定节点 {len({(r.get('bindings') or [{}])[0].get('knowledgeNodeId') for r in rows})} 个")
    if problems:
        print(f"\n问题 {len(problems)} 条：")
        for line in problems:
            print("  " + line)
        return 1
    print("  机器可判项全部通过（契约、绑定、无重复、报告逐页交代）")
    print("  仍需人工/复核代理逐条读页图核对内容——这是唯一能审内容的方式。")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("draft")
    parser.add_argument("--pages", default=None, help="本章页号，逗号分隔（用于核报告是否逐页交代）")
    args = parser.parse_args(argv)
    pages = {p.strip() for p in args.pages.split(",")} if args.pages else None
    return review(Path(args.draft), pages)


if __name__ == "__main__":
    raise SystemExit(main())
