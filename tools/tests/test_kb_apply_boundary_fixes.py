# -*- coding: utf-8 -*-
"""W7 执行器的用例：把"补好的边界能不能进包"变成可复核的门。

要害：① 前缀必须逐字保留（修的人只许往后补，不许改写前文）；② 补出来的尾巴必须能在
**该知识点已绑定材料**里找到（防凭印象补公式——这正是这批截断的成因）；
③ 定界符类只许动 `$`（去掉 `$` 后正文逐字相同）；④ 校验不过整批拒绝、不写表；⑤ 幂等。
"""

from __future__ import annotations

import csv
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_build import apply_boundary_fixes as ap  # noqa: E402

CUR = "定位：数学必修第一册 第三章。翻折别记反：$f(|x|)$ 只改变原来在 $y$ 轴左侧的部分（沿 $y$ 轴翻到右侧）；"
CUR_DELIM = "定位：CHEMISTRY 综合。换算成绝对质量（失=m_起始\\times$ 残留率），只拿比值算容易算错。"


def _pack(boundary: str) -> dict:
    return {"subjects": [{"subject": "MATH", "topics": [
        {"knowledgePoints": [{"slug": "函数图象的翻折变换", "name": "翻折变换", "boundary": boundary}]}]}]}


class ValidateTest(unittest.TestCase):
    def _run(self, rows, pack, materials):
        return ap.validate(rows, pack, materials)

    def test_completion_with_evidence_passes(self):
        tail = "$f(|x|)$ 只改变原来在 $y$ 轴右侧的部分（沿 $y$ 轴翻到左侧）。"
        new = CUR + tail
        rows = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "补全尾（有材料证据）",
                 "boundary": new, "evidence": "材料 ext-mat-1：$f(|x|)$ 沿 y 轴翻折", "_file": "t.csv"}]
        mats = {"函数图象的翻折变换": [{"contentMarkdown": "把 " + tail + " 记牢。"}]}
        fixed, problems = self._run(rows, _pack(CUR), mats)
        self.assertEqual([], problems)
        self.assertEqual(1, len(fixed))

    def test_prefix_must_be_kept_verbatim(self):
        rows = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "补全尾（有材料证据）",
                 "boundary": CUR.replace("左侧", "右边") + "$x$。", "evidence": "x", "_file": "t.csv"}]
        mats = {"函数图象的翻折变换": [{"contentMarkdown": "$x$。"}]}
        _fixed, problems = self._run(rows, _pack(CUR), mats)
        self.assertTrue(any("逐字保留前缀" in p for p in problems), problems)

    def test_tail_must_be_found_in_bound_materials(self):
        rows = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "补全尾（有材料证据）",
                 "boundary": CUR + "$f(|x|)$ 翻到左侧（我猜的）。", "evidence": "猜", "_file": "t.csv"}]
        mats = {"函数图象的翻折变换": [{"contentMarkdown": "材料里写的是别的话。"}]}
        _fixed, problems = self._run(rows, _pack(CUR), mats)
        self.assertTrue(any("材料里找不到" in p for p in problems), problems)

    def test_no_materials_means_trim_only(self):
        rows = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "收尾（无材料证据）",
                 "boundary": CUR.rstrip() + "这一句是我加的。", "evidence": "无", "_file": "t.csv"}]
        _fixed, problems = self._run(rows, _pack(CUR), {})
        self.assertTrue(any("无绑定材料却补了内容" in p for p in problems), problems)
        # 只把没写完的半句去掉（等于前缀）是允许的
        ok = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "收尾（无材料证据）",
               "boundary": CUR.rstrip(), "evidence": "", "_file": "t.csv"}]
        fixed, problems = self._run(ok, _pack(CUR), {})
        self.assertEqual([], problems)
        self.assertEqual(1, len(fixed))

    def test_delimiter_row_may_only_touch_dollars(self):
        good = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "修定界符（正文完整，$ 错位）",
                 "boundary": "定位：CHEMISTRY 综合。换算成绝对质量（失=$m_起始\\times$ 残留率），只拿比值算容易算错。",
                 "evidence": "", "_file": "t.csv"}]
        bad = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "修定界符（正文完整，$ 错位）",
                "boundary": "定位：CHEMISTRY 综合。换算成绝对质量（失=$m_起始\\times$ 残留率），比值算容易算错。",
                "evidence": "", "_file": "t.csv"}]
        pack = _pack(CUR_DELIM)
        fixed, problems = self._run(good, pack, {})
        self.assertEqual([], problems, problems)
        self.assertEqual(1, len(fixed))
        _fixed, problems = self._run(bad, pack, {})
        self.assertTrue(any("动了正文" in p for p in problems), problems)

    def test_missing_evidence_rejected_for_completion(self):
        rows = [{"subject": "MATH", "slug": "函数图象的翻折变换", "action": "补全尾（有材料证据）",
                 "boundary": CUR + "$x$。", "evidence": "  ", "_file": "t.csv"}]
        _fixed, problems = self._run(rows, _pack(CUR), {"函数图象的翻折变换": [{"contentMarkdown": "$x$。"}]})
        self.assertTrue(any("缺 evidence" in p for p in problems), problems)

    def test_unknown_node_and_empty_rejected(self):
        rows = [{"subject": "MATH", "slug": "不存在", "action": "补全尾（有材料证据）",
                 "boundary": "x", "evidence": "y", "_file": "t.csv"},
                {"subject": "MATH", "slug": "函数图象的翻折变换", "action": "补全尾（有材料证据）",
                 "boundary": "  ", "evidence": "y", "_file": "t.csv"}]
        _fixed, problems = self._run(rows, _pack(CUR), {})
        self.assertTrue(any("知识点不存在" in p for p in problems), problems)
        self.assertTrue(any("为空" in p for p in problems), problems)


class MainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="w7-"))
        self.map_path = self.tmp / "boundary_map.csv"
        for name, val in (("TABLES", self.tmp), ("BOUNDARY_MAP", self.map_path)):
            p = mock.patch.object(ap, name, val)
            p.start()
            self.addCleanup(p.stop)
        tail = "$f(|x|)$ 只改变原来在 $y$ 轴右侧的部分。"
        self.mats = {"函数图象的翻折变换": [{"contentMarkdown": tail}]}
        p = mock.patch.object(ap.mk, "load_pack", lambda: _pack(CUR))
        p.start()
        self.addCleanup(p.stop)
        p = mock.patch.object(ap.mk, "load_materials", lambda: self.mats)
        p.start()
        self.addCleanup(p.stop)
        self.tail = tail

    def _write_fix(self, rows):
        with (self.tmp / "boundary_fixes_01.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=["subject", "slug", "action", "boundary", "evidence"],
                               lineterminator="\n")
            w.writeheader()
            w.writerows(rows)

    def _run(self, argv=("--write",)):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = ap.main(list(argv))
        return rc, buf.getvalue()

    def test_write_then_idempotent_and_skip_after_applied(self):
        self._write_fix([{"subject": "MATH", "slug": "函数图象的翻折变换",
                          "action": "补全尾（有材料证据）", "boundary": CUR + self.tail,
                          "evidence": "材料 ext-mat-1"}])
        rc, out = self._run()
        self.assertEqual(0, rc, out)
        first = self.map_path.read_bytes()
        rows = list(csv.DictReader(io.StringIO(first.decode("utf-8"))))
        self.assertEqual([{"subject": "MATH", "slug": "函数图象的翻折变换",
                           "boundary": CUR + self.tail}], [dict(r) for r in rows])
        rc, _ = self._run()
        self.assertEqual(0, rc)
        self.assertEqual(first, self.map_path.read_bytes(), "重跑必须逐字节相同")

    def test_no_table_yet_is_a_report_not_an_error(self):
        rc, out = self._run(())
        self.assertEqual(0, rc)
        self.assertIn("没有裁定表", out)
        self.assertFalse(self.map_path.exists())

    def test_one_bad_row_rejects_whole_batch_without_writing(self):
        self._write_fix([
            {"subject": "MATH", "slug": "函数图象的翻折变换", "action": "补全尾（有材料证据）",
             "boundary": CUR + self.tail, "evidence": "材料 ext-mat-1"},
            {"subject": "MATH", "slug": "函数图象的翻折变换", "action": "补全尾（有材料证据）",
             "boundary": CUR + "编的尾巴。", "evidence": "猜"},
        ])
        rc, out = self._run()
        self.assertEqual(1, rc)
        self.assertIn("整批拒绝", out)
        self.assertFalse(self.map_path.exists())


if __name__ == "__main__":
    unittest.main()
