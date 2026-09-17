# -*- coding: utf-8 -*-
"""merge_points：合并同章同名去重副本（X 与 X-x1），5 处联动 + 无损。"""

from __future__ import annotations

import unittest

from kb_build import merge_points as mp


def pt(slug: str, name: str | None = None, prereq: list[str] | None = None) -> dict:
    return {"slug": slug, "name": name or slug, "aliases": [], "kind": "CONCEPT",
            "boundary": "b", "sourceLocator": "l", "prerequisiteSlugs": prereq or []}


def pack_with(points: list[dict]) -> dict:
    return {
        "schemaVersion": 2, "packId": "t", "taxonomyVersion": "t", "sourceNamespace": "n",
        "reviewedAtEpochMillis": 1, "sourceUri": "https://e.t",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL", "teachingSupportLevel": "PARTIAL"},
        "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64,
                      "topics": [{"slug": "t0", "name": "章", "sourceLocator": "l",
                                  "knowledgePoints": points}]}],
    }


def sidecar_with(binding_id: str) -> dict:
    return {"schemaVersion": 2, "packId": "t", "sources": [],
            "materials": [{"slug": "m1", "subject": "MATH", "type": "CONCEPT_EXPLANATION",
                           "title": "t", "summaryMarkdown": "s", "applicabilityMarkdown": "a",
                           "contentMarkdown": "c", "boundaryMarkdown": "b",
                           "derivationKind": "REVIEWED_SYNTHESIS", "sourceId": "s",
                           "sourceLocator": "l", "reviewedAtEpochMillis": 1,
                           "bindings": [{"knowledgeNodeId": binding_id, "role": "PRIMARY"}]}]}


def node_id(slug: str) -> str:
    return f"kb:t:math:atomic:{slug}"


class MergeTest(unittest.TestCase):
    def test_merge_repoints_material_and_deletes_dup(self):
        pack = pack_with([pt("a"), pt("a-x1"), pt("b")])
        sc = [sidecar_with(node_id("a-x1"))]
        rows = [{"subject": "MATH", "survivor_slug": "a", "merged_slug": "a-x1", "reason": "dup"}]
        stats = mp.merge(pack, sc, rows)
        self.assertEqual(1, stats["merged"])
        self.assertTrue(stats["ok"], stats)
        slugs = [p["slug"] for p in pack["subjects"][0]["topics"][0]["knowledgePoints"]]
        self.assertEqual(["a", "b"], sorted(slugs), "a-x1 应被删，a 保留")
        self.assertEqual(node_id("a"), sc[0]["materials"][0]["bindings"][0]["knowledgeNodeId"],
                         "材料改指 survivor")
        self.assertEqual(0, stats["dangling_bindings"])

    def test_merge_unions_prereqs_and_repoints_others(self):
        # b 以 a-x1 为前置；合并 a-x1→a 后，b 的前置应改成 a
        pack = pack_with([pt("a"), pt("a-x1", prereq=["x"]), pt("b", prereq=["a-x1"])])
        sc = [sidecar_with(node_id("a"))]
        rows = [{"subject": "MATH", "survivor_slug": "a", "merged_slug": "a-x1", "reason": "dup"}]
        stats = mp.merge(pack, sc, rows)
        by = {p["slug"]: p for p in pack["subjects"][0]["topics"][0]["knowledgePoints"]}
        self.assertIn("x", by["a"]["prerequisiteSlugs"], "merged 的前置并入 survivor")
        self.assertEqual(["a"], by["b"]["prerequisiteSlugs"], "他点对 merged 的前置改指 survivor")
        self.assertTrue(stats["ok"])

    def test_drop_prereq_removes_junk_edge_from_union(self):
        # 占位节点 a-x1 带垃圾前置 junk；drop_prereq 应把 junk 从并集里去掉，
        # 而正常前置 x 保留。
        pack = pack_with([pt("a"), pt("a-x1", prereq=["junk", "x"])])
        sc = [sidecar_with(node_id("a"))]
        rows = [{"subject": "MATH", "survivor_slug": "a", "merged_slug": "a-x1",
                 "reason": "dup", "drop_prereq": "junk"}]
        stats = mp.merge(pack, sc, rows)
        by = {p["slug"]: p for p in pack["subjects"][0]["topics"][0]["knowledgePoints"]}
        self.assertEqual(["x"], by["a"]["prerequisiteSlugs"], "junk 被丢弃，x 保留")
        self.assertTrue(stats["ok"])

    def test_drop_prereq_defaults_empty(self):
        # 没有 drop_prereq 键的旧行必须照常工作（并集不动）
        pack = pack_with([pt("a"), pt("a-x1", prereq=["junk", "x"])])
        sc = [sidecar_with(node_id("a"))]
        rows = [{"subject": "MATH", "survivor_slug": "a", "merged_slug": "a-x1", "reason": "dup"}]
        mp.merge(pack, sc, rows)
        by = {p["slug"]: p for p in pack["subjects"][0]["topics"][0]["knowledgePoints"]}
        self.assertIn("junk", by["a"]["prerequisiteSlugs"], "无 drop 列时保留并集")

    def test_is_idempotent(self):
        pack = pack_with([pt("a"), pt("a-x1")])
        sc = [sidecar_with(node_id("a-x1"))]
        rows = [{"subject": "MATH", "survivor_slug": "a", "merged_slug": "a-x1", "reason": "dup"}]
        self.assertEqual(1, mp.merge(pack, sc, rows)["merged"])
        self.assertEqual(0, mp.merge(pack, sc, rows)["merged"], "第二次必须 0（a-x1 已删）")


class RealPackTest(unittest.TestCase):
    def test_merge_idempotent_on_shipped(self):
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        sc = [pack_io.load_json(sp) for sp in pack_io.sidecar_paths()]
        rows = mp.load_merges()
        self.assertEqual(0, mp.merge(pack, sc, rows)["merged"], "已合并的必须幂等")


if __name__ == "__main__":
    unittest.main()
