# -*- coding: utf-8 -*-
"""`report_chapter_locator_mismatch` 的用例。

它消灭的失败：门报的 `chapter_locator_mismatch` 是**一个数字**，里面混着"同章两种写法"
（归属一致）与"归属真的不同"（要逐条判）。把两类混在一起，处置必然出错：
按章表批量重写会把真错的固化，当作格式问题放过又会让真错的永远不被发现。
所以分类判据必须被钉住，且**总数必须等于门的数字**——否则这份报告会悄悄与门脱节。
"""

from __future__ import annotations

import unittest

from kb_build import gate, pack_io, report_chapter_locator_mismatch as R


class ClassifyTest(unittest.TestCase):
    def test_prefix_only(self):
        self.assertEqual("prefix_only", R.classify(
            ("数学必修第一册", "数列"), ("数学必修第一册", "第四章 数列")))

    def test_same_chapter_two_writings(self):
        self.assertEqual("same_chapter_alias", R.classify(
            ("化学必修第一册", "铁与金属材料"), ("化学必修第一册", "第三章 铁 金属材料")))

    def test_renamed_chapter_is_conservatively_left_for_humans(self):
        """章名改写幅度大时（`水溶液中的离子平衡` vs `…离子反应与平衡`）判到"同册异章"。

        这是**刻意的保守方向**：判据只认"去掉章号与虚词后互相包含"，做不到就交给人判。
        错判成同章会触发自动改写（把真错的固化），错判成异章只是多一条待判——两个方向的
        代价不对称，所以宁可少认。
        """
        self.assertEqual("same_book_other_chapter", R.classify(
            ("化学选择性必修1", "水溶液中的离子平衡"),
            ("化学选择性必修1", "第三章 水溶液中的离子反应与平衡")))

    def test_same_book_other_chapter(self):
        self.assertEqual("same_book_other_chapter", R.classify(
            ("数学必修第一册", "函数的概念与性质"),
            ("数学必修第一册", "第四章 指数函数与对数函数")))

    def test_cross_book(self):
        self.assertEqual("cross_book", R.classify(
            ("数学必修第二册", "平面向量"), ("数学选择性必修第一册", "第一章 空间向量与立体几何")))

    def test_identical_is_not_a_mismatch(self):
        self.assertEqual("same", R.classify(
            ("化学必修第一册", "第三章 铁 金属材料"), ("化学必修第一册", "第三章 铁 金属材料")))


class ShippedPackTest(unittest.TestCase):
    def test_total_matches_the_gate_metric(self):
        """分类报告的总数必须与门的 `chapter_locator_mismatch` 一致，否则两边会各自漂移。"""
        rows = R.collect(pack_io.load_json(pack_io.pack_path()))
        expected = {m.key: m.value for m in gate.evaluate()}["chapter_locator_mismatch"]
        self.assertEqual(expected, len(rows))

    def test_both_classes_are_present_on_the_shipped_pack(self):
        """两类都要非零——只有一类就说明判据退化了（要么全当同章、要么全当真错）。"""
        rows = R.collect(pack_io.load_json(pack_io.pack_path()))
        mechanical = [r for r in rows if r["kind"] in ("prefix_only", "same_chapter_alias")]
        hard = [r for r in rows if r["kind"] in ("same_book_other_chapter", "cross_book")]
        self.assertTrue(mechanical, "同章两种写法的一类不该为空")
        self.assertTrue(hard, "归属真的不同的一类不该为空")


if __name__ == "__main__":
    unittest.main()
