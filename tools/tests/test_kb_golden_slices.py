# -*- coding: utf-8 -*-
"""R3 金标集：切片工具与机械验证器的契约测试。

它消灭的失败：
1. **切片不幂等**——若重跑 build/golden/slice-NN.json 逐字节不稳定，D12 的
   "一次性冻结"就没有锚点（冻结产物 golden_queries_v1.json 的 sha256
   只有在输入切片确定时才可比对）。
2. **验证器放坏行**——金标集是检索门禁（主集≥0.90/逐章≥0.80）的基准线，
   任何坏行（query 越界/无汉字、expectedSlug 跨章或不存在、(query,slug)
   重复、孤立/缺失作答文件、低于 target×0.8）混进去都会污染判据。
   验证器必须逐条以 file:line 拒收，且失败时**不写**冻结产物。
"""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from kb_coverage import make_golden_slices as mgs
from kb_coverage import validate_golden as vg

REPO = Path(__file__).resolve().parents[2]
PACK = (REPO / "core" / "data" / "src" / "main" / "resources"
        / "knowledge" / "moe-2025-four-subjects-v1.json")

# 现行成品包下选章规则钉死的 10 个 (subject, chapter)。
# 规则（见 make_golden_slices 模块 docstring）或包结构变化时本断言变红，强制复核。
PINNED_SELECTION = {
    ("MATH", "数学必修第一册·第三章·函数的概念与性质"),
    ("MATH", "数学选择性必修第二册·第四章·数列"),
    ("MATH", "数学选择性必修第一册·第二三章·解析几何"),
    ("PHYSICS", "物理必修第三册·第十一十二章·恒定电流"),
    ("PHYSICS", "物理必修第一册·第一章·运动的描述"),
    ("PHYSICS", "物理必修第一册·第三章·相互作用"),
    ("CHEMISTRY", "化学必修第二册·第六章·化学反应与能量"),
    ("CHEMISTRY", "化学必修第一册·第三章·铁与金属材料"),
    ("CHEMISTRY", "化学必修第二册·第五章·硫氮及其化合物"),
    ("BIOLOGY", "生物学必修2·第一章第二节·遗传的基本规律"),
}


def _line(query: str, slug: str, subject: str, chapter: str,
          extra: dict | None = None) -> str:
    row = {"query": query, "expectedSlug": slug, "subject": subject,
           "chapter": chapter}
    if extra:
        row.update(extra)
    return json.dumps(row, ensure_ascii=False)


def _write_valid_answers(golden_dir: Path) -> list[dict]:
    """每片取前 target 个节点写合法作答；返回 10 份切片文档。"""
    slices = [
        json.loads((golden_dir / f"slice-{i:02d}.json").read_text(encoding="utf-8"))
        for i in range(1, 11)
    ]
    for s in slices:
        lines = [_line(f"什么是{n['name']}", n["slug"], s["subject"], s["chapter"])
                 for n in s["nodes"][: s["target"]]]
        (golden_dir / s["outFile"]).write_text("\n".join(lines) + "\n",
                                               encoding="utf-8")
    return slices


def _run_validator(golden_dir: Path, out: Path) -> tuple[int, str]:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = vg.main(["--golden-dir", str(golden_dir), "--out", str(out)])
    return code, buffer.getvalue()


