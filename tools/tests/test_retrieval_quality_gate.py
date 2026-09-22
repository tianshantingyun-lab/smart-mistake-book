"""Tests for the knowledge retrieval quality gate scaffold (audit §11.5)."""

from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import retrieval_quality_gate as rqg

GOLDEN = [
    rqg.GoldenQuery(
        query_id="q1",
        query_text="示例查询一",
        expected_knowledge_ids=frozenset({"math:monotonicity"}),
    ),
    rqg.GoldenQuery(
        query_id="q2",
        query_text="示例查询二",
        expected_knowledge_ids=frozenset({"physics:newton-2", "math:monotonicity"}),
    ),
]


def write_temp_json(payload) -> Path:
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False, encoding="utf-8"
    )
    json.dump(payload, handle, ensure_ascii=False)
    handle.close()
    return Path(handle.name)


class RecallAtKTest(unittest.TestCase):
    def test_full_hit_scores_one(self) -> None:
        self.assertEqual(
            1.0,
            rqg.recall_at_k(frozenset({"a"}), ["a", "b", "c", "d", "e"]),
        )

    def test_miss_scores_zero(self) -> None:
        self.assertEqual(0.0, rqg.recall_at_k(frozenset({"a"}), ["b", "c"]))

    def test_partial_hit_is_fractional(self) -> None:
        self.assertEqual(
            0.5,
            rqg.recall_at_k(frozenset({"a", "b"}), ["a", "c", "d", "e", "f"]),
        )

    def test_hits_beyond_k_are_ignored(self) -> None:
        self.assertEqual(
            0.0,
            rqg.recall_at_k(frozenset({"a"}), ["b", "c", "d", "e", "f", "a"]),
        )


class ReciprocalRankTest(unittest.TestCase):
    def test_first_rank_scores_one(self) -> None:
        self.assertEqual(1.0, rqg.reciprocal_rank(frozenset({"a"}), ["a", "b"]))

    def test_second_rank_scores_half(self) -> None:
        self.assertEqual(0.5, rqg.reciprocal_rank(frozenset({"a"}), ["b", "a"]))

    def test_missing_expected_scores_zero(self) -> None:
        self.assertEqual(0.0, rqg.reciprocal_rank(frozenset({"a"}), ["b", "c"]))


class NdcgAtKTest(unittest.TestCase):
    def test_perfect_ranking_scores_one(self) -> None:
        self.assertEqual(
            1.0,
            rqg.ndcg_at_k(frozenset({"a", "b"}), ["a", "b", "c", "d", "e"]),
        )

    def test_worse_ranking_scores_below_perfect(self) -> None:
        worse = rqg.ndcg_at_k(frozenset({"a", "b"}), ["c", "d", "a", "b", "e"])
        self.assertGreater(1.0, worse)
        self.assertGreater(worse, 0.0)

    def test_no_hits_scores_zero(self) -> None:
        self.assertEqual(0.0, rqg.ndcg_at_k(frozenset({"a"}), ["b", "c", "d"]))


class EvaluateTest(unittest.TestCase):
    def test_missing_results_count_as_total_miss(self) -> None:
        report = rqg.evaluate(GOLDEN, {"q1": ["math:monotonicity"]})
        self.assertEqual(1, report.missing_query_count)
        self.assertEqual(2, report.evaluated_query_count)
        # q1 perfect, q2 absent: recall = (1.0 + 0.0) / 2.
        self.assertEqual(0.5, report.recall5)

    def test_threshold_gate_pass_and_fail(self) -> None:
        good_results = {
            "q1": ["math:monotonicity"],
            "q2": ["physics:newton-2", "math:monotonicity"],
        }
        report = rqg.evaluate(GOLDEN, good_results)
        thresholds = rqg.GateThresholds(recall5=0.8, mrr=0.6, ndcg5=0.6)
        self.assertTrue(report.meets(thresholds))

        bad_report = rqg.evaluate(GOLDEN, {})
        self.assertFalse(bad_report.meets(thresholds))


class MainExitCodeTest(unittest.TestCase):
    """main() 的退出码与 stdout 报告契约。

    每个用例都捕获 stdout：档1 门禁会扫描整轮测试的 stdout，而负向用例
    （阈值未达）的人读报告里含 `verdict : FAIL`——若放任它漏进测试套件的
    共享 stdout，门禁会被自己钉死的正常负向输出误判红（2026-09-22 档1 假红）。
    因此 verdict 行改为本类内的显式断言：行为契约不丢，共享输出不再带 FAIL。
    """

    def setUp(self) -> None:
        self.golden_path = write_temp_json(
            {
                "queries": [
                    {
                        "query_id": "q1",
                        "query_text": "示例查询",
                        "expected_knowledge_ids": ["math:monotonicity"],
                    },
                ],
            },
        )

    def run_main(self, argv: list[str]) -> tuple[int, str]:
        with contextlib.redirect_stdout(io.StringIO()) as captured:
            exit_code = rqg.main(argv)
        return exit_code, captured.getvalue()

    def test_missing_results_file_exits_two(self) -> None:
        exit_code, stdout = self.run_main(
            ["--golden", str(self.golden_path), "--results", "does-not-exist.json"],
        )
        self.assertEqual(2, exit_code)
        # 错误路径只写 stderr，stdout 不得带报告/verdict（门禁扫的就是 stdout）。
        self.assertEqual("", stdout)

    def test_passing_results_exit_zero(self) -> None:
        results_path = write_temp_json({"q1": ["math:monotonicity"]})
        exit_code, stdout = self.run_main(
            ["--golden", str(self.golden_path), "--results", str(results_path)],
        )
        self.assertEqual(0, exit_code)
        self.assertIn("verdict           : PASS", stdout)

    def test_failing_results_exit_one(self) -> None:
        results_path = write_temp_json({"q1": ["unrelated:node"]})
        exit_code, stdout = self.run_main(
            ["--golden", str(self.golden_path), "--results", str(results_path)],
        )
        self.assertEqual(1, exit_code)
        self.assertIn("verdict           : FAIL", stdout)

    def test_malformed_results_file_exits_two(self) -> None:
        results_path = write_temp_json(["not", "an", "object"])
        exit_code, stdout = self.run_main(
            ["--golden", str(self.golden_path), "--results", str(results_path)],
        )
        self.assertEqual(2, exit_code)
        self.assertEqual("", stdout)

    def test_report_json_is_written(self) -> None:
        results_path = write_temp_json({"q1": ["math:monotonicity"]})
        report_path = self.golden_path.with_name("report.json")
        exit_code, stdout = self.run_main(
            [
                "--golden",
                str(self.golden_path),
                "--results",
                str(results_path),
                "--report-json",
                str(report_path),
            ],
        )
        self.assertEqual(0, exit_code)
        self.assertIn("verdict           : PASS", stdout)
        payload = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertTrue(payload["passed"])
        self.assertEqual(1.0, payload["recall5"])


if __name__ == "__main__":
    unittest.main()
