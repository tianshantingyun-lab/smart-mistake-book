# -*- coding: utf-8 -*-
"""源文件提取状态机：每个内容载体文件一条状态行，Phase 2/4 按状态增量推进。

## 它消灭的失败

61GB / 10482 个源文件的提取（Office 直抽 + 扫描件视觉转写）是超长任务，必然跨多个
会话中断续跑。没有状态账本就会：① 重跑时不知哪些已处理，重复提取或漏提取；
② 无法回答"还剩多少、卡在哪"；③ 失败文件没有记录，下次无从排查。

## 状态取值

    SKIPPED_NO_CONTENT  无内容载体（content_bearing=no 或二进制非内容文件）
    PENDING             可直抽（docx/pptx/txt/html/带文本层 pdf）
    PENDING_SCANNED     需视觉转写（扫描 pdf / png）
    PENDING_STRUCTURED  结构化导图（emmx/xmind，zip 内 XML）
    PENDING_MEDIA       视频（mp4，转写通道另议）
    EXTRACTED           已提取入库（output_ref 记材料 slug，可多个逗号分隔）
    REJECTED            判定无入库价值（note 记理由）
    ERROR               提取失败（note 记错误，可重试回 PENDING*）

只允许"前进"的迁移：PENDING* → EXTRACTED/REJECTED/ERROR；ERROR → 回对应 PENDING*。
SKIPPED_NO_CONTENT 是终态。

## 用法

    PYTHONPATH=tools python -m kb_coverage.extraction_state --init     # 从 source_inventory.csv 初始化
    PYTHONPATH=tools python -m kb_coverage.extraction_state --report   # 按 科×状态 计数
    PYTHONPATH=tools python -m kb_coverage.extraction_state --mark "a.pdf=EXTRACTED:m1,m2" --tool phase2
    PYTHONPATH=tools python -m kb_coverage.extraction_state --verify   # 账本完整性门
"""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
from pathlib import Path

INV = Path(__file__).resolve().parent / "source_inventory.csv"
TABLE = Path(__file__).resolve().parent / "tables" / "extraction_state.csv"
COLUMNS = ("rel_path", "subject", "ext", "state", "output_ref", "tool", "note", "updated_at")

SUBJECT_KEYWORDS = (
    ("数学", "MATH"), ("物理", "PHYSICS"), ("化学", "CHEMISTRY"), ("生物", "BIOLOGY"),
)

SKIP_EXTS = {".ttf", ".ttc", ".exe", ".inf", ".ini", ".apm", ".zip", ""}


def _subject(rel_path: str) -> str:
    """科目词常埋在深层目录/文件名里（如 …/2026年体育单招数学零基础…），全路径扫描。

    同一路径出现多个科目词（全科卷）→ UNASSIGNED，不猜。
    """
    hits = {subj for kw, subj in SUBJECT_KEYWORDS if kw in rel_path}
    return hits.pop() if len(hits) == 1 else "UNASSIGNED"


def _initial_state(row: dict) -> tuple[str, str]:
    if row["content_bearing"] != "yes":
        return "SKIPPED_NO_CONTENT", "无内容载体"
    ext = row["ext"]
    if ext in SKIP_EXTS:
        return "SKIPPED_NO_CONTENT", f"二进制非内容文件 {ext}"
    if ext in (".docx", ".doc", ".pptx", ".pps", ".txt", ".html"):
        return "PENDING", ""
    if ext == ".pdf":
        if row["text_layer"] == "SCANNED_IMAGE":
            return "PENDING_SCANNED", "扫描件，需视觉转写"
        return "PENDING", "带文本层"
    if ext in (".emmx", ".xmind"):
        return "PENDING_STRUCTURED", "思维导图（zip 内 XML）"
    if ext in (".png", ".jpg", ".jpeg"):
        return "PENDING_SCANNED", "图片，需视觉转写"
    if ext == ".mp4":
        return "PENDING_MEDIA", "视频"
    return "PENDING", f"未归类扩展名 {ext}"


PENDING_STATES = {"PENDING", "PENDING_SCANNED", "PENDING_STRUCTURED", "PENDING_MEDIA"}
FORWARD = {
    "PENDING": {"EXTRACTED", "REJECTED", "ERROR"},
    "PENDING_SCANNED": {"EXTRACTED", "REJECTED", "ERROR"},
    "PENDING_STRUCTURED": {"EXTRACTED", "REJECTED", "ERROR"},
    "PENDING_MEDIA": {"EXTRACTED", "REJECTED", "ERROR"},
    "ERROR": PENDING_STATES,
    "SKIPPED_NO_CONTENT": set(),
    # 一个源文件可产出多条材料：EXTRACTED→EXTRACTED 仅允许 output_ref 前缀扩展（追加）
    "EXTRACTED": {"EXTRACTED"},
    "REJECTED": set(),
}


