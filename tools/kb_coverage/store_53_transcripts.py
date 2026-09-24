# -*- coding: utf-8 -*-
"""把"2027版《53知识清单》扫描件的视觉转写结果"存好（**只存切块，不写知识库**）。

## 它消灭的失败

视觉转写子代理按页产出的知识点文字若只落在 `build/` 的临时文件里，就会随清理丢失，
且和既有的块库/状态机脱节（"存好，和以前的规则一样"要求落进既有的 `extracted_chunks.jsonl`
与 `extraction_state.csv`）。本工具是转写→入块库的确定性落盘器：

- 读 `build/2027-53-transcripts/<subject>/<书>/range_*.jsonl`（子代理产物，每行
  `{heading, text, page}`），按 `fp`（内容 sha256）去重后追加到 `extracted_chunks.jsonl`；
- `chunk_id` = `sha256(rel_path)[:10]-NNN`（与 `office_extract` 同一套编号，rel_path 是
  该 PDF 相对源根的路径）；
- 给这 4 本 PDF 在 `source_inventory.csv`（**追加，不动旧行**）与 `extraction_state.csv`
  建行并标 `CHUNKED`（已切块、待语义判定）——**不是 EXTRACTED，不 materialize，不进成品包**。

## 为什么 CHUNKED 而不是 EXTRACTED

`EXTRACTED` 的语义是"已判定并入库"（`output_ref` 指向成品包材料、且会被 `verify` 反查悬空）。
本轮明确"先不要写入知识库"，所以终态停在 `CHUNKED`（已切块待判定），下一步（判定+materialize）
由后续轮次单独做。这样状态机如实反映"扫出来存好了，但还没进知识库"。

幂等：按 `fp` 去重（同一文字只入一次）、inventory/state 按 rel_path 去重（已有行不动）。
只写 `extracted_chunks.jsonl` / `source_inventory.csv` / `extraction_state.csv` 三个既有存储，
不碰成品包，不碰 `build/` 以外的临时目录。

用法：
    python tools/kb_coverage/store_53_transcripts.py --root <源根> [--dry-run]
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHUNKS = REPO / "tools/kb_coverage/tables/extracted_chunks.jsonl"
INV = REPO / "tools/kb_coverage/source_inventory.csv"
STATE = REPO / "tools/kb_coverage/tables/extraction_state.csv"
MANIFEST = REPO / "build/2027-53-pages/manifest.json"
# 转写原文的**唯一工作目录**：`build/` 是 Gradle 输出目录、随时可能被 clean 清掉，
# 故放 knowledge-production/ 下（并在 .gitignore 里排除——教辅原文不入版本控制，
# 与 extracted_chunks.jsonl 同口径）。
TRANSCRIPTS = REPO / "knowledge-production/2027-53-transcripts"
CHUNK_CAP = 900  # 单块上限，超过则按段落切


def _fp(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _chunk_id(rel_path: str) -> str:
    return hashlib.sha256(rel_path.encode("utf-8")).hexdigest()[:10]


def load_existing_fps() -> set[str]:
    fps: set[str] = set()
    if CHUNKS.exists():
        for line in CHUNKS.read_text(encoding="utf-8").splitlines():
            if line.strip():
                try:
                    fps.add(json.loads(line).get("fp", ""))
                except json.JSONDecodeError:
                    pass
    return fps


def split_text(text: str) -> list[str]:
    """把过长的知识点文字按段落切成 <=CHUNK_CAP 的块。"""
    text = text.strip()
    if not text:
        return []
    if len(text) <= CHUNK_CAP:
        return [text]
    parts = [p for p in text.split("\n") if p.strip()]
    out: list[str] = []
    cur = ""
    for p in parts:
        while len(p) > CHUNK_CAP:  # 单段超长：硬切
            out.append((cur + p[:CHUNK_CAP - len(cur)]).strip() if cur else p[:CHUNK_CAP])
            p = p[CHUNK_CAP:]
        if len(cur) + len(p) + 1 > CHUNK_CAP and cur:
            out.append(cur.strip())
            cur = p
        else:
            cur = (cur + "\n" + p) if cur else p
    if cur.strip():
        out.append(cur.strip())
    return [x for x in (o.strip() for o in out) if x]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", type=Path,
                    default=Path.home() / "Desktop" / "最全知识库原始资料")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    if not MANIFEST.exists():
        print(f"缺渲染 manifest：{MANIFEST}（先跑 scan_render_pages.py）")
        return 2
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    inv_rows = list(csv.DictReader(open(INV, encoding="utf-8"))) if INV.exists() else []
    inv_seen = {r["rel_path"] for r in inv_rows}
    state_rows = list(csv.DictReader(open(STATE, encoding="utf-8"))) if STATE.exists() else []
    state_seen = {r["rel_path"] for r in state_rows}
    existing_fps = load_existing_fps()

    now = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S")
    new_chunks: list[dict] = []
    summary = []

    for sub in manifest.get("subjects", []):
        subject = sub.get("subject")
        if not subject or sub.get("error"):
            continue
        rel = sub["pdf_rel"]
        stem = Path(sub["pdf_name"]).stem
        tdir = TRANSCRIPTS / subject / stem
        lines = []
        skipped: list[str] = []
        if tdir.exists():
            for f in sorted(tdir.glob("range_*.jsonl")):
                for ln, raw in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                    s = raw.strip()
                    if not s:
                        continue
                    # 代理常把每行当 JSON 数组元素写，行尾多一个逗号 → JSONL 不接受尾逗号。
                    # 容忍它（内容没坏），但**不再静默跳过**任何真解析不了的行。
                    if s.endswith(","):
                        s = s[:-1].rstrip()
                    try:
                        rec = json.loads(s)
                    except json.JSONDecodeError as e:
                        skipped.append(f"{f.name}:L{ln} {e.msg}")
                        continue
                    if (rec.get("text") or "").strip():
                        lines.append(rec)
        if skipped:
            print(f"  ! {subject} {stem}: {len(skipped)} 行解析失败（未入库）：{skipped[:3]}",
                  file=sys.stderr)
        base = _chunk_id(rel)
        added = 0
        for idx, rec in enumerate(lines):
            for piece in split_text(rec["text"]):
                fp = _fp(piece)
                if fp in existing_fps:
                    continue
                cid = f"{base}-{added + 1:03d}"
                new_chunks.append({
                    "heading": (rec.get("heading") or "").strip()[:80],
                    "text": piece,
                    "rel_path": rel,
                    "subject": subject,
                    "chunk_id": cid,
                    "fp": fp,
                    "source_page": rec.get("page"),
                })
                existing_fps.add(fp)
                added += 1
        # inventory 行（追加）
        if rel not in inv_seen:
            pdf = args.root / rel
            inv_rows.append({"rel_path": rel, "top_dir": sub["top_dir"], "ext": ".pdf",
                             "bytes": str(pdf.stat().st_size) if pdf.exists() else "",
                             "content_bearing": "yes", "pages": str(sub.get("total_pages", "")),
                             "text_cjk_sample": "0", "text_layer": "SCANNED_IMAGE"})
            inv_seen.add(rel)
        # state 行（追加，CHUNKED）
        if rel not in state_seen:
            state_rows.append({"rel_path": rel, "subject": subject, "ext": ".pdf",
                               "state": "CHUNKED",
                               "output_ref": f"视觉转写 {added} 块（2027版53扫描件，未判定）",
                               "tool": "store_53_transcripts",
                               "note": "2027版《53知识清单》彩色版扫描件，视觉转写，先存块库未入知识库",
                               "updated_at": now})
            state_seen.add(rel)
        summary.append({"subject": subject, "rel": rel, "points": len(lines),
                        "chunks": added, "lines_skipped": len(skipped)})
        print(f"[{subject}] {stem}: 转写 {len(lines)} 条知识点 → 新块 {added}", file=sys.stderr)

    if not args.dry_run:
        with CHUNKS.open("a", encoding="utf-8") as fh:
            for c in new_chunks:
                fh.write(json.dumps(c, ensure_ascii=False) + "\n")
        with INV.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(inv_rows[0].keys()), lineterminator="\n")
            w.writeheader()
            w.writerows(inv_rows)
        with STATE.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(state_rows[0].keys()), lineterminator="\n")
            w.writeheader()
            w.writerows(state_rows)

    print(json.dumps({"new_chunks": len(new_chunks), "books": summary,
                      "dry_run": args.dry_run}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
