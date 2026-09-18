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

    def test_shipped_point_count_is_2104(self):
        from kb_build import delete_points as dp
        pack = pack_io.load_json(pack_io.pack_path())
        self.assertEqual(2104, dp._point_count(pack))


if __name__ == "__main__":
    unittest.main()
