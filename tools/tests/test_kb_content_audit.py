# -*- coding: utf-8 -*-
"""内容绑定审计器（audit_content_bindings）的用例：候选要出得来、锚点要报得出、正绑的不许乱报。

三个方向各钉一次：
① **当前包**：候选集非空、两次运行逐行相同（可复算），四条判据都活着；
② **临时 staging**：人为把一条材料改绑到无关节点 → 必须报出（含 C3「材料挂在靶区之外」方向）；
③ **登记锚点**（硬验收）：登记册 I-04 的 6 条在当前包上逐条被报出，且行的现绑节点必须等于
   包里的实际绑定、证据里必须同时写明「登记时绑哪」「现在绑哪」——D-8 类「报出」不等于
   「报成错绑」，锚点早已改绑的事实在证据里必须读得出来。

外加两条负向对照（本仓库既有风格）：正绑材料不许出现在候选里；半成品审计表不进
`check_pack_contract` 的五表一致性（进了会堵 promote）。
"""

from __future__ import annotations

import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

from kb_build import audit_content_bindings as A          # noqa: E402
from kb_build import check_pack_contract, pack_io         # noqa: E402

# 一条**标题与节点名一致、首句主语也是节点名**的正绑材料（负向对照用）
CLEAN_MATERIAL = "phys-kb-sliding-friction-formula"


class MechanismTest(unittest.TestCase):
    """词面机制本身：判断句识别与词元口径（首版两处假阳性就是在这里修掉的）。"""

    def test_predicate_subject(self):
        self.assertEqual("细胞壁", A.first_subject("细胞壁是全透性的：水、溶质都可自由通过。"))
        self.assertEqual("", A.first_subject("操作与现象：加入过量氨水"),
                         "行首小标题不是主语（首版把它当主语，报出一批噪声）")
        self.assertEqual("", A.first_subject("物体缓慢移动时每一时刻均可视为平衡状态。"),
                         "`视为` 里的「为」不是判断词（首版切出「…均可视」这种假主语）")
        self.assertEqual("", A.first_subject("F = μFN"))

    def test_terms_are_cjk_bigrams_plus_ascii_tokens(self):
        self.assertIn("trna", A.terms("tRNA的结构与功能"))
        self.assertIn("轻绳", A.terms("轻绳、轻弹簧与轻质弹性绳的对比"))

    def test_two_letter_ascii_fragment_is_not_a_rare_term(self):
        # Fe(OH)3胶体制备 不许靠 `fe` 去强匹配 Fe(OH)2的制备（首版假阳性）
        self.assertFalse(A._is_rare("fe", 1))
        self.assertTrue(A._is_rare("trna", 1))
        self.assertTrue(A._is_rare("态平", 1))
        self.assertFalse(A._is_rare("态平", A.RARE_DF + 1))

    def test_candidates_are_identical_across_processes_and_hash_seeds(self):
        """**跨进程**可复算：集合迭代序随 PYTHONHASHSEED 变，浮点求和次序一变产物就变。

        首版实测：稀有词并列时的排列跟着哈希走，两次运行的候选表内容不同（长度一样、字节不同）。
        这条用两个不同 seed 的子进程钉住它——同进程内比较抓不到这个 bug。
        """
        script = (
            "import hashlib, json, sys;"
            "sys.path.insert(0, 'tools');"
            "from kb_build import audit_content_bindings as A;"
            "f = A.load_facts();"
            "rows = A.verdict_rows(f, A.default_scope(f));"
            "print(hashlib.sha256(json.dumps(rows, ensure_ascii=False, sort_keys=True)"
            ".encode()).hexdigest())")
        digests = []
        for seed in ("1", "424242"):
            env = dict(os.environ, PYTHONHASHSEED=seed, PYTHONPATH=str(REPO / "tools"))
            done = subprocess.run([sys.executable, "-c", script], cwd=str(REPO), env=env,
                                  capture_output=True, text=True, check=True)
            digests.append(done.stdout.strip())
        self.assertEqual(64, len(digests[0]), digests)
        self.assertEqual(digests[0], digests[1],
                         "不同 PYTHONHASHSEED 的两个进程候选不一致——判据遍历了未排序的集合")


