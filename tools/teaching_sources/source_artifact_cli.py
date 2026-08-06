"""Read-only CLI for prospective textbook source artifacts."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable

from curriculum_coverage.io import resolve_project_path
from curriculum_coverage.model import read_json

from .source_governance import verify_source_artifact_content


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Audit a NON_PROMOTING textbook-edition source artifact."
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent.parent,
    )
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument(
        "--content-artifact",
        type=Path,
        required=True,
        help=(
            "Project-local acquired bytes pinned by contentLengthBytes "
            "and contentFingerprint."
        ),
    )
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    project_root = args.project_root.resolve()
    try:
        artifact = read_json(
            resolve_project_path(project_root, args.artifact, "source artifact")
        )
        content_path = resolve_project_path(
            project_root,
            args.content_artifact,
            "source content artifact",
        )
        summary = verify_source_artifact_content(artifact, content_path)
    except (OSError, ValueError) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1
    sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
