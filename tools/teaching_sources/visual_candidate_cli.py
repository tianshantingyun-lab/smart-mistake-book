"""CLI for the locator-only open visual-candidate inventory."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path
from typing import Iterable

from curriculum_coverage.io import check_output, resolve_project_path, write_output
from curriculum_coverage.model import read_json

from .visual_candidate_inventory import build_visual_candidate_inventory


def build_argument_parser() -> argparse.ArgumentParser:
    default_root = Path(__file__).resolve().parent.parent.parent
    parser = argparse.ArgumentParser(
        description=(
            "Build a rights-review-required locator inventory of complex EPUB visuals."
        )
    )
    parser.add_argument("--project-root", type=Path, default=default_root)
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(
            "knowledge-production/open-teaching-epub-manifest-2026-v1.json"
        ),
    )
    parser.add_argument(
        "--source-register",
        type=Path,
        default=Path(
            "core/data/src/main/resources/knowledge/source-register-2025-v1.json"
        ),
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--summary-output",
        type=Path,
        help="Write/check the redacted deterministic scan summary as a separate gate.",
    )
    parser.add_argument("--max-candidates", type=int, default=120)
    parser.add_argument("--max-per-source", type=int, default=24)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--write", action="store_true")
    action.add_argument("--check", action="store_true")
    return parser


def _output_path(project_root: Path, value: Path, *, writing: bool) -> Path:
    path = (value if value.is_absolute() else project_root / value).resolve()
    if not writing:
        return path
    artifacts_root = (project_root / ".artifacts").resolve()
    try:
        path.relative_to(project_root)
    except ValueError:
        return path
    try:
        path.relative_to(artifacts_root)
    except ValueError as error:
        raise ValueError(
            "Visual candidate output must be outside the project or under .artifacts"
        ) from error
    return path


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    project_root = args.project_root.resolve()
    artifact_root = args.artifact_root.resolve()
    try:
        manifest = read_json(
            resolve_project_path(project_root, args.manifest, "EPUB manifest")
        )
        register = read_json(
            resolve_project_path(
                project_root,
                args.source_register,
                "knowledge source register",
            )
        )
        inventory, summary = build_visual_candidate_inventory(
            artifact_root,
            manifest,
            register,
            candidate_limit=args.max_candidates,
            per_source_limit=args.max_per_source,
        )
        output = _output_path(project_root, args.output, writing=args.write)
        if args.write:
            write_output(output, inventory)
        else:
            check_output(output, inventory)
        if args.summary_output is not None:
            summary_output = _output_path(
                project_root,
                args.summary_output,
                writing=args.write,
            )
            if args.write:
                write_output(summary_output, summary)
            else:
                check_output(summary_output, summary)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    except Exception as error:
        sys.stderr.write(
            f"ERROR: visual-candidate scan isolated an unexpected "
            f"{type(error).__name__}\n"
        )
        return 1
    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0
