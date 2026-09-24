# -*- coding: utf-8 -*-
"""页型分类：用**页图的确定性信号**给每页定"页型"，供分级转写用。

## 它消灭的失败

全流程协议（切块+放大+换人对账）单页约 10 次读图，1205 页全走太贵；而按页型分级能把
叙述/封面类页降到轻量（约 3 次读图）。此前没有任何页级分级（全仓 `page_type` 只在一份
提案文档里出现过），只能"凭感觉"分配档位。

## 信号与判据（全部确定性、可复算）

页图（PIL+numpy，先缩到宽 900 再算）：
- `ink`  墨迹覆盖率 = 深色像素占比（阈值 160/255）；
- `rows` 文字行带数 = 水平投影上"有墨"的行段数；
- `rule` 表格横线数 = 横向连续深色占比 ≥0.55 的行数（表格/分隔线特征）；
- `fig`  大块非文字区 = 覆盖率 ≥0.35 的连通粗块（图密集特征，近似：粗粒度网格中含墨格比例）。

转写文本（有转写时）：
- `formulas` 每千字公式数、`figs` 【图】数、`has_table` 是否含 `|` 表行/“对照表”。

分类（保守：**不确定就归"未定"，档位规则把"未定"当全流程**，绝不因误判而放行）：
- `封面/扉页`：page ≤ 3 且 rows 少；
- `公式密排`：formulas/千字 ≥ 25，或（无转写时）rows ≥ 55 且 ink ≥ 0.09；
- `表格式`：rule ≥ 8，或 has_table；
- `图密集`：figs ≥ 3，或（无转写时）fig 比例 ≥ 0.28；
- `叙述`：其余且 rows ≥ 20；
- `未定`：信号弱/矛盾。

用法：
    PYTHONPATH=tools python -m kb_coverage.classify_page_kind            # 报告（不写）
    PYTHONPATH=tools python -m kb_coverage.classify_page_kind --write     # 写 tables/page_kind.csv
    ... --subject CHEMISTRY --limit 5                                     # 抽样调试
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "build/2027-53-pages/manifest_slim.json"
TRANSCRIPTS = REPO / "knowledge-production/2027-53-transcripts"
OUT = REPO / "tools/kb_coverage/tables/page_kind.csv"
COLUMNS = ("subject", "pdf_rel", "page", "page_kind", "ink", "rows", "rule", "fig",
           "formulas", "figs", "note")
WORK_W = 900
INK_MAX = 160          # 灰度 < INK_MAX 视为墨
RULE_FRAC = 0.55       # 一行里 ≥55% 是墨 → 判为横线
BLOCK = 24             # 粗网格边长（算"图区"比例）


def image_signals(path: Path) -> dict | None:
    try:
        im = Image.open(path).convert("L")
    except Exception:  # noqa: BLE001 - 单页失败不该中断整批
        return None
    h = max(1, int(im.height * WORK_W / im.width))
    a = np.asarray(im.resize((WORK_W, h)), dtype=np.uint8)
    ink_mask = a < INK_MAX
    ink = float(ink_mask.mean())
    row_ink = ink_mask.mean(axis=1)
    rows = int(np.count_nonzero(np.diff((row_ink > 0.02).astype(np.int8)) == 1))
    rule = int(np.count_nonzero(row_ink > RULE_FRAC))
    # 粗网格里的含墨格比例（近似"图/色块区"占比）
    gh, gw = h // BLOCK, WORK_W // BLOCK
    if gh and gw:
        grid = ink_mask[:gh * BLOCK, :gw * BLOCK].reshape(gh, BLOCK, gw, BLOCK).mean(axis=(1, 3))
        fig = float((grid > 0.35).mean())
    else:
        fig = 0.0
    return {"ink": round(ink, 4), "rows": rows, "rule": rule, "fig": round(fig, 4)}


def transcript_signals() -> dict[tuple[str, int], dict]:
    out: dict[tuple[str, int], dict] = {}
    if not TRANSCRIPTS.exists():
        return out
    for f in sorted(TRANSCRIPTS.glob("**/range_*.jsonl")):
        subject = f.parts[-3]
        for raw in f.read_text(encoding="utf-8", errors="replace").splitlines():
            s = raw.strip()
            if not s:
                continue
            if s.endswith(","):
                s = s[:-1].rstrip()
            try:
                rec = json.loads(s)
            except json.JSONDecodeError:
                continue
            t = rec.get("text") or ""
            page = rec.get("page")
            if isinstance(page, int):
                per_k = len(re.findall(r"\$[^$\n]{1,400}\$", t)) / max(len(t), 1) * 1000
                out[(subject, page)] = {
                    "formulas": round(per_k, 1), "figs": t.count("【图"),
                    "has_table": ("|" in t and t.count("|") >= 8) or "对照表" in t or "表格" in t}
    return out



def classify(page: int, img: dict | None, txt: dict | None) -> tuple[str, str]:
    if img is None:
        return "未定", "页图缺失"
    if txt is None:
        # 无转写：只用图像信号，保守
        if page <= 3 and img["rows"] < 25:
            return "封面/扉页", "前 3 页且行数少"
        if img["rule"] >= 8:
            return "表格式", f"横线 {img['rule']}"
        if img["fig"] >= 0.28:
            return "图密集", f"图区比例 {img['fig']}"
        if img["rows"] >= 55 and img["ink"] >= 0.09:
            return "公式密排", f"行 {img['rows']} ink {img['ink']}"
        if img["rows"] >= 20:
            return "叙述", f"行 {img['rows']}"
        return "未定", f"信号弱（行 {img['rows']}）"
    # 有转写：文本信号优先，图像信号兜底
    if page <= 3 and img["rows"] < 25 and txt["formulas"] < 5:
        return "封面/扉页", "前 3 页"
    if txt["formulas"] >= 25:
        return "公式密排", f"公式 {txt['formulas']}/千字"
    if txt["has_table"]:
        return "表格式", "含表行"
    if txt["figs"] >= 3:
        return "图密集", f"【图】{txt['figs']}"
    if img["rule"] >= 8:
        return "表格式", f"横线 {img['rule']}"
    if img["rows"] >= 20:
        return "叙述", f"行 {img['rows']}"
    return "未定", "文本与图像信号都弱"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--subject", default=None)
    ap.add_argument("--limit", type=int, default=None)
    args = ap.parse_args(argv)

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    txt = transcript_signals()
    rows: list[dict] = []
    for sub in manifest["subjects"]:
        subject = sub["subject"]
        if args.subject and subject != args.subject:
            continue
        img_dir = REPO / sub["image_dir"]
        for page in range(1, int(sub["total_pages"]) + 1):
            if args.limit and page > args.limit:
                break
            img = image_signals(img_dir / f"p{page:04d}.jpg")
            t = txt.get((subject, page))
            kind, note = classify(page, img, t)
            rows.append({
                "subject": subject, "pdf_rel": sub["pdf_rel"], "page": page,
                "page_kind": kind,
                "ink": img["ink"] if img else "", "rows": img["rows"] if img else "",
                "rule": img["rule"] if img else "", "fig": img["fig"] if img else "",
                "formulas": t["formulas"] if t else "", "figs": t["figs"] if t else "",
                "note": note,
            })
        if args.limit:
            break
    print(f"分类页数 {len(rows)}｜页型分布：{dict(Counter(r['page_kind'] for r in rows))}")
    for kind in ("公式密排", "图密集", "表格式", "叙述", "封面/扉页", "未定"):
        n = sum(1 for r in rows if r["page_kind"] == kind)
        if n:
            print(f"  {kind:<6} {n:>5} 例：",
                  ", ".join(f"{r['subject'][:4]}p{r['page']}" for r in rows if r["page_kind"] == kind)[:90])
    if args.write and not args.limit:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        with OUT.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(COLUMNS), lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
        print(f"→ 已写 {OUT}（{len(rows)} 行）")
    elif not args.write:
        print("（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
