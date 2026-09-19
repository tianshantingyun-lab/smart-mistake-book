# -*- coding: utf-8 -*-
"""拓扑级归位（主题 → 另一主题，可跨册）的用例。

它消灭的失败：视觉转写新建的方法类节点被批量放进"综合"桶或兜底主题，语义偏粗；
`relocate_chapter_points` 只覆盖"章层→主题层"，这类移动没有工具。本模块补上，
并且**必须同时改写 boundary 的定位串与章表行**，否则门禁的归属一致性会掉。
"""

from __future__ import annotations

import csv
import tempfile
import unittest
from pathlib import Path

from kb_build import relocate_points as rel


def point(slug: str, boundary: str = "定位：数学必修第一册 第三章。真边界。") -> dict:
    return {"slug": slug, "name": slug, "aliases": [], "kind": "CONCEPT",
            "boundary": boundary, "sourceLocator": "《测试》视觉转写",
            "prerequisiteSlugs": []}


def topic(slug: str, name: str, points: list[dict], parent: str | None = None) -> dict:
    return {"slug": slug, "name": name, "parentSlug": parent,
            "sourceLocator": f"定位：{slug}", "knowledgePoints": points}


def pack_with(topics: list[dict]) -> dict:
    return {
        "schemaVersion": 2, "packId": "t", "taxonomyVersion": "t", "sourceNamespace": "n",
        "reviewedAtEpochMillis": 1, "sourceUri": "https://e.t",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL", "teachingSupportLevel": "PARTIAL"},
        "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64, "topics": topics}],
    }


ROWS = [{"subject": "MATH", "slug": "传送带模型", "to_topic_slug": "数学必修第一册·第二章·数列",
         "reason": "动力学模型应归力学章"}]


class PlaceOfTopicTest(unittest.TestCase):
    def test_teaching_topic_uses_book_and_chapter(self):
        self.assertEqual("物理必修第一册 第三章",
                         rel.place_of_topic("物理必修第一册·第三章·相互作用"))

    def test_synthesis_chain_maps_to_subject_scope(self):
        self.assertEqual("PHYSICS·综合·综合", rel.place_of_topic("PHYSICS·综合·综合·综合"))

    def test_single_segment_topic_keeps_itself(self):
        self.assertEqual("化学实验基础", rel.place_of_topic("化学实验基础"))


class RelocateTest(unittest.TestCase):
    def _pack(self):
        return pack_with([
            topic("数学必修第一册·第二章", "第二章", [], parent="数学必修第一册"),
            topic("数学必修第一册·第二章·数列", "数列", [], parent="数学必修第一册·第二章"),
            topic("MATH·综合", "跨册综合", [], parent=None),
            topic("MATH·综合·综合", "综合复习", [], parent="MATH·综合"),
            topic("MATH·综合·综合·综合", "综合复习", [point("传送带模型")],
                  parent="MATH·综合·综合"),
        ])

    def test_moves_point_and_rewrites_locator(self):
        pack = self._pack()
        stats = rel.relocate(pack, ROWS)
        self.assertEqual(1, stats["moved"])
        self.assertEqual([], stats["errors"])
        topics = {t["slug"]: t for t in pack["subjects"][0]["topics"]}
        self.assertEqual([], topics["MATH·综合·综合·综合"]["knowledgePoints"])
        moved = topics["数学必修第一册·第二章·数列"]["knowledgePoints"][0]
        self.assertEqual("传送带模型", moved["slug"])
        self.assertTrue(moved["boundary"].startswith("定位：数学必修第一册 第二章。"),
                        moved["boundary"])
        self.assertIn("真边界", moved["boundary"], "原边界正文不能丢")

    def test_point_total_is_conserved(self):
        pack = self._pack()
        before = sum(len(t["knowledgePoints"]) for t in pack["subjects"][0]["topics"])
        rel.relocate(pack, ROWS)
        after = sum(len(t["knowledgePoints"]) for t in pack["subjects"][0]["topics"])
        self.assertEqual(before, after)

    def test_idempotent_second_run_skips(self):
        pack = self._pack()
        rel.relocate(pack, ROWS)
        stats = rel.relocate(pack, ROWS)
        self.assertEqual(0, stats["moved"])
        self.assertEqual(1, stats["skipped"])

    def test_unknown_target_is_an_error_and_nothing_moves(self):
        pack = self._pack()
        bad = [{**ROWS[0], "to_topic_slug": "不存在·主题"}]
        stats = rel.relocate(pack, bad)
        self.assertEqual(0, stats["moved"])
        self.assertEqual(1, len(stats["errors"]))
        self.assertEqual(1, len(pack["subjects"][0]["topics"][-1]["knowledgePoints"]))

    def test_unknown_point_is_an_error(self):
        pack = self._pack()
        stats = rel.relocate(pack, [{**ROWS[0], "slug": "查无此点"}])
        self.assertEqual(1, len(stats["errors"]))

    def test_boundary_without_locator_gets_one(self):
        pack = self._pack()
        pack["subjects"][0]["topics"][-1]["knowledgePoints"][0]["boundary"] = "只写了边界正文。"
        rel.relocate(pack, ROWS)
        moved = pack["subjects"][0]["topics"][1]["knowledgePoints"][0]
        self.assertEqual("定位：数学必修第一册 第二章。只写了边界正文。", moved["boundary"])


