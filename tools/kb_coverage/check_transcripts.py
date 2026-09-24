# -*- coding: utf-8 -*-
"""转写质量门：把"这批转写能不能算存好了"变成可复跑的检查。

## 它消灭的失败

S3/S4 跑完后，"转写好了没有"此前的答案是"文件都在"——但那回答不了：
① 有没有整页缺失（22 个整片缺失时文件也"都在"）；
② 页内公式的 `$` 有没有被吃成奇数（上一次事故的形态）；
③ 有没有 `\\1` / `/usr/bin/bash` / PID 那类残迹（另一次事故的形态）；
④ 清点说该有 N 条、转写里却数不出编号（漏块的机器可判信号）；
⑤ `【不确定：…】` 有没有被下游悄悄删掉。

## 六条判据（判据来源唯一）

闸门与计数判据**复用 `transcription_ledger`**（`compute_gate` / `signals`），残迹判据复用
`kb_build.gate.field_text_defects`——同一件事只有一份实现，避免门与工具各写一套后漂移。

1. 覆盖：账本页数 == 四科总页数，且 (subject,page) 不重复；
2. 归属：done 页的 span 能解析且 page 落在 span 内；
3. 分隔符：每页 `$` 计数为偶数；
4. 残迹：`field_text_defects` 为空（非法转义 / 美元不成对 / shell 展开 / PID 重复）；
5. 计数：已审计页 `items_min` 非空时，条数闸不过即报（判据同 `compute_gate`）；
6. 留白：`【不确定：…】` 计数与账本一致（等于"没被删"）。

用法：
    PYTHONPATH=tools python -m kb_coverage.check_transcripts          # 报告（0 问题退出 0）
    PYTHONPATH=tools python -m kb_coverage.check_transcripts --only-fail   # 只列问题页
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from kb_build import gate  # noqa: E402
from kb_coverage import transcription_ledger as tl  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--only-fail", action="store_true", help="只列问题页")
    args = ap.parse_args(argv)

    rows = tl.build_rows()
    tr = tl.load_transcripts()
    problems: list[str] = []

    # 1) 覆盖与唯一性
    expect = sum(int(s["total_pages"]) for s in tl.load_manifest())
    keys = [(r["subject"], r["page"]) for r in rows]
    if len(rows) != expect:
        problems.append(f"账本页数 {len(rows)} != 应有 {expect}")
    dup = [k for k, c in Counter(keys).items() if c > 1]
    if dup:
        problems.append(f"账本重复页 {dup[:5]}")

    by_check = Counter()
    for r in rows:
        if r["status"] != "done":
            continue
        text = tr[(r["subject"], r["page"])]["text"]
        tag = f"{r['subject']} p{r['page']}"
        # 2) span 归属
        span = r["span"].replace("_", "-")
        parts = span.split("-")
        if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
            if not (int(parts[0]) <= r["page"] <= int(parts[1])):
                problems.append(f"{tag} 落在 span {r['span']} 之外")
                by_check["span"] += 1
        else:
            problems.append(f"{tag} span 不可解析：{r['span']!r}")
            by_check["span"] += 1
        # 3+4) 分隔符与残迹（同一个判据函数）
        defects = gate.field_text_defects(text)
        if defects:
            problems.append(f"{tag} 文本残迹：{defects}")
            by_check["defects"] += 1
        # 5) 条数闸（复用账本判据）
        if tl.compute_gate(r) == "fail":
            problems.append(f"{tag} 闸门 fail（截断={r['truncated'] or '否'} "
                            f"字数={r['chars']} items_min={r['items_min'] or '-'} "
                            f"编号={r['numbered']}）")
            by_check["gate"] += 1
        # 6) 留白计数一致
        if text.count("【不确定") != r["uncertainties"]:
            problems.append(f"{tag} 【不确定】计数不一致（账本 {r['uncertainties']}）")
            by_check["uncertain"] += 1
        # 7) 空文本
        if not text.strip():
            problems.append(f"{tag} text 为空")
            by_check["empty"] += 1

    done = sum(1 for r in rows if r["status"] == "done")
    print(f"页数 {len(rows)}｜已转写 {done}｜缺失 {len(rows) - done}")
    print("档位：", dict(Counter(r["plan"] for r in rows)))
    print("闸门：", dict(Counter(r["gate"] for r in rows)))
    if problems:
        print(f"\n★ 问题 {len(problems)} 条（按类：{dict(by_check)}）")
        for p in problems[: (999 if args.only_fail else 12)]:
            print("   !", p)
        return 1
    print("\n全部通过：覆盖完整、分隔符成对、无残迹、条数闸过、留白标记未丢。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
