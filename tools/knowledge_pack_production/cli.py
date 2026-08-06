"""Command-line interface for the offline formal knowledge-pack gate."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Iterable

from curriculum_coverage.io import canonical_json, resolve_project_path

from .bounded_io import (
    MAX_GIT_MARKER_BYTES,
    MAX_JSON_FILE_BYTES,
    atomic_write_text,
    read_bounded_json_object,
    read_bounded_text,
    read_fingerprint_key,
    reject_path_overlaps,
)
from .formal_pack import (
    compile_formal_pack,
    production_status,
    validate_formal_build_inputs_before_processing,
)
from .holdout_gate import (
    FINGERPRINT_KEY_PATH_ENV,
    validate_content_input_manifest_before_processing,
    validate_full_holdout_manifest_before_processing,
    validate_holdout_release_registration_before_processing,
)


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Validate human-review evidence and compile an offline formal "
            "knowledge pack without changing runtime activation."
        )
    )
    default_root = Path(__file__).resolve().parent.parent.parent
    parser.add_argument("--project-root", type=Path, default=default_root)
    parser.add_argument(
        "--coverage-ledger",
        type=Path,
        default=Path(
            "knowledge-production/knowledge-coverage-ledger-2025-v1.json"
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
        "--teaching-inventory",
        type=Path,
        default=Path(
            "knowledge-production/open-teaching-review-inventory-2026-v1.json"
        ),
    )
    parser.add_argument(
        "--teaching-decisions",
        type=Path,
        default=Path(
            "knowledge-production/"
            "open-teaching-human-review-decisions-2026-v1.json"
        ),
    )
    parser.add_argument(
        "--pack-review",
        type=Path,
        default=Path(
            "knowledge-production/"
            "formal-knowledge-pack-human-review-2026-v1.json"
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "knowledge-production/formal-high-school-knowledge-pack-2026-v1.json"
        ),
    )
    parser.add_argument(
        "--content-input-manifest",
        type=Path,
        help=(
            "Versioned HMAC manifest for the five formal compiler inputs. "
            "Required for --check and --write."
        ),
    )
    parser.add_argument(
        "--holdout-manifest",
        type=Path,
        help=(
            "Signed private acceptance-holdout manifest containing only "
            "irreversible fingerprints. Required for --check and --write."
        ),
    )
    parser.add_argument(
        "--holdout-release-registration",
        type=Path,
        help=(
            "Content-addressed holdout release registration whose fingerprint "
            "is pinned by the compiler. Required for --check and --write."
        ),
    )
    parser.add_argument(
        "--holdout-fingerprint-key",
        type=Path,
        help=(
            "HMAC key path outside the repository. If omitted, read "
            f"{FINGERPRINT_KEY_PATH_ENV}."
        ),
    )
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--status", action="store_true")
    action.add_argument("--check", action="store_true")
    action.add_argument("--write", action="store_true")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    root = _resolve_repository_root(args.project_root.resolve())
    try:
        ledger = _read(root, args.coverage_ledger, "coverage ledger")
        register = _read(root, args.source_register, "source register")
        inventory = _read(root, args.teaching_inventory, "teaching inventory")
        decisions = _read(root, args.teaching_decisions, "teaching decisions")
        review_path = resolve_project_path(root, args.pack_review, "pack review")
        review = (
            _read_json_path(review_path, "pack review")
            if review_path.is_file()
            else None
        )
        if not args.status and review is not None:
            validate_formal_build_inputs_before_processing(
                {
                    "coverageLedger": ledger,
                    "sourceRegister": register,
                    "teachingInventory": inventory,
                    "teachingDecisions": decisions,
                    "packReviewContent": review,
                }
            )
        holdout_kwargs, holdout_inputs = _load_holdout_gate(
            root,
            args,
            require_complete=not args.status,
        )

        if args.status:
            report = production_status(
                ledger,
                register,
                inventory,
                decisions,
                review,
                **holdout_kwargs,
            )
            report["formalPackOutputExists"] = resolve_project_path(
                root,
                args.output,
                "formal pack output",
            ).is_file()
            sys.stdout.write(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n"
            )
            return 0

        if review is None:
            raise ValueError(
                f"Formal pack human-review artifact does not exist: {review_path}"
            )
        artifact, summary = compile_formal_pack(
            ledger,
            register,
            inventory,
            decisions,
            review,
            **holdout_kwargs,
        )
        output = resolve_project_path(root, args.output, "formal pack output")
        protected_inputs = {
            resolve_project_path(root, args.coverage_ledger, "coverage ledger"),
            resolve_project_path(root, args.source_register, "source register"),
            resolve_project_path(
                root,
                args.teaching_inventory,
                "teaching inventory",
            ),
            resolve_project_path(
                root,
                args.teaching_decisions,
                "teaching decisions",
            ),
            review_path,
            *holdout_inputs,
        }
        _reject_output_input_overlaps(output, protected_inputs)
        if args.write:
            _reject_output_input_overlaps(output, protected_inputs)
            atomic_write_text(output, canonical_json(artifact))
        else:
            _check_output(output, artifact)
        sys.stdout.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        return 0
    except (OSError, ValueError) as error:
        sys.stderr.write(f"ERROR: {error}\n")
        return 1


def _read(root: Path, value: Path, label: str) -> dict:
    return _read_json_path(resolve_project_path(root, value, label), label)


def _load_holdout_gate(
    root: Path,
    args: argparse.Namespace,
    *,
    require_complete: bool,
) -> tuple[dict[str, Any], set[Path]]:
    content_path = _resolve_optional_path(root, args.content_input_manifest)
    holdout_path = _resolve_optional_path(root, args.holdout_manifest)
    registration_path = _resolve_optional_path(
        root,
        args.holdout_release_registration,
    )
    configured_key = args.holdout_fingerprint_key
    if configured_key is None:
        environment_path = os.environ.get(FINGERPRINT_KEY_PATH_ENV)
        configured_key = Path(environment_path) if environment_path else None
    key_path = (
        None
        if configured_key is None
        else configured_key.expanduser().resolve(strict=False)
    )

    required = {
        "content-input manifest": content_path,
        "signed holdout manifest": holdout_path,
        "pinned holdout release registration": registration_path,
        "holdout fingerprint key": key_path,
    }
    missing = [label for label, path in required.items() if path is None]
    if require_complete and missing:
        raise ValueError(
            "Formal release holdout isolation is incomplete; missing "
            + ", ".join(missing)
        )

    content_manifest = (
        None
        if content_path is None
        else _read_json_path(content_path, "content-input manifest")
    )
    holdout_manifest = (
        None
        if holdout_path is None
        else _read_json_path(holdout_path, "signed holdout manifest")
    )
    release_registration = (
        None
        if registration_path is None
        else _read_json_path(
            registration_path,
            "holdout release registration",
        )
    )

    if content_manifest is not None:
        validate_content_input_manifest_before_processing(content_manifest)
    if holdout_manifest is not None:
        validate_full_holdout_manifest_before_processing(
            holdout_manifest,
            require_nonempty=require_complete,
        )
    if release_registration is not None:
        validate_holdout_release_registration_before_processing(
            release_registration
        )

    fingerprint_key = None
    if key_path is not None:
        if any(
            _is_relative_to(key_path, boundary)
            for boundary in _repository_boundaries(root)
        ):
            raise ValueError(
                "Holdout fingerprint keys must be stored outside the repository"
            )
        fingerprint_key = read_fingerprint_key(key_path)

    protected_inputs = {
        path
        for path in (content_path, holdout_path, registration_path, key_path)
        if path is not None
    }
    return (
        {
            "content_input_manifest": content_manifest,
            "holdout_manifest": holdout_manifest,
            "holdout_fingerprint_key": fingerprint_key,
            "holdout_release_registration": release_registration,
        },
        protected_inputs,
    )


def _resolve_optional_path(root: Path, value: Path | None) -> Path | None:
    if value is None:
        return None
    candidate = value.expanduser()
    if not candidate.is_absolute():
        candidate = root / candidate
    return candidate.resolve(strict=False)


def _resolve_repository_root(start: Path) -> Path:
    if not start.is_dir():
        raise ValueError("Project root must be an existing directory")
    for candidate in (start, *start.parents):
        git_marker = candidate / ".git"
        if git_marker.is_file() or git_marker.is_dir():
            return candidate.resolve()
    raise ValueError("Project root is not inside a Git repository")


def _read_json_path(path: Path, label: str) -> dict[str, Any]:
    return read_bounded_json_object(path, label)


def _check_output(path: Path, value: dict[str, Any]) -> None:
    expected = canonical_json(value)
    actual = read_bounded_text(
        path,
        "generated formal pack output",
        max_bytes=MAX_JSON_FILE_BYTES,
    )
    if actual != expected:
        raise ValueError(f"Generated output is stale: {path}")


def _reject_output_input_overlaps(
    output: Path,
    protected_inputs: Iterable[Path],
) -> None:
    paths = {"formal pack output": output}
    paths.update(
        {
            f"protected input {index}": path
            for index, path in enumerate(
                sorted(protected_inputs, key=lambda candidate: str(candidate)),
                start=1,
            )
        }
    )
    reject_path_overlaps(
        paths,
        error_prefix="Formal pack output must not overwrite an input artifact",
    )


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _repository_boundaries(worktree_root: Path) -> set[Path]:
    boundaries = {worktree_root.resolve()}
    git_marker = worktree_root / ".git"
    if not git_marker.is_file():
        return boundaries
    try:
        marker = read_bounded_text(
            git_marker,
            "Git worktree marker",
            max_bytes=MAX_GIT_MARKER_BYTES,
        ).strip()
    except ValueError:
        return boundaries
    prefix = "gitdir:"
    if not marker.casefold().startswith(prefix):
        return boundaries
    git_directory = Path(marker[len(prefix) :].strip())
    if not git_directory.is_absolute():
        git_directory = (worktree_root / git_directory).resolve()
    else:
        git_directory = git_directory.resolve()
    for parent in (git_directory, *git_directory.parents):
        if parent.name == ".git":
            boundaries.add(parent.parent.resolve())
            break
    return boundaries