class CurrentPackTest(unittest.TestCase):
    """① 当前包跑一遍；③ I-04 锚点硬验收。"""

    @classmethod
    def setUpClass(cls):
        cls.facts = A.load_facts()
        cls.scope = A.default_scope(cls.facts)
        cls.rows = A.verdict_rows(cls.facts, cls.scope)

    def test_candidates_are_nonempty_and_stable(self):
        self.assertGreaterEqual(len(self.scope), 100, "靶区节点数不对——切章逻辑是不是坏了？")
        self.assertGreater(len(self.rows), 0, "候选集是空的：判据全哑了")
        self.assertEqual(self.rows, A.verdict_rows(self.facts, self.scope),
                         "同一包同一靶区两次运行必须逐行相同（可复算）")
        criteria = {row["evidence"].split("｜")[0] for row in self.rows}
        # C1 own-title-detached 按设计只有个位数（别名表由材料标题重建，把标题吸收了大半），
        # 因此只钉另外四条；判据死了必须变红。
        for name in ("own-title-alias-only", "own-subject-detached", "foreign-title-match",
                     "registered-anchor"):
            self.assertIn(name, criteria, f"判据 {name} 一行都没出——判据是不是死了？")

    def test_i04_anchors_are_reported_on_the_current_pack(self):
        anchors = A.load_anchors()
        self.assertEqual(6, len(anchors), "I-04 锚点表应恰好 6 行（登记册 §I-04 的表）")
        by_material: dict[str, list[dict]] = {}
        for row in self.rows:
            by_material.setdefault(row["material_slug"], []).append(row)
        for anchor in anchors:
            slug = anchor["material_slug"].strip()
            registered = anchor["registered_node_slug"].strip()
            self.assertIn(slug, by_material, f"登记锚点 {slug} 没被报出（硬验收）")
            row = next(r for r in by_material[slug]
                       if r["evidence"].startswith("registered-anchor"))
            self.assertEqual("slice-05", row["slice"])
            self.assertEqual("", row["verdict"], "候选 ≠ 裁定：verdict 必须留空给 WP2")
            material = self.facts.material(slug)
            self.assertIsNotNone(material, f"锚点材料 {slug} 不在当前包里")
            current = self.facts.node_of[material["slug"]][1]
            self.assertEqual(current, row["current_node_slug"],
                             "行的现绑节点必须等于包里的实际绑定（不许照抄登记册）")
            self.assertIn(registered, row["evidence"], "证据必须写明登记时绑在哪个节点")
            self.assertIn(current, row["evidence"], "证据必须写明现绑节点（读得出是否已改绑）")
            self.assertIn(material["title"], row["evidence"], "证据要能自证——材料标题就在证据里")

    def test_i04_anchors_were_already_rebound_not_still_broken(self):
        """把「锚点已被改绑」这个事实钉住：若哪天又回到登记时的绑定，这条会红。

        它同时钉住**当前包的真实状态**（本次审计的起点），免得下游把它当成「6 条现存错绑」。
        """
        still_broken = []
        for anchor in A.load_anchors():
            slug = anchor["material_slug"].strip()
            material = self.facts.material(slug)
            current = self.facts.node_of.get(material["slug"], ("", ""))[1] if material else ""
            if current == anchor["registered_node_slug"].strip():
                still_broken.append(slug)
        self.assertEqual([], still_broken,
                         "有锚点回到了登记时的绑定——那是真回归，必须报出来并重开 I-04")

    def test_shipped_table_columns_and_filled_verdicts(self):
        """WP1 出「候选」（verdict 全空待 WP2）→ WP2 出「裁定」，本表随之填满。

        这条原来是 `test_shipped_table_columns_and_blank_verdicts`（断言 verdict 列**全空**）。
        WP2 汇总（`tools/kb_build/merge_content_audit_verdicts.py`）把 8 个切片并成同一张表后，
        那条断言的到期条件就到了——它的告警语原文即「裁定在 WP2，本表只是候选」（旧断言语）。
        改动只收紧不放松：空值、未知取值、漏填仍会被这行抓住；WP2 侧的逐行复算与
        REBIND 目标/证据核验在 `tools/tests/test_kb_content_audit_verdicts.py`，
        口径与计数见 `docs/kb-stage4-report-2026-09-25.md`。
        """
        path = A.tables.TABLES_DIR / A.TABLE_NAME
        self.assertTrue(path.exists(),
                        "审计表不在位：%s（重跑 `PYTHONPATH=tools python -m "
                        "kb_build.merge_content_audit_verdicts --write`）" % path)
        with path.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
        self.assertTrue(rows, "审计表不该是空表")
        self.assertEqual(list(A.COLUMNS), list(rows[0].keys()))
        self.assertEqual(["KEEP", "NONE", "REBIND"], sorted({row["verdict"] for row in rows}),
                         "verdict 列必须是 WP2 的裁定（KEEP/REBIND/NONE），不许留空或未知取值")

    def test_clean_material_is_not_reported(self):
        """负向对照：正绑材料出现在候选里，说明判据在乱报。"""
        flagged = [row for row in self.rows if row["material_slug"] == CLEAN_MATERIAL]
        self.assertEqual([], flagged, f"正绑材料被报成候选：{flagged[:1]}")

    def test_audit_tables_are_not_in_pack_contract(self):
        """审计表不进五表一致性——它是审计日志、不是包的五表（进了会堵 promote）。"""
        consistency, _stats = check_pack_contract.evaluate()
        self.assertEqual([], [name for name in consistency if "content_audit" in name],
                         "content_audit_*.csv 不许进 check_table_consistency")


