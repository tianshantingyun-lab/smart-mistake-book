# -*- coding: utf-8 -*-
"""取代映射台账的用例。

它消灭的失败：合并与删除在内容侧执行得很完整，但映射只活在人工 CSV 里——成品包对运行时
表现为"节点凭空消失了"，于是学生数据（错题绑定/掌握度/复习队列）无法解析到取代它的新节点，
发布后任何一次内容更新都会让安装器整包拒绝。

行为契约（2026-09-22 定案，schema 2）：
1. **幂等并入**：同一 nodeId 重复记录不得覆盖——先发生的事实不改写，否则重跑会篡改历史。
2. **写入时压平（一跳到底）**：MERGE 目标沿现有链解析到终局再落账；
   链底是 DELETE 的条目降级为 DELETE。落账后任何条目的 supersededBy 指向的
   节点自身不得在 retired 里——MediaWiki 红线"A double redirect does not work"，
   双跳会把学生数据解析到一个已退役的墓碑节点。
3. **形状校验**：schema 1 恰好 4 根键、schema 2 恰好 5 根键（version 为非负整数）；
   条目恰好 4 键；MERGE 必须有取代目标、DELETE 必须没有。
4. **内容戳只在晋升时刷新**：本模块没有刷戳写入口（`content_version` 是纯函数，
   `promote.py` 落盘那一刻调用）。
"""

from __future__ import annotations

import copy
import shutil
import tempfile
import unittest
from pathlib import Path

from kb_build import pack_io, update_manifest as um


def _repo_tmpdir() -> Path:
    """仓库内临时目录（dump_json 拒仓库外路径；build/ 本就不入版本控制）。"""
    return Path(tempfile.mkdtemp(prefix="kb-test-", dir=pack_io.REPO / "build"))


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
    def _slugs(self, doc):
        return [e["nodeId"].split(":")[-1] for e in doc["retired"]]

    def _targets(self, doc):
        return {e["nodeId"].split(":")[-1]:
                None if e["supersededBy"] is None else e["supersededBy"].split(":")[-1]
                for e in doc["retired"]}

    def test_adds_new_entries_and_sorts(self):
        doc = um.empty("test")
        self.assertEqual(2, um.record(doc, [entry("b", "s"), entry("a", "s")]))
        self.assertEqual(["a", "b"], self._slugs(doc))

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
        self.assertEqual("s1", self._targets(doc)["a"])

    def test_record_flattens_against_existing_chain(self):
        """record() 落账前把目标沿现有链解析到底（写入时压平）。

        y→z 已在账上时再记 x→y：x 直接落账为 x→z，一跳到底。
        """
        doc = um.empty("test")
        um.record(doc, [entry("y", "z")])
        um.record(doc, [entry("x", "y")])
        self.assertEqual("z", self._targets(doc)["x"])
        um.assert_one_hop(doc)

    def test_chain_within_one_batch_flattens_on_write(self):
        """一批条目内部成链（x→y、y→z 同批新账）：record 时 y 尚未入账、
        x 只能按当时状态落 x→y；write() 全账压平后 x 必须变成 x→z。
        """
        doc = um.empty("test")
        um.record(doc, [entry("x", "y"), entry("y", "z")])
        tmp = _repo_tmpdir()
        try:
            um.write(doc, tmp / "m.json")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        self.assertEqual("z", self._targets(doc)["x"])
        self.assertEqual("z", self._targets(doc)["y"])
        um.assert_one_hop(doc)

    def test_chain_ending_at_delete_degrades_to_delete(self):
        """链底是 DELETE（终局节点没有后继）时，上游条目降级为 DELETE——
        它同样没有唯一后继，运行时按"墓碑不重定向"处理。
        """
        doc = um.empty("test")
        um.record(doc, [entry("y", None, um.KIND_DELETE), entry("x", "y")])
        tmp = _repo_tmpdir()
        try:
            um.write(doc, tmp / "m.json")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        targets = self._targets(doc)
        self.assertIsNone(targets["x"])
        kinds = {e["nodeId"].split(":")[-1]: e["kind"] for e in doc["retired"]}
        self.assertEqual(um.KIND_DELETE, kinds["x"])
        um.assert_one_hop(doc)

    def test_cycle_is_rejected(self):
        """A→B→A 成环：压平必须报错，不能静默产出错误重定向。"""
        doc = um.empty("test")
        um.record(doc, [entry("a", "b"), entry("b", "a")])
        with self.assertRaises(ValueError):
            um.flatten(doc)


