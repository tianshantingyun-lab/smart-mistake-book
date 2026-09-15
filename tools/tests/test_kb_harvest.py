# -*- coding: utf-8 -*-
"""转录件 → 权威表的解析规则测试。

这两个解析器各自已经**静默丢过一批数据**，都是"跑完不报错、结果少一截"的形态：

- `harvest_placement._split_renjiao`：册名本身含「第」（`必修第一册`），按首个
  `第X章` 切会把 `必修` 当册；只认中文数字章号，会把生物的 `第2章` 整批判成缺章名。
- `resolve_points.normalize`：库内写 `Fe(OH)3`、转录件写 `Fe(OH)₃ `，按字面比会把
  同一条判成"库内没有"从而重复新建。

因此这里逐条钉住这两条规则，而不是只测 happy path。
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from kb_build import harvest_placement, resolve_points, wusan_route


class SplitRenjiaoTest(unittest.TestCase):
    def test_chinese_chapter_number(self):
        self.assertEqual(
            ("必修第一册", "第一章 物质及其变化"),
            harvest_placement._split_renjiao("必修第一册 第一章 物质及其变化"))

    def test_arabic_chapter_number_in_biologys_own_wording(self):
        """生物转录件写的是 `第2章`。只认中文数字会把这批整批判成缺章名。"""
        self.assertEqual(
            ("必修1《分子与细胞》", "第2章 组成细胞的分子 第1节"),
            harvest_placement._split_renjiao("必修1《分子与细胞》 第2章 组成细胞的分子 第1节"))

    def test_book_name_containing_di_is_not_mistaken_for_the_chapter(self):
        """册名含「第」：取**最后一个** `第X章`，左边一律是册名。"""
        self.assertEqual(
            ("化学选择性必修1 化学反应原理", "第三章 水溶液中的离子反应与平衡"),
            harvest_placement._split_renjiao("化学选择性必修1 化学反应原理 第三章 水溶液中的离子反应与平衡"))

    def test_no_chapter_leaves_the_value_whole(self):
        book, chapter = harvest_placement._split_renjiao("全书目录（五部分 24 章）")
        self.assertEqual("全书目录（五部分 24 章）", book)
        self.assertEqual("", chapter)


PAGE = """# 生物精讲册 页转录 · PDF p0006（内容页 2）

- 书内定位：第一部分 分子与细胞 / 第二章 组成细胞的分子 / 第1节 细胞中的元素和无机物 / 知识清单
- 对应人教版：必修1《分子与细胞》 第2章 组成细胞的分子 第1节
- 页图：`build/wusan-render/biology/p0006.jpg`

