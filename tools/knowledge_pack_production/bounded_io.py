"""Bounded, fail-closed file reads for formal knowledge-pack tooling."""

from __future__ import annotations

import json
import os
import stat
import tempfile
from pathlib import Path
from typing import Any, Mapping


MAX_JSON_FILE_BYTES = 8 * 1024 * 1024
MAX_PRIVATE_KEY_FILE_BYTES = 64 * 1024
MAX_FINGERPRINT_KEY_FILE_BYTES = 4 * 1024
MAX_GENERATED_SOURCE_BYTES = 16 * 1024 * 1024
MAX_RECEIPT_FILE_BYTES = 1024 * 1024
MAX_GIT_MARKER_BYTES = 4 * 1024


def reject_path_overlaps(
    paths: Mapping[str, Path],
    *,
    error_prefix: str,
) -> None:
    """Reject lexical, ancestor, symlink, and hard-link aliases fail closed."""

    entries = [
        (label, path.expanduser().resolve(strict=False))
        for label, path in paths.items()
    ]
    identities: dict[str, tuple[int, int] | None] = {}
    for label, path in entries:
        try:
            info = os.stat(path, follow_symlinks=True)
        except FileNotFoundError:
            identities[label] = None
        except OSError:
            raise ValueError(f"Unable to inspect {label}") from None
        else:
            identities[label] = (info.st_dev, info.st_ino)
    for index, (left_label, left) in enumerate(entries):
        for right_label, right in entries[index + 1 :]:
            same_identity = (
                identities[left_label] is not None
                and identities[left_label] == identities[right_label]
            )
            if (
                left == right
                or left in right.parents
                or right in left.parents
                or same_identity
            ):
                raise ValueError(
                    f"{error_prefix}: {left_label} and {right_label}"
                )


def atomic_write_text(path: Path, value: str) -> None:
    """Replace one text file without truncating an existing hard-link target."""

    path.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    )
    temporary = Path(handle.name)
    try:
        with handle:
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def read_bounded_bytes(path: Path, label: str, *, max_bytes: int) -> bytes:
    """Read at most ``max_bytes`` after proving the target is a regular file."""

    if isinstance(max_bytes, bool) or not isinstance(max_bytes, int) or max_bytes < 1:
        raise ValueError("File byte budget must be a positive integer")
    try:
        before = path.stat()
    except OSError:
        raise ValueError(f"Unable to read {label}") from None
    if not stat.S_ISREG(before.st_mode):
        raise ValueError(f"{label} must be a regular file")
    if before.st_size > max_bytes:
        raise ValueError(f"{label} exceeds the file byte budget")
    try:
        value, opened_stat = _read_file_chunk(path, max_bytes + 1)
    except OSError:
        raise ValueError(f"Unable to read {label}") from None
    if not stat.S_ISREG(opened_stat.st_mode):
        raise ValueError(f"{label} must be a regular file")
    if opened_stat.st_size > max_bytes or len(value) > max_bytes:
        raise ValueError(f"{label} exceeds the file byte budget")
    if (
        opened_stat.st_dev != before.st_dev
        or opened_stat.st_ino != before.st_ino
        or opened_stat.st_size != before.st_size
        or len(value) != opened_stat.st_size
    ):
        raise ValueError(f"{label} changed while it was being read")
    return value


def read_bounded_json_object(
    path: Path,
    label: str,
    *,
    max_bytes: int = MAX_JSON_FILE_BYTES,
) -> dict[str, Any]:
    raw = read_bounded_bytes(path, label, max_bytes=max_bytes)
    _preflight_json_object_bytes(raw, label)
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError(f"Unable to read {label}") from None
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def read_bounded_text(
    path: Path,
    label: str,
    *,
    max_bytes: int,
) -> str:
    raw = read_bounded_bytes(path, label, max_bytes=max_bytes)
    try:
        return raw.decode("utf-8")
    except UnicodeError:
        raise ValueError(f"Unable to read {label}") from None


def read_private_key_pem(path: Path, label: str = "formal signing key") -> bytes:
    value = read_bounded_bytes(
        path,
        label,
        max_bytes=MAX_PRIVATE_KEY_FILE_BYTES,
    )
    if not value.startswith(b"-----BEGIN ") or b"PRIVATE KEY-----" not in value[:128]:
        raise ValueError(f"{label} is not PEM private-key material")
    return value


def read_fingerprint_key(
    path: Path,
    label: str = "holdout fingerprint key",
) -> bytes:
    value = read_bounded_bytes(
        path,
        label,
        max_bytes=MAX_FINGERPRINT_KEY_FILE_BYTES,
    )
    if len(value) < 32:
        raise ValueError(f"{label} must contain at least 32 bytes")
    return value


def _preflight_json_object_bytes(value: bytes, label: str) -> None:
    if not value:
        raise ValueError(f"Unable to read {label}")
    start = 0
    end = len(value) - 1
    whitespace = b" \t\r\n"
    while start <= end and value[start] in whitespace:
        start += 1
    while end >= start and value[end] in whitespace:
        end -= 1
    if start > end or value[start] != ord("{") or value[end] != ord("}"):
        raise ValueError(f"{label} must be a JSON object")


def _read_file_chunk(path: Path, byte_count: int) -> tuple[bytes, os.stat_result]:
    """Single bounded read seam used by oversized-file sentinel tests."""

    with path.open("rb") as handle:
        opened_stat = os.fstat(handle.fileno())
        return handle.read(byte_count), opened_stat
