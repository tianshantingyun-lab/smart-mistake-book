# -*- coding: utf-8 -*-
"""extraction_state：提取状态机的迁移守卫与账本完整性。"""

from __future__ import annotations

import csv
import unittest

from kb_coverage import extraction_state as es


class InitialStateTest(unittest.TestCase):
    def test_office_doc_is_pending(self):
        state, _ = es._initial_state({"content_bearing": "yes", "ext": ".docx",
                                      "text_layer": ""})
        self.assertEqual("PENDING", state)

    def test_scanned_pdf_is_pending_scanned(self):
        state, _ = es._initial_state({"content_bearing": "yes", "ext": ".pdf",
                                      "text_layer": "SCANNED_IMAGE"})
        self.assertEqual("PENDING_SCANNED", state)

    def test_binary_is_skipped(self):
        state, _ = es._initial_state({"content_bearing": "yes", "ext": ".ttf",
                                      "text_layer": ""})
        self.assertEqual("SKIPPED_NO_CONTENT", state)

    def test_multi_subject_path_is_unassigned(self):
        self.assertEqual("UNASSIGNED", es._subject("全科卷/数学+物理合订.docx"))

    def test_deep_subject_keyword_found(self):
        self.assertEqual("MATH", es._subject("2026年新高考资料/一轮复习/2026体育单招数学讲义.docx"))


class TransitionTest(unittest.TestCase):
    def _table(self, tmp):
        (tmp / "extraction_state.csv").write_text(
            "rel_path,subject,ext,state,output_ref,tool,note,updated_at\n"
            "a.docx,MATH,.docx,PENDING,,,init,2026-09-18 00:00:00\n"
            "b.pdf,MATH,.pdf,PENDING_SCANNED,,,init,2026-09-18 00:00:00\n"
            "c.pdf,MATH,.pdf,ERROR,,phase2,崩了,2026-09-18 00:00:00\n",
            encoding="utf-8")
        return tmp / "extraction_state.csv"

    def test_forward_mark(self):
        import tempfile, pathlib
        with tempfile.TemporaryDirectory() as d:
            path = self._table(pathlib.Path(d))
            es.mark({"a.docx": ("EXTRACTED", "m1,m2")}, "phase2", path)
            row = es.load_states(path)["a.docx"]
            self.assertEqual("EXTRACTED", row["state"])
            self.assertEqual("m1,m2", row["output_ref"])

    def test_error_can_retry(self):
        import tempfile, pathlib
        with tempfile.TemporaryDirectory() as d:
            path = self._table(pathlib.Path(d))
            es.mark({"c.pdf": ("PENDING_SCANNED", "")}, "retry", path)
            self.assertEqual("PENDING_SCANNED", es.load_states(path)["c.pdf"]["state"])

    def test_illegal_transition_raises(self):
        import tempfile, pathlib
        with tempfile.TemporaryDirectory() as d:
            path = self._table(pathlib.Path(d))
            es.mark({"a.docx": ("ERROR", "")}, "phase2", path)  # 合法迁移
            with self.assertRaises(ValueError):
                es.mark({"a.docx": ("EXTRACTED", "x"), }, "phase2", path)  # ERROR 只能回 PENDING*
            with self.assertRaises(ValueError):
                es.mark({"nope.docx": ("REJECTED", "")}, "phase2", path)   # 不存在


if __name__ == "__main__":
    unittest.main()
