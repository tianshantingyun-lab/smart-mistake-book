# -*- coding: utf-8 -*-
"""页级账本与档位判据的用例。

两条线索：
1. **合成用例**钉住判据语义（signals 计数、compute_gate 的四种 fail、compute_plan 的档位规则）；
2. **真实产物用例**钉住账本与源的**一致性**（页数 = manifest 总页数、键唯一、done+missing 守恒、
   每行 gate/plan 与其判据函数自洽）——钉的是"账本永远由源重建"这条不变量，不钉具体页号，
   这样转写推进时用例不用改。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import transcription_ledger as tl  # noqa: E402


class SignalsTest(unittest.TestCase):
    def test_counts_each_signal(self):
        text = ("1. 并集 $A\\cup B$\n2. 交集 $A\\cap B$\n① 定义\n"
                "【图：一个示意图】\n【不确定：看不清右下角】")
        sig = tl.signals(text)
        self.assertEqual(2, sig["formulas"])
        self.assertEqual(1, sig["figs"])
        self.assertEqual(3, sig["numbered"])          # 1. / 2. / ①
        self.assertEqual(1, sig["circled"])
        self.assertEqual(1, sig["uncertainties"])
        self.assertGreater(sig["chars"], 20)

    def test_circled_marks_count_anywhere_but_formula_parens_do_not(self):
        # 依据（2026-09-25 试点实测）：代理把一块内容写成一两行时，行内圈号（②离子方程式：…）
        # 必须算；而行内 `f(2)`、`(0)` 这类公式括号不许算成编号。
        sig = tl.signals("（左栏续）②离子方程式：见 $f(2)$ 与 $g(0)$ 的取值。\n①定义：$x>0$")
        self.assertEqual(2, sig["numbered"], "行内圈号要算，公式括号不算")
        self.assertEqual(2, sig["circled"])

    def test_line_leading_digit_marks_count(self):
        sig = tl.signals("1. 定义\n2) 性质\n（3）结论\n正文里的 4) 不算")
        self.assertEqual(3, sig["numbered"])

    def test_empty_text_is_all_zero(self):
        sig = tl.signals("")
        self.assertEqual(0, sig["chars"])
        self.assertEqual(0, sig["formulas"])
        self.assertEqual(0, sig["numbered"])


class GateTest(unittest.TestCase):
    def _row(self, **kw):
        row = {"status": "done", "verdict": "", "truncated": "", "chars": 500,
               "items_min": "", "numbered": 10}
        row.update(kw)
        return row

    def test_missing_page_is_pending(self):
        self.assertEqual("pending", tl.compute_gate(self._row(status="missing")))

    def test_structural_truncation_fails(self):
        self.assertEqual("fail", tl.compute_gate(self._row(truncated="yes", text="占位")))

    def test_too_short_fails(self):
        self.assertEqual("fail", tl.compute_gate(self._row(chars=10)))

    def test_text_defects_fail(self):
        # 残迹判据与成品包同源（kb_build.gate.field_text_defects）
        self.assertEqual("fail", tl.compute_gate(self._row(), text="结论：$T=\\1a$"))
        self.assertEqual("fail", tl.compute_gate(self._row(), text="$a=b$ 与 $c=d$ 不成对$"))

    def test_audit_verdict_retranscribe_fails(self):
        self.assertEqual("fail", tl.compute_gate(self._row(verdict="RETRANSCRIBE")))

    def test_items_min_gate(self):
        # 清点说该有 120 条，转写里只数出 10 个编号 → 疑似整块漏
        self.assertEqual("fail", tl.compute_gate(self._row(items_min="120"), text="正常文本"))
        # 清点 21 条、写出 10 个编号 → 过（编号与式数不必一一对应）
        self.assertEqual("pass", tl.compute_gate(self._row(items_min="21"), text="正常文本"))

    def test_clean_page_passes(self):
        self.assertEqual("pass", tl.compute_gate(self._row(), text="$a+b=b+a$。"))


class PlanTest(unittest.TestCase):
    def _row(self, **kw):
        row = {"status": "done", "page_kind": "叙述", "gate": "pass", "verdict": ""}
        row.update(kw)
        return row

    def test_missing_page_always_full(self):
        # 缺失页一律全协议：实测图像信号分不出"公式密排"（CHEM p2 墨迹 0.0084/行 38），
        # 靠页型猜它简单会放过坏页。
        for kind in ("叙述", "封面/扉页", "公式密排", "未定"):
            with self.subTest(kind=kind):
                self.assertEqual("补齐-全协议",
                                 tl.compute_plan(self._row(status="missing", page_kind=kind)))

    def test_failed_gate_or_retranscribe_wins(self):
        self.assertEqual("重转-全协议", tl.compute_plan(self._row(gate="fail")))
        self.assertEqual("重转-全协议", tl.compute_plan(self._row(verdict="RETRANSCRIBE")))

    def test_narrative_page_gets_light_audit(self):
        self.assertEqual("审计-轻", tl.compute_plan(self._row(page_kind="叙述")))
        self.assertEqual("审计-轻", tl.compute_plan(self._row(page_kind="封面/扉页")))

    def test_dense_or_unknown_gets_full_audit(self):
        for kind in ("公式密排", "图密集", "表格式", "未定", ""):
            with self.subTest(kind=kind):
                self.assertEqual("审计-全", tl.compute_plan(self._row(page_kind=kind)))


class RealArtifactTest(unittest.TestCase):
    """账本必须永远是"源的函数"：这一组用例在转写推进时不需要改。"""

    def setUp(self):
        self.rows = tl.build_rows()
        self.tr = tl.load_transcripts()

    def test_ledger_covers_every_page_exactly_once(self):
        expect = sum(int(s["total_pages"]) for s in tl.load_manifest())
        keys = [(r["subject"], r["page"]) for r in self.rows]
        self.assertEqual(expect, len(self.rows))
        self.assertEqual(len(keys), len(set(keys)), "账本里有重复页")

    def test_done_and_missing_are_conserved(self):
        done = [r for r in self.rows if r["status"] == "done"]
        missing = [r for r in self.rows if r["status"] == "missing"]
        self.assertEqual(len(self.rows), len(done) + len(missing))
        # done 的定义就是"转写里有这一页的记录"
        self.assertEqual({(r["subject"], r["page"]) for r in done},
                         set(self.tr.keys()))

    def test_every_done_page_span_contains_its_page(self):
        for r in self.rows:
            if r["status"] != "done":
                continue
            a, b = r["span"].replace("_", "-").split("-")
            with self.subTest(page=(r["subject"], r["page"])):
                self.assertLessEqual(int(a), r["page"])
                self.assertLessEqual(r["page"], int(b))

    def test_gate_and_plan_are_consistent_with_their_functions(self):
        for r in self.rows:
            text = self.tr.get((r["subject"], r["page"]), {}).get("text", "")
            with self.subTest(page=(r["subject"], r["page"])):
                self.assertEqual(tl.compute_gate(r, text), r["gate"])
                self.assertEqual(tl.compute_plan(r), r["plan"])


if __name__ == "__main__":
    unittest.main()
