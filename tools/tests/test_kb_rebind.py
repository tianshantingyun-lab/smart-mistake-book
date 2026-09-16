# -*- coding: utf-8 -*-
"""rebind_materials + create_points：绑定修复的两个机制。"""

from __future__ import annotations

import unittest

from kb_build import create_points as cp
from kb_build import rebind_materials as rb


def node_id(slug: str) -> str:
    return f"kb:t:math:atomic:{slug}"


def pack_with(slugs: list[str]) -> dict:
    return {
        "schemaVersion": 2, "packId": "t", "taxonomyVersion": "t", "sourceNamespace": "n",
        "reviewedAtEpochMillis": 1, "sourceUri": "https://e.t",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL", "teachingSupportLevel": "PARTIAL"},
        "subjects": [{"subject": "MATH", "sourceFingerprint": "A" * 64,
                      "topics": [{"slug": "t0", "name": "章", "sourceLocator": "l",
                                  "knowledgePoints": [
                                      {"slug": s, "name": s, "aliases": [], "kind": "CONCEPT",
                                       "boundary": "b", "sourceLocator": "l", "prerequisiteSlugs": []}
                                      for s in slugs]}]}],
    }


def sidecar_with(material_slug: str, node: str) -> dict:
    return {"schemaVersion": 2, "packId": "t", "sources": [],
            "materials": [{"slug": material_slug, "subject": "MATH", "type": "CONCEPT_EXPLANATION",
                           "title": "t", "summaryMarkdown": "s", "applicabilityMarkdown": "a",
                           "contentMarkdown": "c", "boundaryMarkdown": "b",
                           "derivationKind": "REVIEWED_SYNTHESIS", "sourceId": "s",
                           "sourceLocator": "l", "reviewedAtEpochMillis": 1,
                           "bindings": [{"knowledgeNodeId": node, "role": "PRIMARY"}]}]}


class RebindTest(unittest.TestCase):
    def test_repoints_material_to_correct_node(self):
        pack = pack_with(["fat", "target"])
        sc = [sidecar_with("m1", node_id("fat"))]
        rows = [{"material_slug": "m1", "from_node_slug": "fat", "to_node_slug": "target",
                 "evidence": "e"}]
        stats = rb.rebind(pack, sc, rows)
        self.assertEqual(1, stats["rebound"])
        self.assertEqual(node_id("target"), sc[0]["materials"][0]["bindings"][0]["knowledgeNodeId"])
        self.assertTrue(stats and not stats["bad_to"])

    def test_refuses_nonexistent_target(self):
        pack = pack_with(["fat"])
        sc = [sidecar_with("m1", node_id("fat"))]
        rows = [{"material_slug": "m1", "from_node_slug": "fat", "to_node_slug": "ghost", "evidence": "e"}]
        stats = rb.rebind(pack, sc, rows)
        self.assertEqual(0, stats["rebound"])
        self.assertEqual(1, len(stats["bad_to"]), "目标节点不存在必须拒，不能静默改绑到别处")

    def test_is_idempotent(self):
        pack = pack_with(["fat", "target"])
        sc = [sidecar_with("m1", node_id("fat"))]
        rows = [{"material_slug": "m1", "from_node_slug": "fat", "to_node_slug": "target", "evidence": "e"}]
        self.assertEqual(1, rb.rebind(pack, sc, rows)["rebound"])
        self.assertEqual(0, rb.rebind(pack, sc, rows)["rebound"], "已改绑的必须幂等跳过")


class CreateTest(unittest.TestCase):
    def test_adds_point_under_parent_topic(self):
        pack = pack_with(["a"])
        rows = [{"subject": "MATH", "slug": "b", "name": "新点", "kind": "CONCEPT",
                 "parent_topic_slug": "t0", "boundary": "定位：章。"}]
        stats = cp.create_points(pack, rows)
        self.assertEqual(1, stats["created"])
        slugs = [p["slug"] for p in pack["subjects"][0]["topics"][0]["knowledgePoints"]]
        self.assertIn("b", slugs)

    def test_refuses_missing_parent(self):
        pack = pack_with(["a"])
        rows = [{"subject": "MATH", "slug": "b", "name": "新点", "kind": "CONCEPT",
                 "parent_topic_slug": "nope", "boundary": ""}]
        stats = cp.create_points(pack, rows)
        self.assertEqual(0, stats["created"])
        self.assertEqual(1, len(stats["errors"]))

    def test_idempotent(self):
        pack = pack_with(["a"])
        rows = [{"subject": "MATH", "slug": "b", "name": "b", "kind": "CONCEPT",
                 "parent_topic_slug": "t0", "boundary": ""}]
        self.assertEqual(1, cp.create_points(pack, rows)["created"])
        self.assertEqual(0, cp.create_points(pack, rows)["created"], "已存在必须幂等跳过")


class RealPackTest(unittest.TestCase):
    def test_rebind_idempotent_on_shipped(self):
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        sc = [pack_io.load_json(sp) for sp in pack_io.sidecar_paths()]
        rows = rb.load_rebinds()
        self.assertEqual(0, rb.rebind(pack, sc, rows)["rebound"], "已改绑的光合作用材料必须幂等")

    def test_photorespiration_node_exists(self):
        from kb_build import pack_io
        pack = pack_io.load_json(pack_io.pack_path())
        slugs = {k["slug"] for s in pack["subjects"] for t in s["topics"] for k in t.get("knowledgePoints") or []}
        self.assertIn("光呼吸", slugs)


if __name__ == "__main__":
    unittest.main()
