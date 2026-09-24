# -*- coding: utf-8 -*-
"""入块库工具的用例：重点钉住"**只收过闸页**"这条过滤。

它消灭的失败很具体：已知有残迹（`\\1`/shell 展开/`$` 不成对）、截断、条数不足的页被当成
"已存好"写进块库——那样"两块门 0 问题"就永远绿不了，且根因会被掩盖成下游判定问题。
所以这里的正反用例都围绕它：过闸的入、没过闸的不入、无页码的不入、且报出来不静默。
"""

from __future__ import annotations

import csv
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import store_53_transcripts as st  # noqa: E402

INV_COLS = ["rel_path", "top_dir", "ext", "bytes", "content_bearing", "pages",
            "text_cjk_sample", "text_layer"]
STATE_COLS = ["rel_path", "subject", "ext", "state", "output_ref", "tool", "note", "updated_at"]


class StoreGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="store53-"))
        self.root = self.tmp / "src"
        self.transcripts = self.tmp / "transcripts"
        self.chunks = self.tmp / "extracted_chunks.jsonl"
        self.inv = self.tmp / "source_inventory.csv"
        self.state = self.tmp / "extraction_state.csv"
        self.manifest = self.tmp / "manifest.json"

        (self.root / "数学").mkdir(parents=True)
        (self.root / "数学" / "book.pdf").write_bytes(b"%PDF-1.4 fake")
        self.manifest.write_text(json.dumps({"subjects": [
            {"subject": "数学", "pdf_rel": "数学/book.pdf", "pdf_name": "book.pdf",
             "top_dir": "数学", "total_pages": 4},
        ]}, ensure_ascii=False), encoding="utf-8")
        tdir = self.transcripts / "数学" / "book"
        tdir.mkdir(parents=True)
        recs = [
            {"page": 1, "heading": "并集", "text": "定义：$A\\cup B$ 的元素合起来。"},      # pass
            {"page": 2, "heading": "交集", "text": "残迹页：$T=\\1a$ 的说明文字够长了。"},   # fail（残迹）
            {"page": 3, "heading": "", "text": "缺失页没转写。"},                          # pending
            {"heading": "无页码", "text": "这条没有页码字段。"},                             # 不入（无法定位）
        ]
        (tdir / "range_0001_0004.jsonl").write_text(
            "\n".join(json.dumps(r, ensure_ascii=False) for r in recs), encoding="utf-8")
        # 账本由 transcription_ledger 算；用例里换成内存小集合（gate 判据本身在 ledger 用例里钉）
        self.rows = [
            {"subject": "数学", "page": 1, "gate": "pass"},
            {"subject": "数学", "page": 2, "gate": "fail"},
            {"subject": "数学", "page": 3, "gate": "pending"},
            {"subject": "数学", "page": 4, "gate": "pending"},
        ]
        for name, path in (("MANIFEST", self.manifest), ("TRANSCRIPTS", self.transcripts),
                           ("CHUNKS", self.chunks), ("INV", self.inv), ("STATE", self.state)):
            p = mock.patch.object(st, name, path)
            p.start()
            self.addCleanup(p.stop)
        p = mock.patch.object(st.tl, "build_rows", lambda: self.rows)
        p.start()
        self.addCleanup(p.stop)
        self._seed(self.inv, INV_COLS, {"rel_path": "已有/old.pdf"})
        self._seed(self.state, STATE_COLS, {"rel_path": "已有/old.pdf", "subject": "数学",
                                            "ext": ".pdf", "state": "EXTRACTED",
                                            "output_ref": "x", "tool": "old", "note": "",
                                            "updated_at": "2026-01-01 00:00:00"})

    def _seed(self, path: Path, cols, row):
        with path.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=cols, lineterminator="\n")
            w.writeheader()
            w.writerow(row)

    def _run(self, argv=()):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = st.main(["--root", str(self.root), *argv])
        return rc, out.getvalue(), err.getvalue()

    def _chunks(self):
        if not self.chunks.exists():
            return []
        return [json.loads(l) for l in self.chunks.read_text(encoding="utf-8").splitlines() if l.strip()]

    def test_only_gated_pages_are_stored(self):
        rc, out, err = self._run()
        self.assertEqual(0, rc, err)
        chunks = self._chunks()
        self.assertEqual([1], [c["source_page"] for c in chunks], chunks)
        self.assertIn("$A\\cup B$", chunks[0]["text"])
        self.assertEqual("数学/book.pdf", chunks[0]["rel_path"])
        # 未过闸/无页码的都报出来了，不静默
        self.assertIn("p2:fail", err)
        self.assertIn("p3:pending", err)
        self.assertIn("无页码", err)
        summary = json.loads(out.strip().splitlines()[-1])
        self.assertEqual(1, summary["books"][0]["points"])
        self.assertEqual(3, summary["books"][0]["pages_blocked"])

    def test_missing_entry_excluded(self):
        # 不在账本里的页码 = 无法判它过没过闸 → 保守收手
        tdir = self.transcripts / "数学" / "book"
        (tdir / "range_0001_0004.jsonl").write_text(
            (tdir / "range_0001_0004.jsonl").read_text(encoding="utf-8")
            + "\n" + json.dumps({"page": 99, "heading": "", "text": "账本外的页。"}, ensure_ascii=False),
            encoding="utf-8")
        rc, out, err = self._run()
        self.assertEqual(0, rc, err)
        self.assertIn("不在账本", err)
        self.assertEqual([1], [c["source_page"] for c in self._chunks()])

    def test_state_row_is_chunked_not_extracted(self):
        rc, _, err = self._run()
        self.assertEqual(0, rc, err)
        with self.state.open(encoding="utf-8", newline="") as fh:
            by_rel = {r["rel_path"]: r for r in csv.DictReader(fh)}
        self.assertIn("已有/old.pdf", by_rel, "旧行必须保留")
        self.assertEqual("EXTRACTED", by_rel["已有/old.pdf"]["state"])
        self.assertEqual("CHUNKED", by_rel["数学/book.pdf"]["state"])
        with self.inv.open(encoding="utf-8", newline="") as fh:
            inv = {r["rel_path"]: r for r in csv.DictReader(fh)}
        self.assertEqual("SCANNED_IMAGE", inv["数学/book.pdf"]["text_layer"])
        self.assertEqual("4", inv["数学/book.pdf"]["pages"])

    def test_dry_run_writes_nothing(self):
        rc, _, err = self._run(("--dry-run",))
        self.assertEqual(0, rc, err)
        self.assertFalse(self.chunks.exists())
        self.assertNotIn("数学/book.pdf",
                         self.inv.read_text(encoding="utf-8").splitlines()[-1])

    def test_rerun_adds_no_duplicate_chunks(self):
        rc, _, err = self._run()
        self.assertEqual(0, rc, err)
        first = self.chunks.read_text(encoding="utf-8")
        rc, out, err = self._run()
        self.assertEqual(0, rc, err)
        self.assertEqual(first, self.chunks.read_text(encoding="utf-8"), "重跑必须按 fp 去重")
        self.assertEqual(0, json.loads(out.strip().splitlines()[-1])["new_chunks"])


if __name__ == "__main__":
    unittest.main()
