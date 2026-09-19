# -*- coding: utf-8 -*-
"""取代映射台账的用例。

它消灭的失败：合并与删除在内容侧执行得很完整，但映射只活在人工 CSV 里——成品包对运行时
表现为"节点凭空消失了"，于是学生数据（错题绑定/掌握度/复习队列）无法解析到取代它的新节点，
发布后任何一次内容更新都会让安装器整包拒绝。

三类行为各有它消灭的具体失败：
1. **幂等并入**：同一 nodeId 重复记录不得覆盖——先发生的事实不改写，否则重跑会篡改历史。
2. **链式保留**：X→Y 之后 Y→Z 必须保留两条，不折叠成 X→Z——折叠会丢掉"中途换过目标"这件事，
   而解析侧的传递闭包正需要每一段。
3. **形状校验**：MERGE 必须有取代目标、DELETE 必须没有——写反了运行时就会去解析一个
   不存在或不该存在的重定向。
"""

from __future__ import annotations

import unittest

from kb_build import pack_io, update_manifest as um


def entry(slug: str, to: str | None, kind: str = um.KIND_MERGE) -> dict:
    return {"nodeId": f"kb:test:math:atomic:{slug}",
            "supersededBy": None if to is None else f"kb:test:math:atomic:{to}",
            "kind": kind, "reason": "测试"}


class NodeIdTest(unittest.TestCase):
    def test_matches_kotlin_derivation(self):
        """必须与 Kotlin 的 pointId 派生逐字一致，否则运行时对不上。

        Kotlin：`"kb:$taxonomyVersion:${subject.lowercase()}:atomic:$slug"`
        （BundledKnowledgePackResources.kt 的 pointId 构造）。
        """
        self.assertEqual(
            "kb:moe-2025-four-subjects-v1:chemistry:atomic:离子键",
            um.node_id("moe-2025-four-subjects-v1", "CHEMISTRY", "离子键"),
        )


class RecordTest(unittest.TestCase):
    def test_adds_new_entries_and_sorts(self):
        doc = um.empty("test")
        self.assertEqual(2, um.record(doc, [entry("b", "s"), entry("a", "s")]))
        self.assertEqual(["a", "b"], [e["nodeId"].split(":")[-1] for e in doc["retired"]])

    def test_is_idempotent(self):
        doc = um.empty("test")
        um.record(doc, [entry("a", "s")])
        self.assertEqual(0, um.record(doc, [entry("a", "s")]))
        self.assertEqual(1, len(doc["retired"]))

    def test_keeps_the_earliest_record_for_a_node(self):
        """同一节点先被合并到 s1、后又被记成 s2 时，保留最早那条。

        覆盖会改写历史事实，而"它当时被并到了哪里"是已经发生过的事。
        """
        doc = um.empty("test")
        um.record(doc, [entry("a", "s1")])
        um.record(doc, [entry("a", "s2")])
        self.assertEqual("s1", doc["retired"][0]["supersededBy"].split(":")[-1])

    def test_chain_merges_are_both_kept(self):
        """X→Y 之后 Y→Z：两条都要在，不折叠。解析侧的传递闭包靠每一段。"""
        doc = um.empty("test")
        um.record(doc, [entry("x", "y"), entry("y", "z")])
        self.assertEqual(["x", "y"], [e["nodeId"].split(":")[-1] for e in doc["retired"]])


class RequireShapeTest(unittest.TestCase):
    def test_rejects_wrong_schema_version(self):
        doc = um.empty("test")
        doc["schemaVersion"] = 99
        with self.assertRaises(ValueError):
            um._require_shape(doc)

    def test_rejects_merge_without_target(self):
        doc = um.empty("test")
        doc["retired"] = [entry("a", None, um.KIND_MERGE)]
        with self.assertRaises(ValueError):
            um._require_shape(doc)

    def test_rejects_delete_with_target(self):
        doc = um.empty("test")
        doc["retired"] = [entry("a", "b", um.KIND_DELETE)]
        with self.assertRaises(ValueError):
            um._require_shape(doc)

    def test_rejects_unknown_kind(self):
        doc = um.empty("test")
        doc["retired"] = [entry("a", "b", "SPLIT")]
        with self.assertRaises(ValueError):
            um._require_shape(doc)

    def test_rejects_missing_field(self):
        doc = um.empty("test")
        doc["retired"] = [{"nodeId": "x", "kind": um.KIND_DELETE}]
        with self.assertRaises(ValueError):
            um._require_shape(doc)


class ContentVersionTest(unittest.TestCase):
    def _pack(self, name="甲"):
        return {"packId": "p", "subjects": [{"subject": "MATH", "topics": [
            {"slug": "t", "name": "t", "knowledgePoints": [
                {"slug": "a", "name": name, "aliases": [], "kind": "CONCEPT",
                 "boundary": "b", "sourceLocator": "l", "prerequisiteSlugs": []}]}]}]}

    def test_is_deterministic(self):
        self.assertEqual(um.content_version(self._pack(), []),
                         um.content_version(self._pack(), []))

    def test_changes_when_content_changes(self):
        """纯改名也必须换戳——否则安装器会走快速路径、把这次改名漏掉。"""
        self.assertNotEqual(um.content_version(self._pack("甲"), []),
                            um.content_version(self._pack("乙"), []))

    def test_sidecars_participate(self):
        self.assertNotEqual(um.content_version(self._pack(), []),
                            um.content_version(self._pack(), [{"materials": [{"slug": "m"}]}]))