def init_state(path: Path = TABLE) -> int:
    """从 source_inventory.csv 初始化状态表。已有行保持不动（幂等：新增文件才补行）。"""
    rows = list(csv.DictReader(open(INV, encoding="utf-8")))
    existing: dict[str, dict] = {}
    if path.exists():
        for r in csv.DictReader(open(path, encoding="utf-8")):
            existing[r["rel_path"]] = r
    now = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S")
    out, added = [], 0
    for r in rows:
        if r["rel_path"] in existing:
            out.append(existing[r["rel_path"]])
            continue
        state, note = _initial_state(r)
        out.append({"rel_path": r["rel_path"], "subject": _subject(r["rel_path"]),
                    "ext": r["ext"], "state": state, "output_ref": "",
                    "tool": "init", "note": note, "updated_at": now})
        added += 1
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(COLUMNS), lineterminator="\n")
        w.writeheader()
        w.writerows(out)
    return added


def load_states(path: Path = TABLE) -> dict[str, dict]:
    if not path.exists():
        raise FileNotFoundError(f"状态表不存在，先 --init：{path}")
    return {r["rel_path"]: r for r in csv.DictReader(open(path, encoding="utf-8"))}


def mark(paths: dict[str, tuple[str, str]], tool: str, path: Path = TABLE) -> list[str]:
    """paths: rel_path -> (new_state, output_ref|note)。返回实际应用清单。"""
    states = load_states(path)
    now = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S")
    applied = []
    for rel, (new, ref) in paths.items():
        row = states.get(rel)
        if row is None:
            raise ValueError(f"状态表无此文件：{rel}")
        if new not in FORWARD.get(row["state"], set()):
            raise ValueError(f"非法迁移 {row['state']} → {new}（{rel}）")
        if row["state"] == new == "EXTRACTED":
            old = row["output_ref"]
            if not (ref == old or ref.startswith(old + ",")):
                raise ValueError(f"EXTRACTED 追加须保持 output_ref 前缀（{rel}）")
        row["state"] = new
        row["output_ref"] = ref
        row["tool"] = tool
        row["updated_at"] = now
        applied.append(rel)
    cols = list(COLUMNS)
    with path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, lineterminator="\n")
        w.writeheader()
        w.writerows(states.values())
    return applied


def report(path: Path = TABLE) -> dict[tuple[str, str], int]:
    states = load_states(path)
    out: dict[tuple[str, str], int] = {}
    for r in states.values():
        out[(r["subject"], r["state"])] = out.get((r["subject"], r["state"]), 0) + 1
    return out


def verify(path: Path = TABLE) -> list[str]:
    """账本完整性门：覆盖全部 inventory 行、rel_path 唯一、状态合法、EXTRACTED 有 output_ref。"""
    problems: list[str] = []
    inv = {r["rel_path"] for r in csv.DictReader(open(INV, encoding="utf-8"))}
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    seen: set[str] = set()
    for r in rows:
        if r["rel_path"] in seen:
            problems.append(f"重复行 {r['rel_path']}")
        seen.add(r["rel_path"])
        if r["state"] not in FORWARD:
            problems.append(f"非法状态 {r['state']}（{r['rel_path']}）")
        if r["state"] == "EXTRACTED" and not r["output_ref"]:
            problems.append(f"EXTRACTED 缺 output_ref（{r['rel_path']}）")
    missing = inv - seen
    if missing:
        problems.append(f"缺 {len(missing)} 个 inventory 文件，例：{sorted(missing)[:3]}")
    # EXTRACTED 的 output_ref 必须真实存在于 sidecar（防状态与包漂移）
    from kb_build import pack_io
    mats: set[str] = set()
    for sp in pack_io.sidecar_paths():
        if sp.exists():
            mats.update(m["slug"] for m in pack_io.load_json(sp)["materials"])
    for r in rows:
        if r["state"] != "EXTRACTED" or not r["output_ref"]:
            continue
        for ref in r["output_ref"].split(","):
            if ref and ref not in mats:
                problems.append(f"output_ref 悬空：{ref}（{r['rel_path']}）")
    return problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--init", action="store_true")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--mark", action="append", default=[],
                    help="rel_path=NEW_STATE[:output_ref]，可重复")
    ap.add_argument("--tool", default="manual")
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args(argv)

    if args.init:
        added = init_state()
        print(f"初始化完成：新增 {added} 行；表共 {len(load_states())} 行")
    if args.mark:
        paths = {}
        for spec in args.mark:
            rel, rest = spec.split("=", 1)
            ref = ""
            if ":" in rest:
                rest, ref = rest.split(":", 1)
            paths[rel] = (rest, ref)
        for rel in mark(paths, args.tool):
            print(f"已标记 {rel}")
    if args.report:
        rep = report()
        for (subj, state), n in sorted(rep.items()):
            print(f"{subj:<12} {state:<20} {n}")
    if args.verify or (not args.init and not args.report and not args.mark):
        problems = verify()
        if problems:
            print("账本不完整：")
            for p in problems[:20]:
                print("  -", p)
            return 1
        print("账本完整：覆盖全部 inventory、状态合法、EXTRACTED 均有 output_ref")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
