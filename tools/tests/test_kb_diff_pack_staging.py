# -*- coding: utf-8 -*-
"""staging↔成品差异工具的用例：晋升前那一眼必须看得见该看的、不看无关的。

要钉两侧：改了 boundary 必须报出来（含字数变化）；只是 JSON 键序/空白不同则**不许**报
（否则每次晋升都刷一堆假差异，人就不看了）。
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_build import diff_pack_staging as dp  # noqa: E402

PACK = dp.PACK


def _family(boundary: str, mat_text: str, order: tuple[str, str] = ("甲", "乙")) -> dict:
    pack = {"subjects": [{"subject": "MATH", "topics": [
        {"name": order[0], "knowledgePoints": [
            {"slug": "a", "name": "甲点", "boundary": boundary, "aliases": ["甲点"],
             "kind": "CONCEPT", "sourceLocator": "x", "prerequisiteSlugs": []}]},
        {"name": order[1], "knowledgePoints": []},
    ]}]}
    mats = {"materials": [{"slug": "m1", "title": "材料", "contentMarkdown": mat_text,
                           "bindings": [{"knowledgeNodeId": "kb:p:math:atomic:a"}]}]}
    return {"pack": pack, "mats": mats}


class DiffTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="diff-"))
        self.stg = self.tmp / "staging"
        self.shp = self.tmp / "shipped"
        for d in (self.stg, self.shp):
            d.mkdir(parents=True)

    def _write(self, base: Path, fam: dict, **pack_kw):
        (base / PACK).write_text(json.dumps({**fam["pack"], **pack_kw}, ensure_ascii=False),
                                 encoding="utf-8")
        (base / "moe-2025-teaching-support-v2-01.json").write_text(
            json.dumps(fam["mats"], ensure_ascii=False), encoding="utf-8")

    def test_boundary_change_is_reported_with_lengths(self):
        self._write(self.shp, _family("定位：X。旧的一条短边界。", "正文"))
        self._write(self.stg, _family("定位：X。新的一条更长的边界，补了后半句。", "正文"))
        out = dp.diff_families(self.stg, self.shp)
        self.assertEqual(1, len(out["changed"]), out)
        self.assertEqual("boundary", out["changed"][0]["field"])
        self.assertIn("字", out["changed"][0]["what"])

    def test_material_text_change_is_reported(self):
        self._write(self.shp, _family("边界", "旧正文"))
        self._write(self.stg, _family("边界", "新正文更长了"))
        out = dp.diff_families(self.stg, self.shp)
        self.assertEqual(["material.contentMarkdown"], [c["field"] for c in out["changed"]])

    def test_identical_families_report_nothing(self):
        fam = _family("边界", "正文")
        self._write(self.shp, fam)
        self._write(self.stg, fam)
        self.assertEqual([], dp.diff_families(self.stg, self.shp)["changed"])

    def test_added_and_removed_points(self):
        a = _family("边界", "正文")
        b = _family("边界", "正文")
        b["pack"]["subjects"][0]["topics"][0]["knowledgePoints"].append(
            {"slug": "b", "name": "乙点", "boundary": "新加的", "aliases": [], "kind": "CONCEPT",
             "sourceLocator": "y", "prerequisiteSlugs": []})
        self._write(self.shp, a)
        self._write(self.stg, b)
        out = dp.diff_families(self.stg, self.shp)
        self.assertIn("新增知识点", [c["what"] for c in out["changed"]])

    def test_topic_order_change_is_reported(self):
        self._write(self.shp, _family("边界", "正文", ("甲", "乙")))
        self._write(self.stg, _family("边界", "正文", ("乙", "甲")))
        out = dp.diff_families(self.stg, self.shp)
        self.assertEqual([], out["changed"], "顺序变化不是字段差异")
        self.assertTrue(out["topic_delta"]["order_changed"])

    def test_missing_file_reports_error(self):
        self._write(self.shp, _family("边界", "正文"))
        rc = dp.main(["--staging", str(self.stg), "--shipped", str(self.shp)])
        self.assertEqual(2, rc)


if __name__ == "__main__":
    unittest.main()