class RequireShapeTest(unittest.TestCase):
    def test_rejects_wrong_schema_version(self):
        doc = um.empty("test")
        doc["schemaVersion"] = 99
        with self.assertRaises(ValueError):
            um._require_shape(doc)

    def test_schema2_requires_nonnegative_integer_version(self):
        doc = um.empty("test")
        del doc["version"]
        with self.assertRaises(ValueError):
            um._require_shape(doc)
        doc["version"] = "1"
        with self.assertRaises(ValueError):
            um._require_shape(doc)
        doc["version"] = -1
        with self.assertRaises(ValueError):
            um._require_shape(doc)
        doc["version"] = 1
        um._require_shape(doc)  # 不抛即通过

    def test_schema1_shape_still_accepted(self):
        doc = {
            "schemaVersion": 1,
            "packId": "test",
            "contentVersion": "",
            "retired": [entry("a", None, um.KIND_DELETE)],
        }
        um._require_shape(doc)  # 不抛即通过

    def test_schema2_rejects_extra_root_key(self):
        doc = um.empty("test")
        doc["extra"] = 1
        with self.assertRaises(ValueError):
            um._require_shape(doc)

    def test_entry_keys_must_be_exactly_four(self):
        doc = um.empty("test")
        doc["retired"] = [dict(entry("a", "b"), extra="x")]
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


class UpgradeTest(unittest.TestCase):
    def test_schema1_with_chains_upgrades_and_flattens(self):
        """存量 schema 1 台账（真实多级链）经 canonicalize 升 schema 2 且一跳到底。"""
        doc = {
            "schemaVersion": 1,
            "packId": "test",
            "contentVersion": "abc",
            "retired": [
                entry("x2", "x1"),
                entry("x1", "x"),
            ],
        }
        um.canonicalize(doc)
        self.assertEqual(um.SCHEMA_VERSION, doc["schemaVersion"])
        self.assertEqual(0, doc["version"])
        targets = {e["nodeId"].split(":")[-1]: e["supersededBy"].split(":")[-1]
                   for e in doc["retired"]}
        self.assertEqual("x", targets["x2"])  # 链已压平
        self.assertEqual("x", targets["x1"])
        um.assert_one_hop(doc)

    def test_canonicalize_is_idempotent(self):
        doc = um.empty("test")
        um.record(doc, [entry("a", "s")])
        um.canonicalize(doc)
        snapshot = copy.deepcopy(doc)
        um.canonicalize(doc)
        self.assertEqual(snapshot, doc)


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


class StampIsCurrentTest(unittest.TestCase):
    """内容戳当前性：记录值 == 对**成品目录**内容的实算值。

    它消灭的失败：手术工具改完内容不刷戳（2026-09-21 审计实测记录
    742d7487bcfc46cd vs 实算 89415663d45b018b）——安装器快速路径信一个
    过期的戳，就会跳过该发生的调和。戳只有 promote 能写，这条用例
    钉住"发布出去的戳永远对应发布出去的内容"。
    """

    @classmethod
    def setUpClass(cls):
        release = pack_io.release_dir()
        cls.pack = pack_io.load_json(release / pack_io.PACK_NAME)
        cls.sidecars = [pack_io.load_json(release / n) for n in _release_sidecar_names()]
        cls.manifest = pack_io.load_json(release / um.MANIFEST_NAME)

    def test_stamp_is_current(self):
        computed = um.content_version(self.pack, self.sidecars)
        self.assertEqual(computed, self.manifest["contentVersion"],
                         "成品内容戳已陈旧：记录值 ≠ 实算值（只有晋升路径能刷戳）")

    def test_manifest_is_schema2_and_one_hop(self):
        """随包发布的台账必须是晋升产物：schema 2 + 一跳到底（Kotlin codec 会拒旧形态）。"""
        um._require_shape(self.manifest)
        self.assertEqual(um.SCHEMA_VERSION, self.manifest["schemaVersion"])
        um.assert_one_hop(self.manifest)


