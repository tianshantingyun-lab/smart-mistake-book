# -*- coding: utf-8 -*-
"""alignment：课标↔KB 对照台账的判定表完整性与计数纪律。"""

from __future__ import annotations

import csv
import unittest

from kb_build import pack_io
from kb_coverage import alignment


def _rows():
    path = alignment.TABLE
    return list(csv.DictReader(open(path, encoding="utf-8")))


class AlignmentTableTest(unittest.TestCase):
    def test_every_candidate_has_exactly_one_judgment(self):
        cands = alignment.load_candidates()
        judg = alignment.load_judgments()
        for subj in alignment.SUBJECTS:
            want = {c["slug"] for c in cands[subj]}
            got = [k[1] for k in judg if k[0] == subj]
            self.assertEqual(want, set(got), f"{subj} 判定缺漏/多余")
            self.assertEqual(len(got), len(set(got)), f"{subj} 有重复判定行")

    def test_statuses_valid_and_targets_consistent(self):
        problems = alignment.check()
        self.assertEqual([], problems, problems[:5])

    def test_not_covered_rows_have_no_target(self):
        for r in _rows():
            if r["status"] in ("NOT_COVERED", "UNDECIDED"):
                self.assertEqual("", r["target_node_slug"].strip(), r["candidate_slug"])

    def test_targets_exist_in_pack(self):
        p = pack_io.load_json(pack_io.pack_path())
        valid = {s["subject"]: {k["slug"] for t in s["topics"] for k in t["knowledgePoints"]}
                 for s in p["subjects"]}
        for r in _rows():
            if r["status"] in ("COVERED", "PARTIAL"):
                self.assertIn(r["target_node_slug"], valid[r["subject"]],
                              f"{r['candidate_slug']} → {r['target_node_slug']}")

    def test_publication_rule_kept_in_document(self):
        doc = alignment.build_doc()
        self.assertEqual(doc["total"]["coverageNumerator"], doc["total"]["COVERED"])
        denom = sum(s["counts"]["coverageDenominator"] for s in doc["subjects"])
        self.assertEqual(doc["total"]["coverageDenominator"], denom)
        self.assertIn("UNDECIDED", doc["publishedRule"],
                      "公布规则必须同时暴露分子、分母与 UNDECIDED 绝对数")


if __name__ == "__main__":
    unittest.main()
