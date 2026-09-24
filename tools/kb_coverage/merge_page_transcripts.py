# -*- coding: utf-8 -*-
"""把"逐页转写产物"并回转写库的 range 文件（补齐与重转的落盘器）。

## 它消灭的失败

S3（补齐 358 页）与 S5（重转 18 页）的产物是**逐页**的（一页一个 JSONL，代理写一页重写一次，
中途失败不丢已完成页）；而转写库的既有布局是**按区间**的 `range_0015_0028.jsonl`，账本
（`transcription_ledger`）又会从文件名解析区间、用"名义区间 vs 实有页"判**结构性截断**。
两者若靠手抄，就会出现：页面交错、重复记录、区间名与实际内容不一致（账本据此误判截断）。

本工具是这一层的确定性落盘器：

1. 逐页产物 → 找到**该页所属的 range 文件**（按既有文件的区间名判断），把该页的记录
   按页码顺序并入；**同一页只留一份**（重转就是要替换旧稿，替换是这份工具的正当行为）；
2. 该页不属于任何既有 range 时，新建 `range_NNNN_NNNN.jsonl`（**区间名就用这一页**）——
   不让账本把它误判成"区间末尾被截断"；
3. 写入前校验：JSON 合法、每条记录带整数 `page` 且落在该文件区间内、同一页不重复；
4. 幂等：同一份产物重跑，文件逐字节相同。

用法：
    PYTHONPATH=tools python -m kb_coverage.merge_page_transcripts --from <逐页产物目录>
    PYTHONPATH=tools python -m kb_coverage.merge_page_transcripts --from <dir> --write
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TRANSCRIPTS = REPO / "knowledge-production/2027-53-transcripts"
MANIFEST = REPO / "build/2027-53-pages/manifest_slim.json"
SPAN_RE = re.compile(r"range_(\d{4})_(\d{4})\.jsonl$")


def book_dir_for(subject: str) -> Path:
    """按 manifest 的书名定位该科目录；manifest 缺失时退回"该科下唯一目录"。"""
    stem = None
    if MANIFEST.exists():
        for s in json.loads(MANIFEST.read_text(encoding="utf-8"))["subjects"]:
            if s.get("subject") == subject:
                stem = Path(s["pdf_name"]).stem
    base = TRANSCRIPTS / subject
    if stem and (base / stem).is_dir():
        return base / stem
    dirs = [d for d in base.iterdir() if d.is_dir()] if base.exists() else []
    if len(dirs) == 1:
        return dirs[0]
    raise LookupError(f"定位不到 {subject} 的书目录（manifest 缺失且目录不唯一）")


def parse_records(path: Path) -> list[dict]:
    """读一个 JSONL：容忍行尾逗号；坏行即报错（不静默跳过——那就是丢页）。"""
    out: list[dict] = []
    for ln, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        s = raw.strip()
        if not s:
            continue
        if s.endswith(","):
            s = s[:-1].rstrip()
        try:
            rec = json.loads(s)
        except json.JSONDecodeError as e:
            raise ValueError(f"{path}:L{ln} JSON 解析失败：{e.msg}") from None
        if not isinstance(rec.get("page"), int):
            raise ValueError(f"{path}:L{ln} 缺整数 page 字段")
        if not (rec.get("text") or "").strip():
            raise ValueError(f"{path}:L{ln} text 为空")
        out.append(rec)
    return out


def range_files(book_dir: Path) -> list[tuple[Path, int, int]]:
    out = []
    for f in sorted(book_dir.glob("range_*.jsonl")):
        m = SPAN_RE.search(f.name)
        if m:
            out.append((f, int(m.group(1)), int(m.group(2))))
    return out


def target_file(book_dir: Path, page: int, create: bool) -> tuple[Path, bool]:
    """返回 (文件, 是否新建)。已有区间包含该页就用它；否则新建"只含这一页"的区间。"""
    for f, a, b in range_files(book_dir):
        if a <= page <= b:
            return f, False
    if not create:
        raise LookupError(f"没有区间文件包含第 {page} 页")
    return book_dir / f"range_{page:04d}_{page:04d}.jsonl", True


def merge(book_dir: Path, page: int, recs: list[dict], write: bool) -> dict:
    path, fresh = target_file(book_dir, page, create=True)
    for r in recs:
        if r["page"] != page:
            raise ValueError(f"产物里出现第 {r['page']} 页的记录（本次只处理第 {page} 页）")
    old = parse_records(path) if path.exists() else []
    others = [r for r in old if r["page"] != page]
    replaced = len(old) - len(others)
    merged = sorted(others + recs, key=lambda r: r["page"])
    before = path.read_text(encoding="utf-8") if path.exists() else ""
    after = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in merged)
    changed = before != after
    if write and changed:
        path.write_text(after, encoding="utf-8")
    return {"page": page, "file": str(path), "created": fresh, "replaced": replaced,
            "records": len(recs), "total_in_file": len(merged), "changed": changed,
            "span": [int(SPAN_RE.search(path.name).group(1)),
                     int(SPAN_RE.search(path.name).group(2))]}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--from", dest="src", type=Path, required=True,
                    help="逐页产物目录（<学科>/pNNNN.jsonl）")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)

    if not args.src.exists():
        print(f"没有逐页产物目录：{args.src}")
        return 2
    results, problems = [], []
    for f in sorted(args.src.glob("*/p*.jsonl")):
        subject = f.parent.name
        page = int(f.stem.lstrip("p"))
        try:
            recs = parse_records(f)
            results.append(merge(book_dir_for(subject), page, recs, args.write))
        except (ValueError, LookupError) as e:
            problems.append(f"{subject}/p{page:04d}: {e}")
    new_files = [r for r in results if r["created"]]
    replaced = [r for r in results if r["replaced"]]
    print(f"并入 {len(results)} 页（新建区间文件 {len(new_files)}、替换旧稿 {len(replaced)}）")
    for r in results:
        mark = "＋新文件" if r["created"] else ("↻替换" if r["replaced"] else "＋补页")
        print(f"   {mark} p{r['page']:<4d} → {Path(r['file']).name}"
              f"（区间 {r['span'][0]}-{r['span'][1]}，文件共 {r['total_in_file']} 页，{r['records']} 条记录）")
    if problems:
        print(f"\n★ 失败 {len(problems)} 条（未并入）：")
        for p in problems[:10]:
            print("   !", p)
        return 1
    print("（未写盘；加 --write 生效）" if not args.write else "（已写盘）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