def _release_sidecar_names() -> list[str]:
    index = pack_io.load_json(pack_io.release_dir() / pack_io.SIDECAR_INDEX_NAME)
    return [n.rsplit("/", 1)[-1] for n in index["sidecars"]]


class RealArtifactsTest(unittest.TestCase):
    """成品包上的回填结果——这些数字是"发生过什么"的账，变动必须被看见。"""

    @classmethod
    def setUpClass(cls):
        cls.pack = pack_io.load_json(pack_io.release_dir() / pack_io.PACK_NAME)
        cls.entries = um.backfill_from_tables(cls.pack)

    def test_backfill_matches_the_authority_tables(self):
        """可回填的应当恰好等于两张表里"已执行"的行数。

        已执行的判据是 merged/deleted 的 slug 已不在包里；仍在包里的说明尚未执行，
        回填会跳过——所以这个用例同时证明"没把没发生的事记成事实"。
        """
        kinds: dict[str, int] = {}
        for e in self.entries:
            kinds[e["kind"]] = kinds.get(e["kind"], 0) + 1
        # 2026-09-19：合并表 132 行收口。2026-09-22（R1）：清掉 2 行"幸存者已退役"
        # 的陈行（无氧呼吸-x1←x2 / 有氧呼吸-x1←x2——幸存者后来并进了根节点，
        # 历史在台账里，表不留指向死节点的行）→ 130。
        self.assertEqual(130, kinds.get(um.KIND_MERGE, 0))
        self.assertEqual(184, kinds.get(um.KIND_DELETE, 0))

    def test_backfilled_entries_pass_shape_check(self):
        doc = um.empty(self.pack["packId"])
        um.record(doc, self.entries)
        um._require_shape(doc)  # 不抛即通过

    def test_backfill_entries_flatten_to_one_hop(self):
        """回填 + 压平后，每条 MERGE 一跳到底、且目标仍在包里。

        2026-09-19 的账：无氧呼吸-x2 / 有氧呼吸-x2 各有两行（-x1 一次、根节点一次），
        执行器按行序第一条生效。2026-09-22 死幸存者行清掉后，回填直接给出
        x2→根节点；即便还有链式形态，record/flatten 也会压平到终局。
        """
        doc = um.empty(self.pack["packId"])
        added = um.record(doc, self.entries)
        um.flatten(doc)
        um.assert_one_hop(doc)
        self.assertEqual(len(self.entries), added, "回填条目应无重复 nodeId")
        present = {p["slug"] for _s, _t, p in pack_io.iter_points(self.pack)}
        retired_ids = {e["nodeId"] for e in doc["retired"]}
        for e in doc["retired"]:
            if e["kind"] != um.KIND_MERGE:
                continue
            target = e["supersededBy"]
            self.assertNotIn(target, retired_ids, f"链跳：{e['nodeId']}")
            self.assertIn(target.split(":")[-1], present,
                          f"MERGE 目标已不在包里：{e['nodeId']} -> {target}")

    def test_recorded_manifest_is_reloadable(self):
        doc = um.load(pack_io.release_dir() / um.MANIFEST_NAME)
        if doc is None:
            self.skipTest("台账尚未生成（先跑 update_manifest --backfill）")
        um._require_shape(doc)
        self.assertEqual(self.pack["packId"], doc["packId"])


if __name__ == "__main__":
    unittest.main()
