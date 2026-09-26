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


def _norm_row(raw: dict) -> dict:
    """把 csv 读出来的行归一：多余列并回 evidence，非字符串字段一并容忍。

    实测形态（2026-09-25）：审计代理在 evidence 里写了逗号但没加引号，csv 就把后半句拆成
    多余列塞进 `None` 键 —— 原实现对它调 `.strip()` 直接崩，整批收拢失败。多余列本来就是
    evidence 的尾巴，**并回去而不是丢掉**（那是审计的证据，不是噪声）。
    """
    row = {}
    for k, v in raw.items():
        if k is None:
            continue
        if isinstance(v, list):
            v = ",".join(str(x) for x in v)
        row[k] = (v or "").strip() if isinstance(v, str) else str(v or "").strip()
    extra = raw.get(None)
    if extra:
        tail = ",".join(str(x) for x in extra).strip()
        row["evidence"] = (row.get("evidence", "") + ("，" if row.get("evidence") else "") + tail)
    return row


def read_rows(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as fh:
        return [_norm_row(r) for r in csv.DictReader(fh)]


def from_slices(d: Path) -> list[dict]:
    rows: list[dict] = []
    for f in sorted(d.glob("*.csv")):
        with f.open(encoding="utf-8-sig", newline="") as fh:
            for r in csv.DictReader(fh):
                r = _norm_row(r)
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

    # 逐页归并（2026-09-25 补齐与审计并行后必须有的规则）：
    #
    # - **完全相同的行**：跳过（幂等——重启/续跑会把同一张片再收一次，那不是冲突）；
    # - **ACCEPT 与 RETRANSCRIBE 撞同一页**：取 ACCEPT 并**打印被覆盖的行**。语义：ACCEPT 来自
    #   "页已落盘且过机械门"（补齐/重转真的做过），RETRANSCRIBE 是旧状态下的判决（例如审计时
    #   那页还整页缺失）——覆盖是正确方向，但绝不许静默；
    # - 其它同页不同判决（含两个都 ACCEPT 但内容不同）：**仍然整批拒绝**（两份判决打架时停下）。
    sys.path.insert(0, str(REPO / "tools"))
    from kb_coverage import apply_transcript_audits as ata  # noqa: E402

    merges: dict[tuple[str, int], dict] = {}
    overrides: list[str] = []
    skipped = 0
    problems: list[str] = []
    for src, r in [("旧表", x) for x in old] + [("本次", x) for x in new]:
        for p in ata.expand(r.get("pages", "")):
            key = (r["subject"], p)
            cur = merges.get(key)
            if cur is None:
                merges[key] = r
                continue
            if cur is r:
                continue
            if all(cur.get(c, "") == r.get(c, "") for c in COLUMNS):
                skipped += 1                # 同一张片再收一次：幂等
                continue
            if {cur.get("verdict", ""), r.get("verdict", "")} == {"ACCEPT", "RETRANSCRIBE"}:
                win, lose = (r, cur) if r.get("verdict") == "ACCEPT" else (cur, r)
                merges[key] = win
                overrides.append(f"{key[0]} p{p}：ACCEPT 覆盖 RETRANSCRIBE"
                                 f"（被覆盖行 evidence：{(lose.get('evidence') or '')[:60]}）")
                continue
            if cur.get("verdict", "") == r.get("verdict", ""):
                # 同判决、不同来源（例：补齐的清点行 vs 审计片的 ACCEPT 行）：不是打架。
                # 取**清点数更大**的那份——对 ACCEPT 页来说那会让计数闸门更严（保守方向）。
                def _im(x: dict) -> int:
                    try:
                        return int(x.get("items_min") or -1)
                    except ValueError:
                        return -1
                win, lose = (r, cur) if _im(r) > _im(cur) else (cur, r)
                merges[key] = win
                overrides.append(f"{key[0]} p{p}：同为 {win.get('verdict')}，取清点数更大的一份"
                                 f"（{_im(win)} vs {_im(lose)}）")
                continue
            problems.append(f"{key[0]} p{p} 两条判决打架：{cur.get('verdict')} vs {r.get('verdict')}")
    if skipped:
        print(f"完全相同的行跳过（幂等）：{skipped} 页次")
    if overrides:
        print(f"ACCEPT 覆盖 RETRANSCRIBE：{len(overrides)} 页（不静默，列表如下）")
        for o in overrides[:12]:
            print("   ↻", o)
    if problems:
        print(f"★ 冲突 {len(problems)} 条（整批拒绝，不写表）：")
        for p in problems[:12]:
            print("   !", p)
        return 1

    merged: list[dict] = []
    kept: set[int] = set()
    # 关键：一行覆盖多页、其中某几页被别的行赢走时，必须把该行的 `pages` **收窄到它真正赢下的页**——
    # 否则同一页会同时出现在两个行里（实测：549-560 的多页行与单页 551 的行并存 → 下游校验判
    # "同一页两条判决"、整批拒绝）。
    won: dict[int, list[int]] = {}
    for (subject, p), r in merges.items():
        won.setdefault(id(r), []).append(p)
    for r in old + new:
        ps = sorted(won.get(id(r), []))
        if not ps:
            continue
        if id(r) in kept:
            continue
        kept.add(id(r))
        merged.append({**{k: r.get(k, "") for k in COLUMNS},
                       "pages": ",".join(str(x) for x in ps)})
    print(f"合并后 {len(merged)} 行（覆盖 {len(merges)} 页，每页恰一行）")
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
