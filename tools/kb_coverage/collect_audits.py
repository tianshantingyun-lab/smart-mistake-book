# -*- coding: utf-8 -*-
"""把"几十个子代理各自写的审计裁定"收成一张表（唯一写者，避免并发追加互相覆盖）。

## 它消灭的失败

S4（换人审计 847 页）与 S3（补齐 358 页）的判决若让每个子代理直接 append 同一张
`transcript_audits.csv`，并发追加就会互相覆盖（CSV 不是并发安全的），而覆盖掉的判决
没有任何痕迹——"判了但没执行"的老形态换个马甲回来。本工具按本仓惯例做**唯一写者**：
子代理各写自己的片文件（`tables/audit_slices/*.csv`），由本工具一次性收拢、
逐页查重（同页两条判决即拒绝）、排序写出。

两种来源：

- `--from-slices <dir>`：S4 的审计片（`subject,pages,verdict,items_min,items_numbered,evidence`）；
- `--from-counts <dir>`：S3 补齐页的清点产物（`<学科>/pNNNN.counts.json`）→ 落成
  `verdict=ACCEPT` + 该页清点数的行（补齐页的清点是转写时做的，这就是它的裁定）。

已存在的目标表会先读进来；新行与旧行**同页冲突即整批拒绝**（不写盘）。

用法：
    PYTHONPATH=tools python -m kb_coverage.collect_audits --from-slices tools/kb_coverage/tables/audit_slices
    PYTHONPATH=tools python -m kb_coverage.collect_audits --from-counts knowledge-production/2027-53-fill
    （加 --write 落盘）
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TARGET = REPO / "tools/kb_coverage/tables/transcript_audits.csv"
COLUMNS = ("subject", "pages", "verdict", "items_min", "items_numbered", "evidence")


def read_rows(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as fh:
        return [{k: (v or "").strip() for k, v in r.items()} for r in csv.DictReader(fh)]


def from_slices(d: Path) -> list[dict]:
    rows: list[dict] = []
    for f in sorted(d.glob("*.csv")):
        with f.open(encoding="utf-8-sig", newline="") as fh:
            for r in csv.DictReader(fh):
                r = {k: (v or "").strip() for k, v in r.items()}
                r["evidence"] = (r.get("evidence") or "") or f.name
                rows.append({k: r.get(k, "") for k in COLUMNS})
    return rows


def from_counts(d: Path) -> list[dict]:
    rows: list[dict] = []
    for f in sorted(d.glob("*/p*.counts.json")):
        subject = f.parent.name
        page = int(f.stem.split(".")[0].lstrip("p"))
        doc = json.loads(f.read_text(encoding="utf-8"))
        rows.append({"subject": subject, "pages": str(page), "verdict": "ACCEPT",
                     "items_min": str(doc.get("items_min", "")),
                     "items_numbered": str(doc.get("items_numbered", "")),
                     "evidence": "S3 补齐页：清点与转写同人同批，机械门另判"})
    return rows


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--from-slices", type=Path)
    ap.add_argument("--from-counts", type=Path)
    ap.add_argument("--target", type=Path, default=TARGET)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)

    new: list[dict] = []
    if args.from_slices:
        if not args.from_slices.exists():
            print(f"没有片目录：{args.from_slices}")
            return 2
        new += from_slices(args.from_slices)
    if args.from_counts:
        if not args.from_counts.exists():
            print(f"没有清点目录：{args.from_counts}")
            return 2
        new += from_counts(args.from_counts)
    if not new:
        print("没有可收的行（既没给 --from-slices 也没给 --from-counts 或都为空）")
        return 0

    old = read_rows(args.target)
    print(f"目标表现有 {len(old)} 行；本次收集 {len(new)} 行")

    # 逐页查重：一行可能是区间（15-28）或列表（15,17）——按页展开比对
    sys.path.insert(0, str(REPO / "tools"))
    from kb_coverage import apply_transcript_audits as ata  # noqa: E402

    seen: dict[tuple[str, int], str] = {}
    for i, r in enumerate(old, 2):
        for p in ata.expand(r.get("pages", "")):
            seen[(r["subject"], p)] = f"旧行 L{i}"
    problems: list[str] = []
    for r in new:
        for p in ata.expand(r.get("pages", "")):
            key = (r["subject"], p)
            if key in seen:
                problems.append(f"{r['subject']} p{p} 与 {seen[key]} 冲突")
            seen[key] = "本次"
    if problems:
        print(f"★ 冲突 {len(problems)} 条（整批拒绝，不写表）：")
        for p in problems[:12]:
            print("   !", p)
        return 1

    merged = old + new
    print(f"合并后 {len(merged)} 行（覆盖 "
          f"{len({(r['subject'], p) for r in merged for p in ata.expand(r.get('pages', ''))})} 页）")
    if args.write:
        args.target.parent.mkdir(parents=True, exist_ok=True)
        with args.target.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(COLUMNS), lineterminator="\n",
                               extrasaction="ignore")
            w.writeheader()
            w.writerows(merged)
        print(f"→ 已写 {args.target}")
    else:
        print("（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
