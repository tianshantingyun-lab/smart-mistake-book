# -*- coding: utf-8 -*-
"""materialize：判定表 → 材料入库的计划校验门。"""

from __future__ import annotations

import unittest

from kb_coverage import materialize as mat


def _row(**over):
    base = {
        "chunk_rel": "2026年新高考资料/一轮复习/2026年体育单招数学零基础一轮总复习/资料/1.1集合的概念（讲义）（学生版）.docx",
        "chunk_id": "9a51501f22-001",
        "action": "MATERIAL",
        "node_slug": "交集与并集",
        "type": "CONCEPT_EXPLANATION",
        "title": "t", "summary": "s", "applicability": "a",
        "content": "c", "boundary": "b", "note": "",
    }
    base.update(over)
    return base


class PlanTest(unittest.TestCase):
    def test_valid_row_plans_clean(self):
        pl = mat.plan([_row()])
        self.assertEqual([], pl["errors"])
        self.assertEqual(1, pl["material_count"])

    def test_unknown_node_is_error(self):
        pl = mat.plan([_row(node_slug="不存在的节点xyz")])
        self.assertTrue(any("节点不存在" in e for e in pl["errors"]), pl["errors"])

    def test_bad_type_is_error(self):
        pl = mat.plan([_row(type="NOT_A_TYPE")])
        self.assertTrue(any("非法 type" in e for e in pl["errors"]), pl["errors"])

    def test_missing_chunk_is_error(self):
        pl = mat.plan([_row(chunk_id="0000000000-999")])
        self.assertTrue(any("块不存在" in e for e in pl["errors"]), pl["errors"])

    def test_empty_title_is_error(self):
        pl = mat.plan([_row(title="   ")])
        self.assertTrue(any("缺 title" in e for e in pl["errors"]), pl["errors"])


if __name__ == "__main__":
    unittest.main()
