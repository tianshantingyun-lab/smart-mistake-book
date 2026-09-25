# -*- coding: utf-8 -*-
"""文件名规范化的用例。

要害三条：① 同页两份只留一份、且名字统一成 `p####`；② 保留哪一份按**内容质量**定，不按名字；
③ 本来就规范的独苗**不许被重写**（实测踩过：一次 --write 把 712 个规范文件全部重写了一遍，
mtime 抖动且有中断风险）。
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import normalize_fill_names as nf  # noqa: E402


class NormalizeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="normfill-"))
        self.sub = self.tmp / "CHEMISTRY"
        self.sub.mkdir(parents=True)

    def _jsonl(self, name: str, text: str):
        (self.sub / name).write_text(
            json.dumps({"page": 216, "heading": "h", "text": text}, ensure_ascii=False),
            encoding="utf-8")

    def test_keeps_the_longer_text_under_the_canonical_name(self):
        self._jsonl("p0216.jsonl", "短")
        self._jsonl("p216.jsonl", "这一份正文长得多，按内容质量应当胜出。")
        rc = nf.main(["--dir", str(self.tmp), "--write"])
        self.assertEqual(0, rc)
        names = sorted(f.name for f in self.sub.glob("p*"))
        self.assertEqual(["p0216.jsonl"], names, "同页只留规范名一份")
        kept = json.loads((self.sub / "p0216.jsonl").read_text(encoding="utf-8"))
        self.assertIn("长得多", kept["text"], "留下的应是质量更好的那份内容")

    def test_singleton_non_canonical_is_renamed(self):
        self._jsonl("p216.jsonl", "只有一份，但名字不规范。")
        nf.main(["--dir", str(self.tmp), "--write"])
        self.assertEqual(["p0216.jsonl"], sorted(f.name for f in self.sub.glob("p*")))

    def test_canonical_singleton_is_not_rewritten(self):
        self._jsonl("p0216.jsonl", "本来就规范，不该被碰。")
        before = (self.sub / "p0216.jsonl").stat().st_mtime_ns
        nf.main(["--dir", str(self.tmp), "--write"])
        self.assertEqual(before, (self.sub / "p0216.jsonl").stat().st_mtime_ns,
                         "规范独苗不许被重写")

    def test_counts_file_with_bad_json_loses_to_the_good_one(self):
        (self.sub / "p0216.counts.json").write_text('{"items_min": 5}', encoding="utf-8")
        (self.sub / "p216.counts.json").write_text("{坏的 json", encoding="utf-8")
        nf.main(["--dir", str(self.tmp), "--write"])
        left = json.loads((self.sub / "p0216.counts.json").read_text(encoding="utf-8"))
        self.assertEqual(5, left["items_min"])


if __name__ == "__main__":
    unittest.main()
