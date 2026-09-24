# -*- coding: utf-8 -*-
"""W7 工单生成器的用例：钉住"前缀怎么算"与"该怎么修"这两处判断。

这两处错了会连带错一整批：前缀算多一格 → 修的人被迫重写前文；把定界符错位当成截断 →
代理会去"补一句本来不缺的话"（正是这批截断的成因）。
"""

from __future__ import annotations

import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_build import make_boundary_fix_slices as mk  # noqa: E402

TRUNC = "定位：数学必修第一册 第三章。翻折别记反：$|f(x)|$ 只改变 $x$ 轴下方的部分（沿 $x$ 轴向上翻）；$f(|x"
DELIM = "定位：CHEMISTRY 综合。换算成绝对质量（失=m_起始\\times$ 残留率），只拿比值算容易算错。"
CLEAN = "定位：数学必修第一册 第三章。这条是好的：$a+b=b+a$。"


class SafePrefixTest(unittest.TestCase):
    def test_cuts_from_last_unmatched_dollar(self):
        self.assertEqual("定位：数学必修第一册 第三章。翻折别记反：$|f(x)|$ 只改变 $x$ 轴下方的部分（沿 $x$ 轴向上翻）；",
                         mk.safe_prefix(TRUNC))

    def test_trailing_backslash_is_dropped(self):
        got = mk.safe_prefix("定位：X。看 $x\\to+\\infty$ 与 $x\\")
        self.assertFalse(got.endswith("\\"))
        self.assertEqual("定位：X。看 $x\\to+\\infty$ 与", got)

    def test_closed_formulas_keep_everything_but_the_backslash(self):
        self.assertEqual("定位：X。结果是 $a$。", mk.safe_prefix("定位：X。结果是 $a$。\\"))


class ClassifyTest(unittest.TestCase):
    def test_complete_sentence_is_a_delimiter_problem(self):
        self.assertTrue(mk.classify(DELIM, [{"slug": "m"}]).startswith("修定界符"))

    def test_cut_mid_formula_is_a_completion(self):
        self.assertTrue(mk.classify(TRUNC, [{"slug": "m"}]).startswith("补全尾"))

    def test_no_materials_means_trim(self):
        self.assertTrue(mk.classify(TRUNC, []).startswith("收尾"))


class NodeRowsTest(unittest.TestCase):
    def test_only_defective_boundaries_come_out(self):
        pack = {"subjects": [{"subject": "MATH", "topics": [
            {"knowledgePoints": [
                {"slug": "a", "name": "截断的", "boundary": TRUNC},
                {"slug": "b", "name": "好的", "boundary": CLEAN},
            ]}]}]}
        rows = mk.node_rows(pack, {"a": [{"slug": "m1"}]})
        self.assertEqual(["a"], [r["slug"] for r in rows])
        self.assertEqual("1", rows[0]["materials"])
        self.assertTrue(rows[0]["evidence_path"].endswith("01_MATH_a.md"))


if __name__ == "__main__":
    unittest.main()
