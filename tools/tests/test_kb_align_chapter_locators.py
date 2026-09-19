# -*- coding: utf-8 -*-
"""`align_chapter_locators` 的用例。

它消灭的失败：门的 `chapter_locator_mismatch` 里有一批**只是写法不同**（章号在节点里单独
成段、在章表里并进章名），而门的判据在有 `·` 时取第二段当章名——两边永远对不上。
对齐只该动 `定位：` 那一段，且**不许为了对齐丢信息**。

两条哨兵最要紧：
- **成品已对齐**：`plan` 在成品上必须 0 改动（否则说明有别的写入者又把定位串写回去了）；
- **归属分歧还在**：机械类归零后，分类器里"归属真不同"的一类必须仍非零——
  否则工具越界了（把该由人判的也顺手改了）。
"""

from __future__ import annotations

import unittest

from kb_build import align_chapter_locators as A, pack_io, report_chapter_locator_mismatch as R


def row(subject: str, slug: str, kind: str, book: str, chapter: str) -> dict:
    return {"subject": subject, "slug": slug, "name": slug, "kind": kind,
            "current_book": book, "current_chapter": chapter,
            "declared_book": book, "declared_chapter": chapter}


class PlanTest(unittest.TestCase):
    def test_chapter_number_moves_into_the_chapter_segment(self):
        boundaries = {("MATH", "p"): "定位：数学必修第一册 第三章·函数的概念与性质。正文不动"}
        planned = A.plan({}, [row("MATH", "p", "prefix_only", "数学必修第一册", "数学必修第一册")], boundaries)
        # 上行的 row 只用于分类字段，这里直接构造真正的期望
        rows = [{"subject": "MATH", "slug": "p", "name": "p", "kind": "prefix_only",
                 "current_book": "数学必修第一册", "current_chapter": "函数的概念与性质",
                 "declared_book": "数学必修第一册", "declared_chapter": "第三章 函数的概念与性质"}]
        planned = A.plan({}, rows, boundaries)
        self.assertEqual(1, len(planned["changes"]))
        self.assertEqual("定位：数学必修第一册·第三章 函数的概念与性质。正文不动",
                         planned["changes"][0]["new"])

    def test_section_segment_is_kept(self):
        boundaries = {("PHYSICS", "p"): "定位：物理必修第一册 第一章·运动的描述·1 质点 参考系和坐标系。x"}
        rows = [{"subject": "PHYSICS", "slug": "p", "name": "p", "kind": "prefix_only",
                 "current_book": "物理必修第一册", "current_chapter": "运动的描述",
                 "declared_book": "物理必修第一册", "declared_chapter": "第一章 运动的描述"}]
        planned = A.plan({}, rows, boundaries)
        self.assertEqual("定位：物理必修第一册·第一章 运动的描述·1 质点 参考系和坐标系。x",
                         planned["changes"][0]["new"])

    def test_content_after_the_first_period_is_untouched(self):
        raw = "定位：数学必修第一册 第三章·函数的概念与性质。（见知识清单/教材）"
        rows = [{"subject": "MATH", "slug": "p", "name": "p", "kind": "prefix_only",
                 "current_book": "数学必修第一册", "current_chapter": "函数的概念与性质",
                 "declared_book": "数学必修第一册", "declared_chapter": "第三章 函数的概念与性质"}]
        new = A.plan({}, rows, {("MATH", "p"): raw})["changes"][0]["new"]
        self.assertTrue(new.endswith("。（见知识清单/教材）"))

    def test_attribution_difference_is_aligned_to_the_table(self):
        """归属类也照章表对齐——2026-09-19 裁定"章表权威"。

        判据是人工定稿的单元级章表（`chapter_by_source.csv`），不是"章名是否被包含"：
        节点写 `概率统计`、章表声明 `第十章 概率`，对齐到章表（抽查最大的 12 个单元，
        章表全部对、节点串全部错）。
        """
        rows = [{"subject": "MATH", "slug": "p", "name": "p", "kind": "same_book_other_chapter",
                 "unit": "专题02 指对幂函数及函数的应用",
                 "current_book": "数学必修第一册", "current_chapter": "函数的概念与性质",
                 "declared_book": "数学必修第一册", "declared_chapter": "第四章 指数函数与对数函数"}]
        planned = A.plan({}, rows, {("MATH", "p"): "定位：数学必修第一册 函数的概念与性质。x"})
        self.assertEqual(1, len(planned["changes"]))
        self.assertEqual("定位：数学必修第一册·第四章 指数函数与对数函数。x", planned["changes"][0]["new"])

    def test_cross_book_difference_is_aligned_to_the_table(self):
        rows = [{"subject": "MATH", "slug": "p", "name": "p", "kind": "cross_book",
                 "unit": "专题02 空间向量与立体几何",
                 "current_book": "数学必修第二册", "current_chapter": "平面向量",
                 "declared_book": "数学选择性必修第一册", "declared_chapter": "第一章 空间向量与立体几何"}]
        planned = A.plan({}, rows, {("MATH", "p"): "定位：数学必修第二册 平面向量。x"})
        self.assertEqual("定位：数学选择性必修第一册·第一章 空间向量与立体几何。x",
                         planned["changes"][0]["new"])


class ShippedPackTest(unittest.TestCase):
    def test_shipped_pack_is_fully_aligned(self):
        """哨兵：对齐之后成品里**不该再有**与章表不一致的节点定位串。

        门指标 `chapter_locator_mismatch` 必须为 0——若这条红了，说明有新内容带着
        旧写法定位串进了包（或章表被改），要么重新对齐、要么把章表修回来。
        """
        pack = pack_io.load_json(pack_io.pack_path())
        self.assertEqual([], R.collect(pack))


if __name__ == "__main__":
    unittest.main()
