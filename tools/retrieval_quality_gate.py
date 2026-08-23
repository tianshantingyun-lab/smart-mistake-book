#!/usr/bin/env python3
"""Knowledge retrieval quality gate (acceptance audit §11.5 scaffold).

Computes Recall@5 / MRR / nDCG@5 over an offline golden set against a
ranked-results file produced by the knowledge retrieval pipeline, and
exits non-zero when any configured threshold is not met.

Usage:
    python3 tools/retrieval_quality_gate.py \
        --golden tools/retrieval_quality_golden_set.json \
        --results path/to/retrieval_results.json \
        [--recall5-threshold 0.8] [--mrr-threshold 0.6] [--ndcg5-threshold 0.6]

Results file format (one ranked list per golden query id, best first):
    {"<query_id>": ["knowledge_node_id", ...]}

NOTE: this is the code/tooling scaffold only. The golden set ships with a
few exemplar queries; real content expansion and human double-review of
knowledge nodes are content-team work items and are intentionally NOT
part of this script (audit §11.3 / PR-10 scope).

Exit codes: 0 = all thresholds met, 1 = threshold(s) missed, 2 = usage or
input error (e.g. results file missing or malformed).
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path

K = 5

DEFAULT_GOLDEN = Path(__file__).resolve().parent / "retrieval_quality_golden_set.json"
DEFAULT_THRESHOLDS = {"recall5": 0.8, "mrr": 0.6, "ndcg5": 0.6}


@dataclass(frozen=True)
class GoldenQuery:
    query_id: str
    query_text: str
    expected_knowledge_ids: frozenset[str]


@dataclass(frozen=True)
class GateThresholds:
    recall5: float
    mrr: float
    ndcg5: float


@dataclass(frozen=True)
class MetricsReport:
    recall5: float
    mrr: float
    ndcg5: float
    evaluated_query_count: int
    missing_query_count: int

    def meets(self, thresholds: GateThresholds) -> bool:
        return (
            self.recall5 >= thresholds.recall5
            and self.mrr >= thresholds.mrr
            and self.ndcg5 >= thresholds.ndcg5
        )


def load_golden(path: Path) -> list[GoldenQuery]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    queries = []
    for entry in raw.get("queries", []):
        expected = entry.get("expected_knowledge_ids", [])
        if not entry.get("query_id") or not entry.get("query_text") or not expected:
            raise ValueError(
                f"golden query entry missing required fields: {entry!r}"
            )
        queries.append(
            GoldenQuery(
                query_id=entry["query_id"],
                query_text=entry["query_text"],
                expected_knowledge_ids=frozenset(expected),
            )
        )
    if not queries:
        raise ValueError("golden set contains no queries")
    return queries


def recall_at_k(expected: frozenset[str], ranked: list[str], k: int = K) -> float:
    """Share of expected knowledge ids present in the top-k ranked list."""
    if not expected:
        return 0.0
    hits = len(expected.intersection(ranked[:k]))
    return hits / len(expected)


def reciprocal_rank(expected: frozenset[str], ranked: list[str]) -> float:
    """1/rank of the first expected id in the ranked list, else 0."""
    for index, knowledge_id in enumerate(ranked):
        if knowledge_id in expected:
            return 1.0 / (index + 1)
    return 0.0


def ndcg_at_k(expected: frozenset[str], ranked: list[str], k: int = K) -> float:
    """nDCG@k with binary relevance (1 for expected ids, 0 otherwise)."""
    if not expected:
        return 0.0
    dcg = sum(
        1.0 / math.log2(position + 2)
        for position, knowledge_id in enumerate(ranked[:k])
        if knowledge_id in expected
    )
    ideal_hits = min(len(expected), k)
    idcg = sum(1.0 / math.log2(position + 2) for position in range(ideal_hits))
    if idcg == 0.0:
        return 0.0
    return dcg / idcg


def evaluate(
    golden: list[GoldenQuery],
    results: dict[str, list[str]],
) -> MetricsReport:
    recall_values: list[float] = []
    reciprocal_ranks: list[float] = []
    ndcg_values: list[float] = []
    missing = 0
    for query in golden:
        ranked = results.get(query.query_id)
        if ranked is None:
            # No retrieval output for this query counts as a total miss.
            missing += 1
            ranked = []
        recall_values.append(recall_at_k(query.expected_knowledge_ids, ranked))
        reciprocal_ranks.append(reciprocal_rank(query.expected_knowledge_ids, ranked))
        ndcg_values.append(ndcg_at_k(query.expected_knowledge_ids, ranked))
    count = len(golden)
    return MetricsReport(
        recall5=sum(recall_values) / count,
        mrr=sum(reciprocal_ranks) / count,
        ndcg5=sum(ndcg_values) / count,
        evaluated_query_count=count,
        missing_query_count=missing,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Knowledge retrieval quality gate")
    parser.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--recall5-threshold", type=float, default=DEFAULT_THRESHOLDS["recall5"])
    parser.add_argument("--mrr-threshold", type=float, default=DEFAULT_THRESHOLDS["mrr"])
    parser.add_argument("--ndcg5-threshold", type=float, default=DEFAULT_THRESHOLDS["ndcg5"])
    parser.add_argument("--report-json", type=Path, default=None)
    args = parser.parse_args(argv)

    if not args.golden.is_file():
        print(f"error: golden set not found: {args.golden}", file=sys.stderr)
        return 2
    if not args.results.is_file():
        print(
            "error: retrieval results file not found: "
            f"{args.results}. Wire the retrieval pipeline to emit ranked "
            "results per golden query before running this gate.",
            file=sys.stderr,
        )
        return 2

    try:
        golden = load_golden(args.golden)
        results_raw = json.loads(args.results.read_text(encoding="utf-8"))
        if not isinstance(results_raw, dict):
            raise ValueError(
                "results file must be a JSON object mapping query_id to a ranked list"
            )
        results = {
            query_id: list(ranked) for query_id, ranked in results_raw.items()
        }
    except (ValueError, json.JSONDecodeError) as failure:
        print(f"error: malformed gate input: {failure}", file=sys.stderr)
        return 2

    thresholds = GateThresholds(
        recall5=args.recall5_threshold,
        mrr=args.mrr_threshold,
        ndcg5=args.ndcg5_threshold,
    )
    report = evaluate(golden, results)

    lines = [
        "knowledge retrieval quality gate (audit §11.5)",
        f"  queries evaluated : {report.evaluated_query_count}"
        f" ({report.missing_query_count} without retrieval results)",
        f"  Recall@5          : {report.recall5:.3f}"
        f" (threshold {thresholds.recall5:.3f})",
        f"  MRR               : {report.mrr:.3f}"
        f" (threshold {thresholds.mrr:.3f})",
        f"  nDCG@5            : {report.ndcg5:.3f}"
        f" (threshold {thresholds.ndcg5:.3f})",
    ]
    passed = report.meets(thresholds)
    lines.append("  verdict           : PASS" if passed else "  verdict           : FAIL")
    print("\n".join(lines))

    if args.report_json is not None:
        args.report_json.write_text(
            json.dumps(
                {
                    "recall5": report.recall5,
                    "mrr": report.mrr,
                    "ndcg5": report.ndcg5,
                    "evaluated_query_count": report.evaluated_query_count,
                    "missing_query_count": report.missing_query_count,
                    "thresholds": {
                        "recall5": thresholds.recall5,
                        "mrr": thresholds.mrr,
                        "ndcg5": thresholds.ndcg5,
                    },
                    "passed": passed,
                },
                indent=2,
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
