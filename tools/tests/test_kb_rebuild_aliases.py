# -*- coding: utf-8 -*-
"""别名重建的用例：别名必须"有据可依"且"全局互斥"。

它消灭的失败：别名曾被字符串机械匹配污染（`函数的概念` 挂着 `导数的概念与导函数`），
而别名是检索打分（权重 7／精确短语加成 70）与模型选点的输入 —— 指向别的概念
就会把匹配引到错误节点上。这些用例钉住三条判据：形状合格、必须来自本节点绑定材料标题、
同学科内一个名字只归一个节点。
"""

from __future__ import annotations

import unittest

from kb_build import rebuild_aliases as ra


def point(slug: str, name: str, aliases: list[str] | None = None) -> dict:
    return {"slug": slug, "name": name, "aliases": aliases or [name], "kind": "CONCEPT",
            "boundary": f"定位：某册 某章。{name}的边界。", "sourceLocator": "《测试》",
            "prerequisiteSlugs": []}


def pack_with(points: list[tuple[str, dict]]) -> dict:
    """points: (topic_slug, point) —— 都挂在同一个章下的不同主题里。"""
    topics = [{"slug": "某册·某章", "name": "某章", "parentSlug": "某册",
               "sourceLocator": "定位：某册·某章", "knowledgePoints": []}]
    for topic_slug, pt in points:
        topics.append({"slug": topic_slug, "name": topic_slug.split("·")[-1],
                       "parentSlug": "某册·某章", "sourceLocator": f"定位：{topic_slug}",
                       "knowledgePoints": [pt]})
    return {"schemaVersion": 2, "packId": "t", "taxonomyVersion": "t", "sourceNamespace": "n",
            "reviewedAtEpochMillis": 1, "sourceUri": "https://e.t",
            "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL",
                         "teachingSupportLevel": "PARTIAL"},
            "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64, "topics": topics}]}


class TitleUsableTest(unittest.TestCase):
    def test_short_noun_phrase_is_usable(self):
        self.assertIsNone(ra.title_usable("同素异形体"))

    def test_sentence_like_titles_rejected(self):
        self.assertIsNotNone(ra.title_usable("下列判断正确的是（　）"))
        self.assertIsNotNone(ra.title_usable("已知函数 f(x) 在区间上单调递增"))

    def test_long_and_truncated_titles_rejected(self):
        self.assertIsNotNone(ra.title_usable("物体看作质点的条件：物体的大小和形状对研究问题的影响可以忽略"))
        # 逗号出现即判句子片段（名字里不会有逗号）
        self.assertIsNotNone(ra.title_usable("奇偶性性质法求参：拆出含参部分，由不含参"))
        self.assertIsNotNone(ra.title_usable("$\frac{1}{2}$ 的系数"))

    def test_dangling_suffix_and_unbalanced_bracket_rejected(self):
        self.assertIsNotNone(ra.title_usable("动量定理在电磁感应中的"))
        self.assertIsNotNone(ra.title_usable("（实验）探究加速度与力的关系（"))
        self.assertIsNone(ra.title_usable("（实验）探究加速度与力的关系"),
                          "括号配对的书名式标题是合法别名")

    def test_textbook_style_colon_title_is_kept(self):
        """教材节名带冒号（实验：探究平抛运动的特点）是好别名，不该被逗号规则误伤。"""
        self.assertIsNone(ra.title_usable("实验：探究平抛运动的特点"))


class RebuildTest(unittest.TestCase):
    def _plan(self):
        pack = pack_with([
            ("某册·某章·函数", point("函数的概念", "函数的概念",
                                     ["函数的概念", "导数的概念与导函数"])),
            ("某册·某章·导数", point("导数的概念", "导数的概念")),
        ])
        titles = {
            ("MATH", "函数的概念"): {"函数的概念与三要素", "题面：下列判断正确的是（　）"},
            ("MATH", "导数的概念"): {"导数的概念与导函数"},
        }
        return pack, ra.rebuild(pack, titles)

    def test_aliases_come_only_from_bound_titles(self):
        _, plan = self._plan()
        self.assertEqual({"函数的概念与三要素"}, plan["candidates"][("MATH", "函数的概念")])
        self.assertEqual({"导数的概念与导函数"}, plan["candidates"][("MATH", "导数的概念")])

    def test_rejected_candidates_are_recorded_with_reason(self):
        _, plan = self._plan()
        reasons = {(d["slug"], d["candidate"]): d["reason"] for d in plan["dropped"]}
        self.assertIn(("函数的概念", "题面：下列判断正确的是（　）"), reasons)

    def test_applied_aliases_keep_name_first_without_duplicates(self):
        pack, plan = self._plan()
        ra.apply_to_pack(pack, plan)
        node = pack["subjects"][0]["topics"][1]["knowledgePoints"][0]
        self.assertEqual("函数的概念", node["aliases"][0])
        self.assertEqual(len(set(node["aliases"])), len(node["aliases"]))
        self.assertNotIn("导数的概念与导函数", node["aliases"], "污染别名必须被清掉")

    def test_name_duplicate_candidate_is_dropped(self):
        pack = pack_with([("某册·某章·甲", point("甲概念", "甲概念"))])
        plan = ra.rebuild(pack, {("MATH", "甲概念"): {"甲概念"}})
        self.assertEqual(set(), plan["candidates"][("MATH", "甲概念")])
        ra.apply_to_pack(pack, plan)
        node = pack["subjects"][0]["topics"][1]["knowledgePoints"][0]
        self.assertEqual(["甲概念"], node["aliases"])

    def test_candidate_equal_to_other_node_name_is_dropped(self):
        pack = pack_with([
            ("某册·某章·甲", point("甲概念", "甲概念")),
            ("某册·某章·乙", point("乙概念", "乙概念")),
        ])
        plan = ra.rebuild(pack, {("MATH", "甲概念"): {"乙概念"}})
        self.assertEqual(set(), plan["candidates"][("MATH", "甲概念")])
        reasons = [d["reason"] for d in plan["dropped"] if d["slug"] == "甲概念"]
        self.assertTrue(any("主名相同" in r for r in reasons), reasons)

    def test_candidate_claimed_by_two_nodes_is_dropped_globally(self):
        pack = pack_with([
            ("某册·某章·甲", point("甲概念", "甲概念")),
            ("某册·某章·乙", point("乙概念", "乙概念")),
        ])
        plan = ra.rebuild(pack, {("MATH", "甲概念"): {"共用别名"},
                                 ("MATH", "乙概念"): {"共用别名"}})
        self.assertEqual(set(), plan["candidates"][("MATH", "甲概念")])
        self.assertEqual(set(), plan["candidates"][("MATH", "乙概念")])
        reasons = {d["reason"] for d in plan["dropped"]}
        self.assertTrue(any("同时 claim" in r for r in reasons), reasons)

    def test_rebuild_is_idempotent(self):
        pack, plan = self._plan()
        ra.apply_to_pack(pack, plan)
        again = ra.rebuild(pack, {("MATH", "函数的概念"): {"函数的概念与三要素"},
                                  ("MATH", "导数的概念"): {"导数的概念与导函数"}})
        stats = ra.apply_to_pack(pack, again)
        self.assertEqual(0, stats["nodes_changed"])


if __name__ == "__main__":
    unittest.main()
