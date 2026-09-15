# -*- coding: utf-8 -*-
"""定稿表校验器的测试。

关键一条：必须证明这个检查真能抓住"引错节点"的错误——2026-09-13 定稿时把化学的
盐类水解规律写给了物理节点。检查器若抓不住这种错，就没有存在价值。
"""

from __future__ import annotations

import unittest

from kb_build import verify_tables


class OverlapTest(unittest.TestCase):
    def test_flagged_when_evidence_comes_from_another_node(self):
        """把化学的证据用到物理节点上：重合度必须掉到阈值以下（这正是要抓的错）。"""
        wrong = "盐类水解的规律"
        physics_node = "规律定位：物理选择性必修第一册 第一章·动量。动量守恒：系统的内力远远大于外力…在爆炸过程中动能增加"
        chars = verify_tables._content_chars(verify_tables._GENERIC_SUFFIX.sub("", wrong))
        text = verify_tables._content_chars(physics_node)
        overlap = len(chars & text) / len(chars)
        self.assertLess(overlap, 0.5, "引错节点时必须被判为低重合")

    def test_pass_when_name_reads_its_own_node(self):
        right = "爆炸过程的动量与能量规律"
        physics_node = "规律定位：物理选择性必修第一册 第一章·动量。动量守恒：系统的内力远远大于外力…在爆炸过程中动能增加"
        chars = verify_tables._content_chars(verify_tables._GENERIC_SUFFIX.sub("", right))
        text = verify_tables._content_chars(physics_node)
        overlap = len(chars & text) / len(chars)
        self.assertGreaterEqual(overlap, 0.5, "对得上自己节点的改名不该被误报")

    def test_generic_suffix_is_stripped(self):
        """"…的规律"里的"规律"不算证据，否则任何改名都能靠通用词尾蒙过去。"""
        stripped = verify_tables._GENERIC_SUFFIX.sub("", "盐类水解的规律")
        self.assertEqual("盐类水解", stripped)

    def test_stop_chars_excluded(self):
        chars = verify_tables._content_chars("的与和及等")
        self.assertEqual(set(), chars)


class RealTablesTest(unittest.TestCase):
    def test_real_overrides_load_and_have_no_pending_review(self):
        """定稿表必须与提案表对得上，且不留未定稿行。"""
        from kb_build import tables
        actions = tables.load_node_actions()
        self.assertEqual(0, tables.unresolved_review_count(actions),
                         "还有未定稿的行，说明定稿没做完")
        for key, row in actions.items():
            self.assertIn(row["action"], {"keep", "rename", "merge", "delete", "review"})
            if row["action"] == "rename":
                self.assertTrue(row["new_name"], f"{key} 改名为空")
            if row["action"] == "merge":
                self.assertTrue(row["new_slug"], f"{key} 合并目标为空")


if __name__ == "__main__":
    unittest.main()
