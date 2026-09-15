# -*- coding: utf-8 -*-
"""textfix 的契约测试：边界去原文摘录、LaTeX 命令修复、控制字符清理。

这些变换会直接改写进包的内容，因此每条规则都要有用例固定；
修错会静默污染教学材料，比不修更糟。
"""

from __future__ import annotations

import unittest

from kb_build import gate, textfix

BS = chr(92)
TAB = chr(9)
CR = chr(13)
LF = chr(10)
COMMANDS = gate._REAL_LATEX_COMMANDS


def fix(text: str) -> str:
    return textfix.repair_latex_commands(text, COMMANDS)


class BoundaryTest(unittest.TestCase):
    def test_split_boundary(self):
        position, rest = textfix.split_boundary("定位：数学必修第一册 第三章·函数。摘录文本")
        self.assertEqual("数学必修第一册 第三章·函数", position)
        self.assertEqual("摘录文本", rest)

    def test_strip_keeps_locator_drops_content(self):
        """摘录必须去掉（来源政策不允许保存原文段落），定位要留（事实性出处）。"""
        raw = "定位：化学必修第一册 第一章·物质分类。1．向0 mol·L－1的溶液中加入______"
        self.assertEqual("定位：化学必修第一册 第一章·物质分类。",
                         textfix.strip_boundary_excerpt(raw))

    def test_the_generators_gate_is_deliberately_blunt(self):
        """生成期用 `has_unvetted_content`：除定位外**一律**算未核验正文。

        它刻意不认摘录特征——剥离是整段替换，而"疑似原文"里有相当一部分是
        「知识 + 典例」拼接体；放行一条真原文是权利问题，删掉真知识只是质量问题。
        """
        for raw in ("定位：物理必修第一册 第一章·运动的描述。（见知识清单/教材）",
                    "定位：数学必修第一册 第三章·函数。"):
            with self.subTest(raw=raw):
                self.assertFalse(textfix.has_unvetted_content(raw))
        self.assertTrue(textfix.has_unvetted_content("定位：某章。这是原文摘录"))
        self.assertTrue(textfix.has_unvetted_content("定位：某章。这是一条真边界，也要先剥掉"))
        self.assertFalse(textfix.has_unvetted_content(""))


class ExcerptDetectionTest(unittest.TestCase):
    """`has_verbatim_excerpt` 是**质量门的度量**：判据必须是题目/答案的形态特征，
    不能是"有内容就算"——后者让门禁的两项指标一项恒真、一项恒假。"""

    def test_question_shapes_are_detected(self):
        for rest in (
            "【典例1】（24-25高三上·福建福州·月考）下列函数最小值为4的是（…",
            "…从中选择一个，补充在下面的问题中：若______，求直线与平面所成角的正弦值．",
            "A. 高温下铁和水蒸气反应生成铁红；B. 过量铁粉在Cl2中燃烧制取FeCl2；C. …",
            "易错辨析：正确的打“√”，错误的打“×”。",
            "3．（2025·云南昭通·模拟预测）已知数列的通项公式为，若是中唯一的最小项…",
            "点在上，且，为的中点，则等于（    ）",
        ):
            with self.subTest(rest=rest[:24]):
                self.assertTrue(textfix.has_verbatim_excerpt("定位：某章。" + rest))

    def test_authored_boundaries_are_not_flagged(self):
        """真边界不该被判成摘录——这正是旧判据的病根。"""
        for rest in (
            "同素异形体之间的转化属化学变化，这是判断的常见陷阱；含同素异形体的体系一定是混合物。",
            "判据是腐生并把有机物分解成无机物；蚯蚓、蜣螂等腐食性动物属分解者而不属消费者。",
            "矢量：既有大小又有方向的物理量。（力、位移、速度、加速度、动量等）；标量：只有大小没有方向的物理量。",
        ):
            with self.subTest(rest=rest[:20]):
                self.assertFalse(textfix.has_verbatim_excerpt("定位：某章。" + rest))

    def test_option_letter_needs_a_group(self):
        """单个 `A.` 不能算选项——集合式 `A∪B＝B∪A` 会污染宽口径。"""
        self.assertFalse(textfix.has_verbatim_excerpt("定位：某章。并集的性质：A∪B＝B∪A；A∪A＝A。"))

    def test_blank_run_needs_fill_in_context(self):
        """连跑空白要有填空语境；版面噪声（图注、对照表）不算。"""
        self.assertFalse(textfix.has_verbatim_excerpt("定位：某章。上升趋势                 下降趋势"))
        self.assertTrue(textfix.has_verbatim_excerpt("定位：某章。得的值为         。"))

    def test_locator_only_means_no_real_boundary(self):
        """`is_locator_only` 是 `locator_boundary` 的正确含义。"""
        for raw in ("定位：化学必修第一册 第一章 物质及其变化。",
                    "定位：物理必修第一册 第一章·运动的描述。（见知识清单/教材）",
                    ""):
            with self.subTest(raw=raw):
                self.assertTrue(textfix.is_locator_only(raw))
        self.assertFalse(textfix.is_locator_only(
            "定位：某章。同素异形体之间的转化属化学变化。"))

    def test_a_real_boundary_passes_both_metrics(self):
        """这一条是本次修改的核心：两项指标不再互斥。

        旧判据下 `locator_boundary` 判"以 `定位：` 开头"，而这个包的规定格式就是
        `定位：…。`，于是它对每个节点都成立——写成什么都没用，永远不可能同时通过两项。
        """
        boundary = "定位：生物学选择性必修2 第三章 生态系统及其稳定性。抵抗力稳定性指抵抗外界干扰、使自身结构与功能保持原状的能力。"
        self.assertFalse(textfix.is_locator_only(boundary), "写了真边界就不该判为只有定位")
        self.assertFalse(textfix.has_verbatim_excerpt(boundary), "真边界不该判为原文摘录")


