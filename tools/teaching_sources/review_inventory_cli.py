"""Command-line entry point for teaching-source review inventory generation."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path
from typing import Iterable

from curriculum_coverage.io import check_output, resolve_project_path, write_output
from curriculum_coverage.model import read_json

from .review_inventory import build_review_inventory


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build locator-only teaching-support candidates for human review."
    )
    default_root = Path(__file__).resolve().parent.parent.parent
    parser.add_argument("--project-root", type=Path, default=default_root)
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
    parser.add_argument(
        "--inventory-output",
        type=Path,
        default=Path(
            "knowledge-production/open-teaching-review-inventory-2026-v1.json"
        ),
    )
    parser.add_argument(
        "--decisions-output",
        type=Path,
        default=Path(
            "knowledge-production/open-teaching-human-review-decisions-2026-v1.json"
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
            resolve_project_path(project_root, args.manifest, "EPUB manifest")
        )
        register = read_json(
            resolve_project_path(
                project_root,
                args.source_register,
                "knowledge source register",
            )
        )
        inventory, decisions, summary = build_review_inventory(
            project_root,
            manifest,
            register,
        )
        outputs = (
            (
                resolve_project_path(
                    project_root,
                    args.inventory_output,
                    "teaching review inventory",
                ),
                inventory,
            ),
            (
                resolve_project_path(
                    project_root,
                    args.decisions_output,
                    "teaching review decisions",
                ),
                decisions,
            ),
        )
        for path, payload in outputs:
            if args.write:
                write_output(path, payload)
            else:
                check_output(path, payload)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0
