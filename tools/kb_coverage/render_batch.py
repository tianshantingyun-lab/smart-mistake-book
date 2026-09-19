# -*- coding: utf-8 -*-
"""扫描件批量渲染：按来源分组的连续页号页图 + 页↔来源映射表。

## 它消灭的失败

1. Windows 上 `multiprocessing` 从 stdin 脚本 spawn 子进程会静默失败（实测：进程在、
   0 产物、无异常）——所以并行度由**外层切片**提供：本脚本一次只渲染一片，互不通信。
2. 多个 PDF 各自从 p0001 起编号会互相覆盖/跳过（一组 200+ 文件时必然踩），
   故按组累计页号，并把 `页号 → 来源相对路径 / 文件内页 / chunk_id` 写进 pages.tsv，
   代理据此填 chunk_rel 与 chunk_id，不必自己算。
3. 分组名与来源目录一一对应（bmath/bphy/bchem/bbio/bx/phyjc/phyerlun/chemzhushu/biozhushu），
   代理只看一个目录 + 一张表。

用法：
    PYTHONPATH=tools python -m kb_coverage.render_batch --plan           # 只出 pages.tsv 与统计
    PYTHONPATH=tools python -m kb_coverage.render_batch --slice 0 --of 8 # 渲染第 0 片（共 8 片）
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = Path(r"C:\Users\听云\Desktop\知识库原始数据资料")
OUT = ROOT / "build" / "render"
DECISIONS = ROOT / "tools" / "kb_coverage" / "tables" / "scan_decisions.csv"
PAGES_TSV = OUT / "pages.tsv"
RULES = {"wusan-b-topic", "wusan-phy-chapter-jingjiang", "erlun-jiangyi", "jjdx-zhushu"}


def group_of(rule: str, subject: str) -> str:
    if rule == "wusan-b-topic":
        return {"math": "bmath", "physics": "bphy", "chemistry": "bchem",
                "biology": "bbio"}.get(subject.lower(), "bx")
    if rule == "wusan-phy-chapter-jingjiang":
        return "phyjc"
    if rule == "erlun-jiangyi":
        return "phyerlun"
    return "chemzhushu" if subject == "CHEMISTRY" else "biozhushu"


def plan() -> list[tuple[str, str, int, int]]:
    """返回 (pdf, group, 全局页号, 文件内页序)。页号按组分文件顺序累加。"""
    import fitz

    rows = list(csv.DictReader(open(DECISIONS, encoding="utf-8-sig")))
    groups: dict[str, list[str]] = {}
    for r in rows:
        if r["decision"] == "TRANSCRIBE_LATER" and r["rule"] in RULES:
            groups.setdefault(group_of(r["rule"], r["subject"]), []).append(r["rel_path"])
    tasks: list[tuple[str, str, int, int]] = []
    tsv: list[str] = []
    for g, rels in sorted(groups.items()):
        off = 0
        for rel in sorted(rels):
            pdf = SOURCE_ROOT / Path(rel)
            if not pdf.exists():
                continue
            with fitz.open(str(pdf)) as d:
                n = d.page_count
            h10 = hashlib.sha256(rel.encode("utf-8")).hexdigest()[:10]
            for i in range(n):
                tasks.append((str(pdf), g, off + i + 1, i))
                tsv.append(f"{off + i + 1}\t{rel}\t{i + 1}\t{h10}-{i + 1:03d}\t{g}")
            off += n
        print(f"{g:<10} files={len(rels):>3} pages={off}")
    PAGES_TSV.parent.mkdir(parents=True, exist_ok=True)
    PAGES_TSV.write_text("\n".join(tsv) + "\n", encoding="utf-8")
    (OUT / "batch_tasks.json").write_text(json.dumps(tasks, ensure_ascii=False), encoding="utf-8")
    print("总页数", len(tasks), "→", PAGES_TSV)
    return tasks


def render_slice(tasks: list[tuple[str, str, int, int]], k: int, of: int) -> int:
    import fitz

    mine = [t for idx, t in enumerate(tasks) if idx % of == k]
    made = 0
    for pdf, g, page, i in mine:
        target = OUT / g / f"p{page:04d}.jpg"
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        doc = fitz.open(pdf)
        try:
            zoom = 150 / 72.0
            pix = doc.load_page(i).get_pixmap(matrix=fitz.Matrix(zoom, zoom))
            pix.save(str(target), jpg_quality=82)
        finally:
            doc.close()
        made += 1
    return made


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--plan", action="store_true")
    ap.add_argument("--slice", type=int, default=None)
    ap.add_argument("--of", type=int, default=8)
    args = ap.parse_args(argv)
    tasks = plan()
    if args.slice is None:
        return 0
    print(f"切 {args.slice}/{args.of}：本片 {sum(1 for idx, _ in enumerate(tasks) if idx % args.of == args.slice)} 页",
          flush=True)
    print("渲染完成", render_slice(tasks, args.slice, args.of), "页", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
