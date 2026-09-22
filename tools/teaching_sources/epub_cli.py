"""Command-line entry point for open teaching EPUB audit."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path
from typing import Iterable

from curriculum_coverage.io import check_output, resolve_project_path, write_output
from curriculum_coverage.model import read_json

from .epub_audit import audit_manifest


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Audit acquired open-teaching EPUB structure, rights and markers."
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
            "knowledge-production/source-register-2025-v1.json"
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("knowledge-production/open-teaching-epub-audit-2026-v1.json"),
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
        report, summary = audit_manifest(project_root, manifest, register)
        output = resolve_project_path(project_root, args.output, "EPUB audit output")
        if args.write:
            write_output(output, report)
        else:
            check_output(output, report)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0
