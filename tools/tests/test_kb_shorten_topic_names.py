# -*- coding: utf-8 -*-
"""主题名收敛成"层内名"这条转换的用例。

它消灭的失败：改前 445 个 topic 里 421 个的 `name` 重复了父名全文，于是逐层展开时同一段
文字被重复四遍——渐进式披露在数据层就不可表达。

用例分两类：
- **合成夹具**测转换本身的语义（剥前缀、幂等、不碰别的字段、无前缀就原样保留）；
- **真实成品包**测不变量（重建的全路径名必须逐字等于原名；残留必须恰好是已知的「综合」链）。
"""

from __future__ import annotations

import copy
import unittest

from kb_build import pack_io, shorten_topic_names as st


def topic(slug: str, name: str, parent: str | None = None, points: int = 0) -> dict:
    return {
        "slug": slug,
        "name": name,
        "sourceLocator": "loc",
        "parentSlug": parent,
        "knowledgePoints": [
            {"slug": f"{slug}-p{i}", "name": f"点{i}", "aliases": [], "kind": "CONCEPT",
             "boundary": "b", "sourceLocator": "l", "prerequisiteSlugs": []}
            for i in range(points)
        ],
    }


def mini_pack(topics: list[dict]) -> dict:
    return {
        "schemaVersion": 2,
        "packId": "test-pack",
        "taxonomyVersion": "test-pack",
        "sourceNamespace": "t",
        "reviewedAtEpochMillis": 1,
        "sourceUri": "https://example.test",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL",
                     "teachingSupportLevel": "PARTIAL"},
        "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64, "topics": topics}],
    }


class ShortenSemanticsTest(unittest.TestCase):
    def test_strips_parent_prefix_at_every_level(self):
        pack = mini_pack([
            topic("册", "数学必修第一册"),
            topic("册·章", "数学必修第一册·第三章", parent="册"),
            topic("册·章·节", "数学必修第一册·第三章·函数", parent="册·章"),
        ])
        self.assertEqual(2, st.shorten(pack))
        names = [t["name"] for t in pack["subjects"][0]["topics"]]
        self.assertEqual(["数学必修第一册", "第三章", "函数"], names)

    def test_is_idempotent(self):
        pack = mini_pack([
            topic("a", "册"),
            topic("b", "册·章", parent="a"),
        ])
        self.assertEqual(1, st.shorten(pack))
        # 第二次跑必须一条都不改——否则"跑两次结果不同"会让重放不可信
        self.assertEqual(0, st.shorten(pack))

    def test_leaves_alone_when_name_does_not_restate_parent(self):
        """名字不复述父名时不许乱剥：那是内容问题，不是路径冗余。"""
        pack = mini_pack([
            topic("a", "册"),
            topic("b", "另一个名字", parent="a"),
        ])
        self.assertEqual(0, st.shorten(pack))
        self.assertEqual("另一个名字", pack["subjects"][0]["topics"][1]["name"])

    def test_touches_nothing_but_name(self):
        """slug 是身份（parentSlug 引用它），知识点/定位串都不是本转换的对象。"""
        pack = mini_pack([
            topic("a", "册", points=2),
            topic("b", "册·章", parent="a", points=3),
        ])
        before = copy.deepcopy(pack)
        st.shorten(pack)
        for old, new in zip(before["subjects"][0]["topics"], pack["subjects"][0]["topics"]):
            self.assertEqual(old["slug"], new["slug"])
            self.assertEqual(old["parentSlug"], new["parentSlug"])
            self.assertEqual(old["sourceLocator"], new["sourceLocator"])
            self.assertEqual(old["knowledgePoints"], new["knowledgePoints"])

    def test_rebuild_reproduces_the_original_full_path(self):
        """无损的定义：由树重建的全路径名逐字等于转换前的名字。

        这条必须在**转换前**的名字上测。回写之后被抹平的那一层已无法从树里重建，
        再拿它比对是自证——所以这条用合成夹具，而不是成品包。
        """
        pack = mini_pack([
            topic("册", "数学必修第一册"),
            topic("册·章", "数学必修第一册·第三章", parent="册"),
            topic("册·章·节", "数学必修第一册·第三章·函数", parent="册·章"),
            topic("册·章·节·点", "数学必修第一册·第三章·函数·单调性", parent="册·章·节"),
        ])
        original = st._snapshot(pack)
        st.shorten(pack)
        self.assertEqual([], st.verify_lossless(pack, original))

    def test_iterates_to_fixed_point_when_one_name_embeds_two_levels(self):
        """一个名字里可能嵌了不止一层父路径，一轮剥不干净。

        `册·章·章·X` 的父是 `册·章`，剥一次得 `章·X`——它在父名（此时已缩成 `章`）之后
        仍带一层 `章·`。不迭代就会"同一份输入跑两次得到不同结果"，重放不再确定。
        """
        pack = mini_pack([
            topic("册", "册"),
            topic("册·章", "册·章", parent="册"),
            topic("册·章·章·X", "册·章·章·X", parent="册·章"),
        ])
        # 三条改动：`册·章`→`章`；`册·章·章·X`→`章·X`；第二轮 `章·X`→`X`
        self.assertEqual(3, st.shorten(pack))
        self.assertEqual(0, st.shorten(pack), "第二轮必须已是空操作")
        names = [t["name"] for t in pack["subjects"][0]["topics"]]
        self.assertEqual(["册", "章", "X"], names)


