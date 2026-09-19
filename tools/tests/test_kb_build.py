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
        # sidecar 按单卷 2.5M 字符上限滚动：2026-09-19 文本判定两轮后滚到 v2-10 → 1 树 + 10 卷。
        # 卷数随材料入库增长，断言的是"当前成品构成"，滚动入库时随更新（台账可查）。
        self.assertEqual(11, len(targets), "知识库应由 1 个知识树 + 10 个 sidecar 组成")
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
             "unbound_materials", "ghost_aliases", "alias_collision", "undeclared_prereq",
             "boundary_excerpt", "locator_boundary", "latex_damage", "control_chars",
             "chapter_uncovered_units", "chapter_locator_mismatch", "chapter_no_book",
             "chapter_split_missing_override", "topic_name_carries_path",
             "chapter_layer_has_points", "topic_parent_after_child"},
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
        self.assertEqual(0, metrics["bad_names"].value)
        self.assertEqual(0, metrics["duplicate_names"].value)
        # 2026-09-19：unbound_points 0→2。别名取证反查（audit_bindings_by_alias）发现 96 条错绑嫌疑，
        # 逐条裁定后改绑 92 条（含 9 条同物重复的合并）——被"错绑材料假装覆盖"的两个知识点露出来了：
        # MATH「由线、面关系误解向量关系」、CHEMISTRY「自然资源的开发利用」。它们是真内容缺口，
        # 待后续轮次补材料，不做数字上的遮掩。
        # 同日并行入库轮（讲义/知识清单）补上了 MATH 那条的材料 → 2→1；
        # 剩 CHEMISTRY「自然资源的开发利用」，缺料登记册（O 节）里注明"块池只有真题碎片"。
        self.assertEqual(1, metrics["unbound_points"].value)
        self.assertEqual(892, metrics["unbound_materials"].value)
        # 2026-09-19 两批扫描件视觉转写入库（951 + 3,609 条材料）后：
        #   unbound_points 1→0（最后一个零材料点拿到材料）。
        # 同日别名重建（rebuild_aliases：别名必须来自本节点绑定材料标题、全局互斥）后：
        #   ghost_aliases 919→0、alias_collision 0（新建指标）。两项都从此充当防回归哨兵。
        self.assertEqual(0, metrics["ghost_aliases"].value)
        self.assertEqual(0, metrics["alias_collision"].value)
        # 2026-09-19 文本级修复收口：latex_damage 194→0、control_chars 80→0。
        # 修复逻辑**早就在**（textfix + build.py._repair_material_text），但那条路只在已停用的
        # build.py 的**内存**里跑过，成品 sidecar 一条都没改——"算得出该修什么"与"真的改到
        # 成品"之间断了。补上写回通道（`fix_material_text`：与 build.py 同一套修复、同一顺序，
        # 带无损三查与幂等）后归零。两项从此充当防回归哨兵。
        self.assertEqual(0, metrics["latex_damage"].value)
        self.assertEqual(0, metrics["control_chars"].value)
        # 2026-09-19 内容裁定轮（R 节）：boundary_excerpt 675→0（675 条含第三方原文摘录的边界
        # 全部按合规要求重写为自己的归纳，不再保存原文段落）、locator_boundary 579→0
        # （579 条只有定位串/占位的边界全部补写了真边界正文）。两项从此充当防回归哨兵。
        self.assertEqual(0, metrics["boundary_excerpt"].value)
        self.assertEqual(0, metrics["locator_boundary"].value)
        # 2026-09-19 内容裁定轮（R 节）：undeclared_prereq 1246→0。1246 条前置逐条语义审计
        # （valid 259 / inverted 147 / unrelated 840——67% 的边根本不成立，印证"前置是假链"），
        # 假边删除、反边翻转，`prereq_map.csv` 由最终图（406 条真边）整体重建。哨兵。
        self.assertEqual(0, metrics["undeclared_prereq"].value)
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
        # （ghost_aliases 2026-09-19 别名重建后修到 0，同上。）
        # （latex_damage / control_chars 2026-09-19 文本修复写回成品后修到 0，同上。）
        # chapter_locator_mismatch 2026-09-19 裁定"章表权威"后由 align_chapter_locators
        # 全量对齐（1676→1307→1306→0），单元映射逐一目验过教材目录：
        # 它从此是防回归哨兵——新内容带着旧写法定位串进包时它会红。
        self.assertEqual(0, metrics["chapter_locator_mismatch"].value)
        # unbound_points 2026-09-19 改绑后回到 2（真实内容缺口），并行入库补 1 条后剩 1，
        # 留在"必须非零"列表里（它是内容缺口哨兵，不是可工程修的缺陷）。
        # duplicate_names / bad_names 已修到 0（上方专门断言），不在此"必须非零"列表。
        # undeclared_prereq / boundary_excerpt / locator_boundary 2026-09-19 内容裁定轮（R 节）
        # 修到 0，已移出此列（上方 assertEqual(0,…) 充当防回归哨兵）。
        for key in ("unbound_points", "unbound_materials"):
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
