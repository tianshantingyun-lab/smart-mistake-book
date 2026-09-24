# -*- coding: utf-8 -*-
"""页级账本：把「1205 页扫描件的转写进度与质量信号」变成一张可复算的表。

## 它消灭的失败

此前的进度只能靠"数文件"回答，于是出现两种误判：
① 以为"66 个片 = 覆盖完了"，实际有 22 个整片缺失、7 个片只写到中途（数学缺 549–552、
   物理缺 202–203、化学只覆盖 97/235、生物只有第 15 页 1 页）；
② 无法回答"哪一页质量可信、哪一页要重转"——审计判决（`transcript_audits.csv`）与
   确定性信号（公式数/图数/编号数/是否截断）没有落成一张表。

本工具**只读源 + 重建**：manifest（该有 1205 页）+ 转写目录 + 页型表 + 审计判决表
→ `tables/transcription_pages.csv`。账本本身**不手工编辑**（derived），所以永远与源一致。

## 计数口径（用户 2026-09-24 裁定）

闸门以**「可独立引用的最小式/条」**为单位，同时记「版面编号条数」：
- `items_min`：审计（P1 清点）给出的最小式/条数——模型读图数出来的，是闸门的分母；
- `numbered`：**确定性**数出的版面编号条数（行首 `1.`/`（1）`/`①` 这类标记）；
- 两个数都进账本：前者是"应该有多少"，后者是"转写里能数出多少编号"，不一致就进人工/重转队列。

## 用法

    PYTHONPATH=tools python -m kb_coverage.transcription_ledger            # 报告
    PYTHONPATH=tools python -m kb_coverage.transcription_ledger --write     # 写表
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from kb_build import gate

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "build/2027-53-pages/manifest_slim.json"
TRANSCRIPTS = REPO / "knowledge-production/2027-53-transcripts"
PAGE_KIND = REPO / "tools/kb_coverage/tables/page_kind.csv"
AUDITS = REPO / "tools/kb_coverage/tables/transcript_audits.csv"
LEDGER = REPO / "tools/kb_coverage/tables/transcription_pages.csv"

COLUMNS = ("subject", "pdf_rel", "page", "span", "status", "page_kind", "plan",
           "chars", "formulas", "figs", "numbered", "uncertainties", "truncated",
           "items_min", "verdict", "gate", "note")

# 行首编号 / 圈号（版面编号条数的确定性判据）
_NUMBERED = re.compile(r"(?m)^\s*(?:\(?\d{1,3}\)?[.、)）]|[①-⑳]|[⒈-⒛])")
_CIRCLED = re.compile(r"[①-⑳]")
_FORMULA = re.compile(r"\$[^$\n]{1,400}\$")

# 截断判据：**结构化**，不看文本尾部。
#
# 曾用"文本尾部不以句末标点结束"当截断代理，实测 192 页误报（正常页以 `．`、`”`、页码数字
# 收尾，都是我漏掉的白名单）——文本形态的截断代理太脆，弃用。改用结构信号：
# 一个片文件 `range_0015_0028` 只写到第 26 页（3 页缺失）时，它的**末页**（26）很可能只转了
# 一半 → 该页 `truncated=yes`；缺失的页本身就已是 `status=missing`，不需要代理再判。


def load_manifest() -> list[dict]:
    if not MANIFEST.exists():
        raise SystemExit(f"缺 manifest：{MANIFEST}（先跑 scan_render_pages.py）")
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return [s for s in data.get("subjects", []) if s.get("subject") and s.get("total_pages")]


def load_transcripts() -> dict[tuple[str, int], dict]:
    """(subject, page) -> {span, text}；容忍代理常见的行尾逗号。"""
    out: dict[tuple[str, int], dict] = {}
    if not TRANSCRIPTS.exists():
        return out
    for f in sorted(TRANSCRIPTS.glob("**/range_*.jsonl")):
        subject = f.parts[-3]
        span = f.stem.replace("range_", "")
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
            page = rec.get("page")
            if isinstance(page, int):
                out[(subject, page)] = {"span": span, "text": rec.get("text") or ""}
    return out


def _read_table(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def page_kind_map() -> dict[tuple[str, int], str]:
    return {(r["subject"], int(r["page"])): r["page_kind"]
            for r in _read_table(PAGE_KIND) if r.get("page", "").isdigit()}


def audit_map() -> dict[tuple[str, int], dict]:
    """审计判决按页展开：`pages` 支持 "15" / "15-28" / "15,17" 三种写法。"""
    out: dict[tuple[str, int], dict] = {}
    for r in _read_table(AUDITS):
        subj, pages = r.get("subject", "").strip(), (r.get("pages") or "").strip()
        for part in [p for p in pages.split(",") if p.strip()]:
            part = part.strip()
            nums = [int(part)] if part.isdigit() else (
                list(range(int(part.split("-")[0]), int(part.split("-")[1]) + 1))
                if re.fullmatch(r"\d+-\d+", part) else [])
            for n in nums:
                out[(subj, n)] = {"verdict": (r.get("verdict") or "").strip(),
                                  "items_min": (r.get("items_min") or "").strip(),
                                  "evidence": (r.get("evidence") or "").strip()}
    return out


def signals(text: str) -> dict:
    t = text or ""
    return {
        "chars": len(t),
        "formulas": len(_FORMULA.findall(t)),
        "figs": t.count("【图"),
        "numbered": len(_NUMBERED.findall(t)),
        "circled": len(_CIRCLED.findall(t)),
        "uncertainties": t.count("【不确定"),
    }


def compute_gate(row: dict, text: str = "") -> str:
    """单页闸门（**唯一判据来源**，check_transcripts 也调它）。

    pending = 未转写；fail = 机械信号不过；pass = 机械信号过且（若有审计）审计未判重转。
    注意：这里**不**判"内容对不对"——那是审计（P4）的事；这里只挡机械可判的坏页
    （截断 / 过短 / 条数闸 / **文本残迹**——残迹判据复用 `kb_build.gate.field_text_defects`，
    不让转写线各写一套）。
    """
    if row["status"] != "done":
        return "pending"
    if row["verdict"] == "RETRANSCRIBE":
        return "fail"
    if row["truncated"]:
        return "fail"
    if row["chars"] < 40:
        return "fail"
    if text and gate.field_text_defects(text):
        return "fail"
    if row["items_min"]:
        try:
            if int(row["items_min"]) > max(row["numbered"], 1) * 3:
                # 清点说该有 N 条，转写里却连 N/3 个编号都数不出来 → 疑似整块漏
                return "fail"
        except ValueError:
            pass
    return "pass"


DENSE_KINDS = {"公式密排", "图密集", "表格式", "未定"}


def compute_plan(row: dict) -> str:
    """本页该走哪条路（**保守式**：页型只用于"升级"，绝不用于降级放行）。

    实测教训：图像信号识别不出"公式密排"（CHEM p2 墨迹 0.0084、行 38，与叙述页不可分），
    而它自己的转写又因未用 LaTeX 使文本信号失效——**靠页型放行会放过坏页**。
    因此：
      缺失页 → 一律全协议（不猜它简单）；
      已有转写 → 机械信号过 + 页型为叙述/封面 → 轻审计（清点+边界，抽检少）；
                 否则 → 全审计（逐条核条件符号/上下标/⇌）；
      机械信号不过或审计判 RETRANSCRIBE → 重转（全协议）。
    """
    if row["gate"] == "fail" or row["verdict"] == "RETRANSCRIBE":
        return "重转-全协议"
    if row["status"] == "missing":
        return "补齐-全协议"
    if row["page_kind"] in ("叙述", "封面/扉页"):
        return "审计-轻"
    return "审计-全"


def build_rows() -> list[dict]:
    kinds, audits = page_kind_map(), audit_map()
    tr = load_transcripts()

    # 结构性截断：每个片文件"名义区间 vs 实有页"，其**末页**标 truncated
    span_pages: dict[tuple[str, str], list[int]] = {}
    for (subject, page), rec in tr.items():
        span_pages.setdefault((subject, rec["span"]), []).append(page)
    span_tail: set[tuple[str, int]] = set()
    for (subject, span), pages in span_pages.items():
        # 文件名里的区间是下划线写法（range_0015_0028），统一成连字符再解析
        m = re.fullmatch(r"(\d+)[-_](\d+)", span)
        if not m:
            continue
        nominal = set(range(int(m.group(1)), int(m.group(2)) + 1))
        if nominal - set(pages):
            span_tail.add((subject, max(pages)))

    rows: list[dict] = []
    for sub in load_manifest():
        subject, total = sub["subject"], int(sub["total_pages"])
        for page in range(1, total + 1):
            rec = tr.get((subject, page))
            sig = signals(rec["text"]) if rec else {"chars": 0, "formulas": 0, "figs": 0,
                                                    "numbered": 0, "circled": 0,
                                                    "uncertainties": 0}
            aud = audits.get((subject, page), {})
            row = {
                "subject": subject, "pdf_rel": sub["pdf_rel"], "page": page,
                "span": rec["span"] if rec else "",
                "status": "done" if rec else "missing",
                "page_kind": kinds.get((subject, page), ""),
                "chars": sig["chars"], "formulas": sig["formulas"], "figs": sig["figs"],
                "numbered": sig["numbered"] + sig["circled"],
                "uncertainties": sig["uncertainties"],
                "truncated": "yes" if (subject, page) in span_tail else "",
                "items_min": aud.get("items_min", ""),
                "verdict": aud.get("verdict", ""),
                "plan": "", "gate": "", "note": "",
            }
            row["gate"] = compute_gate(row, rec["text"] if rec else "")
            row["plan"] = compute_plan(row)
            rows.append(row)
    return rows


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)

    rows = build_rows()
    by_status = Counter(r["status"] for r in rows)
    by_gate = Counter(r["gate"] for r in rows)
    by_kind = Counter(r["page_kind"] or "(未分类)" for r in rows)
    covered = {(r["subject"], r["page"]) for r in rows if r["status"] == "done"}
    print(f"账本页数 {len(rows)}｜转写完成 {by_status['done']}／缺失 {by_status['missing']}")
    print("按学科（完成/总）：", {s: f"{sum(1 for r in rows if r['subject'] == s and r['status'] == 'done')}"
                                f"/{sum(1 for r in rows if r['subject'] == s)}"
                                for s in dict.fromkeys(r["subject"] for r in rows)})
    print("闸门：", dict(by_gate))
    print("页型：", dict(by_kind))
    print("档位：", dict(Counter(r["plan"] for r in rows)))
    print(f"覆盖集合大小 {len(covered)}（应与 done 相等）")

    if args.write:
        LEDGER.parent.mkdir(parents=True, exist_ok=True)
        now = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S")
        with LEDGER.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(COLUMNS), lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
        print(f"→ 已写 {LEDGER}（{len(rows)} 行，生成于 {now}）")
    else:
        print("（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
