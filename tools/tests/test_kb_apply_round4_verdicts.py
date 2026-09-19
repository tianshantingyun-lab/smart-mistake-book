# -*- coding: utf-8 -*-
"""`apply_round4_verdicts` 的用例（登记册 R 节的执行器）。

它消灭的失败（M-05 形态的再防）：裁定表在、执行者消失。两份裁定（前置 1246 边审计、
边界 1254 条正文）必须有一个**常驻、幂等、可重放**的执行器；共享工作树被并行改动后，
重放一遍就能把包恢复到位。
"""

from __future__ import annotations

import unittest

from kb_build import apply_round4_verdicts as AR, pack_io, tables, textfix


class ShippedSentinelTest(unittest.TestCase):
    def test_cli_runs_and_is_idempotent_on_shipped(self):
        self.assertEqual(0, AR.main([]), "执行器在成品上必须跑通且 0 改动（重放语义）")

    def test_all_prereq_edges_are_declared(self):
        """门 m7 口径：包内每条前置边都在 prereq_map.csv 里。"""
        pack = pack_io.load_json(pack_io.pack_path())
        declared = tables.load_prereq_map()
        undeclared = [
            (s, p["slug"], q)
            for s, _t, p in pack_io.iter_points(pack)
            for q in p.get("prerequisiteSlugs") or []
            if not tables.is_declared_prereq(declared, p["slug"], q)
        ]
        self.assertEqual([], undeclared, f"未声明前置 {len(undeclared)} 条")

    def test_no_undeclared_edge_is_silent_again(self):
        """裁定表是账本：包里的每条边必须能在裁定表里找到依据（valid 或 inverted 翻转）。

        防止将来有人往包里加前置边却绕过裁定表——那条边会立刻让 m7 变红。
        """
        pack = pack_io.load_json(pack_io.pack_path())
        rows = tables._read(tables.TABLES_DIR / "prereq_verdicts.csv")
        allowed = set()
        for r in rows:
            s, p, q, v = r["subject"].strip(), r["point_slug"].strip(), r["prereq_slug"].strip(), r["verdict"].strip()
            if v == "valid":
                allowed.add((s, p, q))
            elif v == "inverted":
                allowed.add((s, q, p))
        current = {(s, p["slug"], q) for s, _t, p in pack_io.iter_points(pack)
                   for q in p.get("prerequisiteSlugs") or []}
        self.assertEqual(set(), current - allowed, "包里有裁定表依据之外的前置边")

    def test_boundaries_pass_both_textfix_predicates(self):
        """门 m8a/m8 口径：成品里不再有任何 摘录 或 占位 边界。"""
        pack = pack_io.load_json(pack_io.pack_path())
        exc = [p["slug"] for _s, _t, p in pack_io.iter_points(pack)
               if textfix.has_verbatim_excerpt(p.get("boundary") or "")]
        loc = [p["slug"] for _s, _t, p in pack_io.iter_points(pack)
               if textfix.is_locator_only(p.get("boundary") or "")]
        self.assertEqual([], exc, f"仍有 {len(exc)} 条摘录边界")
        self.assertEqual([], loc, f"仍有 {len(loc)} 条占位边界")


class ApplyTest(unittest.TestCase):
    def _mini_pack(self) -> dict:
        return {"packId": "t", "subjects": [{
            "subject": "MATH",
            "topics": [{"slug": "r", "name": "根", "sourceLocator": "", "parentSlug": None,
                        "knowledgePoints": [
                            {"slug": "a", "name": "A", "boundary": "定位：x y。旧", "prerequisiteSlugs": ["b"]},
                            {"slug": "b", "name": "B", "boundary": "定位：x y。占位", "prerequisiteSlugs": []},
                        ]}]}]}

    def test_apply_moves_prereqs_and_boundaries(self):
        pack = self._mini_pack()
        prereq = [{"subject": "MATH", "point_slug": "a", "prereq_slug": "b",
                   "verdict": "inverted", "reason": "t"}]
        boundary = [{"subject": "MATH", "slug": "b", "boundary_body": "讲 B 的范围与限度。"}]
        stats = AR.apply(pack, prereq, boundary)
        pts = {p["slug"]: p for t in pack["subjects"][0]["topics"] for p in t["knowledgePoints"]}
        self.assertEqual(["a"], pts["b"]["prerequisiteSlugs"], "inverted 应把边翻到 B 一侧")
        self.assertEqual([], pts["a"]["prerequisiteSlugs"])
        self.assertTrue(pts["b"]["boundary"].endswith("讲 B 的范围与限度。"))
        self.assertTrue(pts["b"]["boundary"].startswith("定位：x y。"))
        self.assertEqual(1, stats["boundary_applied"])
        # 幂等
        stats2 = AR.apply(pack, prereq, boundary)
        self.assertEqual(0, stats2["prereq_points"] + stats2["boundary_applied"])

    def test_rejects_a_body_that_would_still_trip_the_excerpt_predicate(self):
        pack = self._mini_pack()
        bad = [{"subject": "MATH", "slug": "b", "boundary_body": "【典例1】（2025·某地模拟）已知…"}]
        with self.assertRaises(ValueError):
            AR.apply(pack, [], bad)


if __name__ == "__main__":
    unittest.main()
