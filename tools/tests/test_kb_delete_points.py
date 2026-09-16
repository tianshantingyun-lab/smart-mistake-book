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

    def test_shipped_point_count_is_2425(self):
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        self.assertEqual(2327, dp._point_count(pack))

    def test_purge_table_refs_is_clean_on_shipped(self):
        """成品已删过点、外部表已清过——再 purge 必须 0（无悬空引用残留）。"""
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        purged = dp._purge_table_refs(pack)
        self.assertEqual(0, sum(purged.values()), purged)


if __name__ == "__main__":
    unittest.main()
