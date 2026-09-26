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

    def test_hard_conflict_still_rejects_the_batch(self):
        # 真正的冲突：同页两份**都不是** ACCEPT/RETRANSCRIBE 对冲（例如旧表 UNCERTAIN、
        # 新片 RETRANSCRIBE）——打架时停下，不写表。
        self.target.write_text(",".join(COLS) + "\nMATH,15-17,UNCERTAIN,9,8,先有的一行\n",
                               encoding="utf-8")
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "16", "verdict": "RETRANSCRIBE",
                                   "items_min": "30", "items_numbered": "5", "evidence": "漏"}])
        rc, out = self._run(["--from-slices", str(d), "--write"])
        self.assertEqual(1, rc, out)
        self.assertIn("打架", out)
        self.assertEqual("MATH,15-17,UNCERTAIN,9,8,先有的一行\n",
                         self.target.read_text(encoding="utf-8").split("\n", 1)[1])

    def test_second_run_is_idempotent_when_nothing_new(self):
        # 2026-09-25 改判：重启/续跑会把同一张片再收一次，那是幂等而不是冲突（原先是硬报错，
        # 结果一次重启就把整批收拢挡死）。真正的冲突只在"同页两份不同判决"时才拒。
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "20", "verdict": "ACCEPT",
                                   "items_min": "9", "items_numbered": "8", "evidence": "看清了"}])
        self._run(["--from-slices", str(d), "--write"])
        rc, out = self._run(["--from-slices", str(d), "--write"])
        self.assertEqual(0, rc, out)
        self.assertIn("跳过（幂等）", out)
        rows = list(csv.DictReader(self.target.open(encoding="utf-8", newline="")))
        self.assertEqual(1, len(rows), "幂等重收不许写重")

    def test_accept_overrides_retranscribe_and_is_printed(self):
        # 补齐与审计并行后必然撞的情形：审计（旧状态：整页缺失）判 RETRANSCRIBE，
        # 补齐已把该页重写并过闸 → 取 ACCEPT，但必须打印被覆盖的行（不静默）。
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "21", "verdict": "RETRANSCRIBE",
                                   "items_min": "0", "items_numbered": "0", "evidence": "整页缺失"}])
        d2 = self._slice("b.csv", [{"subject": "MATH", "pages": "21", "verdict": "ACCEPT",
                                    "items_min": "30", "items_numbered": "12", "evidence": "已重写并过闸"}])
        rc, out = self._run(["--from-slices", str(d), "--from-slices", str(d2), "--write"])
        self.assertEqual(0, rc, out)
        self.assertIn("ACCEPT 覆盖 RETRANSCRIBE", out)
        rows = list(csv.DictReader(self.target.open(encoding="utf-8", newline="")))
        self.assertEqual(1, len(rows))
        self.assertEqual("ACCEPT", rows[0]["verdict"])

    def test_evidence_with_unquoted_commas_is_kept_not_crashed(self):
        # 实测 2026-09-25：代理在 evidence 里用逗号但没加引号 → csv 拆出多余列塞进 None 键，
        # 原实现对着它调 .strip() 直接崩、整批收拢失败。多余列必须**并回 evidence**（那是证据）。
        d = self.tmp / "slices"
        d.mkdir(exist_ok=True)
        (d / "a.csv").write_text(
            "subject,pages,verdict,items_min,items_numbered,evidence\n"
            "MATH,57-70,ACCEPT,317,60,逐页核对：P57 例3齐全,5个解集,手写批注有记录\n",
            encoding="utf-8")
        rc, out = self._run(["--from-slices", str(d), "--write"])
        self.assertEqual(0, rc, out)
        rows = list(csv.DictReader(self.target.open(encoding="utf-8", newline="")))
        self.assertEqual(1, len(rows))
        self.assertIn("例3齐全", rows[0]["evidence"])
        self.assertIn("5个解集", rows[0]["evidence"], "逗号后的后半句也要留住")

    def test_empty_inputs_report_without_writing(self):
        rc, out = self._run([])
        self.assertEqual(0, rc)
        self.assertIn("没有可收的行", out)
        self.assertFalse(self.target.exists())
        rc, _ = self._run(["--from-slices", str(self.tmp / "nope")])
        self.assertEqual(2, rc)



    def test_multi_page_row_is_narrowed_to_the_pages_it_won(self):
        # 实测形态：多页行 549-560 与单页行 551 撞页 → 输出里每页只能出现一次，
        # 多页行要把 pages 收窄到它真正赢下的那些页（否则下游判"同页两条判决"）。
        d = self._slice("a.csv", [{"subject": "MATH", "pages": "549-552", "verdict": "ACCEPT",
                                   "items_min": "10", "items_numbered": "5", "evidence": "片行"}])
        d2 = self._slice("b.csv", [{"subject": "MATH", "pages": "551", "verdict": "ACCEPT",
                                    "items_min": "30", "items_numbered": "9", "evidence": "单页行"}])
        rc, out = self._run(["--from-slices", str(d), "--from-slices", str(d2), "--write"])
        self.assertEqual(0, rc, out)
        rows = list(csv.DictReader(self.target.open(encoding="utf-8", newline="")))
        seen = []
        for r in rows:
            seen += [(r["subject"], p) for p in r["pages"].split(",")]
        self.assertEqual(len(seen), len(set(seen)), f"每页只能出现一次：{rows}")
        p551 = [r for r in rows if "551" in r["pages"].split(",")]
        self.assertEqual(1, len(p551))
        self.assertEqual("30", p551[0]["items_min"], "551 由清点数更大的那份赢下")

if __name__ == "__main__":
    unittest.main()
