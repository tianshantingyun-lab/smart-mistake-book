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
        n = rel.relocate(pack, {("MATH", "a"): ("函数", "")})
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
        rel.relocate(pack, {("MATH", "a"): ("函数", ""), ("MATH", "b"): ("函数", "")})
        themes = [t for t in pack["subjects"][0]["topics"] if t["name"] == "函数"]
        self.assertEqual(1, len(themes), "两个点归同一主题，只能建一个主题 topic")
        self.assertEqual(2, len(themes[0]["knowledgePoints"]))

    def _pack_with_deep_theme(self):
        """ch(章) → unit(单元) → 函数(深层主题, 含点 x)；章层另有散点 a。"""
        pack = pack_with_chapter([pt("a")])
        topics = pack["subjects"][0]["topics"]
        topics.append({"slug": "unit", "name": "单元", "sourceLocator": "loc",
                       "parentSlug": "ch", "knowledgePoints": []})
        topics.append({"slug": "deep-func", "name": "函数", "sourceLocator": "loc",
                       "parentSlug": "unit", "knowledgePoints": [pt("x")]})
        return pack

    def test_finds_deep_theme_not_create_shallow_twin(self):
        pack = self._pack_with_deep_theme()
        n = rel.relocate(pack, {("MATH", "a"): ("函数", "")})
        self.assertEqual(1, n)
        themes = [t for t in pack["subjects"][0]["topics"] if t["name"] == "函数"]
        self.assertEqual(1, len(themes), "必须复用深层主题，不得新建浅层同名分支")
        self.assertEqual({"a", "x"}, {p["slug"] for p in themes[0]["knowledgePoints"]})

    def test_shallow_duplicate_point_moves_to_deeper_theme(self):
        # 点已在浅层同名分支、深层另有同名主题 → 必须去更深的家（幂等判定不得把浅层当完成态）
        pack = self._pack_with_deep_theme()
        topics = pack["subjects"][0]["topics"]
        topics.append({"slug": "shallow-func", "name": "函数", "sourceLocator": "loc",
                       "parentSlug": "ch", "knowledgePoints": [pt("a")]})
        ch = next(t for t in topics if t["slug"] == "ch")
        ch["knowledgePoints"] = []
        n = rel.relocate(pack, {("MATH", "a"): ("函数", "")})
        self.assertEqual(1, n, "浅层重复分支不算完成态")
        themes = [t for t in topics if t["name"] == "函数"]
        deep = next(t for t in themes if t["slug"] == "deep-func")
        self.assertIn("a", [p["slug"] for p in deep["knowledgePoints"]])

    def test_is_idempotent(self):
        pack = pack_with_chapter([pt("a")])
        self.assertEqual(1, rel.relocate(pack, {("MATH", "a"): ("函数", "")}))
        self.assertEqual(0, rel.relocate(pack, {("MATH", "a"): ("函数", "")}), "第二次必须 0 移动")

    def test_preserves_point_count_and_slugs(self):
        pack = pack_with_chapter([pt("a"), pt("b"), pt("c")])
        before = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
        rel.relocate(pack, {("MATH", "a"): ("函数", ""), ("MATH", "c"): ("集合", "")})
        after = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
        self.assertEqual(before, after, "slug 集合必须不变——只挪位置，不增删点")

    def test_slug_not_in_pack_raises(self):
        """表引用了不存在的 slug（写成 name 或拼错）必须报错，不许静默忽略。"""
        pack = pack_with_chapter([pt("a")])
        with self.assertRaises(ValueError):
            rel.relocate(pack, {("MATH", "no-such-slug"): ("函数", "")})


class RealPackTest(unittest.TestCase):
    def test_relocation_is_idempotent_on_shipped(self):
        """当前表对已成品的成品包必须幂等（点已在主题下）——否则重放不幂等。"""
        pack = pack_io.load_json(pack_io.pack_path())
        reloc = rel.load_relocations()
        self.assertEqual(0, rel.relocate(pack, reloc), "重放必须幂等")

    def test_shipped_chapter_layer_is_2(self):
        """成品章层挂点的当前快照。

        167（原始）→ 64（归位真知识点）→ 33（删 31 无材料残渣）→ 20
        （再删 11 个被错误前置引用的纯题干垃圾 + 2 点改名归位）→ 12。
        2026-09-19 又归位 6 个（3 个先经坏名分流改名成合法知识点——键线式 / 淀粉水解产物的检验 /
        石油裂解的产物；另 3 个本就是合法点——晶体类型与晶胞构型的判断 / 卤代烃中卤素原子的检验 /
        乙醇的性质与转化）→ 6。
        同日再读材料复核又归位 2 个（`碳元素的单质有多种形式` 的 4 条材料全是成体系知识 →
        改名 `碳单质与同素异形体`；`一种2-甲基色酮内酯` 的材料是《黄鸣龙还原法》真知识 →
        改名 `黄鸣龙还原法`）→ **4**。
        剩余 4 **刻意不归位**：
        - 化学 1（`(1)写出分子式为C5H12的烷烃的结构简式：`）——它和它唯一的材料都是题干，
          等"连材料一起删"的裁定；
        - 生物 3（`实验：探究抗生素对细菌的选择作用`、`隔离在物种形成中的作用`、`结束后…`）——
          前两条其实是必修2 第六章「生物的进化」的内容挂在选必3 下，属**归属**问题
          （走 chapter_locator_clusters 的簇判定）；第三条名字/别名/边界三方不一致，
          要先看它绑的材料。

        2026-09-19 用户批准那条删除后执行 → **3**（点数 2425→2424，材料随删 1 条）。
        同日读 `结束后…` 绑的材料（《培养基的分类》《鉴别培养基与指示剂的使用》，成体系知识）→
        改名 `培养基的分类与使用` 并归位到 `微生物的培养技术及应用` → **2**：
        剩下两条（`隔离在物种形成中的作用`、`实验：探究抗生素对细菌的选择作用`）**刻意留在章层**——
        它们的定位串已按章表对齐到必修2 第六章「生物的进化」，但树结构上挂在选必3 的
        「生物技术与工程」子树里（该子树混着必修2 第六章的主题），硬归位只是把错误固定，
        要等主题树归属整理那一轮。
        """
        pack = pack_io.load_json(pack_io.pack_path())
        self.assertEqual(2, rel.chapter_layer_count(pack))

    def test_new_themes_have_local_names(self):
        """下移新建的主题名必须是层内名（不含 ·），否则会重新引入 I-01 的冗余。"""
        pack = pack_io.load_json(pack_io.pack_path())
        from kb_build import shorten_topic_names as st
        self.assertEqual([], st.names_carrying_parent_path(pack))


if __name__ == "__main__":
    unittest.main()
