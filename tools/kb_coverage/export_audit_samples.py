# -*- coding: utf-8 -*-
"""往桌面导抽样：源页图 + 新转写（+ 旧稿对照），供人工逐字核对。

## 为什么要有它

自动门只能判机械面（截断/残迹/计数），"文字跟页面到底像不像"只能人看。这一版抽样专门配
**S4 审计的判决**：把审计判"要重转"的页连同**旧稿**一起摆出来——旧稿错在哪、新稿改对没有，
一眼可核。目的是让你（或用户）能独立验证审计不是在说胡话，而不是只能相信代理的自述。

挑选是确定性的（不随机）：按页码排序，优先"审计判过 RETRANSCRIBE 且已有新稿"的页，
再从补齐/新转的页里补足到 `--max` 条。

用法：
    PYTHONPATH=tools python -m kb_coverage.export_audit_samples --max 12
    （写到桌面 `53转写抽查-3/`；同时打印一份清单）
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
from kb_coverage import transcription_ledger as tl  # noqa: E402

DESKTOP = Path.home() / "Desktop" / "53转写抽查-3"
FILL = REPO / "knowledge-production/2027-53-fill"
PAGES = REPO / "build/2027-53-pages"
TRANS = REPO / "knowledge-production/2027-53-transcripts"
MANIFEST = REPO / "build/2027-53-pages/manifest_slim.json"


def image_dir(subject: str) -> Path:
    for s in json.loads(MANIFEST.read_text(encoding="utf-8"))["subjects"]:
        if s["subject"] == subject:
            return Path(s["image_dir"])
    return PAGES / subject


def read_text(path: Path, page: int) -> str:
    if not path.exists():
        return ""
    out = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        s = raw.strip().rstrip(",")
        if not s:
            continue
        try:
            rec = json.loads(s)
        except json.JSONDecodeError:
            continue
        if rec.get("page") == page:
            out.append(f"### {rec.get('heading') or '(无标题)'}\n{rec.get('text') or ''}")
    return "\n\n".join(out)


def pick(max_n: int) -> list[tuple[str, int, str]]:
    """(学科, 页码, 类别) —— 先审计判过的，再补齐的；**按学科轮流**取，保证四科都露面。"""
    rows = tl.build_rows()
    buckets: dict[str, list[tuple[str, int, str]]] = {}
    for r in rows:
        if r["verdict"] == "RETRANSCRIBE" and (FILL / r["subject"] / f"p{r['page']:04d}.jsonl").exists():
            buckets.setdefault(r["subject"], []).append((r["subject"], r["page"], "审计判重转（旧稿有实质缺陷）"))
    for r in rows:
        if r["status"] == "done" and (FILL / r["subject"] / f"p{r['page']:04d}.jsonl").exists():
            buckets.setdefault(r["subject"], []).append((r["subject"], r["page"], "补齐/重转后过闸"))
    picked: list[tuple[str, int, str]] = []
    keys = sorted(buckets)
    i = 0
    while len(picked) < max_n and any(buckets[k] for k in keys):
        k = keys[i % len(keys)]
        if buckets[k]:
            cand = buckets[k].pop(0)
            if not any(p[:2] == cand[:2] for p in picked):
                picked.append(cand)
        i += 1
    return picked[:max_n]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--max", type=int, default=12)
    ap.add_argument("--out", type=Path, default=DESKTOP)
    args = ap.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    lines = ["序号\t学科\t页码\t类别\t页图\t新字数\t旧字数\t不准/其他"]
    # 旧稿直接用账本的读法（(学科,页) → {span,text}），别去猜 range 文件名
    old_map = tl.load_transcripts()
    for i, (subject, page, kind) in enumerate(pick(args.max), 1):
        tag = f"p{page:04d}"
        new_text = read_text(FILL / subject / f"{tag}.jsonl", page)
        old_text = (old_map.get((subject, page)) or {}).get("text", "")
        d = args.out / f"{i:02d}_{subject}_{tag}"
        d.mkdir(exist_ok=True)
        img = image_dir(subject) / f"{tag}.jpg"
        if img.exists():
            shutil.copy2(img, d / f"页图{tag}.jpg")
        (d / "新转写.txt").write_text(new_text or "（没有新转写）", encoding="utf-8")
        (d / "旧稿.txt").write_text(old_text or "（没有旧稿）", encoding="utf-8")
        (d / "说明.txt").write_text(
            f"学科 {subject} 第 {page} 页（PDF 第 {page} 页）\n类别：{kind}\n"
            f"新稿字数 {len(new_text)}｜旧稿字数 {len(old_text)}\n"
            f"怎么核：打开 页图{tag}.jpg，与 新转写.txt 逐段对照；疑处看 旧稿.txt 是不是它错了。\n",
            encoding="utf-8")
        lines.append(f"{i}\t{subject}\t{page}\t{kind}\t页图{tag}.jpg\t{len(new_text)}\t{len(old_text)}\t")
        print(f"{i:02d} {subject} p{page} [{kind}] 新 {len(new_text)} 字 / 旧 {len(old_text)} 字 → {d}")
    (args.out / "清单.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n共 {len(lines) - 1} 条 → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
