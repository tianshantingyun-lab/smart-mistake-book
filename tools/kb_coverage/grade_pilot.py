# -*- coding: utf-8 -*-
"""S2 试点的机械验收：把 pilot 产物按**与成品库同一套判据**过闸。

## 它消灭的失败

试点跑完时，"这 5 页行不行"若靠人眼看几段文字回答，就答不出：编号条数够不够、
`$` 有没有被吃掉、有没有 `\\1` 那类残迹、清点数与写出的条数差多少。这些**都是机械可判的**，
只是需要有人把判据接上。本工具就是那根接线：判据不另写一套，直接复用
`transcription_ledger.compute_gate` 与 `kb_build.gate.field_text_defects`
（门与账本同源，试点与成品同源）。

## pilot 产物约定

    knowledge-production/2027-53-pilot/<subject>/pNNNN.jsonl     每行 {page, heading, text}
    knowledge-production/2027-53-pilot/<subject>/pNNNN.counts.json  {"items_min":N,"items_numbered":M,...}

## 判据（任一不过即该页不过闸）

1. `compute_gate == pass`（截断 / 过短 / 条数闸 / 文本残迹）
2. `field_text_defects` 为空（非法转义 / `$` 不成对 / shell 展开 / PID 重复）
3. `items_min` 必须给出（试点要求清点先行；缺失即算不过——没清点就没法判漏没漏）

用法：
    python tools/kb_coverage/grade_pilot.py                     # 报告
    python tools/kb_coverage/grade_pilot.py --json <out.json>    # 另落一份机器可读结果
    退出码 0 = 全部过闸；1 = 有页不过闸；2 = 目录不存在
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
from kb_build import gate  # noqa: E402
from kb_coverage import transcription_ledger as tl  # noqa: E402

PILOT = REPO / "knowledge-production/2027-53-pilot"


def load_page(jsonl: Path) -> tuple[str, int]:
    """返回 (整页文本, 记录条数)。容忍行尾逗号（代理常见写法）。"""
    parts: list[str] = []
    n = 0
    for raw in jsonl.read_text(encoding="utf-8").splitlines():
        s = raw.strip()
        if not s:
            continue
        if s.endswith(","):
            s = s[:-1].rstrip()
        try:
            rec = json.loads(s)
        except json.JSONDecodeError:
            continue
        if (rec.get("text") or "").strip():
            parts.append(rec["text"])
            n += 1
    return "\n".join(parts), n


def grade_one(root: Path, subject: str, page: int) -> dict | None:
    """只判一页（给逐页循环当快闸用）；该页产物不存在时返回 None。"""
    if not (root / subject / f"p{page:04d}.jsonl").exists():
        return None
    got = grade_dir(root, only=(subject, page))
    return got["pages"][0] if got["pages"] else None


def grade_dir(root: Path, only: tuple[str, int] | None = None) -> dict:
    pages = []
    for jsonl in sorted(root.glob("*/p*.jsonl")):
        subject = jsonl.parent.name
        page = int(jsonl.stem.lstrip("p"))
        if only and (subject, page) != only:
            continue
        text, records = load_page(jsonl)
        counts_file = jsonl.with_suffix(".counts.json")
        counts: dict = {}
        counts_err = ""
        if counts_file.exists():
            try:
                counts = json.loads(counts_file.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError) as e:
                # 坏产物不许把门崩掉：如实记成这一页的问题（清点数读不到 → 按缺清点数判 fail，
                # 交给定点修重写）。实测 2026-09-25：一个批代理在 notes 里写了未转义换行，
                # 4 页 counts 全坏，门在 json.loads 上直接抛错、整轮判不了。
                counts_err = f"counts 文件解析失败：{str(e)[:80]}"
        sig = tl.signals(text)
        row = {"status": "done", "verdict": "", "truncated": "",
               "chars": len(text), "numbered": sig["numbered"],
               "formulas": sig["formulas"], "figs": sig["figs"],
               "items_min": str(counts.get("items_min", ""))}
        defects = gate.field_text_defects(text)
        problems = []
        if counts_err:
            problems.append(counts_err)
        if not row["items_min"]:
            problems.append("缺清点数 items_min（清点先行，未清点即不算过闸）")
        elif tl.compute_gate(row, text) != "pass":
            problems.append(f"闸门不过（清点 {row['items_min']} / 编号 {sig['numbered']}"
                            f" / 字数 {len(text)}）")
        if defects:
            problems.append(f"文本残迹 {defects}")
        pages.append({
            "subject": subject, "page": page, "records": records, "chars": len(text),
            "items_min": counts.get("items_min"), "items_numbered": counts.get("items_numbered"),
            "numbered_in_text": sig["numbered"], "formulas": sig["formulas"],
            "figs": sig["figs"], "uncertainties": sig["uncertainties"],
            "gate": "fail" if problems else "pass", "problems": problems,
        })
    return {"dir": str(root), "pages": pages,
            "passed": sum(1 for p in pages if p["gate"] == "pass"),
            "failed": sum(1 for p in pages if p["gate"] != "pass")}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", type=Path, default=PILOT)
    ap.add_argument("--page", help="只判一页，格式 学科:页码（如 CHEMISTRY:2）")
    ap.add_argument("--json", type=Path)
    args = ap.parse_args(argv)

    if not args.dir.exists():
        print(f"没有 pilot 产物目录：{args.dir}")
        return 2
    if args.page:
        if ":" not in args.page:
            print("--page 格式是 学科:页码", file=sys.stderr)
            return 2
        subject, _, page_s = args.page.partition(":")
        got = grade_one(args.dir, subject.strip(), int(page_s))
        if got is None:
            print(f"没有这一页的产物：{args.dir}/{subject}/p{int(page_s):04d}.jsonl")
            return 1
        print(f"{subject} p{page_s}：清点={got['items_min'] or '-'} 版面编号={got['items_numbered'] or '-'} "
              f"写出编号={got['numbered_in_text']} 字数={got['chars']} 门={got['gate']}")
        for why in got["problems"]:
            print(f"  ! {why}")
        if args.json:
            args.json.write_text(json.dumps(got, ensure_ascii=False, indent=1), encoding="utf-8")
        return 0 if got["gate"] == "pass" else 1
    out = grade_dir(args.dir)
    print(f"{'页':22s} {'清点':>5s} {'版面编号':>8s} {'写出编号':>8s} {'式':>5s} {'图':>4s} "
          f"{'字数':>6s} {'不确定':>6s}  门")
    for p in out["pages"]:
        tag = f"{p['subject']} p{p['page']}"
        print(f"{tag:22s} {str(p['items_min'] or '-'):>5s} {str(p['items_numbered'] or '-'):>8s} "
              f"{p['numbered_in_text']:>8d} {p['formulas']:>5d} {p['figs']:>4d} "
              f"{p['chars']:>6d} {p['uncertainties']:>6d}  {p['gate']}")
        for why in p["problems"]:
            print(f"    ! {why}")
    print(f"\n过闸 {out['passed']}／{len(out['pages'])} 页")
    if args.json:
        args.json.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"→ 已写 {args.json}")
    return 0 if out["pages"] and out["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