class RealArtifactsTest(unittest.TestCase):
    """成品包上的回填结果——这些数字是"发生过什么"的账，变动必须被看见。"""

    @classmethod
    def setUpClass(cls):
        cls.pack = pack_io.load_json(pack_io.pack_path())
        cls.entries = um.backfill_from_tables(cls.pack)

    def test_backfill_matches_the_authority_tables(self):
        """可回填的应当恰好等于两张表里"已执行"的行数。

        已执行的判据是 merged/deleted 的 slug 已不在包里；仍在包里的说明尚未执行，
        回填会跳过——所以这个用例同时证明"没把没发生的事记成事实"。
        """
        kinds: dict[str, int] = {}
        for e in self.entries:
            kinds[e["kind"]] = kinds.get(e["kind"], 0) + 1
        # 2026-09-19：别名取证反查收口时又合并 9 条同物重复/残渣节点（point_merge.csv 104→113 行）
        # 2026-09-19 坏名分流收口：把 fix_bad_names 的定稿移植进权威表后再 +9 行（113→122）
        self.assertEqual(122, kinds.get(um.KIND_MERGE, 0))
        # 同日再删 1 条题干残片（含材料）→ 182→183→184
        self.assertEqual(184, kinds.get(um.KIND_DELETE, 0))

    def test_backfilled_entries_pass_shape_check(self):
        doc = um.empty(self.pack["packId"])
        um.record(doc, self.entries)
        um._require_shape(doc)  # 不抛即通过

    def test_duplicate_merge_rows_collapse_to_the_one_that_ran(self):
        """同一 slug 在表里出现两次、指向不同幸存者时，只记**真正执行的那条**。

        `无氧呼吸-x2` 与 `有氧呼吸-x2` 各出现两次（-x1 一次、根节点一次）。执行器按行序，
        第一条把 -x2 并进 -x1 后 -x2 就没了，第二条因幂等被跳过——所以生效的是第一条。
        回填若把两条都记下，运行时会去解析一个从未发生的重定向。
        """
        doc = um.empty(self.pack["packId"])
        added = um.record(doc, self.entries)
        self.assertEqual(306, len(self.entries), "回填原始条目数（2026-09-19 合并表 113→122 行、删除表 183→184 行后 296→306）")
        self.assertEqual(304, added, "去重后的并入数")
        merged = {e["nodeId"].split(":")[-1]: e["supersededBy"].split(":")[-1]
                  for e in doc["retired"] if e["kind"] == um.KIND_MERGE}
        self.assertEqual("无氧呼吸-x1", merged["无氧呼吸-x2"])
        self.assertEqual("有氧呼吸-x1", merged["有氧呼吸-x2"])

    def test_every_merge_resolves_to_a_live_node(self):
        """每条 MERGE 都能沿链跟到一个**仍在包里**的节点。

        链是真实存在的（`无氧呼吸-x2 → -x1 → 无氧呼吸`），所以解析侧必须做传递闭包；
        这条用例就是那个闭包的可行性证明——有任意一条跟不到底，运行时就会把学生数据
        解析成一个已退役的节点。
        """
        doc = um.empty(self.pack["packId"])
        um.record(doc, self.entries)
        successor = {
            e["nodeId"]: e["supersededBy"]
            for e in doc["retired"] if e["kind"] == um.KIND_MERGE
        }
        present = {p["slug"] for _s, _t, p in pack_io.iter_points(self.pack)}
        unresolved = []
        for start in successor:
            node, hops = start, 0
            while successor.get(node) and hops < 20:
                node, hops = successor[node], hops + 1
            if node.split(":")[-1] not in present:
                unresolved.append((start.split(":")[-1], node.split(":")[-1], hops))
        self.assertEqual([], unresolved)

    def test_merge_targets_mostly_still_present(self):
        """绝大多数取代目标仍在包里；少数不在的是**链式合并**（目标本身后来也被合并）。

        这些必须靠解析侧的传递闭包处理，所以数字变了要能看见——它一变就说明
        要么新增了链，要么回填判据坏了。
        """
        present = {p["slug"] for _s, _t, p in pack_io.iter_points(self.pack)}
        missing = [
            e for e in self.entries
            if e["kind"] == um.KIND_MERGE and e["supersededBy"].split(":")[-1] not in present
        ]
        self.assertEqual(2, len(missing), [e["nodeId"].split(":")[-1] for e in missing])

    def test_recorded_manifest_is_reloadable(self):
        doc = um.load()
        if doc is None:
            self.skipTest("台账尚未生成（先跑 update_manifest --backfill）")
        um._require_shape(doc)
        self.assertEqual(self.pack["packId"], doc["packId"])


if __name__ == "__main__":
    unittest.main()
