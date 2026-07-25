"""Command-line entry point for warning-module decomposition proposal audit."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable

from .io import resolve_project_path
from .model import read_json
from .proposals import validate_decomposition_proposals


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Audit AI-authored module decompositions against source evidence."
    )
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
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    project_root = args.project_root.resolve()
    try:
        evidence = read_json(
            resolve_project_path(
                project_root,
                args.source_evidence,
                "warning source evidence",
            )
        )
        proposals = read_json(
            resolve_project_path(
                project_root,
                args.proposals,
                "decomposition proposals",
            )
        )
        summary = validate_decomposition_proposals(proposals, evidence)
    except (OSError, ValueError) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0