class RealPackTest(unittest.TestCase):
    def setUp(self):
        self.pack = pack_io.load_json(pack_io.pack_path())

    def test_shipped_pack_is_already_short(self):
        """成品包应已收敛；再跑一次不应有改动（幂等，也是"改完了"的判据）。"""
        self.assertEqual(0, st.shorten(self.pack))

    def test_no_name_repeats_its_parent(self):
        """渐进式披露的数据前提：任何一层都不许复述父名。改前 421 条违反，现在应为 0。"""
        self.assertEqual([], st.names_carrying_parent_path(self.pack))

    def test_unassigned_buckets_stay_visible(self):
        """没有章归属的知识点必须仍然看得见。

        改前它们藏在 `PHYSICS·综合·综合·综合` 这类名字里（相邻重复段），改名把那层噪音
        抹平后，剩下的信号是**名字里带 `综合` 段的桶**——共 9 个 topic、92 个知识点
        （随残渣/碎片删除从 105 逐步下降）。规范里写的 `跨册综合` 一个都没有，所以这些
        不是"合法的跨册归并"，是章表匹配事故。

        数字若变：下降＝章表修好了（好），上升＝又有知识点错挂（坏）。两种都该被看见。
        """
        buckets = [
            topic
            for subject in self.pack["subjects"]
            for topic in subject["topics"]
            if st.SEPARATOR.join(["综合"]) in topic["name"].split(st.SEPARATOR)
        ]
        self.assertEqual(9, len(buckets), [t["name"] for t in buckets])
        self.assertEqual(92, sum(len(t["knowledgePoints"]) for t in buckets))

    def test_deep_topics_now_have_short_local_names(self):
        """最深一层的名字必须已经是层内名（改前 L4 是 26–28 字的全路径）。"""
        rebuilt = st.full_path_names(self.pack)
        deep = [slug for slug, name in rebuilt.items() if name.count(st.SEPARATOR) >= 3]
        self.assertTrue(deep, "包内应有 4 层以上的主题")
        by_slug = {
            t["slug"]: t for s in self.pack["subjects"] for t in s["topics"]
        }
        self.assertTrue(
            all(len(by_slug[slug]["name"]) <= 20 for slug in deep),
            [by_slug[s]["name"] for s in deep if len(by_slug[s]["name"]) > 20][:5],
        )


if __name__ == "__main__":
    unittest.main()
