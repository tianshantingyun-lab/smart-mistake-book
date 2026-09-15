# -*- coding: utf-8 -*-
"""源资料清单：把"知识库的输入到底是什么"变成一份可复算的表。

存在的理由：在这份表出现之前，"桌面源目录里还有哪些资料没被处理过"无法回答——
`knowledge-research/candidates/` 的记录是家族级的（如 `registry:kb:math:handout`），
逐文件覆盖状态无处可查。覆盖率的分母必须来自本表的 `content_bearing=yes` 行。

本模块只做登记，不做判定：不猜某个文件"应该"产出多少知识点，也不动任何内容。

判定规则（全部可复算）：
- `content_bearing`：扩展名在 `CONTENT_EXTS` 内即算内容载体。字体/视频/可执行/图片
  （复习资料的配图与字体不承载知识点）一律 no。图片型 PDF 仍算 yes——它承载内容，
  只是需要视觉转录，这是"要不要处理"的问题，不是"是不是内容"的问题。
- PDF 的 `text_layer`：在整本页数上均匀取 `PDF_SAMPLE_PAGES` 页，统计中日韩字符数。
  扫描件与纯图页的 CJK 计数为 0（水印是 ASCII，不计入）；有文本层的中文 PDF 会显著大于 0。
  阈值取 50——远高于单个水印串能产生的量，又远低于任何一页真实正文。
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO: Path = Path(__file__).resolve().parents[2]
OUT_DIR: Path = REPO / "tools" / "kb_coverage"

DEFAULT_SOURCE_ROOT = Path(r"C:\Users\听云\Desktop\知识库原始数据资料")
INVENTORY_NAME = "source_inventory.csv"

CONTENT_EXTS = frozenset({".docx", ".doc", ".pptx", ".pps", ".pdf", ".xmind", ".emmx"})
PDF_SAMPLE_PAGES = 5
CJK_TEXT_THRESHOLD = 50

FIELDS = [
    "rel_path",
    "top_dir",
    "ext",
    "bytes",
    "content_bearing",
    "pages",
    "text_cjk_sample",
    "text_layer",
]


@dataclass
class PdfVerdict:
    pages: int = 0
    cjk: int = 0
    verdict: str = "UNREADABLE"


def _cjk_count(text: str) -> int:
    return sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")


def _probe_pdf(path: Path, sample_pages: int) -> PdfVerdict:
    """取若干页判断有没有文本层。任何一个异常都归为 UNREADABLE，不让整轮中断。"""
    try:
        import pymupdf as fitz  # PyMuPDF ≥1.24 的新包名
    except ImportError:
        try:
            import fitz  # 旧包名，仍可用
        except ImportError:  # pragma: no cover - 环境缺失时降级，不伪装成功
            return PdfVerdict(verdict="NO_PYMUPDF")
    try:
        with fitz.open(path) as doc:
            total = doc.page_count
            if total <= 0:
                return PdfVerdict(verdict="UNREADABLE")
            if total <= sample_pages:
                indexes = range(total)
            else:
                step = total / sample_pages
                indexes = sorted({int(i * step) for i in range(sample_pages)} | {total - 1})
            cjk = 0
            for i in indexes:
                cjk += _cjk_count(doc.load_page(i).get_text())
            verdict = "TEXT_LAYER" if cjk >= CJK_TEXT_THRESHOLD else "SCANNED_IMAGE"
            return PdfVerdict(pages=total, cjk=cjk, verdict=verdict)
    except Exception:
        return PdfVerdict(verdict="UNREADABLE")


@dataclass
class Row:
    rel_path: str
    top_dir: str
    ext: str
    size: int
    content_bearing: str
    pages: str = ""
    cjk: str = ""
    text_layer: str = ""


def scan(root: Path, sample_pages: int) -> list[Row]:
    rows: list[Row] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root)
        parts = rel.parts
        ext = path.suffix.lower()
        content = "yes" if ext in CONTENT_EXTS else "no"
        row = Row(
            rel_path="/".join(parts),
            top_dir=parts[0] if len(parts) > 1 else "",
            ext=ext,
            size=path.stat().st_size,
            content_bearing=content,
        )
        if ext == ".pdf":
            verdict = _probe_pdf(path, sample_pages)
            row.pages = str(verdict.pages) if verdict.pages else ""
            row.cjk = str(verdict.cjk) if verdict.pages else ""
            row.text_layer = verdict.verdict
        rows.append(row)
    return rows


def write_csv(rows: list[Row], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh, lineterminator="\n")
        writer.writerow(FIELDS)
        for r in rows:
            writer.writerow(
                [r.rel_path, r.top_dir, r.ext, r.size, r.content_bearing, r.pages, r.cjk, r.text_layer]
            )


def summarize(rows: list[Row]) -> str:
    content = [r for r in rows if r.content_bearing == "yes"]
    pdfs = [r for r in rows if r.ext == ".pdf"]
    by_verdict: dict[str, int] = {}
    for r in pdfs:
        by_verdict[r.text_layer] = by_verdict.get(r.text_layer, 0) + 1
    lines = [
        f"文件总数       {len(rows)}",
        f"内容载体       {len(content)}",
        f"总字节         {sum(r.size for r in rows):,}",
        f"内容载体字节   {sum(r.size for r in content):,}",
        f"PDF            {len(pdfs)}",
    ]
    for k in sorted(by_verdict):
        lines.append(f"  PDF {k:<14} {by_verdict[k]}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="登记知识库源资料目录")
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--output", type=Path, default=OUT_DIR / INVENTORY_NAME)
    parser.add_argument("--sample-pages", type=int, default=PDF_SAMPLE_PAGES)
    args = parser.parse_args(argv)

    root: Path = args.source_root
    if not root.is_dir():
        print(f"ERROR: 源目录不存在：{root}", file=sys.stderr)
        return 2

    rows = scan(root, args.sample_pages)
    write_csv(rows, args.output)
    print(summarize(rows))
    print(f"\n→ {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
