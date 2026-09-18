# -*- coding: utf-8 -*-
"""kb_build 包的契约测试。

覆盖三件事：
1. 生成器对现行成品可忠实重放（round-trip）——这是所有后续修正可信的前提。
2. 内容质量门能真实反映缺陷，且目标是全 0。
3. 权威表的 schema 校验会拒绝坏输入，而不是静默接受。
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from kb_build import gate, pack_io, roundtrip, tables, textfix


class RoundTripTest(unittest.TestCase):
    def test_every_bundled_file_round_trips(self):
        targets = [pack_io.pack_path(), *pack_io.sidecar_paths()]
        self.assertEqual(7, len(targets), "知识库应由 1 个知识树 + 6 个 sidecar 组成")
        for path in targets:
            with self.subTest(path=path.name):
                ok, message = roundtrip.verify_file(path)
                self.assertTrue(ok, message)

    def test_serialize_uses_lf_without_trailing_newline(self):
        """规范形态由 .gitattributes（*.json text eol=lf）决定。"""
        text = pack_io.serialize({"a": 1})
        self.assertNotIn("\r\n", text)
        self.assertFalse(text.endswith("\n"))

    def test_path_outside_repository_is_rejected(self):
        with self.assertRaises(ValueError):
            pack_io.load_json(Path("C:/Windows/System32/drivers/etc/hosts"))


class PackShapeTest(unittest.TestCase):
    """成品必须满足 ReviewedKnowledgePackJsonCodec 的严格键集合。"""

    def setUp(self):
        self.pack = pack_io.load_json(pack_io.pack_path())

    def test_root_keys_are_exact(self):
        self.assertEqual(
            {"schemaVersion", "packId", "taxonomyVersion", "sourceNamespace",
             "reviewedAtEpochMillis", "sourceUri", "coverage", "subjects"},
            set(self.pack),
        )

    def test_pack_id_equals_taxonomy_version(self):
        self.assertEqual(self.pack["packId"], self.pack["taxonomyVersion"])

    def test_point_keys_are_exactly_seven(self):
        expected = {"slug", "name", "aliases", "kind", "boundary",
                    "sourceLocator", "prerequisiteSlugs"}
        for subject, _topic, point in pack_io.iter_points(self.pack):
            with self.subTest(subject=subject, slug=point["slug"]):
                self.assertEqual(expected, set(point))

    def test_subject_and_topic_keys(self):
        for subject in self.pack["subjects"]:
            self.assertEqual({"subject", "sourceFingerprint", "topics"}, set(subject))
            for topic in subject["topics"]:
                self.assertTrue(
                    set(topic) <= {"slug", "name", "sourceLocator", "parentSlug", "knowledgePoints"}
                )

    def test_prerequisites_stay_inside_subject(self):
        for subject in self.pack["subjects"]:
            slugs = {
                p["slug"]
                for topic in subject["topics"]
                for p in topic.get("knowledgePoints") or []
            }
            for topic in subject["topics"]:
                for point in topic.get("knowledgePoints") or []:
                    for prereq in point["prerequisiteSlugs"]:
                        self.assertIn(prereq, slugs, f"{point['slug']} 的前置跨科或悬空")

    def test_sidecar_materials_carry_exactly_the_contract_keys(self):
        expected = {"slug", "subject", "type", "title", "summaryMarkdown",
                    "applicabilityMarkdown", "contentMarkdown", "boundaryMarkdown",
                    "derivationKind", "sourceId", "sourceLocator",
                    "reviewedAtEpochMillis", "bindings"}
        for path, material in pack_io.load_materials():
            with self.subTest(slug=material["slug"]):
                self.assertEqual(expected, set(material))


class GateTest(unittest.TestCase):
    def test_gate_reports_real_defects_not_zero(self):
        """修复前门禁必须失败——否则它不是在测真东西。"""
        metrics = {m.key: m for m in gate.evaluate()}
        self.assertEqual(
            {"bad_names", "starred_names", "duplicate_names", "unbound_points",
             "unbound_materials", "ghost_aliases", "undeclared_prereq",
             "boundary_excerpt", "locator_boundary", "latex_damage", "control_chars",
             "chapter_uncovered_units", "chapter_locator_mismatch", "chapter_no_book",
             "chapter_split_missing_override", "topic_name_carries_path",
             "chapter_layer_has_points"},
            set(metrics),
        )
        # 这几项是审计里逐一复核过的硬数字，门禁必须能复现。
        # 2026-09-16 章层结构修复 + 去星 + 删残渣/碎片 + 合并同章/跨章重复后的快照
        # （这些 pin 随结构修复推进而变，每次改动在提交里说明来源）：
        #   unbound_points 998→701（删残渣/碎片本就无材料；合并使部分目标获首条材料）
        #   ghost_aliases 847→852、boundary_excerpt 968→835、locator_boundary 589→585
        #   2026-09-18 残渣删除 116 点 + 整句话名收敛 102 个（旧句名进别名，ghost 836→897）后：
        #   unbound_points 701→698（删 3 个零材料占位点）、ghost_aliases 852→849、
        #   boundary_excerpt 835→833、locator_boundary 585→581
        #   集合基础 8 节点 + 命题节点入库材料后：unbound_points 698→697
        # starred_names 96→0、duplicate_names 118→0：已修复，见下方专门断言。
        self.assertEqual(0, metrics["starred_names"].value)
        self.assertEqual(0, metrics["duplicate_names"].value)
        self.assertEqual(142, metrics["unbound_points"].value)
        self.assertEqual(892, metrics["unbound_materials"].value)
        self.assertEqual(872, metrics["ghost_aliases"].value)
        self.assertEqual(194, metrics["latex_damage"].value)
        self.assertEqual(80, metrics["control_chars"].value)
        self.assertEqual(698, metrics["boundary_excerpt"].value)
        # 只留占位写法（`（见知识清单/教材）`）的条数。旧判据是"以 `定位：` 开头"，
        # 而本包每个 boundary 都这样开头，于是该项恒等于节点总数 2573、毫无信息量。
        self.assertEqual(581, metrics["locator_boundary"].value)
        # 两项必须不相交：一条边界不可能既是原文摘录、又是没写边界。
        # 旧判据下两项交集 1984、皆假 0，即"任何写法都至少中一项"，指标失去意义。
        bundled = pack_io.load_json(pack_io.pack_path())
        excerpt_ids = {
            (subject, point["slug"])
            for subject, _t, point in pack_io.iter_points(bundled)
            if textfix.has_verbatim_excerpt(point.get("boundary") or "")
        }
        locator_ids = {
            (subject, point["slug"])
            for subject, _t, point in pack_io.iter_points(bundled)
            if textfix.is_locator_only(point.get("boundary") or "")
        }
        self.assertEqual(set(), excerpt_ids & locator_ids)
        # 缺陷类指标修复前必须非零——否则门禁不是在测真东西。
        # （starred_names 已修到 0，移出此列；上方 assertEqual(0,…) 现充当防回归哨兵。）
        # duplicate_names 已修到 0（上方专门断言），不在此"必须非零"列表
        for key in ("bad_names", "unbound_points",
                    "unbound_materials", "ghost_aliases", "undeclared_prereq",
                    "boundary_excerpt", "locator_boundary", "latex_damage",
                    "control_chars", "chapter_locator_mismatch"):
            with self.subTest(metric=key):
                self.assertFalse(metrics[key].ok, f"{key} 修复前不该是 0")
        # 章节覆盖率与归属完整性在现行包上本来就是满的，不该被当成缺陷
        self.assertTrue(metrics["chapter_uncovered_units"].ok)
        self.assertTrue(metrics["chapter_no_book"].ok)

    def test_bad_name_detector_classifies_known_shapes(self):
        cases = {
            "定义：": "只有标题冒号",
            "分类": "通用名词无主语",
            "（2025·安徽蚌埠·三模）已知，则（   ）": "高考题干残句",
            "预测：": "题干/答案标记",
            "偶次方根的被开方数的被开方数必须大于等于零，即中": "公式被剥离",
            "配方法：主要用于二次函数或可化为二次函数的函数，要特别注意自变量的取值范围．": "整句话当名称",
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                self.assertEqual(expected, gate._is_bad_name(name))
        # 正常名称不得误报
        for good in ("函数的概念", "功", "氧化还原反应", "染色体变异"):
            with self.subTest(good=good):
                self.assertIsNone(gate._is_bad_name(good))

    def test_latex_damage_detector_flags_merged_commands(self):
        """损坏形态是"后一个命令名被前一个命令吞掉"（控制字符被剥掉所致）。"""
        for damaged in (
            r"$ab\lerac{a^2+b^2}{2}$",      # \le + (剥掉的\v) + rac  -> 实际是 \frac
            r"$ec{a}\parallelec{b}$",        # \parallel + \vec -> \parallelec
            r"$\coslpha$",                   # \cos + \alpha
            r"$\cdotec{a}$",                 # \cdot + \vec
            r"$0\leheta\le\pi$",             # \le + \theta
            r"$x\Rightarrowec{y}$",          # \Rightarrow + \vec
        ):
            with self.subTest(damaged=damaged):
                self.assertTrue(gate._latex_damaged(damaged))

    def test_latex_damage_detector_does_not_flag_real_commands(self):
        for good in (
            r"$\frac{a}{b}$", r"$\dfrac{a}{b}$", r"$\tfrac{a}{b}$",
            r"$\vec{a}\parallel\vec{b}$", r"$\theta\in[0,\pi]$",
            r"$a\Rightarrow b$", r"$\Leftrightarrow$", r"$\triangle ABC$",
            r"$a\leqslant b$", r"$\langle a,b\rangle$", r"$\mathbb{R}$",
            r"$\left(\dfrac{a}{b}\right)$",
        ):
            with self.subTest(good=good):
                self.assertFalse(gate._latex_damaged(good))


class TablesTest(unittest.TestCase):
    def test_missing_tables_are_treated_as_undeclared(self):
        """表不存在 = 未定稿；门禁按未声明前置处理，因此修复前必然失败。"""
        mapping = tables.load_prereq_map()
        self.assertIsInstance(mapping, dict)
        self.assertFalse(tables.is_declared_prereq(mapping, "不存在", "不存在"))
        self.assertFalse(tables.is_declared_prereq({}, "任意", "任意"))

    def test_is_declared_prereq_reads_the_entry(self):
        mapping = {"函数的概念": {"subject": "MATH", "prerequisites": ["集合的概念"]}}
        self.assertTrue(tables.is_declared_prereq(mapping, "函数的概念", "集合的概念"))
        self.assertFalse(tables.is_declared_prereq(mapping, "函数的概念", "别的"))

    def test_bad_action_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            original = tables.TABLES_DIR
            try:
                tables.TABLES_DIR = Path(tmp)
                (Path(tmp) / tables.NODE_ACTIONS).write_text(
                    "subject,slug,action,new_name,new_slug\nMATH,a,explode,,\n",
                    encoding="utf-8",
                )
                with self.assertRaises(ValueError):
                    tables.load_node_actions()
            finally:
                tables.TABLES_DIR = original

    def test_duplicate_material_binding_is_rejected(self):
        """合同要求每个材料恰好 1 条 PRIMARY 绑定。"""
        with tempfile.TemporaryDirectory() as tmp:
            original = tables.TABLES_DIR
            try:
                tables.TABLES_DIR = Path(tmp)
                (Path(tmp) / tables.MATERIAL_BINDINGS).write_text(
                    "material_slug,point_slug\nm1,p1\nm1,p2\n", encoding="utf-8"
                )
                with self.assertRaises(ValueError):
                    tables.load_material_bindings()
            finally:
                tables.TABLES_DIR = original


if __name__ == "__main__":
    unittest.main()
