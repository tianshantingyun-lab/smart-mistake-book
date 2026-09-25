# -*- coding: utf-8 -*-
"""WP2 裁定汇总表的验收测试：表可从切片逐行复算；REBIND 每行都有目标 + 证据原句。

映射的验收条件（Stage-4 裁定汇总）：
1. `tools/kb_build/tables/content_audit_2026-09-25.csv` = 8 个 `slice-NN.verdicts.csv`
   去重排序后的并（逐行原样），表头与 `COLUMNS` 逐字相同；
2. 每行 `verdict ∈ {KEEP, REBIND, NONE}`，无空值、无非法值；
3. 每条 REBIND 都有 `suggested_node_slug`（且能在当前包的节点里解析到）与带引文的 `evidence`；
4. 靶子样本与随机抽样样本靠 `slice` 列可分（slice id ∈ SLICE_PLAN，kind 可推出样本种类）。

引文核验（`verbatim_gap_rows`）只查**引文出处**（引文是不是该材料正文的逐字片段），
**不用来判绑定对错**——绑定对错全部来自 WP2 逐条读内容的语义裁定（登记册 I-05：
归属轴的字符串度量无效，宽松 98.1% / 严格 16.4% 两个数都不可信）。
"""

from __future__ import annotations

import csv
import unittest

from kb_build.audit_content_bindings import COLUMNS, SLICE_PLAN, load_facts
from kb_build.merge_content_audit_verdicts import (merge, read_slices, table_path,
                                                   verbatim_gap_rows)

# 交付时冻结的口径（写在 docs/kb-stage4-report-2026-09-25.md：样本、计数、复核命令）
TOTAL = 409
COUNTS = {"KEEP": 330, "REBIND": 56, "NONE": 23}
RANDOM_SLICE = "slice-06"          # kind="sample"：四科各 10 条的分层随机抽样
RANDOM_ROWS = 40
# 唯一一条引文对不上材料正文的 REBIND（slice-02 / phys-hj2-li-fenjie-duojie-taolun：
# 引文两端逐字，中间删了一处括注「，$0<θ<90°$」且没标省略号）——钉住它，
# 只能少不能多：多一条就说明别的切片也开始丢原句了。
KNOWN_EVIDENCE_GAP = ("slice-02", "phys-hj2-li-fenjie-duojie-taolun")


class ShippedTableTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows, cls.files = read_slices()
        cls.merged = merge(cls.rows)
        with table_path().open(encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            cls.shipped_header = list(reader.fieldnames or ())
            cls.shipped = [{column: (row.get(column) or "") for column in COLUMNS}
                           for row in reader]

    def test_slice_files_are_all_present(self):
        self.assertEqual(["slice-%02d" % index for index in range(1, 9)],
                         [path.name.split(".")[0] for path in self.files],
                         "八个切片裁定文件要都在位")

    def test_shipped_header_is_verbatim(self):
        self.assertEqual(list(COLUMNS), self.shipped_header,
                         "表头必须与切片文件/COLUMNS 逐字相同（不增列不减列）")

    def test_shipped_table_equals_recomputation(self):
        self.assertEqual(self.merged, self.shipped,
                         "落盘表 != 8 个切片去重排序后的并（用 --write 重生成；表是唯一写者的产物）")

    def test_order_is_subject_then_slug(self):
        keys = [(row["subject"], row["slug"]) for row in self.shipped]
        self.assertEqual(sorted(keys), keys, "必须按 subject/slug 排序")

    def test_every_row_has_a_legal_verdict(self):
        self.assertEqual(COUNTS, {v: sum(1 for r in self.shipped if r["verdict"] == v)
                                  for v in COUNTS}, "计数变了：先改报告口径，再改这里")
        self.assertEqual(TOTAL, len(self.shipped))
        for row in self.shipped:
            self.assertIn(row["verdict"], COUNTS, "非法 verdict：%s" % row)

    def test_samples_are_distinguishable_by_slice_column(self):
        kinds = {spec["slice"]: spec["kind"] for spec in SLICE_PLAN}
        for row in self.shipped:
            self.assertIn(row["slice"], kinds, "slice 列有切片计划外的值：%s" % row["slice"])
        random_rows = [r for r in self.shipped if kinds[r["slice"]] == "sample"]
        self.assertEqual({RANDOM_SLICE}, {r["slice"] for r in random_rows},
                         "随机分层样本只该由 kind=sample 的那一个切片贡献")
        self.assertEqual(RANDOM_ROWS, len(random_rows),
                         "随机分层样本的行数变了：报告里的样本口径要同步")

    def test_rebind_rows_carry_target_and_evidence(self):
        facts = load_facts()
        slugs = {key[1] for key in facts.point}
        names = {node.get("name") for node in facts.point.values()}
        rebinds = [row for row in self.shipped if row["verdict"] == "REBIND"]
        self.assertEqual(COUNTS["REBIND"], len(rebinds))
        for row in rebinds:
            target = row["suggested_node_slug"].strip()
            self.assertTrue(target, "REBIND 缺 suggested_node_slug：%s" % row["material_slug"])
            self.assertIn(target, slugs | names,
                          "REBIND 的目标在包里解析不到：%s -> %s" % (row["material_slug"], target))
            self.assertNotEqual(target, row["current_node_slug"].strip(),
                                "REBIND 的目标 = 现绑节点（空改绑）：%s" % row["material_slug"])
            self.assertTrue(row["evidence"].strip(), "空证据：%s" % row["material_slug"])

    def test_rebind_evidence_is_grounded_in_the_material_text(self):
        """证据原句：引文要在材料正文里找得到；对不上的行数只能少不能多。"""
        gaps = verbatim_gap_rows(self.shipped)
        self.assertIn(KNOWN_EVIDENCE_GAP, gaps,
                      "已登记的那条（slice-02 转述式引文）不见了——是修好了就删掉这条已知项")
        self.assertLessEqual(len(gaps), 1, "新的 REBIND 丢了材料原句：%s" % (gaps,))


if __name__ == "__main__":
    unittest.main()
