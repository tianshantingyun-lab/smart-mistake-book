"""Deterministic project-local JSON input and output helpers."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .model import ensure_under_project


def canonical_json(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def write_output(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(canonical_json(value), encoding="utf-8", newline="\n")


def check_output(path: Path, value: dict[str, Any]) -> None:
    if not path.is_file():
        raise ValueError(f"Expected generated output does not exist: {path}")
    if path.read_text(encoding="utf-8") != canonical_json(value):
        raise ValueError(f"Generated output is stale: {path}")


def resolve_project_path(project_root: Path, value: Path, label: str) -> Path:
    path = value if value.is_absolute() else project_root / value
    return ensure_under_project(path, project_root, label)
