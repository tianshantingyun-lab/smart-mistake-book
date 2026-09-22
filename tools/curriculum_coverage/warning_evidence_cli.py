"""Command-line entry point for warning-module source evidence."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable

from .io import check_output, resolve_project_path, write_output
from .model import read_json
from .review import build_warning_evidence


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build complete source evidence for ambiguous curriculum modules."
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
        "--candidate-artifact",
        type=Path,
        default=Path(
            "knowledge-production/knowledge-coverage-candidates-2025-v1.json"
        ),
    )
    parser.add_argument(
        "--pdf-directory",
        type=Path,
        default=Path(".artifacts/research/high-school-standards-2025"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "knowledge-production/curriculum-warning-source-evidence-2025-v1.json"
        ),
    )
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--write", action="store_true")
    action.add_argument("--check", action="store_true")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    project_root = args.project_root.resolve()
    try:
        manifest = read_json(
            resolve_project_path(project_root, args.manifest, "manifest")
        )
        register = read_json(
            resolve_project_path(
                project_root,
                args.source_register,
                "source register",
            )
        )
        candidates = read_json(
            resolve_project_path(
                project_root,
                args.candidate_artifact,
                "candidate artifact",
            )
        )
        pdf_directory = resolve_project_path(
            project_root,
            args.pdf_directory,
            "PDF directory",
        )
        output = resolve_project_path(project_root, args.output, "evidence output")
        artifact, summary = build_warning_evidence(
            project_root,
            pdf_directory,
            manifest,
            register,
            candidates,
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
