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

    def test_a_bigger_rename_is_skipped_not_forced(self):
        """章名改写幅度大（`概率统计` ⊄ `第十章 概率`）时跳过——那多半是归属问题，交给人。"""
        rows = [{"subject": "MATH", "slug": "p", "name": "p", "kind": "same_chapter_alias",
                 "current_book": "数学必修第一册", "current_chapter": "概率统计",
                 "declared_book": "数学必修第一册", "declared_chapter": "第十章 概率"}]
        planned = A.plan({}, rows, {("MATH", "p"): "定位：数学必修第一册 第十章·概率统计。x"})
        self.assertEqual([], planned["changes"])
        self.assertEqual(1, len(planned["skipped"]))

    def test_attribution_rows_are_not_touched(self):
        rows = [{"subject": "MATH", "slug": "p", "name": "p", "kind": "same_book_other_chapter",
                 "current_book": "数学必修第一册", "current_chapter": "函数的概念与性质",
                 "declared_book": "数学必修第一册", "declared_chapter": "第四章 指数函数与对数函数"}]
        planned = A.plan({}, rows, {("MATH", "p"): "定位：数学必修第一册 第三章·函数的概念与性质。x"})
        self.assertEqual([], planned["changes"])
        self.assertEqual([], planned["skipped"])


class ShippedPackTest(unittest.TestCase):
    def _plan(self):
        pack = pack_io.load_json(pack_io.pack_path())
        rows = R.collect(pack)
        boundaries = {(s, p["slug"]): (p.get("boundary") or "")
                      for s, _t, p in pack_io.iter_points(pack)}
        return rows, A.plan(pack, rows, boundaries)

    def test_shipped_pack_needs_no_further_alignment(self):
        """哨兵：成品里"机器能对齐"的一条都不剩。

        剩下的写法类必须**全部**落在"跳过"那一列（章名改写幅度大、判据不敢认），
        一个都不许还是"待改"——那才是没做完。
        """
        rows, planned = self._plan()
        mechanical = [r for r in rows if r["kind"] in A.MECHANICAL]
        self.assertEqual([], [c["key"] for c in planned["changes"]], "还有没对齐的写法类")
        self.assertEqual({(r["subject"], r["slug"]) for r in mechanical},
                         {key for key, _why in planned["skipped"]},
                         "写法类里有些既没对齐、也没被跳过（分类器与工具脱节）")

    def test_attribution_class_is_still_untouched_and_non_empty(self):
        """归零的只能是写法类；"归属真不同"必须原样留着（工具不许越界替人判）。"""
        rows, _planned = self._plan()
        hard = [r for r in rows if r["kind"] in ("same_book_other_chapter", "cross_book")]
        self.assertTrue(hard, "归属分歧类不该为空")


if __name__ == "__main__":
    unittest.main()
