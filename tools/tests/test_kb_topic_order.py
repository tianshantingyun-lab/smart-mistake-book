# -*- coding: utf-8 -*-
"""`topic_order.parent_first` 的用例。

它消灭的失败：子级排在父级前面会让加载器按数组顺序写节点时撞
`knowledge_node.parent_knowledge_node_id` 的外键，**整批安装崩掉**。

三类行为各钉一个失败：
1. **父级先序**：一条链不管以什么顺序给出，出来都是父在子前。
2. **稳定**：可用先序的前提下保留原有相对顺序——否则每次跑都产生不同的数组，
   而本工具要写回成品，不稳定就等于每次生成一个巨大的无意义 diff。
3. **不可排序的不静默丢**：父链成环的 topic 仍要出现在结果里（排在最后），并由
   [topic_order.unorderable] 报出；父级不在集合里的照常放行、不卡住整组——排序
   工具不该悄悄吞掉"这条本来就坏"的事实。
"""

from __future__ import annotations

import unittest

from kb_build import topic_order


def topic(slug: str, parent: str | None = None) -> dict:
    return {
        "slug": slug,
        "name": slug,
        "parentSlug": parent,
        "sourceLocator": "loc",
        "knowledgePoints": [],
    }


def slugs_after(topics: list[dict], slug: str) -> bool:
    """`slug` 是否出现在结果里、且排在它的父级之后。"""
    order = [t["slug"] for t in topics]
    parent = next(t["parentSlug"] for t in topics if t["slug"] == slug)
    return slug in order and parent in order and order.index(parent) < order.index(slug)


class ParentFirstTest(unittest.TestCase):
    def test_child_before_parent_is_reordered(self):
        topics = [topic("a"), topic("a·b", "a"), topic("a·b·c", "a·b")]
        shuffled = [topics[2], topics[0], topics[1]]
        self.assertEqual(["a", "a·b", "a·b·c"], [t["slug"] for t in topic_order.parent_first(shuffled)])

    def test_already_ordered_input_is_unchanged(self):
        topics = [topic("a"), topic("a·b", "a")]
        self.assertEqual(topics, topic_order.parent_first(topics))

    def test_stability_keeps_sibling_order(self):
        """同层兄弟之间的原有相对顺序必须保留。"""
        topics = [topic("r"), topic("r·x", "r"), topic("r·y", "r")]
        reordered = topic_order.parent_first([topics[2], topics[1], topics[0]])
        # 兄弟 x、y 的先后按输入保留（输入里 y 在前）
        self.assertEqual(["r", "r·y", "r·x"], [t["slug"] for t in reordered])

    def test_a_missing_parent_does_not_block_the_whole_array(self):
        """父级不在集合里不是顺序问题——不能因为它让整组都排不出来。"""
        topics = [topic("orphan", "nowhere"), topic("a"), topic("a·b", "a")]
        reordered = topic_order.parent_first(topics)
        self.assertEqual(3, len(reordered))
        self.assertTrue(slugs_after(reordered, "a·b"))

    def test_a_cycle_terminates_and_is_reported(self):
        """成环时函数必须返回（不死循环），且把环上的 topic 报出来。"""
        topics = [topic("a", "b"), topic("b", "a"), topic("c")]
        reordered = topic_order.parent_first(topics)
        self.assertEqual(3, len(reordered), "成环也要原样保留，不能丢")
        self.assertEqual({"a", "b"}, set(topic_order.unorderable(topics)))

    def test_unorderable_is_empty_on_a_healthy_forest(self):
        topics = [topic("a"), topic("a·b", "a"), topic("a·b·c", "a·b")]
        self.assertEqual([], topic_order.unorderable(topics))

    def test_is_idempotent(self):
        topics = [topic("a·b·c", "a·b"), topic("a·b", "a"), topic("a")]
        once = topic_order.parent_first(topics)
        self.assertEqual(once, topic_order.parent_first(once))


class RealPackTest(unittest.TestCase):
    def test_shipped_pack_passes_the_gate_predicate(self):
        """成品必须过关门的**同一个**判据，而不是"排序函数说它没关系"。

        `unorderable` 只报环（父级缺失的照常放行），所以这里逐条重算门指标
        `topic_parent_after_child` 的判据；若这条红了，说明有写入者又开始往数组尾
        追加 topic，安装会撞外键。
        """
        from kb_build import pack_io

        pack = pack_io.load_json(pack_io.pack_path())
        for subject in pack["subjects"]:
            topics = subject["topics"]
            position = {t["slug"]: i for i, t in enumerate(topics)}
            for index, t in enumerate(topics):
                parent = t.get("parentSlug")
                if parent is None:
                    continue
                self.assertIn(parent, position, f"[{subject['subject']}] {t['slug']} 的父级不存在")
                self.assertLess(
                    position[parent],
                    index,
                    f"[{subject['subject']}] {t['slug']} 排在父级 {parent} 之前",
                )
            self.assertEqual([], topic_order.unorderable(topics))
            self.assertEqual(topics, topic_order.parent_first(topics))


if __name__ == "__main__":
    unittest.main()
