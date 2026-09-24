# -*- coding: utf-8 -*-
"""P0 版面切块：把一页扫描图切成"能放大读"的横条，并给出**带列序的读法标签**。

## 它消灭的失败

整页读会看错符号（实测：`⇌` 被读成 `→`，因为整页缩到一张图里反应箭头只有几个像素）。
但"随便切块"会引入另一种错——**丢列序**：上一轮把两栏页从左到右、从上到下平铺切，
代理按文件名顺序读，于是左栏下半段与右栏上半段被拼在一起，语义串行（实测翻车）。
所以切完必须回答"按什么顺序读"，而不是只给一堆图。

三件事都是确定性的（不调模型、可复算）：

1. **裁掉空白边**：行投影找出首末有墨迹的行，上下各留 `PAD_PX`，免得每条都带半页白边；
2. **找栏**：垂直投影在页面中部找"够宽且足够空"的谷。**只有**谷宽 ≥ `GUTTER_MIN_PX`、
   谷内墨迹 ≤ `GUTTER_MAX_INK`、且两侧各有实墨迹时才判两栏，否则单栏（宁少判不多判——
   误判两栏会把一整行文字劈成两半）；
3. **切条**：在 `[MIN_BAND_PX, MAX_BAND_PX]` 窗口里挑**墨迹最少的那一行**做切点
   （不切穿文字行），相邻条按 `OVERLAP` 重叠，最后一条吃到页尾。

每条的图**从 PDF 原始页按 `clip` 以 `TILE_DPI` 重渲染**（不是把 160dpi 的整页图放大——
放大不会多出信息）；读数细节因此是真提升。

产物：`build/2027-53-tiles/<subject>/pNNNN/tile_NN.jpg` + `tiles.json`
（`read_hint` 写明顺序：单栏自上而下；两栏先左栏自上而下、再右栏自上而下）。
幂等：`tiles.json` 已存在且计划一致即跳过（`--force` 重切）。

用法：
    python tools/kb_coverage/tile_page.py --subject CHEMISTRY --page 2
    python tools/kb_coverage/tile_page.py --subject CHEMISTRY --pages 27,68 --force
    python tools/kb_coverage/tile_page.py --subject CHEMISTRY --page 2 --plan-only
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "build/2027-53-pages/manifest_slim.json"
PAGES = REPO / "build/2027-53-pages"
TILES = REPO / "build/2027-53-tiles"
DEFAULT_ROOT = Path.home() / "Desktop" / "最全知识库原始资料"

ANALYSIS_DPI = 160          # 与 scan_render_pages 的页图一致（投影分析用它的像素坐标）
TILE_DPI = 300              # 切条重渲染的目标 dpi（相对 160dpi 是 1.875×）
INK_LEVEL = 160             # 灰度 < 此值算墨迹
PAD_PX = 24                 # 裁空白边后上下留白
MIN_BAND_PX = 380
MAX_BAND_PX = 1000
OVERLAP = 0.10
GUTTER_MIN_PX = 40
GUTTER_MAX_INK = 0.004      # 谷内墨迹上限（占列高比例）
MIN_SIDE_INK = 0.004        # 两侧各自的最小墨迹，防止把空白页切成两栏
CLEAR_ROW_INK = 0.002       # "空白行"判据


# ---------------------------------------------------------------- 纯几何（stdlib，可单测）
def trim_span(row_ink: list[float], pad: int = PAD_PX) -> tuple[int, int]:
    """返回 [y0, y1)（半开）：首末有墨迹的行外扩 pad，不越界。整页空白则返回整页。"""
    n = len(row_ink)
    first = next((i for i, v in enumerate(row_ink) if v > CLEAR_ROW_INK), None)
    if first is None:
        return 0, n
    last = next((i for i in range(n - 1, -1, -1) if row_ink[i] > CLEAR_ROW_INK), n - 1)
    return max(0, first - pad), min(n, last + 1 + pad)


def find_columns(col_ink: list[float], width: int) -> list[tuple[int, int]]:
    """按垂直投影找栏；返回 [(x0, x1), ...]，单栏时是 [(0, width)]。"""
    lo, hi = int(width * 0.30), int(width * 0.70)
    best = (0, 0)                     # (起点, 宽度)：最宽的谷
    run_start = None
    for x in range(lo, hi):
        if col_ink[x] <= GUTTER_MAX_INK:
            if run_start is None:
                run_start = x
        else:
            if run_start is not None and x - run_start > best[1]:
                best = (run_start, x - run_start)
            run_start = None
    if run_start is not None and hi - run_start > best[1]:
        best = (run_start, hi - run_start)
    if best[1] < GUTTER_MIN_PX:
        return [(0, width)]
    gx0, gw = best
    left, right = col_ink[:gx0], col_ink[gx0 + gw:]
    # 两侧都要有实墨迹：否则这是"半页空白"，不是两栏
    if not left or not right:
        return [(0, width)]
    if (sum(left) / len(left) < MIN_SIDE_INK) or (sum(right) / len(right) < MIN_SIDE_INK):
        return [(0, width)]
    return [(0, gx0), (gx0 + gw, width)]


def find_bands(row_ink: list[float], y0: int, y1: int,
               min_band: int = MIN_BAND_PX, max_band: int = MAX_BAND_PX,
               overlap: float = OVERLAP) -> list[tuple[int, int]]:
    """在 [y0, y1) 里切横条：切点取窗口内墨迹最少的行，相邻条重叠 overlap。

    末尾不足半条高度的残余**并进上一条**，不单独成条——实测 61px / 83px 的尾条只是
    上一条的重复（多一次读图、多一次抄写机会），对转写没有增量。
    """
    if y1 - y0 <= min_band:
        return [(y0, y1)] if y1 > y0 else []
    last = len(row_ink) - 1
    bands: list[tuple[int, int]] = []
    start = y0
    while y1 - start > min_band:
        win_lo = min(start + min_band, y1)
        win_hi = min(start + max_band, y1)
        cut = min(range(win_lo, win_hi + 1), key=lambda r: row_ink[min(r, last)])
        bands.append((start, cut))
        nxt = cut - int((cut - start) * overlap)
        start = nxt if nxt > start else cut        # 保证单调前进
    if y1 - start > 0:
        if bands and y1 - start <= min_band * 0.5:
            bands[-1] = (bands[-1][0], y1)         # 尾条太短：并进上一条
        else:
            bands.append((start, y1))
    return bands


def plan_tiles(row_ink: list[float], col_ink: list[float], height: int, width: int) -> dict:
    """把"该页怎么切"算成一份计划（列 → 条 → 读序）。"""
    y0, y1 = trim_span(row_ink)
    cols = find_columns(col_ink, width)
    tiles = []
    for ci, (x0, x1) in enumerate(cols):
        for b0, b1 in find_bands(row_ink, y0, y1):
            tiles.append({"file": f"tile_{len(tiles):02d}.jpg", "order": len(tiles), "col": ci,
                          "x0": x0, "x1": x1, "y0": b0, "y1": b1})
    hint = ("单栏：自上而下按 order 顺序读。" if len(cols) == 1 else
            "两栏：先左栏自上而下读完，再右栏自上而下（order 顺序即此序）。")
    return {"px": {"height": height, "width": width}, "analysis_dpi": ANALYSIS_DPI,
            "tile_dpi": TILE_DPI, "trim": [y0, y1], "columns": cols, "tiles": tiles,
            "read_hint": hint}


# ---------------------------------------------------------------- 图像 / PDF（按需导入）
def ink_profile(img_path: Path) -> tuple[list[float], list[float]]:
    """返回 (每行墨迹占比, 每列墨迹占比)。"""
    import numpy as np
    from PIL import Image

    a = np.asarray(Image.open(img_path).convert("L"))
    ink = a < INK_LEVEL
    return ink.mean(axis=1).tolist(), ink.mean(axis=0).tolist()


def stale_tiles(out_dir: Path, plan: dict) -> list[Path]:
    """上一版计划留下、这一版不再需要的块文件（会被当成页内容重复转写，必须删）。"""
    if not out_dir.is_dir():
        return []
    keep = {t["file"] for t in plan["tiles"]}
    return sorted(p for p in out_dir.glob("tile_*.jpg") if p.name not in keep)


def render_tiles(pdf: Path, page_no: int, plan: dict, out_dir: Path) -> int:
    """按计划从 PDF 原页重渲染每条；返回写出的张数。"""
    import fitz

    for old in stale_tiles(out_dir, plan):       # 先清旧：重切后块数变少时尤须清
        old.unlink()
    doc = fitz.open(pdf)
    page = doc[page_no - 1]
    scale = 72.0 / plan["analysis_dpi"]          # px(@analysis_dpi) → pt
    matrix = fitz.Matrix(plan["tile_dpi"] / 72, plan["tile_dpi"] / 72)
    n = 0
    for t in plan["tiles"]:
        clip = fitz.Rect(t["x0"] * scale, t["y0"] * scale, t["x1"] * scale, t["y1"] * scale)
        page.get_pixmap(matrix=matrix, clip=clip).save(
            str(out_dir / t["file"]), jpg_quality=92)
        n += 1
    doc.close()
    return n


def load_manifest() -> dict:
    if not MANIFEST.exists():
        raise SystemExit(f"缺 manifest：{MANIFEST}（先跑 scan_render_pages.py）")
    return {s["subject"]: s for s in json.loads(MANIFEST.read_text(encoding="utf-8"))["subjects"]}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--subject", required=True)
    ap.add_argument("--page", type=int)
    ap.add_argument("--pages", help="逗号分隔的多页，如 27,68,94")
    ap.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="源根（pdf_rel 的基准目录）")
    ap.add_argument("--out", type=Path, default=TILES)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--plan-only", action="store_true", help="只算计划，不渲染")
    args = ap.parse_args(argv)

    book = load_manifest().get(args.subject)
    if not book:
        print(f"未知学科 {args.subject}", file=sys.stderr)
        return 2
    if args.pages:
        pages = [int(p) for p in args.pages.split(",") if p.strip()]
    elif args.page:
        pages = [args.page]
    else:
        print("要给 --page 或 --pages", file=sys.stderr)
        return 2

    src_pdf = args.root / book["pdf_rel"]
    stem = Path(book["pdf_name"]).stem
    summary = []
    for page in pages:
        img = PAGES / args.subject / stem / f"p{page:04d}.jpg"
        if not img.exists():
            print(f"缺页图 {img}", file=sys.stderr)
            return 3
        row_ink, col_ink = ink_profile(img)
        plan = plan_tiles(row_ink, col_ink, len(row_ink), len(col_ink))
        plan.update({"subject": args.subject, "page": page, "pdf_rel": book["pdf_rel"]})
        out_dir = args.out / args.subject / f"p{page:04d}"
        meta = out_dir / "tiles.json"
        if meta.exists() and not args.force:
            old = json.loads(meta.read_text(encoding="utf-8"))
            if {k: v for k, v in old.items() if k != "rendered"} == plan:
                summary.append({"page": page, "tiles": len(plan["tiles"]),
                                "columns": len(plan["columns"]), "skipped": True,
                                "dir": str(out_dir)})
                continue
        out_dir.mkdir(parents=True, exist_ok=True)
        rendered = 0
        if not args.plan_only:
            if not src_pdf.exists():
                print(f"找不到源 PDF：{src_pdf}", file=sys.stderr)
                return 4
            rendered = render_tiles(src_pdf, page, plan, out_dir)
            plan["rendered"] = rendered
        meta.write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
        summary.append({"page": page, "tiles": len(plan["tiles"]), "columns": len(plan["columns"]),
                        "trim": plan["trim"], "rendered": rendered, "dir": str(out_dir)})
        print(f"[{args.subject} p{page}] {len(plan['columns'])} 栏 × {len(plan['tiles'])} 条"
              f"（裁边 {plan['trim']}）→ {out_dir}", file=sys.stderr)
    print(json.dumps({"subject": args.subject, "pages": summary}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
