# -*- coding: utf-8 -*-
"""build.Builder 的契约测试：用合成夹具验证变换保持编解码不变量。

用合成夹具而不是真实数据，是因为真实包 2573 个节点无法逐条断言；
这里要证明的是"变换本身正确"，真实数据的正确性由 gate 与 round-trip 保证。
"""

from __future__ import annotations

import copy
import unittest

from kb_build.build import Builder, InvariantError

PACK_ID = "test-pack-v1"


def _point(slug: str, name: str) -> dict:
    return {
        "slug": slug,
        "name": name,
        "aliases": [name],
        "kind": "CONCEPT",
        "boundary": f"定位：某册 某章·某主题。（见知识清单/教材）",
        "sourceLocator": "某来源",
        "prerequisiteSlugs": [],
    }


def _pack() -> dict:
    return {
        "schemaVersion": 2,
        "packId": PACK_ID,
        "taxonomyVersion": PACK_ID,
        "sourceNamespace": "test",
        "reviewedAtEpochMillis": 1,
        "sourceUri": "https://example.edu/x",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL",
                     "teachingSupportLevel": "PARTIAL"},
        "subjects": [{
            "subject": "MATH",
            "sourceFingerprint": "A" * 64,
            "topics": [{
                "slug": "t1",
                "name": "某章",
                "sourceLocator": "某来源",
                "knowledgePoints": [
                    _point("alpha", "甲"),
                    _point("alpha-star", "甲★★★"),
                    _point("beta", "乙"),
                    _point("junk", "预测："),
                ],
            }],
        }],
    }


def _sidecars(bindings: dict[str, list[str]]) -> list:
    materials = [{
        "slug": slug,
        "subject": "MATH",
        "type": "CONCEPT_EXPLANATION",
        "title": slug,
        "summaryMarkdown": "s",
        "applicabilityMarkdown": "a",
        "contentMarkdown": "c",
        "boundaryMarkdown": "b",
        "derivationKind": "REVIEWED_SYNTHESIS",
        "sourceId": "src",
        "sourceLocator": "loc",
        "reviewedAtEpochMillis": 1,
        "bindings": [{"knowledgeNodeId": f"kb:{PACK_ID}:math:atomic:{target}",
                      "role": "PRIMARY"} for target in targets],
    } for slug, targets in bindings.items()]
    return [(None, {"schemaVersion": 2, "packId": PACK_ID, "sources": [],
                    "materials": materials})]


def _builder(**overrides) -> Builder:
    kwargs = dict(
        pack=_pack(),
        sidecars=_sidecars({"m1": ["alpha-star"], "m2": ["junk"]}),
        actions={
            ("MATH", "alpha-star"): {"action": "merge", "new_name": "", "new_slug": "alpha"},
            ("MATH", "junk"): {"action": "delete", "new_name": "", "new_slug": ""},
            ("MATH", "beta"): {"action": "rename", "new_name": "乙（改）", "new_slug": ""},
        },
        chapters={},
        aliases={},
        boundaries={},
        prereqs={},
        material_bindings={},
        # 这三项必须显式给空：留 None 会让 Builder 去读生产的 new_points.csv /
        # materials.jsonl，夹具就不再是一个自洽的合成世界，生产内容的增删会
        # 随机把无关测试弄红。
        new_points={},
        new_point_placements={},
        new_materials=[],
    )
    kwargs.update(overrides)
    return Builder(**kwargs)


