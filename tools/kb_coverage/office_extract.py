# -*- coding: utf-8 -*-
"""Office/文本源文件 → 提取块（chunk）抽取器。Phase 2 第一段：只抽不判。

## 它消灭的失败

9400+ 个 docx/pptx/txt 无法一次性"读完再判"：需要把每个文件切成带出处定位的
块（heading 感知），落成可续跑的 JSONL 工作队列；抽不到的文件进 ERROR 状态
（提取状态机），而不是静默丢失。

## 边界

- 本模块**只做确定性抽取**（切块/过滤/定位），不做语义判定——判定（归属哪个
  节点、写什么材料字段）由模型语义通道逐批完成（kb-semantic-judgment-by-agent）。
- 幂等：同一 (rel_path, chunk_id) 只入队一次；重跑补新文件，不重复旧块。
- .doc（旧二进制格式）无解析器 → 标 ERROR（note 说明），不猜。

## 用法

    PYTHONPATH=tools python -m kb_coverage.office_extract --report
    PYTHONPATH=tools python -m kb_coverage.office_extract --subject MATH --limit 20
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import zipfile
from pathlib import Path

from kb_coverage import extraction_state as es

CHUNKS = Path(__file__).resolve().parent / "tables" / "extracted_chunks.jsonl"
ROOT = Path.home() / "Desktop" / "知识库原始数据资料"
MAX_CHARS = 700
MIN_CJK = 20

CJK = re.compile(r"[\u4e00-\u9fff]")


def _cjk_len(text: str) -> int:
    return len(CJK.findall(text))


def _is_junk(text: str) -> bool:
    """页码/目录点线/碎片：没有足够正文内容的块不入队。"""
    if _cjk_len(text) < MIN_CJK:
        return True
    # 目录点线行占比过高
    dotted = re.findall(r"[.·…]{4,}", text)
    if sum(len(d) for d in dotted) > len(text) * 0.5:
        return True
    return False


def _flush(buf: list[str], heading: str, out: list[dict], idx: list[int]):
    text = "\n".join(buf).strip()
    if not text:
        return
    body = f"{heading}\n{text}" if heading else text
    if _is_junk(body):
        return
    while len(body) > MAX_CHARS:
        cut = body.rfind("\n", 200, MAX_CHARS)
        if cut < 200:
            cut = MAX_CHARS
        idx[0] += 1
        out.append({"heading": heading, "text": body[:cut]})
        body = body[cut:]
    if body.strip():
        idx[0] += 1
        out.append({"heading": heading, "text": body})


def _extract_docx(path: Path) -> list[dict]:
    from docx import Document
    doc = Document(str(path))
    out: list[dict] = []
    idx = [0]
    buf: list[str] = []
    heading = ""
    for p in doc.paragraphs:
        t = p.text.strip()
        if not t:
            continue
        style = (p.style.name or "") if p.style is not None else ""
        if style.lower().startswith(("heading", "标题")) or style == "Title":
            _flush(buf, heading, out, idx)
            buf, heading = [], t
        else:
            buf.append(t)
    _flush(buf, heading, out, idx)
    return out


def _extract_pptx(path: Path) -> list[dict]:
    out: list[dict] = []
    idx = [0]
    with zipfile.ZipFile(path) as zf:
        slides = sorted(
            (n for n in zf.namelist() if re.match(r"ppt/slides/slide\d+\.xml$", n)),
            key=lambda n: int(re.search(r"slide(\d+)\.xml", n).group(1)),
        )
        for sn in slides:
            xml = zf.read(sn).decode("utf-8", "ignore")
            texts = re.findall(r"<a:t>(.*?)</a:t>", xml, re.S)
            text = "\n".join(t.strip() for t in texts if t.strip())
            heading = f"slide {sn[-7:-4]}"
            _flush([text], heading, out, idx)
    return out


def _extract_pdf(path: Path) -> list[dict]:
    """文本层 PDF 逐页抽文本（fitz）。扫描件不走这里（PENDING_SCANNED）。"""
    import fitz
    out: list[dict] = []
    idx = [0]
    with fitz.open(str(path)) as doc:
        for pno in range(doc.page_count):
            text = doc.load_page(pno).get_text("text")
            _flush([text], f"p{pno + 1}", out, idx)
    return out


def _extract_texty(path: Path) -> list[dict]:
    raw = None
    for enc in ("utf-8", "gbk"):
        try:
            raw = path.read_text(encoding=enc)
            break
        except (UnicodeDecodeError, UnicodeError):
            continue
    if raw is None:
        raise ValueError("text decode failed")
    if path.suffix == ".html":
        raw = re.sub(r"<(script|style).*?</\1>", "", raw, flags=re.S)
        raw = re.sub(r"<[^>]+>", "\n", raw)
    out: list[dict] = []
    idx = [0]
    for para in re.split(r"\n\s*\n", raw):
        _flush([para.strip()], "", out, idx)
    return out


def extract_file(rel_path: str, subject: str) -> list[dict]:
    """单个源文件 → 块列表。块带 rel_path（判定阶段据此取 subject/出处）。"""
    path = ROOT / rel_path
    ext = path.suffix.lower()
    if ext == ".docx":
        chunks = _extract_docx(path)
    elif ext == ".pptx":
        chunks = _extract_pptx(path)
    elif ext == ".pdf":
        chunks = _extract_pdf(path)
    elif ext in (".txt", ".html"):
        chunks = _extract_texty(path)
    elif ext in (".doc", ".pps"):
        raise ValueError(f"legacy binary {ext}: no parser (mark ERROR)")
    else:
        raise ValueError(f"unsupported ext {ext}")
    h = hashlib.sha256(rel_path.encode("utf-8")).hexdigest()[:10]
    for i, c in enumerate(chunks, 1):
        c["rel_path"] = rel_path
        c["subject"] = subject
        c["chunk_id"] = f"{h}-{i:03d}"
        c["fp"] = _fp(c["text"])
    return chunks


def _fp(text: str) -> str:
    """内容指纹：只取 CJK 字符后哈希——学生版/教师版排版差异不挡住去重。"""
    return hashlib.sha256("".join(CJK.findall(text)).encode("utf-8")).hexdigest()


def load_chunks() -> list[dict]:
    if not CHUNKS.exists():
        return []
    return [json.loads(l) for l in CHUNKS.read_text(encoding="utf-8").splitlines() if l.strip()]


def _seen_keys() -> tuple[set[tuple[str, str]], set[str]]:
    keys: set[tuple[str, str]] = set()
    fps: set[str] = set()
    for c in load_chunks():
        keys.add((c["rel_path"], c["chunk_id"]))
        fps.add(c.get("fp", ""))
    return keys, fps


def run(subject: str | None, limit: int | None) -> dict:
    states = es.load_states()
    seen, seen_fps = _seen_keys()
    files = [r for r in states.values()
             if r["state"] == "PENDING" and r["ext"] in (".docx", ".pptx", ".txt", ".html", ".doc", ".pdf")
             and (subject is None or r["subject"] == subject)]
    files.sort(key=lambda r: r["rel_path"])
    if limit:
        files = files[:limit]
    stats = {"scanned": 0, "ok": 0, "error": 0, "chunks": 0, "deduped": 0, "skipped_seen": 0}
    with CHUNKS.open("a", encoding="utf-8") as fh:
        for r in files:
            stats["scanned"] += 1
            rel = r["rel_path"]
            try:
                chunks = extract_file(rel, r["subject"])
            except Exception as e:  # noqa: BLE001 - 单文件失败不能中断整批
                es.mark({rel: ("ERROR", str(e)[:80])}, "office_extract")
                stats["error"] += 1
                continue
            new = [c for c in chunks
                   if (c["rel_path"], c["chunk_id"]) not in seen and c["fp"] not in seen_fps]
            for c in new:
                fh.write(json.dumps(c, ensure_ascii=False) + "\n")
                seen_fps.add(c["fp"])
            stats["chunks"] += len(new)
            stats["deduped"] += len(chunks) - len(new)
            if not new and chunks:
                stats["skipped_seen"] += 1
            stats["ok"] += 1
    return stats


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--subject", default=None)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--run", action="store_true", help="执行抽取（默认不跑，防止误触发全量）")
    args = ap.parse_args(argv)
    if not args.run and (args.report or (args.subject is None and args.limit is None)):
        chunks = load_chunks()
        by: dict[str, int] = {}
        for c in chunks:
            by[c["subject"]] = by.get(c["subject"], 0) + 1
        print(f"块总数 {len(chunks)}；按科: {by}")
        states = es.load_states()
        pend = sum(1 for r in states.values() if r["state"] == "PENDING"
                   and r["ext"] in (".docx", ".pptx", ".txt", ".html", ".doc"))
        err = sum(1 for r in states.values() if r["state"] == "ERROR")
        print(f"可抽文件 PENDING {pend}；ERROR {err}")
        return 0
    stats = run(args.subject, args.limit)
    print(stats)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
