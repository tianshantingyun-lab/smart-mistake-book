# -*- coding: utf-8 -*-
"""P0 切块工具的用例：几何判据是可单测的那部分（stdlib，不依赖 numpy/fitz）。

要钉的两侧：
- **别少判栏**：真两栏页必须切两栏，且读序是"先左栏自上而下、再右栏"（丢列序实测翻车过）；
- **别多判栏**：半页空白、单栏页不得被劈成两栏（会把一行文字切成两半，比不切更坏）。
切条侧钉：不切穿文字行、相邻条重叠 ≥ 10%、覆盖连续（不留缝、末条吃到页尾）。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import tile_page as tp  # noqa: E402


def _rows(blocks: list[tuple[int, int, float]], n: int) -> list[float]:
    """按 (起, 止, 墨迹) 造行投影；块之间是 0。"""
    out = [0.0] * n
    for a, b, v in blocks:
        for i in range(a, min(b, n)):
            out[i] = v
    return out


class TrimTest(unittest.TestCase):
    def test_trims_blank_margins_with_padding(self):
        rows = _rows([(100, 200, 0.05)], 400)
        self.assertEqual((100 - tp.PAD_PX, 200 + tp.PAD_PX), tp.trim_span(rows))

    def test_blank_page_returns_all(self):
        self.assertEqual((0, 300), tp.trim_span([0.0] * 300))


class ColumnsTest(unittest.TestCase):
    def test_two_columns_detected(self):
        # 左 0–600 有墨、右 800–1400 有墨、中间 200px 空白
        cols = [0.03] * 600 + [0.0] * 200 + [0.03] * 600
        self.assertEqual([(0, 600), (800, 1400)], tp.find_columns(cols, 1400))

    def test_single_column_when_gap_too_narrow(self):
        cols = [0.03] * 690 + [0.0] * 20 + [0.03] * 690      # 谷只有 20px
        self.assertEqual([(0, 1400)], tp.find_columns(cols, 1400))

    def test_half_blank_page_is_not_two_columns(self):
        # 右半页全空 → 不能判两栏（这是半页空白，不是双栏版式）
        cols = [0.03] * 600 + [0.0] * 800
        self.assertEqual([(0, 1400)], tp.find_columns(cols, 1400))

    def test_gap_outside_centre_ignored(self):
        # 谷在最左边 10%（页边空白），不在中部 → 不判两栏
        cols = [0.0] * 100 + [0.03] * 1300
        self.assertEqual([(0, 1400)], tp.find_columns(cols, 1400))

    def test_dirty_gutter_not_a_gutter(self):
        # 谷里有零星墨迹（超过了谷内上限）→ 不判两栏
        cols = [0.03] * 600 + [0.01] * 200 + [0.03] * 600
        self.assertEqual([(0, 1400)], tp.find_columns(cols, 1400))


class BandsTest(unittest.TestCase):
    def test_short_page_is_one_band(self):
        self.assertEqual([(0, 300)], tp.find_bands([0.05] * 300, 0, 300))

    def test_cuts_avoid_text_lines(self):
        # 文字行 0–380 / 400–780 / 800–1180，行间 20px 空白
        rows = _rows([(0, 380, 0.05), (400, 780, 0.05), (800, 1180, 0.05)], 1200)
        bands = tp.find_bands(rows, 0, 1200, min_band=380, max_band=1000, overlap=0.10)
        cuts = [b for _a, b in bands[:-1]]
        for c in cuts:
            self.assertLessEqual(rows[min(c, len(rows) - 1)], 0.0, f"切点 {c} 落在文字行上")

    def test_overlap_at_least_ten_percent(self):
        rows = [0.05] * 3000
        bands = tp.find_bands(rows, 0, 3000, min_band=380, max_band=1000, overlap=0.10)
        for (a0, a1), (b0, b1) in zip(bands, bands[1:]):
            self.assertLessEqual(b0, a1, "相邻条必须重叠")
            self.assertGreaterEqual(a1 - b0, int((a1 - a0) * 0.09), "重叠不足 10%")

    def test_covers_the_whole_span(self):
        rows = [0.05] * 2400
        bands = tp.find_bands(rows, 0, 2400)
        self.assertEqual(0, bands[0][0])
        self.assertEqual(2400, bands[-1][1])
        for (_a0, a1), (b0, _b1) in zip(bands, bands[1:]):
            self.assertLessEqual(b0, a1, "条与条之间不许留缝")

    def test_terminates_with_clear_rows(self):
        rows = _rows([(i, i + 5, 0.05) for i in range(0, 5000, 10)], 5000)
        bands = tp.find_bands(rows, 0, 5000)
        self.assertLess(len(bands), 20, bands)

    def test_short_tail_is_merged_not_a_sliver(self):
        # 实测形态：CHEMISTRY p2 曾切出 61px、BIOLOGY p1 曾切出 83px 的尾条——
        # 尾条高度不足半条时必须并进上一条，不单独成条
        rows = _rows([(0, 400, 0.05), (420, 1500, 0.05), (1520, 1620, 0.05)], 1700)
        bands = tp.find_bands(rows, 0, 1700, min_band=380, max_band=1000, overlap=0.10)
        heights = [b1 - a0 for a0, b1 in bands]
        self.assertTrue(all(h >= 380 * 0.5 for h in heights), f"出现尾条碎块：{bands}")
        self.assertEqual(1700, bands[-1][1])


class PlanTest(unittest.TestCase):
    def test_order_is_column_major(self):
        cols = [0.03] * 600 + [0.0] * 200 + [0.03] * 600
        rows = [0.05] * 2000
        plan = tp.plan_tiles(rows, cols, 2000, 1400)
        self.assertEqual(2, len(plan["columns"]))
        seen = [(t["col"], t["y0"]) for t in plan["tiles"]]
        left = [x for x in seen if x[0] == 0]
        right = [x for x in seen if x[0] == 1]
        self.assertEqual(sorted(left), left, "左栏必须自上而下连续排在前")
        self.assertEqual(sorted(right), right)
        self.assertEqual(seen, left + right, "读序必须是先左栏读完再右栏")
        self.assertEqual([t["order"] for t in plan["tiles"]], list(range(len(plan["tiles"]))))
        self.assertIn("两栏", plan["read_hint"])

    def test_tile_files_are_ordered_by_name(self):
        rows = [0.05] * 2000
        plan = tp.plan_tiles(rows, [0.03] * 1400, 2000, 1400)
        files = [t["file"] for t in plan["tiles"]]
        self.assertEqual(sorted(files), files, "文件名排序即读序，免代理读错顺序")
        self.assertIn("单栏", plan["read_hint"])



class StaleTileTest(unittest.TestCase):
    def test_stale_tiles_are_listed_for_removal(self):
        # 实测形态：CHEMISTRY p0002 先按 10 条计划切过，重切成 8 条后 tile_08/09 留在盘上，
        # 代理用 glob 取块时会把它们当页内容再转写一遍（重复内容入库）
        import tempfile
        d = Path(tempfile.mkdtemp(prefix="tiles-"))
        for i in range(10):
            (d / f"tile_{i:02d}.jpg").write_bytes(b"x")
        plan = {"tiles": [{"file": f"tile_{i:02d}.jpg"} for i in range(8)]}
        got = [p.name for p in tp.stale_tiles(d, plan)]
        self.assertEqual(["tile_08.jpg", "tile_09.jpg"], got)

    def test_nothing_stale_when_plan_matches_disk(self):
        import tempfile
        d = Path(tempfile.mkdtemp(prefix="tiles-"))
        for i in range(3):
            (d / f"tile_{i:02d}.jpg").write_bytes(b"x")
        plan = {"tiles": [{"file": f"tile_{i:02d}.jpg"} for i in range(3)]}
        self.assertEqual([], tp.stale_tiles(d, plan))
        self.assertEqual([], tp.stale_tiles(d / "missing", plan))

if __name__ == "__main__":
    unittest.main()
