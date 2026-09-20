# -*- coding: utf-8 -*-
"""rename_points：只改 name，不动 slug（材料/绑定/前置不受影响）。"""

from __future__ import annotations

import unittest

from kb_build import pack_io, rename_points as rp


def pt(slug: str, name: str) -> dict:
    return {"slug": slug, "name": name, "aliases": [], "kind": "CONCEPT",
            "boundary": "b", "sourceLocator": "l", "prerequisiteSlugs": []}


def pack_with(points: list[dict]) -> dict:
    return {
        "schemaVersion": 2, "packId": "t", "taxonomyVersion": "t", "sourceNamespace": "n",
        "reviewedAtEpochMillis": 1, "sourceUri": "https://e.t",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL", "teachingSupportLevel": "PARTIAL"},
        "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64,
                      "topics": [{"slug": "t0", "name": "章", "sourceLocator": "l",
                                  "knowledgePoints": points}]}],
    }


class RenameTest(unittest.TestCase):
    def test_changes_name_keeps_slug(self):
        pack = pack_with([pt("s", "下列说法正确的是")])
        changed = rp.rename(pack, {("MATH", "s"): "集合与元素关系"})
        self.assertEqual(1, changed)
        p = pack["subjects"][0]["topics"][0]["knowledgePoints"][0]
        self.assertEqual("集合与元素关系", p["name"])
        self.assertEqual("s", p["slug"], "slug 必须不变——材料/绑定/前置引用它")

    def test_is_idempotent(self):
        pack = pack_with([pt("s", "旧名")])
        self.assertEqual(1, rp.rename(pack, {("MATH", "s"): "新名"}))
        self.assertEqual(0, rp.rename(pack, {("MATH", "s"): "新名"}))

    def test_only_named_subject(self):
        """表键含 subject，不能把 A 科的重名点改了。"""
        pack = pack_with([pt("s", "旧名")])
        self.assertEqual(0, rp.rename(pack, {("PHYSICS", "s"): "新名"}))


class RealPackTest(unittest.TestCase):
    def test_rename_is_idempotent_on_shipped(self):
        """当前成品已应用过 point_rename.csv；再跑必须 0 改动（幂等）。"""
        pack = pack_io.load_json(pack_io.pack_path())
        renames = rp.load_renames()
        self.assertEqual(0, rp.rename(pack, renames))

    def test_shipped_point_count(self):
        from kb_build import delete_points as dp
        pack = pack_io.load_json(pack_io.pack_path())
        # 2026-09-19：两批视觉转写新增 328 个方法节点、别名取证反查改绑 92 条并合并 9 条同物重复
        # 2026-09-19 坏名分流收口：把 fix_bad_names 的定稿移植进权威表后又合并 9 条
        # （属性条目/碎片并入主节点）→ 2434−9=2425
        # 同日再删 1 条题干残片（含它唯一的题干材料）→ 2424
        # 2026-09-19 文本判定轮：化学/生物讲义与知识清单 9,405 块判定入库，新建 428 个知识点
        # 并合并 1 条同物重复 → 2424 + 428 = 2852
        # 2026-09-19 文本判定第二轮（并行会话）再入库 729 个知识点（sidecar 滚到 v2-10）
        # → 2852 + 729 = 3572。点数随入库轮次增长，由入库侧在提交里维护本 pin。
        self.assertEqual(3572, dp._point_count(pack))


if __name__ == "__main__":
    unittest.main()