class CreateTopicsTest(unittest.TestCase):
    def test_creates_leaf_and_missing_ancestors_with_parent_chain(self):
        pack = pack_with([topic("数学必修第一册", "数学必修第一册", [])])
        rows = [{"subject": "MATH", "slug": "数学必修第一册·第三章·函数与导数",
                 "name": "函数与导数", "reason": "新建主题层容器"}]
        stats = rel.create_topics(pack, rows)
        self.assertEqual(2, stats["created"], "补 1 个祖先 + 建 1 个叶子")
        by_slug = {t["slug"]: t for t in pack["subjects"][0]["topics"]}
        self.assertEqual("数学必修第一册", by_slug["数学必修第一册·第三章"]["parentSlug"])
        self.assertEqual("数学必修第一册·第三章",
                         by_slug["数学必修第一册·第三章·函数与导数"]["parentSlug"])

    def test_idempotent_and_never_duplicates(self):
        pack = pack_with([topic("数学必修第一册", "数学必修第一册", [])])
        rows = [{"subject": "MATH", "slug": "数学必修第一册·第三章·函数与导数",
                 "name": "函数与导数", "reason": "r"}]
        rel.create_topics(pack, rows)
        stats = rel.create_topics(pack, rows)
        self.assertEqual(0, stats["created"])
        self.assertEqual(1, stats["skipped"])


class TableValidationTest(unittest.TestCase):
    def _write(self, text: str) -> Path:
        tmp = Path(tempfile.mkdtemp()) / "point_relocation.csv"
        tmp.write_text(text, encoding="utf-8")
        return tmp

    def test_missing_reason_rejected(self):
        path = self._write("subject,slug,to_topic_slug,reason\nMATH,a,b,\n")
        with self.assertRaises(ValueError):
            rel.load_relocations(path)

    def test_duplicate_rows_rejected(self):
        path = self._write("subject,slug,to_topic_slug,reason\nMATH,a,b,r\nMATH,a,b,r\n")
        with self.assertRaises(ValueError):
            rel.load_relocations(path)


class SyncChapterMapTest(unittest.TestCase):
    def test_row_follows_teaching_target_and_is_removed_for_synthesis(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "chapter_map.csv"
            with path.open("w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow(["subject", "slug", "volume", "chapter", "theme"])
                w.writerow(["MATH", "传送带模型", "MATH·综合", "综合", ""])
                w.writerow(["MATH", "另一点", "数学必修第一册", "第三章", "函数"])
            moves = [{"subject": "MATH", "slug": "传送带模型",
                      "to_topic": "数学必修第一册·第二章·数列"}]
            stats = rel.sync_chapter_map(moves, path)
            self.assertEqual({"updated": 1, "removed": 0}, stats)
            rows = list(csv.DictReader(path.open(encoding="utf-8")))
            row = next(r for r in rows if r["slug"] == "传送带模型")
            self.assertEqual(("数学必修第一册", "第二章", "数列"),
                             (row["volume"], row["chapter"], row["theme"]))
            moves = [{"subject": "MATH", "slug": "传送带模型",
                      "to_topic": "MATH·综合·综合·综合"}]
            stats = rel.sync_chapter_map(moves, path)
            self.assertEqual({"updated": 0, "removed": 1}, stats)


if __name__ == "__main__":
    unittest.main()
