# -*- coding: utf-8 -*-
"""`fix_material_text` 的用例：修得对、只修该修的、成品已修好。

它消灭的失败（登记册 M-04）：修复逻辑（`textfix` + `build.py._repair_material_text`）
一直都在，但只在**已停用的** build.py 的内存里跑过——成品 sidecar 里 194 条 LaTeX 损坏、
80 条控制字符一条都没改。`report_latex` 早就能算出"194 条全部可修"，盘上还是坏的。
所以这里第一条用例就是"成品已修好"的哨兵：再出现任何一条未修的残迹都会红。
"""

from __future__ import annotations

import copy
import unittest

from kb_build import fix_material_text as F, pack_io


def material(**fields) -> dict:
    base = {"slug": "m", "subject": "MATH", "title": "", "summaryMarkdown": "",
            "applicabilityMarkdown": "", "contentMarkdown": "", "boundaryMarkdown": "",
            "bindings": [{"knowledgeNodeId": "kb:x:math:atomic:y"}]}
    base.update(fields)
    return base


class RepairTest(unittest.TestCase):
    def test_form2_damage_is_repaired_before_control_chars_are_stripped(self):
        """形态 2 的证据就是控制符本身（<TAB>heta ← \\theta），先 strip 就修不动了。"""
        doc = {"materials": [material(title="已知\t值求角")]}
        F.repair_document(doc)
        self.assertNotIn("\t", doc["materials"][0]["title"])

    def test_form1_merged_command_is_repaired(self):
        doc = {"materials": [material(contentMarkdown="由余弦定理 $\\coslpha$ 得")]}
        F.repair_document(doc)
        self.assertIn("\\cos\\alpha", doc["materials"][0]["contentMarkdown"])

    def test_clean_text_is_not_touched(self):
        """只改真坏的材料——干净文本一个字节都不动，否则整个包会被无意义地重写。"""
        doc = {"materials": [material(title="函数的单调性", contentMarkdown="干净内容，无残迹。")]}
        before = copy.deepcopy(doc)
        stats = F.repair_document(doc)
        self.assertEqual({"damaged": 0, "junk": 0, "repaired": 0}, {k: stats[k] for k in ("damaged", "junk", "repaired")})
        self.assertEqual(before, doc)

    def test_only_the_five_text_fields_change(self):
        """其余字段（尤其 bindings）不许被碰——那是材料与节点的连接。"""
        doc = {"materials": [material(title="已知\t值", bindings=[{"knowledgeNodeId": "kb:x:math:atomic:y"}])]}
        before = F._other_field_fingerprint(doc)
        F.repair_document(doc)
        self.assertEqual(before, F._other_field_fingerprint(doc))

    def test_repair_is_idempotent(self):
        doc = {"materials": [material(title="已知\t值求角", contentMarkdown="$\\rac{1}{2}$")]}
        F.repair_document(doc)
        once = copy.deepcopy(doc)
        self.assertEqual(0, F.repair_document(doc)["repaired"])
        self.assertEqual(once, doc)


class ShippedPackTest(unittest.TestCase):
    def test_shipped_sidecars_need_no_more_repair(self):
        """哨兵：成品里不该再有未修的 LaTeX 损坏或控制字符残迹。"""
        docs = [pack_io.load_json(p) for p in pack_io.sidecar_paths()]
        for stats in (F.repair_document(doc) for doc in docs):
            self.assertEqual(0, stats["damaged"], "成品仍有 LaTeX 损坏的材料")
            self.assertEqual(0, stats["junk"], "成品仍有含控制字符的材料")

    def test_repair_is_a_no_op_on_the_shipped_sidecars(self):
        """幂等：按写盘-读回路径重放一遍，必须 0 改动。"""
        for path in pack_io.sidecar_paths():
            doc = pack_io.load_json(path)
            F.repair_document(doc)
            again = F.repair_document(pack_io.load_json(path))
            self.assertEqual(0, again["repaired"], f"{path.name} 的修复不幂等")


if __name__ == "__main__":
    unittest.main()