class LatexRepairTest(unittest.TestCase):
    def test_form1_merged_command(self):
        """形态 1：命令名被前一个命令吞并（被剥掉的控制字符）。"""
        self.assertEqual(BS + "cos" + BS + "alpha", fix(BS + "coslpha"))
        self.assertEqual(BS + "cdot" + BS + "vec", fix(BS + "cdotec"))
        self.assertEqual(BS + "le" + BS + "frac", fix(BS + "lerac"))
        self.assertEqual(BS + "Leftrightarrow" + BS + "vec",
                         fix(BS + "Leftrightarrowec"))

    def test_form2_control_char_prefix(self):
        """形态 2：命令尾巴被控制字符孤立（被保留的 TAB/CR/LF）。"""
        self.assertEqual(BS + "theta", fix(TAB + "heta"))
        self.assertEqual(BS + "right", fix(CR + "ight"))
        self.assertEqual(BS + "notin", fix(LF + "otin"))
        self.assertEqual(BS + "times", fix(TAB + "imes"))

    def test_form3_bare_fragment(self):
        """形态 3：命令在 token 开头，剥掉转义字符后只剩裸残片。"""
        self.assertEqual(BS + "frac{a+b}{2}", fix("rac{a+b}{2}"))
        self.assertEqual(BS + "vec{a}", fix("ec{a}"))
        self.assertEqual(BS + "alpha", fix("lpha"))

    def test_form3_after_variable(self):
        r"""前缀是变量而不是命令时也要修（$x\vec{i}$ -> $xec{i}$）。"""
        self.assertEqual("x" + BS + "vec{i}", fix("xec{i}"))
        self.assertEqual("y" + BS + "vec{j}", fix("yec{j}"))

    def test_ambiguous_tails_only_inside_math(self):
        """ar/eta 只在 $...$ 内修，避免英文单词被误伤。"""
        self.assertEqual("$" + BS + "beta$", fix("$eta$"))
        self.assertEqual("$" + BS + "bar{x}$", fix("$ar{x}$"))
        # 数学模式外不动
        self.assertEqual("the eta is", fix("the eta is"))
        self.assertEqual("a bar chart", fix("a bar chart"))

    def test_midword_fragment_is_not_touched(self):
        r"""\nRightarrow 内部的 "ar" 不是残片，不能被改成 \bar（曾造成破坏性误改）。"""
        text = "$q" + LF + "Rightarrow p$"
        self.assertEqual("$q" + BS + "nRightarrow p$", fix(text))

    def test_real_commands_untouched(self):
        for text in (
            BS + "frac{a}{b}", BS + "dfrac{a}{b}", BS + "Rightarrow",
            BS + "triangle ABC", BS + "leqslant", BS + "theta" + BS + "in[0," + BS + "pi]",
            BS + "cdot" + BS + "vec{a}", BS + "parallel",
        ):
            with self.subTest(text=text):
                self.assertEqual(text, fix(text))

    def test_full_materials_no_longer_flagged(self):
        """真实语料上修复后不应再被判为损坏。"""
        from kb_build import pack_io
        remaining = 0
        for _path, material in pack_io.load_materials():
            for field in ("title", "summaryMarkdown", "applicabilityMarkdown",
                          "contentMarkdown", "boundaryMarkdown"):
                text = material.get(field) or ""
                if gate._latex_damaged(fix(text)):
                    remaining += 1
                    break
        self.assertEqual(0, remaining, f"仍有 {remaining} 条材料未被修复")


class ControlJunkTest(unittest.TestCase):
    def test_keeps_newline_converts_tab(self):
        self.assertEqual("a\nb c", textfix.strip_control_junk("a\nb\tc"))

    def test_removes_non_whitespace_controls(self):
        for ch in (chr(12), chr(8), chr(7), chr(11), chr(13)):
            self.assertEqual("ab", textfix.strip_control_junk("a" + ch + "b"))


if __name__ == "__main__":
    unittest.main()
