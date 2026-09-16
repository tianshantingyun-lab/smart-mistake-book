# -*- coding: utf-8 -*-
"""章层知识点下移到主题层的用例。

它消灭的失败：一个知识点直接挂在"章"下（章被当知识点容器用）。下移 = 改点的
parentSlug + 建主题，不删点不改 slug 不碰材料绑定。
"""

from __future__ import annotations

import unittest

from kb_build import pack_io, relocate_chapter_points as rel


def pt(slug: str) -> dict:
    return {"slug": slug, "name": slug, "aliases": [], "kind": "CONCEPT",
            "boundary": "b", "sourceLocator": "l", "prerequisiteSlugs": []}


def chapter(name: str, slug: str, points: list[dict]) -> dict:
    return {"slug": slug, "name": name, "sourceLocator": "loc",
            "knowledgePoints": points}


def pack_with_chapter(points: list[dict]) -> dict:
    return {
        "schemaVersion": 2, "packId": "t", "taxonomyVersion": "t", "sourceNamespace": "n",
        "reviewedAtEpochMillis": 1, "sourceUri": "https://e.t",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL", "teachingSupportLevel": "PARTIAL"},
        "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64,
                      "topics": [chapter("第一章", "ch", points)]}],
    }


class RelocateTest(unittest.TestCase):
    def test_moves_point_under_new_theme(self):
        pack = pack_with_chapter([pt("a"), pt("b")])
        n = rel.relocate(pack, {("MATH", "a"): "函数"})
        self.assertEqual(1, n)
        topics = pack["subjects"][0]["topics"]
        ch = next(t for t in topics if t["slug"] == "ch")
        theme = next(t for t in topics if t["name"] == "函数")
        self.assertEqual(["b"], [p["slug"] for p in ch["knowledgePoints"]])
        self.assertEqual(["a"], [p["slug"] for p in theme["knowledgePoints"]])
        self.assertEqual("ch", theme["parentSlug"])
        self.assertEqual("函数", theme["name"], "主题名是层内名，不含章名")

    def test_points_reuse_same_theme(self):
        pack = pack_with_chapter([pt("a"), pt("b")])
        rel.relocate(pack, {("MATH", "a"): "函数", ("MATH", "b"): "函数"})
        themes = [t for t in pack["subjects"][0]["topics"] if t["name"] == "函数"]
        self.assertEqual(1, len(themes), "两个点归同一主题，只能建一个主题 topic")
        self.assertEqual(2, len(themes[0]["knowledgePoints"]))

    def test_is_idempotent(self):
        pack = pack_with_chapter([pt("a")])
        self.assertEqual(1, rel.relocate(pack, {("MATH", "a"): "函数"}))
        self.assertEqual(0, rel.relocate(pack, {("MATH", "a"): "函数"}), "第二次必须 0 移动")

    def test_preserves_point_count_and_slugs(self):
        pack = pack_with_chapter([pt("a"), pt("b"), pt("c")])
        before = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
        rel.relocate(pack, {("MATH", "a"): "函数", ("MATH", "c"): "集合"})
        after = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
        self.assertEqual(before, after, "slug 集合必须不变——只挪位置，不增删点")

    def test_slug_not_in_pack_raises(self):
        """表引用了不存在的 slug（写成 name 或拼错）必须报错，不许静默忽略。"""
        pack = pack_with_chapter([pt("a")])
        with self.assertRaises(ValueError):
            rel.relocate(pack, {("MATH", "no-such-slug"): "函数"})


class RealPackTest(unittest.TestCase):
    def test_relocation_is_idempotent_on_shipped(self):
        """当前表对已成品的成品包必须幂等（点已在主题下）——否则重放不幂等。"""
        pack = pack_io.load_json(pack_io.pack_path())
        reloc = rel.load_relocations()
        self.assertEqual(0, rel.relocate(pack, reloc))

    def test_shipped_chapter_layer_is_20(self):
        """成品章层挂点的当前快照。

        167（原始）→ 64（归位真知识点）→ 33（删 31 无材料残渣）→ 20
        （再删 11 个被错误前置引用的纯题干垃圾 + 2 点改名归位）。
        剩余 20 = "题目被当成知识点"的节点（名字是题干、挂着材料解析）+ 2 个带★错挂
        进化内容的点——它们是材料重绑/跨章问题，属绑定门（Phase 5），非归位能解决。
        """
        pack = pack_io.load_json(pack_io.pack_path())
        self.assertEqual(20, rel.chapter_layer_count(pack))

    def test_new_themes_have_local_names(self):
        """下移新建的主题名必须是层内名（不含 ·），否则会重新引入 I-01 的冗余。"""
        pack = pack_io.load_json(pack_io.pack_path())
        from kb_build import shorten_topic_names as st
        self.assertEqual([], st.names_carrying_parent_path(pack))


if __name__ == "__main__":
    unittest.main()
