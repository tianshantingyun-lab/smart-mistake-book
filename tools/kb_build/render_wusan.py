# -*- coding: utf-8 -*-
"""把精讲册 PDF 渲染成页图 PNG，供视觉抽取。

产物落在 build/wusan-render/（**/build/ 在 .gitignore 内），并输出 manifest，
让抽取步骤能按页迭代、按页回写定位。

为什么必须渲染：精讲册是纯扫描件（单页一张 4763x6736 内嵌图，无文本层），
文字与公式都在图里，只能靠视觉读。

用法：
  PYTHONPATH=tools python -m kb_build.render_wusan --subject physics --pages 1-20
  PYTHONPATH=tools python -m kb_build.render_wusan --subject physics           # 全本
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from kb_build import pack_io

PDF_DIR = Path(r"C:/Users/听云/Desktop/知识库原始数据资料/27版五三")
PDF_SUBDIR = "2027《53高考总复习A版》9科 精练册&精讲册"
BOOKS = {
    "math": "2027《53高考总复习A版》数学精讲册.pdf",
    "physics": "2027《53高考总复习A版》物理精讲册.pdf",
    "chemistry": "2027《53高考总复习A版》化学精讲册.pdf",
    "biology": "2027《53高考总复习A版》生物精讲册.pdf",
}
OUT_ROOT = pack_io.REPO / "build" / "wusan-render"
# 长边目标像素：密排中文 + 公式，低于 2000 会读错上下标
TARGET_LONG_SIDE = 2600


def parse_pages(spec: str, total: int) -> list[int]:
    """`1-20` / `3,7,9` / 空 = 全部。返回 0-based 页索引。"""
    if not spec:
        return list(range(total))
    pages: list[int] = []
    for part in spec.split(","):
        part = part.strip()
        match = re.fullmatch(r"(\d+)\s*-\s*(\d+)", part)
        if match:
            start, end = int(match.group(1)), int(match.group(2))
            pages.extend(range(start - 1, min(end, total)))
        elif part.isdigit():
            pages.append(int(part) - 1)
    return sorted({p for p in pages if 0 <= p < total})


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--subject", required=True, choices=sorted(BOOKS))
    parser.add_argument("--pages", default="", help="如 1-20 或 3,7,9；默认全本")
    parser.add_argument("--dpi-long-side", type=int, default=TARGET_LONG_SIDE)
    parser.add_argument("--format", choices=("png", "jpeg"), default="png",
                        help="jpeg 体积约为 png 的 1/8，整本渲染时用")
    parser.add_argument("--quality", type=int, default=92)
    args = parser.parse_args(argv)

    import fitz  # 延迟导入，便于无依赖环境下只跑 --help

    pdf = PDF_DIR / PDF_SUBDIR / BOOKS[args.subject]
    if not pdf.exists():
        print(f"! 找不到 {pdf}")
        return 2

    out_dir = OUT_ROOT / args.subject
    out_dir.mkdir(parents=True, exist_ok=True)

    with fitz.open(pdf) as doc:
        wanted = parse_pages(args.pages, doc.page_count)
        rendered: list[dict] = []
        for index in wanted:
            page = doc[index]
            rect = page.rect
            zoom = args.dpi_long_side / max(rect.width, rect.height)
            pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
            ext = "png" if args.format == "png" else "jpg"
            path = out_dir / f"p{index + 1:04d}.{ext}"
            if args.format == "png":
                pix.save(path)
            else:
                pix.pil_save(path, format="JPEG", quality=args.quality, optimize=True)
            rendered.append({
                "subject": args.subject.upper(),
                "page": index + 1,
                "path": str(path.relative_to(pack_io.REPO)).replace("\\", "/"),
                "width": pix.width,
                "height": pix.height,
                "bytes": path.stat().st_size,
            })
        total_pages = doc.page_count

    manifest = OUT_ROOT / f"manifest-{args.subject}.json"
    manifest.write_text(json.dumps({
        "subject": args.subject.upper(),
        "source_pdf": str(pdf).replace("\\", "/"),
        "source_id": f"registry:wusan:2027:a-version-jingjiang:{args.subject}",
        "total_pages": total_pages,
        "rendered": rendered,
    }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    sizes = [r["bytes"] for r in rendered]
    avg = sum(sizes) / max(1, len(sizes))
    print(f"渲染 {len(rendered)}/{total_pages} 页 -> {out_dir}")
    print(f"  尺寸样例 {rendered[0]['width']}x{rendered[0]['height']}  平均 {avg/1024:.0f} KB/页  合计 {sum(sizes)/1e6:.1f} MB")
    print(f"  manifest: {manifest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
