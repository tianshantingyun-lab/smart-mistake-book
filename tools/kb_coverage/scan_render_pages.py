# -*- coding: utf-8 -*-
"""2027版《53知识清单》扫描件 → 逐页渲染成图片（视觉转写的中间产物）。

为什么单独一个工具：这四本是**扫描彩色 PDF**（无文本层，CJK 采样为 0），
走不了 `office_extract` 的文本抽取通道，只能渲染成页图交给视觉转写子代理。
本工具只做确定性的"切页"：把每本 PDF 的每一页渲染成 JPEG，落一个 manifest，
供转写代理按页号取图；不判定、不抽取、不写知识库。

幂等：已渲染的页跳过。中间产物落在 `build/`（gitignore，不入库）；
转写出的文字才是"存好"的交付物（进 extracted_chunks.jsonl）。

用法：
    python tools/kb_coverage/scan_render_pages.py --root <源目录> [--dpi 160] [--max-pages 4]
    （--max-pages 仅用于冒烟测试，正常跑不传）
manifest 写到 <out>/manifest.json，同时打印到 stdout（供 world.run 解析）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    import fitz  # PyMuPDF
except Exception as e:  # noqa: BLE001
    print(json.dumps({"error": f"PyMuPDF 不可用：{e}"}, ensure_ascii=False))
    raise SystemExit(2)

SUBJECTS = [
    ("2027版高中《53知识清单》彩色版（数学）", "MATH"),
    ("2027版高中《53知识清单》彩色版（物理）", "PHYSICS"),
    ("2027版高中《53知识清单》彩色版（化学）", "CHEMISTRY"),
    ("2027版高中《53知识清单》彩色版（生物）", "BIOLOGY"),
]


def render(root: Path, out: Path, dpi: int, max_pages: int | None) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    matrix = fitz.Matrix(dpi / 72, dpi / 72)
    manifest = {"root": str(root), "out": str(out), "dpi": dpi, "subjects": []}
    for top_dir, subject in SUBJECTS:
        dir_ = root / top_dir
        if not dir_.is_dir():
            manifest["subjects"].append({"subject": subject, "error": f"目录缺失 {top_dir}"})
            continue
        pdfs = sorted(dir_.glob("*.pdf"))
        if not pdfs:
            manifest["subjects"].append({"subject": subject, "error": f"{top_dir} 下无 PDF"})
            continue
        for pdf in pdfs:
            book_dir = out / subject / pdf.stem
            book_dir.mkdir(parents=True, exist_ok=True)
            doc = fitz.open(pdf)
            total = doc.page_count
            pages = []
            rendered = skipped = 0
            for i in range(total):
                page_no = i + 1
                if max_pages is not None and page_no > max_pages:
                    break
                img = book_dir / f"p{page_no:04d}.jpg"
                if img.exists() and img.stat().st_size > 0:
                    skipped += 1
                else:
                    pix = doc[i].get_pixmap(matrix=matrix)
                    pix.save(str(img), jpg_quality=82)
                    rendered += 1
                pages.append({"page": page_no, "image": str(img)})
            doc.close()
            manifest["subjects"].append({
                "subject": subject, "top_dir": top_dir,
                "pdf_rel": f"{top_dir}/{pdf.name}", "pdf_name": pdf.name,
                "total_pages": total, "rendered": rendered, "skipped": skipped,
                "image_dir": str(book_dir), "pages": pages,
            })
            print(f"[{subject}] {pdf.name}: {total} 页（新渲染 {rendered}，复用 {skipped}）",
                  file=sys.stderr)
    return manifest


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", type=Path,
                    default=Path.home() / "Desktop" / "最全知识库原始资料")
    ap.add_argument("--out", type=Path, default=Path("build") / "2027-53-pages")
    ap.add_argument("--dpi", type=int, default=160)
    ap.add_argument("--max-pages", type=int, default=None)
    args = ap.parse_args(argv)
    manifest = render(args.root, args.out, args.dpi, args.max_pages)
    (args.out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    # stdout 可能被 PyMuPDF 的弃用 warning 污染（import fitz 时打到 stdout），
    # 所以下游脚本改读这个精简 manifest 文件，不解析 stdout。
    slim = {**{k: v for k, v in manifest.items() if k != "subjects"},
            "subjects": [{k: v for k, v in s.items() if k != "pages"} for s in manifest["subjects"]]}
    (args.out / "manifest_slim.json").write_text(
        json.dumps(slim, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(slim, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
