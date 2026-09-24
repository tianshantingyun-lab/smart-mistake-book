# -*- coding: utf-8 -*-
"""逐页并入的落盘器用例。

要害：① 同一页只留一份（重转就是替换，不许两份并存）；② 该页落在既有区间就并进去，
落在外面就新建"只含这一页"的文件——**不许**让账本把它误判成区间末尾被截断；
③ 坏产物要报错不静默（丢页 = 账本看不见的缺失）；④ 幂等。
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from kb_coverage import merge_page_transcripts as mp  # noqa: E402


def _rec(page: int, text: str = "内容够长的一段转写文字。") -> str:
    return json.dumps({"page": page, "heading": "h", "text": text}, ensure_ascii=False)


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="merge-"))
        self.book = self.tmp / "book"
        self.book.mkdir(parents=True)
        (self.book / "range_0015_0028.jsonl").write_text(
            "\n".join([_rec(15), _rec(16)]) + "\n", encoding="utf-8")

    def test_appends_into_existing_range_in_page_order(self):
        got = mp.merge(self.book, 17, [{"page": 17, "heading": "h", "text": "新转写的第 17 页内容。"}],
                       write=True)
        self.assertFalse(got["created"])
        pages = [json.loads(l)["page"] for l in
                 (self.book / "range_0015_0028.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
        self.assertEqual([15, 16, 17], pages)

    def test_replaces_same_page_and_keeps_one_copy(self):
        mp.merge(self.book, 15, [{"page": 15, "heading": "h", "text": "重转后的第 15 页。"}], write=True)
        lines = [json.loads(l) for l in
                 (self.book / "range_0015_0028.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
        self.assertEqual([15, 16], [r["page"] for r in lines])
        self.assertEqual("重转后的第 15 页。", lines[0]["text"])

    def test_page_outside_every_range_gets_its_own_file(self):
        got = mp.merge(self.book, 550, [{"page": 550, "heading": "h", "text": "第 550 页内容。"}], write=True)
        self.assertTrue(got["created"])
        self.assertEqual("range_0550_0550.jsonl", Path(got["file"]).name)
        self.assertEqual([550, 550], got["span"], "区间名必须就是这一页，账本才不会误判截断")

    def test_idempotent(self):
        rec = [{"page": 17, "heading": "h", "text": "新转写的第 17 页内容。"}]
        mp.merge(self.book, 17, rec, write=True)
        before = (self.book / "range_0015_0028.jsonl").read_bytes()
        got = mp.merge(self.book, 17, rec, write=True)
        self.assertFalse(got["changed"])
        self.assertEqual(before, (self.book / "range_0015_0028.jsonl").read_bytes())

    def test_wrong_page_in_payload_rejected(self):
        with self.assertRaises(ValueError):
            mp.merge(self.book, 17, [{"page": 18, "heading": "h", "text": "串页了。"}], write=True)

    def test_bad_json_and_empty_text_are_errors(self):
        bad = self.tmp / "p0017.jsonl"
        bad.write_text('{"page":17,"heading":"h","text":"ok 够长的一段。"}\n{"page":17,"text":""}\n',
                       encoding="utf-8")
        with self.assertRaises(ValueError):
            mp.parse_records(bad)
        bad.write_text('{"page":17,"heading":"h","text":\n', encoding="utf-8")
        with self.assertRaises(ValueError):
            mp.parse_records(bad)

    def test_trailing_comma_tolerated(self):
        ok = self.tmp / "p0017.jsonl"
        ok.write_text('{"page":17,"heading":"h","text":"够长的一段转写文字内容。"},\n', encoding="utf-8")
        self.assertEqual(1, len(mp.parse_records(ok)))


class MainTest(unittest.TestCase):
    def test_no_source_dir_reports(self):
        rc = mp.main(["--from", str(Path(tempfile.mkdtemp()) / "nope")])
        self.assertEqual(2, rc)

    def test_runs_end_to_end_on_a_synthetic_tree(self):
        tmp = Path(tempfile.mkdtemp(prefix="merge-main-"))
        src = tmp / "fill/MATH"
        src.mkdir(parents=True)
        (src / "p0003.jsonl").write_text(_rec(3, "第三页的转写内容，长度足够通过校验。"), encoding="utf-8")
        book = tmp / "transcripts/MATH/某书"
        book.mkdir(parents=True)
        (book / "range_0001_0014.jsonl").write_text(_rec(1) + "\n", encoding="utf-8")
        with mock.patch.object(mp, "TRANSCRIPTS", tmp / "transcripts"), \
             mock.patch.object(mp, "MANIFEST", tmp / "no-manifest.json"):
            rc = mp.main(["--from", str(tmp / "fill"), "--write"])
        self.assertEqual(0, rc)
        pages = [json.loads(l)["page"] for l in
                 (book / "range_0001_0014.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
        self.assertEqual([1, 3], pages)


if __name__ == "__main__":
    unittest.main()
