# -*- coding: utf-8 -*-
"""审计裁定表的执行器：校验 `transcript_audits.csv` 并生成"重转工作清单"。

## 它消灭的失败

S4 的换人审计（847 页）会产出成百上千条逐页判决；若判决只躺在子代理的产物里，
"判了但没执行"就重演 M-05 的形态（有决定、没执行者）。本工具把判决**收敛成两张表**：

- `tables/transcript_audits.csv`（裁定表，子代理/人工写）：
  `subject,pages,verdict,items_min,items_numbered,evidence`
  - `pages` 支持 `"15"` / `"15-28"` / `"15,17"` 三种写法；
  - `verdict ∈ {ACCEPT, RETRANSCRIBE, UNCERTAIN}`（与仓内既有三值约定一致；
    规划里写的 `SPLIT` 在本层不需要——重转是按**整页**做的，页内切块属于转写协议内部的事）；
  - `items_min` = P1 清点出的「最小式/条」数（闸门分母）；`items_numbered` = 版面编号条数。
- `tables/retranscribe_queue.csv`（生成物）：`subject,pdf_rel,pages,reason`
  —— 这才是下一轮派工的输入。

## 校验（任一不过即整批拒绝，不写任何表）

1. `subject` / `verdict` 合法；`pages` 能解析且落在该书 `1..total_pages` 内；
2. **同一页不得有两条判决**（冲突即拒绝——两份判决打架时宁可停下）；
3. `RETRANSCRIBE` 必须给 `evidence`（要说清凭什么判重转）；
4. `items_min` / `items_numbered` 若非空必须是数字。

## 幂等

同一份裁定表重跑：队列逐字节相同（排序后写出）；不修改裁定表本身。

用法：
    PYTHONPATH=tools python -m kb_coverage.apply_transcript_audits            # 报告+校验
    PYTHONPATH=tools python -m kb_coverage.apply_transcript_audits --write     # 写队列
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "build/2027-53-pages/manifest_slim.json"
AUDITS = REPO / "tools/kb_coverage/tables/transcript_audits.csv"
QUEUE = REPO / "tools/kb_coverage/tables/retranscribe_queue.csv"

VERDICTS = ("ACCEPT", "RETRANSCRIBE", "UNCERTAIN")
SPAN_RE = re.compile(r"(\d+)[-_](\d+)")


def expand(pages: str) -> list[int]:
    out: list[int] = []
    for part in [p.strip() for p in (pages or "").split(",") if p.strip()]:
        m = SPAN_RE.fullmatch(part)
        if m:
            out.extend(range(int(m.group(1)), int(m.group(2)) + 1))
        elif part.isdigit():
            out.append(int(part))
        else:
            raise ValueError(f"无法解析的 pages 片段：{part!r}")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)

    if not AUDITS.exists():
        print(f"没有裁定表：{AUDITS}（S4 审计后才有；本工具此时只报告）")
        print("（未写盘）")
        return 0

    books = {s["subject"]: s for s in json.loads(MANIFEST.read_text(encoding="utf-8"))["subjects"]}
    with AUDITS.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    problems: list[str] = []
    seen: dict[tuple[str, int], str] = {}
    queue: list[dict] = []
    for i, r in enumerate(rows, 2):
        subj = (r.get("subject") or "").strip()
        verdict = (r.get("verdict") or "").strip().upper()
        pages_raw = (r.get("pages") or "").strip()
        evidence = (r.get("evidence") or "").strip()
        if subj not in books:
            problems.append(f"L{i} 未知学科 {subj!r}")
            continue
        if verdict not in VERDICTS:
            problems.append(f"L{i} 非法 verdict {verdict!r}")
            continue
        if verdict == "RETRANSCRIBE" and not evidence:
            problems.append(f"L{i} RETRANSCRIBE 缺 evidence")
            continue
        total = int(books[subj]["total_pages"])
        try:
            pages = expand(pages_raw)
        except ValueError as e:
            problems.append(f"L{i} {e}")
            continue
        if not pages:
            problems.append(f"L{i} pages 为空")
            continue
        out_of_range = [p for p in pages if not (1 <= p <= total)]
        if out_of_range:
            problems.append(f"L{i} pages 越界（该书 {total} 页）：{out_of_range[:5]}")
            continue
        for p in pages:
            if (subj, p) in seen:
                problems.append(f"L{i} 第 {p} 页与 L{seen[(subj, p)]} 冲突（同一页两条判决）")
            seen[(subj, p)] = str(i)
        for key in ("items_min", "items_numbered"):
            v = (r.get(key) or "").strip()
            if v and not v.isdigit():
                problems.append(f"L{i} {key} 非数字：{v!r}")
        if verdict == "RETRANSCRIBE":
            queue.append({"subject": subj, "pdf_rel": books[subj]["pdf_rel"],
                          "pages": pages_raw, "reason": evidence[:120]})

    n_pages = len(seen)
    print(f"裁定表 {len(rows)} 行 → 覆盖 {n_pages} 页；判决分布："
          f"{dict(Counter((r.get('verdict') or '').strip().upper() for r in rows))}")
    print(f"重转队列 {len(queue)} 条")
    if problems:
        print(f"\n校验失败 {len(problems)} 条（整批拒绝，不写表）：")
        for p in problems[:10]:
            print("   !", p)
        return 1

    if args.write:
        queue.sort(key=lambda q: (q["subject"], q["pages"]))
        QUEUE.parent.mkdir(parents=True, exist_ok=True)
        with QUEUE.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=["subject", "pdf_rel", "pages", "reason"],
                               lineterminator="\n")
            w.writeheader()
            w.writerows(queue)
        print(f"→ 已写 {QUEUE}（{len(queue)} 条）")
    else:
        print("（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
