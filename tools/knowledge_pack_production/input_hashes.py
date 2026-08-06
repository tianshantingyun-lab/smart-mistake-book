"""The single canonical JSON and SHA-256 implementation for pack production."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from typing import Any


def canonical_fingerprint(domain: str, value: Any) -> str:
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    digest = hashlib.sha256()
    digest.update(domain.encode("utf-8"))
    digest.update(b"\0")
    digest.update(canonical)
    return digest.hexdigest()


def artifact_fingerprint(value: Any) -> str:
    return canonical_fingerprint("knowledge-production-input-v1", value)


def deep_copy_json(value: Any) -> Any:
    return deepcopy(value)
