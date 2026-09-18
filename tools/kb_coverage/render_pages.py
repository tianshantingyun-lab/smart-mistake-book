# -*- coding: utf-8 -*-
"""扫描件 PDF → 页图（fitz），供视觉转写代理 Read。断点续渲、幂等、带 manifest。

## 它消灭的失败

扫描件（SCANNED_IMAGE，8737 页）没有文本层，转写只能读图。此前只渲染过五三四科精讲册，
其余扫描件（解题觉醒/五三B版专题/二轮讲义）无页图可读。poppler CLI 在本机中文路径上
打不开文件（实测 returncode=1），必须走 fitz/pypdfium2。

## 用法

    PYTHONPATH=tools python -m kb_coverage.render_pages --pdf <路径> --out-dir <目录> [--dpi 150] [--pages 1-80]
    PYTHONPATH=tools python -m kb_coverage.render_pages --manifest          # 打印已渲染清单
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
RENDER_ROOT = ROOT / "build" / "render"


def render(pdf: Path, out_dir: Path, dpi: int = 150, page_range: str | None = None) -> dict:
    """渲染 PDF 页 → out_dir/p0001.jpg…；已存在跳过（幂等）。"""
    import fitz

    out_dir.mkdir(parents=True, exist_ok=True)
    first, last = 1, None
    if page_range:
        a, _, b = page_range.partition("-")
        first = int(a)
        last = int(b) if b else int(a)
    rendered, skipped = 0, 0
    with fitz.open(str(pdf)) as doc:
        total = doc.page_count
        last = min(last or total, total)
        zoom = dpi / 72.0
        mat = fitz.Matrix(zoom, zoom)
        for pno in range(first - 1, last):
            target = out_dir / f"p{pno + 1:04d}.jpg"
            if target.exists():
                skipped += 1
                continue
            pix = doc.load_page(pno).get_pixmap(matrix=mat)
            pix.save(str(target), jpg_quality=82)
            rendered += 1
    manifest = {
        "pdf": str(pdf),
        "out_dir": str(out_dir),
        "total_pages": total,
        "range": [first, last],
        "rendered_now": rendered,
        "skipped_existing": skipped,
        "dpi": dpi,
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    return manifest


def list_manifests() -> list[dict]:
    out = []
    for m in sorted(RENDER_ROOT.rglob("manifest.json")):
        try:
            out.append(json.loads(m.read_text(encoding="utf-8")))
        except Exception:
            continue
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pdf", type=Path, default=None)
    ap.add_argument("--out-dir", type=Path, default=None)
    ap.add_argument("--dpi", type=int, default=150)
    ap.add_argument("--pages", default=None, help="如 1-80")
    ap.add_argument("--manifest", action="store_true")
    args = ap.parse_args(argv)
    if args.manifest:
        for m in list_manifests():
            print(f"{m['total_pages']:>4}页 dpi{m['dpi']} {m['out_dir']}")
        return 0
    if not args.pdf or not args.out_dir:
        ap.error("需要 --pdf 与 --out-dir")
    m = render(args.pdf, args.out_dir, args.dpi, args.pages)
    print(json.dumps(m, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