class BuilderTest(unittest.TestCase):
    def test_review_rows_block_generation(self):
        """未定稿就拒绝生成——否则会把没审完的包推给 App。"""
        actions = {("MATH", "beta"): {"action": "review", "new_name": "", "new_slug": ""}}
        with self.assertRaises(InvariantError):
            _builder(actions=actions).build()

    def test_binding_table_reattaches_an_orphan_material(self):
        """无绑定材料进不了库（导入时被整条剔除），绑定表是它们唯一的修正通道。

        夹具里 m2 绑的是待删节点 junk，删除后绑定被解除、材料变孤儿；
        绑定表把它接到 beta 上，它才回得来。
        """
        builder = _builder(material_bindings={"m2": "beta"})
        builder.build()
        materials = {m["slug"]: m for m in builder.sidecars[0][1]["materials"]}
        self.assertEqual([f"kb:{PACK_ID}:math:atomic:beta"],
                         [b["knowledgeNodeId"] for b in materials["m2"]["bindings"]])

    def test_empty_target_detaches_a_material(self):
        """绑定表的空目标＝解绑，材料退回"不导入"。

        用在逐条读过正文、确认现绑节点讲的是另一回事、同科目里又找不到正确归属的
        材料上：讲题时把不相关材料当依据，比检索不到更糟，而且用户无从发现。

        断言的是**顺序**——`_apply_material_bindings` 跑在 `_rebind_materials` 之前，
        夹具里 m1 原本绑的 alpha-star 会并入 alpha，若解绑发生在改指之后，
        m1 会被重新接到 alpha 上，这条就红了。
        """
        builder = _builder(material_bindings={"m1": "", "m2": "beta"})
        builder.build()
        materials = {m["slug"]: m for m in builder.sidecars[0][1]["materials"]}
        self.assertEqual([], materials["m1"]["bindings"])
        self.assertEqual([f"kb:{PACK_ID}:math:atomic:beta"],
                         [b["knowledgeNodeId"] for b in materials["m2"]["bindings"]])

    def test_binding_table_target_must_survive_the_build(self):
        """绑到待删/待并的节点上等于白写：节点没了绑定会随之解除，材料又成孤儿。
        所以校验目标是生成后的节点集，不是成品包现读的节点集。"""
        with self.assertRaises(InvariantError):
            _builder(material_bindings={"m2": "junk"}).build()

    def test_binding_table_unknown_material_is_refused(self):
        with self.assertRaises(InvariantError):
            _builder(material_bindings={"nosuch": "beta"}).build()

    def test_merge_into_deleted_is_rejected(self):
        actions = {
            ("MATH", "alpha"): {"action": "delete", "new_name": "", "new_slug": ""},
            ("MATH", "alpha-star"): {"action": "merge", "new_name": "", "new_slug": "alpha"},
        }
        with self.assertRaises(InvariantError):
            _builder(actions=actions).build()

    def test_merge_chain_is_rejected(self):
        actions = {
            ("MATH", "alpha"): {"action": "merge", "new_name": "", "new_slug": "beta"},
            ("MATH", "alpha-star"): {"action": "merge", "new_name": "", "new_slug": "alpha"},
        }
        with self.assertRaises(InvariantError):
            _builder(actions=actions).build()

    def test_binding_target_is_the_post_build_node_set(self):
        """材料绑定的校验目标必须是**生成后**仍存在的节点集。

        成品包里还有成批待删、待并的抽取残片。对着成品现读来校验，材料就会绑到
        即将消失的节点上，导入时被静默剔除——那是「写完了但什么都没留下」的失败，
        比生成报错难发现得多。
        """
        builder = _builder(
            new_points={("MATH", "gamma"): _point("gamma", "丙")},
            new_point_placements={("MATH", "gamma"): ("某册", "某章", "某主题")},
        )
        known = builder._post_build_slugs()
        self.assertIn(("MATH", "alpha"), known)
        self.assertNotIn(("MATH", "alpha-star"), known)   # 被合并，包里不再存在
        self.assertNotIn(("MATH", "junk"), known)         # 被删除
        self.assertIn(("MATH", "gamma"), known)           # 本次新增，可绑

    def test_rename_and_delete_applied(self):
        builder = _builder()
        builder.build()
        points = {p["slug"]: p for _s, _t, p in
                  [(s["subject"], t, p) for s in builder.pack["subjects"]
                   for t in s["topics"] for p in t["knowledgePoints"]]}
        self.assertEqual("乙（改）", points["beta"]["name"])
        self.assertNotIn("junk", points)
        self.assertNotIn("alpha-star", points)

    def test_material_binding_retargets_on_merge_and_drops_on_delete(self):
        builder = _builder()
        builder.build()
        materials = {m["slug"]: m for _p, doc in builder.sidecars for m in doc["materials"]}
        # 合并：绑定改指保留者
        self.assertEqual(
            [f"kb:{PACK_ID}:math:atomic:alpha"],
            [b["knowledgeNodeId"] for b in materials["m1"]["bindings"]],
        )
        # 删除：绑定解除（不产生悬空引用，聚合层会过滤掉这条材料）
        self.assertEqual([], materials["m2"]["bindings"])

    def test_invariants_pass_after_build(self):
        builder = _builder()
        builder.build()
        self.assertEqual([], builder.check_invariants())

    def test_dangling_prereq_is_rejected(self):
        builder = _builder(prereqs={"beta": {"subject": "MATH", "prerequisites": ["不存在"]}})
        with self.assertRaises(InvariantError):
            builder.build()

    def test_aliases_default_to_empty_not_stale(self):
        """表里没有别名就清空：幽灵别名会污染检索，空别名只是少一些召回。"""
        builder = _builder()
        builder.build()
        for subject in builder.pack["subjects"]:
            for topic in subject["topics"]:
                for point in topic["knowledgePoints"]:
                    self.assertEqual([], point["aliases"])

    def test_empty_boundary_is_caught_by_invariants(self):
        """codec 要求 atomic 边界非空，空边界必须在生成阶段就被拦下。"""
        builder = _builder()
        builder.build()
        for subject in builder.pack["subjects"]:
            subject["topics"][0]["knowledgePoints"][0]["boundary"] = "  "
        errors = builder.check_invariants()
        self.assertTrue(any("边界为空" in e for e in errors))


class DryRunTest(unittest.TestCase):
    def test_dry_run_writes_nothing(self):
        """默认不落盘：成品包被 App 按逐字段相等校验，不允许误覆盖。"""
        import kb_build.pack_io as pack_io

        before = pack_io.pack_path().read_bytes()
        builder = _builder()
        stats = builder.build()
        self.assertEqual({"merge": 1, "deleted": 1, "new_points": 0, "new_materials": 0}, stats)
        self.assertEqual(before, pack_io.pack_path().read_bytes(), "dry-run 改动了成品文件")


if __name__ == "__main__":
    unittest.main()
