# -*- coding: utf-8 -*-
"""判定"分节扫描件是否整本书的页面拆分"——用页图 aHash 比对，不靠推测。

## 它消灭的失败

五三A版【01】专题资料包里有按节拆的 5_精讲册PDF/*.pdf（每节 2–6 页）。它们看起来
就是整本精讲册的同页拆分；如果真是，重新视觉转写就是纯粹浪费（用户明确要求
"扫过的不要重复扫了"）。反过来，如果只是同名不同内容，误判为重复会丢知识点。
两条路都错，所以必须用可复核的页图指纹来判定，而不是看文件名。

判据：取分节 PDF 的抽样页，算 8×8 aHash，与整本书页图（build/wusan-render/<subj>/）
全量比对，取最小汉明距离。距离 ≤ MATCH_MAX 记 MATCH（同页），否则 NO_MATCH。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RENDER = ROOT / "build" / "render"
WUSAN_RENDER = ROOT / "build" / "wusan-render"
MATCH_MAX = 8


def ahash(path: Path) -> str | None:
    from PIL import Image

    try:
        img = Image.open(path).convert("L").resize((8, 8))
    except Exception:
        return None
    px = list(img.getdata())
    avg = sum(px) / len(px)
    return "".join("1" if p > avg else "0" for p in px)


def hamming(a: str, b: str) -> int:
    return sum(1 for x, y in zip(a, b) if x != y)


def load_book_hashes(subject: str) -> dict[str, str]:
    d = WUSAN_RENDER / subject
    out: dict[str, str] = {}
    for p in sorted(d.glob("p*.jpg")):
        h = ahash(p)
        if h:
            out[p.name] = h
    return out


def probe(pdf: Path, out_dir: Path, pages: list[int] | None = None, dpi: int = 100) -> list[Path]:
    import fitz

    out_dir.mkdir(parents=True, exist_ok=True)
    made = []
    with fitz.open(str(pdf)) as doc:
        idxs = pages or list(range(min(3, doc.page_count)))
        zoom = dpi / 72.0
        mat = fitz.Matrix(zoom, zoom)
        for pno in idxs:
            if pno >= doc.page_count:
                continue
            t = out_dir / f"probe-{pdf.stem[:24]}-p{pno + 1:04d}.png"
            if not t.exists():
                doc.load_page(pno).get_pixmap(matrix=mat).save(str(t))
            made.append(t)
    return made


def mad(pa: Path, pb: Path) -> float | None:
    """灰度平均绝对差（0–255）。同一页两路渲染的 JPEG 噪声应 < ~5；不同页 > ~15。"""
    from PIL import Image, ImageChops

    a = Image.open(pa).convert("L").resize((256, 362))
    b = Image.open(pb).convert("L").resize((256, 362))
    diff = ImageChops.difference(a, b)
    hist = diff.histogram()
    total = sum(hist)
    return sum(i * c for i, c in enumerate(hist)) / total


def compare(pdf: Path, subject: str, book_dir: Path, probes_dir: Path, dpi: int = 150) -> dict:
    """对每个抽样页，与整本书页图逐一算 MAD，取 top3；判定依据"唯一明显胜出"。"""
    res = {"pdf": str(pdf), "subject": subject, "pages": [], "verdict": "NO_MATCH"}
    book_pages = sorted(book_dir.glob("p*.jpg"))
    for img in probe(pdf, probes_dir, dpi=dpi):
        scored = []
        for bp in book_pages:
            d = mad(img, bp)
            if d is not None:
                scored.append((round(d, 2), bp.name))
        scored.sort()
        top = scored[:3]
        res["pages"].append({"probe": img.name, "top3": top})
    # 判定：每个抽样页的最佳匹配都 ≤ 5，且第二好与最好差距 ≥ 2×（唯一胜出）
    ok = bool(res["pages"])
    for pg in res["pages"]:
        if len(pg["top3"]) < 2:
            ok = False
            break
        best, second = pg["top3"][0][0], pg["top3"][1][0]
        if not (best <= 5.0 and second >= best * 2):
            ok = False
            break
    res["verdict"] = "MATCH" if ok else "NO_MATCH"
    return res


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pdfs", nargs="+", type=Path, required=True)
    ap.add_argument("--subject", required=True, choices=["math", "physics", "chemistry", "biology"])
    ap.add_argument("--dpi", type=int, default=150)
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args(argv)
    book_dir = WUSAN_RENDER / args.subject
    if not any(book_dir.glob("p*.jpg")):
        print(f"!! build/wusan-render/{args.subject} 无页图，无法比对")
        return 2
    probes_dir = RENDER / "_probe"
    reports = [compare(p, args.subject, book_dir, probes_dir, args.dpi) for p in args.pdfs]
    for r in reports:
        print(f"{r['verdict']:>8} {Path(r['pdf']).name[:64]}")
        for pg in r["pages"]:
            print(f"          {pg['probe'][:44]} → {pg['top3']}")
    if args.out:
        args.out.write_text(json.dumps(reports, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
