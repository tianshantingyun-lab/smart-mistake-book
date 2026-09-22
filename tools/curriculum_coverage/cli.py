"""Command-line entry point for deterministic coverage extraction."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable

from .artifacts import build_outputs
from .io import check_output, resolve_project_path, write_output
from .model import read_json


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build an auditable draft coverage map from nine 2025 curriculum PDFs."
    )
    default_root = Path(__file__).resolve().parent.parent.parent
    parser.add_argument("--project-root", type=Path, default=default_root)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(
            "knowledge-production/"
            "curriculum-coverage-extraction-manifest-2025-v1.json"
        ),
    )
    parser.add_argument(
        "--source-register",
        type=Path,
        default=Path(
            "knowledge-production/"
            "source-register-2025-v1.json"
        ),
    )
    parser.add_argument(
        "--pdf-directory",
        type=Path,
        default=Path(".artifacts/research/high-school-standards-2025"),
    )
    parser.add_argument(
        "--candidate-output",
        type=Path,
        default=Path(
            "knowledge-production/knowledge-coverage-candidates-2025-v1.json"
        ),
    )
    parser.add_argument(
        "--ledger-output",
        type=Path,
        default=Path("knowledge-production/knowledge-coverage-ledger-2025-v1.json"),
    )
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--write", action="store_true")
    action.add_argument("--check", action="store_true")
    return parser


def _resolve_paths(args: argparse.Namespace) -> tuple[Path, Path, Path, Path, Path]:
    project_root = args.project_root.resolve()
    return (
        project_root,
        resolve_project_path(project_root, args.manifest, "manifest"),
        resolve_project_path(project_root, args.source_register, "source register"),
        resolve_project_path(project_root, args.pdf_directory, "PDF directory"),
        resolve_project_path(project_root, args.candidate_output, "candidate output"),
    )


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    try:
        (
            project_root,
            manifest_path,
            register_path,
            pdf_directory,
            candidate_output,
        ) = _resolve_paths(args)
        ledger_output = resolve_project_path(
            project_root,
            args.ledger_output,
            "ledger output",
        )
        candidates, ledger, summary = build_outputs(
            project_root,
            pdf_directory,
            read_json(manifest_path),
            read_json(register_path),
        )
        if args.write:
            write_output(candidate_output, candidates)
            write_output(ledger_output, ledger)
        else:
            check_output(candidate_output, candidates)
            check_output(ledger_output, ledger)
    except (OSError, ValueError) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1

    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0