class TempStagingTest(unittest.TestCase):
    """② 临时 staging 上人为改绑 → 必须报出（两个方向）。"""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="kb-audit-test-", dir=pack_io.REPO / "build"))
        pack_io.seed_staging(self.tmp)
        pack_io.use_directory(self.tmp)

    def tearDown(self):
        pack_io.reset_directory()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _rebind(self, material_slug: str, node_slug: str) -> None:
        for path in pack_io.sidecar_paths():
            doc = pack_io.load_json(path)
            for material in doc["materials"]:
                if material["slug"] == material_slug:
                    material["bindings"] = [{
                        "knowledgeNodeId": "kb:test:%s:atomic:%s"
                        % (material["subject"].lower(), node_slug), "role": "PRIMARY"}]
                    pack_io.dump_json(doc, path)
                    return
        self.fail(f"夹具里没有材料 {material_slug}")

    def test_material_moved_to_unrelated_node_is_reported(self):
        """把一条讲 tRNA 的材料改绑到「细胞壁」——标题与细胞壁零关系，必须报出。"""
        slug = "bio-kb-trna-anticodon-structure-function"
        self._rebind(slug, "细胞壁")
        facts = A.load_facts()
        scope = A.resolve_scope(facts, [], ["细胞壁"], "BIOLOGY")
        rows = [row for row in A.verdict_rows(facts, scope) if row["material_slug"] == slug]
        self.assertTrue(rows, "改绑到无关节点后必须报出（临时 staging 上仍要看得见）")
        self.assertEqual("细胞壁", rows[0]["current_node_slug"], "报的行要指出现绑节点")
        # C1 或 C1'（标题与节点名零交集；C1' 表示只在别名里挂上）——两条都属于「本节点失联」
        self.assertTrue(rows[0]["evidence"].startswith(("own-title-detached",
                                                        "own-title-alias-only")),
                        rows[0]["evidence"])

    def test_material_bound_outside_scope_is_pulled_back(self):
        """靶区节点的材料挂在别的章 → C3 必须把它拉回来（只审靶区内部的预筛看不见这类）。"""
        slug = "phys-hj3-parallelogram-rule-exp"
        facts = A.load_facts()
        target = "探究互成角度力的合成规律实验"
        outside = next(key[1] for key in sorted(facts.point)
                       if key[0] == "PHYSICS" and facts.topic[key]["slug"] != A.CHAPTER_PHYSICS)
        self._rebind(slug, outside)
        facts = A.load_facts()
        scope = A.resolve_scope(facts, [], [target], "PHYSICS")
        rows = [row for row in A.verdict_rows(facts, scope) if row["material_slug"] == slug]
        self.assertTrue(rows, "标题把靶区节点名讲全了的材料挂在章外，C3 必须报出来")
        self.assertTrue(rows[0]["evidence"].startswith("foreign-title-match"), rows[0]["evidence"])
        self.assertEqual(target, rows[0]["suggested_node_slug"])
        self.assertEqual(outside, rows[0]["current_node_slug"])

    def test_unmodified_temp_staging_matches_the_real_audit(self):
        """临时 staging 没有改绑时，同一靶区的候选与当前包一致（口径不受目录影响）。"""
        facts = A.load_facts()
        scope = A.resolve_scope(facts, [], ["滑动摩擦力"], "PHYSICS")
        rows = A.verdict_rows(facts, scope)
        clean = [row for row in rows if row["material_slug"] == CLEAN_MATERIAL]
        self.assertEqual([], clean)


if __name__ == "__main__":
    unittest.main()
