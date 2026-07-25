"""Command-line entry points for curriculum warning promotion status and output."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable

from .io import check_output, resolve_project_path, write_output
from .model import read_json
from .promotion import build_reviewed_scope, review_promotion_status


def _common_arguments(parser: argparse.ArgumentParser) -> None:
    default_root = Path(__file__).resolve().parent.parent.parent
    parser.add_argument("--project-root", type=Path, default=default_root)
    parser.add_argument(
        "--source-evidence",
        type=Path,
        default=Path(
            "knowledge-production/curriculum-warning-source-evidence-2025-v1.json"
        ),
    )
    parser.add_argument(
        "--proposals",
        type=Path,
        default=Path(
            "knowledge-production/"
            "curriculum-warning-decomposition-proposals-2025-v1.json"
        ),
    )
    parser.add_argument(
        "--decisions",
        type=Path,
        default=Path(
            "knowledge-production/"
            "curriculum-warning-human-review-decisions-2025-v1.json"
        ),
    )


def build_status_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Report the human-review promotion gate status."
    )
    _common_arguments(parser)
    return parser


def build_scope_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Emit reviewed curriculum scope after complete human review."
    )
    _common_arguments(parser)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "knowledge-production/curriculum-warning-reviewed-scope-2025-v1.json"
        ),
    )
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--write", action="store_true")
    action.add_argument("--check", action="store_true")
    return parser


def _load_inputs(args: argparse.Namespace) -> tuple[Path, dict, dict, dict]:
    project_root = args.project_root.resolve()
    evidence = read_json(
        resolve_project_path(
            project_root,
            args.source_evidence,
            "warning source evidence",
        )
    )
    proposals = read_json(
        resolve_project_path(project_root, args.proposals, "decomposition proposals")
    )
    decisions = read_json(
        resolve_project_path(project_root, args.decisions, "human review decisions")
    )
    return project_root, evidence, proposals, decisions


def status_main(argv: Iterable[str] | None = None) -> int:
    args = build_status_parser().parse_args(argv)
    try:
        _, evidence, proposals, decisions = _load_inputs(args)
        status, _, _ = review_promotion_status(evidence, proposals, decisions)
    except (OSError, ValueError) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    sys.stdout.write(json.dumps(status, ensure_ascii=False, indent=2) + "\n")
    return 0


def scope_main(argv: Iterable[str] | None = None) -> int:
    args = build_scope_parser().parse_args(argv)
    try:
        project_root, evidence, proposals, decisions = _load_inputs(args)
        artifact, summary = build_reviewed_scope(evidence, proposals, decisions)
        output = resolve_project_path(
            project_root,
            args.output,
            "reviewed scope output",
        )
        if args.write:
            write_output(output, artifact)
        else:
            check_output(output, artifact)
    except (OSError, ValueError) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0
