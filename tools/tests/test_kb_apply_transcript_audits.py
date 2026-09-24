# -*- coding: utf-8 -*-
"""审计裁定表执行器的用例（正反 + 幂等 + 无损）。

这个小工具的价值全在"**判决能不能被机械执行**"上：S4 会产生几百条逐页判决，若校验太松，
一条脏判决就会把重转队列带偏；若校验太严，正常判决会被整批拒。用例把两侧都钉住，
并钉住两条约定：**校验不过就不写盘**、**同一份裁定表重跑逐字节相同**。
"""

from __future__ import annotations

import csv
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import apply_transcript_audits as ap  # noqa: E402


class ExpandTest(unittest.TestCase):
    def test_three_notations(self):
        self.assertEqual([15], ap.expand("15"))
        self.assertEqual([15, 16, 17], ap.expand("15-17"))
        self.assertEqual([15, 17], ap.expand("15,17"))
        self.assertEqual([15, 16, 18], ap.expand("15-16,18"))

    def test_underscore_span_accepted(self):
        self.assertEqual([7, 8], ap.expand("7_8"))

    def test_garbage_rejected(self):
        with self.assertRaises(ValueError):
            ap.expand("15~17")


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="audits-"))
        self.audits = self.tmp / "transcript_audits.csv"
        self.queue = self.tmp / "retranscribe_queue.csv"
        self.manifest = self.tmp / "manifest_slim.json"
        self.manifest.write_text(json.dumps({"subjects": [
            {"subject": "数学", "pdf_rel": "math.pdf", "total_pages": 30},
            {"subject": "生物", "pdf_rel": "bio.pdf", "total_pages": 20},
        ]}, ensure_ascii=False), encoding="utf-8")
        for name, path in (("MANIFEST", self.manifest), ("AUDITS", self.audits), ("QUEUE", self.queue)):
            p = mock.patch.object(ap, name, path)
            p.start()
            self.addCleanup(p.stop)

    def _write(self, rows, columns=("subject", "pages", "verdict", "items_min", "items_numbered", "evidence")):
        with self.audits.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(columns), lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
        self.audits_bytes = self.audits.read_bytes()

    def _run(self, argv=()):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = ap.main(list(argv))
        return rc, buf.getvalue()

    def test_no_audits_table_is_a_report_not_an_error(self):
        rc, out = self._run()
        self.assertEqual(0, rc)
        self.assertIn("没有裁定表", out)
        self.assertFalse(self.queue.exists())

    def test_valid_table_writes_sorted_queue_and_is_idempotent(self):
        self._write([
            {"subject": "数学", "pages": "1-2", "verdict": "ACCEPT", "items_min": "21",
             "items_numbered": "19", "evidence": ""},
            {"subject": "生物", "pages": "7", "verdict": "RETRANSCRIBE", "items_min": "",
             "items_numbered": "", "evidence": "跨行一条被并进上一式"},
            {"subject": "数学", "pages": "12", "verdict": "UNCERTAIN", "items_min": "9",
             "items_numbered": "9", "evidence": ""},
        ])
        rc, out = self._run(("--write",))
        self.assertEqual(0, rc, out)
        first = self.queue.read_bytes()
        rows = list(csv.DictReader(io.StringIO(first.decode("utf-8"))))
        self.assertEqual(1, len(rows))
        self.assertEqual(["subject", "pdf_rel", "pages", "reason"], list(rows[0].keys()))
        self.assertEqual("生物", rows[0]["subject"])
        self.assertEqual("bio.pdf", rows[0]["pdf_rel"])
        # 幂等：同一份裁定表再跑，队列逐字节相同
        rc, _ = self._run(("--write",))
        self.assertEqual(0, rc)
        self.assertEqual(first, self.queue.read_bytes())
        # 无损：执行器不改裁定表
        self.assertEqual(self.audits_bytes, self.audits.read_bytes())

    def test_report_only_does_not_write(self):
        self._write([{"subject": "数学", "pages": "3", "verdict": "RETRANSCRIBE",
                      "items_min": "", "items_numbered": "", "evidence": "整块漏"}])
        rc, out = self._run()
        self.assertEqual(0, rc)
        self.assertIn("未写盘", out)
        self.assertFalse(self.queue.exists())

    def test_illegal_verdict_rejected_without_writing(self):
        self._write([{"subject": "数学", "pages": "3", "verdict": "SPLIT",
                      "items_min": "", "items_numbered": "", "evidence": "x"}])
        rc, out = self._run(("--write",))
        self.assertEqual(1, rc)
        self.assertIn("非法 verdict", out)
        self.assertFalse(self.queue.exists())

    def test_retranscribe_requires_evidence(self):
        self._write([{"subject": "数学", "pages": "3", "verdict": "RETRANSCRIBE",
                      "items_min": "", "items_numbered": "", "evidence": "  "}])
        rc, out = self._run(("--write",))
        self.assertEqual(1, rc)
        self.assertIn("缺 evidence", out)
        self.assertFalse(self.queue.exists())

    def test_out_of_range_rejected(self):
        self._write([{"subject": "数学", "pages": "31", "verdict": "ACCEPT",
                      "items_min": "", "items_numbered": "", "evidence": ""}])
        rc, out = self._run(("--write",))
        self.assertEqual(1, rc)
        self.assertIn("越界", out)
        self.assertFalse(self.queue.exists())

    def test_conflicting_verdicts_on_same_page_rejected(self):
        self._write([
            {"subject": "数学", "pages": "5", "verdict": "ACCEPT", "items_min": "", "items_numbered": "", "evidence": ""},
            {"subject": "数学", "pages": "4-6", "verdict": "RETRANSCRIBE", "items_min": "", "items_numbered": "",
             "evidence": "边界漏条"},
        ])
        rc, out = self._run(("--write",))
        self.assertEqual(1, rc)
        self.assertIn("冲突", out)
        self.assertFalse(self.queue.exists())

    def test_unknown_subject_and_bad_counts_rejected(self):
        self._write([
            {"subject": "化学", "pages": "1", "verdict": "ACCEPT", "items_min": "", "items_numbered": "", "evidence": ""},
            {"subject": "数学", "pages": "2", "verdict": "ACCEPT", "items_min": "很多", "items_numbered": "", "evidence": ""},
        ])
        rc, out = self._run(("--write",))
        self.assertEqual(1, rc)
        self.assertIn("未知学科", out)
        self.assertIn("非数字", out)
        self.assertFalse(self.queue.exists())

    def test_one_bad_row_rejects_the_whole_batch(self):
        # 整批拒绝：半份判决进队列比不进更危险（重转会漏页，且没人看得出漏了）
        self._write([
            {"subject": "数学", "pages": "1", "verdict": "RETRANSCRIBE", "items_min": "", "items_numbered": "",
             "evidence": "截断"},
            {"subject": "数学", "pages": "99", "verdict": "ACCEPT", "items_min": "", "items_numbered": "", "evidence": ""},
        ])
        rc, out = self._run(("--write",))
        self.assertEqual(1, rc)
        self.assertFalse(self.queue.exists())


if __name__ == "__main__":
    unittest.main()
