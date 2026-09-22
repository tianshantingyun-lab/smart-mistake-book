# -*- coding: utf-8 -*-
"""build.Builder 的契约测试：用合成夹具验证变换保持编解码不变量。

用合成夹具而不是真实数据，是因为真实包有数千个节点无法逐条断言；
这里要证明的是"变换本身正确"，真实数据的正确性由 gate 与 round-trip 保证。

（2026-09-22，C-09 收口的 R5 清理：原夹具注入的 `actions`（rename/merge/delete/review
行）与 node_actions.csv 同属一条已作废的读取路径——那张表与成品包不同坐标系，
其变换逻辑连同 6 条专属用例（review 拒生成、合并链拒、并入已删节点拒、改名/删除
落地、绑随并删改指/解除、绑定目标必须是生成后节点集）一并删除。节点增删改现在
走五张权威动作表 + 幂等手术工具直接改成品，不经本生成器。）
"""

from __future__ import annotations

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
        sidecars=_sidecars({"m1": ["alpha"], "m2": ["junk"]}),
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
    def test_binding_table_reattaches_a_misbound_material(self):
        """绑错节点的材料没有别的机械修正通道，绑定表就是它唯一的修正口。

        夹具里 m2 绑的是残渣点 junk，绑定表把它改接到 beta 上，它才绑对。
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
        """
        builder = _builder(material_bindings={"m1": "", "m2": "beta"})
        builder.build()
        materials = {m["slug"]: m for m in builder.sidecars[0][1]["materials"]}
        self.assertEqual([], materials["m1"]["bindings"])
        self.assertEqual([f"kb:{PACK_ID}:math:atomic:beta"],
                         [b["knowledgeNodeId"] for b in materials["m2"]["bindings"]])

    def test_binding_table_unknown_material_is_refused(self):
        with self.assertRaises(InvariantError):
            _builder(material_bindings={"nosuch": "beta"}).build()

    def test_binding_target_is_the_post_build_node_set(self):
        """材料绑定的校验目标必须是**生成后**仍存在的节点集。

        本次新增的点还不存在于成品包里；对着成品现读来校验，材料就会绑到"校验集
        里没有"的新节点上被误拒——那是「写完了但什么都没留下」的失败。
        """
        builder = _builder(
            new_points={("MATH", "gamma"): _point("gamma", "丙")},
            new_point_placements={("MATH", "gamma"): ("某册", "某章", "某主题")},
        )
        known = builder._post_build_slugs()
        self.assertIn(("MATH", "alpha"), known)
        self.assertIn(("MATH", "gamma"), known)           # 本次新增，可绑

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
        self.assertEqual({"new_points": 0, "new_materials": 0}, stats)
        self.assertEqual(before, pack_io.pack_path().read_bytes(), "dry-run 改动了成品文件")


if __name__ == "__main__":
    unittest.main()
