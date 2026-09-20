# -*- coding: utf-8 -*-
"""delete_points 的用例。只删"无材料 + 不被引用 + 判为残渣"的点；幂等；清悬挂前置。"""

from __future__ import annotations

import unittest

from kb_build import delete_points as dp


def pt(slug: str, prereq: list[str] | None = None) -> dict:
    return {"slug": slug, "name": slug, "aliases": [], "kind": "CONCEPT",
            "boundary": "b", "sourceLocator": "l", "prerequisiteSlugs": prereq or []}


def pack_with(points_by_topic: list[list[dict]]) -> dict:
    return {
        "schemaVersion": 2, "packId": "t", "taxonomyVersion": "t", "sourceNamespace": "n",
        "reviewedAtEpochMillis": 1, "sourceUri": "https://e.t",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL", "teachingSupportLevel": "PARTIAL"},
        "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64,
                      "topics": [{"slug": f"t{i}", "name": f"章{i}", "sourceLocator": "l",
                                  "knowledgePoints": pts} for i, pts in enumerate(points_by_topic)]}],
    }


class DeleteTest(unittest.TestCase):
    def test_removes_only_listed_points(self):
        pack = pack_with([[pt("a"), pt("b"), pt("c")]])
        removed, dangling = dp.delete(pack, {("MATH", "b")})
        self.assertEqual(1, removed)
        self.assertEqual(0, dangling)
        self.assertEqual(["a", "c"], [p["slug"] for p in pack["subjects"][0]["topics"][0]["knowledgePoints"]])

    def test_cleans_dangling_prerequisite(self):
        """表本不该含被引用点，但删除逻辑必须清悬挂引用——这是防损坏的双保险。"""
        pack = pack_with([[pt("a", prereq=["b"]), pt("b")]])
        removed, dangling = dp.delete(pack, {("MATH", "b")})
        self.assertEqual(1, removed)
        self.assertEqual(1, dangling)
        self.assertEqual([], pack["subjects"][0]["topics"][0]["knowledgePoints"][0]["prerequisiteSlugs"])

    def test_is_idempotent(self):
        pack = pack_with([[pt("a"), pt("b")]])
        self.assertEqual(1, dp.delete(pack, {("MATH", "a")})[0])
        # 第二次：a 已不在，删 0
        self.assertEqual(0, dp.delete(pack, {("MATH", "a")})[0])
        self.assertEqual(1, dp._point_count(pack))

    def test_prereq_stays_a_list(self):
        """prerequisiteSlugs 必须是 list（JSON 可序列化）——set 会让 dump 崩溃。"""
        pack = pack_with([[pt("a", prereq=["b"]), pt("b"), pt("c")]])
        dp.delete(pack, {("MATH", "b")})
        self.assertIsInstance(pack["subjects"][0]["topics"][0]["knowledgePoints"][0]["prerequisiteSlugs"], list)


class RealPackTest(unittest.TestCase):
    def test_delete_is_idempotent_on_shipped(self):
        """当前成品已应用过 point_delete.csv；再跑必须删 0（幂等，重放确定）。"""
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        deletes = dp.load_deletes()
        self.assertEqual(0, dp.delete(pack, deletes)[0])
        self.assertEqual(0, dp.delete(pack, deletes)[1], "不得清理悬挂前置")

    def test_shipped_point_count(self):
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        # 2026-09-19：两批视觉转写新增 328 个方法节点、别名取证反查改绑 92 条并合并 9 条同物重复
        # 2026-09-19 坏名分流收口：把 fix_bad_names 的定稿移植进权威表后又合并 9 条
        # （属性条目/碎片并入主节点）→ 2434−9=2425
        # 同日再删 1 条题干残片（`(1)写出分子式为C5H12的烷烃的结构简式：`，连同它唯一的
        # 题干材料，用户批准）→ 2424
        # 2026-09-19 文本判定轮：化学/生物「一轮复习讲义·学生版 + 知识清单·学生版」9,405 块判定入库，
        # 新建 428 个知识点并合并 1 条同物重复 → 2424 + 428 = 2852
        # 2026-09-19 文本判定第二轮（并行会话）再入库 729 个知识点（sidecar 滚到 v2-10）
        # → 2852 + 729 = 3572。点数随入库轮次增长，由入库侧在提交里维护本 pin。
        self.assertEqual(3572, dp._point_count(pack))

    def test_purge_table_refs_is_clean_on_shipped(self):
        """成品已删过点、外部表已清过——再 purge 必须 0（无悬空引用残留）。"""
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        purged = dp._purge_table_refs(pack)
        self.assertEqual(0, sum(purged.values()), purged)

    def test_cli_runs_without_crashing(self):
        """**CLI 必须真的能跑通**——这是库函数级用例照不到的一块。

        它消灭的失败（2026-09-19 实测）：`main()` 里"用前后集合求差算出被删 slug"那一段写的是
        `s["subject"]`，而 `iter_points` 产出的第一项是 subject **字符串**，于是 CLI 一跑就
        TypeError；而库函数 `delete()` 的用例全绿、没有任何门会红——因为"加一段无损求差"
        的改动**没有对应的执行验证**。同类形态在别的表驱动工具上也查一遍。
        """
        self.assertEqual(0, dp.main([]), "delete_points 的 CLI 在成品上应当跑通且不改任何东西")


if __name__ == "__main__":
    unittest.main()
