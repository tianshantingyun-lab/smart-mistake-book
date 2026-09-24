# -*- coding: utf-8 -*-
"""审计收集器的用例：钉住"唯一写者"与"同页冲突必须拒绝"。

并发追加 CSV 会互相覆盖且无痕——这正是这份工具存在的理由，所以"冲突即拒绝"是它的核心
行为，正反两侧都要钉。
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

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import collect_audits as ca  # noqa: E402

COLS = ["subject", "pages", "verdict", "items_min", "items_numbered", "evidence"]


class CollectTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="collect-"))
        self.target = self.tmp / "transcript_audits.csv"

    def _slice(self, name: str, rows: list[dict]):
        d = self.tmp / "slices"
        d.mkdir(exist_ok=True)
        with (d / name).open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=COLS, lineterminator="\n", extrasaction="ignore")
            w.writeheader()
            w.writerows(rows)
        return d

    def _counts(self, subject: str, page: int, items_min: int, items_numbered: int):
        d = self.tmp / "fill"
        (d / subject).mkdir(parents=True, exist_ok=True)
        (d / subject / f"p{page:04d}.counts.json").write_text(
            json.dumps({"subject": subject, "page": page, "items_min": items_min,
                        "items_numbered": items_numbered}), encoding="utf-8")
        return d

    def _run(self, argv):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = ca.main(["--target", str(self.target)] + argv)
        return rc, buf.getvalue()

    def test_slices_are_merged_and_written(self):
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "15-17", "verdict": "ACCEPT",
                                   "items_min": "9", "items_numbered": "8", "evidence": "逐页看清"}])
        self._slice("b.csv", [{"subject": "MATH", "pages": "20", "verdict": "RETRANSCRIBE",
                               "items_min": "30", "items_numbered": "12", "evidence": "整块漏"}])
        rc, out = self._run(["--from-slices", str(d), "--write"])
        self.assertEqual(0, rc, out)
        rows = list(csv.DictReader(self.target.open(encoding="utf-8", newline="")))
        self.assertEqual(2, len(rows))
        self.assertEqual("逐页看清", rows[0]["evidence"], "裁定人自己的依据必须原样保留")

    def test_evidence_falls_back_to_slice_file_name(self):
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "20", "verdict": "ACCEPT",
                                   "items_min": "9", "items_numbered": "8", "evidence": ""}])
        rc, _ = self._run(["--from-slices", str(d), "--write"])
        self.assertEqual(0, rc)
        rows = list(csv.DictReader(self.target.open(encoding="utf-8", newline="")))
        self.assertEqual("a.csv", rows[0]["evidence"], "没写依据时至少留下是哪个片判的")

    def test_counts_become_accept_rows(self):
        d = self._counts("BIOLOGY", 15, 63, 44)
        rc, out = self._run(["--from-counts", str(d), "--write"])
        self.assertEqual(0, rc, out)
        rows = list(csv.DictReader(self.target.open(encoding="utf-8", newline="")))
        self.assertEqual([{"subject": "BIOLOGY", "pages": "15", "verdict": "ACCEPT",
                           "items_min": "63", "items_numbered": "44",
                           "evidence": "S3 补齐页：清点与转写同人同批，机械门另判"}],
                         [{k: r[k] for k in COLS} for r in rows])

    def test_conflict_with_existing_row_rejects_the_batch(self):
        self.target.write_text(",".join(COLS) + "\nMATH,15-17,ACCEPT,9,8,先有的一行\n",
                               encoding="utf-8")
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "16", "verdict": "RETRANSCRIBE",
                                   "items_min": "30", "items_numbered": "5", "evidence": "漏"}])
        rc, out = self._run(["--from-slices", str(d), "--write"])
        self.assertEqual(1, rc)
        self.assertIn("冲突", out)
        self.assertEqual("MATH,15-17,ACCEPT,9,8,先有的一行\n",
                         self.target.read_text(encoding="utf-8").split("\n", 1)[1])

    def test_second_run_is_idempotent_when_nothing_new(self):
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "20", "verdict": "ACCEPT",
                                   "items_min": "9", "items_numbered": "8", "evidence": "看清了"}])
        self._run(["--from-slices", str(d), "--write"])
        rc, out = self._run(["--from-slices", str(d)])
        self.assertEqual(1, rc, "重复收集同一片应报冲突而不是静默重复")
        self.assertIn("冲突", out)

    def test_empty_inputs_report_without_writing(self):
        rc, out = self._run([])
        self.assertEqual(0, rc)
        self.assertIn("没有可收的行", out)
        self.assertFalse(self.target.exists())
        rc, _ = self._run(["--from-slices", str(self.tmp / "nope")])
        self.assertEqual(2, rc)


if __name__ == "__main__":
    unittest.main()