class SlicerIdempotencyTest(unittest.TestCase):
    def test_rerun_is_byte_identical_and_selection_pinned(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            d1, d2 = root / "a", root / "b"
            self.assertEqual(mgs.main(["--pack", str(PACK), "--out-dir", str(d1)]), 0)
            self.assertEqual(mgs.main(["--pack", str(PACK), "--out-dir", str(d2)]), 0)
            names = sorted(p.name for p in d1.iterdir())
            self.assertEqual(
                names,
                ["manifest.json"] + [f"slice-{i:02d}.json" for i in range(1, 11)],
                "切片目录应恰好 10 片 + manifest")
            for name in names:
                self.assertEqual((d1 / name).read_bytes(),
                                 (d2 / name).read_bytes(),
                                 f"重跑字节不稳定：{name}")
            slices = [json.loads((d1 / n).read_text(encoding="utf-8"))
                      for n in names if n.startswith("slice-")]
            self.assertEqual({(s["subject"], s["chapter"]) for s in slices},
                             PINNED_SELECTION, "选章结果漂移")
            self.assertEqual({s["subject"] for s in slices},
                             {"MATH", "PHYSICS", "CHEMISTRY", "BIOLOGY"},
                             "四科覆盖")
            for s in slices:
                self.assertEqual(s["target"], 9, s["chapter"])
                self.assertGreaterEqual(s["nodeCount"], 9, s["chapter"])
                self.assertEqual(s["nodeCount"], len(s["nodes"]), s["chapter"])
            self.assertEqual(sum(s["target"] for s in slices), 90,
                             "总量须落 D12 的 80-100 区间")


class ValidatorRejectsBadLinesTest(unittest.TestCase):
    def _golden_with_answers(self, root: Path) -> Path:
        golden = root / "golden"
        self.assertEqual(
            mgs.main(["--pack", str(PACK), "--out-dir", str(golden)]), 0)
        _write_valid_answers(golden)
        return golden

    def _assert_rejected(self, mutate, *fragments: str):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            golden = self._golden_with_answers(root)
            out = root / "frozen" / "golden_queries_v1.json"
            mutate(golden)
            code, text = _run_validator(golden, out)
            self.assertEqual(code, 1, f"应当拒收：{fragments}\n{text}")
            self.assertFalse(out.exists(), "失败时不得写冻结产物")
            self.assertFalse(out.with_name(out.name + ".sha256").exists())
            for fragment in fragments:
                self.assertIn(fragment, text)

    def test_valid_answers_pass_and_freeze_is_sorted_with_sha(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            golden = self._golden_with_answers(root)
            out = root / "frozen" / "golden_queries_v1.json"
            code, _ = _run_validator(golden, out)
            self.assertEqual(code, 0)
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(len(data), 90)
            keys = [(e["subject"], e["chapter"], e["query"], e["expectedSlug"])
                    for e in data]
            self.assertEqual(keys, sorted(keys), "冻结产物须按 subject/chapter/query 排序")
            self.assertEqual(
                out.with_name(out.name + ".sha256").read_text(encoding="utf-8").strip(),
                hashlib.sha256(out.read_bytes()).hexdigest(),
                ".sha256 文件须等于规范化 JSON 字节的 sha256")

    def test_per_line_bad_rows_rejected_with_file_line(self):
        def s3(g):
            return g / "slice-03.out.jsonl"

        # 切片文档取自合法作答目录里的基准
        with tempfile.TemporaryDirectory() as td:
            base = self._golden_with_answers(Path(td))
            s3doc = json.loads((base / "slice-03.json").read_text(encoding="utf-8"))
            s1doc = json.loads((base / "slice-01.json").read_text(encoding="utf-8"))
            cross_slug = s1doc["nodes"][10]["slug"]  # MATH 函数章节点 → 对数列章是跨章

            cases = [
                ("非法 JSON", "not-json {", "非法 JSON"),
                ("空行", "", "空行"),
                ("query 3 字",
                 _line("函数吗", s3doc["nodes"][0]["slug"], "MATH", s3doc["chapter"]),
                 "长度 3 不在 4-60 字区间"),
                ("query 61 字",
                 _line("长" * 61, s3doc["nodes"][0]["slug"], "MATH", s3doc["chapter"]),
                 "长度 61 不在 4-60 字区间"),
                ("query 无汉字",
                 _line("what is a function", s3doc["nodes"][0]["slug"],
                        "MATH", s3doc["chapter"]),
                 "不含汉字"),
                ("expectedSlug 跨章",
                 _line("什么是跨章节点", cross_slug, "MATH", s3doc["chapter"]),
                 "跨章作答"),
                ("expectedSlug 包内不存在",
                 _line("什么是幽灵节点", "幽灵节点ABC", "MATH", s3doc["chapter"]),
                 "在 MATH 包内不存在"),
                ("(query,slug) 重复",
                 _line("什么是" + s3doc["nodes"][0]["name"],
                        s3doc["nodes"][0]["slug"], "MATH", s3doc["chapter"]),
                 "重复"),
                ("多余字段",
                 _line("什么是多余字段", s3doc["nodes"][0]["slug"], "MATH",
                        s3doc["chapter"], extra={"note": "x"}),
                 "多 ['note']"),
                ("缺字段",
                 json.dumps({"query": "什么是缺字段", "subject": "MATH",
                             "chapter": s3doc["chapter"]}, ensure_ascii=False),
                 "缺 ['expectedSlug']"),
                ("subject 不符",
                 _line("什么是错科", s3doc["nodes"][0]["slug"], "PHYSICS",
                        s3doc["chapter"]),
                 "subject='PHYSICS' 与切片 'MATH' 不符"),
                ("chapter 不符",
                 _line("什么是错章", s3doc["nodes"][0]["slug"], "MATH", "另一章"),
                 "chapter='另一章' 与切片"),
                ("空值字段",
                 json.dumps({"query": "  ", "expectedSlug": s3doc["nodes"][0]["slug"],
                             "subject": "MATH", "chapter": s3doc["chapter"]},
                            ensure_ascii=False),
                 "存在空或非字符串字段"),
            ]
            for name, bad_line, fragment in cases:
                with self.subTest(case=name):
                    def mutate(g, line=bad_line):
                        p = s3(g)
                        p.write_text(p.read_text(encoding="utf-8") + line + "\n",
                                     encoding="utf-8")
                    self._assert_rejected(mutate, fragment)

    def test_file_level_failures_rejected(self):
        def make_orphan(g):
            (g / "slice-99.out.jsonl").write_text(
                _line("孤立文件", "x", "MATH", "y") + "\n", encoding="utf-8")

        self._assert_rejected(make_orphan, "孤立作答文件")

        def drop_out(g):
            (g / "slice-10.out.jsonl").unlink()

        self._assert_rejected(drop_out, "slice-10.out.jsonl: 缺失")

        def under_target(g):
            (g / "slice-10.out.jsonl").write_text(
                "\n".join((g / "slice-10.out.jsonl").read_text(encoding="utf-8")
                          .splitlines()[:7]) + "\n", encoding="utf-8")

        self._assert_rejected(under_target, "有效行 7 < target×0.8=7.2")

    def test_total_below_80_rejected_even_when_per_slice_ok(self):
        """总量门独立于逐片门：10 片各 7 行（≥8×0.8=6.4）但总量 70 < 80。"""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            golden = root / "golden"
            golden.mkdir()
            # 基准节点直接取自成品包 MATH 函数章前 7 个
            pack = json.loads(PACK.read_text(encoding="utf-8"))
            pts = next(t for s in pack["subjects"] if s["subject"] == "MATH"
                       for t in s["topics"]
                       if t["slug"] == "数学必修第一册·第三章·函数的概念与性质")
            nodes = pts["knowledgePoints"][:7]
            for k in range(1, 11):
                doc = {
                    "slice": k, "subject": "MATH", "chapter": pts["slug"],
                    "chapterName": pts["name"], "target": 8, "nodeCount": 7,
                    "outFile": f"slice-{k:02d}.out.jsonl",
                    "nodes": [{"slug": n["slug"], "name": n["name"],
                               "aliases": (n.get("aliases") or [])[:8],
                               "boundary": (n.get("boundary") or "")[:60]}
                              for n in nodes],
                }
                (golden / f"slice-{k:02d}.json").write_text(
                    json.dumps(doc, ensure_ascii=False), encoding="utf-8")
                (golden / f"slice-{k:02d}.out.jsonl").write_text(
                    "\n".join(_line(f"问题{k}之{c}什么是{n['name']}", n["slug"],
                                    "MATH", pts["slug"])
                              for c, n in enumerate(nodes)) + "\n",
                    encoding="utf-8")
            out = root / "frozen.json"
            code, text = _run_validator(golden, out)
            self.assertEqual(code, 1)
            self.assertIn("总量 70 < 80", text)
            self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
