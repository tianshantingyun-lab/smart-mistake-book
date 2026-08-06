"""Read-only, non-publishing CLI for textbook source review decisions."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable

from curriculum_coverage.io import resolve_project_path
from curriculum_coverage.model import read_json

from .source_governance import audit_review_decision, verify_source_artifact_content


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Audit a textbook source review decision without publishing it."
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent.parent,
    )
    parser.add_argument("--source-artifact", type=Path, required=True)
    parser.add_argument(
        "--content-artifact",
        type=Path,
        required=True,
        help="Project-local acquired bytes pinned by the source artifact.",
    )
    parser.add_argument("--decisions", type=Path, required=True)
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    project_root = args.project_root.resolve()
    try:
        source_artifact = read_json(
            resolve_project_path(
                project_root,
                args.source_artifact,
                "source artifact",
            )
        )
        decisions = read_json(
            resolve_project_path(
                project_root,
                args.decisions,
                "source review decision",
            )
        )
        content_path = resolve_project_path(
            project_root,
            args.content_artifact,
            "source content artifact",
        )
        verify_source_artifact_content(source_artifact, content_path)
        summary = {
            **audit_review_decision(source_artifact, decisions),
            "contentArtifactVerified": True,
        }
    except (OSError, ValueError) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
