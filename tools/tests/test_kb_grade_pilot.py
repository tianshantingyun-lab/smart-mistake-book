# -*- coding: utf-8 -*-
"""试点验收判据的用例。

要害三条：① 缺清点数就不能算过闸（没有清点就没法判漏没漏，试点会退化成"看着挺全"）；
② 文本残迹与条数闸用的是与成品库同一套判据（本处只验证接线，不重测判据本身）；
③ 没有页或目录不存在时要如实报错，不能"0 页也全过"。
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import grade_pilot as gp  # noqa: E402


class GradeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="pilot-"))

    def _page(self, subject: str, page: int, text: str, items_min=None, items_numbered=None):
        d = self.tmp / subject
        d.mkdir(parents=True, exist_ok=True)
        recs = [{"page": page, "heading": "h", "text": t} for t in text.split("\n") if t.strip()]
        (d / f"p{page:04d}.jsonl").write_text(
            "\n".join(json.dumps(r, ensure_ascii=False) for r in recs), encoding="utf-8")
        if items_min is not None:
            (d / f"p{page:04d}.counts.json").write_text(
                json.dumps({"items_min": items_min, "items_numbered": items_numbered},
                           ensure_ascii=False), encoding="utf-8")

    def test_clean_page_passes(self):
        self._page("CHEMISTRY", 27,
                   "1. 定义：$a+b=b+a$，交换律在实数范围内恒成立，用它可以重排求和顺序。\n"
                   "2. 性质：$x^2\\geq 0$，等号仅在 $x=0$ 时取到，这是配方的基础。",
                   items_min=2, items_numbered=2)
        out = gp.grade_dir(self.tmp)
        self.assertEqual(1, out["passed"], out)
        self.assertEqual("pass", out["pages"][0]["gate"])

    def test_missing_counts_fails(self):
        self._page("CHEMISTRY", 27, "1. 定义：$a+b=b+a$。")
        out = gp.grade_dir(self.tmp)
        self.assertEqual("fail", out["pages"][0]["gate"])
        self.assertIn("缺清点数", out["pages"][0]["problems"][0])

    def test_defect_fails(self):
        self._page("MATH", 162, "1. 结论：$T=\\1a$ 与 $x$ 的关系。", items_min=1, items_numbered=1)
        out = gp.grade_dir(self.tmp)
        self.assertEqual("fail", out["pages"][0]["gate"])
        self.assertTrue(any("残迹" in p for p in out["pages"][0]["problems"]))

    def test_count_gate_fails_when_text_much_shorter_than_count(self):
        # 清点 120 条、写出只数得出 1 个编号 → 疑似整块漏
        self._page("CHEMISTRY", 2, "1. 只有一条 $\\mathrm{Na_2O}$。", items_min=120, items_numbered=120)
        out = gp.grade_dir(self.tmp)
        self.assertEqual("fail", out["pages"][0]["gate"])
        self.assertTrue(any("闸门不过" in p for p in out["pages"][0]["problems"]))

    def test_trailing_comma_tolerated(self):
        d = self.tmp / "MATH"
        d.mkdir(parents=True, exist_ok=True)
        (d / "p0001.jsonl").write_text(
            '{"page":1,"heading":"h","text":"1. 定义：$a+b=b+a$，交换律在实数范围内恒成立，可用来重排求和顺序。"},\n'
            '{"page":1,"heading":"h","text":"2. 性质：$x^2\\\\geq 0$，等号仅在 $x=0$ 处取到，是配方的基础。"},',
            encoding="utf-8")
        (d / "p0001.counts.json").write_text(json.dumps({"items_min": 2}), encoding="utf-8")
        out = gp.grade_dir(self.tmp)
        self.assertEqual(2, out["pages"][0]["records"], out)
        self.assertEqual("pass", out["pages"][0]["gate"], out)

    def test_empty_dir_is_not_a_pass(self):
        out = gp.grade_dir(self.tmp)
        self.assertEqual(0, out["passed"])
        self.assertEqual([], out["pages"])

    def test_main_reports_missing_dir(self):
        rc = gp.main(["--dir", str(self.tmp / "nope")])
        self.assertEqual(2, rc)



    def test_grade_one_selects_a_single_page(self):
        self._page("CHEMISTRY", 2, "1. 甲：$\mathrm{Na_2O}$ 与水的反应。" * 6, items_min=1)
        self._page("MATH", 5, "1. 乙：$a+b=b+a$ 的说明文字要够长才行。" * 6, items_min=1)
        got = gp.grade_one(self.tmp, "MATH", 5)
        self.assertIsNotNone(got)
        self.assertEqual("MATH", got["subject"])
        self.assertEqual(5, got["page"])
        self.assertIsNone(gp.grade_one(self.tmp, "MATH", 99))

    def test_main_page_mode_exit_code(self):
        self._page("CHEMISTRY", 27,
                   "1. 定义：$a+b=b+a$，交换律在实数范围内恒成立，可用来重排求和顺序，也是配方与移项的依据。",
                   items_min=1)
        self.assertEqual(0, gp.main(["--dir", str(self.tmp), "--page", "CHEMISTRY:27"]))
        self.assertEqual(1, gp.main(["--dir", str(self.tmp), "--page", "CHEMISTRY:28"]))

if __name__ == "__main__":
    unittest.main()
