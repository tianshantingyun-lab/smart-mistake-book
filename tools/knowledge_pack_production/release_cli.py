"""Command-line entry point for offline formal-pack signing."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Iterable, Mapping

from .bounded_io import (
    MAX_GENERATED_SOURCE_BYTES,
    MAX_GIT_MARKER_BYTES,
    MAX_RECEIPT_FILE_BYTES,
    atomic_write_text,
    read_bounded_bytes,
    read_bounded_json_object,
    read_bounded_text,
    read_fingerprint_key,
    read_private_key_pem,
    reject_path_overlaps,
)
from .formal_pack import validate_formal_build_inputs_before_processing
from .holdout_gate import (
    FINGERPRINT_KEY_PATH_ENV,
    validate_content_input_manifest_before_processing,
    validate_full_holdout_manifest_before_processing,
    validate_holdout_release_registration_before_processing,
)
from .release_registry import (
    PRIVATE_KEY_PASSWORD_ENV,
    PRIVATE_KEY_PATH_ENV,
    GeneratedFormalRelease,
    generate_signed_release,
    validate_release_signing_registration_before_processing,
)
from .runtime_contract import validate_formal_artifact_v2


ASSET_FILE_NAME = "GeneratedFormalKnowledgePackAsset.kt"
REGISTRY_FILE_NAME = "GeneratedFormalKnowledgePackRegistry.kt"


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Sign one reviewed formal knowledge artifact and generate the "
            "Android release asset/registry. The private key is never emitted."
        ),
    )
    parser.add_argument("--formal-pack", required=True, type=Path)
    parser.add_argument("--governance", required=True, type=Path)
    parser.add_argument("--formal-build-inputs", required=True, type=Path)
    parser.add_argument("--content-input-manifest", required=True, type=Path)
    parser.add_argument("--holdout-manifest", required=True, type=Path)
    parser.add_argument(
        "--holdout-release-registration",
        required=True,
        type=Path,
    )
    parser.add_argument(
        "--release-signing-registration",
        required=True,
        type=Path,
    )
    parser.add_argument(
        "--holdout-fingerprint-key",
        type=Path,
        help=(
            "HMAC key path outside the repository. If omitted, read "
            f"{FINGERPRINT_KEY_PATH_ENV}."
        ),
    )
    parser.add_argument(
        "--private-key",
        type=Path,
        help=(
            "PEM private-key path outside the repository. If omitted, read "
            f"{PRIVATE_KEY_PATH_ENV}."
        ),
    )
    parser.add_argument("--output-directory", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    repository_root = Path(__file__).resolve().parents[2]
    private_key_path = _configured_path(
        args.private_key,
        PRIVATE_KEY_PATH_ENV,
        "Private key path was not provided through the command or environment",
    )
    holdout_key_path = _configured_path(
        args.holdout_fingerprint_key,
        FINGERPRINT_KEY_PATH_ENV,
        (
            "Holdout fingerprint key path was not provided through "
            "the command or environment"
        ),
    )
    paths = _resolve_release_paths(args, private_key_path, holdout_key_path)
    _reject_release_path_overlaps(paths)

    boundaries = _repository_boundaries(repository_root)
    if any(
        _is_relative_to(paths["private signing key"], boundary)
        for boundary in boundaries
    ):
        raise ValueError("Formal signing keys must be stored outside the repository")
    if any(
        _is_relative_to(paths["holdout fingerprint key"], boundary)
        for boundary in boundaries
    ):
        raise ValueError(
            "Holdout fingerprint keys must be stored outside the repository"
        )

    artifact = _read_json(paths["formal pack"], "formal pack")
    governance = _read_json(paths["release governance"], "release governance")
    build_inputs = _read_json(
        paths["formal build inputs"],
        "formal build inputs",
    )
    content_input_manifest = _read_json(
        paths["content-input manifest"],
        "content-input manifest",
    )
    holdout_manifest = _read_json(
        paths["signed holdout manifest"],
        "signed holdout manifest",
    )
    holdout_registration = _read_json(
        paths["holdout release registration"],
        "holdout release registration",
    )
    release_signing_registration = _read_json(
        paths["release signing registration"],
        "release signing registration",
    )

    validate_formal_build_inputs_before_processing(build_inputs)
    validate_content_input_manifest_before_processing(content_input_manifest)
    validate_full_holdout_manifest_before_processing(
        holdout_manifest,
        require_nonempty=True,
    )
    validate_holdout_release_registration_before_processing(
        holdout_registration
    )
    validate_release_signing_registration_before_processing(
        release_signing_registration
    )
    validate_formal_artifact_v2(artifact)

    private_key_pem = read_private_key_pem(paths["private signing key"])
    holdout_fingerprint_key = read_fingerprint_key(
        paths["holdout fingerprint key"]
    )
    password_text = os.environ.get(PRIVATE_KEY_PASSWORD_ENV)
    if password_text is not None and len(password_text.encode("utf-8")) > 4096:
        raise ValueError("Private signing-key password exceeds the byte budget")
    password = password_text.encode("utf-8") if password_text else None
    generated = generate_signed_release(
        artifact,
        governance,
        private_key_pem,
        password,
        holdout_build_inputs=build_inputs,
        content_input_manifest=content_input_manifest,
        holdout_manifest=holdout_manifest,
        holdout_fingerprint_key=holdout_fingerprint_key,
        holdout_release_registration=holdout_registration,
        release_signing_registration=release_signing_registration,
    )

    receipt_text = (
        json.dumps(
            generated.receipt,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )
    _require_generated_output_budgets(generated, receipt_text)
    asset_path = paths["generated asset"]
    registry_path = paths["generated registry"]
    receipt_path = paths["release receipt"]
    _reject_release_path_overlaps(paths)
    atomic_write_text(asset_path, generated.asset_kotlin)
    atomic_write_text(registry_path, generated.registry_kotlin)
    atomic_write_text(receipt_path, receipt_text)
    _verify_written_release_bundle(
        asset_path,
        registry_path,
        receipt_path,
        generated,
        receipt_text,
    )
    print(
        "Generated signed formal knowledge release "
        f"{generated.receipt['packId']} "
        f"at generation {generated.receipt['activationGeneration']}"
    )
    return 0


def _configured_path(
    explicit: Path | None,
    environment_name: str,
    missing_message: str,
) -> Path:
    if explicit is not None:
        return explicit
    configured = os.environ.get(environment_name)
    if not configured:
        raise ValueError(missing_message)
    return Path(configured)


def _resolve_release_paths(
    args: argparse.Namespace,
    private_key_path: Path,
    holdout_key_path: Path,
) -> dict[str, Path]:
    output_directory = args.output_directory.expanduser().resolve(strict=False)
    return {
        "formal pack": args.formal_pack.expanduser().resolve(strict=False),
        "release governance": args.governance.expanduser().resolve(strict=False),
        "formal build inputs": args.formal_build_inputs.expanduser().resolve(
            strict=False
        ),
        "content-input manifest": args.content_input_manifest.expanduser().resolve(
            strict=False
        ),
        "signed holdout manifest": args.holdout_manifest.expanduser().resolve(
            strict=False
        ),
        "holdout release registration": (
            args.holdout_release_registration.expanduser().resolve(strict=False)
        ),
        "release signing registration": (
            args.release_signing_registration.expanduser().resolve(strict=False)
        ),
        "private signing key": private_key_path.expanduser().resolve(strict=False),
        "holdout fingerprint key": holdout_key_path.expanduser().resolve(
            strict=False
        ),
        "generated asset": (output_directory / ASSET_FILE_NAME).resolve(
            strict=False
        ),
        "generated registry": (output_directory / REGISTRY_FILE_NAME).resolve(
            strict=False
        ),
        "release receipt": args.receipt.expanduser().resolve(strict=False),
    }


def _reject_release_path_overlaps(paths: Mapping[str, Path]) -> None:
    reject_path_overlaps(paths, error_prefix="Release paths overlap")


def _read_json(path: Path, label: str) -> dict:
    return read_bounded_json_object(path, label)


def _require_generated_output_budgets(
    generated: GeneratedFormalRelease,
    receipt_text: str,
) -> None:
    if len(generated.asset_kotlin.encode("utf-8")) > MAX_GENERATED_SOURCE_BYTES:
        raise ValueError("Generated formal asset exceeds the file byte budget")
    if len(generated.registry_kotlin.encode("utf-8")) > MAX_GENERATED_SOURCE_BYTES:
        raise ValueError("Generated formal registry exceeds the file byte budget")
    if len(receipt_text.encode("utf-8")) > MAX_RECEIPT_FILE_BYTES:
        raise ValueError("Generated release receipt exceeds the file byte budget")


def _verify_written_release_bundle(
    asset_path: Path,
    registry_path: Path,
    receipt_path: Path,
    generated: GeneratedFormalRelease,
    receipt_text: str,
) -> None:
    asset_bytes = read_bounded_bytes(
        asset_path,
        "generated formal asset",
        max_bytes=MAX_GENERATED_SOURCE_BYTES,
    )
    registry_bytes = read_bounded_bytes(
        registry_path,
        "generated formal registry",
        max_bytes=MAX_GENERATED_SOURCE_BYTES,
    )
    written_receipt_text = read_bounded_text(
        receipt_path,
        "generated release receipt",
        max_bytes=MAX_RECEIPT_FILE_BYTES,
    )
    written_receipt = read_bounded_json_object(
        receipt_path,
        "generated release receipt",
        max_bytes=MAX_RECEIPT_FILE_BYTES,
    )
    if (
        asset_bytes != generated.asset_kotlin.encode("utf-8")
        or registry_bytes != generated.registry_kotlin.encode("utf-8")
        or written_receipt_text != receipt_text
        or written_receipt != generated.receipt
        or hashlib.sha256(asset_bytes).hexdigest()
        != generated.receipt["assetSourceFingerprint"]
        or hashlib.sha256(registry_bytes).hexdigest()
        != generated.receipt["registrySourceFingerprint"]
    ):
        raise ValueError(
            "Generated release bundle does not match the signed build receipt"
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


if __name__ == "__main__":
    raise SystemExit(main())