## 知识清单
"""


class ParseTranscriptTest(unittest.TestCase):
    def test_header_fields_are_split_into_book_and_chapter(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "p0006.md"
            path.write_text(PAGE, encoding="utf-8")
            row = harvest_placement._parse(path, "BIOLOGY")
        self.assertEqual("BIOLOGY", row.subject)
        self.assertEqual("0006", row.pdf_page)
        self.assertEqual("2", row.content_page)
        self.assertEqual("必修1《分子与细胞》", row.renjiao_book)
        self.assertEqual("第2章 组成细胞的分子 第1节", row.renjiao_chapter)
        self.assertEqual("第一部分 分子与细胞", row.chapter_hint)


class NormalizeTest(unittest.TestCase):
    def test_subscript_digits_are_folded_to_ascii(self):
        """库内 `Fe(OH)3` 与转录件 `Fe(OH)₃` 必须归一到同一个键。"""
        self.assertEqual(resolve_points.normalize("Fe(OH)3胶体的制备"),
                         resolve_points.normalize("Fe(OH)₃ 胶体的制备"))

    def test_latex_wrappers_are_stripped(self):
        self.assertEqual(resolve_points.normalize("CO2"),
                         resolve_points.normalize(r"$\mathrm{CO_2}$"))

    def test_near_synonyms_are_not_folded(self):
        """只归并同义写法，不归并近义概念——后者必须人工判。"""
        self.assertNotEqual(resolve_points.normalize("胶体的性质"),
                            resolve_points.normalize("胶体的性质及应用"))

    def test_case_and_full_width_are_folded(self):
        self.assertEqual(resolve_points.normalize("pH"), resolve_points.normalize("ＰＨ"))


class RouteToCanonicalChapterTest(unittest.TestCase):
    """页头自由文本 → 知识库规范(册,章)。

    两边写法差得很远（转录件写 `必修1《分子与细胞》`、库里叫 `生物学必修1`；
    章号一边中文一边阿拉伯），归一必须只发生在这一处；归错了整章会挂到别处。
    """

    KNOWN = {
        "CHEMISTRY": {("化学必修第一册", "第一章 物质及其变化"),
                      ("化学选择性必修1", "第二章 化学反应速率与化学平衡")},
        "BIOLOGY": {("生物学必修1", "第五章 细胞的能量供应和利用")},
    }

    def test_arabic_and_chinese_chapter_numbers_are_equivalent(self):
        book, chapter, _also, note = wusan_route.route(
            "BIOLOGY", "必修1《分子与细胞》 第5章 细胞的能量供应和利用", self.KNOWN["BIOLOGY"])
        self.assertEqual(("生物学必修1", "第五章 细胞的能量供应和利用"), (book, chapter))
        self.assertEqual("", note)

    def test_book_alias_gains_its_subject_prefix(self):
        book, chapter, _also, _note = wusan_route.route(
            "CHEMISTRY", "必修第一册 第一章 物质及其变化", self.KNOWN["CHEMISTRY"])
        self.assertEqual("化学必修第一册", book)
        self.assertEqual("第一章 物质及其变化", chapter)

    def test_cross_book_page_takes_the_first_mention_and_records_the_rest(self):
        book, chapter, also, _note = wusan_route.route(
            "CHEMISTRY",
            "必修第一册 第一章 物质及其变化；化学选择性必修1 第二章 化学反应速率与化学平衡",
            self.KNOWN["CHEMISTRY"])
        self.assertEqual(("化学必修第一册", "第一章 物质及其变化"), (book, chapter))
        self.assertEqual(["化学选择性必修1 第二章 化学反应速率与化学平衡"], also)

    def test_chapter_the_library_does_not_have_is_left_unrouted(self):
        """库里没有这一章时不猜——那正是需要补章节点的地方，交人处理。"""
        book, chapter, _also, note = wusan_route.route(
            "BIOLOGY", "必修2 遗传与进化 第9章 库里没有的章", self.KNOWN["BIOLOGY"])
        self.assertEqual(("", ""), (book, chapter))
        self.assertIn("章名不在规范表内", note)

    def test_book_only_page_is_not_guessed_into_a_chapter(self):
        book, chapter, _also, note = wusan_route.route(
            "CHEMISTRY", "必修第一册", self.KNOWN["CHEMISTRY"])
        self.assertEqual(("", ""), (book, chapter))
        self.assertIn("只有册无章", note)


class BookPatternTest(unittest.TestCase):
    """册名识别。库内册名写法不统一（`化学必修第一册` vs `生物学必修1`），
    而 `必修1` 又是 `选择性必修1` 的子串——这两条各自都能安静地把整页挂错册。"""

    def test_chinese_numeral_book_is_recognized_in_all_writeings(self):
        for text in ("必修第一册", "必修1", "必修一"):
            with self.subTest(text=text):
                self.assertIsNotNone(wusan_route.book_pattern("化学必修第一册").search(text))

    def test_plain_required_book_does_not_match_inside_selective_required(self):
        """`必修第三册` 是 `选择性必修第三册` 的子串。没有守卫就会把选必三的页
        判成必修三的页——整册内容挂错位置。"""
        pattern = wusan_route.book_pattern("物理必修第三册")
        self.assertIsNone(pattern.search("选择性必修第三册 第二章 气体、固体和液体"))
        self.assertIsNotNone(pattern.search("必修第三册 第九章 静电场"))

    def test_selective_required_book_matches_its_own_writing(self):
        self.assertIsNotNone(
            wusan_route.book_pattern("物理选择性必修第三册").search("选择性必修第三册 第二章 气体"))


class ChapterMentionTest(unittest.TestCase):
    def test_section_number_after_chapter_is_not_taken_as_the_chapter_title(self):
        """页头写 `第2章 第4节 蛋白质是……`。不剥节号就会造出 `第2章 第4节`
        这样一个根本不存在的章名；剥掉后章名留空——章号已经够定位了。"""
        self.assertEqual([(0, 2, "")],
                         wusan_route._chapter_mentions("第2章 第4节 蛋白质是生命活动的主要承担者"))

    def test_plain_mention_keeps_its_title(self):
        self.assertEqual([(0, 5, "细胞的能量供应和利用")],
                         wusan_route._chapter_mentions("第5章 细胞的能量供应和利用"))


class RoutelessBookHintTest(unittest.TestCase):
    """页头只写章不写册时（册在另一个字段里）仍要能归位。

    实现上曾经要求"册 + 章都要有"才归位，于是把这类页全部判成"归不了"——
    实测生物整批 147 页一度被挡在门外。
    """

    KNOWN = {"BIOLOGY": {("生物学必修1", "第五章 细胞的能量供应和利用"),
                         ("生物学必修2", "第一章 遗传因子的发现")}}

    def test_chapter_only_hint_still_routes(self):
        book, chapter, _also, note = wusan_route.route(
            "BIOLOGY", "第5章 细胞的能量供应和利用", self.KNOWN["BIOLOGY"])
        self.assertEqual(("生物学必修1", "第五章 细胞的能量供应和利用"), (book, chapter))
        self.assertEqual("", note)

    def test_unknown_chapter_number_reports_the_book_it_is_missing_from(self):
        _book, _chapter, _also, note = wusan_route.route(
            "BIOLOGY", "必修1《分子与细胞》 第9章 库里没有的章", self.KNOWN["BIOLOGY"])
        self.assertIn("里没有第 9 章", note)


if __name__ == "__main__":
    unittest.main()
